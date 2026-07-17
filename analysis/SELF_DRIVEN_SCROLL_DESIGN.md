# Self-Driven Scroll Design — `animateScrollToEnd()`

## Problem recap (from LOG_ANALYSIS_FINDING.md)

`runEndScroll()` measures `maxScroll` correctly (1942) but calls
`mTabsScroll.smoothScrollTo(maxScroll, 0)`. The framework `HorizontalScrollView`
owns an internal `OverScroller` that **re-clamps the in-flight animation to the
current content width every frame**. Because the content width *shrinks* during
the ~250ms animation (LayoutTransition `CHANGING`, OSC title update, `(+)` button
visibility toggle), the scroller freezes the scroll at the new (smaller) clamp —
scrollX lands at 1634 instead of 1942, leaving the new tab + `(+)` button clipped.

The fix: **drive `scrollX` ourselves** with a `ValueAnimator`. We call
`mTabsScroll.scrollTo((int) value, 0)` every frame against a *fixed* target, so
the HSV's internal clamp cannot shorten the trip.

---

## Design constraints (must respect existing machinery)

1. **Single-owner / sequence guard** — `mScrollSeq`, `mScrollSeqPending`,
   `mScrollRunnable` already serialise all scrolls. The new self-driven scroll
   must run *inside* `runEndScroll()` (which `mScrollRunnable` already calls for
   `SCROLL_END`), never spawn a competing animation path.
2. **`mEndScrollActive` flag** — a sticky END scroll that suppresses
   `requestScroll(SCROLL_CENTRE, …)` and `onPageScrolled()` finger-follow. The
   self-driven scroll must set/clear it exactly as today.
3. **`onPageScrolled()` finger-follow** — calls `mTabsScroll.scrollTo(...)` directly
   during a user swipe. It is already fully suppressed while `mEndScrollActive` is
   true (early `return` at line 793 / 805). We must keep that suppression AND make
   the finger-follow a **canceller** of the running animator.
4. **Cancel signals**:
   - A newer scroll request advances `mScrollSeq` / replaces `mScrollSeqPending`
     and re-posts `mScrollRunnable` → that cancels the in-flight animator.
   - A user drag (`SCROLL_STATE_DRAGGING`, which calls `setEndScrollReserved(false)`)
     must cancel the running animator **immediately**.
   - Tab close (clears `mEndScrollActive`) cancels.
5. **Diagonal clamp rule during animation**:
   - If content width **grows** mid-animation → extend the fixed target to the new
     `maxScroll` (so we still reveal the `(+)` button).
   - If content width **shrinks** → do **not** shorten mid-flight (that is exactly
     the bug); clamp our own `value` to the new `maxScroll` only at `onAnimationEnd`
     so we never overshoot.

---

## New field additions

Add near the other scroll fields (around line 516):

```java
// Self-driven end-scroll animator. Replaces smoothScrollTo() so the HSV's live
// re-clamp cannot stop the trip short. Null when no self-driven scroll is running.
private android.animation.ValueAnimator mEndScrollAnimator = null;

// The widest content width observed so far during the current end-scroll. We seed
// it from the settled measured width and extend our target if the strip grows, so a
// later layout pass that widens the strip still reveals the (+) button.
private int mEndScrollMaxWidth = 0;
```

---

## `animateScrollToEnd()` (public, replaces the scroll trigger)

This is what `runEndScroll()` now calls after measuring. It kicks off the
self-driven animation from the current `scrollX` to the settled `maxScroll`.

```java
/**
 * Self-driven scroll to the absolute right end of the strip. Unlike
 * HorizontalScrollView.smoothScrollTo(), we own the animation: every frame we call
 * {@code mTabsScroll.scrollTo((int) value, 0)} against a FIXED target, so the HSV's
 * internal scroller cannot re-clamp the in-flight animation to a shrinking content
 * width (the root cause of the "stops one tab short" bug).
 *
 * @param targetX the settled maxScroll computed from the final content width
 */
private void animateScrollToEnd(int targetX) {
    if (mTabsScroll == null) return;

    // Cancel any previous self-driven scroll (e.g. a re-entrant end request).
    cancelEndScrollAnimator();

    final int startX = mTabsScroll.getScrollX();
    if (targetX <= startX) {
        // Already at/past the end or nothing to scroll — jump directly and finish.
        mTabsScroll.scrollTo(targetX, 0);
        return;
    }

    // Seed the widest-width tracker with the settled measurement; see onUpdate below
    // for how growth extends the effective target.
    mEndScrollMaxWidth = mTabsContainer != null ? mTabsContainer.getMeasuredWidth() : 0;

    final android.animation.ValueAnimator anim =
            android.animation.ValueAnimator.ofInt(startX, targetX);
    anim.setDuration(250);
    anim.setInterpolator(new AccelerateDecelerateInterpolator());
    anim.addUpdateListener(animation -> {
        if (mTabsScroll == null) return;
        int value = (int) animation.getAnimatedValue();

        // Defensive: if the strip GREW since we started, extend the target so we still
        // reach the true right end (reveal the (+) button). This is the "grow" branch of
        // the diagonal clamp rule. We do NOT shorten on shrink — that keeps the trip intact.
        int wideW = mTabsContainer != null ? mTabsContainer.getMeasuredWidth() : 0;
        if (wideW > mEndScrollMaxWidth) {
            mEndScrollMaxWidth = wideW;
            int scrollW = mTabsScroll.getWidth();
            int grownMax = Math.max(0, wideW - scrollW);
            // Re-aim the animator's remaining frames at the larger target if it is bigger.
            if (grownMax > targetX) {
                // Scale the in-flight value proportionally toward the new target.
                float frac = anim.getAnimatedFraction();
                int newTarget = grownMax;
                anim.setIntValues((int) (startX + (newTarget - startX) * frac), newTarget);
                value = (int) anim.getAnimatedValue();
            }
        }

        // We set scrollX directly. The HSV clamp cannot shorten this because we WRITE it.
        mTabsScroll.scrollTo(value, 0);
    });

    anim.addListener(new AnimatorListenerAdapter() {
        @Override
        public void onAnimationEnd(android.animation.Animator animation) {
            if (mEndScrollAnimator == animation) mEndScrollAnimator = null;
            // Shrink clamp: only at the END do we snap to the current real maxScroll so we
            // never overshoot a content that shrank mid-flight. This is safe because the
            // animation is finished; no clamp can "trap" a stopped scroll.
            if (mTabsScroll != null && mTabsContainer != null) {
                int realMax = Math.max(0,
                        mTabsContainer.getMeasuredWidth() - mTabsScroll.getWidth());
                if (mTabsScroll.getScrollX() > realMax) {
                    mTabsScroll.scrollTo(realMax, 0);
                }
            }
            mEndScrollActive = false;
            logTabScroll("animateScrollToEnd",
                    "END done scrollX=" + (mTabsScroll != null ? mTabsScroll.getScrollX() : -1)
                            + " " + scrollGeometry());
        }

        @Override
        public void onAnimationCancel(android.animation.Animator animation) {
            if (mEndScrollAnimator == animation) mEndScrollAnimator = null;
            // Cancelled by a newer request / user drag / tab close. Leave mEndScrollActive
            // as-is — the cancelling path (sequence guard, setEndScrollReserved(false),
            // updateTabs remove) owns the flag transition.
        }
    });

    mEndScrollAnimator = anim;
    anim.start();
    logTabScroll("animateScrollToEnd",
            "START startX=" + startX + " targetX=" + targetX + " " + scrollGeometry());
}

/** Cancel the running self-driven end-scroll, if any. Safe to call repeatedly. */
private void cancelEndScrollAnimator() {
    if (mEndScrollAnimator != null) {
        mEndScrollAnimator.cancel();   // fires onAnimationCancel; nulls mEndScrollAnimator
        mEndScrollAnimator = null;
    }
}
```

> Note on the "grow" branch: re-calling `setIntValues` mid-flight re-wraps the
> animator's internal range. This is correct and rarely taken (grow is the rare
> case). The common case (shrink) is simply *not* handled here — we keep going to
> the original target and only clamp at `onAnimationEnd`, which is exactly the
> desired behaviour.

---

## Replace `smoothScrollTo` in `runEndScroll()`

Lines 593-599 currently read:

```java
final int childMeasuredW = mTabsContainer.getMeasuredWidth();
final int scrollW = mTabsScroll.getWidth();
final int maxScroll = Math.max(0, childMeasuredW - scrollW);
logTabScroll("endScrollLayout", ...);
mTabsScroll.smoothScrollTo(maxScroll, 0);   // <-- remove
```

Replace the final line with the self-driven call:

```java
final int childMeasuredW = mTabsContainer.getMeasuredWidth();
final int scrollW = mTabsScroll.getWidth();
final int maxScroll = Math.max(0, childMeasuredW - scrollW);
logTabScroll("endScrollLayout",
        "childMeasuredW=" + childMeasuredW + " scrollW=" + scrollW
                + " maxScroll=" + maxScroll + " " + scrollGeometry());
// Self-driven scroll (immune to HSV live re-clamp). May also be invoked via
// scrollStripToEnd() once the new tab's label is set.
animateScrollToEnd(maxScroll);
```

`runEndScroll()` keeps the `OnGlobalLayoutListener` retry/`widthBefore` guard
unchanged — it still waits until the container has actually grown before measuring.
The only behavioural change is the *mechanism* of the scroll.

---

## Wiring into the existing flow (no structural changes)

- **`requestScroll(SCROLL_END, …)`** → posts `mScrollRunnable` → `runEndScroll()`
  → `animateScrollToEnd(maxScroll)`. Unchanged entry path; we only swapped the
  final scroll mechanism.
- **Sequence guard**: `requestScroll` already bumps `mScrollSeq` and re-posts
  `mScrollRunnable`. When a *newer* request re-posts, the still-running
  `mEndScrollAnimator` from the previous `runEndScroll()` must be cancelled first.
  Do this at the top of `runEndScroll()` (and/or `requestScroll`):

  ```java
  // In runEndScroll(), before measuring (replace the existing listener-removal block;
  // add the animator cancel next to it):
  cancelEndScrollAnimator();
  ```

  Because `mScrollRunnable` drops stale seqs (`if (seq < mScrollSeq) return;`), a
  superseded END never starts a second animator, and a CENTRE that wins the seq
  will itself cancel the running END via `cancelEndScrollAnimator()` if we also
  add it to the `SCROLL_CENTRE` branch — see below.

- **User drag canceller**: `onPageScrollStateChanged(SCROLL_STATE_DRAGGING)` already
  calls `tabs.setEndScrollReserved(false)` (SessionPagerManager.java:132). Extend
  `setEndScrollReserved(false)` to cancel the animator so a manual swipe takes over
  instantly:

  ```java
  public void setEndScrollReserved(boolean reserved) {
      mEndScrollActive = reserved;
      if (!reserved) {
          mScrollSeqPending = -1;
          cancelEndScrollAnimator();   // <-- user is taking over; stop the END scroll now
      }
  }
  ```

- **Tab close canceller**: `updateTabs()` already sets `mEndScrollActive = false`
  when `newCount < sessionCount` (line 189). Add `cancelEndScrollAnimator();` there
  so a close that shrinks the strip doesn't leave a dangling animation targeting a
  now-invalid X.

- **CENTRE request during a running END**: the `if (mEndScrollActive) return;`
  guard in `requestScroll` (line 611) already blocks CENTRE while END owns the
  strip, so CENTRE cannot fight the animator unless END was already released. Once
  released, `cancelEndScrollAnimator()` is a no-op and CENTRE's `smoothScrollTo`
  runs normally. No change needed beyond the added `cancelEndScrollAnimator()`
  calls above.

- **`onPageScrolled()` finger-follow**: still fully suppressed while
  `mEndScrollActive`. Now that a drag cancels the animator *and* clears
  `mEndScrollActive` (via `setEndScrollReserved(false)`), `onPageScrolled` resumes
  calling `mTabsScroll.scrollTo(...)` directly — which is exactly the manual
  finger-follow we want, and it cannot fight a cancelled animator. No change to
  `onPageScrolled` body needed.

---

## Making `scrollStripToEnd()` robust after removing `smoothScrollTo`

`scrollStripToEnd()` (line 634) itself never called `smoothScrollTo` — it only set
`mEndScrollActive = true` and `requestScroll(SCROLL_END, -1)`. It is already robust;
the only `smoothScrollTo` was inside `runEndScroll()`, which we replaced. To make it
extra-safe against a race where the label arrives *before* layout settles:

- Keep `mEndScrollActive = true` set *before* `requestScroll` (already done).
- `runEndScroll()`'s `widthBefore` wait + retry (max 6) already guarantees the
  measurement happens at the grown width. With the self-driven animator, even if a
  later layout pass shrinks the width, the animation still reaches the originally
  measured `maxScroll` and only snaps to the (smaller) real max at `onAnimationEnd`
  — so the strip is *never* left short of the new tab.

No further change to `scrollStripToEnd()` is required; it is correct as written.

---

## Summary of files / locations to change (when implementing)

| Location | Change |
|---|---|
| Fields (~line 516) | Add `mEndScrollAnimator`, `mEndScrollMaxWidth` |
| `runEndScroll()` (line 599) | Replace `smoothScrollTo` with `animateScrollToEnd(maxScroll)`; add `cancelEndScrollAnimator()` at top |
| New methods | `animateScrollToEnd(int)`, `cancelEndScrollAnimator()` |
| `setEndScrollReserved(false)` (line 725) | Add `cancelEndScrollAnimator()` |
| `updateTabs()` close branch (line 189) | Add `cancelEndScrollAnimator()` |
| `requestScroll()` (optional) | `cancelEndScrollAnimator()` for safety on re-post |

The fix is localised to `TermuxSessionTabsController.java`; `SessionPagerManager`
only needs the already-existing `setEndScrollReserved(false)` drag hook to also
cancel the animator (one line).

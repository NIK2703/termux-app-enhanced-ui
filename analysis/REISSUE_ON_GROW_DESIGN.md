# Re-issue `smoothScrollTo` on Content-Growth Design

**Goal:** Fix the under-scroll bug where `HorizontalScrollView` (HSV) live-re-clamps an
in-flight `smoothScrollTo(maxScroll)` to a *shrinking* content width, freezing the strip
≈ one tab short of the true right end (see `LOG_ANALYSIS_FINDING.md`).

**Strategy:** Rather than relying on a single stable target, keep re-issuing
`smoothScrollTo(newMax, 0)` *every time the measured content width GROWS* while an
"END scroll in progress" flag is set. Because HSV only ever *shortens* the clamp when
content shrinks, re-arming the scroller toward the (objectively larger) actual end keeps
pushing the strip to the growing true end. Width shrinks only transiently (LayoutTransition
CHANGING, OSC title resize, add-button toggle), then grows back to its final value, so
re-issuing on growth converges to the correct end and stabilises when width stops changing.

---

## 1. State to add

```java
// ── Re-issue-on-growth (end-scroll) bookkeeping ──────────────────────────
/** When true, a sticky END scroll is actively being driven by the growth loop. */
private boolean mEndScrollInProgress = false;

/** Largest target scrollX we have armed so far; only grow from here. */
private int mEndScrollTarget = 0;

/** Monotonic in-progress sequence: bumped on each new END request so a stale
 *  layout listener from a previous request cannot keep driving the strip. */
private long mEndScrollSeq = 0;
private long mEndScrollSeqLive = -1;

/** Bounded "no growth seen for N layouts" watchdog so the loop always terminates
 *  even if a final layout fire never arrives. */
private int mEndScrollStableCount = 0;

/** Hard cap on how long the loop stays attached (ms). Safety net. */
private long mEndScrollStartMs = 0;

/** The live global-layout listener driving the re-issue loop (so we can detach it). */
private android.view.ViewTreeObserver.OnGlobalLayoutListener mEndScrollListener = null;

private static final int END_SCROLL_EPSILON = 2;       // px: treat as "reached"
private static final int END_SCROLL_MAX_STABLE = 4;    // N no-growth fires → done
private static final int END_SCROLL_MAX_MS = 1200;     // hard timeout
private static final int END_SCROLL_RETRY_BEFORE_GROW = 6; // wait for first real growth
```

---

## 2. Cancellation points (must clear flag + detach listener)

A newer request or a user gesture must immediately stop the loop. Centralise teardown:

```java
private void endScrollCancel() {
    mEndScrollInProgress = false;
    if (mEndScrollListener != null && mTabsContainer != null) {
        mTabsContainer.getViewTreeObserver()
                .removeOnGlobalLayoutListener(mEndScrollListener);
    }
    mEndScrollListener = null;
    mEndScrollSeqLive = -1;
}
```

Wire it into the existing cancellation paths:

- **Newer CENTRE / END request (`requestScroll`)** — call `endScrollCancel()` when a
  *different* scroll wins. END beating CENTRE already works via `mScrollSeq`; the runner
  just calls `endScrollCancel()` before launching a CENTRE, or when a fresh END supersedes.
- **User drag (`DRAGGING`)** — the pager / touch path must call `endScrollCancel()`.
  Concretely, `onPageScrollStateChanged(DRAGGING)` should call `endScrollCancel()`
  (a genuine drag is user intent and must win). This also clears `mEndScrollActive` so a
  subsequent title refresh can recentre.
- **Tab close (`updateTabs` with `newCount < sessionCount`)** — already sets
  `mEndScrollActive = false`; also call `endScrollCancel()`.
- **`setEndScrollReserved(false)` / `setCurrentSession` genuine switch** — call
  `endScrollCancel()` (these already clear `mEndScrollActive`).

The re-issue loop itself also self-cancels on completion (section 4).

---

## 3. The re-issue loop (replaces the single-shot listener in `runEndScroll`)

```java
private void runEndScroll() {
    if (mTabsContainer == null || mTabsScroll == null) return;

    // Tear down any prior loop first (never stack listeners).
    endScrollCancel();

    final int widthBefore = mTabsContainer.getMeasuredWidth();
    final long mySeq = ++mEndScrollSeq;
    mEndScrollInProgress = true;
    mEndScrollSeqLive = mySeq;
    mEndScrollStableCount = 0;
    mEndScrollStartMs = android.os.SystemClock.uptimeMillis();

    final android.view.ViewTreeObserver.OnGlobalLayoutListener listener =
            new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
                @Override
                public void onGlobalLayout() {
                    // Stale loop from a superseded request: detach and bail.
                    if (mySeq != mEndScrollSeqLive) {
                        mTabsContainer.getViewTreeObserver()
                                .removeOnGlobalLayoutListener(this);
                        return;
                    }
                    if (mTabsContainer == null || mTabsScroll == null) {
                        endScrollCancel();
                        return;
                    }

                    final int measuredW = mTabsContainer.getMeasuredWidth();

                    // Wait until the container has actually grown to include the new tab
                    // (a layout pass may fire before the new width is folded in).
                    if (measuredW <= widthBefore && mEndScrollStableCount < END_SCROLL_RETRY_BEFORE_GROW) {
                        mEndScrollStableCount++;
                        return;
                    }

                    final int scrollW = mTabsScroll.getWidth();
                    final int newMax = Math.max(0, measuredW - scrollW);
                    final int scrollX = mTabsScroll.getScrollX();

                    // ── Completion checks ────────────────────────────────────
                    // (a) Already at/over the (current) end, and width isn't growing → done.
                    // (b) Hard timeout.
                    final long elapsed = android.os.SystemClock.uptimeMillis() - mEndScrollStartMs;
                    if ((scrollX >= newMax - END_SCROLL_EPSILON
                                && newMax <= mEndScrollTarget + END_SCROLL_EPSILON)
                            || elapsed >= END_SCROLL_MAX_MS) {
                        endScrollCancel();
                        return;
                    }

                    // ── Growth re-issue ─────────────────────────────────────
                    // Only act when content GREW past what we have armed. If width shrank
                    // (newMax < current scrollX) do NOTHING — never pull the strip back.
                    if (newMax > scrollX + END_SCROLL_EPSILON
                            && newMax > mEndScrollTarget) {
                        mEndScrollTarget = newMax;
                        mEndScrollStableCount = 0; // a growth fire resets the stable watchdog
                        logTabScroll("endScrollGrow",
                                "re-issue newMax=" + newMax + " scrollX=" + scrollX
                                        + " " + scrollGeometry());
                        mTabsScroll.smoothScrollTo(newMax, 0);
                    } else {
                        // No growth this fire; count toward stable watchdog.
                        mEndScrollStableCount++;
                        if (mEndScrollStableCount >= END_SCROLL_MAX_STABLE
                                && scrollX >= newMax - END_SCROLL_EPSILON) {
                            endScrollCancel();
                        }
                    }
                }
            };

    mEndScrollListener = listener;
    mTabsContainer.getViewTreeObserver().addOnGlobalLayoutListener(listener);
}
```

### How this converges

1. `scrollStripToEnd()` sets `mEndScrollActive = true` and queues `SCROLL_END` → `runEndScroll()`.
2. First valid layout: `newMax` (e.g. 1942) > `scrollX` (0) → `smoothScrollTo(1942)`.
3. Mid-animation the CHANGING transition reflows neighbours; a layout fire reports
   `measuredW` shrunk → `newMax` (1634) ≤ `scrollX` (in flight, say 1200) → **no re-issue**;
   HSV clamps the running anim to 1634, scroll coasts.
4. Width grows back as the transition settles; next fire reports `newMax` 1700 →
   `1700 > scrollX(1634)+ε` → **re-issue** `smoothScrollTo(1700)`, re-arming the scroller
   toward the larger end.
5. Final fire: `newMax` 1942 → re-issue `smoothScrollTo(1942)`.
6. `scrollX` reaches ≥ 1940; next fire hits the completion check → `endScrollCancel()`.

Because we only re-arm on *growth*, the loop cannot run away: once width is final and
`scrollX` ≥ `newMax−ε`, it detaches after at most `END_SCROLL_MAX_STABLE` no-growth fires
(or the `END_SCROLL_MAX_MS` hard cap).

---

## 4. Integration with the existing request/sequence guard

`requestScroll()` already funnels every scroll through one `mScrollRunnable` with
`mScrollSeq`/`mScrollSeqPending`. Changes:

- In `mScrollRunnable`, the `SCROLL_END` branch already calls `runEndScroll()` — keep it.
  `runEndScroll()` now (re)starts the growth loop instead of a one-shot.
- In `mScrollRunnable`, the `SCROLL_CENTRE` branch must cancel any in-progress END loop
  *if that CENTRE actually wins* (seq check). Add `endScrollCancel()` at the top of the
  CENTRE branch (the existing `if (mEndScrollActive) return;` guard already drops a CENTRE
  while `mEndScrollActive` is set — but `mEndScrollActive` is set by `scrollStripToEnd()`,
  so a winning CENTRE on a cleared flag is fine to cancel the loop after the fact too).
- `scrollStripToEnd()` keeps `mEndScrollActive = true`; the loop clears only
  `mEndScrollInProgress`/listener. We *deliberately keep `mEndScrollActive` true* until a
  genuine switch/close clears it, so a title-only refresh still won't recentre.

  **Optional refinement:** clear `mEndScrollActive` inside `endScrollCancel()` too, but
  only when the cancel is user/switch-driven — not when the loop completes naturally.
  Simplest correct rule: keep `mEndScrollActive` controlled *only* by
  `setEndScrollReserved`/`setCurrentSession`/tab-close, and let the loop's own
  `mEndScrollInProgress` be the "still animating" bit. The loop completion does NOT clear
  `mEndScrollActive`; it is cleared by the next genuine interaction. This preserves the
  existing "don't recentre on OSC title refresh" behaviour.

### Touch / drag hookup

Add to the pager scroll-state callback (the place that already knows `DRAGGING`):

```java
public void onPageScrollStateChanged(int state) {
    if (state == ViewPager2.SCROLL_STATE_DRAGGING) {
        // Genuine user drag wins: stop the auto end-scroll immediately.
        endScrollCancel();
        mEndScrollActive = false;
    }
    ...
}
```

If `onPageScrollStateChanged` lives elsewhere, expose `endScrollCancel()` as package-visible
and call it from the drag path. The key invariant: **any DRAGGING event → `endScrollCancel()`**.

---

## 5. Replacement map (what changes in the current file)

| Current | New |
|---|---|
| `runEndScroll()` single-shot listener (lines 556–604) | `runEndScroll()` growth-loop listener (section 3) + `endScrollCancel()` (section 2) |
| `mPendingEndLayoutListener` field (line 519) | `mEndScrollListener` + growth state fields (section 1) |
| CENTRE branch in `mScrollRunnable` (lines 533–543) | add `endScrollCancel()` before centring |
| No drag cancel | `endScrollCancel()` on `DRAGGING` (section 4) |
| `updateTabs` close path sets `mEndScrollActive=false` (line 189) | also `endScrollCancel()` |

`scrollStripToEnd()` / `setEndScrollReserved()` / `setCurrentSession()` keep their current
`mEndScrollActive` logic untouched.

---

## 6. Robustness vs. the self-driven `ValueAnimator` approach

**Re-issue-on-growth (this design)**

Pros
- Stays inside HSV's own scroll machinery — no fight with HSV's internal touch/fling/abort
  handling, and `getScrollX()`/clamp semantics are whatever the framework expects.
- Minimal new state; reuses the existing `OnGlobalLayoutListener` + `requestScroll` plumbing.
- Naturally tolerant: shrinking content never pulls back, growing content is chased.
- Respects `mScrollSeq`/`mEndScrollActive` for cross-request priority with no extra wiring.

Cons
- Depends on `onGlobalLayout` firing often enough during the transition. If layout stabilises
  *between* fires while scroll is still short, the next fire will still re-issue (fine), but
  completion detection leans on layout events — mitigated by the `END_SCROLL_MAX_MS` hard cap.
- Smoothness is whatever HSV's `smoothScrollTo` gives; repeated re-arms restart the
  deceleration curve, which can look slightly "re-energised" mid-flight (still smooth, no
  jump, because target only ever grows).
- Edge: if width oscillates forever (shouldn't, but a pathological transition), the
  `END_SCROLL_MAX_MS` cap is the backstop.

**Self-driven `ValueAnimator` on `mTabsScroll.scrollTo(x,0)`**

Pros
- Full authorial control: you animate `scrollX` 0→`target` yourself, clamped only to *your*
  computed `target`, ignoring HSV's live re-clamp. No dependence on `onGlobalLayout` rate.
- Deterministic duration/easing; no re-arm "re-energise".
- Completion is simply `onAnimationEnd` — no watchdog heuristics.

Cons
- You must own *all* scroll while the animator runs: a user drag must `cancel()` the animator
  AND you must not let HSV's own touch handling fight it (HSV will try to `scrollTo` on touch;
  an in-progress `scrollTo` from your animator can conflict → flicker). Needs an
  `isAnimating` flag guarding `onPageScrolled`/touch, i.e. more surface to keep consistent.
- `ValueAnimator` posts to Choreographer; combined with HSV's own `OverScroller` (which
  `smoothScrollTo` uses) you can end up with two scroll owners. Manual `scrollTo` bypasses
  HSV's fling/velocity, so a finger-fling right after would need re-syncing HSV's scroller
  state (hard — HSV doesn't expose it).
- More code, more risk of diverging from HSV's clamp/overscroll feel.

**Verdict:** For this specific bug (transient shrink, monotonic-ish growth to a final width),
*re-issue-on-growth* is the lower-risk, smaller, more HSV-friendly fix and reuses the
existing proven scaffolding. The `ValueAnimator` approach is more "correct" in the abstract
(immune to live-clamp entirely) but introduces a second scroll owner that fights HSV's
touch/fling model — a bigger, more fragile change for a problem that is purely about the
clamp trapping a *shrinking* range. Recommend re-issue-on-growth; keep ValueAnimator as a
fallback only if layout-fire rate proves too sparse in the field (logs will show
`endScrollGrow` re-issues; absence of them + short stop = revisit).

---

## 7. Open follow-ups before implementation

- Confirm where `ViewPager2.SCROLL_STATE_DRAGGING` is observable for this strip and add the
  `endScrollCancel()` hook there (or expose `endScrollCancel()` and call from the existing
  drag handler).
- Decide whether `endScrollCancel()` should also clear `mEndScrollActive`. Recommended: **no**
  (keep current `mEndScrollActive` owners), so OSC title refresh still won't recentre. The
  loop's "done" is tracked by `mEndScrollInProgress`/`mEndScrollListener == null` exclusively.
- Add `endScrollCancel()` to the `newCount < sessionCount` branch of `updateTabs` (line ~189).

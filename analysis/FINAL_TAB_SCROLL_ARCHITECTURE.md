# FINAL TAB-SCROLL ARCHITECTURE (device-proven root cause driven)

File under redesign: `app/src/main/java/com/termux/app/terminal/TermuxSessionTabsController.java`
Layout under redesign: `app/src/main/res/layout/activity_termux.xml`

---

## 0. Root cause recap (from `analysis/LOG_ANALYSIS_FINDING.md`)

- `smoothScrollTo(1942)` **is** called with the correct target.
- During the ~250 ms smooth-scroll animation the **content width of `mTabsContainer` shrinks** (3006 → 2698).
- `HorizontalScrollView` **re-clamps** the in-flight `smoothScrollTo` to the new (smaller) `maxScroll`, so the animation freezes at `1634` instead of `1942` — one tab short.
- Shrinks come from: (a) `LayoutTransition.CHANGING` (220 ms) reflowing neighbour widths; (b) a freshly added/retitled tab whose title is set via OSC *after* add; (c) `updateAddButtonVisibility` toggling the (+) button `GONE/VISIBLE` (and possibly its width).
- Geometry measurement is **correct** when read at the *final settled* width. The bug is purely **live re-clamping during the animation + shrinking content**.

Therefore the fix is **NOT** about measuring geometry better — it is about making the scroll **immune to HSV's live re-clamp** while the content width is still in flux.

---

## 1. Chosen strategy: self-driven `ValueAnimator` (option A+, "clamp-immune + extend-on-grow")

### Why option A over option B

| Aspect | (A) self-driven `scrollTo(value)` to a fixed target | (B) re-issue `smoothScrollTo` on width-grow |
|---|---|---|
| Immune to mid-animation shrink? | **Yes** — we call `mTabsScroll.scrollTo(value, 0)` directly; HSV's clamp only bounds `smoothScrollTo`, not a direct `scrollTo`, so a transient shrink cannot trap us short. | No — every re-issue is itself re-clamped; a single shrink *between* fires still traps. |
| Always arrives? | **Yes** — animates to a value computed from the final settled width. | Mostly, but timing of "final width" is indeterminate. |
| Oscillation risk? | None. | Possible: width can grow then shrink then grow (close anim + title settle), each re-issue restarting the easing and visibly stuttering. |
| Interaction with HSV fling/over-scroll? | Clean: `scrollTo` is the same primitive HSV itself uses; no competing `OverScroller`. | Each re-issue starts a *new* `OverScroller` tween; overlapping tweens fight. |

**Decision: option A.** Additionally, we apply the *one* good idea from B as a **safety valve**: the animator's update listener **extends `mEndScrollTarget` if the measured container width has grown** since the target was computed (e.g. a late OSC title widened the new tab). Shrinks are ignored entirely — exactly what option A guarantees.

This single approach satisfies **both** add paths because both funnel through `scrollStripToEnd()` → `requestScroll(SCROLL_END)` → the rewritten `runEndScroll()`:

- **(+) button tap path:** `TermuxTerminalSessionActivityClient` adds the session → `updateTabs` with `newCount > sessionCount` → `scrollStripToEnd()` (`TermuxTerminalSessionActivityClient.java:86`, `:187`).
- **Right-swipe placeholder path:** `SessionPagerManager` commits the placeholder → same `updateTabs` growth → `scrollStripToEnd()` (`SessionPagerManager.java:354` calls `setEndScrollReserved(true)` then the commit flows through `updateTabs`).

---

## 2. Keep the existing correctness plumbing (do NOT remove)

These already work and are preserved verbatim:

1. **Settle-wait via `OnGlobalLayoutListener`** in `runEndScroll()` (`TermuxSessionTabsController.java:556-604`) — measures only after layout. We keep the *measure* logic but move the *scroll* from `smoothScrollTo` to a self-driven animator.
2. **Sequence guard `END beats CENTRE`** via `mScrollSeq` / `mScrollSeqPending` (`TermuxSessionTabsController.java:510-516`, `mScrollRunnable:521-545`, `requestScroll:607-624`). Preserved.
3. **`mEndScrollActive` suppression** of `onPageScrolled` finger-follow (`TermuxSessionTabsController.java:790-800`) and of `setCurrentSession` CENTRE (`TermuxSessionTabsController.java:773-775`). Preserved.

---

## 3. Cancellation contract

A running self-driven animator must be cancelled **immediately** by either:

- **Newer scroll request (sequence guard):** `requestScroll()` is the single owner. When a `SCROLL_CENTRE` (or a newer `SCROLL_END`) is issued we `cancelEndScroll()` before posting. The `mScrollRunnable` already drops stale runs via `seq < mScrollSeq` (`TermuxSessionTabsController.java:527`); we add `cancelEndScroll()` there too so an in-flight animator from a *previous* request is stopped even if its runnable already ran.
- **User drag:** `SessionPagerManager.onPageScrollStateChanged(DRAGGING)` → `tabs.setEndScrollReserved(false)` (`SessionPagerManager.java:132`). We extend `setEndScrollReserved(false)` to also call `cancelEndScroll()` and re-enable the `LayoutTransition` (see §4). This guarantees that the moment the user grabs the strip, the auto-scroll stops and they own it.

`cancelEndScroll()` also tears down the global-layout listener and restores `LayoutTransition`/`updateAddButtonVisibility` state (§4).

---

## 4. Content-width stability (kill the mid-scroll shrink)

Three concrete changes:

### 4a. Freeze `LayoutTransition.CHANGING` during the END scroll
The CHANGING transition reflows neighbour widths for 220 ms after `updateTabs` adds a tab. While the END scroll runs we **disable CHANGING** so neighbour widths are frozen; the new tab is inserted at full width (already the case — APPEARING/CHANGE_APPEARING are disabled) so the strip settles in one pass. Re-enable CHANGING on `cancelEndScroll()` / animator end.

### 4b. Reserve the (+) button width (INVISIBLE, never GONE)
`updateAddButtonVisibility()` currently toggles `View.GONE`/`View.VISIBLE` (`:234-240`). Toggling `GONE` removes the button from layout, shrinking content width *and* shifting the last real tab — directly the shrink that traps the scroll. Change it to **`INVISIBLE`/`VISIBLE`** so the button always reserves its 36 dp + 4 dp marginEnd. This makes the trailing extent constant across the add, so the END target is stable. (When `MAX_SESSIONS` is reached we still hide it, but that happens *before* any add path and never mid-END-scroll.)

### 4c. Run the scroll after the title/close settle
Because the new tab's title can be set via OSC *after* `scrollStripToEnd()`, the animator's "extend-on-grow" safety (§1, §5 `mEndScrollTarget`) covers the case where the tab widens later. We do **not** need to defer the whole scroll — the fixed target already ignores shrinks, and the extend-on-grow handles widens. This keeps the scroll prompt (no perceptible delay) while still being correct.

---

## 5. Full Java code sketch (replaces `runEndScroll`, SCROLL_END in `mScrollRunnable`, cancellation, `updateAddButtonVisibility`, LayoutTransition freeze)

> All new/changed code lives in `TermuxSessionTabsController.java`. Line ranges referenced are from the current file.

### 5a. New fields (add near the scroll-state block at `TermuxSessionTabsController.java:487-519`)

```java
    // Self-driven END-scroll animator (clamp-immune). Replaces smoothScrollTo so HSV's
    // live re-clamp during the animation cannot trap the strip short.
    private android.animation.ValueAnimator mEndScrollAnimator = null;
    // Fixed target computed from the FINAL settled width; shrink during animation is ignored,
    // grow extends it (see EXTEND-ON-GROW in mEndScrollUpdateListener).
    private int mEndScrollTarget = 0;
    // LayoutTransition.CHANGING is frozen while the END scroll runs (§4a) to stop neighbour
    // reflow from shrinking the content width mid-animation.
    private boolean mLayoutTransitionFrozen = false;

    private void cancelEndScroll() {
        if (mEndScrollAnimator != null) {
            mEndScrollAnimator.cancel();
            mEndScrollAnimator = null;
        }
        if (mPendingEndLayoutListener != null && mTabsContainer != null) {
            mTabsContainer.getViewTreeObserver().removeOnGlobalLayoutListener(mPendingEndLayoutListener);
            mPendingEndLayoutListener = null;
        }
        restoreLayoutTransition();
    }

    private void freezeLayoutTransition() {
        if (mTabsContainer == null) return;
        LayoutTransition t = mTabsContainer.getLayoutTransition();
        if (t != null && !mLayoutTransitionFrozen) {
            t.disableTransitionType(LayoutTransition.CHANGING);
            mLayoutTransitionFrozen = true;
        }
    }

    private void restoreLayoutTransition() {
        if (mTabsContainer == null) return;
        LayoutTransition t = mTabsContainer.getLayoutTransition();
        if (t != null && mLayoutTransitionFrozen) {
            t.enableTransitionType(LayoutTransition.CHANGING);
            mLayoutTransitionFrozen = false;
        }
    }
```

### 5b. `mScrollRunnable` — replace the `SCROLL_END` branch (currently `TermuxSessionTabsController.java:531-532`)

```java
            if (mode == SCROLL_END) {
                runEndScroll();
            } else if (mode == SCROLL_CENTRE) {
```

No structural change to `CENTRE`; but add a `cancelEndScroll()` at the very top of `mScrollRunnable.run()` so any previously running END animator is stopped before a newer request executes (covers the sequence-guard drop path):

```java
        @Override
        public void run() {
            final long seq = mScrollSeqPending;
            mScrollSeqPending = -1;
            if (seq < mScrollSeq) {
                // Stale request: a newer scroll superseded this one. Make sure no
                // in-flight END animator from the superseded request keeps running.
                cancelEndScroll();
                return;
            }
            if (mTabsContainer == null || mTabsScroll == null) return;
```

### 5c. New `runEndScroll()` (replaces `TermuxSessionTabsController.java:556-604`)

```java
    /**
     * Scroll the strip to its absolute right end. Measurement is deferred to a global-layout
     * listener (existing settle-wait plumbing) so it runs ONLY after the container is laid out.
     * The actual scroll is a SELF-DRIVEN ValueAnimator that calls mTabsScroll.scrollTo(value,0)
     * to a FIXED target — bypassing HorizontalScrollView.smoothScrollTo, whose live re-clamp is
     * exactly what froze the strip at 1634 in the bug. Shrinks during animation are ignored; if
     * the content WIDTH GROWS (late OSC title), the target is extended (§1 safety valve).
     */
    private void runEndScroll() {
        if (mTabsContainer == null || mTabsScroll == null) return;

        // Tear down any prior listener/animator so we never stack measurements or tweens.
        cancelEndScroll();

        final int widthBefore = mTabsContainer.getMeasuredWidth();

        final android.view.ViewTreeObserver.OnGlobalLayoutListener listener =
                new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
                    private int mRetries = 0;
                    @Override
                    public void onGlobalLayout() {
                        if (mTabsContainer == null || mTabsScroll == null) {
                            cancelEndScroll();
                            return;
                        }
                        final int newWidth = mTabsContainer.getMeasuredWidth();
                        // Wait until the container has actually grown to include the new tab,
                        // bounded so a never-growing layout still eventually scrolls.
                        if (newWidth <= widthBefore && mRetries < 6) {
                            mRetries++;
                            return;
                        }
                        mTabsContainer.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                        if (mPendingEndLayoutListener == this) mPendingEndLayoutListener = null;

                        final int childMeasuredW = mTabsContainer.getMeasuredWidth();
                        final int scrollW = mTabsScroll.getWidth();
                        final int maxScroll = Math.max(0, childMeasuredW - scrollW);
                        logTabScroll("endScrollLayout",
                                "childMeasuredW=" + childMeasuredW + " scrollW=" + scrollW
                                        + " maxScroll=" + maxScroll + " " + scrollGeometry());

                        // FIXED target from the final settled width.
                        mEndScrollTarget = maxScroll;
                        startEndScrollAnimator();
                    }
                };
        mPendingEndLayoutListener = listener;
        mTabsContainer.getViewTreeObserver().addOnGlobalLayoutListener(listener);
    }

    /**
     * Launch (or restart) the clamp-immune END scroll. Freezes LayoutTransition.CHANGING (§4a)
     * for the duration so neighbour reflow cannot shrink content width mid-animation.
     */
    private void startEndScrollAnimator() {
        if (mTabsScroll == null) return;
        freezeLayoutTransition();

        final int from = mTabsScroll.getScrollX();
        final int to = mEndScrollTarget;
        if (from == to) {
            // Already at the right end — nothing to animate, but keep END ownership semantics.
            restoreLayoutTransition();
            return;
        }

        if (mEndScrollAnimator != null) mEndScrollAnimator.cancel();
        mEndScrollAnimator = android.animation.ValueAnimator.ofInt(from, to);
        mEndScrollAnimator.setDuration(250);
        mEndScrollAnimator.setInterpolator(new AccelerateDecelerateInterpolator());
        mEndScrollAnimator.addUpdateListener(animation -> {
            if (mTabsScroll == null) return;
            int value = (int) animation.getAnimatedValue();
            // EXTEND-ON-GROW safety valve (option B's one good idea): if the content width has
            // grown since the target was fixed (late OSC title widened the new tab), raise the
            // target so we still reach the true right end. Shrinks are deliberately ignored.
            int currentMax = Math.max(0,
                    mTabsContainer.getMeasuredWidth() - mTabsScroll.getWidth());
            if (currentMax > mEndScrollTarget) mEndScrollTarget = currentMax;
            int clamped = Math.min(value, mEndScrollTarget);
            mTabsScroll.scrollTo(clamped, 0);
        });
        mEndScrollAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (mEndScrollAnimator == animation) mEndScrollAnimator = null;
                // Arrive exactly at the (possibly extended) target regardless of last frame.
                if (mTabsScroll != null) mTabsScroll.scrollTo(mEndScrollTarget, 0);
                restoreLayoutTransition();
            }
            @Override
            public void onAnimationCancel(Animator animation) {
                if (mEndScrollAnimator == animation) mEndScrollAnimator = null;
                restoreLayoutTransition();
            }
        });
        mEndScrollAnimator.start();
    }
```

### 5d. `setEndScrollReserved` — cancel animator + restore transition on user drag (currently `TermuxSessionTabsController.java:725-732`)

```java
    public void setEndScrollReserved(boolean reserved) {
        mEndScrollActive = reserved;
        if (!reserved) {
            // User-initiated navigation (drag) cancels the end-scroll reservation entirely:
            // stop the running self-driven animator and re-enable LayoutTransition so the user
            // (and any subsequent scroll) owns the strip. Allow a later CENTRE to win again.
            cancelEndScroll();
            mScrollSeqPending = -1;
        }
    }
```

### 5e. `updateAddButtonVisibility` — reserve width via INVISIBLE (currently `TermuxSessionTabsController.java:234-240`)

```java
    public void updateAddButtonVisibility(int sessionCount) {
        if (mTabsContainer == null) return;
        View addBtn = mTabsContainer.findViewById(R.id.new_session_tab_button);
        if (addBtn == null) return;
        // Use INVISIBLE (not GONE) so the button ALWAYS reserves its 36dp + marginEnd. Toggling
        // GONE removed it from layout, shrinking content width mid-END-scroll and trapping the
        // strip short (the root-cause shrink). INVISIBLE keeps the strip's trailing extent stable.
        addBtn.setVisibility(sessionCount >= TermuxTerminalSessionActivityClient.MAX_SESSIONS
                ? View.INVISIBLE : View.VISIBLE);
    }
```

### 5f. `updateTabs` removal path — also cancel any in-flight END animator (`TermuxSessionTabsController.java:185-190`)

When tabs are removed (close), the END scroll no longer applies; cancel the animator so a stale tween does not keep driving `scrollTo`:

```java
        } else if (newCount < sessionCount) {
            mTabsContainer.removeViews(newCount, sessionCount - newCount);
            mEndScrollActive = false;
            cancelEndScroll();           // NEW: stop any running END tween on close
        }
```

> Note: `addBtn` is the **last child** of `mTabsContainer`. The INVISIBLE change keeps it occupying layout space, so `childCount - 1` session-count math and the `mTabsContainer.getChildAt(childCount - 1)` add-button lookups in `applySchemeColorsToTabs` / `setCurrentSession` / `resetPageSelection` are unaffected (they already handle an always-present last child).

---

## 6. Layout XML change (`activity_termux.xml`)

The (+) button already has a fixed `android:layout_width="36dp"` (`activity_termux.xml:47`) and `android:layout_marginEnd="4dp"` (`:49`). No width change is required. The **functional** reservation is done in code (§5e, INVISIBLE). The only recommended XML hardening is to make the reservation explicit and guarantee the button is never collapsed by a style:

- Keep `android:layout_width="36dp"` (fixed, not `wrap_content`) — already correct.
- No change strictly required, but to be safe against future theming that sets `GONE`, the code change in §5e is the authoritative fix.

**Optional defensive XML:** none needed; the INVISIBLE reservation in §5e fully resolves the width-shrink.

---

## 7. End-to-end behaviour matrix

| Scenario | Before | After |
|---|---|---|
| (+) tap add, 8th tab | freeze at 1634 (1 tab short) | animator drives `scrollTo(1942)`; shrink ignored → reaches 1942 |
| Right-swipe placeholder commit | same short-stop | same fix (funnel through `scrollStripToEnd`) |
| New tab title set via OSC *after* scroll | n/a | EXTEND-ON-GROW raises target; arrives at true end |
| User drags mid-END-scroll | may fight | `setEndScrollReserved(false)` → `cancelEndScroll()` stops tween instantly |
| Title-only refresh (no size change) | no recentre (mEndScrollActive) | unchanged — suppressed |
| Tab close during END scroll | n/a | `cancelEndScroll()` + `mEndScrollActive=false` clean stop |
| Content width shrinks mid-anim | **traps at smaller clamp** | **ignored** — fixed target wins |

---

## 8. Verification plan (no edit here — for implementer)

1. Build & install on-device (this fork builds on-device; do NOT runtime-test from the agent shell — same package as agent).
2. Reproduce the 8-tab add; capture `/storage/emulated/0/Download/termux/tabscroll.log`. Expect: `endScrollLayout maxScroll=1942`, then no `updateTabs scrollX=1634` short-stop; final `scrollX` ≈ 1942.
3. Tap (+) rapidly while an END scroll animates → confirm no oscillation (single animator, `cancel` on re-issue).
4. Halfway through an END scroll, drag the strip → confirm the auto-scroll halts immediately and the finger owns it.
5. Add a tab whose OSC title is long (widens tab) → confirm the strip still ends fully revealed (extend-on-grow).
6. Close a tab during an END scroll → confirm clean stop, no stuck `scrollTo` tween.

# Scenario B — Right-swipe-to-add, then shell sets a LONG title

**Verdict: PASS** (long-label swipe-add reaches the right end correctly, `(+)` fully visible) **and NO REGRESSION** of the original long-label under-scroll bug (a07ee4b2).

---

## Flow under test (Scenario B)

1. Right-swipe commits a placeholder → `createSessionForPlaceholder()` (TermuxTerminalSessionActivityClient.java:610) creates the session, `markPendingEndScrollSession()` (206-211) arms `mPendingEndScrollSession` + 250 ms fallback.
2. `createTermuxSession` triggers `updateTabs()` (TermuxSessionTabsController.java:116-191). `newCount > sessionCount`, so the tab view is added at full width (137-141), and because `mBuilt` is already true and `newCount > sessionCount`, `scrollStripToEnd()` (179) is called immediately — but `mEndScrollActive` is set and the actual scroll is deferred until the title is real.
3. Shell emits OSC title `user@host:~/projects/termux-app-ui-improve/src/main` → `onTitleChanged()` (173-198). Because `mPendingEndScrollSession == updatedSession` (182), it now runs `termuxSessionListNotifyUpdated()` (192) **BEFORE** `tabs.scrollStripToEnd()` (193). The order fix ensures the LONG label is painted and the strip geometry has settled before the end-scroll measures.

## Trace of runEndScroll for a GROWING (long) tab

- Listener fires on each layout pass (TermuxSessionTabsController.java:553).
- The new **width-gate** (`newWidth <= widthBefore`) of a07ee4b2 is gone. Current gate (566-572):
  ```
  final boolean stillMoving = (t != null && t.isRunning());
  if (stillMoving && mRetries < 10) { mRetries++; return; }
  ```
  For a long label the tab GROWS after the title paint. The CHANGING `LayoutTransition` (enabled at 63-72, duration 220 ms) is *running* while the container re-measures the wider tab, so `stillMoving == true` and the listener keeps **deferring** each pass until the transition finishes. Once `isRunning()` returns false the geometry has settled at the WIDE width.
- `maxScroll = Math.max(0, childMeasuredW - scrollW)` (582-584) is read ONCE, after settling, against the **wide** container including the `(+)` button's `marginEnd` (activity_termux.xml:49) and container `paddingHorizontal` (42). → correct right end, no under-scroll, `(+)` fully visible.
- If no further layout pass ever fires (geometry already final at first measure), `stillMoving` is false on the first call, so it proceeds immediately — **no starvation**, unlike the old growth-gate which could exhaust retries when width never grew.

## Regression check vs a07ee4b2 (the "(+) button clipped" symptom)

- **Root cause in a07ee4b2's predecessor:** `HSV.smoothScrollTo` re-clamps the in-flight scroll to content width every frame, so a mid-animation shrink froze the strip short. a07ee4b2 fixed it with a self-driven `ValueAnimator` → `scrollTo(fixedTarget)`.
- **Current fix preserves that protection:** `mEndScrollAnim` (601-621) still drives `mTabsScroll.scrollTo((int) anim.getAnimatedValue(), 0)` to a target computed ONCE. No `smoothScrollTo`, so the live per-frame re-clamp cannot shorten the trip.
- **Transition detach still in place:** `setLayoutTransition(null)` (591) is called *after* the wait, before the ValueAnimator starts. During the wait the transition is still active but we only **read** `getMeasuredWidth()` then — no mutation. The animation itself runs with the transition detached, so content width cannot shrink mid-animation. `saved` is restored in `onAnimationEnd`/`onAnimationCancel` (609-619). This is functionally identical to a07ee4b2's protection, just relocated after the wait (a correct refinement — detaching earlier would have killed the CHANGING animation we actually wait on).
- **`(+) button GONE vs VISIBLE`:** a07ee4b2 used `INVISIBLE`; current code uses `GONE` at `updateAddButtonVisibility` (207-208) only at `MAX_SESSIONS`. For Scenario B (long label, count < MAX) the button stays `VISIBLE`, so its footprint is measured into `childMeasuredW` and the `(+)` is revealed. At MAX_SESSIONS the comment (202-206) confirms the target is measured *after* the button is hidden, so no spurious trailing gap. No under-scroll regression.

## Many-tabs / overflow check

- `maxScroll = Math.max(0, childMeasuredW - scrollW)` (584) is floored at 0 → **no negative scrollX**. For a large pile of tabs `childMeasuredW` is large, `maxScroll` is correctly positive, and `scrollTo` clamps to `[0, maxScroll]` anyway.
- Sequence guard (484-485, 496) and `mEndScrollActive` suppression of `onPageScrolled` (896-905) ensure a competing CENTRE request or pager instant `scrollTo()` cannot fight the END scroll. `requestScroll` is single-owner (629-643). No overflow/race-induced under-scroll.

## Conclusion

**PASS** — Scenario B (long-label right-swipe-add) scrolls to the true right end with `(+)` fully visible. The removal of the width-gate fixes the short-label starvation while the long-label path is unchanged in correctness, and the a07ee4b2 re-clamp protection (self-driven `scrollTo`, transition detached during animation) is fully retained → **no regression of the original under-scroll bug.**

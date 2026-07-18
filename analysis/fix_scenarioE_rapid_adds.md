# Scenario E — Rapidly Adding Multiple Tabs in Succession

READ-ONLY verification. No files modified.

Files read fully:
- `app/src/main/java/com/termux/app/terminal/TermuxSessionTabsController.java`
- `app/src/main/java/com/termux/app/terminal/TermuxTerminalSessionActivityClient.java`

## Scenario
User taps (+) (or right-swipe-adds) 4× in quick succession. Each add must end
with the strip scrolled to its **latest** absolute right end (last real tab +
trailing (+) button). No listener leaks, no stale scroll to an intermediate
maxScroll, no short/under-scroll.

---

## Step-by-step trace of a single add

1. `updateTabs()` (line 116). New-count branch inserts the tab view at full
   width (lines 134-141), then the new-tab branch fires
   `scrollStripToEnd()` (line 179).
2. `scrollStripToEnd()` (line 738) sets `mEndScrollActive = true` (741) and calls
   `requestScroll(SCROLL_END, -1)` (744).
3. `requestScroll()` (line 629): mode ≠ CENTRE, so the `mEndScrollActive` guard
   at 633 is skipped. It bumps the monotonic seq (`mScrollSeq++`, 637), stamps
   `mScrollSeqPending = mScrollSeq` (638), **removes any queued
   `mScrollRunnable`** (640) and re-posts exactly one (641).
4. `mScrollRunnable.run()` (line 490): captures `seq = mScrollSeqPending`,
   resets it to -1, drops if `seq < mScrollSeq` (496). For the live post
   `seq == mScrollSeq`, so it proceeds → `runEndScroll()` (501/536).
5. `runEndScroll()` (536) calls `cancelEndScroll()` (540) FIRST, then registers a
   **new** `OnGlobalLayoutListener` (lines 624-625) into `mPendingEndLayoutListener`.

---

## Sub-question 1 — Listener pile-up (line 530-534, 540, 624-625)

**No pile-up. CONFIRMED.**

Two independent coalescing layers guarantee at most one live end-scroll
listener:

- **Runnable layer:** `requestScroll()` calls
  `mTabsScroll.removeCallbacks(mScrollRunnable)` then `post()` (lines 640-641).
  `mScrollRunnable` is a single shared instance, so four rapid adds within one
  frame collapse to **one** queued runnable → **one** `runEndScroll()` call →
  **one** listener registered. Only the final add's SCROLL_END actually reaches
  `runEndScroll`.

- **Listener layer (the case the scenario asks about — adds spread across
  frames):** if add #2's `mScrollRunnable` runs while add #1's listener is still
  parked waiting on `LayoutTransition.isRunning()`, `runEndScroll()` begins with
  `cancelEndScroll()` (540). `cancelEndScroll()` (525-534) removes
  `mPendingEndLayoutListener` from the ViewTreeObserver (531) and nulls it (532),
  then `runEndScroll` overwrites `mPendingEndLayoutListener` with the new
  listener (624). So the prior listener is **detached from the observer before**
  the new one is attached. Pile-up is impossible.

---

## Sub-question 2 / 3 — Can a superseded OLD listener still fire a stale scroll?

**No. CONFIRMED.**

Consider add #1's listener L1 parked in `onGlobalLayout` waiting on the
transition (lines 569-572, `stillMoving && mRetries < 10` → `return`). Add #2
arrives and its `runEndScroll` runs `cancelEndScroll()`, which executes
`removeOnGlobalLayoutListener(L1)` (531). Once removed from the observer, the
Android framework will **not** invoke `L1.onGlobalLayout` again — the "waiting"
state is not a suspended coroutine, it is simply "L1 returned early and expects
the next layout pass to re-enter it." After removal there is no next entry.

Even in the theoretical race where a layout pass is *already being dispatched*
to L1's `onGlobalLayout` at the instant #2 supersedes it, L1 would fall through
past the retry gate and reach lines 573-574:
```
mTabsContainer.getViewTreeObserver().removeOnGlobalLayoutListener(this);
if (mPendingEndLayoutListener == this) mPendingEndLayoutListener = null;
```
The `mPendingEndLayoutListener == this` guard (574) means L1 will **not** null
out L2's registration. And critically, L1's stale `maxScroll` is read
**inside** this same terminal pass (582-584) from
`mTabsContainer.getMeasuredWidth()` — the LIVE container width **after** #2's tab
was already inserted. So even a straggler L1 measures the *current* geometry, not
a stale snapshot. There is no captured-at-registration width; `childMeasuredW` /
`scrollW` / `maxScroll` are all read at fire time (582-584). A late L1 fire would
therefore scroll to the correct current right end, not a stale one.

Additionally, before L1 could start an animator, `cancelEndScroll()` from #2 also
cancelled any in-flight `mEndScrollAnim` (526-528), so two animators cannot run
concurrently either.

**Net:** no stale-maxScroll scroll, no double animator.

---

## Sub-question 4 — Does the LAST add win the final right-end position?

**Yes. CONFIRMED — for both the (+)-tap path and the OSC-title/swipe path.**

- **(+) tap path:** `updateTabs()` → `scrollStripToEnd()` directly (line 179).
  The last add's `requestScroll` has the highest `mScrollSeq`, so its runnable is
  the survivor (older coalesced posts were removed at 640; any that already ran
  and are parked as listeners get cancelled at 540). The surviving
  `runEndScroll` measures `getMeasuredWidth()` (582) after ALL inserted tabs are
  present and the CHANGING transition (only enabled type, 64-68) has settled
  (`!t.isRunning()`, 568), then animates to `maxScroll` (601-621). This is the
  true final right end.

- **Short-label / OSC-title path:** `onTitleChanged()` (line 174). Line 192
  calls `termuxSessionListNotifyUpdated()` **BEFORE** `scrollStripToEnd()` (193),
  so the (possibly shorter) real title is painted and geometry re-settles first;
  the subsequent `runEndScroll` measurement (582) reads the FINAL width. This is
  exactly the fix described. Because the width-gate was removed
  (comments 542-548) and the listener now only waits on
  `LayoutTransition.isRunning()`, a label that SHRINKS the tab no longer starves
  the scroll (the old growth-gate would never see growth and burn its retries).

- **Intermediate scroll can't "stick short":** each superseded end-scroll is
  cancelled (540/526-533) before it can start or complete its animator, and each
  survivor re-measures live geometry. So no intermediate add leaves the strip
  parked short of the newest end.

### One residual timing note (not a failure)
`runEndScroll` detaches the `LayoutTransition` for the animation duration
(591) and restores it in the animator end/cancel callbacks (612, 618). If add #N
supersedes add #N-1 *after* N-1 already detached the transition and started its
animator, #N's `cancelEndScroll()` cancels that animator → `onAnimationCancel`
(615) restores the saved transition (617-618), and #N's own listener then waits
on the (restored) transition and re-detaches. State is correctly balanced;
`saved` is captured per-listener from the live value at fire time (589-590), so
the transition is never lost or doubly-detached. No leak.

---

## VERDICT: **PASS**

Rapid repeated adds are handled correctly:

- **No listener leak.** Two coalescing layers (`removeCallbacks` on the shared
  `mScrollRunnable` at 640, and `cancelEndScroll()` removing
  `mPendingEndLayoutListener` at 531 before each new registration at 624) keep at
  most one live `OnGlobalLayoutListener` and one live animator.
- **No stale scroll.** A superseded listener is removed from the observer and
  cannot re-fire; even a same-frame straggler re-measures LIVE geometry
  (582-584) and is guarded by `mPendingEndLayoutListener == this` (574), so it
  neither clobbers the new listener nor scrolls to a stale target.
- **Last add wins the true end.** The highest-seq `runEndScroll` survives,
  waits only for `LayoutTransition.isRunning()` to finish (568-572, cap 10),
  reads the settled `getMeasuredWidth()` once (582), and animates to `maxScroll`
  (584, 601-621). `onTitleChanged` paints titles before scrolling (192→193), so
  short labels reach the correct final right end.
- **No missing end position / no under-scroll.** The width-gate removal +
  self-driven `ValueAnimator scrollTo(fixedTarget)` (bypassing HSV's per-frame
  re-clamp, 600-606) reaches the true right edge for both long and short labels.

**Confidence: High.** The only subtlety (transition detach/restore across a
supersede) is state-balanced via per-listener `saved` capture and the
cancel-callback restore path; it does not produce a leak or a short scroll.

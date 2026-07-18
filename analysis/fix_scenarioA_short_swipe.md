# Scenario A — Right-swipe-to-add, then shell sets a SHORT title "~"

**Verdict: PASS** (with one minor, non-fatal caveat noted at the end).

## Exact call / layout-pass trace

### 1. Swipe committed → placeholder replaced by real session
`SessionPagerManager.commitPlaceholderToSession()` (`SessionPagerManager.java:326`):
- `client.createSessionForPlaceholder(false, null)` (line 342) creates the `TerminalSession`. The creation fires `termuxSessionListNotifyUpdated()`, but the adapter still counts the placeholder so that sync is effectively a no-op for the strip.
- `tabs.setPlaceholderActive(false)` (line 347) ends the swipe blend.
- `tabs.setEndScrollReserved(true)` (line 354) → sets `mEndScrollActive = true` (`TermuxSessionTabsController.java:828-829`), which **suppresses** `onPageScrolled()` instant `scrollTo()` calls during pager settle (lines 896-905).
- `client.markPendingEndScrollSession(session)` (line 356) arms `mPendingEndScrollSession = session` and posts a 250 ms `mEndScrollFallback` (`TermuxTerminalSessionActivityClient.java:206-211`).
- In the `post()` block (lines 372-385) `onTerminalPageSelected(idx)` runs → `updateTabs(...)` (`TermuxTerminalSessionActivityClient.java:466`) → `TermuxSessionTabsController.updateTabs()` adds the new tab view at full default width and, because `newCount > sessionCount`, calls `scrollStripToEnd()` (line 179).

### 2. `scrollStripToEnd()` on add (branch newCount > sessionCount)
`TermuxSessionTabsController.java:738-745`: sets `mEndScrollActive = true` again (already true), then `requestScroll(SCROLL_END, -1)` (line 744).
- `requestScroll` (lines 629-643): bumps `mScrollSeq`, sets `mScrollSeqPending`, posts `mScrollRunnable`.
- `mScrollRunnable` (lines 490-506) runs: `seq == mScrollSeqPending`, calls `runEndScroll()`.
- `runEndScroll()` (lines 536-626): **`cancelEndScroll()`** first (line 540) — removes any prior listener. Registers a fresh `OnGlobalLayoutListener` (lines 549-625). **Note:** at this point the new tab still shows its *default* (longer) label because the shell has not emitted the title yet.

### 3. Shell emits OSC title "~"
`onTitleChanged(session)` (`TermuxTerminalSessionActivityClient.java:173-198`):
- `mPendingEndScrollSession == updatedSession` → true (line 182).
- `mPendingEndScrollSession = null` (line 183).
- `mainHandler.removeCallbacks(mEndScrollFallback)` (line 185) → **cancels the 250 ms fallback**. ✅ (answers scenario step 5: no double-trigger from the fallback).
- **FIX step:** `termuxSessionListNotifyUpdated()` is called FIRST (line 192) → repaints the tab title to "~", **shrinking** the tab and triggering a layout pass. The CHANGING `LayoutTransition` (enabled at line 64, duration 220 ms) becomes running.
- THEN `tabs.scrollStripToEnd()` (line 193) re-posts the END scroll → `requestScroll(SCROLL_END)` → `runEndScroll()` again.

### 4. `runEndScroll()` OnGlobalLayoutListener (the fixed logic)
- `cancelEndScroll()` (line 540) removes the *previous* listener from step 2 and registers the new one.
- Each `onGlobalLayout()` (lines 552-622):
  - Reads `t.getLayoutTransition().isRunning()` (lines 566-568). For a short label, the title shrink re-triggers a CHANGING transition, so `stillMoving` is `true` for the first few passes → `mRetries++` and returns (lines 569-572), bounded by `mRetries < 10`.
  - Once the CHANGING transition finishes, `stillMoving` becomes `false` → proceeds on the very next layout pass (no starvation, because the gate is "transition running AND retries remain", so a finished transition always proceeds).
  - Computes `childMeasuredW = mTabsContainer.getMeasuredWidth()` and `scrollW = mTabsScroll.getWidth()` (lines 582-583). With several tabs (the swipe-added one plus existing ones) plus the (+) button, `childMeasuredW > scrollW` unless everything fits the viewport. `maxScroll = max(0, childMeasuredW - scrollW)` (line 584).
  - Detaches the `LayoutTransition` (line 591) so width cannot shrink mid-animation, then starts a self-driven `ValueAnimator.ofInt(startX, maxScroll)` (line 601) writing `mTabsScroll.scrollTo(...)` per frame (lines 605-606).
  - **Result:** the strip scrolls to `maxScroll`, revealing the (+) button. ✅

### 5. Fallback race?
The 250 ms `mEndScrollFallback` is **removed** in `onTitleChanged` (line 185) *before* the replacement `scrollStripToEnd()` is posted. So even if the fallback were about to fire, it is cancelled. The only residual END trigger is the `scrollStripToEnd()` re-post from `onTitleChanged`, which is the intended single path. No double-fire. ✅

### 6. Remaining failure modes considered
- **Listener starvation:** impossible — the gate only defers while `isRunning()`; once the CHANGING transition ends the listener proceeds unconditionally. The `mRetries < 10` cap only stops *extra* deferrals, never the final proceed.
- **maxScroll computed too early:** no — measurement happens only after the transition settles (the `stillMoving` gate), and the title was already applied in step 3 before re-posting, so the width read reflects the short "~" label.
- **Double scroll:** `cancelEndScroll()` at the start of every `runEndScroll()` (line 540) cancels any prior in-flight anim + listener, so the step-2 add-scroll and the step-3 title-scroll do not run simultaneously. The single `mEndScrollAnim` field is reused/cancelled (lines 525-534, 607-620).
- **Minor caveat (non-fatal):** if the shell never emits any title for the new session, `onTitleChanged` never runs, so the step-2 `scrollStripToEnd()` (from `updateTabs`) is the *only* scroll — and it still works because that path does not depend on the title. The 250 ms fallback then never fires either (also correct, since the add already scrolled). So the short-title case is fully covered by the add-path scroll alone, and `onTitleChanged` only re-runs it harmlessly.

## Conclusion
The removed width-gate (`runEndScroll` no longer requires `newWidth > widthBefore`) means a short label no longer prevents the scroll. Ordering `termuxSessionListNotifyUpdated()` before `scrollStripToEnd()` ensures the "~" paint is applied (and the layout settles) before `maxScroll` is measured. The fallback is correctly cancelled. **PASS.**

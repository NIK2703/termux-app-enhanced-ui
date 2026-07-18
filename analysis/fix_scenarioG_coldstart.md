# Scenario G — Cold start / app reopened from BACK with several restored tabs (restored active tab NOT last)

**Fix under test:** `runEndScroll()` (TermuxSessionTabsController.java:536-626) removed the old width-gate and now waits only while `LayoutTransition.isRunning()` (mRetries<10, lines 568-572), then scrolls to `maxScroll` (line 584). `onTitleChanged` (TermuxTerminalSessionActivityClient.java:173-198) calls `termuxSessionListNotifyUpdated()` BEFORE `scrollStripToEnd()` (lines 192-193). An `mBuilt` flag (line 39) distinguishes the first build from later adds.

**Files read fully:** TermuxSessionTabsController.java, TermuxTerminalSessionActivityClient.java, activity_termux.xml.

---

## 1. Cold start does NOT use the end-scroll path — fix cannot break centring
- On cold start, `onStart()` (client:103-110) calls `setCurrentSession(...)` then `termuxSessionListNotifyUpdated()`, which reaches `updateTabs()`.
- In `updateTabs()`, the first-build branch is taken: `if (!mBuilt)` (line 163) → `requestScroll(SCROLL_CENTRE, currentSessionIndex)` (line 171) → `runCentreScroll()` (lines 665-728). The restored active tab is centred/visible.
- `mBuilt` is then set `true` (line 172).
- `runEndScroll()` is **not** invoked on cold start. The fix to `runEndScroll` therefore has zero effect on cold-start centring.
- ✅ **Confirmed:** the CENTRE path (restored active tab reveal) is independent and untouched.

## 2. First add after cold start scrolls to the right end correctly
- After cold start, `mBuilt==true`. When the user adds a tab, `updateTabs()` enters the add-branch: `newCount > sessionCount` (line 134) inserts the new tab view at full width (line 140), then the `else if (newCount > sessionCount)` at line 173 is true → `scrollStripToEnd()` (line 179).
- `scrollStripToEnd()` (lines 738-745) sets `mEndScrollActive=true` and calls `requestScroll(SCROLL_END, -1)` → `runEndScroll()`.
- Fixed `runEndScroll()` (536-626): `cancelEndScroll()` first; registers a `OnGlobalLayoutListener`. In `onGlobalLayout()` it reads `LayoutTransition.isRunning()` (lines 566-568); while running and `mRetries<10` it defers (lines 569-572), otherwise proceeds. It computes `childMeasuredW = mTabsContainer.getMeasuredWidth()` and `maxScroll = Math.max(0, childMeasuredW - scrollW)` (lines 582-584) ONCE after settlement, detaches the LayoutTransition (line 591) and self-drives a `ValueAnimator` to `maxScroll` (lines 601-621).
- Because the new tab was inserted at full measured width (no APPEARING animation — lines 65-68) the container geometry settles within one layout pass, so the right-end measurement is correct. The `maxScroll` computation at line 584 reads the full container width (including the trailing `+` button, since `session_tabs` is `wrap_content` with `paddingHorizontal=4dp` and the button has `marginEnd=4dp` per activity_termux.xml:42, 49), so the new tab AND the `+` button are fully revealed.
- ✅ **Confirmed:** the FIRST add after cold start correctly scrolls to the right end.

## 3. No spurious end-scroll when the active tab is already visible on cold start
- `scrollStripToEnd()` (the only entry to `runEndScroll`) is called from exactly three places: the `updateTabs` add-branch (line 179), `onTitleChanged` pending-session path (line 193 — but only when `mPendingEndScrollSession == updatedSession`), and the fallback timer (client:86).
- Cold start goes through `requestScroll(SCROLL_CENTRE, ...)` (line 171), never `SCROLL_END`. So even when the restored active tab is already within the viewport (no scroll needed), the CENTRE runnable (runCentreScroll) simply computes a `scrollX` clamped to `[0, maxScroll]` (lines 696-700) and, if `fromX == scrollX`, returns early (line 706). No end-scroll is ever triggered.
- `runEndScroll` is guarded at entry by the `SCROLL_END` mode (mScrollRunnable lines 500-504), so it cannot fire from a CENTRE request.
- ✅ **Confirmed:** no spurious end-scroll on cold start.

## 4. runCentreScroll is collateral-damage free
- The fix only modified `runEndScroll()` (536-626) and the `onTitleChanged` ordering (lines 192-193: notify-then-scroll). `runCentreScroll()` (665-728) is byte-for-byte independent: its listener, retry logic (lines 675-690), measurement (`getLeft()`-based centring, lines 693-700) and ValueAnimator (707-718) are unchanged.
- `requestScroll()` (629-643) still applies the `mEndScrollActive` guard for CENTRE (line 633) and the monotonic `mScrollSeq` ordering, so an END request still outranks a CENTRE — that behaviour is intact, not altered by the fix.
- ✅ **Confirmed:** no collateral damage to the centre logic.

## 5. Interaction flags
- **Single-thread / last-call-wins already enforced:** `requestScroll` (629-643) uses `mScrollSeq`/`mScrollSeqPending` so a later END (add) correctly outranks an earlier CENTRE. The cold-start CENTRE (line 171) and a subsequent add END (line 179) cannot fight in one frame because they are sequential calls across distinct user actions, and the seq guard drops stale CENTRE if an END arrives.
- **`mEndScrollActive` reservation:** set by `scrollStripToEnd()` (line 741) and only cleared on a genuine tab switch via `setCurrentSession()` (lines 876-877) or `setEndScrollReserved(false)` (lines 828-837). A title-only refresh after the add (updateTabs equal-count branch, line 185) correctly skips recentring while `mEndScrollActive` is true. This is consistent before and after the fix; the fix did not change it.
- **Add-then-rename sequence:** For a right-swipe add, `mPendingEndScrollSession` is armed (markPendingEndScrollSession, client:206-211), and `onTitleChanged` (client:182-194) now notifies the list FIRST (line 192) so the final (possibly shorter) label is painted, THEN `scrollStripToEnd()` (line 193) measures correct geometry. This ordering is exactly what the fixed `runEndScroll` relies on; with the old "scroll-then-paint" order the width-gate would have mis-measured, but the new order makes the post-fix unconditional `maxScroll` read correct. Good.
- **Cold-start `mBuilt` stays true across the session:** `updateTabs` only sets `mBuilt=false`? No setter found — `mBuilt` is set true at line 172 and never reset except by a new controller instance (activity recreation). So every add after the first build correctly routes to the END branch (line 173). Verified consistent.

---

## VERDICT: **PASS**

The fix does not break the cold-start centre path (Scenario 1): cold start exclusively uses `requestScroll(SCROLL_CENTRE, ...)` at line 171 through `runCentreScroll`, which is unmodified. The first add after cold start (Scenario 2) correctly routes through `scrollStripToEnd()` (line 179) → fixed `runEndScroll()` and scrolls to the true right end including the `+` button via the settled `maxScroll` (line 584). No spurious end-scroll occurs on cold start when the active tab is already visible (Scenario 3) because `scrollStripToEnd()` is never reached on the first build. The centre logic (Scenario 4) is collateral-damage free — only `runEndScroll` and the `onTitleChanged` notify-then-scroll ordering were changed. No blocking interactions were found; the existing `mScrollSeq` ordering and `mEndScrollActive` reservation continue to protect against END/CENTRE contention.

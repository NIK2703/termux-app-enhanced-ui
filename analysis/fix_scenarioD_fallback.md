# Scenario D — Shell Never Sets a Title (Fallback Timer Path)

## Files / Lines inspected
- `TermuxSessionTabsController.java`: `cancelEndScroll()` 525-534, `runEndScroll()` 536-626, `scrollStripToEnd()` 738-745, `requestScroll()` 629-643 (single-owner `mScrollRunnable`), `updateTabs()` add path 173-179, `setEndScrollReserved()` 828-837.
- `TermuxTerminalSessionActivityClient.java`: `mEndScrollFallback` / constructor 70-90, `onTitleChanged()` 173-198, `markPendingEndScrollSession()` 206-211.
- `SessionPagerManager.java`: `commitPlaceholderToSession()` 326-357 (calls `markPendingEndScrollSession` at 356).

## 1. Both paths converge on `scrollStripToEnd()` -> `runEndScroll()`

**Deferred `onTitleChanged` path (title DOES arrive):**
- `onTitleChanged()` 182-194: if `updatedSession == mPendingEndScrollSession`, clears `mPendingEndScrollSession` (183), `removeCallbacks(mEndScrollFallback)` (185), then `termuxSessionListNotifyUpdated()` (192) BEFORE `tabs.scrollStripToEnd()` (193).
- `scrollStripToEnd()` 738-745: sets `mEndScrollActive = true` (741) then `requestScroll(SCROLL_END, -1)` (744).
- `requestScroll()` 629-643: `mScrollSeq++`, `mScrollSeqPending = mScrollSeq`, `mTabsScroll.removeCallbacks(mScrollRunnable)` (640) then `post(mScrollRunnable)` (641).
- `mScrollRunnable` 490-506: drops if stale (`seq < mScrollSeq`), then calls `runEndScroll()` (501).
- `runEndScroll()` 536: `cancelEndScroll()` first (540), then registers ONE `mPendingEndLayoutListener` (624-625).

**Fallback path (title never arrives):**
- `markPendingEndScrollSession()` 206-211: sets `mPendingEndScrollSession` (207), `removeCallbacks` (209), `postDelayed(mEndScrollFallback, 250)` (210).
- After 250ms, `mEndScrollFallback` 83-89: if `mPendingEndScrollSession != null`, calls `tabs.scrollStripToEnd()` (86) then clears `mPendingEndScrollSession` (87).
- Same convergence: `scrollStripToEnd()` -> `requestScroll(SCROLL_END)` -> `runEndScroll()`.

Both paths are identical downstream. Confirmed.

## 2. Can a late fallback double-fire `runEndScroll()` alongside `onTitleChanged`?

No. Timing analysis:
- If `onTitleChanged` fires, it runs `mainHandler.removeCallbacks(mEndScrollFallback)` at line 185 **before** calling `scrollStripToEnd()`.
- `removeCallbacks` on a `Handler` is authoritative: once called, the runnable will NOT execute, even if it was already posted (and 250ms had not elapsed). There is no race on the main looper — `onTitleChanged` and `mEndScrollFallback` both run on the main thread, and `removeCallbacks` guarantees the posted message is dequeued/cancelled.
- Worst case where a "late fallback" could still fire: `onTitleChanged` for the SAME session never runs, so removal never happens — but then there is exactly ONE invocation (the fallback), not two.

**Two separate `runEndScroll()` invocations / double OnGlobalLayoutListener registration?**
- `runEndScroll()` calls `cancelEndScroll()` at its very start (540), which removes any prior `mPendingEndLayoutListener` (530-533) and cancels any in-flight `mEndScrollAnim`.
- Because `requestScroll()` is the single owner (last-call-wins via `mScrollSeq` + `mScrollRunnable` dedup at 640), only ONE `runEndScroll()` is ever queued per frame. Even if the fallback and a prior requestScroll raced, the sequence guard (496) and single `mScrollRunnable` (640 `removeCallbacks`) prevent two concurrent `runEndScroll()` bodies.
- Therefore at most ONE `mPendingEndLayoutListener` is ever registered at a time. No double-listener leak. Confirmed.

## 3. Is there ALWAYS at least one trigger to scroll?

Yes. Even if BOTH `onTitleChanged` and the fallback somehow did not fire:
- `updateTabs()` add path: when `newCount > sessionCount` (line 173-179), it calls `scrollStripToEnd()` unconditionally at line 179. This runs at the moment the new tab is inserted (the right-swipe commit path calls `commitPlaceholderToSession` -> `createTermuxSession` -> `termuxSessionListNotifyUpdated` -> `updateTabs`).
- So the strip is scrolled to end at least once at add time (line 179), independent of any title/fallback mechanism.
- Note: the line-179 call happens BEFORE `markPendingEndScrollSession`/`setEndScrollReserved` and before `mEndScrollActive` is set by `scrollStripToEnd()` (741). It sets `mEndScrollActive = true` itself (741) only via `scrollStripToEnd()`. The add-path call at 179 goes through `scrollStripToEnd()` too, so `mEndScrollActive` is set. This is fine; it means the strip will reach the end regardless.

Conclusion: there is always ≥1 trigger (add-time 179, and/or onTitleChanged 193, and/or fallback 86). No scenario with zero scrolls for a successful add. Confirmed.

## 4. Fallback scenario with a MEDIUM default label ("Terminal") and no title change

- The default label `"Terminal"` (or any placeholder) is applied at tab-creation time in `populateTabView` (296-299, via `R.string.session_default_title`). The width is therefore final before `updateTabs` even adds the tab.
- In the fallback path, `runEndScroll()` is entered via `scrollStripToEnd()` -> `requestScroll(SCROLL_END)` -> `mScrollRunnable` -> `runEndScroll()`.
- Inside `runEndScroll()`:
  - `cancelEndScroll()` (540) — no-op initially.
  - Listener waits only while `LayoutTransition.isRunning()` (CHANGING, ~220ms after add) and `mRetries < 10` (569). The CHANGING transition is enabled (63-72) and IS running right after the structural add in `updateTabs`.
  - Once CHANGING finishes (or retries exhausted), it reads `getMeasuredWidth()` (582) and computes `maxScroll = max(0, childMeasuredW - scrollW)` (584), then scrolls to it via `ValueAnimator` (601-621).
- Crucially, the OLD width-gate (waiting for the container to *grow* past a baseline) has been REMOVED per the fix. So for a short/default-width label that never changes, `stillMoving` becomes false as soon as the CHANGING transition ends, and the scroll proceeds. It does NOT starve waiting for a growth that never comes.
- The (+) button is revealed: `getMeasuredWidth()` of the wrap_content container includes the (+) button's `marginEnd` (see comment 578-579), so `maxScroll` reaches the true right end. Confirmed correct for the no-title case.

No starvation risk: if CHANGING never runs (no structural change, e.g. title-only refresh), `stillMoving` is false on the first `onGlobalLayout`, so it scrolls immediately (the retry cap is only a safety net for a never-settling transition).

## 5. VERDICT

**PASS** for the fallback / no-title scenario.

Justification:
1. Both the deferred `onTitleChanged` and the fallback converge on the same `scrollStripToEnd()` -> `requestScroll(SCROLL_END)` -> `runEndScroll()` chain. ✔
2. `removeCallbacks(mEndScrollFallback)` (185) on the main-thread `Handler` guarantees the fallback cannot double-fire once `onTitleChanged` has run. `requestScroll`'s single-owner `mScrollRunnable` + `mScrollSeq` guard plus `cancelEndScroll()` at the top of `runEndScroll()` guarantee at most ONE `OnGlobalLayoutListener`/`runEndScroll` instance at a time — no double-scroll, no listener leak. ✔
3. Even with both title and fallback absent, the `updateTabs` add branch (line 179) calls `scrollStripToEnd()` unconditionally, so the strip always scrolls at least once. ✔
4. With the width-gate removed, `runEndScroll()` waits only for `LayoutTransition.isRunning()` then scrolls to `maxScroll` (which includes the (+) button). A MEDIUM default label that never changes still scrolls correctly; no starvation. ✔

**Flagged risks (none blocking):**
- *Ordering nuance:* `updateTabs` line-179 `scrollStripToEnd()` runs at add time, BEFORE `setEndScrollReserved(true)` (SessionPagerManager 354) and the fallback arm (356). Since `scrollStripToEnd()` sets `mEndScrollActive = true` (741) itself, the subsequent `setEndScrollReserved(true)`/`setCurrentSession` suppression works as intended. No conflict.
- *Fallback clears `mPendingEndScrollSession` but not `setEndScrollReserved`*: the 250ms fallback calls `scrollStripToEnd()` which sets `mEndScrollActive=true`; the reservation is naturally "consumed" by the scroll. `setEndScrollReserved(false)` is only needed on user navigation (829-837). If the user navigates away during the 250ms window before the fallback fires, `setCurrentSession` (876) returns early because `mEndScrollActive` is still true, and `onPageScrolled` (896) is suppressed — but the fallback will still fire once and scroll to end, which is harmless (the user already navigated). Minor, non-blocking.

No double-scroll or starvation defect detected. **PASS.**

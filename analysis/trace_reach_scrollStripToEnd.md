# Trace: Is `scrollStripToEnd()` reached on the (+) tap path?

**Symptom:** After adding a tab via the (+) tap, the strip never scrolls to the right end (stays put in all cases).

**Verdict (one line):** `scrollStripToEnd()` **IS reached** on the (+) tap path — `updateTabs()` enters the add-branch (`newCount > sessionCount`) and calls it at `TermuxSessionTabsController.java:179`. The break is therefore **downstream**: the end-scroll is requested but cancelled/never-completed (see `trace_setEndScrollReserved_callers`). This report proves the upstream call is made; the failure is in the scroll execution, not the branch selection.

---

## 1. Where `termuxSessionListNotifyUpdated()` is defined and what it calls

- `TermuxTerminalSessionActivityClient.termuxSessionListNotifyUpdated()` — `TermuxTerminalSessionActivityClient.java:741-742` → delegates to `mActivity.termuxSessionListNotifyUpdated(-1)`.
- `TermuxActivity.termuxSessionListNotifyUpdated(int)` — `TermuxActivity.java:1995-2016`:
  - `mSessionPagerManager.termuxSessionListNotifyUpdated(preferredIndex)` (`:2004`) — pager adapter sync (NOT tabs).
  - `mTermuxSessionTabsController.updateTabs(mServiceConnectionManager.getTermuxService().getTermuxSessions())` (`:2010`) — **this is the updateTabs call**.
- So `termuxSessionListNotifyUpdated() → updateTabs(sessions)` with `sessions = service.getTermuxSessions()`.

## 2. On (+) tap: is `updateTabs` triggered, and synchronously?

`addNewSession()` at `TermuxTerminalSessionActivityClient.java:538`:

- `:555` `service.createTermuxSession(...)` creates the session.
- Inside `TermuxService.createTermuxSession()` (`TermuxService.java:580`), the session is added to the list at `:613` (`mShellManager.mTermuxSessions.add(newTermuxSession)`) **before** the notify.
- `:623` `mTermuxTerminalSessionActivityClient.termuxSessionListNotifyUpdated()` is called **synchronously** from inside `createTermuxSession`, which is still on the UI thread calling `addNewSession`.
- Therefore **Call 1 (synchronous)**: `addNewSession → createTermuxSession → termuxSessionListNotifyUpdated → updateTabs(sessions)` runs with the new session already in `sessions`.

There is also a **Call 2 (asynchronous)**: `:609` `setCurrentSession(newTerminalSession)` → `:427` `pager.setCurrentItem(index, true)` (smooth) → later fires `onTerminalPageSelected → onSessionPageSelected` → `:468` `updateTabs(service.getTermuxSessions())`. This runs after the smooth pager settle.

## 3. No early return before the add-branch in `updateTabs`

`TermuxSessionTabsController.updateTabs()` (`:116`):

- `:117` `if (mTabsContainer == null) return;` — container is non-null (strip built). Passes.
- `:131` `int sessionCount = mTabsContainer.getChildCount() - 1;`
- `:132` `int newCount = sessions.size();`
- `:134` `if (newCount > sessionCount)` — the add-branch.

`mBuilt` only affects the *first-build* branch (`:163`); it is independent of the add-branch. No guard blocks reaching `:134`.

## 4. Is `newCount > sessionCount` true on Call 1? (the double-update concern)

At **Call 1**, `updateTabs` itself is what will *add* the new tab view. The new session is already in `sessions`, but its tab view has NOT yet been added to `mTabsContainer`. So:

- `sessionCount = mTabsContainer.getChildCount() - 1` = **old session count** (the new tab's view is not yet present).
- `newCount = sessions.size()` = **old + 1**.

⇒ `newCount > sessionCount` is **TRUE**. The add-branch at `:134-141` runs, adds the tab view, then `:173` `else if (newCount > sessionCount)` is taken → **`scrollStripToEnd()` at `:179` is called.**

**Call 2** (async, after pager settle) runs `updateTabs` again. By now the tab view is already added, so `newCount == sessionCount`. It hits the equal-count branch `:180` `else if (currentSessionIndex >= 0)`, but `:185` `if (!mEndScrollActive) requestScroll(SCROLL_CENTRE, ...)` — and `mEndScrollActive` was set `true` by `scrollStripToEnd()` (Call 1) → the CENTRE request is **suppressed**. Good: Call 2 does not cancel the end-scroll.

So the **equal-count-skip** scenario (hypothesis #4) does NOT apply to the end-scroll's triggering updateTabs — the triggering call is Call 1, which has a genuine size increase. The end-scroll fires from Call 1, not Call 2.

## 5. Is `requestScroll(SCROLL_END)` actually reached from `scrollStripToEnd()`?

`scrollStripToEnd()` — `TermuxSessionTabsController.java:738-745`:

- `:739` null-guard on `mTabsContainer`/`mTabsScroll` — both non-null.
- `:741` `mEndScrollActive = true;`
- `:744` `requestScroll(SCROLL_END, -1);`

`requestScroll()` — `:629-643`:

- `:630` `if (mode == SCROLL_CENTRE)` — mode is `SCROLL_END` (`:471`), so this block is skipped (no `mEndScrollActive` early-return for END mode).
- `:636` `mPendingScrollMode = mode;`
- `:637-638` `mScrollSeq++; mScrollSeqPending = mScrollSeq;`
- `:639-641` `mTabsScroll.post(mScrollRunnable);` — posted.

`mScrollRunnable` — `:490-506`:

- `:496` `if (seq < mScrollSeq) return;` — drops only if a *newer* request superseded it.
- `:500-501` `if (mode == SCROLL_END) runEndScroll();` — runs.

So `requestScroll(SCROLL_END)` reaches `runEndScroll()` cleanly. There is **no early-return for SCROLL_END** in either `requestScroll` or `scrollStripToEnd` (other than the null-guards, which pass).

Note the sequence guard works in the END scroll's favour: `setCurrentSession(true)` reservation (`:606`) set `mEndScrollActive=true` *before* Call 1, and `scrollStripToEnd()` re-asserts it. A later CENTRE `requestScroll` (`:633`) would `return` early because `mEndScrollActive` is true, and even if it were posted, `mScrollSeq` would make the END win. So the END scroll is **not** clobbered by a CENTRE request at the `requestScroll` level.

## 6. Conclusion

- `scrollStripToEnd()` **IS called** on the (+) tap path:
  - `addNewSession` (`TermuxTerminalSessionActivityClient.java:538`)
  - → `createTermuxSession` (`:555`) → `TermuxService.createTermuxSession` (`:623`)
  - → `termuxSessionListNotifyUpdated` → `TermuxActivity.termuxSessionListNotifyUpdated` (`:1995`) → `updateTabs` (`:2010`)
  - → `updateTabs` add-branch (`:134`, `newCount > sessionCount`) → `scrollStripToEnd()` (`:179`).
- It sets `mEndScrollActive = true` (`:741`) and posts `requestScroll(SCROLL_END)` (`:744`), which reaches `runEndScroll()` (`:500-501`).
- No branch/guard prevents `scrollStripToEnd()` from being reached. The `mBuilt`-first-build branch, the `mTabsContainer == null` guard, and the `requestScroll` CENTRE-only early-return all pass/do-not-apply.
- Therefore the symptom ("never scrolls to the right end") is **NOT** caused by `scrollStripToEnd()` being skipped. It is a **downstream failure of the end-scroll itself** — the `mScrollRunnable`/`runEndScroll`/`ValueAnimator` execution, or `mEndScrollActive` being cleared/cancelled before `runEndScroll` completes (e.g. by `setEndScrollReserved(false)` / `cancelEndScroll()` callers, or `onPageScrolled`/`setCurrentSession` side effects). That downstream path is covered in `trace_setEndScrollReserved_callers`.

### Key evidence (file:line)
- `TermuxTerminalSessionActivityClient.java:555` — `createTermuxSession` (synchronous notify source).
- `TermuxService.java:613,623` — session added, then `termuxSessionListNotifyUpdated()` fired synchronously.
- `TermuxActivity.java:1995,2010` — `updateTabs(sessions)` invoked.
- `TermuxSessionTabsController.java:131-134,173-179` — `newCount > sessionCount` add-branch → `scrollStripToEnd()`.
- `TermuxSessionTabsController.java:738-745` — `scrollStripToEnd` sets `mEndScrollActive`, calls `requestScroll(SCROLL_END)`.
- `TermuxSessionTabsController.java:629-643` — `requestScroll` for END mode (no early-return).
- `TermuxSessionTabsController.java:490-505` — `mScrollRunnable` → `runEndScroll()` for END.
- `TermuxTerminalSessionActivityClient.java:606` — `setEndScrollReserved(true)` arms the guard *before* the add, so the add-branch/call-2 CENTRE is suppressed.

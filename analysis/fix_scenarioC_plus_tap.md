# Scenario C Verification — Tap (+) button to add a new tab with a SHORT final title "~"

## Files read fully
- `app/src/main/java/com/termux/app/terminal/TermuxSessionTabsController.java`
- `app/src/main/java/com/termux/app/terminal/TermuxTerminalSessionActivityClient.java`
- `app/src/main/res/layout/activity_termux.xml` (lines 20-60)

---

## 1. The (+) button click handler — which path does it take?

Handler wiring:
- `TermuxActivity.java:827` — `newSessionTabButton.setOnClickListener(v -> mTermuxTerminalSessionClient.addNewSession(false, null));`
- `addNewSession(false, null)` → `TermuxTerminalSessionActivityClient.java:536`.

Inside `addNewSession` (non-cold-start branch):
- `TermuxTerminalSessionActivityClient.java:553` creates the session (`service.createTermuxSession(...)`).
- `TermuxTerminalSessionActivityClient.java:596` calls `setCurrentSession(newTerminalSession)`.

`setCurrentSession` (`:377`) → pager `setCurrentItem(index, true)` (`:425`) → fires `onSessionPageSelected` (`:438`) → `TermuxSessionTabsController.updateTabs(service.getTermuxSessions())` (`:466`).

`updateTabs` (controller line 116) detects `newCount > sessionCount` (line 134), adds the tab view at line 137-141, and the **immediate** add branch (line 173-179) calls `scrollStripToEnd()` directly:

```
173: } else if (newCount > sessionCount) {
...
179:     scrollStripToEnd();
```

**Confirmed: the (+) tap takes the IMMEDIATE path** — `scrollStripToEnd()` is invoked synchronously inside `updateTabs` (branch `newCount > sessionCount`). It does **NOT** go through `markPendingEndScrollSession()` + `onTitleChanged` (deferred path). The deferred path is used only by the right-swipe placeholder commit (see `SessionPagerManager.java:342-356`, which calls `client.markPendingEndScrollSession(...)`). This matches aspect7's finding.

`scrollStripToEnd()` (controller line 738-745) sets `mEndScrollActive = true` and calls `requestScroll(SCROLL_END, -1)`, which posts `mScrollRunnable` → `runEndScroll()`.

---

## 2. With the current fix, does the (+) path still work for a short title "~"?

`runEndScroll()` (controller line 536-626) under the fix:

- The listener (line 549-623) is registered on `OnGlobalLayoutListener`.
- It **no longer gates on container width growth**. It only defers while `LayoutTransition.isRunning()` (line 566-572) with a bounded `mRetries < 10` safety net, then proceeds unconditionally.
- After the transition settles, it reads the **settled** geometry ONCE: `childMeasuredW = mTabsContainer.getMeasuredWidth()` (line 582), `maxScroll = Math.max(0, childMeasuredW - scrollW)` (line 584).
- Then it detaches the LayoutTransition (line 589-591) and drives a `ValueAnimator` from `startX` to `maxScroll` calling `scrollTo` (line 601-621).

For the (+) tap sequence with a short final title:
1. `updateTabs` inserts the new tab at its **default-label width** (e.g. "Terminal"), then immediately calls `scrollStripToEnd()`. The CHANGING LayoutTransition (enabled line 64) runs for ~220ms after the structural add.
2. `runEndScroll` waits for `LayoutTransition.isRunning()` to finish (bounded by `mRetries<10`), so it measures geometry AFTER the add reflow settles — at the **wide** default-label width.
3. The shell later emits an OSC title "~", firing `onTitleChanged` (`TermuxTerminalSessionActivityClient.java:174`). For the (+) tap, `mPendingEndScrollSession != updatedSession` (it was never set for the (+) path — that field is set only by the swipe path at `SessionPagerManager.java:356`), so the `if` block (line 182) is skipped and it falls through to the plain `termuxSessionListNotifyUpdated()` at line 197 → `updateTabs` → no size change → branch line 180-186: `if (!mEndScrollActive) requestScroll(SCROLL_CENTRE,...)`. Since `mEndScrollActive == true` (set at controller line 741), the title-only refresh does **NOT** recentre — good, the strip is left at the right end.

Now the question in the brief: *the title shrink happens LATER via a separate updateTabs from onTitleChanged; by then the end-scroll already fired (or is firing). Does the end-scroll still complete to the right end for the wide-then-maybe-shrunk tab?*

Yes. Two sub-cases:
- **End-scroll already fired** (most common): the ValueAnimator completed before the OSC title arrives (the title typically arrives ~tens–hundreds of ms later). The strip is already at `maxScroll` measured at the wide width. When the tab shrinks, `maxScroll` *decreases*, but the strip at the previously-larger `maxScroll` is **clamped down** automatically by `HorizontalScrollView` to the new smaller `maxScroll` (HSV always clamps `scrollX` to `[0, maxScroll]`). The (+) button, being the last child, is at the right end regardless — so it stays revealed. No regression.
- **End-scroll still firing when the shrink hits**: the brief's scenario-3 analysis (below) confirms `scrollTo` cannot exceed content width, and the animated `maxScroll` target was computed at the wide width. After the shrink the live `maxScroll` is smaller, and HSV re-clamps the in-flight `scrollTo` to the smaller value. The (+) button is still at the right end (it just snaps to the now-shorter right edge, which still includes the button). The (+) button is fully revealed either way.

Conclusion: the (+) path completes the end-scroll to the true right end for both wide-then-shrunk and short-label cases. The fix removed the growth-gate that could previously starve for short labels; for the immediate path the measurement now happens after the CHANGING transition settles, which is correct.

---

## 3. Risk of over-scroll from the WIDE width measured before shrink?

No over-scroll possible:
- `maxScroll = Math.max(0, childMeasuredW - scrollW)` (controller line 584) — this is exactly the HSV clamp limit. `scrollTo(target, 0)` with `target == maxScroll` requests the maximum legal scroll.
- `HorizontalScrollView` internally clamps `scrollX` to `[0, maxScroll]` on every frame. If content width later shrinks (title "~"), the live `maxScroll` shrinks and HSV re-clamps; `scrollTo` cannot push past the content's right edge.
- There is no scenario where the strip scrolls past the (+) button; the (+) button is the trailing child (layout line 45-54) so the right end always includes it.

Confirmed: no over-scroll.

---

## 4. VERDICT

**PASS** for the (+) tap with a short final title "~".

Justification:
- The (+) tap uses the immediate `updateTabs` → `scrollStripToEnd()` path (controller line 173-179, 738-745), independent of `markPendingEndScrollSession`/`onTitleChanged` (deferred path reserved for the swipe gesture).
- `runEndScroll` under the fix waits only for `LayoutTransition.isRunning()` (bounded `mRetries<10`, line 569) then measures the settled container width once and animates to `maxScroll`. The growth-gate removal means a short label can no longer starve the scroll.
- For a wide-default-then-shrunk-to-"~" tab: the end-scroll either already completed at the wide `maxScroll` and HSV auto-clamps down on shrink, or it is in flight and HSV clamps the in-flight `scrollTo` to the new smaller `maxScroll`. In all cases the (+) button (trailing child) remains fully revealed at the right end. `mEndScrollActive` correctly suppresses the later title-only `SCROLL_CENTRE` (controller line 185).
- `scrollTo` is mathematically clamped to `[0, maxScroll]`; over-scroll is impossible (scenario-3 confirmed).

### Difference vs the swipe path
- **(+) tap (immediate):** `scrollStripToEnd()` is called directly from `updateTabs` at default-label width. The end-scroll fires essentially immediately after the add (subject only to the CHANGING-transition settle wait). It does **not** depend on the shell ever emitting a title, so there is no dependency on `onTitleChanged`/the fallback timer. More robust against a shell that never sets a title.
- **Swipe (deferred):** `SessionPagerManager.java:342-356` creates the session, calls `tabs.setEndScrollReserved(true)` then `client.markPendingEndScrollSession(...)`. The end-scroll is **deferred** until `onTitleChanged` fires for that session (or a 250ms fallback timer, `TermuxTerminalSessionActivityClient.java:83-89, 210`), at which point `onTitleChanged` (line 182-194) calls `termuxSessionListNotifyUpdated()` **first** (so the short title is painted), then `scrollStripToEnd()`. This ordering guarantees `runEndScroll` measures the already-shrunk width for short labels.

Both paths converge on the same `scrollStripToEnd()` → `runEndScroll()` mechanism and both are correct under the fix. The (+) tap is actually *less* exposed to the original bug than the swipe path, because it never needed the deferred title-ordering trick — but the fix's growth-gate removal makes the swipe path equally safe for short labels, and the (+) path remains safe by construction. No remaining defect for Scenario C.

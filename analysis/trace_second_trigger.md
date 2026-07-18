# Trace: The Second Trigger (`onTitleChanged` → `scrollStripToEnd`) for the "strip never scrolls to right end" symptom

READ-ONLY analysis. No files modified.

## TL;DR

The second trigger **does fire** and **does reach `scrollStripToEnd()` → `runEndScroll()` → its `OnGlobalLayoutListener`**, and under the code as written that listener is **not cancelled by `setEndScrollReserved(false)` or by `setCurrentSession`** for the title-driven path. So if the strip still "stays put in all cases," the failure is **not** at the arming/competition layer — it is at the **measurement / mechanical-scroll layer inside `runEndScroll`** (or the listener is silently starved of layout passes). The two concrete suspects are, in order of likelihood:

1. **`mTabsScroll.getWidth()` returns the wrong/old width, or the listener never re-fires after the `stillMoving` guard, so the ValueAnimator runs to `maxScroll` but `maxScroll` is computed against a stale geometry.**
2. **The `OnGlobalLayoutListener` is added to `mTabsContainer`'s observer, but a layout pass after the title change never occurs on `mTabsContainer` (no width/children change once the tab is already inserted), so `onGlobalLayout()` never runs a second time and the listener is just parked — `mEndScrollActive` stays `true` forever but nothing scrolls.**

Below is the full evidence chain.

---

## 1. The second trigger is armed in the correct path (confirming hypothesis 6c)

In `addNewSession` (TermuxTerminalSessionActivityClient.java:538-611), the `(+)` tap → `createTermuxSession` → non-cold-start branch:

- TermuxTerminalSessionActivityClient.java:606 `tabs.setEndScrollReserved(true)` → sets `mEndScrollActive = true` (TermuxSessionTabsController.java:829).
- TermuxTerminalSessionActivityClient.java:607 `markPendingEndScrollSession(newTerminalSession)` → sets `mPendingEndScrollSession = newTerminalSession` and posts a 250ms fallback (TermuxTerminalSessionActivityClient.java:208-213).
- TermuxTerminalSessionActivityClient.java:609 `setCurrentSession(newTerminalSession)` → the *activity client's* `setCurrentSession`, which (per the surrounding code) eventually calls the tabs controller's `setCurrentSession(index)`.

The cold-start early `return` at TermuxTerminalSessionActivityClient.java:571-596 only applies to the **very first** session (`getTermuxSessionsSize() == 1` AND adapter empty). For a normal `(+)` tap on an already-running app with ≥1 session, `isColdStart` is `false`, so execution reaches :606-609. **Confirmed: the pending ref is armed on the path a `(+)` tap actually takes.**

`setEndScrollReserved(false)` is only ever called from:
- `SessionPagerManager.java:132` (inside `onPageScrollStateChanged(SCROLL_STATE_DRAGGING)` — a *user* drag start), and
- `TermuxSessionTabsController.java:838` (inside `setEndScrollReserved(false)` itself, to `cancelEndScroll()`).

A `(+)` tap is a programmatic `setCurrentItem()` (TermuxTerminalSessionActivityClient.java:609 → underlying `setCurrentSession` → pager selection), which never passes through `SCROLL_STATE_DRAGGING` (see the comment at SessionPagerManager.java:123-124). So **`setEndScrollReserved(false)` is NOT called during the add settle** for a `(+)` tap. `mEndScrollActive` remains `true` from :606.

---

## 2. Sequence at add time (first chance)

1. `addNewSession` → `updateTabs(sessions)` is invoked (via the session-list notify) with `newCount > sessionCount`. TermuxSessionTabsController.java:173-179 takes the `else if (newCount > sessionCount)` branch and calls `scrollStripToEnd()` **directly from within `updateTabs`**. This is the *first* chance. It sets `mEndScrollActive = true` (already true) and `requestScroll(SCROLL_END, -1)` (TermuxSessionTabsController.java:738-745).
2. `requestScroll` posts `mScrollRunnable` (TermuxSessionTabsController.java:641). When it runs, `runEndScroll()` registers the `OnGlobalLayoutListener` (TermuxSessionTabsController.java:625).
3. Meanwhile `setCurrentSession(newTerminalSession)` (the tabs controller variant, TermuxSessionTabsController.java:842) runs. Because `mEndScrollActive` is `true`, it hits the early `return` at TermuxSessionTabsController.java:879 **without** posting a competing CENTRE. Good — no competition at add time either.
4. The pager settles onto the new page. If the title has not yet arrived, `onPageScrolled` is suppressed by `mEndScrollActive` (TermuxSessionTabsController.java:899-903, 908). Good.

So far consistent with the design. The first-chance listener either scrolls or (per the original symptom) does not reach the right end. The user added the **second chance** precisely because the first could under-scroll.

---

## 3. The second trigger: title arrives → `onTitleChanged`

Eventually the shell emits an OSC window title. TermuxTerminalSessionActivityClient.java:174 `onTitleChanged(updatedSession)` fires on the main thread.

- Guard TermuxTerminalSessionActivityClient.java:175 passes (activity visible).
- `mPendingEndScrollSession == updatedSession` is `true` (armed at :607, not yet cleared). **Branch entered.**
- TermuxTerminalSessionActivityClient.java:183 sets `mPendingEndScrollSession = null`.
- TermuxTerminalSessionActivityClient.java:184-185 `removeCallbacks(mEndScrollFallback)` cancels the 250ms fallback.
- TermuxTerminalSessionActivityClient.java:193 `tabs.scrollStripToEnd()` — **second chance**, in scroll-first order.
- TermuxTerminalSessionActivityClient.java:195 `termuxSessionListNotifyUpdated()` → `updateTabs(sessions)` with `newCount == sessionCount` (no structural change).

### 3a. Does `scrollStripToEnd()` survive and scroll?

`scrollStripToEnd()` (TermuxSessionTabsController.java:738-745):
- `mTabsContainer`/`mTabsScroll` non-null — assumed true.
- Sets `mEndScrollActive = true` (already true) — **NOT cleared here**.
- `requestScroll(SCROLL_END, -1)` (TermuxSessionTabsController.java:744) → posts `mScrollRunnable`.

In `requestScroll` (TermuxSessionTabsController.java:629-643): mode is `SCROLL_END`, so it skips the `if (mEndScrollActive) return` guard at :633 (that guard only applies to `SCROLL_CENTRE`). It stamps a fresh `mScrollSeq`, sets `mScrollSeqPending = mScrollSeq`, removes any prior `mScrollRunnable` and posts a new one. **No cancellation from a CENTRE here.**

When `mScrollRunnable` runs (TermuxSessionTabsController.java:490-505): `seq == mScrollSeq` (no newer request), `mode == SCROLL_END` → `runEndScroll()` (TermuxSessionTabsController.java:501).

`runEndScroll()` (TermuxSessionTabsController.java:536-626):
- `cancelEndScroll()` (TermuxSessionTabsController.java:540) removes any *previous* `mPendingEndLayoutListener`. Since the first-chance listener (if any) already fired/removed itself, this is a no-op or tears down a stale one — fine.
- Creates a **new** `OnGlobalLayoutListener` and registers it on `mTabsContainer.getViewTreeObserver()` (TermuxSessionTabsController.java:624-625).

So the listener **is** alive and attached. **No `setEndScrollReserved(false)` / `setCurrentSession` cancels it at title time** (hypothesis 2's "not called now" holds; hypothesis 6b "ALSO cancelled" is FALSE for the title path — `setCurrentSession` early-returns at :879 because `mEndScrollActive` is still true).

### 3b. Does the CENTRE from `termuxSessionListNotifyUpdated()` fight it?

Back in `onTitleChanged`, after `scrollStripToEnd()` (which *posted* the runnable but did **not** run the listener yet), `termuxSessionListNotifyUpdated()` → `updateTabs`. Now `newCount == sessionCount`, so it reaches the `else if (currentSessionIndex >= 0)` branch (TermuxSessionTabsController.java:182) → `if (!mEndScrollActive) requestScroll(SCROLL_CENTRE, ...)`. `mEndScrollActive` is `true` (set at :741), so **no CENTRE is posted**. Confirmed: no competition, exactly as the user reasoned (hypothesis 3).

---

## 4. So why does it still "stay put"? — the real failure surface

The listener survives and there is no competing CENTRE. The only ways it "stays put" now are:

### Suspect A — The listener never gets a layout pass to run its body (starvation)

The `OnGlobalLayoutListener` fires on **every** global layout pass of `mTabsContainer`'s view tree. At *title time* the tab was **already inserted** at add time (TermuxSessionTabsController.java:137-141). The title change only updates the text in the existing tab view (TermuxSessionTabsController.java:153-157, `populateTabView`). If that text-width change does **not** trigger a new layout pass on `mTabsContainer` (e.g. the tab already had enough width, or the `HorizontalScrollView` did not re-measure), then **no new `onGlobalLayout()` fires after the listener is registered**, and the body (which does the actual scroll) never executes. The first-chance listener already consumed the add-time layout pass.

Critically, the listener **registers itself inside `runEndScroll` *after* the add-time layout has already happened**. If the title's `populateTabView` does not cause `mTabsContainer` to re-layout, the registered observer has nothing to observe → the listener is parked indefinitely → `mEndScrollActive` stays `true`, `onPageScrolled` stays suppressed forever, and **the strip never moves on either chance**. This matches "stays put in all cases."

Note the `stillMoving` retry logic (TermuxSessionTabsController.java:566-572) only *delays*; it still requires layout passes to keep arriving. If no passes arrive, the `mRetries++` branch is never even hit — `onGlobalLayout()` simply never calls again.

### Suspect B — `maxScroll` measured against stale/parent width

Assuming a layout pass *does* arrive, the listener proceeds (TermuxSessionTabsController.java:573-584):

```
int childMeasuredW = mTabsContainer.getMeasuredWidth();
int scrollW       = mTabsScroll.getWidth();
int maxScroll     = Math.max(0, childMeasuredW - scrollW);
```

If `mTabsScroll.getWidth()` (the `HorizontalScrollView` viewport, TermuxSessionTabsController.java:583) is read before the `HorizontalScrollView` itself has been laid out to its final width — or if `mTabsContainer.getMeasuredWidth()` reflects the *pre-title* (wider default-label) width while `mTabsScroll.getWidth()` is the *post* width — `maxScroll` can be computed wrong. The original code comment at :576-581 asserts this is read "AFTER the geometry has settled," but at title time the only settling is the (possibly shrinking) title text reflow. If the title makes the tab *narrower* (`~` vs `user@host:~`), `childMeasuredW` is **smaller** than at add time; combined with `setLayoutTransition(null)` (:591) so no further reflow changes it, `maxScroll` becomes the smaller value — which may still be > current scrollX and *would* scroll, but to a point short of where the user expects if `mTabsScroll.getWidth()` is also off.

The genuine "stays put" case here is `startX == maxScroll` (TermuxSessionTabsController.java:594-598): if the strip **already appears at what the code believes is the right end** (because `mTabsScroll.getWidth()` under-reports, or `childMeasuredW` over-reports, making `maxScroll ≤ currentScrollX`), it early-returns with no animation. I.e. the strip *thinks* it's at the end when it isn't.

### Suspect C — `updateTabs` after `scrollStripToEnd` closes the tab-count branch incorrectly

Not applicable: at title time `newCount == sessionCount`, so we never hit the `newCount < sessionCount` branch that sets `mEndScrollActive = false` (TermuxSessionTabsController.java:142-147). Confirmed harmless.

---

## 5. Interaction with the `setCurrentSession` order (the line :609 subtlety)

`setCurrentSession(newTerminalSession)` at TermuxTerminalSessionActivityClient.java:609 runs **synchronously** during `addNewSession`, *before* the title arrives. The tabs-controller `setCurrentSession` (TermuxSessionTabsController.java:842) early-returns at :879 because `mEndScrollActive` is `true` — **so it does NOT post a CENTRE and does NOT clear the flag.** This is correct and means the second trigger's `mEndScrollActive` is already set by add time. Good.

But note: `setCurrentSession` at :872 sets `mCurrentSessionIndex = index` *before* the early return. Then, when the title fires and `updateTabs` runs, `currentSessionIndex` is correct, and the `else if` branch at :180 is taken (`!mEndScrollActive` is false → no CENTRE). Consistent.

---

## 6. Verdict (addressing the user's 7 points)

| User point | Finding |
|---|---|
| 1. `onTitleChanged` enters branch & calls `scrollStripToEnd()` | **YES** — `mPendingEndScrollSession == updatedSession` holds; `mPendingEndScrollSession` nulled at :183; `scrollStripToEnd()` called at :193 in scroll-first order. |
| 2. `setEndScrollReserved(false)` NOT called now | **CONFIRMED** — title arrives after settle; `onPageScrollStateChanged(DRAGGING)` never fires for a programmatic `(+)` tap. |
| 3. `updateTabs` → no competing CENTRE | **CONFIRMED** — `mEndScrollActive` true at :741 → :185 guard skips CENTRE. |
| 4. listener retries if `stillMoving` | **TRUE but moot if no passes arrive** — the retry only works while layout passes keep coming; if none come, the body never runs at all. |
| 5. `mPendingEndScrollSession` cleared elsewhere? | Only at :183 (consumer), :87 (fallback, which is `removeCallbacks`'d at :185). For a single `(+)` tap, **not overwritten**. |
| 6. Does the second trigger fire & scroll? | It **fires and reaches `runEndScroll`**. Whether it scrolls depends on layout-pass availability (Suspect A) or `maxScroll` correctness (Suspect B). The code as written does **not guarantee** a layout pass occurs at title time. |
| 7. Conclude | The second trigger is **not cancelled**. The remaining suspects are (**A**) the `OnGlobalLayoutListener` is registered *after* the only relevant layout pass and no further pass is triggered by a title-only text update, so it never executes its scroll body; or (**B**) `maxScroll` is mis-computed (typically `startX == maxScroll` short-circuit at :594 because `mTabsScroll.getWidth()` is stale), so it early-returns without scrolling. **Both explain "stays put in all cases" without any cancellation.** |

### Precise remaining-suspect statement

The second trigger **does** arm and post `runEndScroll`, and it is **not** defeated by `setEndScrollReserved(false)`, `setCurrentSession`, or a competing CENTRE. Therefore the failure is mechanical:

- **Most likely (Suspect A):** `runEndScroll` registers its `OnGlobalLayoutListener` on `mTabsContainer` *after* the add-time layout pass has already occurred. A title-only `populateTabView` may not schedule a new layout pass on `mTabsContainer`, so `onGlobalLayout()` never fires again and the scroll body at TermuxSessionTabsController.java:573-621 **never runs**. `mEndScrollActive` remains `true`, permanently suppressing `onPageScrolled`, and the strip stays put on *both* chances.
- **Secondary (Suspect B):** if a pass does arrive, the `startX == maxScroll` short-circuit (TermuxSessionTabsController.java:594) returns without animation when `mTabsScroll.getWidth()` (TermuxSessionTabsController.java:583) is stale/over-wide, making the computed end coincide with the current position.

### Recommended verification (read-only / for the implementer, not applied here)
1. Log inside `runEndScroll`'s `onGlobalLayout` to confirm whether it fires at title time at all (Suspect A) — if the log line never prints, the listener is starved.
2. Log `childMeasuredW`, `scrollW`, `maxScroll`, `startX` at TermuxSessionTabsController.java:582-594 to confirm `startX == maxScroll` (Suspect B).
3. Confirm `mTabsScroll.getWidth()` is final at title time; if not, the deferred-measurement must also wait for the `HorizontalScrollView` viewport to settle, not only `mTabsContainer`.

### Key file:line references
- TermuxTerminalSessionActivityClient.java:174-200 `onTitleChanged`
- TermuxTerminalSessionActivityClient.java:182-196 second-trigger branch (scroll-first order)
- TermuxTerminalSessionActivityClient.java:208-213 `markPendingEndScrollSession`
- TermuxTerminalSessionActivityClient.java:538-611 `addNewSession` (non-cold path :596-609)
- TermuxTerminalSessionActivityClient.java:606-607 arm reservation + pending ref
- TermuxSessionTabsController.java:738-745 `scrollStripToEnd`
- TermuxSessionTabsController.java:536-626 `runEndScroll` + `OnGlobalLayoutListener`
- TermuxSessionTabsController.java:566-584 `stillMoving` guard + measurement
- TermuxSessionTabsController.java:594-598 `startX == maxScroll` early-return (no scroll)
- TermuxSessionTabsController.java:628-643 `requestScroll` (END bypasses CENTRE guard)
- TermuxSessionTabsController.java:116-191 `updateTabs` (branch logic, :180-185 no-CENTRE guard)
- TermuxSessionTabsController.java:828-840 `setEndScrollReserved` (only `false`-callers are user-drag + self)
- TermuxSessionTabsController.java:842-882 `setCurrentSession` (early-return at :879 preserves flag)
- TermuxSessionTabsController.java:896-908 `onPageScrolled` suppressed while `mEndScrollActive`
- SessionPagerManager.java:118-132 `onPageScrollStateChanged` — `setEndScrollReserved(false)` only on DRAGGING (not hit by programmatic `(+)` add)

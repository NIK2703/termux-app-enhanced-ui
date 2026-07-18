# Why the fix does not work — is OUR reordering (B) the regression?

**Scope:** READ-ONLY root-cause analysis of `TermuxTerminalSessionActivityClient.onTitleChanged`
and `TermuxSessionTabsController` in the Termux fork at
`/data/local/projects/termux-app-ui-improve`.

**Confirmed facts**
- The fix is built and running.
- `runEndScroll()` (TermuxSessionTabsController.java:536) removes the width-gate
  (`newWidth <= widthBefore`) and now unconditionally scrolls to `maxScroll` once
  `LayoutTransition.isRunning()` is false (lines 566–584).
- Yet the strip still does NOT reach the right end after adding a tab.

**Our fix changed two things**
- (A) `runEndScroll()` dropped the growth-gate; now waits only for the LayoutTransition then scrolls to `maxScroll`.
- (B) `onTitleChanged` (TermuxTerminalSessionActivityClient.java:182–194) reordered:
  `termuxSessionListNotifyUpdated()` is now called **BEFORE** `tabs.scrollStripToEnd()`
  (previously `scrollStripToEnd()` ran first).

---

## 1. Call-chain map (the relevant chain)

`onTitleChanged` (line 173) for a pending-end-scroll session:
1. `mPendingEndScrollSession = updatedSession` matches → lines 182–194 run.
2. `termuxSessionListNotifyUpdated()` (line 192) →
   `TermuxTerminalSessionActivityClient.termuxSessionListNotifyUpdated()` (line 728/732) →
   `SessionPagerManager.termuxSessionListNotifyUpdated(int)` (SessionPagerManager.java:624).
3. Inside that, on a size change, `mTerminalPagerAdapter.syncWithServiceList(...)` (line 687)
   triggers `updateTabs(...)` (TermuxSessionTabsController.java:116) **synchronously** during step 2.
4. `tabs.scrollStripToEnd()` (line 193) runs **AFTER** step 2 completes.

So in the CURRENT (fixed) ordering, when `updateTabs()` runs (step 3), `scrollStripToEnd()`
has **NOT** yet run, therefore `mEndScrollActive` is still `false` at that moment.

---

## 2. Does reordering (B) break the `mEndScrollActive` guard?

### 2.1 The guard site that matters — `updateTabs` else-if branch

TermuxSessionTabsController.java:180–186:
```
} else if (currentSessionIndex >= 0) {
    if (!mEndScrollActive) requestScroll(SCROLL_CENTRE, currentSessionIndex);
}
```

This is the **no-size-change** branch (a title-only OSC refresh). In the add-a-tab scenario,
`termuxSessionListNotifyUpdated()` from `onTitleChanged` is invoked **after** the tab was
already added (the add path already ran `scrollStripToEnd()` via the add branch at line 179).
So by the time `onTitleChanged` fires with the real title, `newCount == sessionCount` → this
else-if branch is the one that executes.

### 2.2 What the OLD ordering did

OLD ordering: `scrollStripToEnd()` (line 193) ran **first** → `mEndScrollActive = true`
(line 741) was set **before** `termuxSessionListNotifyUpdated()` → `updateTabs()` ran.
Therefore, when `updateTabs()` reached line 185, `mEndScrollActive` was already `true`, so the
`if (!mEndScrollActive)` guard **blocked** the competing CENTRE scroll. The guard's intent
("don't yank back to centre while an end-scroll is reserved") was honoured.

### 2.3 What the CURRENT ordering does

CURRENT ordering: `termuxSessionListNotifyUpdated()` (line 192) runs first. If this call
reaches `updateTabs()` in the no-size-change branch **before** `scrollStripToEnd()` sets
`mEndScrollActive`, then `mEndScrollActive` is `false` → line 185 **posts a CENTRE scroll**.

**Does it actually reach that branch before line 193?** Yes — `termuxSessionListNotifyUpdated()`
(line 192) is a synchronous call that culminates in `syncWithServiceList()` → `updateTabs()`
(line 687 → adapter → updateTabs) *before* line 193 executes. So `updateTabs()` for the title
refresh runs with `mEndScrollActive == false`.

### 2.4 Is the CENTRE scroll actually fatal? (deduplication analysis)

`requestScroll` (line 629) does:
- CENTRE branch: if `mEndScrollActive` is true it returns early (line 633). But here
  `mEndScrollActive` is false, so it proceeds.
- It sets `mPendingScrollMode = SCROLL_CENTRE`, bumps `mScrollSeq`, and
  `removeCallbacks(mScrollRunnable)` + `post(mScrollRunnable)` (lines 636–642).

Then `scrollStripToEnd()` (line 193) runs:
- sets `mEndScrollActive = true` (line 741),
- `requestScroll(SCROLL_END, -1)` → `removeCallbacks(mScrollRunnable)` + `post(mScrollRunnable)`
  (lines 636–642). Because `removeCallbacks` cancels the CENTRE-posted runnable, **only the END
  runnable remains queued**. When it runs, `mScrollRunnable` (lines 490–505) sees `mode ==
  SCROLL_END` and calls `runEndScroll()`. The CENTRE scroll is effectively cancelled by the
  last-call-wins `removeCallbacks` + the sequence guard (`mScrollSeq`, lines 696–698 in
  `runCentreScroll`).

**Conclusion on the runnable race:** The `removeCallbacks` dedup + sequence guard mean the END
scroll is the only one that survives to *execute*. So the reordering (B) does **NOT** cause a
competing CENTRE scroll to actually win at runtime in the common add-a-tab-then-title path.
The `runEndScroll()` is still reached.

BUT the reordering (B) **defeats the guard semantically** for the window between
`termuxSessionListNotifyUpdated()` returning and `scrollStripToEnd()` running: any re-entrant
`requestScroll(SCROLL_CENTRE)` from within `updateTabs`/pager-callback stack (e.g. a nested
`onPageScrolled`→`setCurrentSession`) sees `mEndScrollActive == false`. The guard comment at
line 182–184 ("If we are still in the sticky end-scroll … do NOT recentre") is *not* upheld
during that window. The safety that used to come from "arm the reservation before any
re-populate" is gone. Whether that window is exercised depends on whether a CENTRE is posted
synchronously during the notify; given the `removeCallbacks` dedup, the visible symptom is
typically *not* a centre scroll but rather the reservation being unset before it should be.

### 2.5 A sharper problem introduced by (B): reservation never armed for the (+) tap path

Reordering (B) interacts with a **separate, more fundamental gap** in the (+) tap path:

- The (+) button tap → `addNewSession(false, null)`
  (TermuxActivityViewHelper.java:105–106 → TermuxTerminalSessionActivityClient.java:536).
- `addNewSession` → `createTermuxSession` → `termuxSessionListNotifyUpdated` →
  `SessionPagerManager.termuxSessionListNotifyUpdated` (line 624) → `syncWithServiceList` →
  `updateTabs` (add branch, line 179) → `scrollStripToEnd()`.
- **Crucially, the (+) tap path never calls `setEndScrollReserved(true)`.** That call exists
  ONLY in the swipe-commit path `commitPlaceholderToSession` (SessionPagerManager.java:354).
  A grep for `setEndScrollReserved` finds exactly two sites: line 132 (clear) and line 354
  (set) — both inside `SessionPagerManager`, both only on the swipe path.

So for the (+) tap path, `mEndScrollActive` is set to `true` only *inside* `scrollStripToEnd()`
(line 741), at the moment `updateTabs` hits the add branch. That is fine for arming. But the
*label-triggered* re-fire from `onTitleChanged` (line 192–193) is governed by
`mPendingEndScrollSession`, which is only armed by `markPendingEndScrollSession`
(TermuxTerminalSessionActivityClient.java:206), again called **only from `commitPlaceholderToSession`**
(line 356). The (+) tap path does NOT arm `mPendingEndScrollSession` either.

=> For the (+) tap, the end-scroll that runs is the one from `updateTabs`' add-branch
(`scrollStripToEnd`, line 179) at insert time — measuring the tab at FULL width (default
"Terminal" label, not yet shrunk). That is exactly the scenario runEndScroll/A was meant to fix
later via the label-triggered re-fire. But that re-fire is **never armed for the (+) tap**, so
the strip is measured once at full width and never re-scrolled after the title shrinks.

This is the heart of why "the strip still does NOT reach the right end after adding a tab":
**the (+) tap path never reserves/arms the end-scroll before the pager settles, and never
re-fires after the title shrinks.** Reordering (B) does not cause this; it only affects whether
the *label-triggered* re-fire (which exists on the swipe path) is ordered safely relative to
the guard.

### 2.6 Did the OLD ordering actually work for the swipe path?

For the swipe path, `setEndScrollReserved(true)` is called at SessionPagerManager.java:354
**before** `markPendingEndScrollSession`, and that happens before the pager settles / before any
`onTitleChanged`. So `mEndScrollActive` is already `true` when the eventual `updateTabs` /
`onPageScrolled` run. In the OLD `onTitleChanged` ordering, `scrollStripToEnd()` ran first and
re-asserted `mEndScrollActive = true` right before `termuxSessionListNotifyUpdated()` →
`updateTabs`, so the else-if guard (line 185) blocked the CENTRE scroll cleanly. The reservation
was armed *before* the re-populate in both orderings for the swipe path — so (B) is mostly
*neutral* for the swipe path (the reservation was already armed upstream at line 354).

---

## 3. Answers

### (1) Does our reordering (B) introduce a regression in the `mEndScrollActive` guard?

**Partially / borderline — but it is not the primary cause of the failure.**

- (B) defeats the guard's *semantic* intent: when `onTitleChanged` calls
  `termuxSessionListNotifyUpdated()` before `scrollStripToEnd()`, the synchronous `updateTabs`
  re-populate runs with `mEndScrollActive == false` (lines 182–194 vs 741). For the window of
  that call, a CENTRE scroll could be posted (line 185) that the guard was meant to suppress.
- In practice the posted CENTRE runnable is cancelled by `removeCallbacks` in the subsequent
  `scrollStripToEnd()` → `requestScroll(SCROLL_END)` (lines 636–642) and by the sequence guard
  (lines 696–698), so the END scroll still wins at runtime. The reordering therefore does not
  *visibly* corrupt the end-scroll in the common swipe path.
- It DOES make the guard fragile: any code path that reads `mEndScrollActive` *during* the
  synchronous `termuxSessionListNotifyUpdated()` and acts on it (e.g. a nested
  `onPageScrolled`→`setCurrentSession`) will see the wrong (false) state. This is a latent
  regression even if not the active bug.

**Verdict:** (B) is a real (if subtle) regression against the guard's design, but it is masked
by the runnable dedup. It is not what makes the strip fail to reach the end.

### (2) Is the true fix elsewhere (arming reservation before pager settle)?

**Yes.** The true defect is that the **(+) tap path never arms the end-scroll reservation or the
label-triggered re-fire before the pager settles** (and never re-fires after the title shrinks):

- `setEndScrollReserved(true)` and `markPendingEndScrollSession(...)` are called **only** in
  `commitPlaceholderToSession` (SessionPagerManager.java:354, 356) — the swipe path.
- The (+) tap through `addNewSession` (TermuxTerminalSessionActivityClient.java:536 →
  createTermuxSession → termuxSessionListNotifyUpdated) reaches `updateTabs` add-branch
  (line 179) which calls `scrollStripToEnd()` once, at full default-label width. The label later
  shrinks via OSC, but **no pending-end-scroll re-fire is armed for the (+) tap**, so the strip
  is never re-scrolled to the (now smaller) true right end. Either:
  - the title shrink makes `maxScroll` smaller, so the earlier full-width scroll *overshoots* and
    gets re-clamped, or
  - nothing re-fires and the strip stops short of revealing the (+) button at the final width.

The correct place to arm the reservation is **before the add's pager settle** on *both* paths —
i.e. `addNewSession` (and `addNewSessionInDirectory`) should call
`setEndScrollReserved(true)` + `markPendingEndScrollSession(newSession.getTerminalSession())`
the same way `commitPlaceholderToSession` does, so the reservation is armed before
`termuxSessionListNotifyUpdated()` → `updateTabs` runs and before the pager settles.

---

## 4. Recommendation

- **REVERT (B)** to restore the guard's semantic correctness (call `scrollStripToEnd()`
  *before* `termuxSessionListNotifyUpdated()` in `onTitleChanged`, lines 192–193). Even though
  the runtime is currently masked by runnable dedup, reverting removes the fragile window where
  `mEndScrollActive` is false during `updateTabs`, and it costs nothing for the swipe path
  (reservation already armed upstream at line 354). Reverting makes (A) safer, not weaker.

- **KEEP (A)** — the removal of the width-gate in `runEndScroll()` is correct and necessary
  (the short-label case `newWidth <= widthBefore` would otherwise starve). Do not revert (A).

- **ADD the missing arming on the (+) tap path** (the actual root cause): in
  `addNewSession`/`addNewSessionInDirectory`, after `createTermuxSession` returns the new
  session, call `tabs.setEndScrollReserved(true)` and
  `markPendingEndScrollSession(newSession.getTerminalSession())` — mirroring
  SessionPagerManager.java:354–357. This arms the reservation *before* `termuxSessionListNotifyUpdated`
  (which `createTermuxSession` triggers internally) and arms the label-triggered re-fire so the
  strip scrolls to the true right end after the title shrinks. This is the fix that actually
  makes the strip reach the end.

---

## 5. Line-numbered evidence index

| Concern | File:Line |
|---|---|
| (B) reordered call in onTitleChanged | TermuxTerminalSessionActivityClient.java:182–194 (notify 192 before scroll 193) |
| `markPendingEndScrollSession` (swipe-only) | TermuxTerminalSessionActivityClient.java:206 |
| (A) removed width-gate in runEndScroll | TermuxSessionTabsController.java:536–584 (gate removed; unconditional maxScroll at 582–584) |
| `mEndScrollActive` flag | TermuxSessionTabsController.java:476 |
| else-if guard that (B) defeats | TermuxSessionTabsController.java:180–186 (`if (!mEndScrollActive) requestScroll(CENTRE)`) |
| `scrollStripToEnd` sets flag + posts END | TermuxSessionTabsController.java:738–745 |
| add-branch calls scrollStripToEnd | TermuxSessionTabsController.java:173–179 |
| `setCurrentSession` honours guard | TermuxSessionTabsController.java:876–878 |
| `onPageScrolled` suppressed by guard | TermuxSessionTabsController.java:896–905 |
| `requestScroll` dedup (removeCallbacks) | TermuxSessionTabsController.java:629–643 |
| sequence guard drops stale CENTRE | TermuxSessionTabsController.java:490–505, 696–698 |
| `setEndScrollReserved(true)` — SWIPE ONLY | SessionPagerManager.java:354 |
| `markPendingEndScrollSession` — SWIPE ONLY | SessionPagerManager.java:356 |
| (+) tap path → addNewSession | TermuxActivityViewHelper.java:105–106; TermuxTerminalSessionActivityClient.java:536 |
| updateTabs triggered by notify | SessionPagerManager.java:624–719 (syncWithServiceList line 687 → updateTabs) |

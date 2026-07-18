# Trace: `runEndScroll()` OnGlobalLayoutListener — does it fire and compute a correct `maxScroll`, or no-op?

Project: Termux app fork (`/data/local/projects/termux-app-ui-improve`, Android, Java)
Symptom: After adding a tab, the strip never scrolls to the right end (stays put in **all** cases).
Assumption for this trace: `scrollStripToEnd()` IS reached, `runEndScroll()` runs, and it **does** register its `OnGlobalLayoutListener`.
Question: Does the listener *fire* and compute a correct `maxScroll`, or does it no-op?

---

## 1. The listener registration path (and what we assume is alive)

`scrollStripToEnd()` (TermuxSessionTabsController.java:738-745) sets `mEndScrollActive = true` then calls `requestScroll(SCROLL_END, -1)`.
`requestScroll` (TermuxSessionTabsController.java:629-643) stamps a monotonic sequence, sets `mPendingScrollMode = SCROLL_END`, and `post(mScrollRunnable)` on `mTabsScroll`.
`mScrollRunnable` (TermuxSessionTabsController.java:490-506) runs on the next frame, sees `mode == SCROLL_END`, and calls `runEndScroll()` (TermuxSessionTabsController.java:536-626).

`runEndScroll()`:
1. Bails if `mTabsContainer == null || mTabsScroll == null` (536-537). Not our case.
2. Calls `cancelEndScroll()` (540) — tears down any prior anim + listener (525-534).
3. Builds the `OnGlobalLayoutListener` (549-623) and registers it on `mTabsContainer.getViewTreeObserver()` (625), also stashing it in `mPendingEndLayoutListener` (624).

Given the assumption, the listener **is registered** on `mTabsContainer`'s `ViewTreeObserver`. The container is the `session_tabs` LinearLayout (`activity_termux.xml:37-56`, `android:id="@+id/session_tabs"`, `wrap_content`). Adding a child (`mTabsContainer.addView(...)` at TermuxSessionTabsController.java:140) requests layout, so a layout pass **will** occur and the listener **will** be dispatched. So "never fires" due to no layout is **not** the general-case failure.

---

## 2. Listener execution — what actually happens on fire (lines 552-622)

On each layout pass `onGlobalLayout()` runs:

### 2a. Null guard (554-558)
`if (mTabsContainer == null || mTabsScroll == null)` → removes listener, returns. Both non-null. Proceeds.

### 2b. LayoutTransition settle gate (559-572)
```java
final android.animation.LayoutTransition t = mTabsContainer.getLayoutTransition();
final boolean stillMoving = (t != null && t.isRunning());
if (stillMoving && mRetries < 10) { mRetries++; return; }
```
- `mTabsContainer` (session_tabs LinearLayout) **has** a `LayoutTransition` (the open/close CHANGING/APPEARING animation). On a tab *add* the CHANGING transition is running for ~220ms, so the first few layout passes hit `stillMoving == true` and `return` (retry). This is **not** a no-op — it just defers.
- Eventually CHANGING finishes (`isRunning()` false) → proceeds on the next pass. The `mRetries < 10` cap is a safety net; it does **not** generally starve (settling normally happens well under 10 passes).
- **Caveat (design risk, not the general symptom):** if CHANGING stopped but *no further layout pass* was ever requested, the listener would starve and never proceed. The code comment at TermuxSessionTabsController.java:563-565 explicitly acknowledges this. But the user reports the strip "stays put in all cases," and this starvation only triggers in the rare no-further-pass-after-settle situation, so it cannot explain a universal failure. (Also note `runEndScroll` **detaches** the LayoutTransition only *after* the gate at 591, so the gate itself cannot deadlock on its own detached transition.)

### 2c. Authoritative measurement (573-584)
```java
mTabsContainer.getViewTreeObserver().removeOnGlobalLayoutListener(this);   // 573
if (mPendingEndLayoutListener == this) mPendingEndLayoutListener = null;    // 574
final int childMeasuredW = mTabsContainer.getMeasuredWidth();               // 582
final int scrollW = mTabsScroll.getWidth();                                 // 583
final int maxScroll = Math.max(0, childMeasuredW - scrollW);                // 584
```
- `removeOnGlobalLayoutListener(this)` at **573** fires *before* any measurement. **Important:** if `mTabsContainer.getViewTreeObserver()` returns the *same* observer that is currently dispatching, this self-removal works correctly (standard Android behaviour — removing during dispatch is deferred to after the current callback). Confirmed: `mTabsContainer` is a fixed view; the observer identity is stable, so the listener is correctly de-registered and will not re-fire after this pass.
- `childMeasuredW = mTabsContainer.getMeasuredWidth()` is read **after** layout, so it reflects the **new** (post-add) content width — the `wrap_content` LinearLayout including the new tab (item_session_tab.xml: `wrap_content` width, `layout_marginEnd=4dp`) **and** the trailing `+` button width 36dp + `marginEnd=4dp` (activity_termux.xml:45-54) **and** container `paddingHorizontal=4dp` (activity_termux.xml:42). So `childMeasuredW` is correct and current, **not stale**.
- Thus `maxScroll` is the true right end.

### 2d. THE POSSIBLE NO-OP — already-at-end early return (593-599)
```java
final int startX = mTabsScroll.getScrollX();          // 593
if (startX == maxScroll) {                            // 594
    mTabsContainer.setLayoutTransition(saved);
    return;                                           // 596  -> NO scroll
}
```
This is the **only true no-op branch inside the listener**. It skips scrolling iff the strip is *already* parked at `maxScroll`.

**When would `startX == maxScroll` right after an add?**
- The strip was already scrolled to the right end *before* the add **and** the new tab + `+` button do not extend past the viewport (i.e. content still fits, `maxScroll` unchanged). Then no animation is needed.
- But the symptom is **"in all cases," including when there are only a few tabs** (so content clearly overflows the viewport, `maxScroll` is large, and `startX` is ~0 from viewing the first tab). In that situation `startX (≈0) != maxScroll (large)` → the early return is **not** taken. So this no-op cannot be the universal explanation.

### 2e. Self-driven scroll (600-621)
When `startX != maxScroll`, a `ValueAnimator` is created `ofInt(startX, maxScroll)` (601), `setDuration(250)`, and each frame calls `mTabsScroll.scrollTo(value, 0)` (605-606). This bypasses HSV's per-frame re-clamp. On `onAnimationEnd`/`Cancel` the saved `LayoutTransition` is restored (607-619). This is correct and would *visibly* scroll.

**Conclusion on the listener itself:** if the listener *fires* (we assume it does) and reaches 2c/2e, it computes a correct, current `maxScroll` and *will* animate to the right end in the general over-flowing case. The in-listener logic is **not** a universal no-op. The only no-op (`startX == maxScroll`, 594) is contingent and does not apply when the viewport is overflowing.

---

## 3. The real candidate: the listener is **cancelled before it fires** — `cancelEndScroll()`

Because the listener logic itself is sound, the universal "stays put" symptom points to the listener being **removed before its first layout pass**. That is exactly what `cancelEndScroll()` does:

```java
private void cancelEndScroll() {                                            // 525
    if (mEndScrollAnim != null) { mEndScrollAnim.cancel(); mEndScrollAnim = null; }
    if (mPendingEndLayoutListener != null && mTabsContainer != null) {
        mTabsContainer.getViewTreeObserver().removeOnGlobalLayoutListener(mPendingEndLayoutListener); // 531
        mPendingEndLayoutListener = null;                                   // 532
    }
}
```
`cancelEndScroll()` is called from **two** places:
1. Inside `runEndScroll()` at line **540** — but only to tear down a *prior* request; it then re-registers a fresh listener at 625, so this is fine.
2. Inside `setEndScrollReserved(false)` at line **838**.

`setEndScrollReserved(false)` is the **dangerous caller**. It is reached from:
- `SessionPagerManager.onPageScrollStateChanged` at **SCROLL_STATE_DRAGGING** → `tabs.setEndScrollReserved(false)` (SessionPagerManager.java:132). This fires only on a *user swipe*, not on a plain programmatic add, so it is not the universal trigger.

But look at the **ordering hazard** in the add paths, which is the smoking gun:

### 3a. Right-swipe / (+) add path ordering
- `commitPlaceholder` path (SessionPagerManager.java:354) calls `setEndScrollReserved(true)` **early** (arms `mEndScrollActive`, but does **not** post the END scroll yet — the actual `scrollStripToEnd()` is deferred until the shell sets a title, via `onTitleChanged`/fallback at TermuxTerminalSessionActivityClient.java:86,193).
- The actual `scrollStripToEnd()` (which posts `mScrollRunnable` → `runEndScroll()` → registers the listener) happens **later**, in `onTitleChanged` (TermuxTerminalSessionActivityClient.java:193) or the 250ms fallback (TermuxTerminalSessionActivityClient.java:83-89).
- Between arming (`setEndScrollReserved(true)`) and the deferred `scrollStripToEnd()`, the pager **settles** onto the new page (`setCurrentItem`). If that settle ever produces a `SCROLL_STATE_DRAGGING`→`setEndScrollReserved(false)` (SessionPagerManager.java:132) **before** the listener is registered, the reservation is cancelled — but no listener exists yet, so no harm.
- The relevant harm: **if `setEndScrollReserved(false)` is called *after* `runEndScroll()` registered the listener but *before* the next layout pass**, then `cancelEndScroll()` (838→531) removes `mPendingEndLayoutListener` and the listener **never fires** → strip stays put. Given `setEndScrollReserved(true)`/`false` toggles and the pager settle all interleave around the deferred title, this cancellation-before-fire is the structurally fragile point.

### 3b. The explicit self-defence in the code
Note the comment at setEndScrollReserved (TermuxSessionTabsController.java:827-838): the author explicitly documents that clearing `mScrollSeqPending` to `-1` would make a still-queued `mScrollRunnable` self-drop and "cancel the right-end scroll before it runs (the 'never reaches right end' symptom)". The author fixed the *sequence* drop but **did not** guard `cancelEndScroll()` from removing a *listener that is merely pending a layout pass*. This is the residual gap.

---

## 4. Verdict

The listener logic in `runEndScroll()` is **internally correct** and is **not** a universal no-op:

- It **fires** (a child add requests layout; the observer identity is stable; self-removal at 573 is safe).
- It computes a **correct, current `maxScroll`** from `mTabsContainer.getMeasuredWidth()` read *after* layout (582-584), inclusive of the new tab and trailing `+` button.
- The only in-listener no-op — `startX == maxScroll` (594-599) — is **not** the general case: with a handful of tabs the viewport overflows, `startX ≈ 0 ≠ maxScroll`, so it proceeds to the self-driven `scrollTo` animation (600-621).

Therefore, since you observe "stays put in **all** cases," the failure is almost certainly **not** in the listener's measurement/animation math, but in the listener **being cancelled before its first layout pass** via `cancelEndScroll()` → `setEndScrollReserved(false)` (TermuxSessionTabsController.java:838 → 531). The `mPendingEndLayoutListener` is removed from the `ViewTreeObserver` and nulled (531-532) *between* `runEndScroll()` registering it (625) and the next layout pass dispatching it, so `onGlobalLayout()` never executes and `maxScroll` is never even read. The `startX == maxScroll` early return (594) is a red herring for the universal symptom; it would only apply when the strip is already at the end, which is not true for overflowing strips.

### Recommended confirmation steps (read-only)
1. Add logging at TermuxSessionTabsController.java:530-533 (`cancelEndScroll` listener-removal branch) and at 625 (registration) to capture whether a removal fires *after* registration but *before* `onGlobalLayout` (line 553) — i.e. registration timestamp in `mPendingEndLayoutListener` vs. removal in `cancelEndScroll`.
2. Log at SessionPagerManager.java:132 (`setEndScrollReserved(false)`) with the pager `state` and current `mScrollSeqPending`/`mPendingEndLayoutListener != null` to see if a drag-cancel or settle lands in the window between `scrollStripToEnd()` (TermuxTerminalSessionActivityClient.java:193) and the layout pass.
3. Verify `onTitleChanged`'s IMPORTANT-ORDER contract (TermuxTerminalSessionActivityClient.java:187-195) is honoured on *both* add paths; if `termuxSessionListNotifyUpdated()` ever runs *before* `scrollStripToEnd()` and `updateTabs` (line 185) clears `mEndScrollActive` / posts a competing CENTRE that itself triggers nothing for END, the END listener can be orphaned.

### Key file:line evidence
- Listener register: TermuxSessionTabsController.java:624-625
- Listener fire + null guard: TermuxSessionTabsController.java:553-558
- LayoutTransition settle gate: TermuxSessionTabsController.java:559-572
- Measurement: TermuxSessionTabsController.java:573-584
- Already-at-end no-op: TermuxSessionTabsController.java:593-599
- Self-driven scroll: TermuxSessionTabsController.java:600-621
- cancelEndScroll (listener removal): TermuxSessionTabsController.java:525-534, 530-532
- setEndScrollReserved(false) → cancelEndScroll: TermuxSessionTabsController.java:828-839 (838)
- Drag-cancel caller: SessionPagerManager.java:125-132 (132)
- Add-path reservation: SessionPagerManager.java:354; TermuxTerminalSessionActivityClient.java:606
- Deferred scrollStripToEnd: TermuxTerminalSessionActivityClient.java:83-89 (fallback), 193 (onTitleChanged)
- Strip geometry: activity_termux.xml:37-56; item_session_tab.xml:1-10

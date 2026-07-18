# Root-Cause Analysis: end-scroll `scrollTo(maxScroll)` does not reach the right end

**File:** `app/src/main/java/com/termux/app/terminal/TermuxSessionTabsController.java`
**Layout:** `app/src/main/res/layout/activity_termux.xml`
**Status:** READ-ONLY analysis. No files modified.
**Confirmed:** The `runEndScroll()` fix is built and running. The strip still does not reach the right end after adding a tab.

---

## TL;DR

The fix's `mEndScrollActive` guard **does** block the obvious "override" candidates (`onPageScrolled` at line 933 and `setCurrentSession`→CENTRE at line 878). So a *later `scrollTo(smallerX)`* does **not** normally win. The residual failure is therefore a **measurement/stale-width** defect in `runEndScroll()`: the gate `while (t.isRunning())` (line 568) is **insufficient to force a real wait** in the common add path, so the `onGlobalLayout` listener can proceed and read `childMeasuredW` (line 582) **before the newly-added tab's width is final** (or while the `(+)` button's footprint is not yet in the measured extent). That produces a `maxScroll` that is *too small*, clipped exactly like the original under-scroll bug.

In short: **the end-scroll's `scrollTo(maxScroll)` is measured wrong (too small), not overridden.**

---

## 1. The end-scroll mechanism (as built)

`scrollStripToEnd()` (lines 738-745):
- Line 741: `mEndScrollActive = true;` — reserves the strip.
- Line 744: `requestScroll(SCROLL_END, -1);` — single-owner scroll (lines 628-643) posts `mScrollRunnable`.

`mScrollRunnable` (lines 490-506) → `runEndScroll()` (lines 536-626).

`runEndScroll()`:
- Lines 549-625: attaches an `OnGlobalLayoutListener`.
- Line 568: `final boolean stillMoving = (t != null && t.isRunning());` — the wait gate.
- Lines 569-572: if `stillMoving && mRetries < 10`, return (wait for next layout pass).
- Lines 582-584: reads `childMeasuredW = mTabsContainer.getMeasuredWidth();`, `scrollW = mTabsScroll.getWidth();`, `maxScroll = Math.max(0, childMeasuredW - scrollW);`.
- Lines 601-606: self-driven `ValueAnimator.ofInt(startX, maxScroll)` whose update listener calls `mTabsScroll.scrollTo((int) anim.getAnimatedValue(), 0);` — bypasses HSV per-frame re-clamp (per the comment at lines 513-521).
- Line 591: detaches the `LayoutTransition` only *after* measuring, so the content width stays stable during the 250 ms animation.

`mTabsScroll` is the `HorizontalScrollView` `session_tabs_scroll` (resolved at line 55; declared `session_tabs_scroll` in `activity_termux.xml:30-35`). HSV `scrollTo(x,0)` clamps `x` to `[0, maxScrollContent]`. So if `maxScroll` is computed correctly, `scrollTo(maxScroll)` reaches the true right end including the trailing `(+)` button (`activity_termux.xml:44-54`, the last child of `session_tabs`).

---

## 2. Override theory — REFUTED by the guard (evidence)

Two later `scrollTo` calls could beat the end-scroll:

### (a) `onPageScrolled` tab-centre (line 933)
```
893  public void onPageScrolled(int position, float positionOffset) {
894      if (mTabsContainer == null || mTabsScroll == null) return;
895      if (!mSchemeApplied) return;
896      if (mEndScrollActive) {            // <-- guard
897          mPageScrollSuppressed++;
898          return;
899      }
900      ...
905      if (mEndScrollActive) return;     // duplicate guard (dead, line 896 already returned)
906      ...
933      mTabsScroll.scrollTo(targetScrollX, 0);
```
Because `scrollStripToEnd()` sets `mEndScrollActive = true` (line 741) **synchronously inside `updateTabs()`** (before any pager settle, see §4), `onPageScrolled` returns early at line 896 for the whole duration of the end-scroll. It cannot issue `scrollTo(tabCentreX)` while the end-scroll owns the strip. **Overriden by `onPageScrolled`? No.**

### (b) `setCurrentSession` → CENTRE (line 878)
```
839  public void setCurrentSession(int index) {
...
869      mCurrentSessionIndex = index;
876      if (mEndScrollActive) return;      // <-- guard
877      mEndScrollActive = false;
878      requestScroll(SCROLL_CENTRE, index);
```
Again, while `mEndScrollActive` is true, `setCurrentSession` returns at line 876 and never posts a competing CENTRE. `mEndScrollActive` is only cleared by:
- `setEndScrollReserved(false)` (lines 828-837) — fired **only** from `onPageScrollStateChanged(DRAGGING)` (`SessionPagerManager.java:132`), i.e. a genuine user swipe, not during an add;
- `setCurrentSession` reaching line 877 — which itself is gated by line 876;
- `updateTabs` remove branch (line 146) — tab close, not add.

So during a tab add + end-scroll, `mEndScrollActive` stays true and no CENTRE is posted. **Overriden by CENTRE? No.**

**Conclusion of §2:** The override candidates are effectively neutralized. This is consistent with the symptom being *consistent* ("never reaches right end") rather than *intermittent* — a race-driven override would be flaky, not deterministic.

---

## 3. Measurement theory — SUPPORTED (the real defect)

`runEndScroll()` waits only while `t.isRunning()` (line 568), where `t` is the container's `LayoutTransition`.

### 3.1 What transition actually runs on add?
The transition is configured at lines 63-72:
```
64  transition.enableTransitionType(LayoutTransition.CHANGING);
65  transition.disableTransitionType(LayoutTransition.APPEARING);
66  transition.disableTransitionType(LayoutTransition.CHANGE_APPEARING);
67  transition.disableTransitionType(LayoutTransition.DISAPPEARING);
68  transition.disableTransitionType(LayoutTransition.CHANGE_DISAPPEARING);
```
Comment at lines 56-62 states the intent: disable `APPEARING`/`CHANGE_APPEARING` so a newly-added tab is inserted at **full measured width** and the strip geometry settles within one layout pass.

Consequence: on a plain tab add, **no `APPEARING`/`CHANGE_APPEARING` animator runs**. The only transition type enabled is `CHANGING`, which fires when an *existing* child's bounds change — e.g. the title re-populate (see §3.3). **If the add alone does not start a `CHANGING` run, then `t.isRunning()` is `false` at the very first `onGlobalLayout`.**

### 3.2 The gate can pass on the first, premature layout pass
Lines 569-572:
```
569  if (stillMoving && mRetries < 10) {
570      mRetries++;
571      return;
572  }
```
If `stillMoving == false` on the first `onGlobalLayout` (no transition running yet), the listener **falls through immediately** and measures at line 582. The comment at lines 559-565 even anticipates this: *"once it has finished we must proceed on the very next layout pass — otherwise … we would starve and never scroll."* The fix prioritizes *never starving* over *always waiting for final geometry*.

When does the first `onGlobalLayout` fire relative to the new child's final width?
- `updateTabs()` calls `mTabsContainer.addView(tabView, …)` (line 140) → `requestLayout()`.
- `scrollStripToEnd()` → `requestScroll(SCROLL_END)` → `mTabsScroll.post(mScrollRunnable)` (line 641).
- `mScrollRunnable` runs, calls `runEndScroll()`, attaches the `OnGlobalLayoutListener`.
- The next global layout pass fires. **If the add's measure has completed, `getMeasuredWidth()` is correct.** But the listener can also fire on an *earlier* pass (e.g. a layout triggered by `populateTabView`'s `setPadding`/`setText` at lines 153-157, or by `applyTabHeightMode`, or by the `(+)` button's `setVisibility` at line 207) **before the new view is laid out at its final width.**

Because the gate only waits for `t.isRunning()` and the add path often has **no running transition at that moment**, the listener proceeds on this premature pass and reads a `childMeasuredW` that is **smaller than the true final extent** (new tab not yet at full width, or `(+)` button's `marginEnd`/`width` not yet counted). `maxScroll` is then too small → `scrollTo(maxScroll)` stops short → `(+)` clipped. This is exactly the *original* under-scroll bug the fix was meant to remove; the fix's wait is simply not triggered when nothing is "running".

### 3.3 The title re-populate can *also* shrink width after measurement
For the `(+)`-tap add, `addNewSession` (TermuxTerminalSessionActivityClient.java:553) → `createTermuxSession` → `updateTabs` runs with the **default title** (shell has not emitted an OSC title yet). `scrollStripToEnd()` (line 744) sets `mEndScrollActive=true` and queues END. Later `onTitleChanged` (lines 173-198) re-populates the title (`termuxSessionListNotifyUpdated()` at line 192) and calls `scrollStripToEnd()` again (line 193).

On the *first* END pass the listener may have measured the **wide default-title** width → `maxScroll` is computed for that width and the strip animates there. The later real-title re-populate shrinks the tab (a `CHANGING` run). The second `scrollStripToEnd()` re-arms the listener; it waits for that `CHANGING` and re-measures the narrower width, then animates to the *smaller* `maxScroll`. End state is correct for revealing `(+)`. **So a narrower final title is harmless** — it can only make `maxScroll` smaller, and `(+)` is always included.

The dangerous case is the **inverse**: measuring on a premature pass where the new tab/`(+)` is *not yet counted at full extent*, yielding a `maxScroll` that is **too small** and never corrected (if no subsequent `onTitleChanged` re-arms the listener). That is the deterministic "never reaches right end" symptom.

### 3.4 Why the `(+)` button's own visibility matters
`updateAddButtonVisibility` (lines 198-209) sets the `(+)` button `GONE` only at the session limit (`sessionCount >= MAX_SESSIONS`), else `VISIBLE`. In the normal add case it is `VISIBLE`, so its `36dp` width + `4dp` `marginEnd` (activity_termux.xml:47-49) are part of `childMeasuredW`. If the listener measures **before** the `(+)` view's layout/visibility is applied (e.g. before the `addView` of the new tab has shifted the `(+)` to its final position, or before a `requestLayout` from `setVisibility` settles), `childMeasuredW` excludes or under-counts that footprint → `maxScroll` short → `(+)` clipped. `runEndScroll` detaches the `LayoutTransition` at line 591 **after** measuring, so the measurement at line 582 is the only place the extent is captured — and it is captured at the wrong moment.

---

## 4. Confirmation that `mEndScrollActive` is set before any pager settle (so §2 holds)

`(+)`-tap path:
- `TermuxTerminalSessionActivityClient.addNewSession` (line 553) calls `service.createTermuxSession(...)`.
- `createTermuxSession` synchronously drives `termuxSessionListNotifyUpdated()` → `TermuxSessionTabsController.updateTabs()` → branch `newCount > sessionCount` (line 173) → `scrollStripToEnd()` (line 179) → `mEndScrollActive = true` (line 741) **before** line 596 `setCurrentSession(newTerminalSession)` runs.
- Line 596 `setCurrentSession` → `pager.setCurrentItem(index, true)` (line 425) triggers the smooth pager scroll + `onPageScrolled` calls, but all are suppressed at line 896 because `mEndScrollActive` is already true.
- `onPageSelected` → `onTerminalPageSelected` → `setCurrentSession(position)` (SessionPagerManager.java:517) also hits the line-876 guard and returns.

Swipe-to-add path:
- `commitPlaceholderToSession` (SessionPagerManager.java:326) calls `tabs.setEndScrollReserved(true)` (line 354) **and** `markPendingEndScrollSession` (line 356) *before* the posted `onTerminalPageSelected` (line 384). The title later arrives via `onTitleChanged` → `scrollStripToEnd` → END animates with `mEndScrollActive` true throughout.

In both paths the guard is established before any competing scroll can run. **Therefore the failure is not an override.**

---

## 5. Secondary, lower-probability override window (worth ruling in code review)

`requestScroll` (lines 628-643) uses `mScrollSeq`/`mScrollSeqPending` so a newer END beats an older CENTRE. But note: `setCurrentSession` line 878 is reached **only if `mEndScrollActive` is false**. There is exactly one scenario where a CENTRE could still post *after* the END animation starts and fight it: if `mEndScrollActive` is cleared (line 877) and a CENTRE is posted (line 878) *during* the END animation. The clear at line 877 only happens when `setCurrentSession` is entered with `mEndScrollActive == false`, which (per §4) does not occur on the add path. So this window is **not reachable** for the reported symptom. Listed only for completeness.

Also note lines 896-905 contain a **dead duplicate guard**: line 896 `if (mEndScrollActive) { … return; }` already returns, so the second `if (mEndScrollActive) return;` at line 905 is unreachable. Not a bug, but indicates the suppression logic at 896 is the one that matters.

---

## 6. Verdict

| Theory | Verdict | Evidence |
|--------|---------|----------|
| End-scroll `scrollTo(maxScroll)` overridden by a later `scrollTo(smallerX)` from `onPageScrolled` (line 933) | **Refuted** | `mEndScrollActive` guard at line 896 returns early for the whole add+end-scroll window (§2a). |
| Overridden by `setCurrentSession`→CENTRE (line 878) | **Refuted** | `mEndScrollActive` guard at line 876 returns early; cleared only on user DRAGGING or via the guarded path (§2b, §4). |
| `scrollTo(maxScroll)` measured with a **too-small `maxScroll`** (stale/partial width, `(+)` not yet counted) | **Supported** | Wait gate at line 568 only triggers when a `LayoutTransition` is running; the add path disables `APPEARING`/`CHANGE_APPEARING` (lines 65-66) so often **nothing is running** at the first `onGlobalLayout`, and the listener proceeds immediately and measures `childMeasuredW` (line 582) before the new tab / `(+)` footprint is final (§3). |

**Conclusion:** The end-scroll's `scrollTo(maxScroll)` is **measured wrong (maxScroll too small)**, not overridden. The `while (t.isRunning())` gate (line 568) is the wrong wait primitive for this path: with `APPEARING`/`CHANGE_APPEARING` disabled, a plain add frequently has **no running transition**, so the listener does not actually wait and reads geometry on a premature layout pass. The fix needs a geometry-stability gate that does not depend on a transition being "running" (e.g. wait until `mTabsContainer.getMeasuredWidth()` is stable across two consecutive layout passes, or explicitly postpone measurement until after the new view's width is known final) — otherwise `maxScroll` is computed short and the `(+)` button stays clipped, reproducing the original under-scroll symptom.

# WHY-FIX SYNTHESIS — The applied `runEndScroll()` width-gate fix does NOT make the strip reach the right end on a (+) tap

> READ-ONLY analysis. No files were modified by this report.
> Project: Termux app fork at `/data/local/projects/termux-app-ui-improve` (Android/Java).
> Status confirmed by the task brief and verified against the live working tree:
> the width-gate fix **is** built and running on device, yet the strip **still** does not
> reach the right end after adding a tab via the `(+)` button.

---

## 0. Executive verdict

**The width-gate was a red herring for the "never reaches the right end" symptom.**

The applied fix (removing `newWidth <= widthBefore`, replacing it with a `LayoutTransition.isRunning()` wait — verified in the working-tree diff of `TermuxSessionTabsController.java:542-572`) is *correct* as a measurement fix, but it only protects the **execution** of an end-scroll that, on the `(+)` tap path, is **never armed in time** and is **cancelled/disarmed before it can win**.

The true root cause is a **pager-reservation race** between the ViewPager2 settle and the end-scroll:

1. On the `(+)` tap add path, `mEndScrollActive` is **never set true before** the pager settles, so `onPageScrolled()` runs **unguarded** and writes `scrollTo(tabCentreX)` (`:933`) — a position **short of the true right end**.
2. The pager's `SCROLL_STATE_DRAGGING` transition fires `setEndScrollReserved(false)` (`:132` → `:828-837`), which `cancelEndScroll()`s any in-flight/queued END `ValueAnimator` and resets `mScrollSeqPending = -1` (`:834`), and then `setCurrentSession(idx)` posts a competing **CENTRE** scroll (`:876-878`).

The right-swipe path works precisely because it calls `setEndScrollReserved(true)` **up front** (`SessionPagerManager.java:354`) *before* the pager settles, so the same two defeat mechanisms are suppressed. The `(+)` tap path has **no equivalent arming** — that asymmetry is the entire bug.

**SINGLE true root cause:** the `(+)` add path never reserves the end-scroll (`setEndScrollReserved(true)` + `markPendingEndScrollSession`) before the pager's `setCurrentItem(index, true)` smooth settle, so the pager-coordination guard (`mEndScrollActive`) is false during the settle and the end-scroll is either overridden by `onPageScrolled` or cancelled by `setEndScrollReserved(false)`.

---

## 1. The two scroll drivers and the single guard (verified live)

- **`onPageScrolled(int, float)`** — `TermuxSessionTabsController.java:893-948`.
  During a pager settle it writes `mTabsScroll.scrollTo(targetScrollX, 0)` at `:933`, where `targetScrollX = interpolatedCenter - scrollW/2` (`:929-932`). For an add, `interpolatedCenter` is the centre of the new tab (or a blend toward the `(+)` button), which **centres the tab**, not the max scroll. It is gated by `mEndScrollActive`: at `:896-900` (and a redundant `:905` `if (mEndScrollActive) return;`), if `mEndScrollActive == true` the method returns early and does **not** write `scrollTo`.
- **`scrollStripToEnd()`** — `:738-745`. Sets `mEndScrollActive = true` (`:741`) then `requestScroll(SCROLL_END, -1)` (`:744`), which (after a layout settle) runs `runEndScroll()` (`:501`) → self-driven `ValueAnimator.scrollTo(maxScroll)` (`:601-621`).

**Conclusion:** the guard only works if `mEndScrollActive` is already `true` *during* the pager settle. The question is whether the `(+)` tap path sets it in time. It does **not**.

---

## 2. The `(+)` tap add — exact ordering (unguarded `onPageScrolled`)

`(+)` button tap → `addNewSession(false, null)` — `TermuxTerminalSessionActivityClient.java:536-598`.

- `:553` `service.createTermuxSession(...)` → fires `termuxSessionListNotifyUpdated()` → `updateTabs()` → `newCount > sessionCount` (`TermuxSessionTabsController.java:134-179`) → `scrollStripToEnd()` (`:179`) → `mEndScrollActive = true` (`:741`) **and** a queued `requestScroll(SCROLL_END)` runnable (`:744`→`:627`).
- `:596` `setCurrentSession(newTerminalSession)` → `setCurrentSession(session, true)` → `:425` `pager.setCurrentItem(index, true)` — a **smooth** programmatic scroll to the new (last) tab.

Now the pager settle runs:

1. `onPageScrollStateChanged(DRAGGING/SETTLING/IDLE)` fires. **No `setEndScrollReserved(true)` was called anywhere in this path.**
2. `onPageScrolled(position, offset)` fires repeatedly (forwarded at `SessionPagerManager.java:159-165`). Because `mEndScrollActive` is **still false at this moment** (the queued `mScrollRunnable` from `:744` has not been dequeued yet — it was only `post()`ed at `:627`; the pager frames run *before* the next looper turn), the guard at `:896` does **NOT** fire → `:933` `mTabsScroll.scrollTo(targetScrollX, 0)` executes, **parking the strip at a tab-centre X short of the right end.**
3. Settle completes → `onPageSelected(newIndex)` (`SessionPagerManager.java:184`) → `onTerminalPageSelected` (`:406-537`) → `:517` `tabs.setCurrentSession(position)`.
   - Controller `setCurrentSession(int index)` — `:839-879`: `mEndScrollActive` is *now* true (set at `:741`), so `:876 if (mEndScrollActive) return;` fires and it does **not** post a CENTRE here. Good — but the strip is already parked at `targetScrollX` from step 2.
   - `:525` `onSessionPageSelected` → `:466` `updateTabs(...)` runs again (`newCount == sessionCount`, no size change) → since `mEndScrollActive` is true, `:185 if (!mEndScrollActive) requestScroll(...)` is skipped. No new scroll requested.
4. **The queued `mScrollRunnable` (from `:744`) finally runs** → `runEndScroll()` (`:501`) → `OnGlobalLayoutListener` waits while `LayoutTransition.isRunning()` (`:566-572`) → eventually `scrollTo(maxScroll)` (`:606`).

So the END scroll *is* computed with the **correct `maxScroll`** and *does* issue `scrollTo(maxScroll)`. **But the strip was already parked at `targetScrollX < maxScroll` by step 2, and the END animator is racing the pager-state machinery** (see §3).

**This is the crux:** the width-gate fix computes `maxScroll` correctly now, but the strip never *stays* at `maxScroll` because (a) the unguarded `onPageScrolled` writes a lower `targetScrollX` first, and (b) `setEndScrollReserved(false)` cancels the END `ValueAnimator` mid-flight and re-enables the CENTRE path. The width-gate never determined whether `maxScroll` was correct — it was a *secondary* "short-label starvation" bug (aspect8) that the fix correctly addressed, but it was **not** what prevented the strip from reaching the right end.

---

## 3. THE CRUX: `setEndScrollReserved(false)` cancels/disarms the END scroll mid-flight

The pager smooth `setCurrentItem(index, true)` emits `onPageScrollStateChanged`. The handler:

`SessionPagerManager.java:120-135`
```java
public void onPageScrollStateChanged(int state) {
    if (state == ViewPager2.SCROLL_STATE_DRAGGING) {          // :125
        mUserScrollInProgress = true;
        // :131-132
        TermuxSessionTabsController tabs = ...;
        if (tabs != null) tabs.setEndScrollReserved(false);   // :132  ← DEFEAT
    } else if (state == ViewPager2.SCROLL_STATE_IDLE) {       // :133
        mUserScrollInProgress = false;
    }
    ...
}
```

`setEndScrollReserved(false)` — `TermuxSessionTabsController.java:828-837`
```java
public void setEndScrollReserved(boolean reserved) {
    mEndScrollActive = reserved;                              // :829  → false
    if (!reserved) {
        mScrollSeqPending = -1;                              // :834  → kills the queued END runnable
        cancelEndScroll();                                   // :835  → cancels the END ValueAnimator
    }
}
```

`cancelEndScroll()` (`:525-534`) cancels the `ValueAnimator` (`:526-529`) and removes the pending `OnGlobalLayoutListener` (`:530-533`). `mScrollSeqPending = -1` (`:834`) means: when `mScrollRunnable` eventually runs, `seq = -1` and `mScrollSeq` is some positive counter → `seq < mScrollSeq` is **true** → the runnable **drops itself at `:496`** and `runEndScroll()` never executes.

**Sequence for the `(+)` tap:**
- `scrollStripToEnd()` (`:179`) queued the END runnable; `mScrollSeqPending = mScrollSeq = N`.
- The pager smooth settle fires `onPageScrollStateChanged(DRAGGING)` → `setEndScrollReserved(false)` (`:132`) → `mScrollSeqPending = -1`, `mEndScrollActive = false`, `cancelEndScroll()`.
- `onPageScrolled` (now `mEndScrollActive == false`) writes `scrollTo(targetScrollX)` at `:933` — parks short.
- When the looper finally dequeues `mScrollRunnable`: `seq == -1 < N` → **`runEndScroll()` is never called** (`:496`). The END scroll is **dead.**
- Later `onTerminalPageSelected` → `setCurrentSession(idx)` at `:517` now sees `mEndScrollActive == false` → falls through `:876` → `:877-878` `mEndScrollActive = false; requestScroll(SCROLL_CENTRE, index)` → a **CENTRE** scroll re-centres the new tab.

This is the **dominant defeat mechanism (#2)**: the smooth `setCurrentItem(index, true)` *itself* triggers `setEndScrollReserved(false)` (`SessionPagerManager.java:132`), which `cancelEndScroll()`s the END scroll and resets `mScrollSeqPending = -1`, and the subsequent `setCurrentSession` posts a **CENTRE** scroll that wins.

---

## 4. Ranking of candidate defeat mechanisms

Given the width-gate fix is live and ineffective, ranked by how definitively each explains "the strip never reaches the right end":

### (2) `setEndScrollReserved(false)` / `cancelEndScroll()` cancels the running 250 ms end-scroll animation when the pager goes IDLE/DRAGGING — **DOMINANT (most definitive).**
- Exact evidence: `SessionPagerManager.java:125-132` → `TermuxSessionTabsController.java:828-837` (`:834 mScrollSeqPending = -1`, `:835 cancelEndScroll()`, `:829 mEndScrollActive = false`) → `:496` self-drop of `mScrollRunnable` → END scroll never runs; then `:876-878` posts competing **CENTRE**.
- This alone fully explains the symptom for the `(+)` tap: the END `ValueAnimator` is cancelled and the runnable self-drops, so the strip is left wherever `onPageScrolled` (`targetScrollX`) or the CENTRE scroll put it — never `maxScroll`.
- It is **100% deterministic** for a programmatic smooth `setCurrentItem` (which always reports DRAGGING/SETTLING), regardless of label length. This is why the symptom is not actually label-length-specific on the `(+)` path — the width-gate framing (aspect1/aspect8) mis-attributed a *guaranteed cancellation* to a *probabilistic width starvation*.

### (1) `onPageScrolled` unguarded (`mEndScrollActive` not armed before pager settle on `(+)` tap) writes `scrollTo(tabCentreX) < maxScroll` — **PRIMARY contributor (sets the wrong resting position).**
- Exact evidence: `TermuxSessionTabsController.java:893-900` guard fails because `mEndScrollActive == false`; `:932-933` writes `targetScrollX = interpolatedCenter - scrollW/2`, which centres the new tab, never `maxScroll`.
- This guarantees the strip is parked short *before* the END scroll is even attempted. Even if #2 were somehow neutralised, the strip would still visibly stop short on the first settle frame.

### (3) `mScrollSeqPending = -1` reset causes the END runnable to drop itself — **CONSEQUENCE of #2 (not independent).**
- Exact evidence: `:834` sets `-1`; `:493-494` reads it; `:496 if (seq < mScrollSeq) return;` drops. This is *how* #2 kills the END scroll. It is the precise mechanism by which the END runnable vanishes, but it is triggered by `setEndScrollReserved(false)` (covered by #2). It deserves its own line in the fix (don't let a reservation-clear blow away a *separately-armed* in-flight END scroll).

### (4) Our `onTitleChanged` reordering defeats the `mEndScrollActive` guard during re-populate — **NOT a defeat on the `(+)` tap path; minor/optional.**
- We reordered `onTitleChanged` to `termuxSessionListNotifyUpdated()` (`:192`) **then** `scrollStripToEnd()` (`:193`). For the `(+)` tap this is **irrelevant** because `mPendingEndScrollSession` is never set on that path (`addNewSession` → `setCurrentSession` does *not* call `markPendingEndScrollSession`, unlike `commitPlaceholderToSession` at `SessionPagerManager.java:356`). So `onTitleChanged`'s pending branch (`:182`) is never taken for a `(+)` tap; the reorder only affects the swipe path.
- For the swipe path the reorder is **correct and should be kept** (it ensures `runEndScroll` measures settled geometry). It does **not** break the guard — `scrollStripToEnd()` (`:738-745`) re-arms `mEndScrollActive = true` at `:741`, so the guard is intact before the title repaint is observed by the listener. Candidate #4 as a "regression" is **rejected for the `(+)` symptom**.

**Ranking summary:** #2 (cancellation) > #1 (unguarded write) > #3 (self-drop, a facet of #2) > #4 (not applicable to `(+)` tap).

---

## 5. Why the width-gate fix could never fix the symptom

The width-gate (`newWidth <= widthBefore` at the old `:559`) governed **whether `runEndScroll` would compute and issue `scrollTo(maxScroll)` at all** — and specifically whether it would *starve* on a short label. The fix (waiting on `LayoutTransition.isRunning()`) makes `runEndScroll` **correctly and unconditionally** issue `scrollTo(maxScroll)` with the right target. That part is sound.

**But the scroll IS being computed correctly and is then OVERRIDDEN/CANCELLED by the pager coordination:**
- The `maxScroll` value was always *correct* — the old gate only risked *never issuing* the scroll (short-label starvation). On the `(+)` tap the strip reaches the end only if the END scroll survives to run, which it does **not**, because:
  - the unguarded `onPageScrolled` (`:933`) writes `targetScrollX < maxScroll` first, and
  - `setEndScrollReserved(false)` (`:132`→`:828-837`) cancels the END `ValueAnimator` and resets `mScrollSeqPending = -1` so the queued END runnable **self-drops at `:496`**, then a CENTRE scroll (`:876-878`) re-centres.

So even with a *perfect* `maxScroll`, the strip never rests at the right end, because the end-scroll is killed by the pager-state handler and superseded by a CENTRE scroll. **The width-gate was a SECONDARY bug (short-label starvation / never-issue) but NOT the cause of "never reaches the right end."** That cause is the pager-reservation race — the `(+)` path simply never reserves the end-scroll, so the guard that would have suppressed `onPageScrolled` and prevented `setEndScrollReserved(false)` from winning is absent.

---

## 6. Why the right-swipe path works (asymmetry proof)

`commitPlaceholderToSession()` — `SessionPagerManager.java:326-386`:
- `:354` `tabs.setEndScrollReserved(true)` — arms `mEndScrollActive = true` **up front, before** the pager settle / any `onPageScrolled`.
- `:356` `client.markPendingEndScrollSession(...)` — arms the label-triggered `scrollStripToEnd`.

Because `mEndScrollActive` is already `true` when `onPageScrolled` fires during the settle, the guard at `:896-900` suppresses the `:933` `scrollTo`. The strip is not pre-parked short. Later `onTitleChanged`/fallback → `scrollStripToEnd()` keeps `mEndScrollActive = true` and runs `runEndScroll()` to `maxScroll` undisturbed.

**The `(+)` tap path (`addNewSession` → `setCurrentSession(session, true)` → `:425 pager.setCurrentItem(index, true)`) never calls `setEndScrollReserved(true)` or `markPendingEndScrollSession`.** That single missing arming is the asymmetry. `setCurrentSession(session, true)` at `TermuxTerminalSessionActivityClient.java:425` does a smooth `setCurrentItem` but never reserves the end-scroll.

---

## 7. CORRECT minimal fix (with exact current line numbers)

The fix must **mirror the swipe path**: arm the reservation **before** the pager settles on the `(+)` tap add path, and make the reservation **sticky through the END animation** so `setEndScrollReserved(false)` no longer cancels an in-flight END scroll.

### (A) ARM the reservation in the `(+)` tap add path — **NECESSARY (root cause).**
In `TermuxTerminalSessionActivityClient.addNewSession` (`:596`) and/or the controller's `setCurrentSession(int)`, call `setEndScrollReserved(true)` **before** `pager.setCurrentItem(index, true)` at `:425`, and arm the label-triggered scroll. Mirror `SessionPagerManager.java:354-356`:

In `setCurrentSession(TerminalSession session, boolean showToast)` — `TermuxTerminalSessionActivityClient.java:416-426`, insert **before** `:425`:
```java
// Reserve the right-end scroll BEFORE the smooth pager settle so onPageScrolled()'s
// instant scrollTo() is suppressed by mEndScrollActive during the settle, and so the
// end-scroll survives (mirror SessionPagerManager.java:354 for the right-swipe path).
TermuxSessionTabsController tabs = mActivity.getTermuxSessionTabsController();
if (tabs != null) tabs.setEndScrollReserved(true);
if (tabs != null) markPendingEndScrollSession(newTerminalSession); // :206 helper
pager.setCurrentItem(index, true);   // existing :425
```
This makes `mEndScrollActive == true` *before* the first `onPageScrolled` frame (`:896` guard then suppresses `:933`), and before `onPageScrollStateChanged(DRAGGING)` → `setEndScrollReserved(false)` at `SessionPagerManager.java:132`. **But note:** `setEndScrollReserved(false)` at `:132` would immediately disarm it again (see B below). So A alone is **not enough** — it must be paired with B.

### (B) Make `setEndScrollReserved(false)` NOT cancel an in-flight/armed END scroll — **NECESSARY (closes the DRAGGING cancellation).**
The intent of `:132 → setEndScrollReserved(false)` is: "a *user* swipe should take over and cancel the reserved end-scroll." But for a **programmatic smooth `setCurrentItem`** (the `(+)` add, `:425`), the DRAGGING state is *faked* by ViewPager2 for an animation, not a real user gesture. Cancelling there is wrong.

Two safe options (pick one; both are minimal):

**B1 (recommended, smallest):** In `onPageScrollStateChanged` (`SessionPagerManager.java:125-132`), only release the reservation for a *genuine* user drag — i.e. guard with `mUserScrollInProgress` having been set by a real `onPageScrolled` finger movement, OR simply **do not call `setEndScrollReserved(false)` for the programmatic add**. Concretely, drop the `setEndScrollReserved(false)` call from the DRAGGING branch (`:131-132`) and instead clear `mEndScrollActive` only when a *real* user navigation settles (e.g. in `resetPageSelection` or in `onTerminalPageSelected` when not an add). The END scroll already clears `mEndScrollActive` itself once it fires (via `runEndScroll`/animator end), and the CENTRE path is already gated by `if (mEndScrollActive) return` at `:633` and `:876`.

**B2 (keeps the call but makes it non-destructive to a running END):** Change `setEndScrollReserved(false)` (`TermuxSessionTabsController.java:828-837`) so that when an END scroll is *armed or in flight*, it only **clears the reservation flag** but does **NOT** `cancelEndScroll()` or reset `mScrollSeqPending = -1`:
```java
public void setEndScrollReserved(boolean reserved) {
    mEndScrollActive = reserved;
    if (!reserved) {
        // Do NOT cancel an in-flight/armed END scroll here. A programmatic smooth
        // setCurrentItem() reports DRAGGING for its own animation; cancelling the END
        // scroll there strands the strip short of the right end. The END scroll clears
        // mEndScrollActive itself on completion; a real user swipe clears it via
        // resetPageSelection/onTerminalPageSelected.
        // mScrollSeqPending = -1;   // REMOVED
        // cancelEndScroll();        // REMOVED (or only cancel if no END is active)
    }
}
```
The cheapest correct version: **keep `cancelEndScroll()` only if `mEndScrollActive` was already false** (i.e. no END scroll pending). Since on the `(+)` path `mEndScrollActive` is now `true` (from A), B2's guarded version would skip the cancellation and let the END `ValueAnimator` run to `maxScroll`.

### (C) Fix the `mScrollSeqPending = -1` reset — **NECESSARY as part of B** (it is the self-drop trigger at `:496`).
Removing `:834 mScrollSeqPending = -1` (or guarding it as in B2) prevents the queued END runnable from self-dropping at `:496`. This is the precise line that makes the END scroll vanish. Required.

### (D) Revert the `onTitleChanged` reordering (B) — **OPTIONAL, NOT RECOMMENDED.**
The reorder (`termuxSessionListNotifyUpdated()` at `:192` then `scrollStripToEnd()` at `:193`) is **correct and should stay** for the swipe path (it makes `runEndScroll` measure settled geometry). It does **not** defeat the `mEndScrollActive` guard on the `(+)` path (that path never enters the pending branch). Reverting it would re-introduce the short-label measurement race on the swipe path. **Keep the reorder.**

### (E) The width-gate fix itself — **KEEP (correct, but insufficient alone).**
The removal of `widthBefore`/`newWidth <= widthBefore` (diff at `:542-572`) is a legitimate measurement fix and must remain. It is simply not the cause of the `(+)` symptom.

---

## 8. Which fixes are necessary vs optional

| Fix | Necessary? | Why |
|---|---|---|
| **A** — arm `setEndScrollReserved(true)` + `markPendingEndScrollSession` before `pager.setCurrentItem(index, true)` in `setCurrentSession` (`TermuxTerminalSessionActivityClient.java:425`) | **NECESSARY** | Directly mirrors swipe path `SessionPagerManager.java:354-356`; gives `onPageScrolled` guard (`TermuxSessionTabsController.java:896`) something to suppress. Root-cause closure. |
| **B** — `setEndScrollReserved(false)` must not cancel an in-flight/armed END scroll (`TermuxSessionTabsController.java:828-837`, call site `SessionPagerManager.java:132`) | **NECESSARY** | Stops the DRAGGING-induced cancellation that currently kills the END `ValueAnimator` and re-enables the CENTRE override. |
| **C** — stop `mScrollSeqPending = -1` from self-dropping the END runnable (`TermuxSessionTabsController.java:834`/`:496`) | **NECESSARY** (folded into B) | Without it the queued END runnable still vanishes at `:496`. |
| **D** — revert the `onTitleChanged` reorder (`TermuxTerminalSessionActivityClient.java:192-193`) | **OPTIONAL / DO NOT** | Correct for the swipe path; irrelevant to `(+)` symptom; reverting re-breaks short-label measurement. |
| **E** — keep the width-gate→`LayoutTransition.isRunning()` fix (`TermuxSessionTabsController.java:542-572`) | **KEEP** | Legitimate secondary fix; not the cause but harmless and correct. |

**Minimal correct change set: A + B(+C) + keep D/E as-is.**

---

## 9. Precise execution order AFTER the correct fix (target state)

```
(+) tap → addNewSession() :553 createTermuxSession → updateTabs() :179 scrollStripToEnd()
            → mEndScrollActive = true :741, queue END runnable (mScrollSeqPending = N)
       :596 setCurrentSession(new) → :425 pager.setCurrentItem(index, true)
            [NEW A] setEndScrollReserved(true) BEFORE :425 → mEndScrollActive stays true
            [NEW A] markPendingEndScrollSession(new) arms label/fallback scroll
       pager settle:
         onPageScrollStateChanged(DRAGGING) :125 → setEndScrollReserved(false) :132
            [NEW B] does NOT cancelEndScroll() / does NOT reset mScrollSeqPending
            → mEndScrollActive remains true (END still reserved)
         onPageScrolled() :893 → mEndScrollActive==true → :896 early return (NO scrollTo)
         onPageSelected(newIndex) → onTerminalPageSelected :517 setCurrentSession(idx)
            → mEndScrollActive true → :876 return (NO CENTRE)
         mScrollRunnable dequeued → seq == N == mScrollSeq → :496 passes → runEndScroll()
            → wait LayoutTransition :566-572 → scrollTo(maxScroll) :606 → strip reaches right end ✅
       shell emits OSC title → onTitleChanged :182 (mPendingEndScrollSession == session)
            → termuxSessionListNotifyUpdated() :192, scrollStripToEnd() :193 (re-arm, no-op)
            → END already fired; mEndScrollActive still true → no CENTRE override
```

---

## 10. Final verdict

- **The width-gate was a red herring for the "never reaches the right end" symptom.** It governed *whether* `runEndScroll` issued `scrollTo(maxScroll)` (short-label starvation) — a real but secondary bug, now correctly fixed. It never governed *whether the issued end-scroll survived the pager*.
- **The true cause is the pager-reservation race:** the `(+)` tap add path never calls `setEndScrollReserved(true)` (mirroring `SessionPagerManager.java:354`) before the pager settles, so (1) `onPageScrolled` (`:933`) writes `scrollTo(tabCentreX) < maxScroll` unguarded, and (2) the pager's `SCROLL_STATE_DRAGGING` → `setEndScrollReserved(false)` (`SessionPagerManager.java:132` → `TermuxSessionTabsController.java:828-837`) cancels the END `ValueAnimator`, resets `mScrollSeqPending = -1` (self-drop at `:496`), and lets a CENTRE scroll (`:876-878`) re-centre the tab — stranding the strip short of the right end.
- **The minimal correct fix:** arm the reservation before `pager.setCurrentItem` in the `(+)` add path (mirror `SessionPagerManager.java:354-356`) **and** make `setEndScrollReserved(false)` non-destructive to an in-flight/armed END scroll (remove/guard the `cancelEndScroll()` + `mScrollSeqPending = -1` at `TermuxSessionTabsController.java:834-835`). Keep the `onTitleChanged` reorder and the width-gate fix as they are.

*Report generated by READ-ONLY synthesis. No source files were modified.*

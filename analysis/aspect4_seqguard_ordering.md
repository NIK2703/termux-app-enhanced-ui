# Aspect 4 — Sequence Guard & Last-Call-Wins Ordering Analysis

**File under analysis:** `app/src/main/java/com/termux/app/terminal/TermuxSessionTabsController.java`
**Project:** Termux fork (`termux-app-ui-improve`), Android / Java
**Mode:** READ-ONLY diagnostic. No files were modified.

---

## 1. Symptom restated

When a **new terminal tab is created**, the tab-strip auto-scroll to the right end fires **only when the new tab's label is LONG**. With a **SHORT label** (e.g. `"~"`), the end-scroll does **not** happen.

The reported hypothesis to test: is this caused by the **monotonic sequence guard / last-call-wins ordering** (`mScrollSeq` / `mScrollSeqPending` / `mScrollRunnable`), or by **listener starvation** (the `OnGlobalLayoutListener` in `runEndScroll` never firing because no further layout pass occurs)?

---

## 2. The end-scroll control flow (as built)

### 2.1 Reservation (BEFORE the label is set)

`SessionPagerManager.commitPlaceholder()` (line 354) calls:

```
tabs.setEndScrollReserved(true);          // SessionPagerManager.java:354
client.markPendingEndScrollSession(...)   // SessionPagerManager.java:356  → arms 250ms fallback
```

`setEndScrollReserved(true)` (controller lines **814-815**) simply sets `mEndScrollActive = true`. It does **not** post any scroll. With `reserved=true` it does **not** reset `mScrollSeqPending` or cancel anything.

So at reservation time: **`mEndScrollActive == true`**, and **no scroll runnable is queued yet**.

### 2.2 The actual end-scroll trigger (AFTER the label is set)

The end-scroll itself is fired from **one of two places**, both in `TermuxTerminalSessionActivityClient.java`:

- `onTitleChanged()` when the new session's title arrives → `tabs.scrollStripToEnd();` (line **187**)
- The 250ms `mEndScrollFallback` runnable → `tabs.scrollStripToEnd();` (line **86**)

`scrollStripToEnd()` (controller lines **724-731**):

```
mEndScrollActive = true;                 // line 727
requestScroll(SCROLL_END, -1);           // line 730
```

Note: `mEndScrollActive` is **set true again** immediately before `requestScroll`, but it was already true from the reservation. Order within `scrollStripToEnd` is irrelevant to our analysis — what matters is that it then calls `requestScroll(SCROLL_END)`.

### 2.3 `requestScroll` (lines **615-629**)

```
private void requestScroll(int mode, int index) {
    if (mode == SCROLL_CENTRE) {
        if (mEndScrollActive) return;    // line 619 — CENTRE blocked while END active
        mPendingTabScrollIndex = index;
    }
    mPendingScrollMode = mode;           // line 622
    mScrollSeq++;                        // line 623
    mScrollSeqPending = mScrollSeq;      // line 624
    if (mTabsScroll != null) {
        mTabsScroll.removeCallbacks(mScrollRunnable);   // line 626
        mTabsScroll.post(mScrollRunnable);              // line 627
    }
}
```

For an END request: `mode != SCROLL_CENTRE`, so the `mEndScrollActive` early-return at line 619 is **skipped**. `mPendingScrollMode = SCROLL_END`, `mScrollSeq++`, `mScrollSeqPending = mScrollSeq`, and `mScrollRunnable` is posted.

### 2.4 The runnable (lines **490-506**)

```
public void run() {
    final long seq = mScrollSeqPending;  // line 493
    mScrollSeqPending = -1;              // line 494
    if (seq < mScrollSeq) return;        // line 496 — drop if superseded
    ...
    final int mode = mPendingScrollMode; // line 498
    mPendingScrollMode = SCROLL_NONE;    // line 499
    if (mode == SCROLL_END) runEndScroll();        // line 500-501
    else if (mode == SCROLL_CENTRE) runCentreScroll(seq);  // line 502-503
}
```

### 2.5 `runEndScroll` (lines **536-612**) — the deferred-layout listener

```
cancelEndScroll();                              // line 540
final int widthBefore = mTabsContainer.getMeasuredWidth();   // line 544
final OnGlobalLayoutListener listener = new ... {            // lines 546-609
    private int mRetries = 0;                   // line 548
    onGlobalLayout() {                          // line 550
        final int newWidth = mTabsContainer.getMeasuredWidth();  // line 556
        if (newWidth <= widthBefore && mRetries < 6) {  // line 559
            mRetries++;                         // line 560
            return;                             // line 561 — wait for growth
        }
        ... compute maxScroll, run ValueAnimator ...         // lines 563-607
    }
};
mPendingEndLayoutListener = listener;          // line 610
mTabsContainer.getViewTreeObserver().addOnGlobalLayoutListener(listener);  // line 611
```

**Critical observation (line 559):**
```java
if (newWidth <= widthBefore && mRetries < 6) { mRetries++; return; }
```

The listener **only proceeds to scroll once `newWidth > widthBefore`** (or after `mRetries` reaches 6). If the container width **never exceeds `widthBefore`** AND fewer than 6 layout passes occur, the listener simply re-returns every time and **never scrolls**. The `mRetries` cap of 6 is a *bounded* escape hatch, but each retry depends on `onGlobalLayout()` actually being **called again**.

---

## 3. Answering the four focus questions

### Q1 — Does `updateTabs` issue both END and CENTRE for a new tab?

No. In `updateTabs()` (lines **116-191**) the branches are mutually exclusive via `if / else if / else if`:

- `if (!mBuilt)` → `requestScroll(SCROLL_CENTRE, ...)` (line 171) — only on the first cold-start build.
- `else if (newCount > sessionCount)` → `scrollStripToEnd()` (line 179) → END only. This is the add path. It does **not** also call CENTRE.
- `else if (currentSessionIndex >= 0)` → `if (!mEndScrollActive) requestScroll(SCROLL_CENTRE, ...)` (line 185). This is the no-size-change path.

For a tab **add**, only the `newCount > sessionCount` branch runs → only END is requested. Good — there is **no competing CENTRE posted from `updateTabs` itself** on add.

Additionally, `setCurrentSession()` (lines **825-865**) has at line **862**:
```java
if (mEndScrollActive) return;   // line 862 — does NOT post CENTRE while END reserved
```
So because `mEndScrollActive == true` (set at reservation, line 815, and again at line 727), any `setCurrentSession` (e.g. from page settle at SessionPagerManager.java:517) **returns early and posts no CENTRE**. Confirmed: no CENTRE competes with the END on a tab add.

### Q2 — For a SHORT tab, is the END request superseded/dropped by a stale CENTRE?

Trace `mScrollSeqPending` for a short tab add:

1. Reservation (line 354) sets `mEndScrollActive=true` only — **no `requestScroll`**, so `mScrollSeqPending` is left at whatever it was (typically `-1` after the last run, or a stale value).
2. The new tab is added → `updateTabs` runs the END branch (line 179) → `scrollStripToEnd()` → `requestScroll(SCROLL_END)`:
   - `mScrollSeq++`, `mScrollSeqPending = mScrollSeq` (latest).
   - `removeCallbacks(mScrollRunnable)` then `post(mScrollRunnable)`.
3. `mScrollRunnable` runs: `seq = mScrollSeqPending` (the just-issued END seq). Since no *newer* request is issued afterward in this flow, `seq < mScrollSeq` is **false** → it does **not** drop. `runEndScroll()` is entered.

There is **no prior CENTRE runnable** in this flow that could out-rank the END. The sequence guard's purpose (lines 479-483) is to stop a *stale CENTRE from clobbering a newer END*. But here the END is the **newest** request, so the guard **cannot** be the cause of the scroll not firing — in fact the guard is *neutral/safe* for the END. A short label at most affects **width**, not sequencing.

**Conclusion for Q2:** the END is not superseded. The sequence guard is correctly last-call-wins in END's favor.

### Q3 — Could multiple `mScrollRunnable` posts be queued / wrong listener re-entry?

`requestScroll` always calls `mTabsScroll.removeCallbacks(mScrollRunnable)` (line 626) **before** `mTabsScroll.post(mScrollRunnable)` (line 627). `removeCallbacks` removes **all** pending posts of that single `Runnable` instance, so at most **one** `mScrollRunnable` is ever queued. The `mPendingEndLayoutListener` is a fresh object each `runEndScroll` call, but `cancelEndScroll()` (lines 525-534) removes any previously registered one before a new is added. So no duplicate/stale listeners accrue. This path is sound and **independent of label length**.

### Q4 — Listener starvation: does a SHORT label prevent the layout listener from ever firing the scroll?

This is the crux. In `runEndScroll`:

- `widthBefore = mTabsContainer.getMeasuredWidth()` (line 544) is captured **at the moment `runEndScroll` runs** — i.e. *after* the new tab has already been added to the container and the container has (at least once) been laid out to include it.
- The listener (line 559) requires `newWidth > widthBefore` to proceed, else it re-`return`s until `mRetries` hits 6.

**Key insight about `widthBefore`:** By the time `scrollStripToEnd()` is finally called (from `onTitleChanged` / fallback, which is *after* the tab is created and the title is set), the container has **already** been laid out **with the new tab at its final width**. Therefore `widthBefore` already reflects the width *including* the new tab.

- **Long label:** the new tab is wide. Between `widthBefore` capture and a subsequent layout pass, something can still nudge width (e.g. the (+) button `GONE`/`VISIBLE` toggle in `updateAddButtonVisibility`, line 207; or a re-measure when the OSC title settles). On at least one of the ≤6 retries `newWidth` can exceed `widthBefore` → listener proceeds → scroll fires.
- **Short label (`"~"`):** by the time `scrollStripToEnd` runs, the container width is already at/near its final small value. Every subsequent layout pass yields `newWidth <= widthBefore` (the strip does **not** keep growing). The listener hits the `mRetries < 6` guard repeatedly and **returns without ever scrolling** — unless exactly 6 more layout passes happen to fire within the window. With a short tab the geometry is *stable*, so **few or no additional layout passes occur**, and the 6-retry counter is only decremented **inside** `onGlobalLayout`, which itself needs to be called to decrement it. If no further global layout callback arrives at all, the listener stays registered forever (leak) and **never scrolls**.

This matches the symptom precisely: **long label → a layout pass with growth → scroll fires; short label → no growth → listener starves → no scroll.** The behavior is **driven by container width dynamics, not label text per se**, but in practice a short label is exactly what makes the strip width settle immediately with no further growth.

---

## 4. Is the cause the sequence guard/ordering, or the listener starvation?

**The cause is the listener starvation (no further layout pass), NOT the sequence guard / last-call-wins ordering.**

Evidence:

1. **The sequence guard protects END, it cannot suppress it.** The guard (`mScrollSeqPending`/`mScrollSeq`, lines 484-485, 493-496) only ever *drops a runnable whose seq is older than the latest issued request*. On an add, the END request is the **latest**, so its runnable is never dropped (Q2). The guard is functioning exactly as intended and is label-length-independent.

2. **No competing CENTRE exists on the add path.** `updateTabs` (line 179) posts only END; `setCurrentSession` (line 862) early-returns because `mEndScrollActive` is true; the `!mEndScrollActive` gate at line 619 also blocks CENTRE. So there is nothing for the guard to arbitrate against in the failure case.

3. **The starvation is real and width-dependent.** `runEndScroll` line 559 makes progression contingent on `newWidth > widthBefore`. Because `scrollStripToEnd` is deferred until *after* the tab is added and titled (Section 2.2), `widthBefore` (line 544) already includes the new tab. A short tab produces no further width growth, so the `newWidth <= widthBefore` branch keeps returning. The only escape is the `mRetries < 6` cap, but that counter is only advanced **inside** `onGlobalLayout` — if no further layout pass is scheduled, the callback never fires again and the scroll is never triggered. Label length therefore maps directly onto "does width keep growing after capture."

4. **The ordering machinery is correct and would work IF the listener fired.** The runnable posts once (line 627), is not duplicated (removeCallbacks at 626), and dispatches to `runEndScroll` (line 501) which *would* scroll if its listener ever saw `newWidth > widthBefore`. The defect is upstream of the guard: the listener's trigger condition is unsatisfiable for a stable (short) strip.

---

## 5. Root-cause summary

| Hypothesis | Verdict | Why |
|---|---|---|
| Sequence guard drops the END runnable | **Rejected** | END is the newest request (lines 623-624); `seq < mScrollSeq` (496) is false → not dropped. Guard is label-independent. |
| Last-call-wins lets a CENTRE clobber END | **Rejected** | No CENTRE is posted on add (lines 179, 619, 862). |
| Duplicate/queued runnables | **Rejected** | `removeCallbacks` + single `Runnable` instance (lines 626-627). |
| **Listener starvation (no layout pass)** | **CONFIRMED** | Line 559 requires `newWidth > widthBefore`; for a short tab width is already final at capture (544), so no growth → listener perpetually re-returns; scroll never fires. |

**Root cause:** `runEndScroll` keys its "now the strip grew, scroll" decision on a **relative** width comparison (`newWidth <= widthBefore`, line 559) measured **after** the new tab is already laid out. For a short label the strip width is already final, so the growth condition is never met and the deferred scroll is starved. This is a **measurement-timing / trigger-condition bug**, not a sequencing bug.

---

## 6. Suggested fix direction (non-exhaustive, for the implementing engineer)

The condition at line 559 conflates "wait for layout" with "wait for growth." A more robust trigger would:

- Fire as soon as the container holds the expected number of tabs **and** has a non-zero measured width (mirroring the `runCentreScroll` guard at lines 672-676, which waits for `getChildCount()-1 > idx` and `MeasuredWidth > 0` rather than for growth).
- Or in `scrollStripToEnd`, capture `widthBefore` **before** the tab is inserted (i.e. earlier, at reservation time) so that by the time `runEndScroll` runs, `widthBefore` is the *pre-add* width and `newWidth > widthBefore` is guaranteed true.

Either change removes the label-length dependence while leaving the (correct) sequence guard untouched.

---

*Report generated by READ-ONLY diagnostic analysis. No source files were modified.*

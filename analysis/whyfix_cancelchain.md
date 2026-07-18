# Root-Cause Analysis: Why `runEndScroll()`'s fix never takes effect (END scroll dropped after a tab add)

**Scope:** READ-ONLY root-cause analysis of the strip-end-scroll cancellation / sequence-guard
mechanics in `TermuxSessionTabsController` (TSTC) and its callers in `SessionPagerManager`
(SPM) and `TermuxTerminalSessionActivityClient` (TTSC). No files were modified.

**Confirmed premise:** The fix inside `runEndScroll()` (TermuxSessionTabsController.java:536-623)
**is** built and running. Yet after adding a tab the strip does **not** reach the right end.
Therefore the bug is **not** in `runEndScroll()` itself — it is that the END scroll request is
cancelled or dropped *before / instead of* ever being honoured. This report maps the
cancellation chain and the sequence-guard, and concludes which one fires during an add.

---

## 1. The cancellation chain — every place that can kill the END scroll

### 1.1 `cancelEndScroll()` — TSTC:525-534
```java
private void cancelEndScroll() {
    if (mEndScrollAnim != null) {                       // 526
        mEndScrollAnim.cancel();                        // 527
        mEndScrollAnim = null;                          // 528
    }
    if (mPendingEndLayoutListener != null && ...) {     // 530
        mTabsContainer.getViewTreeObserver()
            .removeOnGlobalLayoutListener(mPendingEndLayoutListener);  // 531
        mPendingEndLayoutListener = null;               // 532
    }
}
```
This does **two** independent things:
- Cancels a *running* `ValueAnimator` (`mEndScrollAnim`) — i.e. stops a scroll that already started.
- Removes the *pending* `OnGlobalLayoutListener` (`mPendingEndLayoutListener`) — i.e. prevents a
  scroll that has been queued but not yet fired (the listener is what eventually starts the anim).

### 1.2 `setEndScrollReserved(false)` — TSTC:828-837
```java
public void setEndScrollReserved(boolean reserved) {
    mEndScrollActive = reserved;                       // 829
    if (!reserved) {
        mScrollSeqPending = -1;                        // 834  <-- resets the sequence guard
        cancelEndScroll();                             // 835  <-- cancels anim + removes listener
    }
}
```

### 1.3 All callers of `cancelEndScroll()` / `setEndScrollReserved(false)` in the codebase

| Call site | File:line | Context | Reachable during a tab add + settle? |
|---|---|---|---|
| `cancelEndScroll()` (internal) | TSTC:540 | called at the start of `runEndScroll()` to restart cleanly | yes, but self-cancellation only — harmless (restarts) |
| `cancelEndScroll()` (via `setEndScrollReserved(false)`) | TSTC:835 | **SPM:132** | **YES — see §2** |
| `setEndScrollReserved(false)` | SPM:132 | `onPageScrollStateChanged(SCROLL_STATE_DRAGGING)` | **YES — fires on the add's pager settle** |
| `setEndScrollReserved(true)` | SPM:354 | `commitPlaceholderToSession()` | sets reservation (does not cancel) |
| `setEndScrollReserved(false)` (no other caller) | — | — | — |
| `mScrollSeqPending = -1` (direct) | TSTC:834 | inside `setEndScrollReserved(false)` | same as SPM:132 |

**Critical finding:** The **only external caller** of `setEndScrollReserved(false)` is
`SessionPagerManager.onPageScrollStateChanged()` at **SPM:132**, and it fires on
`SCROLL_STATE_DRAGGING`. The add-by-swipe gesture ends with the pager settling, and the
`DRAGGING → SETTLING → IDLE` transition *starts* with a `DRAGGING` state change. So this
cancellation is **highly reachable during an add** — it is the primary suspect for the
"strip never reaches the end" symptom.

(Note: `runEndScroll()` itself calls `cancelEndScroll()` at line 540 — but only to cancel a
*previous* in-flight scroll before starting a fresh one. That is intentional self-restart, not
a defect.)

---

## 2. The add flow, traced with line numbers

Two add paths exist; the *right-swipe-to-add* path is the one that exercises the full
reservation machinery. (The `(+)` button path funnels through `scrollStripToEnd()` too, but
without the `setEndScrollReserved(true)` guards, so it is less affected — it is the swipe path
that demonstrates the cancellation.)

### 2.1 Swipe-add path

1. **SPM:326** `commitPlaceholderToSession()` runs when the user swipes onto the placeholder.
2. **SPM:354** `tabs.setEndScrollReserved(true)` → sets `mEndScrollActive = true`.
   - This makes `onPageScrolled()` (TSTC:896) suppress finger-follow `scrollTo()`, and makes
     `setCurrentSession()` (TSTC:876) early-return, and makes `requestScroll(SCROLL_CENTRE)`
     drop (TSTC:633). Good — reservation is in force.
3. **SPM:356** `client.markPendingEndScrollSession(session)` → TTSC:206-211 arms `mPendingEndScrollSession`
   and a 250 ms fallback `mEndScrollFallback`.
4. **SPM:372** `mTerminalPager.post(() -> { ... onTerminalPageSelected(idx); })` →
   **SPM:384** → **SPM:517** `tabs.setCurrentSession(idx)`.
   - `setCurrentSession()` at **TSTC:876** sees `mEndScrollActive == true` and **returns early**,
     so it does **not** post a competing CENTRE. Reservation still intact.
5. The user lifts the finger → the pager emits a **`SCROLL_STATE_DRAGGING`** change (the very
   first state transition of the settle).
   - **SPM:125-132** sets `mUserScrollInProgress = false` and calls **`tabs.setEndScrollReserved(false)`**.
   - **This is the killer.** `setEndScrollReserved(false)` (TSTC:828-837) executes:
     - `mEndScrollActive = false` (TSTC:829) — reservation released.
     - `mScrollSeqPending = -1` (TSTC:834) — **wipes the pending END sequence** (see §3).
     - `cancelEndScroll()` (TSTC:835) — **removes the pending `OnGlobalLayoutListener`**
       (`mPendingEndLayoutListener`) if one was already registered.

6. **Later**, the shell emits the OSC title for the new session → TTSC:174 `onTitleChanged()` →
   **TTSC:192-193** calls `termuxSessionListNotifyUpdated()` then **`tabs.scrollStripToEnd()`**.
   - `scrollStripToEnd()` (TSTC:738-745) sets `mEndScrollActive = true` again and posts
     `requestScroll(SCROLL_END, -1)`.
   - This *re-arms* the END scroll — so in the clean swipe case the END scroll can still fire
     from step 6 *if* step 5 has not already destroyed something. But note the 250 ms fallback
     (TTSC:210) and the order: `setEndScrollReserved(false)` at step 5 cleared the listener that
     `scrollStripToEnd()` had not yet posted. As long as step 6 runs after step 5, step 6
     re-posts and survives **unless step 5 also ran after step 6's listener was registered**.

The race that defeats the fix: **`onTitleChanged` (step 6) often runs *before* the user lifts
the finger is fully processed**, or the 250 ms fallback fires while the pager is still mid-settle,
registering the `OnGlobalLayoutListener` — and then the eventual `DRAGGING` state change at
SPM:132 calls `cancelEndScroll()` (TSTC:835) which **removes that very listener**. The END
scroll is cancelled before it ever starts the animator.

**Conclusion for the cancel path:** `setEndScrollReserved(false)` at **SPM:132 → TSTC:835** is
the dominant cancellation of the END scroll during an add. It is reached because a swipe-to-add
gesture *by definition* produces a `DRAGGING` state transition as it settles, and the code treats
"user started dragging" as "release the reserved end-scroll" — even though the only reason the
drag happened is the add gesture itself.

---

## 3. The sequence-guard bug in `mScrollRunnable` — TSTC:490-506

```java
private final Runnable mScrollRunnable = new Runnable() {
    @Override
    public void run() {
        final long seq = mScrollSeqPending;     // 493  capture shared pending seq
        mScrollSeqPending = -1;                 // 494  <-- RESETS shared flag to -1
        if (seq < mScrollSeq) return;           // 496  drop if superseded
        ...
        if (mode == SCROLL_END) runEndScroll();          // 500
        else if (mode == SCROLL_CENTRE) runCentreScroll(seq);  // 503
    }
};
```

### 3.1 How `requestScroll` posts — TSTC:629-643
```java
private void requestScroll(int mode, int index) {
    if (mode == SCROLL_CENTRE) {
        if (mEndScrollActive) return;          // 633  CENTRE suppressed while END active
        mPendingTabScrollIndex = index;
    }
    mPendingScrollMode = mode;                 // 636
    mScrollSeq++;                              // 637  bump monotonic seq
    mScrollSeqPending = mScrollSeq;            // 638  publish latest seq to the runnable
    if (mTabsScroll != null) {
        mTabsScroll.removeCallbacks(mScrollRunnable);  // 640  dedupe one pending runnable
        mTabsScroll.post(mScrollRunnable);             // 641
    }
}
```

### 3.2 The defect: shared `mScrollSeqPending` is reset by the *first* runnable to execute

The guard relies on `mScrollSeqPending` holding the sequence of the **currently-queued** runnable.
But:

- `requestScroll` sets `mScrollSeqPending = mScrollSeq` (638) and posts **one** runnable
  (640-641 `removeCallbacks` ensures only one is ever *pending*).
- The runnable, on **entry**, captures `seq = mScrollSeqPending` (493) then **immediately
  sets `mScrollSeqPending = -1`** (494).

Two ways this bites during an add:

#### (a) A request arrives *while a runnable is already executing*

Suppose the END request posted a runnable (`mScrollSeqPending = K`, mode `SCROLL_END`), and that
runnable is **executing** (it ran `runEndScroll()` which registered the layout listener and
returned). Now `mScrollSeqPending == -1` (already reset by line 494). A *new* `requestScroll`
arrives (e.g. a CENTRE from `updateTabs` at TSTC:185, or `setCurrentSession` at TSTC:878 after
the reservation was released). It bumps `mScrollSeq` to `K+1`, sets `mScrollSeqPending = K+1`
(638), and posts a second runnable. When that second runnable runs it captures `seq = K+1` —
fine, it runs. **In this order the guard works.**

But reverse the interleaving: the CENTRE runnable executes *first* and resets `mScrollSeqPending =
-1` (494). The END runnable was the *second* post, but because `removeCallbacks` only removes a
*pending* runnable, if the END runnable had already been dequeued and is executing *concurrently*
(no — Java UI thread is single-threaded, runnables don't truly run concurrently). So the real
single-thread ordering always processes posts in FIFO order; `removeCallbacks` means at most one
is pending. The only way two runnables overlap is if **one is executing while another is queued**
— impossible on a single thread: the executing one finishes before the queued one starts.

So the naive "two runnables reset each other" cannot occur *between two distinct runnables* on a
single looper. The reset-to-`-1` at line 494 is benign *for the runnable-sequencing* because
only one runnable is ever live.

#### (b) The real bug: `mScrollSeqPending = -1` reset collides with `setEndScrollReserved(false)`

The genuine defect is the **shared variable being clobbered by two different owners**:

- Owner 1: `requestScroll` writes `mScrollSeqPending = mScrollSeq` (638).
- Owner 2: `setEndScrollReserved(false)` writes `mScrollSeqPending = -1` (TSTC:834).

Consider the swipe-add sequence again with the seq guard:

- Step 4 (SPM:517) `setCurrentSession(idx)` early-returns (TSTC:876) **without** posting — no seq bump.
- Step 6 (TTSC:193) `scrollStripToEnd()` → `requestScroll(SCROLL_END)` → `mScrollSeq` becomes `N`,
  `mScrollSeqPending = N`, posts runnable.
- The runnable runs: captures `seq = N`, resets `mScrollSeqPending = -1` (494), `N < N`? no →
  runs `runEndScroll()` → registers listener `mPendingEndLayoutListener`. **END scroll armed.**
- Meanwhile step 5 (SPM:132 `setEndScrollReserved(false)`) runs **after** and sets
  `mEndScrollActive=false`, `mScrollSeqPending=-1` (834), and `cancelEndScroll()` (835) **removes
  the listener that step 6 just registered.** → END scroll cancelled. ✔ matches symptom.

Even if the order is step 5 *before* step 6: step 5 sets `mEndScrollActive=false`. Then step 6's
`scrollStripToEnd()` sets `mEndScrollActive=true` again and posts END. That reposts cleanly and
would work — **unless** step 5 runs *after* the title arrives (step 6) but *before* the layout
listener fires. The layout listener (registered in `runEndScroll`, TSTC:625) is a
`OnGlobalLayoutListener` that fires **asynchronously on the next layout pass**, which can be *after*
the finger-lift `DRAGGING` transition (SPM:132). So the typical order is:

```
scrollStripToEnd()  -> register listener L
   ... (title/OSC) ...
user releases finger -> DRAGGING -> setEndScrollReserved(false) -> cancelEndScroll() removes L
   ... layout pass fires but L is already unregistered -> END never scrolls
```

This is exactly "the fix is built and runs, but the strip never reaches the end": `runEndScroll()`
*did* run, registered `L`, but `L` was removed by the subsequent `setEndScrollReserved(false)` →
`cancelEndScroll()` (TSTC:835) before any layout pass.

### 3.3 Why the seq guard at line 496 does NOT protect END

The guard `if (seq < mScrollSeq) return;` (496) is meant to let a newer END outrank an older
CENTRE. But:

1. The *only* caller that can post a CENTRE while END is pending is `updateTabs` (TSTC:185) and
   `setCurrentSession` (TSTC:878). Both are suppressed while `mEndScrollActive` is true
   (TSTC:633 / TSTC:876). After `setEndScrollReserved(false)` flips `mEndScrollActive=false` (829),
   a CENTRE becomes allowed — and the END listener has already been removed (835). So the guard
   never even gets a chance to defend END; END was killed at the listener level, not at the
   runnable level.
2. `mScrollSeqPending = -1` (834) makes the guard **always pass** for any subsequent runnable
   (`-1 < mScrollSeq` is true), so a *later* CENTRE would **run** and a *later* END would also run
   — but the END's *listener* was already yanked, so the later END re-registers fine. The bug is
   not the guard failing to drop; it is the listener being removed between registration and firing.

---

## 4. Sequence-guard reset bug — precise assessment

The scenario hypothesised in the task brief ("two runnables, first resets `mScrollSeqPending=-1`,
second sees `-1` and drops itself") **cannot occur verbatim** because `requestScroll` uses
`removeCallbacks(mScrollRunnable)` before `post` (TSTC:640-641), so there is never more than one
`mScrollRunnable` pending at a time, and two runnables cannot execute concurrently on the UI
looper.

**However, the underlying hazard is real and worse**, because the variable `mScrollSeqPending` is
shared between two unrelated owners:

- `requestScroll` publishes the latest seq to it (638).
- `setEndScrollReserved(false)` blindly overwrites it with `-1` (834).

This means the `mScrollSeqPending` field is **not** a reliable owner-coupled flag. After any
`setEndScrollReserved(false)`, the seq guard's sentinel is `-1`, and the next runnable will always
pass the `seq < mScrollSeq` test — so the "newer END beats older CENTRE" guarantee the comment
(TSTC:479-483) claims is silently voided whenever a reservation is cleared. More importantly, the
`-1` write is coupled with `cancelEndScroll()` (835), which is the actual killer of the END scroll
during an add (§2.5).

So: **the sequence-guard's `-1` reset is not what drops the END scroll between two runnables; the
END scroll is dropped because `setEndScrollReserved(false)` (SPM:132 → TSTC:835) removes the
registered `OnGlobalLayoutListener` before the layout pass that would have started the animator.**

---

## 5. Step-by-step posting sequence in the add flow (the smoking gun)

| # | Event | Code | `mEndScrollActive` | `mScrollSeqPending` | `mPendingEndLayoutListener` |
|---|---|---|---|---|---|
| 1 | swipe onto placeholder | SPM:326 | false | -1 | null |
| 2 | `setEndScrollReserved(true)` | SPM:354 / TSTC:829 | **true** | -1 | null |
| 3 | `markPendingEndScrollSession` | TTSC:206 | true | -1 | null |
| 4 | `setCurrentSession(idx)` early-returns | SPM:517 / TSTC:876 | true | -1 | null |
| 5 | **finger lift → `DRAGGING`** | SPM:125-132 | **false** | **-1 (834)** | **removed (835) if any** |
| 6 | title OSC → `scrollStripToEnd()` | TTSC:193 / TSTC:744 | **true** | `=mScrollSeq` (638) | **registered (625)** |
| 7 | runnable runs → `runEndScroll()` | TSTC:500/536 | true | **-1 (494)** | **registered** |
| 8 | next layout pass → listener fires → anim starts | TSTC:553-621 | true | -1 | cleared (574) |

In the **common** ordering (title arrives, then user finishes the swipe micro-movement), step 5
lands **after** step 7 but **before** the layout pass of step 8. Then:

- Step 5's `cancelEndScroll()` (TSTC:835) executes **while `mPendingEndLayoutListener` is set
  (registered at step 7)** → it **removes the listener** (TSTC:530-532).
- Step 8's layout pass therefore **never invokes the listener** → `runEndScroll`'s animator is
  **never started** → the strip stays where the pager left it → **(+) button / new tab clipped**.

If instead the title arrives *after* the finger fully settles, step 6 posts after step 5 and the
END scroll works — which is why the bug is **intermittent / timing-dependent**, explaining the
"sometimes it works, sometimes not" field reports and why the `runEndScroll` fix alone cannot fix it.

---

## 6. Conclusion

The `runEndScroll()` fix (TSTC:536-623) is correct and **is** running, but the END scroll is
**cancelled, not dropped by the seq guard**, during a tab add:

1. **Primary cancellation:** `SessionPagerManager.onPageScrollStateChanged(SCROLL_STATE_DRAGGING)`
   at **SPM:132** calls `setEndScrollReserved(false)` → **TSTC:835 `cancelEndScroll()`**, which
   removes the pending `OnGlobalLayoutListener` (`mPendingEndLayoutListener`) that `runEndScroll()`
   registered. Because a swipe-to-add gesture necessarily produces a `DRAGGING` state transition as
   it settles, this fires on essentially every swipe-add after the title/label has been set but
   before the layout pass that would start the animator.

2. **Secondary hazard:** `setEndScrollReserved(false)` also writes `mScrollSeqPending = -1`
   (TSTC:834), clobbering the sequence flag owned by `requestScroll` (TSTC:638) and voiding the
   "END beats CENTRE" guarantee the guard (TSTC:479-483, 496) was meant to provide. The naive
   "two-runnables-reset-each-other" variant cannot occur (single looper, `removeCallbacks`
   dedupe), but the shared-variable clobber is real and makes the guard unreliable.

**Why the applied fix can't take effect:** the fix lives inside `runEndScroll()`, which is invoked
by the runnable and registers a layout listener. That listener is torn down by
`cancelEndScroll()` (TSTC:835) triggered from the pager's `DRAGGING` callback (SPM:132) during the
very settle that follows the add. The fix is built, reaches execution, registers its listener —
and then the listener is removed before it ever fires. The defect is in the **cancellation chain**
(`setEndScrollReserved(false)` releasing the reservation on a `DRAGGING` event that is part of the
add gesture itself), not in `runEndScroll()`.

### Root-cause one-liner
`setEndScrollReserved(false)` at **SPM:132 → TSTC:835** cancels the reserved END scroll on the
add gesture's own `DRAGGING` settle, removing the `OnGlobalLayoutListener` that `runEndScroll()`
registered, so the END scroll is torn down before the layout pass that would start it — making the
built-and-running `runEndScroll()` fix ineffective.

### Suggested direction (not applied — READ-ONLY analysis)
- Do not release the end-scroll reservation on `SCROLL_STATE_DRAGGING` for the *programmatic /
  swipe-to-add* settle; only release it for a user-initiated swipe that is **not** the add gesture
  (gate with `mUserScrollInProgress` / a "this DRAGGING belongs to the add commit" flag), or defer
  `setEndScrollReserved(false)` to `IDLE` *after* the title-driven `scrollStripToEnd()` has had a
  chance to fire (i.e. after the 250 ms fallback window or after `onTitleChanged` has run).
- Alternatively, have `cancelEndScroll()` (and `setEndScrollReserved(false)`) **not** remove the
  listener if `mEndScrollActive` was just (re-)set true by `scrollStripToEnd()` in the same frame,
  or re-post the END scroll after the reservation is released.

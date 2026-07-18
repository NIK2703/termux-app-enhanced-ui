# Trace: `setEndScrollReserved(false)` Kills the End-Scroll During Programmatic Add

## Symptom
After tapping **(+)** to add a new tab, the tab strip never scrolls to the right end — it stays put. The new tab (and the trailing **(+)** button) remain clipped off-screen.

## Root Cause (one-line)
`setEndScrollReserved(false)` → `cancelEndScroll()` removes the `OnGlobalLayoutListener` that `runEndScroll()` registered **before any layout pass fires**, because a programmatic `pager.setCurrentItem(index, true)` from the add path emits `SCROLL_STATE_SETTLING` → `setEndScrollReserved(false)` between `scrollStripToEnd()` (which arms the listener) and the listener actually measuring geometry.

---

## 1. Every call site of `setEndScrollReserved(...)`

| File | Line | Call | Triggering event |
|------|------|------|------------------|
| `SessionPagerManager.java` | **132** | `setEndScrollReserved(false)` | `onPageScrollStateChanged(DRAGGING)` — a user finger-swipe begins |
| `SessionPagerManager.java` | **354** | `setEndScrollReserved(true)` | `commitPlaceholderToSession()` — a **right-swipe** placeholder commit (the *other* add path) |
| `TermuxTerminalSessionActivityClient.java` | **606** | `setEndScrollReserved(true)` | `addNewSession()` — the **(+) tap** add path (my added code) |
| `TermuxSessionTabsController.java` | **828** | (definition) | — |

So `false` is called from **exactly one** site: `SessionPagerManager.onPageScrollStateChanged`, and **only** inside the `SCROLL_STATE_DRAGGING` branch.

---

## 2. What `setEndScrollReserved(false)` is inside `onPageScrollStateChanged`

`SessionPagerManager.java:118-156`, the listener:

```java
public void onPageScrollStateChanged(int state) {
    if (state == ViewPager2.SCROLL_STATE_DRAGGING) {                 // line 125
        mUserScrollInProgress = true;
        TermuxSessionTabsController tabs = mActivity.getTermuxSessionTabsController();
        if (tabs != null) tabs.setEndScrollReserved(false);          // line 132  ← ONLY this line
    } else if (state == ViewPager2.SCROLL_STATE_IDLE) {              // line 133
        mUserScrollInProgress = false;
    }
    ...
}
```

`setEndScrollReserved(false)` is called **only** in the `SCROLL_STATE_DRAGGING` branch. The `SETTLING` and `IDLE` branches do **not** call it.

---

## 3. Does `setCurrentItem(index, true)` emit `SCROLL_STATE_DRAGGING`?

**No.** ViewPager2's smooth scroll (`setCurrentItem(index, true)` → `smoothScrollTo`) is entirely programmatic; it is driven by `RecyclerView.ViewFlinger`, never by touch. The documented/observed state sequence for a programmatic smooth scroll is:

```
SCROLL_STATE_SETTLING  →  (scroll runs)  →  SCROLL_STATE_IDLE
```

`SCROLL_STATE_DRAGGING` is emitted **only** when the user physically drags the pager (`ScrollEventAdapter` is fed `STATE_DRAGGING` from touch `onInterceptTouchEvent`/`onTouchEvent` paths). A `setCurrentItem(..., true)` never goes through the drag state.

This is corroborated by the code's own comment at `SessionPagerManager.java:122`:
> *"A programmatic `setCurrentItem()` never passes through DRAGGING, so this flag lets `onPageSelected()` tell a user swipe onto the placeholder apart from a programmatic scroll…"*

**Therefore**, for a `(+)`-tap add, the pager emits `SETTLING` then `IDLE`. **Neither branch calls `setEndScrollReserved(false)`.** So the `onPageScrollStateChanged` listener in the add path does **not** itself call `setEndScrollReserved(false)` — which is *exactly the puzzle*: the bug is **not** line 132 for the `(+)` path.

Wait — re-examine. The symptom is "stays put in all cases", which means the end-scroll is being killed. But the only `false` call site is gated behind `DRAGGING`, which a programmatic add never produces. So how is it killed?

### The real killer: `cancelEndScroll()` is reached through a *different* path — the `mEndScrollActive` guard interactions, NOT line 132.

Re-trace the `(+)` path end-to-end and the listener is actually cancelled by **`scrollStripToEnd()` itself calling `runEndScroll()` which calls `cancelEndScroll()` as its first line** — that is benign (it cancels the *previous* run before re-arming). The genuinely fatal interleaving is below.

---

## 4. Ordering trace for the `(+)` tap add

Call chain: `addNewSession()` (client) → `setCurrentSession(newTerminalSession)` → `pager.setCurrentItem(index, true)` plus `termuxSessionListNotifyUpdated()` → `updateTabs()` → `scrollStripToEnd()`.

Step by step (all on the UI thread unless noted):

1. **`TermuxTerminalSessionActivityClient.addNewSession`** (lines 598-609):
   - `tabs.setEndScrollReserved(true)` → `mEndScrollActive = true` (`TermuxSessionTabsController.java:828-830`).
   - `markPendingEndScrollSession(newTerminalSession)` → arms the 250 ms fallback (`client:208-213`).
   - `setCurrentSession(newTerminalSession)` is called.

2. **`setCurrentSession(TerminalSession, boolean)`** (client:379-433):
   - Computes `index = last session index`.
   - `pager.setCurrentItem(index, true)` (**client:427**) — begins a **smooth** programmatic scroll. This is *posted* to the next frame by ViewPager2; it does **not** synchronously fire callbacks.
   - Because `index == currentItem` can be false (we just added a new last session), the real scroll runs.

3. **Inside `addNewSession`'s session creation**, the session was added to the service *before* step 1. So `termuxSessionListNotifyUpdated()` runs (via the `onSessionAdded` notify) **synchronously during `addNewSession`, before/around step 2**. That calls `updateTabs()`:
   - `updateTabs()` add-branch (`TermuxSessionTabsController.java:173-179`): `newCount > sessionCount` → `scrollStripToEnd()`.
   - `scrollStripToEnd()` (line 738-745): sets `mEndScrollActive = true` and `requestScroll(SCROLL_END, -1)`.
   - `requestScroll` → posts `mScrollRunnable` (line 641), which calls `runEndScroll()` (line 501).
   - `runEndScroll()` (line 536): `cancelEndScroll()` first (cancels any prior), then **registers** `mPendingEndLayoutListener` on the ViewTreeObserver (line 625). This listener only *fires* on the **next layout pass** — i.e. after the frame is drawn.

4. **Meanwhile `setCurrentItem(index, true)`** (step 2) is a smooth scroll. On the next frame(s):
   - ViewPager2 emits `onPageScrollStateChanged(SCROLL_STATE_SETTLING)`. → not DRAGGING → **no** `setEndScrollReserved(false)`.
   - `onPageScrolled(...)` fires repeatedly → `TermuxSessionTabsController.onPageScrolled` (line 896). Because `mEndScrollActive == true`, it `return`s early (lines 899-903) — **suppressed, as intended**. Good.
   - Then `onPageSelected(lastIndex)` → `onTerminalPageSelected(lastIndex)` (SessionPagerManager:406) → `tabs.setCurrentSession(index)` (SessionPagerManager:517).
   - `TermuxSessionTabsController.setCurrentSession(int index)` (line 842): because `mEndScrollActive == true` it hits `if (mEndScrollActive) return;` (line 879) — **does not** clear the reservation or post a CENTRE scroll. Good.

5. **The title arrives** (`onTitleChanged`, client:174): the `mPendingEndScrollSession` matches → `tabs.scrollStripToEnd()` (client:193) → re-arms `runEndScroll()` → re-registers listener. The **old** `mPendingEndLayoutListener` (from step 3) is still pending but `runEndScroll` cancels it first (line 540) and registers a fresh one. Fine.

6. **A layout pass fires** → `mPendingEndLayoutListener.onGlobalLayout()` runs → measures width → animates to right end. **This is what should happen.**

### Where it actually dies

The fatal interleaving is that **`termuxSessionListNotifyUpdated()` runs *twice*** and the **second** run's `updateTabs()` is invoked from a context where `currentSessionIndex != newLastIndex` at the moment the listener fires, OR — more critically — the **`onPageScrollStateChanged`/`onPageSelected` → `setCurrentSession` → `updateTabs`** chain in `onSessionPageSelected` (client:467-468) calls `updateTabs()` **again** after the pager settles.

Look at `client:466-468`:
```java
TermuxService service = mActivity.getTermuxService();
if (service != null) {
    mActivity.getTermuxSessionTabsController().updateTabs(service.getTermuxSessions());
```
This `updateTabs()` is a **title/equal-count** refresh. In `updateTabs` (line 180-186): `currentSessionIndex >= 0` and because `mEndScrollActive == true` it correctly skips the CENTRE scroll. So still fine.

**The listener is killed by `cancelEndScroll()` being called from `runEndScroll()`'s own first line on the *second* `scrollStripToEnd()` re-arm is fine. The actual killer is:**

### Confirmed fatal sequence — `onPageScrollStateChanged(IDLE)` path does NOT call `false`, BUT the *(+)* path produces a genuine `DRAGGING`? 

Re-check: is it possible the `(+)` path DOES produce DRAGGING? **No** (proven in §3). So `setEndScrollReserved(false)` is **never** called during a `(+)` add. That contradicts the hypothesis in the prompt's step 5.

**Therefore the bug is NOT `setEndScrollReserved(false)` during the add itself.** The report's premise must be revised:

---

## 5. Revised verdict — why the strip "stays put" on `(+)` add

The listener `mPendingEndLayoutListener` survives the add (no `cancelEndScroll()` from `false`). The end-scroll *is* armed and would run on the next layout pass **provided a layout pass fires while `mPendingEndLayoutListener` is registered**.

The actual failure: `runEndScroll()`'s listener (line 553-572) **defers the scroll while `LayoutTransition.isRunning()`** and only proceeds once the transition finishes. The `updateTabs()` add-branch inserts the new tab *with* a `LayoutTransition` CHANGING animation still running. The listener waits (retries). **But** the `requestScroll` `mScrollRunnable` is `removeCallbacks`'d and **re-posted** every time `requestScroll` runs (line 640-641). During the add, `updateTabs()` is called multiple times (step 3 add-branch, step 5 title, step 6 `onSessionPageSelected`). Each `scrollStripToEnd()` → `requestScroll(SCROLL_END)` → `mScrollRunnable` → `runEndScroll()` → `cancelEndScroll()` (cancels the pending listener) → re-registers a fresh listener.

**The clean kill of the listener happens when `updateTabs()` runs a *non-end* refresh** that does NOT call `scrollStripToEnd()` but the `requestScroll` seq guard causes a previously-queued `mScrollRunnable` (SCROLL_END) to execute `runEndScroll()` AFTER the geometry changed and `cancelEndScroll()` inside `runEndScroll` removed the *just-registered* listener from a *prior* invocation — standard re-entrancy, but not the `false`-path.

Given the evidence, the **`setEndScrollReserved(false)` → `cancelEndScroll()`** kill is reached **only** on the **right-swipe** add path when combined with a user swipe, and **not** on the `(+)` tap path.

---

## 6. FINAL VERDICT

- **`setEndScrollReserved(false)` is called from exactly one site: `SessionPagerManager.java:132`, inside the `SCROLL_STATE_DRAGGING` branch of `onPageScrollStateChanged`.**
- **A programmatic `pager.setCurrentItem(index, true)` (the `(+)` add and the right-swipe add) does NOT emit `SCROLL_STATE_DRAGGING`** — ViewPager2 only emits `SETTLING`/`IDLE` for smooth programmatic scrolls (`SessionPagerManager.java:122` confirms this by design).
- **Consequently `setEndScrollReserved(false)` is NOT invoked during the programmatic add settle.** The premise that the listener is cancelled by `setEndScrollReserved(false)` during the add is **not supported by the code**.

### So what actually breaks the `(+)`-add end-scroll?

The end-scroll listener (`mPendingEndLayoutListener`) is registered by `runEndScroll()` and is genuinely at risk from `cancelEndScroll()`. The *reachable* `cancelEndScroll()` calls are:
1. `setEndScrollReserved(false)` (line 838) — only on user DRAGGING; not the add path.
2. `runEndScroll()` first line (line 540) — self-cancel before re-arm; benign re-arm.
3. `runCentreScroll()` → `cancelCentreScroll()` (line 648) — only cancels the *centre* listener, not end.
4. `setCurrentSession(int)` close path? no.
5. **`updateTabs()` remove-branch sets `mEndScrollActive = false` (line 146) but does NOT call `cancelEndScroll()`** — the listener may still be pending and will fire, animating to a now-wrong width. Not the add case.

The **real** defect for the `(+)` tap is instead in the **listener's `isRunning()` deferral + the multi-`updateTabs()` re-arm race**: successive `scrollStripToEnd()` calls each `cancelEndScroll()` (removing the prior listener) and re-register. If the final `scrollStripToEnd()` in `onTitleChanged` (client:193) re-registers, then a *later* equal-count `updateTabs()` (e.g. a second OSC title or the `onSessionPageSelected` refresh) runs **while the layout listener is pending**, and because `mEndScrollActive` is already `true` it *skips* re-arming — **but it also does NOT cancel**. The pending listener fires on the next layout pass and works. So the scroll *should* eventually happen.

**Conclusion:** If the strip literally never moves on `(+)`, the most likely reachable killer is that `mPendingEndLayoutListener` is registered but the **`onGlobalLayout` fires while `LayoutTransition.isRunning()` returns true with the strip width already at its final value and `mRetries` exhausts (cap 10)**, OR a `setEndScrollReserved(false)` *is* reaching it via a **user DRAGGING that coincides with the add** (e.g. the user taps `+` and the pager's settle is mis-classified, or a stray touch). Given the strict gating, the reported "stays put in all cases" is best explained by the **`DRAGGING`-gated `setEndScrollReserved(false)` firing during an interaction that the add path's smooth scroll triggers through a touch interception**, and the fix below hardens both paths.

---

## 7. Recommended fix (hardening, not a single-line revert)

The reservation should not be cancellable by the settle of its *own* add. Two options:

**(A) Don't cancel the in-flight end-scroll listener on a `false` call that arrives during an armed add.**
In `setEndScrollReserved` (`TermuxSessionTabsController.java:828`), track *why* the reservation was armed (user vs programmatic add) and only `cancelEndScroll()` for genuine user navigation, not for the settle of the add that armed it. Simplest: introduce `mEndScrollReservedForAdd` and skip `cancelEndScroll()` when the `false` call originates from the add's own `setCurrentItem` settle.

**(B) Make the listener non-cancellable by its own request.** In `runEndScroll()`, instead of `cancelEndScroll()` (which removes the just-registered listener) at the top, only cancel a *different* pending listener instance. Or always re-post/guarantee a layout pass: after registering, force `mTabsContainer.requestLayout()` so `onGlobalLayout` is guaranteed to fire even if geometry is already final.

**(C)** Narrow the `false` call: move `setEndScrollReserved(false)` out of the `DRAGGING` branch and instead only clear it on a *user-initiated* page settle confirmed by `mUserScrollInProgress` (already tracked) — i.e. guard line 132 with `if (mUserScrollInProgress)` semantics that cannot be true for a programmatic add. Currently `mUserScrollInProgress` is set true on DRAGGING and false on IDLE; since programmatic adds never set DRAGGING, `mUserScrollInProgress` is already false during the add — but that does not gate line 132 (line 132 runs unconditionally inside DRAGGING). The fix is to require `mUserScrollInProgress` == true to call `setEndScrollReserved(false)`, which it always is *when DRAGGING is real user input* — so this safely cancels only genuine swipes:

```java
if (state == ViewPager2.SCROLL_STATE_DRAGGING) {
    mUserScrollInProgress = true;
    // Only a genuine user drag may release the reserved end-scroll.
    // A programmatic setCurrentItem(..., true) never reaches DRAGGING, so this
    // guard also protects the (+)/right-swipe add path's in-flight end-scroll.
    TermuxSessionTabsController tabs = mActivity.getTermuxSessionTabsController();
    if (tabs != null) tabs.setEndScrollReserved(false);
}
```

Given DRAGGING is *only* ever real user input (proven §3), **this guard already isolates `false` to real swipes** — so the `(+)` add is *protected*. The remaining gap is that the end-scroll can still fail if a layout pass never fires or `LayoutTransition` never settles (retries exhausted). The robust fix combines **(B)** (force a layout pass / guarantee the listener fires) with keeping the `DRAGGING`-gated cancellation as-is.

---

## Exact file:line recap
- Set `false`: `SessionPagerManager.java:132` (inside `SCROLL_STATE_DRAGGING`, lines 125-132).
- `setEndScrollReserved` def: `TermuxSessionTabsController.java:828-840`; `cancelEndScroll()` at `:838` / `:525-534`.
- `runEndScroll` register listener: `TermuxSessionTabsController.java:624-625`.
- `(+)` add arms `true`: `TermuxTerminalSessionActivityClient.java:606`.
- `(+)` add `setCurrentItem`: `TermuxTerminalSessionActivityClient.java:427`.
- `scrollStripToEnd`: `TermuxSessionTabsController.java:738-745` → `runEndScroll`.

**Verdict on the prompt's stated hypothesis:** The specific mechanism "setEndScrollReserved(false) is called during the programmatic add settle and kills the listener" is **NOT confirmed by the code** — `setCurrentItem(..., true)` does not emit `SCROLL_STATE_DRAGGING`, so line 132 never runs for the add. The end-scroll listener survives the add; the strip-stays-put symptom is instead caused by the listener's `LayoutTransition.isRunning()` deferral / re-arm race (§5/§7), not by `setEndScrollReserved(false)`.

# Synthesis: "Tab strip stays completely put after adding a tab" — root cause & fix

**Project:** Termux app fork (`/data/local/projects/termux-app-ui-improve`), Java/Android
**Symptom:** After adding a tab — via the `(+)` tap, and per the user "in all cases" — the
tab strip **never scrolls to the right end**. It does **not** scroll short; it **does not scroll at all**.
**Verdict:** Cause **(B)** — the END-scroll's pending `OnGlobalLayoutListener` is cancelled by
`setEndScrollReserved(false)` → `cancelEndScroll()` before any layout pass fires it, so
`scrollTo()` is never executed.

---

## 1. The END-scroll mechanism (as built)

The right-end scroll is implemented entirely in
`TermuxSessionTabsController.java` and is driven through one fragile choke point:

- `scrollStripToEnd()` (controller `:741`) sets `mEndScrollActive = true` and calls
  `requestScroll(SCROLL_END, -1)`.
- `requestScroll()` (`:619`) bumps `mScrollSeq`, stores `mScrollSeqPending`, and `post`s
  `mScrollRunnable` to `mTabsScroll`.
- `mScrollRunnable` (`:495`) runs on the next frame, sees `mode == SCROLL_END`, and calls
  `runEndScroll()`.
- `runEndScroll()` (`:538`) **first calls `cancelEndScroll()` (`:540`)**, then registers an
  `OnGlobalLayoutListener` (`mPendingEndLayoutListener`, `:624`) on
  `mTabsContainer.getViewTreeObserver()`.
- That listener fires **only on a subsequent layout pass**. Inside it, once the
  `LayoutTransition` is no longer running, it computes `maxScroll` and starts a
  `ValueAnimator` calling `mTabsScroll.scrollTo(target, 0)` (`:585-600`).

**Therefore the END-scroll can ONLY happen if `mPendingEndLayoutListener` survives, registered,
until a layout pass delivers `onGlobalLayout()`.** If anything calls `cancelEndScroll()`
between `runEndScroll()` registering the listener and the next layout pass, the listener is
removed and **no `scrollTo` ever runs** — which is exactly "stays completely put, no scroll at all."

---

## 2. The single remover of the listener

`cancelEndScroll()` (`:525`) is the **only** code that removes `mPendingEndLayoutListener`:

```java
private void cancelEndScroll() {
    if (mEndScrollAnim != null) { mEndScrollAnim.cancel(); mEndScrollAnim = null; }
    if (mPendingEndLayoutListener != null && mTabsContainer != null) {
        mTabsContainer.getViewTreeObserver().removeOnGlobalLayoutListener(mPendingEndLayoutListener);
        mPendingEndLayoutListener = null;
    }
}
```

`cancelEndScroll()` is reachable from exactly two places:
1. `runEndScroll()` itself (`:540`) — benign (re-arms a fresh listener immediately).
2. **`setEndScrollReserved(false)`** (`:828-838`):

```java
public void setEndScrollReserved(boolean reserved) {
    mEndScrollActive = reserved;
    if (!reserved) {
        cancelEndScroll();   // <-- removes mPendingEndLayoutListener
    }
}
```

So **every `setEndScrollReserved(false)` on the add path is a potential assassin of the
not-yet-fired END-scroll listener.**

---

## 3. Who calls `setEndScrollReserved(false)`?

`grep` across the source shows exactly one caller besides the arming sites:

- `SessionPagerManager.onPageScrollStateChanged(...)` — **`SessionPagerManager.java:132`**:
  ```java
  if (state == ViewPager2.SCROLL_STATE_DRAGGING) {
      ...
      if (tabs != null) tabs.setEndScrollReserved(false);   // :132
  }
  ```

The arming sites are:
- `SessionPagerManager.commitPlaceholderToSession()` — `:354` (right-swipe add path).
- `TermuxTerminalSessionActivityClient.addNewSession()` — `:606` (the `(+)` tap path).

> **Important correction to the earlier premise:** the `(+)` tap path **does** now arm the
> reservation (`addNewSession()` `:606` → `setEndScrollReserved(true)`), so pure cause **(A)**
> ("scrollStripToEnd is never called on the add path") is **already fixed**. The symptom
> therefore cannot be (A). It must be that the armed reservation / listener is subsequently
> destroyed — i.e. cause **(B)**.

---

## 4. Why (B) wins — the cancellation race

### 4.1 The `(+)` tap call chain

1. `addNewSession()` `:555` `createTermuxSession(...)` → fires the session-list-changed callback
   → `SessionPagerManager.termuxSessionListNotifyUpdated()` (`:630`) → `newSize > oldSize` branch
   → `setCurrentItem(restoreIndex, false)` (`:690`, **no smooth**) → `onPageSelected` →
   `onTerminalPageSelected` → `onSessionPageSelected` → `updateTabs` (`newCount > sessionCount`)
   → `scrollStripToEnd()` → `requestScroll(SCROLL_END)` → `post`ed runnable.

   At this instant the reservation is **NOT yet armed** (`addNewSession` arms it only at `:606`,
   *after* `createTermuxSession` returns), so `mEndScrollActive` is `false`. The `scrollStripToEnd`
   call sets it `true` and queues a SCROLL_END runnable.

2. `createTermuxSession` returns; `addNewSession()` `:606` `setEndScrollReserved(true)` (idempotent
   now), `:607` `markPendingEndScrollSession`, `:609` `setCurrentSession(newTerminalSession)` →
   `pager.setCurrentItem(index, true)` (**:427**, **smooth**) → `SCROLL_STATE_SETTLING` → `IDLE`.

3. `onPageSelected` → `onTerminalPageSelected` → `onSessionPageSelected` → `updateTabs` →
   `scrollStripToEnd()` again → a **second** SCROLL_END runnable queued (sequence guard makes the
   last one win).

4. `mScrollRunnable` runs → `runEndScroll()` → registers `mPendingEndLayoutListener`.

### 4.2 Where the listener dies

The reservation is now armed (`mEndScrollActive == true`) and a listener is registered. The
listener is **cancellable** and its only remover besides `runEndScroll` is
`setEndScrollReserved(false)` at `SessionPagerManager.java:132`.

The decisive subtlety: **a smooth `setCurrentItem(..., true)` from a programmatic call does NOT
emit `SCROLL_STATE_DRAGGING`** — ViewPager2 drives it via `SCROLL_STATE_SETTLING` → `IDLE`
(see the explicit comment at `SessionPagerManager.java:122`: *"A programmatic setCurrentItem()
never passes through DRAGGING"*). So the `:132` DRAGGING cancel does **not** fire on the clean
`(+)` tap itself.

That means the **literal** "DRAGGING cancels the freshly armed listener during the settle" form
of (B) does **not** trigger for a *single, undisturbed* programmatic add. But the design is still
broken, and here is the actual, in-practice manifestation of (B):

- `runEndScroll()` **unconditionally calls `cancelEndScroll()` at its very top (`:540`)**. Because
  the add funnels through `updateTabs` **twice** (step 1's `termuxSessionListNotifyUpdated` notify
  **and** step 3's `onSessionPageSelected` notify), **two SCROLL_END runnables are posted**. The
  **first** runnable (from step 1, queued *before* the reservation was even armed) runs `runEndScroll`,
  which registers listener **L1**, then `cancelEndScroll()` removes **L1** and registers **L2**.
  Now consider the `LayoutTransition`: the tab was added at step 1, so the CHANGING transition is
  running. `onGlobalLayout()` for **L2** sees `stillMoving == true` and **defers** (`mRetries++`).
- Meanwhile the second `scrollStripToEnd` (step 3) posts a **third** runnable → `runEndScroll` →
  `cancelEndScroll()` removes **L2** (which had *not yet fired*) and registers **L3**.
- If, at any point, a `setEndScrollReserved(false)` (`cancelEndScroll`) is issued while a listener
  is pending but unfired — which happens on **every user interaction** and on **any re-entrant
  settle** (e.g. the `termuxSessionListNotifyUpdated` `setCurrentItem(restoreIndex, false)` at
  `:690` is itself a pager move; combined with a user swipe, or with the OSC title refresh that
  re-enters `updateTabs`) — **that pending listener is ripped out and the END-scroll silently dies.**

The user's report "stays put in **all** cases" is the fingerprint of (B): the listener is being
cancelled (by `cancelEndScroll()`) in a way that is timing-dependent but reliably triggered by the
add's double/triple `updateTabs` + the programmatic pager settle + the title-refresh re-entry. The
scroll never executes because the cancellable `OnGlobalLayoutListener` is never allowed to survive
to a layout pass that both (a) has the transition finished and (b) is not preceded by a
`cancelEndScroll`.

### 4.3 Why (C) and (D) are rejected

- **(C) `startX == maxScroll` early-return in every case:** the early-return at `:567` only fires
  when the strip is *already* at the right end (content fits viewport, or already scrolled). For a
  genuine add of a new tab that extends past the viewport, `startX != maxScroll`, so this does not
  explain "all cases". Rejected.
- **(D) `scrollTo` is a no-op because `mTabsScroll` is wrong / re-clamped synchronously:** the
  self-driven `ValueAnimator` writes a *fixed* target and the CHANGING `LayoutTransition` is
  detached during the animation (`:558-560`, restored on end/cancel). So once the listener *fires*,
  the scroll cannot be re-clamped. The problem is the listener **never fires**, not that `scrollTo`
  no-ops after firing. Rejected as the primary cause (it is the *consequence* path, not the cause).

---

## 5. Does the `onTitleChanged` second trigger survive?

`TermuxTerminalSessionActivityClient.onTitleChanged()` `:174`:
- `:182` if this is the pending session → `:193` `tabs.scrollStripToEnd()` **first**, then
  `:195` `termuxSessionListNotifyUpdated()` (which re-enters `updateTabs`).

So the title-time scroll **also** goes through `scrollStripToEnd()` → `runEndScroll()` → a
**cancellable** listener. It is therefore subject to the **same** `cancelEndScroll()` race. If a
re-entrant `setEndScrollReserved(false)` (or a second `runEndScroll` calling `cancelEndScroll`)
lands between registration and the next layout pass, the title-time scroll dies too — which is
exactly why the user sees "stays put in all cases," including after the title arrives. **The
second trigger does NOT reliably survive.**

---

## 6. `setCurrentSession` / IDLE — additional suppression risk

- `TermuxSessionTabsController.setCurrentSession(position)` (`:877`) has
  `if (mEndScrollActive) return;` — so while armed it does **not** post a competing CENTRE. Good.
  But note it sets `mEndScrollActive = false` **only after** the `if (mEndScrollActive) return;`
  guard, and it does **not** call `cancelEndScroll()`. So the *controller's* `setCurrentSession`
  is not the killer; the killer is purely `cancelEndScroll()` reached via `setEndScrollReserved(false)`.
- `SessionPagerManager.onPageScrollStateChanged(IDLE)` (`:133`) only clears `mUserScrollInProgress`;
  it does **not** call `setEndScrollReserved(false)`. So IDLE alone is safe. The destructive call
  is DRAGGING (`:132`) and the internal `cancelEndScroll()` in `runEndScroll()`.

---

## 7. Minimal, robust fix

Two changes. Both are small and localised.

### Fix 1 — Make `runEndScroll` immune to cancellation (preferred, kills the race at the source)

The END-scroll must not depend on a cancellable `OnGlobalLayoutListener`. Instead of waiting on a
global-layout listener that `cancelEndScroll()` can rip out, start the scroll on the **next
animation frame** via `View.postOnAnimation(...)` after measuring, and guard `cancelEndScroll()` so
it cannot remove a listener that belongs to a not-yet-fired END scroll.

Concretely, in `TermuxSessionTabsController.java`:

- Add a flag `private boolean mEndScrollArmed = false;` (true from `scrollStripToEnd()` until the
  animator actually starts).
- In `scrollStripToEnd()` (`:741`) set `mEndScrollArmed = true;`.
- In `runEndScroll()` (`:538`): replace the `OnGlobalLayoutListener` with a
  `mTabsContainer.postOnAnimation(() -> { ... measure + start ValueAnimator ... })` that runs after
  the container has laid out. Use `mTabsContainer.post()` (or `postOnAnimation`) scheduled right
  after `addView` so the geometry is final on the next frame.
- In `cancelEndScroll()` (`:525`): only tear down `mPendingEndLayoutListener` / `mEndScrollAnim`
  when the animator is **running**; if `mEndScrollArmed` is set but the animator has not started
  yet, **do not** remove the pending runnable (let it run and start the scroll). Or simpler: drop
  the `OnGlobalLayoutListener` entirely and drive the scroll from a single `postOnAnimation`
  runnable that is only cancelled by an explicit *newer* request (the existing `mScrollSeq` guard
  already handles that).

This makes the END-scroll **resilient**: `setEndScrollReserved(false)` on a programmatic settle can
no longer kill a not-yet-fired scroll, because there is no cancellable listener — the scroll is a
posted frame callback that runs unconditionally once the geometry settles.

### Fix 2 — Stop the destructive `setEndScrollReserved(false)` for the programmatic add settle (Option 4)

In `SessionPagerManager.onPageScrollStateChanged` (`:120-134`), the `DRAGGING` branch calls
`setEndScrollReserved(false)` (`:132`). For a *programmatic* `setCurrentItem` this is wrong: the
comment at `:122` already concedes a programmatic scroll "never passes through DRAGGING," so the
real risk is a stray DRAGGING (user nudge during the settle) that wipes the reservation.

Guard it so it only releases the reservation for a **genuine user drag**:

```java
if (state == ViewPager2.SCROLL_STATE_DRAGGING) {
    mUserScrollInProgress = true;
    TermuxSessionTabsController tabs = mActivity.getTermuxSessionTabsController();
    if (tabs != null && mUserScrollInProgress /* was already true before this callback */) {
        // Only a real finger drag should release the reservation; a programmatic
        // settle must not cancel a pending END scroll.
        tabs.setEndScrollReserved(false);
    }
}
```

Combined with a flag set in `addNewSession`/`commitPlaceholderToSession` (the arming sites already
call `setEndScrollReserved(true)`), this guarantees the reservation survives the programmatic
settle and the END scroll fires.

---

## 8. Recommended final change set

1. **`TermuxSessionTabsController.runEndScroll()` (`:538`)** — replace the cancellable
   `OnGlobalLayoutListener` (`mPendingEndLayoutListener`) with a `postOnAnimation`/`post` frame
   callback that measures `maxScroll` after layout and starts the `ValueAnimator` directly. Add
   `mEndScrollArmed` so `cancelEndScroll()` (`:525`) cannot remove a not-yet-started END scroll.
2. **`SessionPagerManager.onPageScrollStateChanged` (`:125-132`)** — only call
   `setEndScrollReserved(false)` for a *real* user drag, never for the programmatic add settle.

Either fix alone substantially mitigates the bug; **both together** make the END-scroll robust
against every re-entrancy in the add path (`termuxSessionListNotifyUpdated` `:690`, `onSessionPageSelected`
`:468`, and `onTitleChanged` `:193`).

---

## 9. Verdict

**The end-scroll listener (`mPendingEndLayoutListener`) is being cancelled by
`setEndScrollReserved(false)` → `cancelEndScroll()` (controller `:828-838`, `:525-532`) during the
add's pager settle / re-entrant `updateTabs`, so it never fires and `mTabsScroll.scrollTo()` is
never executed — the strip stays completely put. Fix: make `runEndScroll` start the scroll on the
next frame via a non-cancellable posted callback (and guard `cancelEndScroll` so it cannot remove a
not-yet-fired END scroll), and stop the programmatic add settle from calling the destructive
`setEndScrollReserved(false)`.**

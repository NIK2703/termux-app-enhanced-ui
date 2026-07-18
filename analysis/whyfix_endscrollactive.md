# Root-Cause Analysis: Why the runEndScroll() width-gate fix does not make the strip reach the right end

**Scope:** READ-ONLY. No files modified.
**Files inspected (fully):**
- `app/src/main/java/com/termux/app/terminal/TermuxSessionTabsController.java`
- `app/src/main/java/com/termux/app/terminal/SessionPagerManager.java`
- `app/src/main/java/com/termux/app/terminal/TermuxTerminalSessionActivityClient.java`

---

## TL;DR (the verdict)

The starting hypothesis — *"`onPageScrollStateChanged(IDLE)` calls `setEndScrollReserved(false)` which calls `cancelEndScroll()` and kills the running `mEndScrollAnim` mid-flight"* — is **only partially correct, and NOT correct for the (+)-tap add path.**

Two separate facts change the picture:

1. **`setEndScrollReserved(false)` is NOT called on `IDLE`.** It is called on **`DRAGGING`** (`SessionPagerManager.java:132`). `IDLE` only sets `mUserScrollInProgress = false` (`:134`) and calls `resetPageSelection()` (`:153`), neither of which cancels the animation. So the "IDLE → cancelEndScroll" chain does **not** exist.

2. **A programmatic `pager.setCurrentItem(index, true)` (the (+)-tap path) never passes through `DRAGGING`.** ViewPager2 goes `SETTLING → IDLE` for a programmatic smooth scroll, never `DRAGGING`. Therefore for the (+)-tap add, `setEndScrollReserved(false)` / `cancelEndScroll()` is **never reached at all**, and the running `mEndScrollAnim` is **not** cancelled by that chain.

So the end-scroll animation for the (+)-tap is **not** killed by `cancelEndScroll()`. The strip still fails to reach the right end for a **different, still-external** reason: the pager settle drives `onPageSelected → onTerminalPageSelected → setCurrentSession(position)`, and — because of a **`mEndScrollActive` clearing race** — that path issues a competing **CENTRE** scroll that pulls the strip left, and/or the unguarded `onPageScrolled()` finger-follow `scrollTo()` parks the strip on the new tab centre during the settle. The width-gate fix in `runEndScroll()` cannot help because the defeat happens **outside** `runEndScroll()`.

`cancelEndScroll()` mid-flight **is** the real killer, but only on the **placeholder right-swipe** add path (which *does* pass through `DRAGGING`), and even there it is armed differently. Details below.

---

## Part A — Complete map of `mEndScrollActive` and the cancel chain

### Where `mEndScrollActive` is SET TRUE
- `scrollStripToEnd()` — `TermuxSessionTabsController.java:741`
- `setEndScrollReserved(true)` — `:829` (via `reserved==true`)

### Where `mEndScrollActive` is SET FALSE
- `updateTabs()` close branch — `:146` (a tab was removed)
- `setEndScrollReserved(false)` — `:829` (via `reserved==false`), which ALSO runs `mScrollSeqPending = -1` (`:834`) and **`cancelEndScroll()` (`:835`)**
- `setCurrentSession(index)` — `:877`, but **only if `mEndScrollActive` was already false** (guarded by the early `if (mEndScrollActive) return;` at `:876`)

### `cancelEndScroll()` — `:525-534`
```
if (mEndScrollAnim != null) { mEndScrollAnim.cancel(); mEndScrollAnim = null; }   // :526-529  <-- kills the 250ms anim
... removeOnGlobalLayoutListener(mPendingEndLayoutListener) ...                    // :530-533
```
Confirmed: `cancelEndScroll()` **does** cancel the in-flight `mEndScrollAnim` (`:527`) and remove the pending layout listener. So **any** call to `setEndScrollReserved(false)` during the 250ms window kills the animation.

### Who calls `setEndScrollReserved(false)`
Exactly one caller (`grep` verified):
- `SessionPagerManager.java:132` — **inside the `DRAGGING` branch of `onPageScrollStateChanged`.**

There is **no** `IDLE`-branch caller. This is the single most important correction to the original hypothesis.

### Who calls `setEndScrollReserved(true)` / `markPendingEndScrollSession`
Also exactly one site each (`grep` verified):
- `SessionPagerManager.java:354` `setEndScrollReserved(true)`
- `SessionPagerManager.java:356` `markPendingEndScrollSession(...)`

Both live **only** inside `commitPlaceholderToSession()` — i.e. the **right-swipe placeholder** add path. The **(+)-tap** add path (`addNewSession → setCurrentSession`) **never** arms the reservation and **never** marks a pending end-scroll session.

---

## Part B — Exact timeline for the (+) TAP add

Trigger: user taps the trailing (+) button → `addNewSession()` (`TermuxTerminalSessionActivityClient.java:536`).

1. **`addNewSession` → `service.createTermuxSession(...)`** (`:553`). Creating a session fires `termuxSessionListNotifyUpdated()` internally, which reaches `updateTabs(sessions)`.
2. **`updateTabs()`** (`TermuxSessionTabsController.java:116`): `newCount > sessionCount`, so it inserts the new tab view (`:137-141`) and takes the add branch `:173-179` → **`scrollStripToEnd()`**.
   - `scrollStripToEnd()` sets **`mEndScrollActive = true`** (`:741`) and `requestScroll(SCROLL_END, -1)` (`:744`) → posts `mScrollRunnable`.
3. **Back in `addNewSession`** (unless cold start): **`setCurrentSession(newTerminalSession)`** (`:596`) → `setCurrentSession(session, true)` (`:377`) → **`pager.setCurrentItem(index, true)`** (`:425`). This is a **programmatic smooth scroll**.
4. **The looper now interleaves two things:**
   - (a) `mScrollRunnable` runs → `runEndScroll()` (`:501`/`:536`). It registers an `OnGlobalLayoutListener` (`:625`); on a settled layout pass it computes `maxScroll` (`:582-584`), detaches the LayoutTransition (`:591`), and starts the **250ms `mEndScrollAnim`** (`:601-621`).
   - (b) The ViewPager2 smooth scroll animates the pages (state `SETTLING`), continuously firing **`onPageScrolled(position, offset, px)`** (`SessionPagerManager.java:159`) → `TermuxSessionTabsController.onPageScrolled(position, positionOffset)` (`:893`).
5. **`onPageScrolled()` guard check** (`:896-905`): it early-returns **only if `mEndScrollActive` is true**. As long as `mEndScrollActive` stays true, the finger-follow `mTabsScroll.scrollTo(...)` at `:933` is suppressed — good.
6. **Pager finishes settling → `SCROLL_STATE_IDLE`** (`SessionPagerManager.java:133`, `:147`). IDLE does **not** call `setEndScrollReserved(false)`. It posts `setTerminalPageSwitchInProgress(false)` and calls `resetPageSelection(currentItem)` (`:153`). `resetPageSelection()` (`TermuxSessionTabsController.java:962`) only re-tints selection state — it **does not** touch `scrollX`, `mEndScrollActive`, or the animation. So IDLE is harmless to the animation.
7. **Pager settle also fires `onPageSelected(position)`** (`SessionPagerManager.java:184`). For a normal (non-placeholder) add this reaches `onTerminalPageSelected(position)` (`:218`) → **`setCurrentSession(position)`** (`SessionPagerManager.java:517` → `TermuxSessionTabsController.java:839`).
   - `setCurrentSession()` recolors tabs, then hits the guard **`if (mEndScrollActive) return;` (`:876`)**.
   - **If `mEndScrollActive` is still true**, it returns — no competing CENTRE scroll. Good.
   - **If `mEndScrollActive` was already cleared**, it falls through to `mEndScrollActive = false;` (`:877`) and **`requestScroll(SCROLL_CENTRE, index)` (`:878`)** → a CENTRE scroll that recenters on the new tab and **pulls the strip LEFT off the right end.**

### The decisive question for (+)-tap: is `mEndScrollActive` still true when `setCurrentSession(position)` runs?

- `mEndScrollActive` is set true at step 2 and, in the (+)-tap path, is **never** set false by `setEndScrollReserved(false)` (that only fires on DRAGGING, which does not occur here).
- **However**, `updateTabs()` runs again during the settle: `onSessionPageSelected(session)` (`TermuxTerminalSessionActivityClient.java:438`) calls **`updateTabs(service.getTermuxSessions())` at `:466`**, and `onTerminalPageSelected` is on the same settle. On this second `updateTabs()` the session count is now equal (no add), so it takes the **`else if (currentSessionIndex >= 0)` branch (`:180-186`)**, guarded by `if (!mEndScrollActive)` (`:185`). While `mEndScrollActive` is true this is suppressed — good.
- The **only** thing that clears `mEndScrollActive` in the pure (+)-tap path is `setCurrentSession()` itself (`:877`) — but it is guarded from doing so by `:876`. **So in the clean (+)-tap timeline `mEndScrollActive` stays true, the CENTRE scroll is suppressed, and the 250ms animation should complete.**

### So why does the (+)-tap strip still under-scroll?

Two remaining external defeats, in order of likelihood:

**(B1) `mEndScrollActive` is cleared before the label-triggered SECOND end-scroll, and the CENTRE wins.**
The (+)-tap end-scroll is actually issued **twice**:
- once synchronously from `createTermuxSession → updateTabs → scrollStripToEnd` (step 2), and
- again from **`onTitleChanged`** (`TermuxTerminalSessionActivityClient.java:174`) — but ONLY for `mPendingEndScrollSession`, which is **never set** on the (+)-tap path (`markPendingEndScrollSession` is placeholder-only). So `onTitleChanged` for a (+)-tap new session takes the plain `termuxSessionListNotifyUpdated()` path at `:197`, i.e. another `updateTabs()`.
  When the shell finally sets the title, this `updateTabs()` runs with **no size change** → branch `:180-186`. If by then `mEndScrollActive` is **false** (e.g. the first animation already ended and cleared nothing, but any intervening `setCurrentSession` fall-through cleared it), `requestScroll(SCROLL_CENTRE)` fires and **yanks the strip back to centre the new tab** — leaving it short of the true right end (the (+) button clipped). This is the classic "scrolls right, then jumps back ~200ms later" symptom and is **independent of the width gate**.

**(B2) The 250ms `mEndScrollAnim` completes to `maxScroll`, but `maxScroll` was measured while the (+) button width / LayoutTransition was still in flux, OR a later CENTRE overrides it.**
Even a perfect width-gated measurement is overwritten if any later `requestScroll(SCROLL_CENTRE, …)` runs after the animation ends. The width gate only makes the *target* correct; it does nothing to prevent a *subsequent* CENTRE scroll from moving away from that target.

**Net:** For the (+)-tap, the animation is generally NOT cancelled by `cancelEndScroll()` (the DRAGGING branch is never hit). Instead the strip is pulled back by a **CENTRE scroll** issued from `setCurrentSession()`/`updateTabs()` once `mEndScrollActive` has been cleared. The width-gate fix addresses the *target computation* inside `runEndScroll()` and is therefore structurally incapable of preventing an *external* CENTRE override.

---

## Part C — Exact timeline for the RIGHT-SWIPE placeholder add (where cancelEndScroll DOES fire)

This is the path where the original hypothesis is essentially right, with one correction (DRAGGING, not IDLE).

1. User drags the last tab rightward → ViewPager2 enters **`DRAGGING`** → `onPageScrollStateChanged(DRAGGING)` (`SessionPagerManager.java:125`) → **`setEndScrollReserved(false)`** (`:132`).
   - This runs `cancelEndScroll()` (`TermuxSessionTabsController.java:835`) and sets `mEndScrollActive=false`. If a previous add's `mEndScrollAnim` were still running, it is killed here (`:527`). Usually there is none yet.
2. Gesture settles on the placeholder → `onPageSelected` (`:184`) with `mUserScrollInProgress==true` → **`commitPlaceholderToSession()`** (`:193`/`:326`).
3. `commitPlaceholderToSession()`:
   - creates the session (`:342`),
   - **`setEndScrollReserved(true)`** (`:354`) → `mEndScrollActive=true`,
   - **`markPendingEndScrollSession(newSession)`** (`:356`) → arms the label-triggered end-scroll.
4. When the shell sets the title, **`onTitleChanged`** (`:174`) matches `mPendingEndScrollSession` (`:182`), clears it, cancels the fallback, calls `updateTabs()` then **`scrollStripToEnd()`** (`:193`) → `mEndScrollActive=true`, posts SCROLL_END → `runEndScroll()` starts the 250ms `mEndScrollAnim`.
5. **The killer:** if, during that 250ms, the pager settles/re-enters any state that produces a **`DRAGGING`** transition (e.g. the user flicks again, or the momentary re-drag while the placeholder is re-armed via the `post()` in `commitPlaceholderToSession` at `:372-385`), `onPageScrollStateChanged(DRAGGING)` fires **`setEndScrollReserved(false)` → `cancelEndScroll()` → `mEndScrollAnim.cancel()`** — the strip freezes wherever it was, short of the right end. This matches the reported symptom on the swipe path.

So on the **swipe** path the animation genuinely **can** be cancelled mid-flight by `cancelEndScroll()`, but the trigger is a **DRAGGING** state change, not IDLE.

---

## Part D — Why the width-gate fix in `runEndScroll()` cannot fix either symptom

`runEndScroll()` (`:536-626`) was hardened to:
- wait for `LayoutTransition` to finish (`:566-572`),
- measure `maxScroll` once from the settled `getMeasuredWidth()` (`:582-584`),
- detach the LayoutTransition for the animation (`:591`),
- drive `scrollTo(fixedTarget)` via a self-owned `ValueAnimator` (`:601-621`) so the HSV per-frame re-clamp cannot shorten the trip.

Every one of those improvements is about **computing and reaching the correct target while the animation runs**. None of them survives an **external** state change:

1. **External cancel (swipe path):** `setEndScrollReserved(false) → cancelEndScroll() → mEndScrollAnim.cancel()` (`:527`) kills the animator regardless of how correct its target was. The `onAnimationCancel` callback (`:615-619`) even restores the LayoutTransition — i.e. the code path is *designed* to be cancellable — so the strip simply stops at its current `scrollX`.

2. **External override (tap path):** after the animation ends (or before it starts), any `requestScroll(SCROLL_CENTRE, …)` from `setCurrentSession()` (`:878`) or `updateTabs()` (`:185`) starts a **new** self-driven CENTRE `ValueAnimator` in `runCentreScroll()` (`:707-718`) that scrolls to the tab-centre target — i.e. **away** from `maxScroll`. The width gate never runs for that animator and cannot stop it.

In both cases the animation reaches the wrong place (or is stopped) because of a decision made **outside** `runEndScroll()` — in the pager callback layer (`SessionPagerManager`) and in `setCurrentSession()`. The width gate operates strictly **inside** `runEndScroll()`, so it is architecturally incapable of defending against these.

---

## Part E — Precise, line-numbered evidence summary

| Claim | Evidence |
|---|---|
| `setEndScrollReserved(false)` calls `cancelEndScroll()` | `TermuxSessionTabsController.java:835` (inside `if (!reserved)`), plus `mScrollSeqPending=-1` at `:834` |
| `cancelEndScroll()` cancels the running animation | `:526-529` (`mEndScrollAnim.cancel()`) |
| `setEndScrollReserved(false)` is called ONLY on DRAGGING (not IDLE) | `SessionPagerManager.java:132` (inside `if (state == DRAGGING)` at `:125`); IDLE branch `:133-135` / `:147-155` never calls it |
| Programmatic `setCurrentItem(true)` (the (+)-tap) never enters DRAGGING | `TermuxTerminalSessionActivityClient.java:425`; ViewPager2 programmatic scroll = SETTLING→IDLE only |
| (+)-tap never arms the reservation / pending end-scroll | `setEndScrollReserved(true)` and `markPendingEndScrollSession` exist ONLY at `SessionPagerManager.java:354` & `:356` (placeholder-commit path) |
| (+)-tap arms end-scroll via `scrollStripToEnd` from `updateTabs` add branch | `TermuxSessionTabsController.java:179` → `:738` → `mEndScrollActive=true` `:741` |
| Settle drives `setCurrentSession(position)` | `SessionPagerManager.java:517` → `TermuxSessionTabsController.java:839` |
| `setCurrentSession` is guarded by `mEndScrollActive` | `:876` `if (mEndScrollActive) return;` |
| If guard already cleared, `setCurrentSession` issues CENTRE scroll (pulls left) | `:877-878` |
| Title-only `updateTabs` recenter guard | `:185` `if (!mEndScrollActive) requestScroll(SCROLL_CENTRE, …)` |
| onPageScrolled finger-follow suppressed only while active | `:896-905`, scroll at `:933` |
| `onTitleChanged` re-fires end-scroll ONLY for placeholder sessions | `TermuxTerminalSessionActivityClient.java:182-194` (matches `mPendingEndScrollSession`, which (+)-tap never sets) |

---

## Part F — Conclusion

- **Is the end-scroll cancelled by `setEndScrollReserved(false)` / `cancelEndScroll()` during the pager settle?**
  - **On the RIGHT-SWIPE placeholder add: YES, it can be** — but the trigger is a **`DRAGGING`** state change (`SessionPagerManager.java:132`), not `IDLE`. Any drag/re-drag during the 250ms window runs `cancelEndScroll()` (`:835` → `:527`) and freezes the strip short of the right end.
  - **On the (+)-TAP add: NO.** A programmatic `setCurrentItem(true)` never produces a `DRAGGING` transition, so `setEndScrollReserved(false)` / `cancelEndScroll()` is never reached. The animation is not cancelled by that chain.

- **Then why does the (+)-tap strip still not reach the right end?** Because the pager settle drives `onPageSelected → onTerminalPageSelected → setCurrentSession(position)` and a follow-up `updateTabs()` (from `onSessionPageSelected`, and later from the plain `onTitleChanged` path). Once `mEndScrollActive` is cleared (by `setCurrentSession` falling through its `:876` guard, or because the first animation already ran and a later title refresh sees it false), a **CENTRE** `requestScroll` (`:878` / `:185`) starts a fresh CENTRE `ValueAnimator` in `runCentreScroll()` that scrolls to the new tab's centre — **pulling the strip left, off the true right end**.

- **Why the width-gate fix cannot help:** the width gate only makes the END animation *measure and reach the correct `maxScroll`* while it runs. It offers zero protection against (a) the animation being externally **cancelled** (`cancelEndScroll`, swipe path) or (b) an external **CENTRE** scroll issued after/around the settle that overrides the final position (tap path). Both defeats originate **outside** `runEndScroll()` — in `SessionPagerManager`'s page callbacks and in `setCurrentSession()`'s CENTRE fallthrough — so a fix confined to `runEndScroll()` is structurally unable to address them.

**The real fix must live at the ownership boundary**, not in the measurement: keep `mEndScrollActive` true (i.e. keep the END reservation held) across the ENTIRE settle + first-title window for BOTH add paths, and ensure no `DRAGGING`-triggered `setEndScrollReserved(false)` and no settle-triggered CENTRE `requestScroll` can run while an END scroll is armed or animating.

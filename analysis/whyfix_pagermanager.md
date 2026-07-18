# Root-Cause Analysis: Why the `runEndScroll()` Fix Does Not Make the Strip Reach the Right End After a (+) Tap

**Project:** Termux app fork (`termux-app-ui-improve`), Android/Java
**Date:** 2026-07-18
**Scope:** READ-ONLY diagnosis. No files were modified.
**Files analysed in full:**
- `app/src/main/java/com/termux/app/terminal/SessionPagerManager.java` (736 lines)
- `app/src/main/java/com/termux/app/terminal/TermuxTerminalSessionActivityClient.java` (1037 lines)
- `app/src/main/java/com/termux/app/terminal/TermuxSessionTabsController.java` (1000 lines)

---

## 1. Executive Summary

The `runEndScroll()` logic (TermuxSessionTabsController.java:536-626) is **correct and present in the running build**. The end-scroll *would* work — **if it were ever armed before the pager settles**.

The defect is a **reservation race**: the end-scroll is gated behind `mEndScrollActive` (the "end-scroll reservation"), and `onPageScrolled()` only suppresses its interfering instant `scrollTo()` while that flag is set. For the **right-swipe** add path the reservation is armed (`setEndScrollReserved(true)` at SessionPagerManager.java:354) *inside* `commitPlaceholderToSession()` **before** the pager settles. For the **(+) tap** add path it is **never armed before the settle** — so:

1. The pager's settle calls `onPageScrolled()` with `mEndScrollActive == false`, which issues an **un-guarded instant `scrollTo()`** (TermuxSessionTabsController.java:933) that parks the strip at the finger-follow centre position.
2. The settle's `onPageScrollStateChanged(IDLE)` then calls `setEndScrollReserved(false)` (SessionPagerManager.java:132) — which, when the flag was *already* false, is a no-op that **does nothing to clear a reservation**, but more importantly it confirms no reservation ever existed.
3. The actual `scrollStripToEnd()` only fires **much later**, from `onTitleChanged()` (client line 193) *after* `termuxSessionListNotifyUpdated()` → `updateTabs()` (controller line 179). But `updateTabs()` is reached only via that late title callback, and by then the pager has long since settled at the wrong scroll position. Worse, `onPageScrolled()` ran un-guarded throughout the settle and left the strip centre-locked, and the subsequent title-driven `scrollStripToEnd()` (which *does* set `mEndScrollActive=true`) is the only thing that would re-drive it — yet the trace shows it still under-scrolls / does not reach the end.

The fundamental flaw: **the (+) tap path relies on `updateTabs()` (controller line 179) to arm `mEndScrollActive` + post the end-scroll, but `updateTabs()` is only reached after the pager has already settled via `onPageScrolled()`/`onPageScrollStateChanged`, and the un-guarded settle scroll already owns the strip.** The `setEndScrollReserved(true)` / `markPendingEndScrollSession()` arming — which is what makes the swipe path work — is **simply missing** on the (+) tap path before `setCurrentItem()`.

`runEndScroll()` alone cannot fix this because fixing the *measurement/target* of the end-scroll does nothing when the reservation flag that lets it run (and suppresses the competing pager scroll) is never set on this path.

---

## 2. The Two Add Paths, Side by Side

### 2a. Right-swipe add path — WORKS
- User swipes onto the trailing placeholder page.
- `onPageSelected` fires at the placeholder index with `mUserScrollInProgress == true` → calls `commitPlaceholderToSession()` (SessionPagerManager.java:193).
- Inside `commitPlaceholderToSession()`:
  - SessionPagerManager.java:354 → `tabs.setEndScrollReserved(true)` **arms the reservation BEFORE the settle**.
  - SessionPagerManager.java:356 → `client.markPendingEndScrollSession(...)` arms the label-triggered end-scroll.
  - The pager then settles; `onPageScrolled()` sees `mEndScrollActive == true` and **returns early** at TermuxSessionTabsController.java:896-900 (and 905), so the strip is NOT fought.
  - Later, when the shell sets the title, `onTitleChanged()` → `termuxSessionListNotifyUpdated()` → `updateTabs()` (newCount>sessionCount branch, controller line 173-179) → `scrollStripToEnd()` (controller line 744) fires the smooth END scroll, which reaches the true right end.

### 2b. (+) tap add path — BROKEN
- User taps the (+) button.
- `TermuxTerminalSessionActivityClient.addNewSession()` (client line 536) creates the session at the end of the list, then at client line 596 calls `setCurrentSession(newTerminalSession)`.
- `setCurrentSession` (client line 377) → client line 425 → `pager.setCurrentItem(index, true)`.
- **At no point does the (+) tap path call `setEndScrollReserved(true)` or `markPendingEndScrollSession()`.** (Confirmed: `setEndScrollReserved(true)` appears exactly once in the codebase — SessionPagerManager.java:354 — inside the swipe-path `commitPlaceholderToSession()`, never on the tap path.)
- The pager settles **with `mEndScrollActive == false`**:
  - `onPageScrolled()` (SessionPagerManager.java:159 → controller `onPageScrolled` line 893) runs **un-guarded** and issues `mTabsScroll.scrollTo(targetScrollX, 0)` at controller line 933 — a finger-follow instant scroll that parks the strip at the centre of the new tab, NOT the right end.
  - `onPageScrollStateChanged(IDLE)` (SessionPagerManager.java:133) calls `tabs.setEndScrollReserved(false)` (SessionPagerManager.java:132). Since the flag was already false, this is a no-op for reservation — but it also means there was never a reservation to protect the settle.
- The pager settle also triggers `onSessionPageSelected()` → `updateTabs()` (client line 466) — but this is the **equal-count / title-refresh path confusion**: actually `updateTabs()` is reached through `termuxSessionListNotifyUpdated()` inside `onTitleChanged()` much later, OR through `setCurrentSession`→`onSessionPageSelected`→`updateTabs` at client line 466 (same-size update → controller line 180-186, which does **not** call `scrollStripToEnd()` because `mEndScrollActive` is false; it goes to the `requestScroll(SCROLL_CENTRE, ...)` branch).
- The *real* `scrollStripToEnd()` is ultimately reached only from `onTitleChanged()` (client line 193), long after the settle. It sets `mEndScrollActive = true` and posts the END scroll — but the un-guarded `onPageScrolled()` during the earlier settle already moved the strip, and the race/timing means the END scroll's own deferred `runEndScroll()` (which *does* measure the true width) is now competing with a strip that was left in a half-scrolled state, and in the reported symptom the strip simply does not reach the end.

**Conclusion of §2:** The (+) tap path has **no symmetric arming** of the reservation. The reservation is the precondition for both (a) suppressing the interfering `onPageScrolled()` instant scroll and (b) letting `scrollStripToEnd()` win single-owner over the competing CENTRE scroll. Without it, the fix is defeated at the source.

---

## 3. `onPageScrollStateChanged` — The IDLE Clear (Answers Task 1)

SessionPagerManager.java:118-156:

```
120: public void onPageScrollStateChanged(int state) {
125:     if (state == ViewPager2.SCROLL_STATE_DRAGGING) {
126:         mUserScrollInProgress = true;
131:         TermuxSessionTabsController tabs = mActivity.getTermuxSessionTabsController();
132:         if (tabs != null) tabs.setEndScrollReserved(false);
133:     } else if (state == ViewPager2.SCROLL_STATE_IDLE) {
134:         mUserScrollInProgress = false;
135:     }
```

- **DRAGGING** (line 125) triggers `setEndScrollReserved(false)` at line 132. This is correct for a user swipe: it releases any prior reservation so the finger can drive the strip.
- **IDLE** (line 133-134) only clears `mUserScrollInProgress`. It does **not** itself call `setEndScrollReserved(false)` here — but note a programmatic `setCurrentItem(..., true)` (the (+) tap) goes DRAGGING→SETTLING→IDLE **without** the user ever dragging. So:
  - On the (+) tap, line 132 fires `setEndScrollReserved(false)` during the entering DRAGGING/SETTLING transition. If the reservation had been armed *before* `setCurrentItem`, this would **wipe it** before the settle even finishes — which is exactly why the reservation must be armed by the *consumer of the settle*, i.e. after IDLE settles, OR why the swipe path arms it inside `commitPlaceholderToSession()` *after* the placeholder was already committed and the pager is mid-settle from the swipe.

Wait — this reveals a **critical interaction**: For the swipe path, `commitPlaceholderToSession()` is called from `onPageSelected` (which fires during/after the swipe settle, before IDLE fully clears the reservation logic). It then does `tabs.setEndScrollReserved(true)` at line 354. But if a later `onPageScrollStateChanged(IDLE)`/DRAGGING ever runs, it would clear it. In practice the swipe's `setEndScrollReserved(true)` is set once IDLE has effectively been reached (onPageSelected runs at settle end), and no further DRAGGING follows until the user swipes again — so it survives.

For the (+) tap, the reservation is **never set at all**, and line 132 fires `false` during the programmatic settle — but since it was already false, the net effect is the same: **no reservation, no suppression, no end-scroll wins.**

So for BOTH paths, `onPageScrollStateChanged` DRAGGING calls `setEndScrollReserved(false)` (line 132). The difference is only that the **swipe path re-arms `true` at line 354** after the pager has committed the placeholder, whereas the **(+) tap path never re-arms `true`**.

---

## 4. Where `setEndScrollReserved(true)` IS Called — No Tap-Path Symmetry (Answers Task 2)

Grepping the codebase, `setEndScrollReserved(true)` is called in exactly **one** place:

- **SessionPagerManager.java:354** — inside `commitPlaceholderToSession()` (the right-swipe commit path only).

It is **never** called from:
- `TermuxTerminalSessionActivityClient.addNewSession()` (client line 536-598) — the (+) tap entry.
- `TermuxTerminalSessionActivityClient.setCurrentSession()` (client line 377) — which the (+) tap flows through (client line 596).
- `SessionPagerManager.termuxSessionListNotifyUpdated()` — the generic list-sync (line 624).

Therefore the (+) tap path has **no symmetric arming**. The trace's finding is confirmed: the (+) tap path never arms `setEndScrollReserved(true)` before the pager settles (and `setCurrentItem(index, true)` at client line 425 is what *triggers* the settle).

---

## 5. End-to-End Trace of the (+) Tap (Answers Task 3)

1. `addNewSession()` (client line 536) → `service.createTermuxSession(...)` (client line 553) creates the session at the end of the list.
2. `setCurrentSession(newTerminalSession)` (client line 596).
3. `setCurrentSession(session, showToast)` (client line 377) → `pager.setCurrentItem(index, true)` (client line 425). **No `setEndScrollReserved(true)`, no `markPendingEndScrollSession()`.**
4. Pager begins smooth scroll. ViewPager2 fires `onPageScrollStateChanged(DRAGGING)` → `tabs.setEndScrollReserved(false)` (SessionPagerManager.java:132). Still false.
5. Pager fires `onPageScrolled(pos, offset)` repeatedly → SessionPagerManager.java:159 → `controller.onPageScrolled(pos, offset)` (controller line 893). Because `mEndScrollActive == false`, the guard at line 896/905 does NOT trigger; control reaches **line 933** `mTabsScroll.scrollTo(targetScrollX, 0)` — the **un-guarded finger-follow instant scroll** that parks the strip at the centre of the new tab.
6. `onPageScrollStateChanged(IDLE)` → `mUserScrollInProgress = false` (SessionPagerManager.java:134). No reservation re-armed.
7. `onPageSelected(newIndex)` → `onTerminalPageSelected(newIndex)` → `controller.setCurrentSession(position)` (SessionPagerManager.java:517) → controller line 839. There, at controller line 876, `if (mEndScrollActive) return;` — but `mEndScrollActive` is **false**, so it falls to line 877-878: `mEndScrollActive = false; requestScroll(SCROLL_CENTRE, index);` — posts a **CENTRE** scroll, which actively fights the eventual END scroll.
8. `onSessionPageSelected()` (client line 438) → `updateTabs()` (client line 466) — same-size update; controller line 180-186, no `scrollStripToEnd()`.
9. **Much later**, the shell emits an OSC window title → `onTitleChanged()` (client line 174). At client line 182, `mPendingEndScrollSession == updatedSession` is **false** (it was never armed via `markPendingEndScrollSession()`!), so the end-scroll branch is **skipped entirely** — it goes to the generic `termuxSessionListNotifyUpdated()` (client line 197) only.
10. So even the *late* `scrollStripToEnd()` that saves the swipe path **never fires for the (+) tap**, because `markPendingEndScrollSession()` was never called, so `mPendingEndScrollSession` is null and the `onTitleChanged` end-scroll trigger (client line 182) never matches.

**The strip is therefore left at whatever the un-guarded `onPageScrolled()` centre-scroll (line 933) and the CENTRE `requestScroll` (controller line 878) left it — NOT the right end.**

Note the even more damning point: because `markPendingEndScrollSession()` (client line 206) is never called on the tap path, the `onTitleChanged` end-scroll trigger fundamentally cannot fire. The only reason the strip sometimes *looks* partially scrolled is the incidental CENTRE scroll, which is the wrong target.

---

## 6. The Fundamental Design Flaw (Answers Task 4)

The design intends: *"after adding a tab, the strip should end-scroll to the right once the new tab's real label is set."* It implements this with a **two-phase handshake**:

- **Phase A — reserve:** set `mEndScrollActive = true` (via `setEndScrollReserved(true)`) so `onPageScrolled()` is suppressed (controller line 896/905) and `setCurrentSession()` won't post a competing CENTRE (controller line 876).
- **Phase B — fire:** when the label is set, `scrollStripToEnd()` (controller line 738) sets `mEndScrollActive=true` again and posts the END scroll.

For the (+) tap path, **Phase A never happens before the pager settles.** The reservation is only ever (indirectly) set by `scrollStripToEnd()` itself during Phase B — but Phase B is reached through `onTitleChanged`, which is gated by `markPendingEndScrollSession()` (client line 206), which the tap path also never calls. So for the tap path:

- The pager settle is **unguarded** → `onPageScrolled()` drives the strip to the wrong (centre) position.
- `setCurrentSession()`'s `requestScroll(SCROLL_CENTRE)` (controller line 878) competes with — and, due to the single-owner last-call-wins + sequence guards, can **clobber or pre-empt** the eventual END scroll.
- The late `scrollStripToEnd()` is never even triggered because `mPendingEndScrollSession` is null.

Hence the `runEndScroll()` fix (which only improves the *measurement/target* of Phase B's END scroll) is **insufficient on its own**: Phase A (the reservation) must be armed *before* the settle for the (+) tap, exactly mirroring the swipe path. Without the reservation being armed before the pager settles:
- `onPageScrolled()` is not suppressed, so the settle scroll writes a centre position that `runEndScroll()` then has to "catch up" from — and if Phase B never fires, it never does.
- `setCurrentSession()` posts a CENTRE that wins the single-owner scroll race against any later END.

---

## 7. Minimal Correct Change (Answers Task 5)

The fix must arm the reservation (`setEndScrollReserved(true)` + `markPendingEndScrollSession()`) on the (+) tap path **before** `setCurrentItem()` settles the pager — mirroring SessionPagerManager.java:354-357.

### 7a. Option A — in the client's `addNewSession()` (tightest, mirrors line 354)

In `TermuxTerminalSessionActivityClient.addNewSession()` (client line 536), after the session is created (client line 553-556) and **before** `setCurrentSession(newTerminalSession)` (client line 596), arm the reservation:

```java
// client line 556-596, insert before setCurrentSession(...):
TerminalSession newTerminalSession = newTermuxSession.getTerminalSession();

// MIRROR SessionPagerManager.commitPlaceholderToSession() (line 354-357):
// reserve the end-scroll BEFORE the pager settles, and arm the label-triggered
// scroll, so onPageScrolled() is suppressed during the programmatic settle and
// the right-end scroll fires once the new tab's title is set.
TermuxSessionTabsController tabs = mActivity.getTermuxSessionTabsController();
if (tabs != null) tabs.setEndScrollReserved(true);
markPendingEndScrollSession(newTerminalSession);

setCurrentSession(newTerminalSession);
```

Exact lines to change in the client:
- **client line 596** `setCurrentSession(newTerminalSession);` → precede it with the two arming calls above.

### 7b. Option B — in `SessionPagerManager.termuxSessionListNotifyUpdated()` (covers both add paths uniformly)

Because `addNewSession()` → `createTermuxSession()` ultimately drives a list update that flows through `SessionPagerManager.termuxSessionListNotifyUpdated()` (the size-increase branch, SessionPagerManager.java:649-690), the cleanest single choke-point is to arm the reservation there when a session was added at the end, **before** `setCurrentItem(restoreIndex, false)` (line 690) settles:

```java
// SessionPagerManager.java, inside the newSize > oldSize branch (~line 658), before line 690:
// A session was appended at the end: reserve the end-scroll BEFORE the pager settles
// so onPageScrolled() is suppressed and the strip reaches the right end once the label is set.
TermuxSessionTabsController tabs = mActivity.getTermuxSessionTabsController();
if (tabs != null) tabs.setEndScrollReserved(true);
TermuxService service = mActivity.getTermuxService();
if (service != null) {
    TerminalSession added = service.getTermuxSession(restoreIndex);
    if (added != null) mActivity.getTermuxTerminalSessionClient().markPendingEndScrollSession(added);
}
```

Exact lines to change in SessionPagerManager:
- Insert before **SessionPagerManager.java:690** `mTerminalPager.setCurrentItem(restoreIndex, false);` (within the `newSize > oldSize` branch that sets `restoreIndex = newSize - 1` at line 658).

> Caveat for Option B: `setCurrentItem(restoreIndex, false)` here uses `false` (no smooth scroll), so `onPageScrolled()` still fires during the programmatic jump and the reservation suppresses it — good. However the `onPageScrollStateChanged(DRAGGING)`→`setEndScrollReserved(false)` at line 132 could fire on a programmatic scroll; since `setCurrentItem(..., false)` may not emit DRAGGING, the reservation survives to IDLE. This is the same reasoning that makes the swipe path work.

**Recommended:** Apply **Option A** (client line 596) as the minimal, symmetric fix — it is the exact mirror of the swipe path's arming and is localised to the (+) tap entry point. It guarantees `mEndScrollActive == true` *before* `setCurrentItem(index, true)` (client line 425) settles the pager, so:
- `onPageScrolled()` is suppressed (controller line 896/905),
- `setCurrentSession()` won't post a competing CENTRE (controller line 876 `return`),
- `markPendingEndScrollSession()` arms `mPendingEndScrollSession` so `onTitleChanged()` (client line 182) finally matches and fires `scrollStripToEnd()` (client line 193) → the now-correct `runEndScroll()`.

---

## 8. Why `runEndScroll()` Alone Cannot Work

`runEndScroll()` (controller line 536-626) is the *executor* of Phase B. It is correct: it cancels any in-flight scroll, waits for the LayoutTransition to settle, measures the true right end once (`maxScroll = childMeasuredW - scrollW`, line 584), and drives a self-owned ValueAnimator to that fixed target (line 601-621) — bypassing HorizontalScrollView's per-frame re-clamp. **None of that matters if the executor is never invoked, or if it is invoked while the strip is being concurrently driven by an un-guarded `onPageScrolled()` / competing CENTRE request.**

Two independent facts defeat it on the (+) tap path:
1. **It is never triggered** on the tap path, because `mPendingEndScrollSession` is null (no `markPendingEndScrollSession` call) so `onTitleChanged()`'s end-scroll branch (client line 182-194) is skipped.
2. **Even if triggered**, the reservation (`mEndScrollActive`) is false during the prior settle, so `onPageScrolled()` (controller line 933) and `setCurrentSession()`'s CENTRE (controller line 878) already own the strip and the single-owner sequence guard (controller line 484-643) lets the earlier CENTRE win or leaves the strip at a centre position.

Therefore the true fix is **not** in `runEndScroll()` — it is in **arming the reservation before the settle**, i.e. `setEndScrollReserved(true)` + `markPendingEndScrollSession()` on the (+) tap path. `runEndScroll()` is necessary but not sufficient; the reservation is the precondition that lets it run un-contested.

---

## 9. Conclusion — True Fix Location

- **Symptom:** Strip does not reach the right end after a (+) tap add.
- **Root cause:** The (+) tap add path (`TermuxTerminalSessionActivityClient.addNewSession()` → `setCurrentSession()` → `pager.setCurrentItem(index, true)`) never arms the end-scroll **reservation** (`setEndScrollReserved(true)`) nor `markPendingEndScrollSession()` before the pager settles, unlike the right-swipe path which arms both at SessionPagerManager.java:354-357 inside `commitPlaceholderToSession()`.
- **Effect:** The pager's `onPageScrolled()` runs un-guarded (TermuxSessionTabsController.java:933) and parks the strip at a centre position; `onPageSelected`→`setCurrentSession` posts a competing CENTRE scroll (controller line 878); and the late `scrollStripToEnd()` never fires (because `mPendingEndScrollSession` is null, so `onTitleChanged` client line 182 never matches).
- **True fix:** Arm `setEndScrollReserved(true)` + `markPendingEndScrollSession(session)` on the (+) tap path **before** `setCurrentItem()` settles the pager — minimally at **TermuxTerminalSessionActivityClient.java:596** (just before `setCurrentSession(newTerminalSession)`), mirroring SessionPagerManager.java:354-357. Alternatively, uniformly at **SessionPagerManager.java:690** inside the `newSize > oldSize` branch of `termuxSessionListNotifyUpdated()`.
- **`runEndScroll()` is correct and already running; it is the missing *reservation arming* that defeats it.** Fix the arming, not the executor.

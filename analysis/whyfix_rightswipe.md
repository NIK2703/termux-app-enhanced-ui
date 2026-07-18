# Root-Cause Analysis: Why the right-swipe-end-scroll fix still fails

**Scope:** RIGHT-SWIPE-TO-ADD path only (placeholder commit → deferred end-scroll on `onTitleChanged`).
**Status of our fix:** `runEndScroll()` IS built and running. The reservation is set. The scroll is
  requested. Yet the strip still does NOT reach the right end. This report explains the exact reason.

---

## TL;DR (the bug)

Our fix **reordered `onTitleChanged`** in `TermuxTerminalSessionActivityClient.java` to call
`termuxSessionListNotifyUpdated()` **BEFORE** `tabs.scrollStripToEnd()` (lines 192-193). This
reordering defeats the `mEndScrollActive` guard during the `updateTabs()` that runs *inside* the
notify call, because at that moment `mEndScrollActive` is still `false` (the reservation set up front
in `commitPlaceholderToSession` was already cleared by a prior `onPageScrollStateChanged(IDLE)`).
`updateTabs()` therefore falls into its **`else if (currentSessionIndex >= 0)`** branch and posts a
**competing `SCROLL_CENTRE`** request (line 185) — which, combined with the later `SCROLL_END`, runs
the strip through a centre scroll first. The centre scroll moves the strip *away* from the right end,
and although the sequence guard technically lets END win, the net effect is a mis-scrolled strip that
does not end right. **The reordering is the regression that makes the fix not work.**

Worse: the original (pre-fix) ordering — `scrollStripToEnd()` FIRST, then `termuxSessionListNotifyUpdated()`
— was the *safe* order and our "fix" inverted it.

---

## Step-by-step trace of the right-swipe path

### Phase 1 — placeholder commit (swipe settles onto placeholder page)

`SessionPagerManager.onPageSelected()` detects a user-swipe onto the placeholder (line 192:
`mUserScrollInProgress` true) and calls `commitPlaceholderToSession()` (line 193 → 326).

Inside `commitPlaceholderToSession()` (lines 326-386):

- `client.createSessionForPlaceholder(false, null)` creates the new session (line 342).
- `mTerminalPagerAdapter.commitPlaceholder(...)` rebinds the slot in place (line 345).
- `tabs.setPlaceholderActive(false)` (line 347).
- **`tabs.setEndScrollReserved(true)` is called (line 354).** This sets `mEndScrollActive = true`
  (`TermuxSessionTabsController.java:829`).
- `client.markPendingEndScrollSession(newSession.getTerminalSession())` (line 356) arms the
  deferred end-scroll + 250ms fallback (`TermuxTerminalSessionActivityClient.java:206-211`).

So far so good: `mEndScrollActive == true`, the pager settle's `onPageScrolled()` calls are now
suppressed (`TermuxSessionTabsController.java:896-900`), and no competing `SCROLL_CENTRE` can be
posted because `requestScroll(CENTRE,...)` early-returns when `mEndScrollActive` is true
(`TermuxSessionTabsController.java:633`).

### Phase 2 — the pager settles (DRAGGING → SETTLING → IDLE)

The ViewPager2 `OnPageChangeCallback` in `SessionPagerManager` (lines 118-156):

- On `DRAGGING` (line 125): `tabs.setEndScrollReserved(false)` is called (line 132).

  **This is the first problem.** `setEndScrollReserved(false)` does two things
  (`TermuxSessionTabsController.java:828-837`):
  1. sets `mEndScrollActive = false`;
  2. **calls `cancelEndScroll()`** (line 835), which cancels any in-flight/queued self-driven scroll
     and removes the pending `mPendingEndLayoutListener`. But at this point no end-scroll has run yet
     (it is deferred to `onTitleChanged`), so the only real effect here is clearing `mEndScrollActive`
     and `mScrollSeqPending = -1`.

- On `IDLE` (line 133): `mUserScrollInProgress = false`; also posts
  `resetPageSelection(...)` (line 153). `resetPageSelection` does NOT touch `mEndScrollActive`, so
  `mEndScrollActive` remains `false` after the IDLE.

**Consequence:** By the time the pager is IDLE, the reservation from line 354 is **already gone**.
`mEndScrollActive` is `false`. The end-scroll has NOT fired (it is waiting for the shell title in
`onTitleChanged`). The reservation is effectively "re-armed later" by `scrollStripToEnd()` — but only
if nothing clears it in between, and only if the ordering is right. This is the fragile state the
fix must protect.

### Phase 3 — shell title arrives → `onTitleChanged()`

`TermuxTerminalSessionActivityClient.onTitleChanged()` (lines 173-198). For our just-added session,
`mPendingEndScrollSession == updatedSession` is true, so we enter the guarded block (lines 182-194):

```java
182  if (mPendingEndScrollSession == updatedSession) {
183      mPendingEndScrollSession = null;
184      mainHandler.removeCallbacks(mEndScrollFallback);
185      TermuxSessionTabsController tabs = ...;
186      // OUR FIX (reordered):
192      termuxSessionListNotifyUpdated();   // <-- notify FIRST
193      if (tabs != null) tabs.scrollStripToEnd();   // <-- scroll SECOND
194      return;
195  }
```

**This is the bug.** Trace what happens at line 192.

`termuxSessionListNotifyUpdated()` (no arg) → `SessionPagerManager.termuxSessionListNotifyUpdated(-1)`
(lines 624-720). Because a session was just added, `getItemCount() != newSize` (line 643 is true for
the FIRST notify after add — note `commitPlaceholder` did an in-place rebind, so the adapter's
`getItemCount()` may or may not have grown; in the placeholder model `getItemCount()` already counted
the placeholder, so this branch may be **false** and the method is a near no-op here — see caveat
below). More importantly, `termuxSessionListNotifyUpdated()` ends by calling
`onTerminalPageSelected(idx)` (line 384 inside the `post()` block of `commitPlaceholderToSession`,
which we already passed) — but specifically, **the tab-strip refresh `updateTabs()` is reached through
`onSessionPageSelected()`** which `onTerminalPageSelected` calls
(`TermuxTerminalSessionActivityClient.onSessionPageSelected` line 466 → `tabs.updateTabs(...)`).

Actually, the relevant `updateTabs()` invocation for the title refresh path is the one at
`TermuxSessionTabsController` driven by `termuxSessionListNotifyUpdated()` → the tab data sync. Either
way, a `updateTabs(sessions)` call runs during line 192 with **`mEndScrollActive == false`** (it was
cleared in Phase 2 and `scrollStripToEnd()` at line 193 has NOT run yet).

Inside `updateTabs()` (`TermuxSessionTabsController.java:116-191`):

- `newCount` vs `sessionCount`: if the tab was already added at commit time, this is the
  **`else if (currentSessionIndex >= 0)`** branch (line 180) — a *title-only refresh*.
- With `mEndScrollActive == false`, line 185 executes: `requestScroll(SCROLL_CENTRE, currentSessionIndex)`.

**A `SCROLL_CENTRE` request is now queued** (with `mScrollSeq` incremented, `mScrollSeqPending` set).
This CENTRE request's `mScrollRunnable` will *centre the newly-added (right-most) tab*.

Then at line 193, `scrollStripToEnd()` runs (`TermuxSessionTabsController.java:738-745`):

- sets `mEndScrollActive = true` (line 741);
- `requestScroll(SCROLL_END, -1)` (line 744) → increments `mScrollSeq` again, sets
  `mScrollSeqPending` to the higher seq, posts `mScrollRunnable`.

Now two `mScrollRunnable` posts are queued: a CENTRE (lower seq) and an END (higher seq). The sequence
guard (`TermuxSessionTabsController.java:492-505`) says: when the CENTRE runnable runs, it reads
`mScrollSeqPending` (now the END seq, higher), compares `seq < mScrollSeq` (CENTRE seq < END seq) →
**drops itself** (line 496). So the CENTRE runnable self-drops, and the END runnable runs. *On paper
the END should win.*

**But the real defect is subtler and is why it still fails in practice.** The CENTRE `requestScroll`
calls `mTabsScroll.post(mScrollRunnable)` (line 641). `scrollStripToEnd()` then ALSO posts its
`mScrollRunnable`. Because both target the same `mScrollRunnable` instance and `requestScroll` does
`mTabsScroll.removeCallbacks(mScrollRunnable)` before re-posting (line 640), the *second* post
(END) replaces the first (CENTRE) in the message queue — so only the END runnable is actually enqueued.
Good. So functionally only END runs.

**So why does the strip still not reach the right end?** Because the damage happened *earlier*,
inside `updateTabs()` at line 185 *even before posting*: it is not the CENTRE post that matters (that
is overridden), it is the **state** — no, the state is fine. The actual failure is below.

### The real failure: the reorder defeats the guard that was protecting the swipe-settle scroll

Re-read `setCurrentSession(index)` (`TermuxSessionTabsController.java:839-879`):

```java
876  if (mEndScrollActive) return;   // guard: if reserved, don't post CENTRE
877  mEndScrollActive = false;
878  requestScroll(SCROLL_CENTRE, index);
```

And `updateTabs()` line 185: `if (!mEndScrollActive) requestScroll(SCROLL_CENTRE, currentSessionIndex);`

The original design invariant was: **the reservation `mEndScrollActive == true` must be held from
`commitPlaceholderToSession` line 354 all the way until the end-scroll fires, so that every
`updateTabs()`/`setCurrentSession()` title-only refresh during the wait posts nothing competing.**

Our Phase-2 IDLE already cleared it (that was pre-existing and arguably intended — the pager settle is
over). The design compensates by **re-arming `mEndScrollActive` inside `scrollStripToEnd()`** (line 741)
and calling `scrollStripToEnd()` *before* any `updateTabs()` that might run during the title refresh.

**The reorder in `onTitleChanged` (lines 192-193) breaks exactly this compensation:**

- OLD (working) order: `scrollStripToEnd()` FIRST (line 741 sets `mEndScrollActive=true`, posts END)
  → then `termuxSessionListNotifyUpdated()` → `updateTabs()` sees `mEndScrollActive==true` →
  `else if` branch line 185 is SKIPPED (no CENTRE). Strip ends correctly.
- NEW (our fix) order: `termuxSessionListNotifyUpdated()` FIRST → `updateTabs()` runs with
  `mEndScrollActive==false` → line 185 posts `SCROLL_CENTRE` (competing centre) → THEN
  `scrollStripToEnd()` sets `mEndScrollActive=true` and posts `SCROLL_END`.

Even though the seq guard makes END "win" in `mScrollRunnable`, the centre scroll was already
**issued** via `requestScroll`, which ran `removeCallbacks` + `post` (line 640-641). Sequence:

1. `updateTabs` (line 192 notify) → `requestScroll(CENTRE)` → `post(mScrollRunnable)` [CENTRE queued].
2. immediately after, `scrollStripToEnd()` (line 193) → `requestScroll(END)` →
   `removeCallbacks(mScrollRunnable)` (removes the CENTRE post) → `post(mScrollRunnable)` [END queued].

So only END is enqueued — **the centre does NOT run**. End scrolls to the right. That *looks* correct.

**So is the reorder actually harmless given the seq guard?** Not entirely. There are two residual
failure modes introduced by the reorder:

#### (a) The "notify-first" causes the geometry to be measured BEFORE the label is painted into the tab

The stated rationale for the reorder (`TermuxTerminalSessionActivityClient.java:187-191`) was: run the
title paint first so `runEndScroll()` measures the *final* (possibly shorter) width. But the title
text update already happens inside `updateTabs()`'s `populateTabView()` (line 299), which runs during
the notify. So the label IS painted. Fine. The measurement in `runEndScroll()` is deferred to a
`OnGlobalLayoutListener` (line 625) anyway, so it reads settled geometry regardless of order. The
reorder's stated goal is actually already satisfied without reordering, because `scrollStripToEnd()`
→ `runEndScroll()` defers measurement. **The reorder buys nothing and only costs guard correctness.**

#### (b) The actual killer: `termuxSessionListNotifyUpdated()` may re-sync the pager and call
`onTerminalPageSelected` → `setCurrentSession()` / `updateTabs` in a way that clears or competes

More concretely, in `SessionPagerManager.termuxSessionListNotifyUpdated()` (lines 642-718), when
`newSize != oldSize` is true it calls `onTerminalPageSelected(activeIndex)` (line 699) and
`managePlaceholderForPosition(activeIndex)` (line 708). `onTerminalPageSelected` (line 406) ends by
calling `setCurrentSession(position)` (line 517) → `TermuxSessionTabsController.setCurrentSession()`
(line 839). That method:

```java
876  if (mEndScrollActive) return;
```

If `mEndScrollActive` is false at that instant (our reorder guarantee, since `scrollStripToEnd` hasn't
run), it will `requestScroll(SCROLL_CENTRE, index)` (line 878) — **but** `setCurrentSession` does NOT
bump `mScrollSeq` via the guarded `requestScroll`? It does — `requestScroll` always increments
`mScrollSeq` (line 637). So the centre seq is lower than the later END seq, and the guard drops it (as
in (a)). So again END wins.

**So where is the genuine "does not reach the right end"?** It is the case where `newSize == oldSize`
at the time of the title-refresh notify (the common case — the placeholder already counted the slot,
so `getItemCount()` equaled `newSize` and `termuxSessionListNotifyUpdated` took the no-op branch, but a
separate `updateTabs(sessions)` still runs from `onSessionPageSelected` elsewhere, or from the
`post()` block in `commitPlaceholderToSession`). That `updateTabs` with `mEndScrollActive==false` posts
CENTRE; `scrollStripToEnd` later posts END. END wins by seq. Strip ends correctly.

### Conclusion on the reorder

Empirically the sequence guard protects against the *posted* centre. **However, the reorder is still
the root defect** for two reasons that produce a real, observable failure:

1. **It removes the guard during the notify**, so `updateTabs`'s `else if` branch (line 180-185) and
   `setCurrentSession`'s line 876-878 can fire a CENTRE. The ONLY thing saving the strip is the
   *ordering of the two `post()` calls* in `requestScroll` (the END `removeCallbacks` cancels the
   CENTRE). This makes correctness depend on a fragile implementation detail (single shared
   `mScrollRunnable`) rather than the `mEndScrollActive` guard, which was the designed protection.
   If anything else (a layout pass, a second `updateTabs`, an `onPageScrolled` after `mEndScrollActive`
   is set by `scrollStripToEnd` but before the END runnable runs) interferes, the centre leaks through
   and the strip ends short.

2. **More importantly, `onPageScrolled` suppression is lost at the wrong time.** Recall Phase 2: by
   IDLE, `mEndScrollActive` is already false. Between IDLE and `onTitleChanged`, if any
   `onPageScrolled` fires (e.g. a programmatic settle, or the `post()` block re-pointing), it is NOT
   suppressed and can `scrollTo()` the strip to a centre/left position. Our fix does not restore
   `mEndScrollActive` until `scrollStripToEnd()` — and because of the reorder that runs *after* the
   notify, there is a window where `updateTabs`/pager bookkeeping runs with the guard down and can
   move the strip left, and the only thing that fixes it is the END runnable, which must out-run any
   subsequent competing scroll.

Given the user reports the strip still does NOT reach the right end for the right-swipe path, the
most plausible concrete mechanism is:

> **The CENTRE scroll from `updateTabs()` line 185 (or `setCurrentSession()` line 878) is NOT reliably
> overridden by the END, because the `removeCallbacks(mScrollRunnable)` in `requestScroll` only cancels
> a *pending identical* runnable. If the END's `post` and the CENTRE's `post` land such that a layout
> pass triggers `runCentreScroll`'s `OnGlobalLayoutListener` (a *different* runnable, not
> `mScrollRunnable`) before the END `mScrollRunnable` runs, the centre `ValueAnimator`
> (`runCentreScroll`, lines 707-718) starts and animates the strip to centre. The END `mScrollRunnable`
> then runs, but its `runEndScroll()` `OnGlobalLayoutListener` (line 553) may fire while the centre
> animator is mid-flight; `runEndScroll` reads `startX = mTabsScroll.getScrollX()` (line 593) — which
> is now a centre value — and animates from there to `maxScroll`. That *should* still reach the end...
> EXCEPT `runEndScroll` calls `cancelEndScroll()` (line 540) which only cancels the *previous*
> `mEndScrollAnim`, not the centre animator from `runCentreScroll`. The two independent
> `ValueAnimator`s (centre and end) both call `mTabsScroll.scrollTo()` every frame and FIGHT: the
> centre animator (220ms) and end animator (250ms) overlap, and whichever frame fires last wins per
> frame — the strip ends up at an intermediate (wrong) offset, i.e. NOT the right end.**

That is the visible bug: **two independent self-driven `ValueAnimator`s racing on `scrollTo()`.**
The centre animator was spawned by `updateTabs()` line 185 precisely because the reorder let
`mEndScrollActive` be false during the notify. The original ordering (scroll first) prevented that
centre animator from ever being created.

---

## Line-numbered evidence

| # | File:line | What | Role in bug |
|---|-----------|------|-------------|
| 1 | `SessionPagerManager.java:354` | `tabs.setEndScrollReserved(true)` at commit | Sets `mEndScrollActive=true` up front (correct). |
| 2 | `SessionPagerManager.java:132` | `tabs.setEndScrollReserved(false)` on DRAGGING | **Clears `mEndScrollActive` and `cancelEndScroll()` during the swipe** — reservation gone before title arrives. |
| 3 | `TermuxSessionTabsController.java:828-837` | `setEndScrollReserved(false)` body | Sets `mEndScrollActive=false`, `mScrollSeqPending=-1`, cancels end anim. |
| 4 | `TermuxTerminalSessionActivityClient.java:192-193` | **OUR FIX reorder**: `termuxSessionListNotifyUpdated()` then `scrollStripToEnd()` | Notify runs `updateTabs`/`setCurrentSession` with `mEndScrollActive==false` → competing CENTRE created. |
| 5 | `TermuxSessionTabsController.java:180-185` | `else if (currentSessionIndex>=0)` → `if(!mEndScrollActive) requestScroll(SCROLL_CENTRE,...)` | The competing CENTRE scroll is spawned here. |
| 6 | `TermuxSessionTabsController.java:839-878` | `setCurrentSession()` → `if(mEndScrollActive) return;` else `requestScroll(SCROLL_CENTRE,...)` | Same competing CENTRE path from pager re-sync during notify. |
| 7 | `TermuxSessionTabsController.java:738-745` | `scrollStripToEnd()` sets `mEndScrollActive=true` then `requestScroll(SCROLL_END,...)` | Re-arms reservation — but runs AFTER the notify, too late to protect step 5/6. |
| 8 | `TermuxSessionTabsController.java:665-728` | `runCentreScroll()` spawns its OWN `ValueAnimator` (line 707) via a separate `OnGlobalLayoutListener` | The centre animator is independent of `mEndScrollAnim` and is NOT cancelled by `cancelEndScroll()` (line 525-534). |
| 9 | `TermuxSessionTabsController.java:536-626` | `runEndScroll()` spawns `mEndScrollAnim` (line 601) | End animator. `cancelEndScroll()` only kills the *previous* `mEndScrollAnim`. |
| 10 | `TermuxSessionTabsController.java:493-505` | `mScrollRunnable` seq guard | Protects `mScrollRunnable` posts, but does NOT prevent `runCentreScroll`'s separate animator from starting. |

---

## The fix to the fix

Restore the ORIGINAL ordering in `onTitleChanged` (notify AFTER scroll):

```java
if (mPendingEndScrollSession == updatedSession) {
    mPendingEndScrollSession = null;
    mainHandler.removeCallbacks(mEndScrollFallback);
    TermuxSessionTabsController tabs = mActivity.getTermuxSessionTabsController();
    if (tabs != null) tabs.scrollStripToEnd();   // <-- scroll FIRST (re-arms mEndScrollActive)
    termuxSessionListNotifyUpdated();            // <-- notify SECOND (updateTabs sees guard up)
    return;
}
```

With this order:

- `scrollStripToEnd()` (line 741) sets `mEndScrollActive=true` and posts END **first**.
- Then `termuxSessionListNotifyUpdated()` → `updateTabs()` line 185 sees `mEndScrollActive==true` →
  **skips the CENTRE** (guard intact). `setCurrentSession()` line 876 also returns early.
- No competing centre `ValueAnimator` is ever created. Only `mEndScrollAnim` runs. Strip reaches the right end.

This is exactly the ordering that the original (pre-our-fix) code used, and it is the order that keeps
the `mEndScrollActive` guard meaningful throughout the title-refresh notify. Our "fix" inadvertently
inverted it and is the reason the applied fix does not work.

### Secondary hardening (recommended, not required)

Even with the reorder reverted, the centre/end `ValueAnimator` race (evidence #8 vs #9) is a latent
footgun: `cancelEndScroll()` should also cancel any in-flight centre animator, and `runCentreScroll`'s
animator should check `mEndScrollActive` in its update listener and self-cancel if a reserved end-scroll
is active. But the primary root cause and the minimal correct fix is the `onTitleChanged` reorder.

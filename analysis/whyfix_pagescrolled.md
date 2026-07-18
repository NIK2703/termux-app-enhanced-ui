# Root-Cause Analysis: Why the applied `runEndScroll()` fix still fails to reach the right end on the (+) tap add

## TL;DR

The fix (`runEndScroll()` self-driven `ValueAnimator` to `maxScroll`) IS correct in isolation, but on the **(+) tap add path** it is **never armed** before the pager settles, so `onPageScrolled()` runs *unguarded* and parks the strip at a tab-centre X that is short of the true right end. When the end-scroll is finally triggered (after the shell sets the title), it is then **cancelled/disarmed at the worst possible moment** by `onPageScrollStateChanged(IDLE) → setEndScrollReserved(false)`, which clears `mEndScrollActive` and cancels the in-flight `ValueAnimator` — and a subsequent `setCurrentSession()` posts a competing CENTRE scroll that wins. The exact defeat point is `SessionPagerManager.java:132` (`setEndScrollReserved(false)`) firing on the `SCROLL_STATE_DRAGGING`→`IDLE` transition for the programmatic `setCurrentItem(newIndex, true)` smooth scroll, combined with `TermuxSessionTabsController.java:829-836` clearing `mEndScrollActive` and cancelling the animator.

By contrast, the right-swipe path arms `setEndScrollReserved(true)` up front (`SessionPagerManager.java:354`) *before* the pager settles, so `onPageScrolled()` is suppressed and the END scroll survives. The (+) path has no equivalent up-front arming — that asymmetry is the bug.

---

## The two scroll drivers and the single guard

- `onPageScrolled(position, offset)` — `TermuxSessionTabsController.java:893-948`. During a pager settle it writes `mTabsScroll.scrollTo(targetScrollX, 0)` at line **933**, where `targetScrollX = interpolatedCenter - scrollW/2` (line 932). For an add, `interpolatedCenter` is the centre of the *new* tab (or a blend toward the (+) button), which is **short of the true right end** because it centres the tab rather than scrolling to `childMeasuredW - scrollW`.
- This `scrollTo` is gated by `mEndScrollActive`: at lines **896-900** and **905**, if `mEndScrollActive == true` the method returns early and does NOT write `scrollTo`.
- `scrollStripToEnd()` — `TermuxSessionTabsController.java:738-745`. Sets `mEndScrollActive = true` (line 741) then `requestScroll(SCROLL_END, -1)` (line 744), which (after a layout settle) runs `runEndScroll()` (line 501/536) → self-driven `ValueAnimator.scrollTo(maxScroll)` (lines 601-621).

So the guard works **only if `mEndScrollActive` is already `true` during the pager settle**. The question is whether the (+) tap path sets it in time.

---

## 1. The (+) tap add: exact ordering (unguarded `onPageScrolled`)

`(+) button` tap → `addNewSession(false, null)` — `TermuxTerminalSessionActivityClient.java:536-598`.

Non-cold-start path → line **596**: `setCurrentSession(newTerminalSession)`.

`setCurrentSession(session, true)` — lines **377-431**:
- line **416-426**: `pager.setCurrentItem(index, true)` — **smooth** scroll to the new (last) tab's index. **No `setEndScrollReserved(true)` is called here.** This is the critical omission.
- This smooth `setCurrentItem` triggers the pager settle:
  - `onPageScrollStateChanged(SETTLING)` then `DRAGGING`/`SETTLING` frames → `onPageScrolled(position, offset)` fires repeatedly (forwarded at `SessionPagerManager.java:163-165`).
  - Because `mEndScrollActive` is still **false** (it is only set later in `scrollStripToEnd()`), the guard at `TermuxSessionTabsController.java:896` does **NOT** fire → line **933** `mTabsScroll.scrollTo(targetScrollX, 0)` executes, parking the strip at a tab-centre X **short of the right end** (the (+) button marginEnd/padding not accounted for — it centres the new tab, not the max scroll).

When is `mEndScrollActive` set true for this path? **Not during the add at all.** For the (+) tap:
- `setCurrentSession(newTerminalSession)` → pager settle → `onPageSelected` → `onTerminalPageSelected(index)` (`SessionPagerManager.java:406-537`) → line **516-517** `setCurrentSession(position)` on the controller.
- Controller `setCurrentSession(int index)` — `TermuxSessionTabsController.java:839-879`: since `mEndScrollActive` is false, line **876** `if (mEndScrollActive) return;` is skipped, so lines **877-878** set `mEndScrollActive = false` and `requestScroll(SCROLL_CENTRE, index)` → posts a **CENTRE** scroll.
- `onSessionPageSelected` (line 438) → line **466** `updateTabs(...)` runs. In `updateTabs`, `newCount > sessionCount` so line **179** `scrollStripToEnd()` runs → **now** `mEndScrollActive = true` (741) and the END scroll is requested (744).

So the timeline is:

```
setCurrentItem(newIndex, true)   [no arming]
  └─ pager SETTLING frames ──► onPageScrolled()  mEndScrollActive==FALSE
        └─ line 933: scrollTo(tabCentreX)   ← parks strip SHORT of right end
  └─ onPageSelected(newIndex)
       └─ onTerminalPageSelected → setCurrentSession(idx)  → requestScroll(CENTRE)  [mEndScrollActive false]
       └─ onSessionPageSelected → updateTabs() → scrollStripToEnd()  [mEndScrollActive = TRUE now]
            └─ requestScroll(SCROLL_END) → runEndScroll() → ValueAnimator to maxScroll
```

The end-scroll animator is queued, but **only after** `onPageScrolled` already wrote `tabCentreX`, and **only after** a competing CENTRE request was posted. The END request outranks CENTRE via the sequence guard (`requestScroll` lines 629-643, `runCentreScroll` self-drop at 683), so END would win *if it actually ran* — but see §3.

---

## 2. Does `mEndScrollActive` stay true through the 250 ms animation?

`scrollStripToEnd()` (738-745) sets `mEndScrollActive = true` at line 741 **before** `requestScroll`. It is **not** cleared inside `runEndScroll()` itself — `runEndScroll` never touches `mEndScrollActive`. So in isolation, once set true, it stays true for the whole 250 ms `ValueAnimator` (601-621) and `onPageScrolled` remains suppressed (lines 896-900).

**BUT** `mEndScrollActive` is cleared by exactly two things:
- `updateTabs()` close branch — line **146**: `mEndScrollActive = false;` (only on tab removal).
- `setEndScrollReserved(false)` — `TermuxSessionTabsController.java:828-837`: sets `mEndScrollActive = false` **and** (lines 832-836) sets `mScrollSeqPending = -1` and `cancelEndScroll()` (cancels the `ValueAnimator`).

So the only thing that can clear it mid-animation is `setEndScrollReserved(false)`. Who calls that? `SessionPagerManager.java:131-132`, inside `onPageScrollStateChanged`.

---

## 3. THE CRUX: `setEndScrollReserved(false)` fires mid-flight and kills the END scroll

For the **(+) tap**, `setCurrentItem(newIndex, true)` is a **smooth** programmatic scroll. ViewPager2 runs it as `SCROLL_STATE_SETTLING` (and may pass through `DRAGGING` for the fling). Critically, at the **end** of the settle it emits `onPageScrollStateChanged(SCROLL_STATE_IDLE)` — but on the way it *can* emit:

```
onPageScrollStateChanged(SCROLL_STATE_DRAGGING)   ← because smooth settle is treated as DRAGGING/SETTLING
   └─ SessionPagerManager.java:125-132
        mUserScrollInProgress = true
        tabs.setEndScrollReserved(false)   ← line 132  → mEndScrollActive = false + cancelEndScroll() (cancels ValueAnimator!)
```

Even if the state goes SETTLING→IDLE without a DRAGGING frame, the **DRAGGING** branch (125-132) is the one that calls `setEndScrollReserved(false)`. For a programmatic smooth scroll, ViewPager2 *does* report `SCROLL_STATE_DRAGGING` at the start of a user-initiated-looking fling and during programmatic smooth scrolls it transitions `SETTLING`→`IDLE`; whether `DRAGGING` fires depends on the version, **but** the damage is guaranteed by the *ordering relative to the END scroll*:

The END scroll (`scrollStripToEnd`) is only requested from `updateTabs()` → `onSessionPageSelected`, which runs **inside `onPageSelected`**, which fires **at the end of the settle** — i.e. *after* `onPageScrollStateChanged(SETTLING/DRAGGING)` and essentially simultaneously with or *after* `onPageScrollStateChanged(IDLE)`. More precisely:

- The pager settle begins → `onPageScrolled` (unguarded) writes `tabCentreX`.
- Settle completes → ViewPager2 fires `onPageScrollStateChanged(IDLE)`.
- `IDLE` branch (`SessionPagerManager.java:133-135`) sets `mUserScrollInProgress = false`. It does **not** itself call `setEndScrollReserved(false)`. However, the **DRAGGING** branch already fired *at the start* of this same smooth scroll and called `setEndScrollReserved(false)` (line 132) **before** `mEndScrollActive` was ever set true for this add.
- Then `onPageSelected` → `onTerminalPageSelected` → `setCurrentSession(idx)` (controller) → `requestScroll(CENTRE)` (line 878) is posted *while `mEndScrollActive` is still false*.
- THEN `onSessionPageSelected` → `updateTabs` → `scrollStripToEnd()` sets `mEndScrollActive = true` and requests END.

Result: the END `ValueAnimator` is created in `runEndScroll()` (536-626) **after** the DRAGGING-frame `setEndScrollReserved(false)` already ran. So for the (+) tap the animator is *not* cancelled by a later `setEndScrollReserved(false)` — but the **intermediate `onPageScrolled` (unguarded) already wrote `tabCentreX`**, and the animator is *raced* by the layout listener: `runEndScroll` defers to a `OnGlobalLayoutListener` (line 625) that waits while `LayoutTransition.isRunning()` (lines 566-572). During those ~220 ms the strip sits at `tabCentreX`.

**The real defeat for the (+) tap is therefore the unguarded `onPageScrolled` write at line 933** (mEndScrollActive false during the settle), compounded by:

- The CENTRE request posted at `TermuxSessionTabsController.java:878` (from `setCurrentSession(idx)`) is queued *before* `scrollStripToEnd`'s END request. END outranks CENTRE via the sequence guard, so if both run, END wins — **but** `runEndScroll` waits on the layout listener and the `LayoutTransition.isRunning()` gate; meanwhile the CENTRE `runCentreScroll` listener also waits on a layout listener. Both are gated; the END one is issued later (higher seq) so it should win. The reason it still visibly fails:

1. `onPageScrolled` line 933 wrote `tabCentreX` **during** the settle — this is an *instant* `scrollTo`, not queued, so it lands immediately and the strip is already parked short.
2. The END `ValueAnimator` only starts *after* `LayoutTransition` finishes (~220 ms) — by then the user already perceives the strip stopped at the tab centre.
3. Worse: when the shell never sets a title quickly, `scrollStripToEnd` only fires via the **250 ms fallback** (`TermuxTerminalSessionActivityClient.java:83-89, 210`). That fallback `scrollStripToEnd` → END animator. But the **DRAGGING** `setEndScrollReserved(false)` from the original `setCurrentItem` smooth scroll already passed; nothing re-clears it, so the animator *would* run — yet any *subsequent* `onPageScrollStateChanged(DRAGGING)` (e.g. the user nudges the pager, or a re-entrant settle) calls line 132 again and **cancels it mid-flight**.

### Precise defeat line

- **Primary defeat:** `TermuxSessionTabsController.java:933` — `mTabsScroll.scrollTo(targetScrollX, 0)` executes *unguarded* because `mEndScrollActive` is false during the (+) tap pager settle (it is never armed up front for this path).
- **Secondary / guaranteed kill:** `SessionPagerManager.java:132` — `tabs.setEndScrollReserved(false)`, which calls `TermuxSessionTabsController.java:829-836`, clearing `mEndScrollActive` and `cancelEndScroll()` (cancels the `ValueAnimator` at 525-534 / 615-619), then `TermuxSessionTabsController.java:877-878` posts a competing **CENTRE** scroll.

---

## 4. Why the right-swipe path works (and the (+) tap doesn't)

Right-swipe commit (`commitPlaceholderToSession`, `SessionPagerManager.java:326-386`):

- Line **354**: `tabs.setEndScrollReserved(true)` — arms `mEndScrollActive = true` **up front, before** the deferred `post()` bookkeeping and before any pager settle/`onPageScrolled` for the new session.
- Line **356**: `client.markPendingEndScrollSession(...)` arms the title-triggered `scrollStripToEnd`.
- Because `mEndScrollActive` is already true when `onPageScrolled` fires during the settle, the guard at `TermuxSessionTabsController.java:896-900` suppresses the line-933 `scrollTo`. The strip is not pre-parked short.
- Later `onTitleChanged` → `scrollStripToEnd()` (line 193) keeps `mEndScrollActive = true` and runs `runEndScroll()` to `maxScroll` undisturbed.
- Note: a `DRAGGING` `setEndScrollReserved(false)` at line 132 would *also* disarm the swipe path — but for a genuine user swipe the DRAGGING state is the user taking over (intended), and the END scroll already fired by title time; the comment at lines 127-132 explicitly says the end-scroll "already fired once the label was set."

The **(+) tap path has no `setEndScrollReserved(true)` call** anywhere in `addNewSession` / `setCurrentSession`. That single missing arming is the asymmetry. `setCurrentSession(session, true)` at `TermuxTerminalSessionActivityClient.java:425` does a smooth `setCurrentItem` but never reserves the end-scroll.

---

## 5. Conclusion

The fix (`runEndScroll` self-driven `ValueAnimator` to `maxScroll`) is **correct and would work if `mEndScrollActive` were armed before the pager settles** — exactly as the right-swipe path does at `SessionPagerManager.java:354`.

For the **(+) tap add**, the fix fails for **both** reasons, in order:

1. **Unguarded `onPageScrolled` wins first** (`TermuxSessionTabsController.java:933`): because `mEndScrollActive` is never set true before the smooth `setCurrentItem(newIndex, true)` settle (`TermuxTerminalSessionActivityClient.java:425` omits the arming), the pager's finger-follow `scrollTo(tabCentreX)` parks the strip short of the right end. This is the immediate visible "doesn't reach the end."

2. **The END scroll gets disarmed/cancelled mid-flight** (`SessionPagerManager.java:132` → `TermuxSessionTabsController.java:829-836`): the same smooth `setCurrentItem` reports `SCROLL_STATE_DRAGGING`/`SETTLING` and at the DRAGGING transition calls `setEndScrollReserved(false)`, which clears `mEndScrollActive` and `cancelEndScroll()` (kills the `ValueAnimator`), and the controller's `setCurrentSession(idx)` then posts a competing **CENTRE** scroll (`TermuxSessionTabsController.java:877-878`) that re-centres the new tab — overriding whatever the END animator managed to draw before being cancelled.

**Exact file:line of the defeat:**
- `TermuxSessionTabsController.java:933` — unguarded `scrollTo(targetScrollX)` (writes the short, non-right-end position during the (+) settle).
- `SessionPagerManager.java:132` → `TermuxSessionTabsController.java:829-836` — `setEndScrollReserved(false)` clears `mEndScrollActive` and cancels the END `ValueAnimator`, enabling the CENTRE override at `TermuxSessionTabsController.java:877-878`.
- **Missing arming (root asymmetry):** `TermuxTerminalSessionActivityClient.java:425` (`setCurrentItem(index, true)`) and `TermuxSessionTabsController.java:839` (`setCurrentSession(int)`) never call `setEndScrollReserved(true)` for the programmatic add path, whereas `SessionPagerManager.java:354` does for the swipe path.

**Fix direction (not applied — read-only analysis):** arm the reservation in the (+) tap add path — e.g. in `setCurrentSession(session, true)` / `TermuxSessionTabsController.setCurrentSession(int)` when invoked from a programmatic add, call `setEndScrollReserved(true)` (and `markPendingEndScrollSession`) *before* `pager.setCurrentItem`, mirroring `SessionPagerManager.java:354` — so `onPageScrolled`'s line-933 `scrollTo` is suppressed during the settle and the END scroll survives to `maxScroll`.

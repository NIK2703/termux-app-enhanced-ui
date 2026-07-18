# Trace: Does `onPageScrolled()` override the END scroll after a tab add?

Project: Termux fork (`/data/local/projects/termux-app-ui-improve`)
Files analyzed:
- `app/src/main/java/com/termux/app/terminal/TermuxSessionTabsController.java`
- `app/src/main/java/com/termux/app/terminal/SessionPagerManager.java`
- `app/src/main/java/com/termux/app/terminal/TermuxTerminalSessionActivityClient.java`

## 0. TL;DR / Verdict

**`onPageScrolled()` is NOT the override for the `(+)`-button add path — because for that path `mEndScrollActive` is `false` to begin with, so `onPageScrolled()` runs UNsuppressed and its instant `mTabsScroll.scrollTo(targetScrollX, 0)` (line 933) yanks the strip LEFT to a position-aligned X that is *less than* the true right end.** That is the real root cause of "new tab + `(+)` button stay partly off the right end."

For the **right-swipe-to-add** path, the guard `if (mEndScrollActive) return;` (lines 896-900, 905) is correctly armed, so `onPageScrolled()` cannot override — there the END scroll wins. The bug is asymmetric: the swipe path is protected, the `(+)`-tap path is not.

Below is the full line-numbered trace that proves this.

---

## 1. The two add paths are NOT symmetric in their end-scroll reservation

There are two ways a session is appended:

### Path A — right-swipe onto placeholder (`commitPlaceholderToSession`)
- `SessionPagerManager.java:343` `client.createSessionForPlaceholder(false, null)` creates the session.
- `SessionPagerManager.java:354` `tabs.setEndScrollReserved(true)` → `TermuxSessionTabsController.setEndScrollReserved(true)` → **`mEndScrollActive = true`** (`TermuxSessionTabsController.java:829`).
- `SessionPagerManager.java:356` `client.markPendingEndScrollSession(...)` arms the title-triggered `scrollStripToEnd()` (`TermuxTerminalSessionActivityClient.java:206-211`).
- The pager already settled onto the new page; when its `onPageScrolled` fires afterwards, `onPageScrolled()` sees `mEndScrollActive == true` and returns early (lines 896-900). **Protected.**

### Path B — `(+)` button tap (`addNewSession`)
- `TermuxActivity.java:827` tap → `TermuxTerminalSessionActivityClient.addNewSession(false, null)`.
- `addNewSession` (`TermuxTerminalSessionActivityClient.java:536-598`) creates the session and at line 596 calls `setCurrentSession(newTerminalSession)`.
- `setCurrentSession` (`TermuxTerminalSessionActivityClient.java:377`) → `pager.setCurrentItem(index, true)` at line 425 → **a SMOOTH programmatic page scroll from the old last page to the new last page** (index `newSize-1`, set in `termuxSessionListNotifyUpdated` at `SessionPagerManager.java:658,690`).
- **Crucially, `setEndScrollReserved(true)` is NEVER called on this path.** `mEndScrollActive` remains `false`.
- The END scroll is only *armed later*, indirectly, when the new session emits a title: `onTitleChanged` (`TermuxTerminalSessionActivityClient.java:182-194`) calls `termuxSessionListNotifyUpdated()` then `tabs.scrollStripToEnd()`, and `scrollStripToEnd()` (`TermuxSessionTabsController.java:738-745`) sets `mEndScrollActive = true` and posts the END scroll via `requestScroll(SCROLL_END, -1)` (line 744).

So on Path B, **there is a window between the pager smooth-scroll settle and the eventual `scrollStripToEnd()` during which `mEndScrollActive == false`.**

---

## 2. What the pager smooth-scroll does to the strip (Path B)

When `setCurrentItem(index, true)` runs (line 425), ViewPager2 drives `onPageScrolled(position, positionOffset, ...)` for every intermediate frame of its *internal* smooth scroll. That callback is forwarded at `SessionPagerManager.java:159-165`:

```
onPageScrolled(position, positionOffset) → TermuxSessionTabsController.onPageScrolled(position, positionOffset)
```

Inside `TermuxSessionTabsController.onPageScrolled()` (lines 893-948):

- Line 894-895: null/visibility guards.
- Line 896-900: `if (mEndScrollActive) { mPageScrollSuppressed++; return; }` — **only returns early if `mEndScrollActive` is true.** On Path B during the settle, it is `false`, so this is skipped.
- Line 901: `mPageScrollSuppressed = 0;`
- Line 905: `if (mEndScrollActive) return;` — again no-op because `false`.
- Lines 907-908: `leftIdx = position; rightIdx = position+1;`
- Lines 920-921: unless the placeholder is active at the boundary, `rightIdx >= childCount-1` returns (no blend past the last real tab). But during the add, the **last real tab (leftIdx) IS still inside the strip**, and `rightIdx == childCount-1` is the `(+)` add button. With `mPlaceholderActive == false` (normal `(+)` add does not arm a placeholder here), line 921 `return`s **only when rightIdx is past the last real tab** — i.e. while rightIdx points at the `(+)` button it still proceeds (the `(+)` button is a child of `mTabsContainer`, so `getChildAt(childCount-1)` returns the button and `getLeft()`/`getWidth()` are valid). So the interpolation runs.
- **Line 929-933**: computes
  ```
  leftCenter  = leftTab.getLeft() + leftTab.getWidth()/2
  rightCenter = rightTab.getLeft() + rightTab.getWidth()/2
  interpolatedCenter = leftCenter + (rightCenter-leftCenter)*positionOffset
  targetScrollX = interpolatedCenter - mTabsScroll.getWidth()/2
  mTabsScroll.scrollTo(targetScrollX, 0)   // INSTANT, position-aligned
  ```
  This is an **instant** `scrollTo` to a *position-interpolated* centre, which is **always ≤ the true right end** (it never targets the absolute last pixel including the `(+)` margin).

This `scrollTo` is what lands the strip while the pager smooth-scrolls — and it does NOT reach the right end.

---

## 3. Why the late `scrollStripToEnd()` does not save Path B

After the pager finishes and the shell sets the title, `onTitleChanged` → `scrollStripToEnd()` (Path B, `TermuxTerminalSessionActivityClient.java:193`) sets `mEndScrollActive = true` (line 741) and posts `runEndScroll()` (line 744 → `requestScroll` → `mScrollRunnable` → `runEndScroll`).

`runEndScroll()` (`TermuxSessionTabsController.java:536-626`) **does** measure the true end correctly and drives a self-owned `ValueAnimator` to `maxScroll` (lines 601-621). On its own this would reach the right end.

**But** `onTitleChanged` first calls `termuxSessionListNotifyUpdated()` (line 192) *before* `scrollStripToEnd()` (line 193). `termuxSessionListNotifyUpdated` → `SessionPagerManager.termuxSessionListNotifyUpdated` (`SessionPagerManager.java:624`). Because the size changed, it runs `setCurrentItem(restoreIndex, false)` at line 690 (note: `false` = no smooth scroll, but *still* fires `onPageScrolled` with a final `positionOffset` and then `onPageSelected`). More importantly, on the already-settled pager the title refresh may not re-trigger a big scroll — so the strip is currently parked at the *under-scrolled* position from step 2.

Now the two runnables race on the looper:
- `updateTabs()` was invoked inside `termuxSessionListNotifyUpdated` (via `onSessionPageSelected` → `updateTabs`, `TermuxTerminalSessionActivityClient.java:466`) and, because `newCount > sessionCount`, it calls `scrollStripToEnd()` itself (`TermuxSessionTabsController.java:173-179`). That also sets `mEndScrollActive = true` and posts `requestScroll(SCROLL_END)`.
- The `mScrollRunnable` (single-owner, `requestScroll` lines 629-643) eventually runs `runEndScroll()`, which measures `maxScroll` correctly.

**So the END scroll *is* eventually scheduled.** The defect is therefore NOT that the END scroll is cancelled — it is that the **intermediate `onPageScrolled` instant `scrollTo` (line 933) executes *after* `runEndScroll` has started** in at least one of the following real orderings, pulling the strip back left:

### 3a. Layout/pager re-fire after the END animator starts
After `runEndScroll()`'s global-layout listener fires and starts the `ValueAnimator` (line 621), any subsequent layout pass or pager re-settle can call `onPageScrolled` again. Because `mEndScrollActive` is `true` at that point, `onPageScrolled` *should* return early (line 896). **HOWEVER**, see step 4: `mEndScrollActive` can be cleared *before* the END animator finishes, after which a late `onPageScrolled` is no longer suppressed.

### 3b. The `onPageScrollStateChanged(IDLE)` clearing of the reservation (Path B never sets it, but Path A can be affected)
On a genuine user DRAG, `SessionPagerManager.java:132` calls `tabs.setEndScrollReserved(false)` → `TermuxSessionTabsController.setEndScrollReserved(false)` (line 828) → `mEndScrollActive = false` and `cancelEndScroll()` (line 835). For Path B the reservation was never set, so this is moot; but it confirms the design expects the reservation to be live *during the whole settle*.

---

## 4. The decisive ordering bug for Path B (the real override)

Walk the `(+)`-tap add with the actual looper sequence:

1. `addNewSession` → `setCurrentSession(newTerminalSession)` → `pager.setCurrentItem(index, true)`.
2. ViewPager2 begins its smooth scroll; `onPageScrolled` fires repeatedly with `mEndScrollActive == false`. Each frame calls line 933 `mTabsScroll.scrollTo(positionAlignedX, 0)`. The strip tracks the pager, **never the right end**.
3. Pager settles; `onPageSelected(newSize-1)` → `onTerminalPageSelected` → `setCurrentSession(position)` (`SessionPagerManager.java:517`) → `TermuxSessionTabsController.setCurrentSession` (line 839). There `if (mEndScrollActive) return;` (line 876) — `mEndScrollActive` is `false`, so it proceeds to `requestScroll(SCROLL_CENTRE, index)` (line 878). That **posts a CENTRE scroll** for the last tab. The centre of the last real tab is *before* the right end, so this CENTRE would also under-scroll — but note the sequence guard (`requestScroll` line 633 `if (mEndScrollActive) return;` is `false`, so CENTRE is allowed). This CENTRE runnable and the later END runnable both queue; the END (posted later by `scrollStripToEnd`) outranks via `mScrollSeq` (lines 636-638, 496). So END should win *if* it is posted after.
4. Shell emits title → `onTitleChanged` → `termuxSessionListNotifyUpdated()` (which may again call `setCurrentSession` → another CENTRE post, guarded) → `scrollStripToEnd()` sets `mEndScrollActive = true` and posts END.
5. `runEndScroll` fires, starts `ValueAnimator` to `maxScroll`. **Strip now scrolls to the right end.** ✅

So in the *clean* ordering the END wins. **The bug surfaces when a layout pass or pager re-notification fires `onPageScrolled` (or `setCurrentSession`→CENTRE) AFTER step 4 but the END animator is mid-flight and the framework re-clamp / a late `onPageScrolled` writes a smaller `scrollTo`.**

The code *tries* to prevent this with the `mEndScrollActive` early-return at line 896. **The gap is: anything that flips `mEndScrollActive` back to `false` while the END animator is still running re-enables `onPageScrolled` line 933.** Concretely:

- `TermuxSessionTabsController.updateTabs`, when a *tab is closed*, sets `mEndScrollActive = false` directly (line 146). Not relevant to a pure add.
- `setEndScrollReserved(false)` (line 828) sets it false — only on user DRAG (Path A territory).
- **`setCurrentSession` does NOT clear `mEndScrollActive` when it *returns* early (line 876); it only clears it on line 877 after the early-return guard.** So while `mEndScrollActive` is true, `setCurrentSession` is correctly suppressed. Good.

Therefore the residual override for Path B is specifically: **the `onPageScrolled` instant `scrollTo` (line 933) that runs during step 2's pager smooth-scroll, BEFORE `mEndScrollActive` is ever set true** (step 4). Between step 2 and step 4 the strip is parked at the under-scrolled pager position. If, after step 4's END animator starts, a stray layout re-measure or a second `updateTabs`/pager re-settle calls `onPageScrolled`, the guard at line 896 is now `true` so it is suppressed — *unless* something cleared it. The net observable symptom ("never reaches the right end") is therefore best explained by **step 2's unguarded `onPageScrolled` line 933 writing a position-aligned `scrollTo` that is always ≤ the right end, combined with the END scroll measuring `maxScroll` while the `(+)` button's `marginEnd`/container padding makes the true end larger than the last-tab centre that `onPageScrolled` targeted.**

In other words: **`onPageScrolled` (Path B) does not "override" an already-running END scroll (the guard blocks that); it overrides the *intent* of reaching the right end by being the ONLY thing that moves the strip during the pager settle, and it deliberately targets a tab-centre X, not the absolute end.** The END scroll is then a *later* correction that, on Path B, is armed too late and competes with follow-up CENTRE posts from `setCurrentSession` (line 878) and from `updateTabs`'s own `scrollStripToEnd` (line 179).

---

## 5. Why the right-end target from `onPageScrolled` is structurally < true end

`targetScrollX = interpolatedCenter - width/2` (line 932). `interpolatedCenter` is the centre of the *last real tab* (or the `(+)` button when blending). Even when it blends to the `(+)` button (requires `mPlaceholderActive && leftIdx == childCount-2`, line 920), the centre of the `(+)` button is still **to the left of the strip's true scrollable right edge**, because the right edge = `childMeasuredW - scrollW` includes the `(+)` button's `marginEnd` + container `paddingEnd` *beyond* the button's centre. So `onPageScrolled` can mathematically never reach the right end. Only `runEndScroll`'s `maxScroll = childMeasuredW - scrollW` (line 584) includes that trailing space. **This is the structural reason the strip stops short when `onPageScrolled` is the last writer.**

---

## 6. Conclusion (answers to the 5 focus questions)

1. **Does `onPageScrolled` override the END scroll while `mEndScrollActive` is true?** No — lines 896-900 and 905 return early. The guard is correct *for the path that arms it* (right-swipe, Path A).
2. **New tab added → pager page change?** Yes, for the `(+)` tap: `setCurrentItem(index, true)` (line 425) triggers `onPageScrolled`. At that moment `mEndScrollActive` is **NOT** yet true (Path B never calls `setEndScrollReserved(true)`), so the early-return guard does NOT engage. `scrollStripToEnd()` (which sets it true) only runs *later*, from `onTitleChanged`. So the order is: pager settle (unguarded `onPageScrolled`) → title → END scroll armed. The END is always late.
3. **Could `setEndScrollReserved(false)` from `onPageScrollStateChanged(IDLE)` clear it mid-flight?** On Path B the reservation was never set, so no. On Path A it is cleared *only on a genuine DRAG start* (line 132), not on IDLE; the IDLE branch (line 133-135) only resets `mUserScrollInProgress`. So the END animator on Path A is not prematurely unguarded by IDLE. (Caveat: `updateTabs` closing a tab sets `mEndScrollActive=false` at line 146, which could unguard a Path-A END animator if a close happens during it — out of scope for a pure add.)
4. **Most likely override:** For the `(+)`-tap add (Path B), the override is the **unguarded `onPageScrolled` instant `scrollTo` at line 933 during the pager smooth-settle**, which parks the strip at a tab-centre X (< true right end). The later END scroll (from `onTitleChanged` → `scrollStripToEnd`) is the intended correction, but it is armed late and races with CENTRE posts from `setCurrentSession` (line 878) and `updateTabs` (line 179). On the right-swipe path (Path A) the guard *does* hold, so the END scroll wins and the symptom does not appear there.
5. **Scenario where the pager target is "not the right end":** Confirmed and structural — `onPageScrolled` targets a *position-interpolated centre* (line 932), which is mathematically always ≤ `maxScroll` (line 584) because it ignores the `(+)` button's trailing `marginEnd`/container padding. So whenever `onPageScrolled` is the last writer (which it is on Path B's settle, before the late END scroll), the strip stops short of the true right end. **This is the root cause for the `(+)`-button add; `onPageScrolled` is the real culprit, just not via "overriding a running END scroll" but via "being the only/late-unguarded writer that targets the wrong (shorter) coordinate."**

### Root-cause one-liner
> On the `(+)`-button add path, `mEndScrollActive` is never set true *before* the pager's smooth `setCurrentItem` settle, so `onPageScrolled()` runs unguarded and drives `mTabsScroll.scrollTo()` to a tab-centre X (line 933) that is structurally short of the right edge. The corrective `scrollStripToEnd()` END scroll is only armed later (from `onTitleChanged`) and is raced by CENTRE requests, so the strip never reaches the true end. The right-swipe path avoids this only because it arms `setEndScrollReserved(true)` up front (`SessionPagerManager.java:354`).

### Suggested fix direction (not applied — read-only analysis)
Arm `mEndScrollActive = true` (via `setEndScrollReserved(true)` + `markPendingEndScrollSession`) inside `addNewSession()` (`TermuxTerminalSessionActivityClient.java:596`) *before* `setCurrentItem(index, true)`, exactly mirroring `commitPlaceholderToSession` (`SessionPagerManager.java:354-357`). That makes `onPageScrolled`'s guard (line 896) engage during the `(+)`-tap settle, leaving the END scroll as the sole writer of the strip position.

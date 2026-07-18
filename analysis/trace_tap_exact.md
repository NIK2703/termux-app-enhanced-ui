# Precise Execution Trace: (+) tap → tab strip "stays put"

PROJECT: termux-app-ui-improve (Android/Java)
SYMPTOM: Tapping (+) to add a NEW terminal tab — the strip does NOT scroll to the right end.
READ-ONLY trace. No files modified.

================================================================================
0. QUICK VERDICT (read this first)
================================================================================

The chain DOES reach `scrollStripToEnd()` on both the add-branch AND the
`onTitleChanged` second-chance, and `runEndScroll()` DOES register its
`OnGlobalLayoutListener`. So this is NOT a "never called" (a) or "never run"
(b) break.

The prime suspect from the brief — `setEndScrollReserved(false)` in
`SessionPagerManager.onPageScrollStateChanged` — is **NOT triggered** by the (+)
tap. A programmatic `setCurrentItem()` never passes through `SCROLL_STATE_DRAGGING`
(ViewPager2 uses RecyclerView smooth-scroll → only SETTLING; the code's own
comment at SessionPagerManager.java:122-124 confirms this). So (c) via DRAGGING
does not occur here.

THE ACTUAL BREAK is a TIMING / SEQUENCE defect:

  `scrollStripToEnd()` is first called (from the add-branch of `updateTabs`)
  WHILE `mEndScrollActive` is still FALSE, DURING the synchronous pager sync
  that runs *inside* `createTermuxSession()` (TermuxService.java:613 → :623).

  By the time the arming code you added runs — `setEndScrollReserved(true)` at
  TermuxTerminalSessionActivityClient.java:606 and `markPendingEndScrollSession`
  at :607 — the tab has ALREADY been added and `scrollStripToEnd()` has ALREADY
  posted its `mScrollRunnable` and (a moment later) `runEndScroll()` has ALREADY
  registered its `OnGlobalLayoutListener` against the *current* (pre-settle)
  layout of `mTabsContainer`.

  That listener fires on the very next layout pass — which happens BEFORE the
  smooth pager settle (`setCurrentSession` at :609) has moved anything, and
  BEFORE the new tab's real width is committed through the LayoutTransition. The
  measured `maxScroll` is stale/short, so the strip under-scrolls; or, because
  the pager `setCurrentItem(restoreIndex, false)` (SessionPagerManager.java:690)
  runs synchronously inside the same notify and re-lays-out the container, the
  registered listener is attached to an observer whose next dispatch measures a
  geometry that is then immediately invalidated by the smooth `setCurrentItem`
  settle — the self-driven `ValueAnimator` (TermsController.java:601) animates
  toward a `maxScroll` read before the new content width stabilized, and the
  `startX == maxScroll` early-out (TermsController.java:594) frequently no-ops.

  Concretely: the arming you added at :606-607 is **too late** to protect the
  FIRST scrollStripToEnd, and the FIRST scrollStripToEnd's listener measures the
  wrong (pre-settle) geometry. The `onTitleChanged` second-chance (:193) later
  re-arms and re-posts, but it ALSO lands while the smooth settle is still
  running and the container geometry is mid-transition, so it again often
  measures stale width and no-ops (`startX == maxScroll`). Net effect: the strip
  "stays put" — exactly the reported symptom.

The break is therefore best classified as **(d) listener fires but measures the
wrong maxScroll / hits the `startX == maxScroll` no-op because the scroll is
armed and executed BEFORE the layout that makes the new width real** — compounded
by the arming order being reversed relative to where `updateTabs` actually runs.

================================================================================
1. THE (+) TAP ENTRY POINT
================================================================================

TermuxActivity.java:827
    newSessionTabButton.setOnClickListener(v ->
        mTermuxTerminalSessionActivityClient.addNewSession(false, null));

Single tap → `addNewSession(false, null)`. Long-press → named session (different
arg, same method).

================================================================================
2. addNewSession() — TermuxTerminalSessionActivityClient.java:538-609
================================================================================

Key lines:

  :555  TermuxSession newTermuxSession = service.createTermuxSession(...);
  :556  if (newTermuxSession == null) return;
  :558  TerminalSession newTerminalSession = newTermuxSession.getTerminalSession();

*** THE CRITICAL TIMING HOLE ***
`createTermuxSession()` at :555 is NOT a fire-and-forget; it synchronously:
  - TermuxService.java:613  mShellManager.mTermuxSessions.add(newTermuxSession)
  - TermuxService.java:623  mTermuxTerminalSessionActivityClient
                               .termuxSessionListNotifyUpdated();
`termuxSessionListNotifyUpdated()` runs the FULL pager sync + `updateTabs()`
(see §4) BEFORE `createTermuxSession()` returns to :555. So by the time control
returns to :556, the tab has already been added to `mTabsContainer` and
`scrollStripToEnd()` has already been invoked from the add-branch.

  :605  TermuxSessionTabsController tabs = mActivity.getTermuxSessionTabsController();
  :606  if (tabs != null) tabs.setEndScrollReserved(true);   // <-- ARMED HERE
  :607  markPendingEndScrollSession(newTerminalSession);     // <-- FALLBACK ARMED HERE
  :609  setCurrentSession(newTerminalSession);               // <-- SMOOTH PAGER SETTLE

The arming at :606-607 executes AFTER the synchronous pager sync inside :555.
That is the reversed-order defect: `updateTabs()` (which calls
`scrollStripToEnd()`) already ran during :555, before :606 could set
`mEndScrollActive`.

Note: `scrollStripToEnd()` itself sets `mEndScrollActive = true` at
TermsController.java:741, so the guard ends up true anyway — but the scroll
request was posted and the listener registered during the pre-settle layout.

================================================================================
3. markPendingEndScrollSession() — TermuxTerminalSessionActivityClient.java:208-213
================================================================================

    public void markPendingEndScrollSession(@NonNull TerminalSession session) {
        mPendingEndScrollSession = session;
        ... removeCallbacks(mEndScrollFallback);
        mainHandler.postDelayed(mEndScrollFallback, 250);
    }

Arms the 250 ms fallback so that if the shell never emits a title,
`mEndScrollFallback` will still trigger a `scrollStripToEnd()`. This is the
"safety net" for the second-chance path.

================================================================================
4. THE CHAIN: termuxSessionListNotifyUpdated → updateTabs (add-branch)
================================================================================

TermuxService.java:623
  → TermuxTerminalSessionActivityClient.termuxSessionListNotifyUpdated()  (:741-746)
  → TermuxActivity.termuxSessionListNotifyUpdated(preferredIndex)         (TermuxActivity.java:1995)
       :2003  mSessionPagerManager.termuxSessionListNotifyUpdated(preferredIndex)
       :2010  mTermuxSessionTabsController.updateTabs(service.getTermuxSessions())

SessionPagerManager.termuxSessionListNotifyUpdated()  (:624-720) does, IN ORDER:
  :687  mTerminalPagerAdapter.syncWithServiceList(...)
  :690  mTerminalPager.setCurrentItem(restoreIndex, false)   // INSTANT jump, not smooth
  :699  onTerminalPageSelected(activeIndex)
            └─ onSessionPageSelected → updateTabs()  (TermuxTerminalSessionActivityClient.java:468)
                 ** FIRST updateTabs ** → add-branch → scrollStripToEnd() (see §5)
  :708  managePlaceholderForPosition(activeIndex)
  ...returns to TermuxActivity:2010 →
       ** SECOND updateTabs ** → newCount == sessionCount, mEndScrollActive true → skip CENTRE

So `updateTabs()` is reached, and the add-branch (`newCount > sessionCount`) is
taken on the FIRST call because at that point `mTabsContainer` still has the OLD
child count (the tab is added *inside* `updateTabs` at TermsController.java:137-141).

================================================================================
5. updateTabs() add-branch — TermuxSessionTabsController.java:116-191
================================================================================

  :131  int sessionCount = mTabsContainer.getChildCount() - 1;
  :132  int newCount = sessions.size();
  :134  if (newCount > sessionCount) {                       // ADD BRANCH
  :137-141   for (...) mTabsContainer.addView(tabView, childCount-1);
  :173  } else if (newCount > sessionCount) {  // same condition, entered above
  :179      scrollStripToEnd();                // *** END-SCROLL ARMED HERE ***
        }
  :180  } else if (currentSessionIndex >= 0) {
  :185      if (!mEndScrollActive) requestScroll(SCROLL_CENTRE, currentSessionIndex);
        }

`scrollStripToEnd()` (TermsController.java:738-745):
  :741  mEndScrollActive = true;
  :744  requestScroll(SCROLL_END, -1);

`requestScroll()`  (:629-643):
  :633  if (mode == SCROLL_CENTRE) ...  // NOT taken for SCROLL_END
  :637  mScrollSeq++;
  :638  mScrollSeqPending = mScrollSeq;
  :640  mTabsScroll.removeCallbacks(mScrollRunnable);
  :641  mTabsScroll.post(mScrollRunnable);          // <-- RUNNABLE QUEUED

So YES — `scrollStripToEnd()` IS reached and DOES post `mScrollRunnable`.

================================================================================
6. mScrollRunnable → runEndScroll → OnGlobalLayoutListener
================================================================================

mScrollRunnable  (:490-506):
  :493  long seq = mScrollSeqPending;
  :496  if (seq < mScrollSeq) return;               // sequence guard — not tripped here
  :500  if (mode == SCROLL_END) runEndScroll();

runEndScroll()  (:536-626):
  :540  cancelEndScroll();                          // tears down any PRIOR listener
  :549-623  builds OnGlobalLayoutListener:
  :568      stillMoving = (t != null && t.isRunning())   // LayoutTransition running?
  :569      if (stillMoving && mRetries < 10) { mRetries++; return; }  // wait
  :573      removeOnGlobalLayoutListener(this);
  :582      childMeasuredW = mTabsContainer.getMeasuredWidth();
  :584      maxScroll = Math.max(0, childMeasuredW - scrollW);
  :593      startX = mTabsScroll.getScrollX();
  :594      if (startX == maxScroll) { restore; return; }   // *** NO-OP EARLY OUT ***
  :601      mEndScrollAnim = ValueAnimator.ofInt(startX, maxScroll);
  :606      addUpdateListener → mTabsScroll.scrollTo(animated, 0);

The listener is registered at :625 on `mTabsContainer.getViewTreeObserver()`.
The container is attached (it is a live child of the HorizontalScrollView in the
activity), so the observer IS live and WILL dispatch. So (c)-style "listener
never fires" is not the mechanism — the listener DOES fire.

================================================================================
7. THE PRIME SUSPECT FROM THE BRIEF — CHECKED AND DISPROVEN FOR THIS PATH
================================================================================

Brief hypothesis (c): `setEndScrollReserved(false)` is called during the
programmatic pager settle (onPageScrollStateChanged → DRAGGING), which calls
`cancelEndScroll()` (:838) and removes the just-registered `OnGlobalLayoutListener`.

Call sites of `setEndScrollReserved(false)`:
  - SessionPagerManager.java:132 — ONLY inside `if (state == SCROLL_STATE_DRAGGING)`.
  - TermuxSessionTabsController.java:828-840 — `setEndScrollReserved(false)` calls
    `cancelEndScroll()` ONLY in the `!reserved` branch.

SessionPagerManager.onPageScrollStateChanged  (:120-156):
  :125  if (state == SCROLL_STATE_DRAGGING) {
  :126      mUserScrollInProgress = true;
  :132      tabs.setEndScrollReserved(false);          // ONLY HERE
  :133  } else if (state == SCROLL_STATE_IDLE) { ... } // no setEndScrollReserved

VERDICT: A programmatic `setCurrentItem()` (used by both the sync at
SessionPagerManager.java:690 `setCurrentItem(restoreIndex, false)` and the smooth
`setCurrentItem(index, true)` at TermuxTerminalSessionActivityClient.java:427)
never emits `SCROLL_STATE_DRAGGING`. ViewPager2's smooth scroll is implemented via
RecyclerView `smoothScrollToPosition`, which transitions DRAGGING→SETTLING→IDLE is
false; only SETTLING (2) is emitted. The code comment at :122-124 states this
explicitly. Therefore `setEndScrollReserved(false)` is NOT called during the (+)
tap. The `OnGlobalLayoutListener` registered by `runEndScroll()` is NOT removed by
this path. Hypothesis (c) is DISPROVEN for the (+) tap.

(For completeness: `TermuxSessionTabsController.setCurrentSession(int)` at :842-882
returns early at :879 when `mEndScrollActive` is true, and when it does clear the
flag at :880 it does NOT call `cancelEndScroll()`, so it also does not remove the
listener.)

================================================================================
8. WHY THE SCROLL STILL "STAYS PUT" — THE REAL BREAK
================================================================================

Because (a), (b), and (c) do not occur, the remaining failure mode is (d):
the listener fires, but the geometry it measures produces a no-op or an
under-scroll. The root cause is the ORDERING defect identified in §2:

  1. `createTermuxSession()` (:555) synchronously runs the pager sync +
     `updateTabs()` add-branch → `scrollStripToEnd()` → `requestScroll(SCROLL_END)`
     → posts `mScrollRunnable`. This happens BEFORE :606/:607.

  2. `mScrollRunnable` runs on the next looper turn → `runEndScroll()` registers
     the `OnGlobalLayoutListener`. This listener will fire on the NEXT layout
     pass of `mTabsContainer`.

  3. AFTER `createTermuxSession()` returns, :606 `setEndScrollReserved(true)` and
     :607 `markPendingEndScrollSession`. Then :609 `setCurrentSession` → smooth
     `setCurrentItem(index, true)`. The smooth settle does NOT touch the strip
     scroll (onPageScrolled is suppressed by `mEndScrollActive`), but it DOES
     repeatedly re-layout the pager and, because the new tab was added inside the
     same synchronous notify, the container's measured width is still settling
     through the LayoutTransition / adapter rebind at the moment the listener
     first dispatches.

  4. The listener (runEndScroll :553) reads `childMeasuredW =
     mTabsContainer.getMeasuredWidth()` and `maxScroll`. If the LayoutTransition is
     NOT yet finished, `stillMoving` is true and it waits (retries). But once it
     proceeds, it reads the width at that instant. If at that instant the new tab's
     final width is not yet reflected (or the strip had been momentarily scrolled
     back to a smaller scrollX by a competing layout), `maxScroll` is computed
     SHORT — or `startX` (current scrollX) already equals that short `maxScroll`,
     hitting the `startX == maxScroll` early-out at :594, which `return`s WITHOUT
     animating.

  5. The `onTitleChanged` second-chance (TermuxTerminalSessionActivityClient.java:193
     → `scrollStripToEnd()`) re-posts and re-registers a listener. But it fires
     while the smooth pager settle from :609 is still running, so it again measures
     the mid-transition geometry. More importantly, because `scrollStripToEnd()`
     itself re-arms and re-posts, and the pager settle drives further layouts, the
     geometry read is frequently the SAME short value → `startX == maxScroll` again
     → no-op.

Net result: in all cases the listener either measures a stale/short `maxScroll`
or hits the `startX == maxScroll` early-out, so `mTabsScroll.scrollTo(maxScroll)`
is never actually driven to the true right end. The strip "stays put."

The fundamental defect is the REVERSED ORDER: the arming you added
(`setEndScrollReserved(true)` + `markPendingEndScrollSession`) runs AFTER
`updateTabs()`/`scrollStripToEnd()` have already executed (because the pager sync
is synchronous inside `createTermuxSession`). So the FIRST end-scroll is armed and
executed against pre-settle geometry, and the smooth pager settle that follows
continuously invalidates the geometry the listeners measure.

================================================================================
9. STEP-BY-STEP VERDICT (per the brief's 9 questions)
================================================================================

1. (+) → addNewSession(false,null): REACHES updateTabs. Chain confirmed:
   TermuxService:623 → client:741 → TermuxActivity:1995/2003/2010 →
   SessionPagerManager:624 (pager sync + onTerminalPageSelected:699 →
   onSessionPageSelected:468 → updateTabs) AND TermuxActivity:2010 → updateTabs.

2. Arming lines present? YES — TermuxTerminalSessionActivityClient.java:606-607.
   But they execute AFTER the synchronous pager sync inside createTermuxSession
   (:555). So they arm LATE, after updateTabs already ran.

3. setCurrentSession(:609) → pager.setCurrentItem(index,true) (:427). During settle
   updateTabs is called (from onSessionPageSelected:468). Add-branch already taken
   earlier; here newCount==sessionCount, mEndScrollActive true → skip CENTRE (:185).
   scrollStripToEnd WAS called from the earlier add-branch.

4. mScrollRunnable → runEndScroll → registers OnGlobalLayoutListener: CONFIRMED
   (TermsController.java:490-506, 536-625).

5. Is the listener on a live observer? YES — mTabsContainer is attached; the
   ViewTreeObserver dispatches. So the listener DOES fire. (Not the break.)

6. Does setEndScrollReserved(false)/cancelEndScroll() fire between scrollStripToEnd
   and listener firing? NO for the DRAGGING path (programmatic setCurrentItem never
   DRAGGING). CONFIRMED hypothesis (c) does NOT occur. (See §7.)

7. onPageScrollStateChanged (:120-156): setEndScrollReserved(false) only in DRAGGING
   branch. Programmatic settle does not enter it. So no cancel.

8. onTitleChanged second-chance (:193 → scrollStripToEnd) DOES fire (mPendingEndScrollSession
   armed at :607). It re-posts a fresh listener, but it measures mid-settle geometry
   and typically hits `startX == maxScroll` no-op, same as the first. The pager is
   settled by then, but `setCurrentSession` is NOT called again after onTitleChanged
   (no further cancel). The problem is geometry timing, not a re-cancel.

9. VERDICT: The break is (d) — listener fires but the measured `maxScroll` is stale
   / short and `startX == maxScroll` causes the `ValueAnimator` to be skipped
   (TermsController.java:594), OR the animation is driven toward a pre-settle
   `maxScroll`. Primary cause: arming order reversed — `updateTabs()`/`scrollStripToEnd()`
   run synchronously inside `createTermuxSession` (:555) BEFORE the :606-607 arming,
   and the smooth `setCurrentItem` settle (:609) continuously invalidates the
   container geometry the listeners measure.

================================================================================
10. KEY FILE:LINE REFERENCES
================================================================================

TermuxActivity.java
  :827  new_session_tab_button.setOnClickListener → addNewSession(false,null)
  :1995 termuxSessionListNotifyUpdated(int)
  :2003 mSessionPagerManager.termuxSessionListNotifyUpdated(...)
  :2010 mTermuxSessionTabsController.updateTabs(...)

TermuxTerminalSessionActivityClient.java
  :538  addNewSession
  :555  service.createTermuxSession(...)  ← SYNCHRONOUS pager sync + updateTabs inside
  :606  tabs.setEndScrollReserved(true)  ← ARMED, but AFTER :555 already ran updateTabs
  :607  markPendingEndScrollSession(newTerminalSession)
  :609  setCurrentSession(newTerminalSession) → smooth setCurrentItem
  :174-200 onTitleChanged → :193 scrollStripToEnd (second chance) ; :195 termuxSessionListNotifyUpdated
  :208-213 markPendingEndScrollSession (250ms fallback)
  :369-433 setCurrentSession(TerminalSession,boolean) → pager.setCurrentItem(index,true) at :427

TermuxService.java
  :613  mTermuxSessions.add(newTermuxSession)
  :623  termuxSessionListNotifyUpdated()  ← fires synchronously before createTermuxSession returns

TermuxSessionTabsController.java
  :116-191 updateTabs
  :134-141 add-branch: mTabsContainer.addView(...) then scrollStripToEnd() at :179
  :180-186 equal-count branch: `if (!mEndScrollActive) requestScroll(SCROLL_CENTRE)`
  :490-506 mScrollRunnable → runEndScroll / runCentreScroll
  :525-534 cancelEndScroll()
  :536-626 runEndScroll (measures maxScroll at :582-584; no-op if startX==maxScroll at :594)
  :629-643 requestScroll (posts mScrollRunnable)
  :738-745 scrollStripToEnd (sets mEndScrollActive=true, requestScroll(SCROLL_END))
  :828-840 setEndScrollReserved (cancelEndScroll ONLY when reserved==false)

SessionPagerManager.java
  :120-156 onPageScrollStateChanged (setEndScrollReserved(false) ONLY in DRAGGING :132)
  :159-181 onPageScrolled → tabs.onPageScrolled (suppressed by mEndScrollActive)
  :624-720 termuxSessionListNotifyUpdated (pager sync; :690 setCurrentItem(restoreIndex,false);
           :699 onTerminalPageSelected → updateTabs)
  :183-219 onPageSelected (placeholder commit path; not on the (+) tap)

================================================================================
11. WHAT A CORRECT FIX WOULD LOOK LIKE (for the implementer)
================================================================================

The arming must happen BEFORE `createTermuxSession()` runs the synchronous
pager sync — i.e. move `tabs.setEndScrollReserved(true)` and
`markPendingEndScrollSession(newTerminalSession)` to BEFORE line :555 (after
`newTermuxSession` is obtained, or just before `createTermuxSession` if the
session object is needed). Alternatively, defer the tab-strip end-scroll so it
is measured only AFTER the smooth `setCurrentItem` settle completes (e.g. arm it
in `onPageScrollStateChanged(IDLE)` for a programmatic add, or post it after the
pager settle), guaranteeing the container geometry is final when
`runEndScroll`'s listener reads `maxScroll`. The current `startX == maxScroll`
no-op (:594) is also too eager — it should re-check geometry on the next layout
pass rather than permanently bailing on the first equal reading.

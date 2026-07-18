# Trace: maxScroll measurement & (+) button inclusion in the tab strip end-scroll

Project: Termux app fork — `com.termux.app.terminal.TermuxSessionTabsController`
Problem: After adding a tab, the strip does not scroll fully to the right end.
Scope: READ-ONLY. This report traces the measurement path only; no files were modified.

=============================================================================
1. THE WIDGET TREE (from activity_termux.xml + item_session_tab.xml)
=============================================================================

  session_tabs_container        LinearLayout  (match_parent x, wrap y)   [line 21-28]
    └─ session_tabs_scroll       HorizontalScrollView  (layout_width=0dp, weight=1) [line 30-35]
         └─ session_tabs          LinearLayout  (wrap_content x, wrap y, paddingHorizontal=4dp) [line 37-42]
              ├─ ... N × item_session_tab  (each wrap_content, marginEnd=4dp)   [item_session_tab.xml]
              └─ new_session_tab_button   ImageButton  (36dp x 36dp, marginEnd=4dp) [line 45-54]

Key facts established from XML:

  - `session_tabs` (mTabsContainer) uses `android:layout_width="wrap_content"` (line 39).
    => Its `getMeasuredWidth()` is the SUM of all child widths PLUS its own
       `paddingHorizontal` (4dp left + 4dp right = 8dp). It is NOT clamped to the
       viewport. (A match_parent container WOULD be clamped; it is not, so this
       suspect is eliminated.)

  - `session_tabs_scroll` (mTabsScroll) uses `layout_width="0dp" layout_weight="1"`
    (lines 32-34), inside a `match_parent` horizontal LinearLayout. => it fills the
    remaining horizontal space and its `getWidth()` = the VISIBLE viewport width.

  - `item_session_tab.xml` layout_width = `wrap_content` (line 3), NOT fixed, NOT
    weighted. The title TextView is `layout_width="0dp" layout_weight="1"` with
    `android:maxWidth="100dp"` (lines 14-17) and `ellipsize="end"`.
    => A tab's width is its padding (12dp start + 4dp end = 16dp) + title width,
       capped at 100dp, + close button (20dp + 4dp margin start = 24dp, only when
       visible). There is NO `minWidth`. So a tab with a short title such as "~"
       is genuinely NARROW; a long title is capped at ~140dp (16+100+24). This is
       important: tabs are variable width; the strip width is the sum of variable
       widths plus the trailing (+) button.

  - `new_session_tab_button` is a REAL child of `session_tabs` (lines 45-54), with
    width 36dp and `layout_marginEnd="4dp"`. Because it is a child of the
    wrap_content container, its 36dp width AND its 4dp right margin ARE part of the
    container's measured width. So `mTabsContainer.getMeasuredWidth()` DOES include
    the (+) button. The earlier hypothesis ("(+) button excluded") is FALSE — it is
    included by construction of the XML hierarchy.

=============================================================================
2. THE maxScroll COMPUTATION (TermuxSessionTabsController.java)
=============================================================================

runEndScroll() — lines 536-626. The authoritative measurement occurs inside the
OnGlobalLayoutListener (fires after layout settles):

  582:  final int childMeasuredW = mTabsContainer.getMeasuredWidth();
  583:  final int scrollW        = mTabsScroll.getWidth();
  584:  final int maxScroll      = Math.max(0, childMeasuredW - scrollW);

This is the standard, correct HorizontalScrollView clamp rule:
    clamp range = [0, childMeasuredWidth - viewportWidth].

  - childMeasuredW = full content width = Σ tabs + (+) button (36+4) + container
    padding (4+4). INCLUDES the (+) button. CONFIRMED.
  - scrollW = viewport width.
  - maxScroll = the exact scrollX that reveals the right edge (including the (+)
    button and the right padding). `scrollTo(maxScroll, 0)` therefore reveals the
    trailing (+) button fully.

scrollStripToEnd() — lines 738-745: sets `mEndScrollActive = true` and calls
`requestScroll(SCROLL_END, -1)`. No measurement happens here; it only schedules
`runEndScroll` via `mScrollRunnable` (line 641 `post`). Correct.

runCentreScroll() — lines 665-728: uses the SAME formula (lines 696-697), clamped to
maxScroll. It is the CENTRE path; not the suspect for end-scroll.

=============================================================================
3. IS maxScroll COMPUTED CORRECTLY? — VERDICT
=============================================================================

YES. In principle the maxScroll computation is correct:

  (a) It reads `getMeasuredWidth()` of a wrap_content container => NOT viewport-
      clamped, so no under-measurement.
  (b) That width inherently includes the (+) button (child) + its margin + the
      container's horizontal padding, because the (+) button is a descendant of
      `session_tabs`, not a sibling outside it.
  (c) The measurement is deferred to a GlobalLayoutListener that waits ONLY while
      the CHANGING LayoutTransition is actually running (lines 566-572), then reads
      geometry on the next stable pass. This avoids reading during the ~220ms
      re-measure window — the historically-correct fix for the "short stop".
  (d) The target is driven by a self-owned ValueAnimator calling `scrollTo(fixed
      target)` (lines 601-606), bypassing HorizontalScrollView's per-frame live
      re-clamp (the prior source of "stops short").
  (e) The CHANGING LayoutTransition is detached before animating (lines 589-591) and
      restored on end/cancel (lines 611-612, 616-618), so the content width cannot
      shrink mid-animation.

CONCLUSION: The maxScroll math itself is sound and the (+) button IS included.
A too-small maxScroll (button excluded / viewport-clamped) is NOT the cause of the
reported failure.

=============================================================================
4. ALTERNATIVE ROOT CAUSES (since the math is correct but the user sees FAIL)
=============================================================================

The agent scenarios reported PASS while the user reports FAIL. That discrepancy
points AWAY from the measurement code and toward one of:

  4.1 STALE BUILD / FIX NOT INSTALLED
       - The current source computes maxScroll correctly. If the installed APK
         predates these edits (the deferred GlobalLayoutListener + ValueAnimator
         rewrite), the device is running the OLD code that DID under-scroll.
       - Verdict: most likely when "code looks right but device fails". Build/install
         must be re-verified. No code change is warranted for the measurement path.

  4.2 onPageScrolled OVERRIDE FIGHTING THE END SCROLL (lines 893-948)
       - While `mEndScrollActive` is true, onPageScrolled is suppressed (lines 896-900
         and the duplicate guard at 905). Good — the END scroll owns the strip.
       - BUT note the redundancy: lines 896-900 `if (mEndScrollActive) { ... return; }`
         and lines 902-905 repeat `if (mEndScrollActive) return;`. Harmless, but the
         suppression depends entirely on `mEndScrollActive` being TRUE at swipe time.
       - Risk: if a genuine page settle (onPageScrollStateChanged→IDLE) or
         setCurrentSession fires and clears mEndScrollActive BEFORE the end-scroll
         ValueAnimator starts, onPageScrolled would then issue `scrollTo()` to an
         interpolated (short) target (line 933) and yank the strip back left,
         clipping the (+) button. The end-scroll guard chain: setCurrentSession lines
         876-878 returns early while mEndScrollActive; setEndScrollReserved(false)
         (line 828) clears it and cancels the end-scroll. Trace ordering is critical.
       - This is an OVERRIDE interaction, NOT a measurement error. See trace_page_scrolled.

  4.3 updateAddButtonVisibility (lines 198-209)
       - At MAX_SESSIONS (8) the (+) button is set GONE (line 207) to release its
         footprint. This is correct and safe here because the end-scroll measures
         AFTER the button is hidden (so the strip ends exactly on the last real tab).
       - For the general (non-limit) case the button stays VISIBLE, so it is included
         in measured width. No defect.

  4.4 Timing of label (OSC title) vs. end-scroll
       - scrollStripToEnd is called only AFTER the new session's label is set
         (see header lines 730-737). The runEndScroll waits for layout stability.
         If the label arrives and SHRINKS the tab (short "~"), the comment at lines
         542-548 explicitly notes the old growth-gate was removed precisely because a
         shrinking tab would starve the scroll. The current code waits for stability
         not growth, so this is handled.

=============================================================================
5. SUMMARY TABLE
=============================================================================

  Suspect                                          Verdict
  ----------------------------------------------   ------------------------------
  getMeasuredWidth() of wrong view used            FALSE — mTabsContainer is the
                                                   wrap_content content view.
  getMeasuredWidth() clamped to viewport           FALSE — container is wrap_content,
                                                   not match_parent.
  (+) button excluded from maxScroll               FALSE — (+) is a child of the
                                                   container; its width+margin are
                                                   in getMeasuredWidth().
  Container padding mishandled                     FALSE — padding included in
                                                   getMeasuredWidth; scrollTo(max)
                                                   reveals right edge + padding.
  maxScroll formula wrong                          FALSE — standard
                                                   childW - viewportW, clamped >=0.
  Live HSV re-clamp stops scroll short             FIXED — self-driven ValueAnimator
                                                   with fixed target (lines 601-606).
  LayoutTransition shrink mid-anim                 FIXED — transition detached
                                                   (lines 589-591).
  Measure during unstable layout                   FIXED — deferred GlobalLayout
                                                   gate (lines 549-572).
  --------------------------------------------------------------
  REAL LIKELY CAUSE                                Stale build OR onPageScrolled
                                                   override fight (see §4.2 /
                                                   trace_page_scrolled).

=============================================================================
6. FINAL CONCLUSION
=============================================================================

The maxScroll computation in runEndScroll() (lines 582-584) is CORRECT in principle:
it measures the wrap_content content container (which inherently includes the (+)
button, its margin, and the container padding) and subtracts the viewport width of
the weight=1 HorizontalScrollView, producing the exact right-end scroll target. The
(+) button is INCLUDED, not excluded. Padding is handled correctly. The current code
also neutralises the two historical under-scroll mechanisms (live HSV re-clamp and
mid-animation LayoutTransition shrink).

Therefore, if the device still fails to reach the right end, the defect is NOT in
this measurement code. The two candidates are (a) a STALE BUILD that predates these
edits, or (b) an OVERRIDE interaction in onPageScrolled / setCurrentSession /
setEndScrollReserved that clears `mEndScrollActive` and re-issues a competing
`scrollTo()` to a short target before/during the end-scroll — which should be traced
separately (trace_page_scrolled). No measurement-path code change is indicated.

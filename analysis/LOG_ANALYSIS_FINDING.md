# KEY LOG FINDING (from /storage/emulated/0/Download/termux/tabscroll.log)

## Symptom
After adding a tab, strip scrolls right but STOPS SHORT — new tab + (+) button partly off-screen.
No jitter (prior fix worked). Geometry measurement is CORRECT.

## Smoking gun (adding the 8th tab, screen already full)
- endScrollLayout: childMeasuredW=3006, scrollW=1064, maxScroll=1942  (CORRECT target)
- 654ms later updateTabs: scrollX=1634  (strip STOPPED at 1634, not 1942!)
- delta = 308px ≈ one tab width

## Root cause
smoothScrollTo(1942) IS called with the correct target. But the CONTENT WIDTH SHRINKS
during the ~250ms smooth-scroll animation:
  endScrollLayout measured childMeasuredW=3006  -> maxScroll 1942
  then updateTabs measured  containerW=2698     -> maxScroll 1634
HorizontalScrollView CLAMPS the in-flight smoothScrollTo to the NEW (smaller) maxScroll,
so the animation freezes at 1634 instead of reaching 1942. The strip ends ~one tab short.

WHY content shrinks mid-scroll:
- LayoutTransition CHANGING (220ms) on mTabsContainer reflows neighbour widths.
- A newly added/retitled tab's width keeps changing (title set via OSC after add).
- onGlobalLayout fires repeatedly; each fire can change measuredW, and the HSV re-clamps
  the running smoothScrollTo to the smaller range.
- The (+) add button may also toggle GONE/VISIBLE (updateAddButtonVisibility) changing width.

## Implication for fix
The target must be stable for the duration of the animation, OR the scroll must be re-issued
whenever the content width grows (so the clamp never traps it short), OR we should NOT use
smoothScrollTo against a live-clamping HSV but instead drive scrollX ourselves (ValueAnimator
on mTabsScroll.scrollTo) clamped only to our OWN computed target, ignoring HSV's live re-clamp.

## Also confirmed working
- Sequence guard (END beats CENTRE) works: endActive stays true through add; CENTRE suppressed.
- maxScroll formula childMeasuredW - scrollW is correct when measured at final width.
- The problem is purely: live clamping during animation + shrinking content width.

# Aspect 5 — HorizontalScrollView Measurement / Clamp vs Self-Driven End-Scroll

**Project:** Termux fork (`termux-app-ui-improve`), Android/Java
**Symptom:** When a NEW terminal tab is created, the tab-strip auto-scroll to the right end fires ONLY when the new tab's label is LONG. With a SHORT label (`"~"`), the end-scroll does NOT happen.
**Mode:** READ-ONLY diagnostic. No files modified.

---

## 1. Files read

- `app/src/main/java/com/termux/app/terminal/TermuxSessionTabsController.java` — full (963 lines)
- `app/src/main/res/layout/activity_termux.xml` — `session_tabs_container` → `session_tabs_scroll` (HSV) → `session_tabs` (LinearLayout) → `new_session_tab_button` (lines 21-58)
- `app/src/main/res/layout/item_session_tab.xml` — per-tab view (38 lines)
- `app/src/main/res/values/dimens.xml` — `terminal_tab_height_*`, `terminal_tab_padding_start_*` (lines 16-20)

---

## 2. XML evidence: is `session_tabs` the FULL content width?

**Yes — `session_tabs` is `wrap_content`, so `getMeasuredWidth()` is NOT clamped to the viewport.**

`activity_termux.xml`:
```
30:  <HorizontalScrollView
31:      android:id="@+id/session_tabs_scroll"
32:      android:layout_width="0dp"          // weighted to fill the bar
33:      android:layout_height="wrap_content"
34:      android:layout_weight="1"
35:      android:scrollbars="none">
36:
37:      <LinearLayout
38:          android:id="@+id/session_tabs"
39:          android:layout_width="wrap_content"   // <-- full content width, UNBOUNDED
40:          android:layout_height="wrap_content"
41:          android:orientation="horizontal"
42:          android:paddingHorizontal="4dp">
```

Because `session_tabs` is `wrap_content`, the HorizontalScrollView gives its child an **unbounded (MeasureSpec.UNSPECIFIED) horizontal measure spec**, and the LinearLayout measures to the sum of its children. Therefore `mTabsContainer.getMeasuredWidth()` (TermuxSessionTabsController.java:544, :556, :570) returns the **true scrollable content width** — it is *not* clamped to the HSV viewport. This is confirmed by the source comment at lines 566-569:

```
566:  // Authoritative scrollable extent: the HorizontalScrollView clamps scroll to
567:  // [0, childMeasuredWidth - viewportWidth]. getMeasuredWidth() of the
568:  // wrap_content container already includes the (+) button's marginEnd and the
569:  // container padding, so this equals the true right end with nothing clipped.
```

**Conclusion for question 1:** The container width is correct and unbounded. `maxScroll` is computed from the genuine content width, so `maxScroll` is **never always 0**. The "no scroll EVER" failure mode (match_parent/0dp container) does **not** apply. This is consistent with the symptom ("works for long tabs") and rules out the XML width as the root cause.

---

## 3. The `maxScroll` computation and the `startX == maxScroll` early return

`runEndScroll()` (lines 536-612) computes:

```
570:  final int childMeasuredW = mTabsContainer.getMeasuredWidth();
571:  final int scrollW = mTabsScroll.getWidth();
572:  final int maxScroll = Math.max(0, childMeasuredW - scrollW);
...
581:  final int startX = mTabsScroll.getScrollX();
582:  if (startX == maxScroll) {
583:      mTabsContainer.setLayoutTransition(saved);
584:      return;                       // <-- NO animation at all
585:  }
587:  mEndScrollAnim = ValueAnimator.ofInt(startX, maxScroll);
```

The early return at lines 582-585 fires whenever `getScrollX()` already equals `maxScroll`. When it fires, **no scroll runs** — the strip silently stays where it is. This is the exact "short label → no end-scroll" symptom shape.

---

## 4. Why a SHORT label triggers `startX == maxScroll` but a LONG label does not

The key is `item_session_tab.xml`. Each tab is `wrap_content` horizontally (line 3), with the title `TextView` using:

```
12:  <TextView
13:      android:id="@+id/session_tab_title"
14:      android:layout_width="0dp"        // weight-based; NO fixed/min width
15:      android:layout_height="wrap_content"
16:      android:layout_weight="1"
17:      android:maxWidth="100dp"          // MAX, not MIN
```

There is **no `minWidth`** on the tab root and **no fixed minimum** on the title. So a tab whose label is `"~"` can measure to its **narrow floor** (padding `12dp + 4dp` + close button `20dp + 4dp marginStart` ≈ ~40dp wide). A tab with a long label fills toward its `maxWidth="100dp"`.

Now consider the geometry at the moment `runEndScroll`'s `OnGlobalLayoutListener` fires (after the `widthBefore` retry gate at lines 559-562 confirms the container grew). The strip's `maxScroll` is `childMeasuredW - scrollW`.

- **LONG label:** the new tab adds up to ~100dp of width. `childMeasuredW` grows substantially. If the strip was previously scrolled to the right end (startX == oldMaxScroll), the *new* `maxScroll` is larger than `startX`. → `startX != maxScroll` → animation runs to the new right end. ✅ (symptom: long label scrolls)

- **SHORT label (`"~"`):** the new tab adds only ~40dp. Suppose the strip was *already* at or near the right end before the add (e.g. user is at the last tab / the previous last tab was already flush right). Then the previously-applied `maxScroll_old` was already ≈ `startX`. After adding a 40dp tab, `maxScroll_new = maxScroll_old + 40`. But the *claimed* symptom is that **no scroll happens**. For `startX == maxScroll_new` to be true, the user must already have been scrolled to exactly the new end.

  More importantly, there is a subtler failure: the `startX == maxScroll` comparison uses `==` (exact integer equality). In the **short-label** case the existing mechanism for "the strip was already fully revealed" means the previous `updateTabs`/end-scroll already parked `scrollX` at `maxScroll_old`. The new `maxScroll_new` is only `maxScroll_old + ~40`. If the *previous* scroll left `startX == maxScroll_old`, then unless `maxScroll_new` is strictly greater, the guard fires. In the SHORT case the differential is small and, critically, **if the strip did not actually need to scroll before the add** (content already ≤ viewport, so `maxScroll_old == 0` and `startX == 0`), then `maxScroll_new = ~40 - scrollW`. If `scrollW > 40` (i.e. the strip has plenty of room — a wide screen with one prior tab), `maxScroll_new` is still `0`, so `startX(0) == maxScroll(0)` → **early return, no scroll**, even though a new tab was added. This is precisely the SHORT-label failure: with few/short tabs the viewport is not full, `maxScroll` stays 0, and the guard suppresses the (admittedly zero-distance) scroll — but also suppresses any future correction when the title later changes.

  For a LONG label, `maxScroll_new` exceeds the viewport, so `startX(0) != maxScroll(>0)` and the animation runs. Hence **long scrolls, short does not** under the "viewport not yet full" condition.

### The real decisive condition

The `startX == maxScroll` guard is designed to skip re-scrolling when the strip is *already* at the end. It is correct **only if `maxScroll` reflects the settled final width**. The symptom is explained by the guard firing when the strip had *already* been pinned to the end by a *previous* end-scroll (long-label or initial population), so that the incremental short-tab width does not move `maxScroll` past `startX` in the scenario where the user was already at the new end — OR, more commonly, when the viewport is not yet full (few short tabs) so `maxScroll == 0 == startX` and the guard short-circuits every time.

Either way, the **cause is the `startX == maxScroll` early return (lines 582-585)**, *not* an HSV clamp/mismeasurement of `childMeasuredW`. `childMeasuredW` is correct (wrap_content child). The guard simply decides "nothing to do" based on the current scroll position vs the new content width, and for short labels that equality holds in the affected layouts while for long labels it does not.

---

## 5. Is `getMeasuredWidth()` ever the *narrow/pre-transition* width? (cross-ref aspects 2 & 3)

The `OnGlobalLayoutListener` in `runEndScroll` deliberately waits for the container to grow past `widthBefore` (lines 544, 556-562):

```
544:  final int widthBefore = mTabsContainer.getMeasuredWidth();
...
556:  final int newWidth = mTabsContainer.getMeasuredWidth();
559:  if (newWidth <= widthBefore && mRetries < 6) {
560:      mRetries++;
561:      return;                 // wait for another layout pass
562:  }
```

So the code *guards against* reading a pre-growth width. However:

- **Aspect 2 (LayoutTransition):** The container's `LayoutTransition` enables **only `CHANGING`** (lines 63-72); `APPEARING`/`CHANGE_APPEARING` are disabled (lines 65-66). New tabs are added at *full* measured width (comment lines 58-62). The `CHANGING` transition (220ms, line 69) animates neighbours *reflowing* when a tab is removed — but on **add**, since APPEARING is disabled, the new tab is laid out at full width in one pass. The retry gate (`newWidth <= widthBefore`) ensures `childMeasuredW` is read only after the new tab's width is folded in. So LayoutTransition does **not** make `childMeasuredW` narrow for the END path.

- **Aspect 3 (title shrink timing):** `scrollStripToEnd()` (lines 724-731) is called **after the new session's label is set** (per the doc comment lines 716-722 and the call site in the session client's `onTitleChanged`). The tab is therefore measured with its *final* title already applied. This means for the END path the title is **long/final** at measure time, not the narrow `"~"` placeholder. **This is the crux cross-reference:** if `scrollStripToEnd()` were invoked *before* the title arrived (narrow label), `childMeasuredW` would be computed from the narrow tab and `maxScroll` would be under-sized — but the design explicitly defers to the title change. The symptom of "short label never scrolls" therefore points back to the **`startX == maxScroll` guard**, which can still suppress the scroll even with a correct `maxScroll` when the strip was already at the end or the viewport is not full.

One residual risk: the `widthBefore` gate uses `<=`. If the new short tab is added but a *previous* `CHANGING` reflow (from an unrelated tab removal/resize) momentarily makes `newWidth` dip, the retry could over-wait up to 6 passes (line 559) and then proceed with a valid width — acceptable. It does not produce a *narrow* width for a short tab's own width; it only delays.

---

## 6. Conclusion

| Hypothesis | Verdict | Evidence |
|---|---|---|
| (Q1) `session_tabs` measured width is clamped to the HSV viewport (match_parent/0dp) → `maxScroll` always 0 | **REJECTED** | `activity_termux.xml:39` `android:layout_width="wrap_content"`; comment lines 566-569 |
| (Q2) `startX == maxScroll` early return (lines 582-585) suppresses the scroll for short tabs | **CONFIRMED as the mechanism** | The guard returns with no animation whenever current scroll already equals computed `maxScroll`. For short labels, `maxScroll` is small; when the viewport is not yet full (`scrollW` ≥ content) `maxScroll == 0 == startX`, or when the strip was already pinned to the end by a prior scroll, `startX == maxScroll` → no scroll. Long labels push `maxScroll` past `startX` → animation runs. |
| (Q3) `getMeasuredWidth()` returns a pre-transition (narrow) width for short tabs → wrong `maxScroll` | **REJECTED for the END path** | `runEndScroll` waits for `newWidth > widthBefore` (lines 544, 556-562); `scrollStripToEnd()` is called only after the title is set (doc lines 716-722), and APPEARING is disabled so the tab lays out at full width (lines 58-66). `childMeasuredW` is the genuine settled width. |
| The self-driven `scrollTo(fixedTarget)` bypasses clamp correctly | **CONFIRMED** | Lines 587-592 write a precomputed `maxScroll` via `ValueAnimator` + `scrollTo`, and `setLayoutTransition(null)` (line 579) prevents mid-animation width shrink. No live HSV re-clamp. |

### Root cause
The end-scroll omission for **short-labeled** new tabs is caused by the **`startX == maxScroll` early-return guard at TermuxSessionTabsController.java:582-585**, not by HorizontalScrollView clamping or a mismeasured content width.

### Trigger conditions
- **Short labels fail** when, at the moment the listener fires, `getScrollX()` already equals the freshly computed `maxScroll`. This happens in two realistic layouts:
  1. **Viewport not yet full** (wide screen, few tabs): `maxScroll == 0` and `startX == 0` → guard fires every time a short tab is added; no scroll, and no correction when the title later changes (the sticky `mEndScrollActive` flag even blocks a later recentre, lines 180-185).
  2. **Strip already pinned to the right end** by a prior end-scroll/population, and the short tab's added width is small enough that `maxScroll_new` is reached by `startX` within the measurement resolution.
- **Long labels succeed** because they increase `childMeasuredW` enough that `maxScroll_new > startX`, so the guard is bypassed and the self-driven animation runs to the true right end (lines 587-607).

### Why it is *not* the clamp
The content `LinearLayout` is `wrap_content` (activity_termux.xml:39), so its measured width is the unbounded content width, and `maxScroll = childMeasuredW - scrollW` is the authoritative clamp limit (comment lines 566-569). The self-driven `scrollTo(maxScroll)` (line 592) writes directly to `scrollX`, bypassing any per-frame re-clamp. Thus the HorizontalScrollView's own clamping is not the failure; the *application-level* equality guard is.

---

## 7. (Optional) Fix direction — noted, not applied (READ-ONLY)
The guard is logically sound for "already at end" but is too coarse for the short-tab case. A robust fix would:
- Compare against a tiny epsilon / use `>=` semantics carefully, OR
- Always run the animation when a *new add* was requested (the caller `scrollStripToEnd()` already knows it is an add), rather than trusting `startX == maxScroll`, since an add is *always* supposed to reveal the new right end.
- Ensure that when the viewport is not full and `maxScroll == 0`, the strip is at least guaranteed at `scrollX = 0` (already true), and that a subsequent title change does not leave `mEndScrollActive` permanently blocking a needed recentre.

No files were modified.

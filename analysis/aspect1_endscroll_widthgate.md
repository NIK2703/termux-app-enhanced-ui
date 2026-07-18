# Aspect 1 — Root-cause analysis of the `runEndScroll()` width-gate

**Scope:** READ-ONLY diagnostic. No files were modified.

**Symptom:** When a new terminal tab is created, the tab-strip's auto-scroll to
the right end (to reveal the trailing `(+)` add button) fires reliably only when
the new tab's label is **long / maximum width**. When the new tab's label is
**short** (e.g. `~`), the right-end scroll animation does **not** happen — the
strip stays put.

**Files analyzed (fully):**
- `app/src/main/java/com/termux/app/terminal/TermuxSessionTabsController.java`
- `app/src/main/java/com/termux/app/terminal/TermuxTerminalSessionActivityClient.java`
- `app/src/main/res/layout/activity_termux.xml`
- `app/src/main/res/layout/item_session_tab.xml`

---

## 1. Layout / geometry facts (from XML)

From `activity_termux.xml:30-58`:

- `session_tabs_container` (`R.id.session_tabs_container`) is a horizontal
  `LinearLayout`, `layout_width="match_parent"`. This is **NOT** the view the
  controller calls `mTabsContainer`.
- `session_tabs_scroll` (`R.id.session_tabs_scroll` → `mTabsScroll`) is the
  `HorizontalScrollView`, `layout_width="0dp"` + `layout_weight="1"`, so its
  width is fixed to the available viewport width (the horizontal space left in
  the outer row). **This is the viewport.**
- `session_tabs` (`R.id.session_tabs` → `mTabsContainer`) is the inner
  `LinearLayout`, `layout_width="wrap_content"`, `paddingHorizontal="4dp"`. This
  is the scrolled content whose measured width grows as tabs are added.

`mTabsContainer` is bound in the constructor at
`TermuxSessionTabsController.java:54`:
```java
this.mTabsContainer = activity.findViewById(R.id.session_tabs);   // wrap_content inner strip
this.mTabsScroll   = activity.findViewById(R.id.session_tabs_scroll); // 0dp+weight=1 viewport
```

Each tab (`item_session_tab.xml`) is `wrap_content` wide with a title
`TextView` that has `maxWidth="100dp"` + `ellipsize="end"` + `layout_weight="1"`
on a `0dp` width. The trailing `(+)` button is `36dp + marginEnd 4dp`.

**Key consequence:** the *content* width `mTabsContainer.getMeasuredWidth()`
depends on the label text width of each tab (bounded to ~100dp), while the
*viewport* width `mTabsScroll.getWidth()` is constant for a given device
orientation.

---

## 2. The call chain that reaches `runEndScroll()`

For a user-initiated add:

1. `updateTabs()` (`TermuxSessionTabsController.java:116`) is called with the new
   session list.
2. `newCount > sessionCount`, so the new tab view is inflated and inserted
   **before the `(+)` button** at line **140**:
   ```java
   mTabsContainer.addView(tabView, mTabsContainer.getChildCount() - 1);
   ```
   The tab is added at its **full measured width** (LayoutTransition APPEARING
   is deliberately disabled — constructor lines 63-72 — so there is no 0→full
   width grow animation).
3. Titles/colors are synced in-place (lines 153-157).
4. Because `newCount > sessionCount`, line **179** calls `scrollStripToEnd()`.
5. `scrollStripToEnd()` (line 724) sets `mEndScrollActive = true` and calls
   `requestScroll(SCROLL_END, -1)` (line 730).
6. `requestScroll()` (line 615) bumps the sequence, then
   `mTabsScroll.post(mScrollRunnable)` (line 627).
7. On the next looper tick `mScrollRunnable` (line 490) runs and, for
   `SCROLL_END`, calls `runEndScroll()` (line 501).
8. `runEndScroll()` (line 536) captures `widthBefore` and registers an
   `OnGlobalLayoutListener` that later computes `maxScroll` and starts the
   animator.

There is also the deferred *label-driven* path
(`TermuxTerminalSessionActivityClient.onTitleChanged()`, lines 173-194, and
`markPendingEndScrollSession()`, lines 202-207) which calls `scrollStripToEnd()`
again once the shell sets the real title. Both paths funnel into the same
`runEndScroll()`, so the width-gate applies to both.

---

## 3. When is `widthBefore` captured — before or after the add?

**It is captured AFTER the new tab is already in the container** — but the
comment claims the opposite.

`runEndScroll():542-544`:
```java
// Record the width BEFORE the add so we only scroll once the container has actually grown
// to include the new tab (a layout pass may fire without the new width folded in yet).
final int widthBefore = mTabsContainer.getMeasuredWidth();
```

The comment says "the width BEFORE the add", but the actual execution order
proves otherwise:

- `updateTabs()` adds the child at **line 140** (synchronous).
- `updateTabs()` then calls `scrollStripToEnd()` at **line 179** →
  `requestScroll()` → `mTabsScroll.post(mScrollRunnable)`.
- `runEndScroll()` (and therefore the `widthBefore` capture at line 544) runs
  **later**, on a subsequent looper message.

So by the time line 544 executes, `addView()` has already run. The new child is
part of the container's child list. **`widthBefore` is therefore the container's
measured width *with the new tab already present*** — the exact opposite of what
the comment asserts. **Hypothesis point 1 confirmed.**

Two sub-cases for what `getMeasuredWidth()` returns at line 544:

- **(a)** If a layout/measure pass has *already run* since `addView()` (very
  common — `addView` triggers `requestLayout`, and the posted runnable often
  runs after that measure pass), then `widthBefore` already includes the new
  tab's full width. This is the dangerous case (see §4).
- **(b)** If no measure pass has run yet, `getMeasuredWidth()` returns the
  *previous* (stale) measured width from before the add. This is the case the
  gate was *designed* for.

The bug is that the code cannot tell (a) from (b), and case (a) is the norm here
precisely because tabs are inserted at full width (no APPEARING animation to
delay the settle).

---

## 4. The asymmetry: short tab vs long tab

The gate in the listener (`runEndScroll():556-562`):
```java
final int newWidth = mTabsContainer.getMeasuredWidth();
if (newWidth <= widthBefore && mRetries < 6) {
    mRetries++;
    return;
}
```

The gate's *intent* is: "don't scroll until the container has grown to include
the new tab, i.e. wait for `newWidth > widthBefore`." But since `widthBefore`
was already captured *after* the add (§3), the container has **already grown**.
From that moment on, `newWidth` will (in the steady state) equal `widthBefore`.

### Short tab (`~`)

- The container was inserted at full width during `updateTabs()`.
- By the time `runEndScroll()` runs, the strip has typically already been
  measured with the short tab folded in → `widthBefore` = final settled width
  **including** the short tab (case 4a).
- On every subsequent `onGlobalLayout()` callback, `newWidth == widthBefore`.
  The condition `newWidth <= widthBefore` is therefore **TRUE**.
- The listener increments `mRetries` and returns — for all 6 retries — because
  the width **never exceeds** the value that already contained the tab.
- After 6 retries the gate falls through with `newWidth == widthBefore`.

Whether the scroll then happens hinges entirely on §5 (the `startX == maxScroll`
no-op check). And crucially: **the label text of a short tab (`~`) barely widens
the strip.** If the strip content (existing tabs + short tab + `(+)` button) is
**already narrower than the viewport**, then `maxScroll == 0`. If the current
`scrollX` is also `0` (nothing has been scrolled), then `startX == maxScroll`
and the animator is skipped (line 582-585) — a legitimate no-op, but it *looks*
like "the scroll didn't fire". More importantly, even when the content *does*
overflow the viewport, the 6-retry stall means the scroll is delayed and can be
pre-empted / clobbered before it starts (see §6).

### Long tab (max width, ~100dp)

- Same insertion mechanics, but the wider label makes a bigger difference to the
  content width.
- Two things can make the long-tab path *appear* to work:
  1. A long label is more likely to make the *earlier* `getMeasuredWidth()` read
     (at line 544) catch the container **mid-remeasure** (case 4b), i.e.
     `widthBefore` captured the pre-add / partially-measured width, so a later
     pass genuinely yields `newWidth > widthBefore` → gate passes on the first
     or second callback, promptly and correctly.
  2. Even in the steady state where `newWidth == widthBefore`, the long tab is
     far more likely to push the content past the viewport → `maxScroll > 0` and
     `startX (0) != maxScroll` → the animator **does** start.

So the asymmetry is real, but it is a **compound** effect:

| | short `~` tab | long (max) tab |
|---|---|---|
| `widthBefore` vs steady `newWidth` | equal (gate never passes early → 6 retries) | often unequal (gate passes early) OR equal |
| content width added | tiny | large |
| `maxScroll` after gate | often `0` or ≈ current scrollX | clearly `> 0` |
| `startX == maxScroll` no-op | **frequently true → no animation** | rarely true → animation runs |
| perceived result | **strip stays put** | strip scrolls to end |

**Hypothesis point 2 confirmed** — the asymmetry is genuine, and it stems from
`widthBefore` being captured post-add combined with the short tab producing a
`maxScroll` that is ≈ the current `scrollX`.

---

## 5. Exact behaviour when `newWidth` never exceeds `widthBefore`

After the 6th retry the gate falls through (`mRetries < 6` becomes false). The
code then (lines 563-607):

```java
mTabsContainer.getViewTreeObserver().removeOnGlobalLayoutListener(this);   // 563
...
final int childMeasuredW = mTabsContainer.getMeasuredWidth();              // 570  (== widthBefore)
final int scrollW        = mTabsScroll.getWidth();                        // 571  (viewport)
final int maxScroll      = Math.max(0, childMeasuredW - scrollW);         // 572
...
final int startX = mTabsScroll.getScrollX();                             // 581
if (startX == maxScroll) {                                                // 582
    mTabsContainer.setLayoutTransition(saved);                            // 583
    return;                                                               // 584  ← NO-OP EXIT
}
mEndScrollAnim = ValueAnimator.ofInt(startX, maxScroll); ...              // 587+
```

- `childMeasuredW == widthBefore` (steady state).
- `maxScroll = max(0, childMeasuredW - scrollW)`.
- **If the whole strip fits in the viewport** (few short tabs, `~` label):
  `childMeasuredW <= scrollW` → `maxScroll == 0`. If `scrollX` is already `0`
  (it usually is when the strip has never scrolled), then `startX == maxScroll`
  → **line 582 returns without starting the animator**. This is a *correct*
  no-op (there is genuinely nothing to reveal), but it is *also* the visible
  symptom for the case where the user expects the `(+)` to be revealed and it
  already is.
- **If the strip overflows but the current scroll already sits at the (stale)
  computed `maxScroll`** — e.g. because a competing `scrollTo()` from the pager
  or a prior end-scroll left `scrollX` at that value — `startX == maxScroll`
  again short-circuits (line 582-584). No animation.
- **Otherwise** the animator *does* start (lines 587-607) and scrolls to
  `maxScroll`. So the gate falling through after 6 retries is **not** by itself
  fatal; the fatal part is that for a short tab, `maxScroll` frequently equals
  the current `scrollX`, tripping the no-op guard.

So the resulting `maxScroll` is *arithmetically correct for the settled
geometry* — the flaw is not a wrong `maxScroll` value; it is that the 6-retry
stall and the `startX == maxScroll` short-circuit combine so that a short tab's
end-scroll either (a) is a legitimate no-op because nothing overflows, or (b) is
skipped/delayed long enough to be superseded. **Hypothesis point 3: the maxScroll
value is correct; the animator may or may not start; and `startX == maxScroll`
is the concrete no-op path.**

---

## 6. HorizontalScrollView vs container measured-width divergence, and the retry stall

**Hypothesis point 4:** yes, the two views differ and this matters.

- `mTabsScroll.getWidth()` (viewport, `0dp`+weight=1) is essentially constant.
- `mTabsContainer.getMeasuredWidth()` (content, `wrap_content`) varies with tab
  labels.

The gate compares two *content* measurements (`newWidth` vs `widthBefore`), both
taken from `mTabsContainer`. That comparison is only meaningful if `widthBefore`
was captured **before** the growth — which it was not (§3). So the gate is
comparing the settled width to itself.

The 6-retry stall has a second-order consequence. During those (up to 6)
`onGlobalLayout()` passes where the listener keeps returning, other scroll
requests can be posted and run. In particular:

- `onSessionPageSelected()`
  (`TermuxTerminalSessionActivityClient.java:434-478`) calls `updateTabs()`
  again at line 462.
- The pager's `onPageScrolled()` (`TermuxSessionTabsController.java:879`) issues
  instant `scrollTo()` calls (line 919) — these are suppressed while
  `mEndScrollActive` is true (lines 882-886), which is the mitigation, but the
  stall widens the window in which sequence/state races can leave `scrollX` at a
  value that then trips the `startX == maxScroll` no-op.

For a **long** tab the gate usually passes on the *first* callback (§4), so this
stall window is essentially zero and the animation runs cleanly. For a **short**
tab the gate stalls for the full 6 passes, maximizing the chance that (a)
nothing overflows, or (b) `scrollX` already equals `maxScroll`.

---

## 7. Root-cause assessment

**Yes — the width-gate `newWidth <= widthBefore` is a genuine contributor to the
short-tab failure, and its logical flaw is precise:**

> `widthBefore` is captured *after* the new tab has already been inserted into
> `mTabsContainer` (the `addView()` at `updateTabs():140` runs synchronously,
> before the `post()`-deferred `runEndScroll()` at line 544 executes). The gate's
> premise — "wait until `newWidth` grows past the pre-add width" — is therefore
> false: the width has *already* grown. In the steady state `newWidth ==
> widthBefore`, so the gate condition `newWidth <= widthBefore` is **true on
> every layout pass**, the listener burns all 6 retries, and only then falls
> through.

This flaw is **width-symmetric in principle** (it applies to both short and long
tabs), but the *symptom* is width-asymmetric because of a compounding second
factor:

1. **Timing:** a long label is more likely to catch line 544 during an
   in-progress remeasure (so `widthBefore` is stale/smaller and the gate passes
   early and correctly); a short label's tiny width change lets the strip settle
   before line 544, so `widthBefore` is already final and the gate never passes
   early.
2. **Geometry:** after the gate falls through, `maxScroll = childMeasuredW -
   scrollW`. A short tab often leaves `childMeasuredW <= scrollW` (`maxScroll ==
   0`) or leaves `maxScroll` equal to the current `scrollX`, so the
   `startX == maxScroll` guard at line 582-584 short-circuits and **no animator
   starts** — the strip visibly "stays put".

### Minimal logical flaw (one sentence)

`widthBefore` is measured *after* `addView()` (post-`post()`), so the gate
`newWidth <= widthBefore` can only ever be satisfied by a *transient*
under-measured pass; for a short tab that never happens, so the listener stalls
for 6 retries and then frequently exits via the `startX == maxScroll` no-op — the
gate is comparing the already-grown width against itself.

### Why the current design masks this for long tabs

The APPEARING LayoutTransition is disabled (constructor lines 63-72) so tabs are
inserted at full width and the geometry "settles within one layout pass". This is
what makes the *long*-tab path work — the strip is already at final width and
`maxScroll` is clearly positive — but it is *also* exactly what defeats the gate
for short tabs: since there is no width-growth animation, `newWidth` never
transiently rises above `widthBefore`, so the "wait for growth" gate can never
succeed and always exhausts its retries.

---

## 8. Precise line-number index

| Concern | File:line |
|---|---|
| `mTabsContainer` = inner wrap_content strip | `TermuxSessionTabsController.java:54` |
| `mTabsScroll` = 0dp+weight viewport | `TermuxSessionTabsController.java:55` |
| New tab inserted at full width (APPEARING disabled) | constructor `63-72`; `addView` at `140` |
| `updateTabs()` add branch → `scrollStripToEnd()` | `134-141`, `173-179` |
| `scrollStripToEnd()` sets `mEndScrollActive`, requests SCROLL_END | `724-731` |
| `requestScroll()` posts `mScrollRunnable` | `615-629` |
| `mScrollRunnable` → `runEndScroll()` | `490-506` |
| **`widthBefore` captured AFTER add** (comment wrong) | **`542-544`** |
| Global-layout listener registered | `546-611` |
| **Width-gate `newWidth <= widthBefore` + 6-retry stall** | **`556-562`** |
| `childMeasuredW` / `scrollW` / `maxScroll` compute | `570-572` |
| **`startX == maxScroll` no-op exit** | **`581-585`** |
| Self-driven animator start | `587-607` |
| Deferred label-driven end-scroll (short tab OSC) | `TermuxTerminalSessionActivityClient.java:173-194` |
| `markPendingEndScrollSession()` + 250ms fallback | `TermuxTerminalSessionActivityClient.java:202-207` |

---

## 9. Conclusion

The width-gate is a real bug and a primary cause of the short-tab symptom. Its
minimal logical flaw is that `widthBefore` is sampled **after** the tab has been
added (because `runEndScroll()` runs on a posted runnable that fires after the
synchronous `addView()` and its measure pass), so `newWidth <= widthBefore` is
essentially always true for a short tab. The gate then stalls 6 retries and
exits, and because a short tab adds negligible content width, the subsequent
`startX == maxScroll` guard treats the (already-settled) geometry as "nothing to
scroll" — no animation runs. A long tab escapes this two ways: it is more likely
to pass the gate early on a genuinely under-measured pass, and it produces a
`maxScroll` clearly greater than the current `scrollX`.

A correct fix would (a) not gate on a self-referential `newWidth > widthBefore`
comparison at all — since the tab is inserted at full width the geometry is
already final by the time `runEndScroll()` runs — or (b) capture `widthBefore`
*synchronously in `updateTabs()` before `addView()`* and pass it into
`runEndScroll()`, so the "did it grow" check is meaningful. Given design note at
lines 60-62 that the strip "settles within one layout pass", option (a)
(drop the width-gate, compute `maxScroll` directly, and drive the animator
whenever `startX != maxScroll`) is the smaller, more robust change. This analysis
is diagnostic only; no changes were made.

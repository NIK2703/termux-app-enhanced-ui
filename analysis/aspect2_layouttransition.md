# Aspect 2 — LayoutTransition / Width-Measurement Diagnostic

**Project:** Termux app fork (`termux-app-ui-improve`, Android/Java)
**File under analysis:** `app/src/main/java/com/termux/app/terminal/TermuxSessionTabsController.java`
**Symptom:** When a NEW terminal tab is created, the tab-strip auto-scroll to the right end (revealing the `+` add button) fires **only** when the new tab's label is LONG/maximum width. With a SHORT label (e.g. `~`) the right-end scroll animation does NOT happen.
**Mode:** READ-ONLY diagnostic. No files modified.

---

## 1. How an add reaches `runEndScroll()`

1. `updateTabs()` detects growth at **L134** (`if (newCount > sessionCount)`) and inserts the new tab view(s) at **L137–L141** via `mTabsContainer.addView(tabView, mTabsContainer.getChildCount() - 1)` — i.e. inserted *before* the `+` button, at full measured width (the constructor comment at **L57–L62** explains why APPEARING is disabled: the tab is added at full width so geometry settles in one pass).
2. If it is the first build, `requestScroll(SCROLL_CENTRE, …)` is issued (**L171**); otherwise, because `newCount > sessionCount`, `scrollStripToEnd()` is called (**L179**).
3. `scrollStripToEnd()` (**L724–L731**) sets `mEndScrollActive = true` (**L727**) and `requestScroll(SCROLL_END, -1)` (**L730**).
4. `requestScroll()` (**L615–L629**) posts `mScrollRunnable` (**L490–L506**) which, for `SCROLL_END`, calls `runEndScroll()` (**L501**, defined **L536–L612**).

The end-scroll never uses `HorizontalScrollView.smoothScrollTo()` (see the CRITICAL comment at **L513–L522**); instead it self-drives `scrollTo(fixedTarget)` through a `ValueAnimator` to avoid the per-frame re-clamp that caused the original under-scroll bug.

---

## 2. The width-gate in `runEndScroll()`

```java
L544:  final int widthBefore = mTabsContainer.getMeasuredWidth();
...
L550:  public void onGlobalLayout() {
...
L556:      final int newWidth = mTabsContainer.getMeasuredWidth();
L559:      if (newWidth <= widthBefore && mRetries < 6) {
L560:          mRetries++;
L561:          return;                 // <-- wait / retry
L562:      }
L563:      mTabsContainer.getViewTreeObserver().removeOnGlobalLayoutListener(this);
...
L570:      final int childMeasuredW = mTabsContainer.getMeasuredWidth();
L571:      final int scrollW = mTabsScroll.getWidth();
L572:      final int maxScroll = Math.max(0, childMeasuredW - scrollW);
```

The listener **only scrolls once `newWidth > widthBefore`** (or after 6 retries). `widthBefore` is captured at **L544** *before* the `OnGlobalLayoutListener` is attached (**L611**), i.e. it reflects the container width as seen at the moment `runEndScroll()` runs (the next layout pass after the `mScrollRunnable` posted at **L627**).

### setLayoutTransition(null) timing

```java
L577:  final android.animation.LayoutTransition saved = mTabsContainer.getLayoutTransition();
L579:  mTabsContainer.setLayoutTransition(null);
...
L598:  if (mTabsContainer != null) mTabsContainer.setLayoutTransition(saved);   // end
L604:  if (mTabsContainer != null) mTabsContainer.setLayoutTransition(saved);   // cancel
```

**`setLayoutTransition(null)` is called only at L579 — i.e. AFTER the width-gate has already passed (L559/L562) and AFTER the listener has removed itself (L563).** During the entire retry wait loop the LayoutTransition is **still active**.

---

## 3. Answers to the analysis questions

### Q1 — For a *short* tab, is the CHANGING transition still animating neighbours / width during the listener's passes?

Yes. The constructor (**L63–L72**) enables **only** `LayoutTransition.CHANGING` with `setDuration(220)` (**L69**) and an `AccelerateDecelerateInterpolator` (**L70–L71**). CHANGING fires whenever any child's bounds change *without* an add/remove transition — and inserting a new child at full width at **L140** changes the bounds of every subsequent child (the other tabs and the `+` button are pushed right), so the container's measured width **does** change and the CHANGING animator **does** animate that reflow over 220 ms.

Crucially, the comment at **L57–L62** justifies disabling APPEARING precisely because APPEARING "animate[d] a newly-added tab's width 0->full" and "made the scrollable width unstable for ~220ms after an add." **But the CHANGING transition is still enabled, and it occupies the same ~220 ms window and also mutates the layout width** — the neighbours slide over 220 ms, so `getMeasuredWidth()` continues to change during that interval. The fix disabled APPEARING but left CHANGING, which still produces a 220 ms window of non-settled width.

### Q2 — Does `getMeasuredWidth()` reflect the *current, mid-transition* width; does a short tab change the total width?

`getMeasuredWidth()` (**L544**, **L556**, **L570**) returns the layout's **currently committed measured width**, which during a running CHANGING animation is the *animated* (interpolated) width, not the settled final width.

For a **short** tab (`~`):
- The new view adds only a small width (a `~` label is ~tens of px).
- During the CHANGING glide, neighbours are *still sliding*; their positions change but, because the strip is `wrap_content` and the new tab is narrow, the **total settle delta is tiny** — and it can even be **≤ 0** if, for example, the trailing `+` button simultaneously toggles `GONE`→`VISIBLE`/padding or if a prior transient width was already near-final.
- Net effect: `newWidth` frequently satisfies `newWidth <= widthBefore` while the animation is in flight (or never exceeds it by a meaningful amount).

For a **long** tab:
- The new view adds a large width; total container width grows substantially and monotonically, so `newWidth > widthBefore` becomes true quickly and the gate opens.

This is exactly the asymmetry reported: long tab → gate opens → scroll; short tab → gate may never see growth → fails through.

### Q3 — During the width-gate wait, is the transition still active, so measured width is transitioning?

**Yes.** `setLayoutTransition(null)` is at **L579**, reached *after* the gate (`L559`) and after listener removal (`L563`). During the retry loop (`L559–L562`) the CHANGING transition is fully active and still animating. So every `getMeasuredWidth()` read at **L556** is the **mid-transition width**, not the settled width. The null-out at L579 — intended to "detach the CHANGING LayoutTransition so the content width cannot shrink mid-animation" (**L574–L576**) — happens **too late** to help the gate; it only protects the scroll *execution* phase (L587–L607), by which point the wrong `maxScroll` may already have been computed from a stale/transitional width.

### Q4 — Does the 220 ms CHANGING duration interact badly with the 6-retry (layout-pass) bound?

The retry bound is **`mRetries < 6`** at **L559** — a *count of layout passes*, **not** time-based. `onGlobalLayout()` fires on each layout pass, but layout passes are throttled by the framework and are **not guaranteed to occur 6 times within 220 ms**. Two failure modes:

- **Transition completes (220 ms) before 6 passes occur:** Once CHANGING finishes, `getMeasuredWidth()` is frozen at whatever the *settled* width is. If the settled width happens to be ≤ `widthBefore` (short tab, tiny/negative delta), every remaining layout pass keeps hitting `newWidth <= widthBefore`, and after 6 passes the gate falls through. The listener then scrolls to a `maxScroll` computed from a width that was always ≤ `widthBefore` — i.e. it scrolls to a position that **does not reveal the new short tab / `+` button**, or scrolls a negligible distance that looks like "no animation."
- **Transition still running but all 6 passes report ≤ widthBefore:** same fall-through with a stale width.

So the 6-retry count and the 220 ms animation duration are **decoupled and incompatible**: the retry loop can either exhaust before the transition settles, or settle at a wrong (non-growing) width that the gate is designed to reject — but the rejection logic (`newWidth <= widthBefore`) has no notion of "settled but didn't grow," so it silently treats a *correctly settled short width* as "not ready yet" and gives up after 6.

### Q5 — Long vs short tab comparison

| | Long tab | Short tab (`~`) |
|---|---|---|
| Width delta from add | Large, positive | Tiny / zero / negative |
| `newWidth > widthBefore` | True quickly → gate opens (L559 passes) | Often never true → retries until 6 → fall-through (L559→L562) |
| `maxScroll` computed | Correct, reveals `+` | Stale/`≤` → scroll too short or no-op |
| Observed behaviour | Right-end scroll fires | Right-end scroll does NOT fire |

This matches the symptom exactly: the bug is **not** random — it is deterministically correlated with the new tab's width, because the gate's success condition is itself width-delta dependent.

---

## 4. Root-cause conclusion

**The LayoutTransition setup/lifetime IS a contributing cause** (together with the width-gate design):

1. **CHANGING is still enabled** (L64) and still animates neighbour reflow for ~220 ms after an add (L69). The constructor's rationale (L57–L62) only disabled APPEARING/CHANGE_APPEARING; it left CHANGING, which equally produces a ~220 ms window of unstable `getMeasuredWidth()`. The comment's claim that "Adding a tab at its full measured width lets the strip geometry settle within one layout pass" is **only true for the added view's own width, not for the container's total width**, which is perturbed by the CHANGING-driven neighbour slide.
2. **`setLayoutTransition(null)` is applied too late** — at L579, *after* the width-gate (L559) and after listener removal (L563). During the gate wait the transition is live, so the measured widths feeding the gate (L544/L556/L570) are transitional, not settled.
3. **The gate condition `newWidth > widthBefore` (L559) is width-delta dependent**, so it passes for long tabs and fails (after 6 retries) for short tabs — the precise asymmetry in the symptom.
4. **The 6-retry bound is layout-pass-count based (L559)**, decoupled from the 220 ms animation, so the transition can settle (or not settle enough) independently of the retry budget, freezing `maxScroll` at a wrong value.

Note: this is the *proximate* mechanism. The deeper design flaw is using a *relative* growth gate (`> widthBefore`) instead of waiting for the transition to *finish*, plus relying on `getMeasuredWidth()` while a CHANGING transition is live.

---

## 5. Recommended correct interaction

1. **Wait for the transition to finish, not for "width grew."** The code already has the right primitive elsewhere — `scrollToTabIndex()` uses `transition.isRunning()` + `addTransitionListener`/`endTransition` (**L739–L756**). Replicate that in `runEndScroll()`: if `mTabsContainer.getLayoutTransition() != null && isRunning()`, register an `endTransition` listener and only then attach the `OnGlobalLayoutListener` to compute the target. This makes the wait **time/transition-based**, eliminating the APPEARING/CHANGING-induced inconsistency entirely.

2. **Null out the LayoutTransition BEFORE measuring.** Move `setLayoutTransition(null)` (currently L577–L579) to *before* the gate/measure phase (i.e. before L544 or at the very start of the listener), and restore it in `onAnimationEnd`/`onAnimationCancel` (L598/L604) as today. That guarantees every `getMeasuredWidth()` read (L544, L556, L570) sees the **settled** width, never a transitional one. Better yet, combine with recommendation #1 so the transition is already finished.

3. **Measure against a settled width, not a relative delta.** Replace `newWidth <= widthBefore` (L559) with "is the transition running OR width not yet final." If you must keep a gate, base it on `transition.isRunning()` clearing rather than `> widthBefore`, so a legitimately short tab (which correctly produces a small/zero delta) is not mistaken for "not ready."

4. **Optionally, suppress CHANGING during the add path too.** The constructor enables CHANGING primarily for the *close* animation reflow (L57–L62, and the close path at L342–L454 drives width itself and explicitly suppresses the container transition at L407–L409). For the *add* END-scroll path, CHANGING provides no benefit (the added tab is already full width) — so detaching CHANGING around the add, as the code already does at L579 for the scroll phase, should be extended to the *measure* phase as well.

**Minimal, safe change set:** (a) move `setLayoutTransition(null)` to before measurement; (b) gate on `isRunning()` rather than `> widthBefore`; (c) keep the restore in end/cancel callbacks. This removes the long/short width asymmetry without touching the close-animation behaviour.

---

## 6. Exact line references

- CHANGING enabled, APPEARING/CHANGE_APPEARING/DISAPPEARING/CHANGE_DISAPPEARING disabled, duration 220, AccelerateDecelerate interp: **L63–L72** (rationale comment **L57–L62**).
- Add path inserts tab at full width before `+` button: **L134–L141**.
- `scrollStripToEnd()` sets `mEndScrollActive` and requests END scroll: **L724–L731** (call site **L179**).
- `widthBefore` captured: **L544**.
- Width-gate `newWidth <= widthBefore && mRetries < 6`: **L559–L562**.
- `setLayoutTransition(null)` (too late): **L577–L579**; restore in end/cancel: **L598**, **L604**.
- `maxScroll` computed from `getMeasuredWidth()`: **L570–L572**.
- Self-driven scroll anim (target frozen at L572): **L587–L607**.
- Reference pattern for transition-finish waiting: **L739–L756** (`scrollToTabIndex`).
- Close path suppresses container transition: **L407–L409**.

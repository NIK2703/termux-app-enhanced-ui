# Aspect 8 — Root-Cause Synthesis: tab-strip end-scroll starves for SHORT-label new tabs

**File under analysis:** `app/src/main/java/com/termux/app/terminal/TermuxSessionTabsController.java`
**Secondary:** `app/src/main/java/com/termux/app/terminal/TermuxTerminalSessionActivityClient.java`
**Mode:** READ-ONLY synthesis. No files were modified.

---

## 1. Single root cause

The end-scroll's measurement gate in `runEndScroll()` **assumes the container width will GROW** after the new tab is added (it waits for `newWidth > widthBefore`). For a SHORT-label new tab, the real title arrives via `onTitleChanged()` and **shrinks** the tab (and no subsequent layout pass increases width), so the gate either exhausts its 6 retries with a `newWidth <= widthBefore` reading and then proceeds with a stale/equal width where `startX == maxScroll` (early return, no animation), or the `OnGlobalLayoutListener` simply never fires again after the shrink — in both cases the strip is **left unscrolled**. The growth assumption is wrong for the short-label path.

---

## 2. Exact defect locations (line numbers)

- **Primary defect — the width-gate growth assumption:**
  `TermuxSessionTabsController.java:559`
  ```java
  if (newWidth <= widthBefore && mRetries < 6) {   // expects GROWTH after add
      mRetries++;
      return;
  }
  ```
  Captured baseline: `widthBefore = mTabsContainer.getMeasuredWidth()` at `:544`.
  The gate only proceeds once the container *grows*. A short label makes the final width **shrink or stay equal**, so the pass either keeps looping until `mRetries == 6` (then measures a possibly-equal width) or never re-fires.

- **Secondary defect — early return when already "at end":**
  `TermuxSessionTabsController.java:582`
  ```java
  if (startX == maxScroll) { ... return; }   // no scroll if startX already == maxScroll
  ```
  Because the strip never moved (no growth → no new layout → scrollX unchanged) and `maxScroll` is computed from a width that never grew, `startX` can equal `maxScroll`, producing a silent no-op.

- **Contributing defect — deferred-on-title ordering in `onTitleChanged()`:**
  `TermuxTerminalSessionActivityClient.java:187-189`
  ```java
  if (tabs != null) tabs.scrollStripToEnd();   // :187  ← arms the END scroll FIRST
  termuxSessionListNotifyUpdated();             // :189  ← THEN shrinks the label
  ```
  `scrollStripToEnd()` (`:724`) captures `widthBefore` **before** `termuxSessionListNotifyUpdated()` → `updateTabs()` repopulates the title and shrinks the tab. So when the gate at `:559` later runs, the container has only *already shrunk* since the capture — it can never observe the required growth.

---

## 3. Minimal correct fix (and which is most robust)

### Option (c) — invert the ordering in `onTitleChanged()` *(simplest, but weakest)*
Call `termuxSessionListNotifyUpdated()` **first**, then `scrollStripToEnd()`. This lets the label settle so `widthBefore` (captured inside `runEndScroll()` via `:544`) reflects the *final* width. However, `runEndScroll()` still relies on a layout pass that **grows** the width relative to `widthBefore`; if the settled final width happens to equal the pre-add width (e.g. short label replacing an equally-wide default), the gate can still no-op. So (c) alone does **not** fully fix the growth assumption — it merely moves the race.

### Option (b) — measure `maxScroll` from the last child's right edge, not container-growth *(most robust)*
Replace the growth-gated wait with a deterministic target:
```java
final int lastIdx = mTabsContainer.getChildCount() - 1;   // last real tab (excl. (+) button)
final View lastTab = mTabsContainer.getChildAt(lastIdx);
final int target = lastTab.getRight() + mTabsContainer.getPaddingRight();
final int maxScroll = Math.max(0, target - mTabsScroll.getWidth());
```
Then scroll to `maxScroll` after **one** layout pass (or unconditionally), with no `newWidth <= widthBefore` gate. `getRight()` of the last tab is the authoritative right edge regardless of whether the container grew or shrank — it is measured *after* the label settles, so a short label still yields the correct, smaller `maxScroll` and the strip scrolls to the true end. This decouples the scroll target from the fragile "wait for growth" heuristic.

### Option (a) — bounded wait without requiring growth *(partial)*
In `runEndScroll()`, after capturing `widthBefore`, wait a **fixed number of layout passes** (e.g. 2–3) or a single post, then measure — never require `newWidth > widthBefore`. This works but still trusts `getMeasuredWidth()` of the container and the occurrence of *any* layout pass; on the short-label path the needed layout pass does occur (the shrink itself triggers one), so (a) is sufficient in practice but less deterministic than (b)'s right-edge read.

**Recommended:** **Option (b)** as the core fix (deterministic, one layout pass, no growth assumption), optionally paired with the ordering swap from (c) for cleanliness. This is the most robust because it computes the scroll target from the actual last-tab geometry rather than from a fragile relative-growth signal.

---

## 4. Risks of each option

- **(c) ordering swap only:** Does **not** fix the root assumption; a short label equal in width to the default still no-ops at `:559`/`:582`. Risky as a stand-alone fix.

- **(b) last-child `getRight()`:** Most deterministic. Minor risk: if called before the container has laid out the new tab, `getRight()` may read 0/stale — mitigated by keeping the single deferred layout pass already in `runEndScroll()` (`:550`). Must still exclude the trailing (+) button (child index `childCount-1`) and add the container's end padding so the (+) button is fully revealed.

- **(a) bounded wait without growth gate:** Sufficient for the short-label case (the shrink triggers a layout pass) but still depends on `getMeasuredWidth()` semantics and on a layout pass actually firing; slightly less robust than (b) on devices/configs where the shrink is absorbed without a new layout pass.

**Conclusion:** The width-growth gate at `:559` (plus the `startX == maxScroll` early-out at `:582` and the premature `scrollStripToEnd()` → `termuxSessionListNotifyUpdated()` ordering at `onTitleChanged():187-189`) is the root cause. The minimal robust fix is **(b)**: measure `maxScroll` from the last real tab's `getRight()` after one layout pass, dropping the growth requirement.

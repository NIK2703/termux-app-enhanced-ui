# Aspect 7 — Add-path equivalence & (+) button / placeholder interplay in the right-end scroll

**Project:** Termux app fork (`/data/local/projects/termux-app-ui-improve`, Android/Java)
**Symptom:** New tab created via **(+) tap** OR **right-swipe placeholder commit** → auto-scroll to right end fires only when the new tab's label is **LONG**. With a **SHORT** label (`~`) the end-scroll does **NOT** happen.
**Mode:** READ-ONLY diagnostic. No files modified.

---

## 1. Both add paths funnel through the *same* deferred-on-title mechanism

### Path (a): (+) button tap  → `addNewSession()`
`TermuxTerminalSessionActivityClient.addNewSession(boolean, String)` — `TermuxTerminalSessionActivityClient.java:532-594`:

1. L549 `service.createTermuxSession(...)` creates the session. This internally fires `termuxSessionListNotifyUpdated()` → `updateTabs()` → because `newCount > sessionCount`, the `else if (newCount > sessionCount)` branch at `TermuxSessionTabsController.java:173-179` calls `scrollStripToEnd()` **immediately** (label still default/"Terminal").
2. L592 `setCurrentSession(newTerminalSession)` → pager `setCurrentItem(index, true)` → settle → `onSessionPageSelected` → `updateTabs()` (no size change) and finally the shell emits an OSC title → `onTitleChanged()` at `TermuxTerminalSessionActivityClient.java:173-194`.

Note: there is **no `markPendingEndScrollSession`** call on this path. The end-scroll is requested synchronously inside `updateTabs()` at `TermuxSessionTabsController.java:179` the moment the tab is structurally added, *before* the label is set. `mEndScrollActive` is set true by `scrollStripToEnd()` (`TermuxSessionTabsController.java:727`).

### Path (b): right-swipe placeholder commit → `commitPlaceholderToSession()`
`SessionPagerManager.commitPlaceholderToSession()` — `SessionPagerManager.java:326-386`:

1. L342 `client.createSessionForPlaceholder(false, null)` (returns, does NOT scroll pager). `createSessionForPlaceholder` → `service.createTermuxSession()` → `termuxSessionListNotifyUpdated()` fires, but because the adapter still counts the placeholder, `getItemCount()` already equals the new size so `termuxSessionListNotifyUpdated` skips the adapter rebuild (`SessionPagerManager.java:643`). **No `updateTabs()` size-change branch runs here.** (Critical difference — see §4.)
2. L354 `tabs.setEndScrollReserved(true)` (sets `mEndScrollActive=true`, `TermuxSessionTabsController.java:814`).
3. L356 `client.markPendingEndScrollSession(newSession.getTerminalSession())` (`TermuxTerminalSessionActivityClient.java:202-207`) sets `mPendingEndScrollSession` + 250 ms fallback `mEndScrollFallback`.
4. The actual `scrollStripToEnd()` is deferred until `onTitleChanged()` fires for this session (`TermuxTerminalSessionActivityClient.java:182-190`) **or** the 250 ms fallback (`TermuxTerminalSessionActivityClient.java:83-89`) → which calls `tabs.scrollStripToEnd()`.

### Conclusion on equivalence
- **Both paths converge on `scrollStripToEnd()` → `requestScroll(SCROLL_END)` → `runEndScroll()`** (`TermuxSessionTabsController.java:724, 615, 536`). Same single-owner, same sequence guard, same geometry-measurement routine.
- **They differ only in *when* `scrollStripToEnd()` is invoked:**
  - (+) path: synchronously at add time (label still default), via `updateTabs()` `TermuxSessionTabsController.java:179`.
  - swipe path: deferred to `onTitleChanged`/fallback, after the real label is set.
- This timing difference is material for the short-tab symptom (see §3–§4). The label-length gate described in the brief is **not** a code-level width branch; it is an *emergent* effect of `runEndScroll`'s `widthBefore` retry gate combined with the title-shrink timing (Aspect 3). There is **no** `if (label.length …)` branch anywhere.

---

## 2. The (+) button width in the end-scroll target

`updateAddButtonVisibility(int)` — `TermuxSessionTabsController.java:198-209`:
```java
addBtn.setVisibility(sessionCount >= MAX_SESSIONS ? View.GONE : View.VISIBLE);
```
- Called at the **end** of `updateTabs()`, `TermuxSessionTabsController.java:190` — *after* the `scrollStripToEnd()` request at L179 was already queued.
- `runEndScroll()` measures `childMeasuredW = mTabsContainer.getMeasuredWidth()` (`TermuxSessionTabsController.java:570`). Because the (+) button (last child, `R.id.new_session_tab_button`) is **always present in the container** (only its *visibility* flips to GONE at the MAX_SESSIONS limit), its width + `marginEnd` is included in `getMeasuredWidth()` **unless** it is currently GONE.

For the documented symptom (few tabs, not at limit) the (+) button is VISIBLE, so `childMeasuredW` already includes it — confirming the brief's point that `maxScroll` must (and does) include the (+) button width. So the (+) button is **not** what flips short vs long behaviour.

---

## 3. Where label length actually matters — `runEndScroll` width-gate

`TermuxSessionTabsController.java:536-562`:
```java
final int widthBefore = mTabsContainer.getMeasuredWidth();   // L544
...
final int newWidth = mTabsContainer.getMeasuredWidth();      // L556
if (newWidth <= widthBefore && mRetries < 6) {              // L559
    mRetries++;
    return;            // wait for container to grow
}
```

- `widthBefore` is snapshotted at the moment `runEndScroll()` runs (inside the deferred `OnGlobalLayoutListener`).
- The gate **delays** the scroll until the container has grown (`newWidth > widthBefore`), bounded by 6 retries.
- The decisive question for the symptom: **does a SHORT new tab ever fail to grow the container beyond `widthBefore`?**

Relevant mechanics:
- **(+) path:** `scrollStripToEnd()` is requested *during* the add, while the default-label tab is already inserted. The tab view is added at `WRAP_CONTENT` full width (`TermuxSessionTabsController.java:140`, APPEARING transition disabled at L65). So `getMeasuredWidth()` grows by the **new tab's measured width at add time** (default "Terminal" label, fairly wide). `widthBefore` was captured *before* this layout, so `newWidth > widthBefore` is satisfied and the scroll fires. The label later shrinks ("~") *after* the scroll already ran — irrelevant to whether it ran. So on the **(+) path a short final label should still scroll**, because the gate saw the wide default label's growth. This means the (+) path is **less** likely to exhibit the bug than the swipe path.

- **swipe path:** `scrollStripToEnd()` runs **after** `onTitleChanged` — i.e. *after* the new session's real (possibly short `~`) title has already been painted. By the time `runEndScroll()` snapshots `widthBefore` (L544), the tab already has its final short width. Now consider the **(+) button GONE toggling** and the **title-shrink**:
  - If, between the tab insertion (wide default) and `onTitleChanged` (short `~`), the container was laid out wide, then the title shrinks the container (narrow tab). `widthBefore` captured at `runEndScroll` time reflects the already-narrow state. The gate `newWidth <= widthBefore` just waits for another layout; it does not *fail*. So the gate alone does not suppress the scroll.
  - The actual suppression, consistent with the brief, is the **`startX == maxScroll` early-return** at `TermuxSessionTabsController.java:582-585`: if the strip is already at (or past) `maxScroll` for the *narrow* measured width, `runEndScroll` returns without animating. For a short label the container is narrower → `childMeasuredW` smaller → `maxScroll` smaller. If the existing tabs already pushed `scrollX` to (or near) that smaller `maxScroll`, **no further scroll is needed and the code (correctly) does nothing** — but this is equally true for long labels as long as `childMeasuredW` exceeds viewport. The brief's claim that LONG works while SHORT doesn't therefore implies `childMeasuredW` *does* exceed the viewport in both cases.

### Resolving the paradox
The brief's hypothesis #3 ("width should be similar; only the width-gate / shrink timing differs") holds **only if** the new tab is added *before* `widthBefore` is captured. That is true for the **(+) path** (scroll queued at add, gate sees growth) but **NOT** guaranteed for the **swipe path** (scroll deferred until after title shrink). Therefore:

- **(+) tap path:** end-scroll effectively fires regardless of final label length (it saw the wide default insert). Bug is *masked*.
- **right-swipe path:** end-scroll is scheduled *after* the label is already final. If the final label is short, `runEndScroll`'s `widthBefore`/`maxScroll` reflect the slim container; when combined with the title-shrink having already reduced total width, `maxScroll` can drop to ~`startX` → early return at L582 → **no visible scroll on a short tab, while a long tab still needs (and gets) one.**

This makes the two add paths **NOT identically affected** under the symptom, contrary to the brief's framing: the (+) path is far more robust because it captures `widthBefore` before the title shrinks.

---

## 4. Extra `updateTabs()` on the (+) path vs swipe path

Brief asked: *"When adding via (+) button tap, is there an extra `updateTabs()` call that re-populates and shrinks the label BEFORE `onTitleChanged`?"*

Trace:
- (+) tap: `createTermuxSession` (L549) → `termuxSessionListNotifyUpdated()` → `updateTabs()` **with size change** → `scrollStripToEnd()` at `TermuxSessionTabsController.java:179`. Then `setCurrentSession` (L592) → `onSessionPageSelected` → `updateTabs()` **again, no size change** (`TermuxTerminalSessionActivityClient.java:462`). Then the shell OSC → `onTitleChanged`.
  - So yes: the label is (re)populated by the first `updateTabs()` while still default, and again by the `onSessionPageSelected` `updateTabs()` — but the **end-scroll already fired** at the first size-change `updateTabs()`. The subsequent shrink happens *after* the scroll decision → does not affect it.
- swipe: `createSessionForPlaceholder` → `termuxSessionListNotifyUpdated()` is a **no-op** for the adapter (`SessionPagerManager.java:643`) and therefore does **not** trigger a size-change `updateTabs()` that would call `scrollStripToEnd()`. The only `updateTabs()` for the new tab's geometry happens later when the placeholder is committed/rebound and the title is set — i.e. the scroll is deferred to `onTitleChanged` where the label is already final.

This asymmetry **confirms** §3: the (+) path gets its `scrollStripToEnd()` while the tab is still wide; the swipe path gets it after the tab is already narrow. Hence the short-label failure is specific to (or far more pronounced on) the swipe path, while the (+) path generally scrolls regardless of label length.

---

## 5. Does the new tab width change whether the (+) button stays in viewport?

- The (+) button is the last child and is VISIBLE (not at MAX_SESSIONS). Whether the *new tab* is short or long, the (+) button's own width is constant. The container's total width is `Σ(existing tabs) + newTabWidth + (+)buttonWidth (+ paddings)`.
- A short new tab reduces `childMeasuredW` by `longWidth − shortWidth`. If `childMeasuredW` still exceeds `scrollW`, `maxScroll > 0` and a scroll is required for **both** short and long — so purely from the (+) button perspective, short vs long should not change the *need* to scroll.
- The **only** way short-vs-long changes behaviour is the timing of `widthBefore` capture (§3): on the swipe path the scroll is measured *after* shrink, so a short tab yields a smaller `maxScroll`; if the strip was already near that smaller `maxScroll` (because few prior tabs already pushed scrollX close to it), `startX == maxScroll` early-returns (L582). A long tab yields a larger `maxScroll`, so `startX < maxScroll` and the scroll runs. This is the label-length dependence, and it is an artifact of **measuring after shrink**, not of the (+) button width per se.

---

## 6. Placeholder interplay with the strip blend

- `setPlaceholderActive(boolean)` (`TermuxSessionTabsController.java:803-805`) only affects `onPageScrolled` blending (`TermuxSessionTabsController.java:906-907`): while the placeholder is active, the last real tab may blend into the (+) button during the swipe. It does **not** change `runEndScroll` geometry or the short/long gate.
- `commitPlaceholderToSession()` clears placeholder (`SessionPagerManager.java:347`) **before** `setEndScrollReserved(true)` (L354) and `markPendingEndScrollSession` (L356). So by the time `onTitleChanged` triggers `scrollStripToEnd`, the placeholder is already inactive and `mPlaceholderActive` is false — the blend branch is inert. The placeholder thus does **not** alter the right-end scroll target; it only governs the intermediate finger-follow during the commit swipe, which is separately suppressed by `mEndScrollActive` (`TermuxSessionTabsController.java:882-891`).

---

## 7. Answers to the brief's three focus questions

1. **Are both paths equally affected?** No. Both *originate* from `scrollStripToEnd()` (same `runEndScroll`), but they differ in *when* it is invoked. The **(+) tap** path requests the scroll synchronously at add time (tab still wide) → robust to short labels. The **right-swipe** path defers the scroll to `onTitleChanged`/fallback (tab already shrunk to `~`) → susceptible to the `maxScroll == startX` early-return on a short tab. The reported symptom (short label fails) is therefore primarily a **swipe-path** failure; the (+) path masks it.

2. **Is the (+) button width involved?** Not as a cause. The (+) button is always a child of `mTabsContainer` and is VISIBLE below MAX_SESSIONS, so its width is already part of `childMeasuredW` (`TermuxSessionTabsController.java:570`). `updateAddButtonVisibility` (L190, after the scroll is queued) does not change behaviour for the symptom's conditions. The relevant constant is the **new tab's own width**, measured *after* the title shrinks on the swipe path.

3. **Does short vs long change whether the (+) button stays in viewport?** Only indirectly, via `maxScroll` magnitude. A short tab → smaller `childMeasuredW` → smaller `maxScroll`. If `startX` already ≈ that smaller `maxScroll`, `runEndScroll` early-returns (L582) and no scroll occurs; a long tab → larger `maxScroll` → scroll runs. This is the mechanism that makes label length appear to gate the end-scroll, and it is specific to the **post-shrink measurement** on the swipe path.

---

## 8. Root-cause summary

- Primary: **`runEndScroll()` snapshots `widthBefore` (L544) and computes `maxScroll` (L572) at the moment it runs, not at the moment the tab was inserted.** On the right-swipe path the run is deferred until after the new tab's title has shrunk to its final (possibly short `~`) width, so the computed `maxScroll` can already equal `startX`, triggering the `startX == maxScroll` early-return (L582-585) and producing "no end-scroll for a short tab."
- Secondary: the **(+) tap path and swipe path are not symmetric** in scroll-trigger timing. The (+) path triggers `scrollStripToEnd()` inside the size-change `updateTabs()` (`TermuxSessionTabsController.java:179`) while the tab is still wide, so it captures a larger `maxScroll` and always scrolls; the swipe path relies on `markPendingEndScrollSession`/`onTitleChanged` (`TermuxTerminalSessionActivityClient.java:182-190, 202-207`) and thus measures after shrink.
- The (+) button / placeholder interplay does **not** change short-tab behaviour; `mPlaceholderActive` only affects `onPageScrolled` blending, not `runEndScroll`.

### Suggested fix direction (not applied — read-only)
Make `runEndScroll` measure `maxScroll` against the **wide** (pre-shrink) container, or trigger the scroll on the swipe path at commit time (like the (+) path) rather than deferring to `onTitleChanged`. Either removes the post-shrink `maxScroll` reduction that causes the short-tab early-return. Also consider replacing the `startX == maxScroll` early-return (L582) with a tolerance check only after the strip has fully settled at final width, so a short final label does not suppress a scroll that was already warranted by the add.

---

## Appendix — exact line references

| Concern | File:Line |
|---|---|
| (+) add → `scrollStripToEnd` via size-change `updateTabs` | `TermuxSessionTabsController.java:173-179` |
| `scrollStripToEnd()` sets `mEndScrollActive`, requests SCROLL_END | `TermuxSessionTabsController.java:724-731` |
| `requestScroll` single-owner + sequence guard | `TermuxSessionTabsController.java:615-629` |
| `runEndScroll` widthBefore snapshot | `TermuxSessionTabsController.java:544` |
| `runEndScroll` width-gate `newWidth <= widthBefore` | `TermuxSessionTabsController.java:559` |
| `runEndScroll` `childMeasuredW`/`maxScroll` | `TermuxSessionTabsController.java:570-572` |
| `runEndScroll` `startX == maxScroll` early-return | `TermuxSessionTabsController.java:582-585` |
| `updateAddButtonVisibility` GONE/VISIBLE | `TermuxSessionTabsController.java:198-209` (called L190) |
| `setPlaceholderActive` | `TermuxSessionTabsController.java:803-805` |
| `onPageScrolled` placeholder blend branch | `TermuxSessionTabsController.java:906-907`, suppression L882-891 |
| (+) tap `addNewSession` | `TermuxTerminalSessionActivityClient.java:532-594` |
| `onTitleChanged` pending-end-scroll trigger | `TermuxTerminalSessionActivityClient.java:173-194` (esp. 182-190) |
| `markPendingEndScrollSession` + fallback | `TermuxTerminalSessionActivityClient.java:202-207`, fallback 83-89 |
| swipe commit `commitPlaceholderToSession` | `SessionPagerManager.java:326-386` (no-op notify L343, reserved L354, mark L356) |
| `termuxSessionListNotifyUpdated` no-op when size unchanged | `SessionPagerManager.java:643-718` |
| `managePlaceholderForPosition` | `SessionPagerManager.java:300-319` |

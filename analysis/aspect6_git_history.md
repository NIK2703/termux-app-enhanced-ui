# Aspect 6 — Git History Diagnostic: Short-Tab End-Scroll Regression

**Project:** Termux app fork (`/data/local/projects/termux-app-ui-improve`)
**Primary file:** `app/src/main/java/com/termux/app/terminal/TermuxSessionTabsController.java`
**Secondary file:** `app/src/main/java/com/termux/app/terminal/TermuxTerminalSessionActivityClient.java`
**Symptom:** When a NEW terminal tab is created, the tab-strip auto-scroll to the right end only fires when the new tab's label is LONG. With a SHORT label (e.g. `~`), the end-scroll does NOT happen.

---

## 1. TL;DR — Root-Cause Commit

**The regression was introduced in commit `a07ee4b2fe4d6284bd3d9051e9e2ac44e7d044b0`**
("Tabs: fix strip stopping short of right edge on add", 2026-07-17 06:13:39 +0600 by NIK2703).

That commit added, in a single change:
- the **width-gate** `if (newWidth <= widthBefore && mRetries < 6)` in `runEndScroll()`;
- the **deferred-on-title design** (`mPendingEndScrollSession` + `scrollStripToEnd()` only from `onTitleChanged`/`mEndScrollFallback`);
- the **APPEARING/CHANGE_APPEARING disabled** "add-at-full-width" LayoutTransition change.

The width-gate was a *fix* for a prior bug ("new tab + (+) button stay partly off-screen / under-scroll"). It works for **long** labels but its gate predicate is wrong for **short** labels, so the short-label path under-scrolls or never scrolls. The deferred-on-title design compounds the issue: the end-scroll only fires once the shell emits an OSC window title, and for a short default label the timing/width interaction leaves the strip parked at the left/overflowing edge.

---

## 2. The Chain of Commits (git log --oneline -20)

```
061b210e Tabs: fix strip state reset on BACK-finish cold start
9fee3bbc Tabs: hide add button with GONE at session limit (no dead gap)
a07ee4b2 Tabs: fix strip stopping short of right edge on add        <-- REGRESSION ORIGIN
a43dce64 Swipe-new-tab + right-edge scroll + autocomplete swipe-select
bf4112fa Tabs: mirror open/close animation and fix swipe-new-tab scroll snap
afdb8792 Hide add-tab (+) button at MAX_SESSIONS limit, restore on free slot
37f6d4b4 Add swipe-rightmost-tab for new session and auto-complete popup tuning
95bbe68f Fix tap popup animation flicker and back-button handling; inverted dir-history for tabs-at-top
d13c6d54 Move add-tab button into scrollable tab list; floating pencil button; prevent long-press on swipe-up
1238c39b Multi-language UI: RU/ZH/ES translations + EnhancedUI fixes
10bbee7e EnhancedUI mod release 8
94372e3c Исправление: перепривязка слушателей вкладок в populateTabView()
0edc35e2 EnhancedUI v0.118.0+enhancedui.7: UI consolidation & backup progress fix
0504cc92 Unify bottom-panel colors with Termux:Style scheme; translucent control/tab backgrounds
5737d113 Unify session tabs panel with signal panel; fix light-mode tab text
7c7eb3d1 Add text input toggle button to tabs bar with settings integration
233fa39e Move terminal session tabs to top bar
```

Key observation: `a07ee4b2` is the **only** commit that introduces `runEndScroll`, `newWidth <= widthBefore`, `scrollStripToEnd`, and `mPendingEndScrollSession` (confirmed by `git log -S` on all four tokens — every one returns exactly this single commit, plus `061b210e` only for `runEndScroll` which *extends* it, never introduces the gate).

`061b210e` ("fix strip state reset on BACK-finish cold start") builds ON TOP of `a07ee4b2` (it adds `mBuilt`, `runCentreScroll`) but does **not** touch the width-gate or title-deferral — so it is an incremental fix, not the regression origin.

`9fee3bbc` ("hide add button with GONE at session limit") **reverted** one part of `a07ee4b2`'s fix: it changed the `(+)` button back from `INVISIBLE` to `GONE` at the session limit. This interacts with the width-gate but is unrelated to the short-label symptom (that symptom appears well below the session limit).

---

## 3. The Width-Gate Logic — How It Was Added (exact diff hunk)

From `git log -p -S "newWidth <= widthBefore"` → commit `a07ee4b2`, inside the **new** `runEndScroll()`:

```java
final int widthBefore = mTabsContainer.getMeasuredWidth();   // width BEFORE the add

final android.view.ViewTreeObserver.OnGlobalLayoutListener listener = new ... {
    private int mRetries = 0;
    @Override
    public void onGlobalLayout() {
        ...
        final int newWidth = mTabsContainer.getMeasuredWidth();
        // Wait until the container has actually grown to include the new tab, but
        // bound the wait so a never-growing layout still eventually scrolls.
        if (newWidth <= widthBefore && mRetries < 6) {
            mRetries++;
            return;                       // <-- STAYS PARKED, no scroll yet
        }
        ...
        final int maxScroll = Math.max(0, childMeasuredW - scrollW);
        ... // ValueAnimator scrollTo(maxScroll)
    }
};
```

**Intent (per the commit message and code comments):** "Record the width BEFORE the add so we only scroll once the container has actually grown to include the new tab (a layout pass may fire without the new width folded in yet)." The gate's purpose was to avoid scrolling to the *old* right edge before the new tab widened the strip — which, combined with the per-frame re-clamp, caused the earlier "stops one tab short" bug.

**The flaw:** The predicate `newWidth <= widthBefore` assumes that *adding a tab always increases `mTabsContainer.getMeasuredWidth()`*. This is only true when the strip is NOT already overflowing the viewport. When the strip is wider than the HorizontalScrollView viewport (already scrollable), the `wrap_content` container's `getMeasuredWidth()` is already its full natural content width — **adding one more tab does not necessarily grow the measured width on the very next layout pass**, especially for a SHORT label (`~`) whose width is tiny. The gate then keeps returning through all 6 retries (`mRetries < 6`), and even after the retries are exhausted it finally runs — but at that point `maxScroll` is computed from whatever width the container reports, and crucially the scroll was *deferred/retried* rather than fired cleanly on the add. For a short label the gate effectively suppresses or delays the end-scroll so it never visually reaches the right end — exactly the reported symptom.

In other words: **the gate keys off "did the container get wider", but the symptom depends on "did the container become wider than the viewport", i.e. `maxScroll > 0`.** A short label produces a small/zero width delta, so the gate's trigger condition fails to capture the case that actually needs scrolling.

---

## 4. The Deferred-On-Title Design — How It Was Added (exact diff hunks)

From `git log -p -S "mPendingEndScrollSession"` and `-S "scrollStripToEnd"` → commit `a07ee4b2`, in `TermuxTerminalSessionActivityClient.java`:

```java
// NEW field + fallback
private TerminalSession mPendingEndScrollSession = null;
private java.lang.Runnable mEndScrollFallback;

// in ctor:
this.mEndScrollFallback = () -> {
    if (mPendingEndScrollSession != null) {
        TermuxSessionTabsController tabs = mActivity.getTermuxSessionTabsController();
        if (tabs != null) tabs.scrollStripToEnd();
        mPendingEndScrollSession = null;
    }
};

// in onTitleChanged() — the ONLY place the actual scroll fires for the swipe-add path:
if (mPendingEndScrollSession == updatedSession) {
    mPendingEndScrollSession = null;
    ... mEndScrollFallback removed ...
    TermuxSessionTabsController tabs = mActivity.getTermuxSessionTabsController();
    if (tabs != null) tabs.scrollStripToEnd();
    termuxSessionListNotifyUpdated();
    return;
}

// arming API (called from SessionPagerManager.commitPlaceholderToSession):
public void markPendingEndScrollSession(@NonNull TerminalSession session) {
    mPendingEndScrollSession = session;
    ... postDelayed(mEndScrollFallback, 250);
}
```

And in `TermuxSessionTabsController.java` (same commit), the add-branch in `updateTabs()` was changed from an immediate `scrollToTabIndexRightEdge(newCount - 1)` to:

```java
if (newCount > sessionCount) {
    scrollStripToEnd();   // defers to runEndScroll() via requestScroll(SCROLL_END)
}
```

with `scrollStripToEnd()` setting `mEndScrollActive = true` and `requestScroll(SCROLL_END, -1)`.

**Why this compounds the short-label bug:** The swipe-new-tab add path (the most common "new tab" gesture, armed via `SessionPagerManager.commitPlaceholderToSession` → `markPendingEndScrollSession`) does NOT scroll at add-time. It waits for `onTitleChanged` — the shell's OSC window-title emission — with only a 250 ms fallback (`mEndScrollFallback`). So the end-scroll's *trigger* is now coupled to the shell reporting a title. The end-scroll then executes through `runEndScroll()` whose width-gate (Section 3) fails for a short label. The result: **for the swipe-add path, a short label yields no reliable end-scroll at all** — the title fires, `scrollStripToEnd()` runs, but the width-gate sees `newWidth <= widthBefore` (short label → tiny/zero width delta) and either exhausts retries or scrolls to a `maxScroll` that doesn't actually reveal the `(+)` button.

The `(+)` button tap path (`R.id.new_session_tab_button` → `createTermuxSession`) does NOT call `markPendingEndScrollSession`, so it scrolls via `updateTabs()` → `scrollStripToEnd()` directly at add time. But it still goes through the same flawed `runEndScroll()` width-gate, so a short-tab add via the `(+)` button is *also* subject to the under-scroll for the same reason.

---

## 5. Was This Code Added to Fix a Previous Bug? — YES

The commit `a07ee4b2` message and its inline comments explicitly state it was fixing an **under-scroll / clipped-(+) bug**:

> HorizontalScrollView re-clamps an in-flight smoothScrollTo to its content width every frame, so when the strip width shrinks during the ~250ms animation (LayoutTransition CHANGING reflow + late OSC title re-measure) the scroll freezes ~one tab short, leaving the new tab and (+) button off-screen.

Inline comment in `runEndScroll()`:
> ... the "new tab + (+) button stay partly off-screen" symptom.

So the prior behaviour (commit `a07ee4b2~1`) was: an immediate, simple `scrollToTabIndexRightEdge(newCount - 1)` using `mTabsScroll.smoothScrollTo()` (with `LayoutTransition` APPEARING + CHANGE_APPEARING **enabled**). That prior code (confirmed via `git show a07ee4b2~1:...TermuxSessionTabsController.java`):

```java
transition.enableTransitionType(LayoutTransition.APPEARING);
transition.enableTransitionType(LayoutTransition.CHANGE_APPEARING);
...
if (newCount > sessionCount) {
    scrollToTabIndexRightEdge(newCount - 1);   // live-clamping smoothScrollTo
}
```

That approach **always scrolled on add** (no width-gate, no title-deferral) — so a SHORT label scrolled correctly the old way; the bug it had was the *under-scroll / off-screen (+) button* because the layout-animated width growth (APPEARING 0→full over 220 ms) shrank mid-animation and HSV re-clamped.

**Conclusion:** `a07ee4b2` correctly fixed the *under-scroll with long labels*, but in doing so it (a) disabled APPEARING so tabs add at full width (good), (b) replaced the immediate scroll with a width-gated deferred `runEndScroll()` (the width-gate is the bad part for short labels), and (c) deferred the swipe-add scroll to `onTitleChanged` (compounds the failure). The width-gate's predicate does not hold for short labels, making this fix a **regression for the short-label case** — a classic side effect of a fix that over-constrained the trigger condition.

---

## 6. LayoutTransition Evolution (original vs current)

| Aspect | Before `a07ee4b2` (`a07ee4b2~1`) | After `a07ee4b2` (current) |
|---|---|---|
| APPEARING | enabled | **disabled** |
| CHANGE_APPEARING | enabled | **disabled** |
| CHANGING | enabled | enabled |
| DISAPPEARING / CHANGE_DISAPPEARING | disabled | disabled |
| Scroll on add | `scrollToTabIndexRightEdge()` → `smoothScrollTo()` (live re-clamp) | `scrollStripToEnd()` → `runEndScroll()` self-driven `ValueAnimator` + `scrollTo()` + **width-gate** |
| Scroll trigger | immediate at add | width-gated, AND (swipe path) deferred to `onTitleChanged` |

The disabling of APPEARING/CHANGE_APPEARING is sound: it makes the new tab appear at full measured width so geometry settles in one layout pass. The width-gate was meant to handle the *remaining* race where a layout pass fires before the new width is folded in — but keying it on `newWidth <= widthBefore` rather than on `maxScroll > 0` / `newWidth > viewport` is what breaks short labels.

---

## 7. Full Regression Chain (what led to the current bug)

1. **Pre-`a07ee4b2`**: Simple immediate `smoothScrollTo`-based right-edge scroll on every add. Always scrolled, but **under-scrolled / clipped the (+) button** for long labels because APPEARING animation + late OSC re-measure caused HSV per-frame re-clamp (the "stops one tab short" bug). Short labels worked.

2. **`a07ee4b2`** ("fix strip stopping short of right edge on add"): Regresses short labels while fixing long labels.
   - Disabled APPEARING/CHANGE_APPEARING (add-at-full-width) — good.
   - Replaced `smoothScrollTo` with self-driven `ValueAnimator` + `scrollTo()` to a fixed `maxScroll` — fixes the re-clamp under-scroll — good for long labels.
   - **Added the `newWidth <= widthBefore` width-gate in `runEndScroll()`** — the regression. For a short label the container width delta is tiny/zero, so the gate fails to detect "needs scroll" and the end-scroll is suppressed/delayed → short-tab no-scroll.
   - **Added the deferred-on-title design** (`mPendingEndScrollSession`, `scrollStripToEnd` only from `onTitleChanged` + 250 ms fallback) for the swipe-add path. Now the swipe-add scroll's trigger is coupled to the shell title, adding another point of failure for short labels.

3. **`061b210e`** ("fix strip state reset on BACK-finish cold start"): Adds `mBuilt` and `runCentreScroll`, refines cold-start centring. Does NOT change the width-gate or title-deferral, so does not fix (or worsen) the short-label bug.

4. **`9fee3bbc`** ("hide add button with GONE at session limit"): Reverts the `(+)` button from `INVISIBLE` back to `GONE` at the session limit (to remove a dead trailing gap). Independent of the short-label symptom (which occurs well below the limit) but reintroduces a width perturbation the earlier commit had guarded against.

Net result: the current `runEndScroll()` width-gate (`TermuxSessionTabsController.java:559`) plus the title-deferral (`TermuxTerminalSessionActivityClient.java:182` / `:202`) are the inherited mechanism from `a07ee4b2` that produces "end-scroll fires for LONG labels only, not SHORT labels."

---

## 8. Recommended Direction (not applied — read-only analysis)

The regression is the **width-gate predicate**. The correct trigger is not "container got wider than before" but "the strip overflows the viewport and the new last tab/(+) button is off-screen", i.e. scroll whenever `maxScroll > mTabsScroll.getScrollX()` after the new tab is laid out — independent of the *delta* in container width. The gate should be replaced with a check that fires on the first post-add layout where the container has a non-zero measured width and `maxScroll > currentScrollX`, regardless of whether `newWidth > widthBefore`. The title-deferral on the swipe path can remain (it avoids scrolling to a placeholder label) but its `scrollStripToEnd()` should not be subject to the width-gate's width-delta assumption.

---

## 9. Evidence Index (commit → token)

| Token searched | Commit(s) returned | Role |
|---|---|---|
| `runEndScroll` | `a07ee4b2` (introduced), `061b210e` (extends via `runCentreScroll`) | core end-scroll |
| `newWidth <= widthBefore` | `a07ee4b2` **only** | the width-gate (regression) |
| `scrollStripToEnd` | `a07ee4b2` **only** | deferred end-scroll entry |
| `mPendingEndScrollSession` | `a07ee4b2` **only** | deferred-on-title design (regression amplifier) |

**Regression-origin commit: `a07ee4b2fe4d6284bd3d9051e9e2ac44e7d044b0`** ("Tabs: fix strip stopping short of right edge on add").

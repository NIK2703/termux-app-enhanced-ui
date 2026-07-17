# Tab-Strip Scroll — New Robust Architecture

**Scope:** `TermuxSessionTabsController.java` (tab strip), with touch points in
`SessionPagerManager.java`, `TerminalPagerAdapter.java`,
`TermuxTerminalSessionActivityClient.java`, and the tab layouts.

**Goal:** After a tab is added — via a right-swipe placeholder commit **or** the
`(+)` add-tab button — the strip must smoothly scroll until the **new tab AND the
trailing `(+)` button are fully revealed at the right edge**. Never stop short,
never jitter, never get yanked back by a title refresh, page-settle, or layout
transition.

---

## 1. Why the current design is fragile

| Symptom | Root cause in current code |
|---|---|
| Scroll stops short of the `(+)` button | `SCROLL_END` measures `last.getRight()` inside a plain `post()` (`mScrollRunnable`). `post()` is a **message-queue** callback, not a **layout** callback — it can run *before* the just-added child has been measured/laid out, so `getRight()` is stale (0 or the pre-add value). `maxScroll` is then computed too small. |
| Jitter / double smoothScroll | Two owners compete: `requestScroll(SCROLL_END)` from `scrollStripToEnd()` and `requestScroll(SCROLL_CENTRE)` from title refresh / `setCurrentSession`. "Last-call-wins" only helps if both are queued in the *same* frame; a CENTRE that arrives one frame later still fires. |
| Fragile timing | `mEndScrollActive` is released by a hard-coded `postDelayed(350ms)`. If the smooth scroll takes longer (long strip) it releases early and a stray CENTRE wins; if shorter it holds the strip hostage. `markPendingEndScrollSession` adds a *second* `250ms` timer. Two unsynchronised timers + one boolean = race soup. |
| Width shifts after measurement | `LayoutTransition.CHANGING` (220ms) animates neighbour widths. Even with APPEARING disabled, a *title change* on an existing tab triggers CHANGING, moving `last.getRight()` **after** the END scroll target was measured. |
| Placeholder vs (+) path divergence | Swipe-commit arms the scroll via `markPendingEndScrollSession` → `onTitleChanged`. The `(+)` button path (`addNewSession`) does **not** arm it at all — it relies on `updateTabs`' `SCROLL_CENTRE`, which centres the new tab rather than revealing the `(+)`. |

**Core insight:** every failure is a *measurement-timing* or a *ownership*
problem. Fix both:

1. **Always measure geometry inside a real layout callback** (`OnGlobalLayoutListener`
   fired after the strip has re-laid-out at final widths, or `doOnLayout`), never
   in a bare `post()`.
2. **Replace the boolean juggling with one explicit state machine** that owns the
   strip and defines exactly when CENTRE / page-blend requests are allowed.

---

## 2. State machine

One field: `private ScrollState mState = ScrollState.IDLE;`

```
enum ScrollState {
    IDLE,                 // strip free; CENTRE + page-blend allowed
    REVEAL_PENDING,       // add committed; waiting for final layout to measure
    REVEAL_ANIMATING,     // smoothScrollTo(maxScroll) in flight
    REVEAL_SETTLE,        // scroll finished; short guard window to swallow late CENTRE
    CENTRE_PENDING        // (optional) a CENTRE was requested; waiting for layout settle
}
```

### Transition table

| From | Event | Guard | To | Action |
|---|---|---|---|---|
| IDLE | `revealNewTabAndAddButton()` (either add path) | — | REVEAL_PENDING | register one-shot layout listener |
| REVEAL_PENDING | `onStripLayoutSettled()` | last child laid out (`getRight()>0` & width stable) | REVEAL_ANIMATING | compute `maxScroll`, `smoothScrollTo`, arm settle timer keyed to scroll duration |
| REVEAL_PENDING | `onStripLayoutSettled()` | not yet stable | REVEAL_PENDING | re-arm listener (bounded retries) |
| REVEAL_ANIMATING | scroll end (settle timer) | — | REVEAL_SETTLE | start short settle window (`SETTLE_MS`) |
| REVEAL_SETTLE | settle window elapsed | — | IDLE | — |
| REVEAL_* | `requestCentre(i)` (title refresh / setCurrentSession no-arm) | — | *(unchanged)* | **ignored** (log only) |
| REVEAL_* | `onUserSwipeStarted()` (DRAGGING) | — | IDLE | cancel pending reveal — user took over |
| REVEAL_* | `onTabClosed()` | — | IDLE | cancel — geometry no longer applies |
| IDLE | `requestCentre(i)` | — | CENTRE_PENDING | register layout listener, then smoothScroll centre |
| IDLE | `onPageScrolled()` (blend) | — | IDLE | interpolate scrollTo (finger-follow) |
| REVEAL_ANIMATING / REVEAL_SETTLE | `onPageScrolled()` | — | *(unchanged)* | **ignored** (do not fight the smooth scroll) |

### Guard rules (the whole point)

- **CENTRE is ignored while `isRevealInFlight()`** (`REVEAL_PENDING`,
  `REVEAL_ANIMATING`, `REVEAL_SETTLE`). This kills the "title-refresh yanks the strip
  back" bug without any `mEndScrollActive` boolean.
- **`onPageScrolled` finger-follow is ignored while reveal is in flight** but stays
  fully live in `IDLE` — so per-swipe blending is preserved for normal tab-to-tab
  swipes.
- **A user DRAGGING event always drops to IDLE** — the user is explicitly taking
  over, so we never fight them.
- The `REVEAL_SETTLE` window (`SETTLE_MS ≈ 120ms`) exists only to swallow the
  *trailing* CENTRE that a late `onTitleChanged` / `onSessionPageSelected` posts one
  or two frames after the scroll visually finishes.

---

## 3. The measurement pattern (the reliability core)

Never measure in a bare `post()`. Use a **self-removing `OnGlobalLayoutListener`**
that fires *after* the container has been measured and laid out at its final widths,
and validate the geometry before committing the scroll. If the transition is still
running (widths still animating), wait for the next layout pass.

```java
private void runAfterStripLayout(Runnable body) {
    final ViewTreeObserver vto = mTabsContainer.getViewTreeObserver();
    final ViewTreeObserver.OnGlobalLayoutListener l =
        new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override public void onGlobalLayout() {
                // Self-remove FIRST so a re-entrant requestLayout inside body()
                // cannot double-fire us.
                ViewTreeObserver o = mTabsContainer.getViewTreeObserver();
                if (o.isAlive()) o.removeOnGlobalLayoutListener(this);
                body.run();
            }
        };
    vto.addOnGlobalLayoutListener(l);
    // Force a layout pass so the listener is guaranteed to fire even if nothing
    // else invalidates (e.g. add already measured — we still want a callback).
    mTabsContainer.requestLayout();
}
```

**Why `OnGlobalLayoutListener` beats `post()`:** it is dispatched from
`ViewRootImpl.performTraversals()` *after* `measure` + `layout`, so every child's
`getRight()`/`getWidth()` is final for that pass. `post()` only guarantees "next
message loop", which can precede the layout traversal.

**Handling the LayoutTransition width drift:** before committing, if
`mTabsContainer.getLayoutTransition().isRunning()`, the animated widths are not yet
final — re-arm and try again (bounded, e.g. 5 retries) instead of scrolling to a
mid-animation target. Combined with keeping APPEARING disabled (new tab inserted at
full width), the first or second layout pass is almost always stable.

---

## 4. New controller API surface

Replace `scrollStripToEnd()`, `requestScroll()`, `mScrollRunnable`,
`mEndScrollActive`, `mPendingScrollMode`, `mPendingTabScrollIndex`,
`setEndScrollReserved()`, and the `mPageScrollSuppressed` bookkeeping with:

| Method | Called by | Purpose |
|---|---|---|
| `revealNewTabAndAddButton()` | swipe-commit **and** `(+)` button add path | single entry point — the ONLY way to trigger an end reveal |
| `requestCentre(int index)` | `setCurrentSession`, title refresh in `updateTabs` | centre a tab; auto-ignored while reveal in flight |
| `onStripLayoutSettled()` | internal (layout listener) | measure + fire the smooth scroll |
| `onUserSwipeStarted()` | `onPageScrollStateChanged(DRAGGING)` | cancel reveal, drop to IDLE |
| `onTabClosed()` | `updateTabs` remove branch | cancel reveal |
| `onPageScrolled(pos, off)` | `SessionPagerManager` | finger-follow blend; no-op while reveal in flight |
| `isRevealInFlight()` | guards | state predicate |

**Both add paths now converge on `revealNewTabAndAddButton()`.** The
`onTitleChanged`/`markPendingEndScrollSession` label-wait can be kept as the
*trigger* (so the reveal starts once the real title is set), but it calls the single
new entry point instead of the old `scrollStripToEnd()`. The `(+)` button path calls
it directly after `updateTabs` inserts the tab.

---

## 5. Code sketch (Java)

```java
// ── State ────────────────────────────────────────────────────────────────
private enum ScrollState { IDLE, REVEAL_PENDING, REVEAL_ANIMATING, REVEAL_SETTLE }
private ScrollState mState = ScrollState.IDLE;

private static final long SETTLE_MS = 120L;      // swallow trailing CENTRE
private static final int  MAX_LAYOUT_RETRIES = 5;
private int mRevealRetries = 0;

private final android.os.Handler mHandler =
        new android.os.Handler(android.os.Looper.getMainLooper());

private boolean isRevealInFlight() {
    return mState == ScrollState.REVEAL_PENDING
        || mState == ScrollState.REVEAL_ANIMATING
        || mState == ScrollState.REVEAL_SETTLE;
}

// ── SINGLE entry point for revealing new tab + (+) button ────────────────
/**
 * Smoothly scroll the strip so the newly-added tab AND the trailing (+) button are
 * fully revealed at the right edge. Idempotent: re-entrant calls while a reveal is
 * already in flight simply re-arm the measurement pass (widths may still be settling).
 * Called by BOTH add paths:
 *   - swipe placeholder commit (via onTitleChanged / fallback trigger)
 *   - (+) add-tab button (directly after updateTabs inserts the tab)
 */
public void revealNewTabAndAddButton() {
    if (mTabsContainer == null || mTabsScroll == null) return;
    logTabScroll("reveal", "requested state=" + mState + " " + scrollGeometry());
    mHandler.removeCallbacksAndMessages(null);  // drop any pending settle/centre
    mState = ScrollState.REVEAL_PENDING;
    mRevealRetries = 0;
    armStripLayoutMeasure();
}

private void armStripLayoutMeasure() {
    final ViewTreeObserver vto = mTabsContainer.getViewTreeObserver();
    vto.addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
        @Override public void onGlobalLayout() {
            ViewTreeObserver o = mTabsContainer.getViewTreeObserver();
            if (o.isAlive()) o.removeOnGlobalLayoutListener(this);
            onStripLayoutSettled();
        }
    });
    mTabsContainer.requestLayout();
}

/** Fired from the layout listener: widths are final for this pass. Measure & scroll. */
private void onStripLayoutSettled() {
    if (mState != ScrollState.REVEAL_PENDING) return;   // superseded (swipe/close/idle)

    // If the container's LayoutTransition is still animating child widths, the geometry
    // is not final yet — wait for the next stable pass (bounded).
    LayoutTransition lt = mTabsContainer.getLayoutTransition();
    boolean transitionRunning = lt != null && lt.isRunning();
    int childCount = mTabsContainer.getChildCount();
    View last = childCount > 0 ? mTabsContainer.getChildAt(childCount - 1) : null;
    boolean geomReady = last != null && last.getWidth() > 0 && last.getRight() > 0;

    if ((transitionRunning || !geomReady) && mRevealRetries < MAX_LAYOUT_RETRIES) {
        mRevealRetries++;
        logTabScroll("reveal", "geom not final, retry " + mRevealRetries
                + " transRun=" + transitionRunning + " " + scrollGeometry());
        armStripLayoutMeasure();
        return;
    }
    if (last == null) { mState = ScrollState.IDLE; return; }

    // trueEnd = right edge of the last child (the (+) button, or the last tab if the
    // button is GONE at MAX_SESSIONS) + container right padding. HorizontalScrollView
    // clamps, so this is the exact "everything revealed" scroll position.
    final int trueEnd   = last.getRight() + mTabsContainer.getPaddingRight();
    final int maxScroll = Math.max(0, trueEnd - mTabsScroll.getWidth());

    logTabScroll("reveal", "SCROLL maxScroll=" + maxScroll
            + " trueEnd=" + trueEnd + " " + scrollGeometry());

    mState = ScrollState.REVEAL_ANIMATING;
    mTabsScroll.smoothScrollTo(maxScroll, 0);

    // Estimate scroll duration; HorizontalScrollView.smoothScrollBy caps at ~250ms.
    // Move to SETTLE when the animation is (over-)done, then IDLE after the guard window.
    long est = 300L;   // generous upper bound of HSV smooth scroll
    mHandler.postDelayed(() -> {
        if (mState == ScrollState.REVEAL_ANIMATING) {
            mState = ScrollState.REVEAL_SETTLE;
            mHandler.postDelayed(() -> {
                if (mState == ScrollState.REVEAL_SETTLE) mState = ScrollState.IDLE;
            }, SETTLE_MS);
        }
    }, est);
}

// ── CENTRE (title refresh, tab switch) — auto-ignored during reveal ──────
public void requestCentre(int index) {
    if (mTabsContainer == null || mTabsScroll == null) return;
    if (isRevealInFlight()) {
        logTabScroll("centre", "IGNORED (reveal in flight) idx=" + index);
        return;                                   // guard: reveal owns the strip
    }
    if (index < 0 || index >= mTabsContainer.getChildCount() - 1) return;
    final int idx = index;
    // Same measurement discipline: centre after layout settles (covers close reflow).
    runAfterStripLayout(() -> {
        View tab = mTabsContainer.getChildAt(idx);
        if (tab == null) return;
        int scrollX = tab.getLeft() - mTabsScroll.getWidth() / 2 + tab.getWidth() / 2;
        mTabsScroll.smoothScrollTo(scrollX, 0);
    });
}

// ── Cancellation events ─────────────────────────────────────────────────
public void onUserSwipeStarted() {        // from onPageScrollStateChanged(DRAGGING)
    if (isRevealInFlight()) {
        mHandler.removeCallbacksAndMessages(null);
        mState = ScrollState.IDLE;            // user took over — never fight them
    }
}

private void onTabClosed() {               // from updateTabs remove branch
    if (isRevealInFlight()) {
        mHandler.removeCallbacksAndMessages(null);
        mState = ScrollState.IDLE;
    }
}
```

### `updateTabs()` mapping

```java
public void updateTabs(List<TermuxSession> sessions) {
    // ... existing add/remove/populate logic (unchanged) ...

    if (newCount > sessionCount) {
        // A tab was added. Do NOT scroll to end HERE for the swipe path — the label-
        // triggered reveal (onTitleChanged -> revealNewTabAndAddButton) fires once the
        // real title is set. The (+) button path arms the reveal at its call site
        // (see addNewSession mapping below) so it does not depend on a title event.
        // Nothing to do here beyond structure.
    } else if (newCount < sessionCount) {
        onTabClosed();                 // cancel any in-flight reveal
        if (currentSessionIndex >= 0) requestCentre(currentSessionIndex);
    } else if (currentSessionIndex >= 0) {
        // Title-only / same-size refresh. requestCentre() self-guards: if a reveal is
        // in flight it is ignored, so the OSC-title refresh can NEVER yank the strip
        // back from the right end. No mEndScrollActive boolean needed.
        requestCentre(currentSessionIndex);
    }

    updateAddButtonVisibility(sessions.size());
}
```

### `setCurrentSession()` mapping

```java
public void setCurrentSession(int index) {
    // ... existing selection-state / tint bookkeeping (unchanged) ...
    mCurrentSessionIndex = index;
    requestCentre(index);   // self-guarded; ignored while reveal in flight.
                            // No explicit mEndScrollActive check required.
}
```

### `onPageScrolled()` mapping

```java
public void onPageScrolled(int position, float positionOffset) {
    if (mTabsContainer == null || mTabsScroll == null || !mSchemeApplied) return;
    if (isRevealInFlight()) return;      // single guard replaces mEndScrollActive juggling
    // ... unchanged finger-follow scrollTo + colour blend + close-button logic ...
}
```

### Swipe-commit path (`SessionPagerManager.commitPlaceholderToSession`)

- Keep `client.markPendingEndScrollSession(newSession.getTerminalSession())`.
- **Remove** `tabs.setEndScrollReserved(true)` — obsolete; the state machine and the
  `isRevealInFlight()` guard in `onPageScrolled` replace it.
- `TermuxTerminalSessionActivityClient.onTitleChanged` (and the fallback timer) call
  `tabs.revealNewTabAndAddButton()` instead of `tabs.scrollStripToEnd()`.
- `onPageScrollStateChanged(DRAGGING)` calls `tabs.onUserSwipeStarted()` instead of
  `tabs.setEndScrollReserved(false)`.

### (+) button path (`addNewSession`)

The `(+)` button previously never revealed the `(+)`. Now, after
`createTermuxSession` fires `termuxSessionListNotifyUpdated()` → `updateTabs()`
inserts the tab, arm the reveal directly (no title dependency, because the button add
selects the new session immediately):

```java
// In addNewSession(), after the new session is created & selected:
TermuxSessionTabsController tabs = mActivity.getTermuxSessionTabsController();
if (tabs != null) tabs.revealNewTabAndAddButton();
```

Because `revealNewTabAndAddButton()` measures inside a layout listener, it does not
matter that the tab view may not be laid out at the instant of the call.

---

## 6. Layout XML changes

The reveal target is `last.getRight() + container.paddingRight`. For this to reliably
place the `(+)` button flush at the right edge:

### `activity_termux.xml` — `session_tabs_scroll` (HorizontalScrollView)

```xml
<HorizontalScrollView
    android:id="@+id/session_tabs_scroll"
    android:layout_width="0dp"
    android:layout_height="wrap_content"
    android:layout_weight="1"
    android:scrollbars="none"
    android:clipToPadding="false"     <!-- ADD: let the last child render into padding on overscroll clamp -->
    android:fillViewport="true" />    <!-- ADD: container fills viewport when few tabs, so trailing (+) stays right-anchored and geometry is stable -->
```

- `fillViewport="true"`: when tabs do not fill the width, the inner `LinearLayout`
  stretches to the viewport, giving a **stable, non-zero measured width** so the
  `maxScroll==0` case is unambiguous (no phantom scroll on the first add).
- `clipToPadding="false"`: prevents the last child being visually clipped by the
  scroll view's own padding in the clamped end position.

### `activity_termux.xml` — `session_tabs` (inner LinearLayout)

```xml
<LinearLayout
    android:id="@+id/session_tabs"
    android:layout_width="wrap_content"
    android:layout_height="wrap_content"
    android:orientation="horizontal"
    android:paddingStart="4dp"
    android:paddingEnd="8dp"          <!-- was paddingHorizontal=4dp; larger END padding guarantees the (+) button clears the edge after the clamp -->
    android:clipToPadding="false" />
```

`paddingEnd` is what `onStripLayoutSettled()` adds to `last.getRight()` to compute
`trueEnd`. A slightly larger end padding (8dp) gives visual breathing room so the
`(+)` is never flush against the physical edge.

### `item_session_tab.xml`

`layout_marginEnd="4dp"` on `session_tab_root` is fine and already outside the tab's
`getRight()`. **No change required** — but be aware the last *tab's* trailing margin
sits between it and the `(+)` button, not at the strip end, so it does not affect
`trueEnd` (which is measured from the `(+)` button, the true last child). Leave as is.

**Do NOT** add a `marginEnd` to `new_session_tab_button` expecting it to be revealed —
margins on the last child live *outside* `getRight()` and are eaten by the
HorizontalScrollView clamp. Reveal room must come from the container's `paddingEnd`
(added to `trueEnd`), which is exactly what the new code does.

---

## 7. What this buys us

- **Deterministic measurement:** geometry read only inside `performTraversals`, after
  measure+layout, with an explicit "transition still running → retry" guard. No more
  scrolling to a stale/short `maxScroll`.
- **One owner, explicit states:** CENTRE and page-blend are *structurally* unable to
  fight an in-flight reveal (single `isRevealInFlight()` guard), replacing three
  booleans and two ad-hoc timers.
- **Both add paths unified** on `revealNewTabAndAddButton()`; the `(+)` button now
  reveals the `(+)` too, which it never did.
- **Per-swipe blending preserved** — `onPageScrolled` is only suppressed *during* a
  reveal and fully live in IDLE, so normal tab-to-tab finger-follow is unchanged.
- **User always wins** — a DRAGGING event cancels the reveal instantly.
- **No hard-coded 350/250 ms release** — the settle window (`SETTLE_MS`) is only a
  small trailing guard, and cancellation events short-circuit it.

## 8. Fields removed / added

Removed: `mPendingScrollMode`, `mPendingTabScrollIndex`, `mScrollRunnable`,
`mEndScrollActive`, `mPageScrollSuppressed`, `SCROLL_NONE/CENTRE/END`,
`requestScroll()`, `scrollStripToEnd()`, `setEndScrollReserved()`.

Added: `ScrollState` enum + `mState`, `mHandler`, `mRevealRetries`,
`revealNewTabAndAddButton()`, `armStripLayoutMeasure()`, `onStripLayoutSettled()`,
`requestCentre()`, `runAfterStripLayout()`, `onUserSwipeStarted()`, `onTabClosed()`,
`isRevealInFlight()`.

`scrollToTabIndex()` becomes a thin wrapper over `requestCentre()` (the
LayoutTransition-listener special-case is subsumed by the retry-on-transition-running
logic in the shared measurement path).

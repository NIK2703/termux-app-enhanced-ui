# Aspect 3 — `onTitleChanged` Timing & Label-Width Sign Determine End-Scroll Success

**File under analysis:** `TermuxTerminalSessionActivityClient.java`, `TermuxSessionTabsController.java`
**Symptom:** A newly created terminal tab's right-end strip scroll (revealing the `(+)` button) fires only when the new tab's eventual label is LONG/maximum width. With a SHORT label (`~`), the scroll does not happen.

---

## 1. The trigger chain (who fires the end-scroll, and when)

The right-end scroll is **deferred** from the moment the tab is created until the shell emits a real window title (OSC) for that session.

### 1.1 Arming the pending scroll (at creation)

`SessionPagerManager.commitPlaceholderToSession()` appends the new session and arms the deferred scroll:

- `TermuxSessionTabsController.setEndScrollReserved(true)` — `SessionPagerManager.java:354` (sets `mEndScrollActive = true` immediately, suppressing competing `onPageScrolled`/`CENTRE`).
- `client.markPendingEndScrollSession(newTerminalSession)` — `SessionPagerManager.java:356`.

`markPendingEndScrollSession` (`TermuxTerminalSessionActivityClient.java:202-207`):

```java
public void markPendingEndScrollSession(@NonNull TerminalSession session) {
    mPendingEndScrollSession = session;                                  // :203
    android.os.Handler mainHandler = ...;
    mainHandler.removeCallbacks(mEndScrollFallback);                     // :205
    mainHandler.postDelayed(mEndScrollFallback, 250);                    // :206  ← 250ms fallback
}
```

So at *creation time* nothing scrolls. The strip only gets `mEndScrollActive = true`.

### 1.2 The actual scroll fires from `onTitleChanged`

When the shell finally sets the OSC title, `onTitleChanged` runs (`TermuxTerminalSessionActivityClient.java:173-194`):

```java
@Override
public void onTitleChanged(@NonNull TerminalSession updatedSession) {
    if (!mActivity.isVisible()) return;                                  // :175
    if (mPendingEndScrollSession == updatedSession) {                    // :182
        mPendingEndScrollSession = null;                                 // :183
        ... removeCallbacks(mEndScrollFallback);                         // :185
        TermuxSessionTabsController tabs = ...;
        if (tabs != null) tabs.scrollStripToEnd();                       // :187  ← SCHEDULES end-scroll
        termuxSessionListNotifyUpdated();                                // :189  ← RE-PAINTS tabs (re-sets title)
        return;                                                          // :190
    }
    termuxSessionListNotifyUpdated();                                    // :193
}
```

**Confirmed order (your Aspect #3):**
1. `:187` `scrollStripToEnd()` — **does NOT measure**. It only sets `mEndScrollActive = true` and posts a runnable (`requestScroll(SCROLL_END)` → `mTabsScroll.post(mScrollRunnable)`), verified below.
2. `:189` `termuxSessionListNotifyUpdated()` → `updateTabs(...)` → `populateTabView()` which re-sets the title text (`titleView.setText(displayTitle)`) at `TermuxSessionTabsController.java:299`.

### 1.3 `scrollStripToEnd` only posts — it never reads width

`TermuxSessionTabsController.scrollStripToEnd()` (`TermuxSessionTabsController.java:724-731`):

```java
public void scrollStripToEnd() {
    if (mTabsContainer == null || mTabsScroll == null) return;
    mEndScrollActive = true;                                             // :727
    requestScroll(SCROLL_END, -1);                                       // :730
}
```

`requestScroll` (`TermuxSessionTabsController.java:615-629`) only does:

```java
mPendingScrollMode = mode;                                               // :622
mScrollSeq++;                                                            // :623
mScrollSeqPending = mScrollSeq;                                          // :624
if (mTabsScroll != null) {
    mTabsScroll.removeCallbacks(mScrollRunnable);                       // :626
    mTabsScroll.post(mScrollRunnable);                                   // :627  ← queued; NOT run now
}
```

So `scrollStripToEnd()` at `:187` **does no measurement and runs no scroll**. It enqueues `mScrollRunnable` on the looper. `termuxSessionListNotifyUpdated()` at `:189` then runs **synchronously** and re-populates the tab titles, *before* `mScrollRunnable` is dequeued.

---

## 2. The width-gate in `runEndScroll` — and why label sign matters

The queued `mScrollRunnable` (`TermuxSessionTabsController.java:490-506`) eventually calls `runEndScroll()` (`TermuxSessionTabsController.java:536-612`).

### 2.1 `widthBefore` is captured at the start of `runEndScroll` (NOT before the title)

```java
private void runEndScroll() {
    cancelEndScroll();
    final int widthBefore = mTabsContainer.getMeasuredWidth();          // :544  ← captured HERE
    final OnGlobalLayoutListener listener = new ... {
        private int mRetries = 0;
        @Override
        public void onGlobalLayout() {
            ...
            final int newWidth = mTabsContainer.getMeasuredWidth();     // :556
            if (newWidth <= widthBefore && mRetries < 6) {              // :559  ← THE GATE
                mRetries++;
                return;                                                 // :561  retry, no scroll yet
            }
            ...
            final int childMeasuredW = mTabsContainer.getMeasuredWidth(); // :570
            final int scrollW = mTabsScroll.getWidth();
            final int maxScroll = Math.max(0, childMeasuredW - scrollW); // :572
            final int startX = mTabsScroll.getScrollX();
            if (startX == maxScroll) {                                  // :582  ← NO-OP guard
                ... ; return;
            }
            ... animate scrollTo(maxScroll) ...                        // :587-607
        }
    };
    mPendingEndLayoutListener = listener;
    mTabsContainer.getViewTreeObserver().addOnGlobalLayoutListener(listener); // :611
}
```

### 2.2 What `widthBefore` actually contains

`runEndScroll()` is only entered *after* the looper dequeues `mScrollRunnable`. By that point, the synchronous `termuxSessionListNotifyUpdated()` (`:189`) has **already executed** and `updateTabs` has **already re-measured the strip with the new (real) title**. The strip geometry at `:544` therefore reflects the *final* title width — not the default-label width.

This is the crux. The code comment (`TermuxSessionTabsController.java:542-544`) claims:

> *"Record the width BEFORE the add so we only scroll once the container has actually grown to include the new tab."*

But `widthBefore` is **not** captured "before the add" — it is captured **after** `updateTabs` already folded in the new tab at its *real* (post-title) width. So `widthBefore` is effectively the *final* width, and `newWidth` (read again inside `onGlobalLayout`, `:556`) is the same final width or *smaller* if any subsequent reflow shrinks it.

### 2.3 The gate's intent vs. the short-label reality

The gate `:559` (`newWidth <= widthBefore`) was designed to **wait until the strip GROWS** (default label → longer real title), so the end-scroll only starts once the full new width is present. The contract it expects is:

- **LONG title:** default label (e.g. a command or "Terminal") → real title is also long or longer → strip **grows** → `newWidth > widthBefore` eventually → gate passes → `maxScroll` is large → animation to `maxScroll` runs. ✅
- **SHORT title (`~`):** the *default* label is typically **longer** than `~` (e.g. "Terminal", or the cwd/command). When the OSC title `~` arrives, `updateTabs`/`populateTabView` **shrinks** the tab (`:299`). The strip width *decreases*.

So when the title-eventual-`~` arrives:

1. `onTitleChanged:187` posts `mScrollRunnable` (no measure).
2. `onTitleChanged:189` `termuxSessionListNotifyUpdated` → `updateTabs` re-sets title to `~` → strip **shrinks** (the new tab is now very narrow).
3. `mScrollRunnable` dequeued → `runEndScroll` captures `widthBefore` = **already-shrunk** width (`:544`).
4. `onGlobalLayout` fires → `newWidth` (`:556`) is **≤ widthBefore** (it can only be equal or smaller; nothing is growing it anymore).
5. Gate `:559` holds true ⇒ `mRetries++`, `return` (`:561`). It retries up to 6 times (`:559` `mRetries < 6`).
6. After 6 retries the gate is bypassed, and it proceeds to compute `maxScroll` at `:572`.

### 2.4 Final outcome for the short label

After exhausting the 6 retries, the listener computes `maxScroll` from the **shrunk** width. For a `~` tab, even the full strip (all tabs + `(+)`) is now *shorter* than it was with the default label. Two consequences:

- If the shrunk total width fits within the viewport (`childMeasuredW <= scrollW`), then `maxScroll = 0` (`:572`) and the strip is already at/near the end with no animation — **the scroll appears to "not happen."**
- If `maxScroll > 0` but `startX == maxScroll` (`:582`), it early-returns with **no animation** — again "no scroll."
- Even when it does animate, it animates to the *smaller* `maxScroll`, so the right-end reveal is weak/absent relative to what the long-label case produces.

For the **long** label the opposite holds: after the title arrives the strip *grows*, `newWidth > widthBefore` passes the gate quickly, `maxScroll` is large, and the animation to the far right end runs fully. ✅

**Conclusion for your Aspect #1 and #4:** Yes — for a short (`~`) title the default label is longer, so the title change **shrinks** the tab. Because `widthBefore` is captured *after* the shrink (post-`updateTabs`), the `newWidth <= widthBefore` gate never sees the expected *growth*; it sees a non-growing or shrinking width and either spins on retries or proceeds to a `maxScroll` that is too small (`startX == maxScroll` or 0). The width-gate's **sign assumption (grow)** is violated by short labels, and that is the root cause.

---

## 3. Why the default-label-at-full-width argument does NOT save it

The controller deliberately inserts new tabs at **full width** (no `APPEARING` transition) — see `TermuxSessionTabsController.java:57-72` and the comment at `:60-62`:

> *"Adding a tab at its full measured width lets the strip geometry settle within one layout pass, so the right-end scroll reads correct widths."*

At *creation* time (before the title), the tab is added at its default-label full width, so the strip *does* include the new tab. But the deferred design means the actual scroll is **not triggered at creation** — it is triggered later by `onTitleChanged`. By the time `runEndScroll` measures, `updateTabs` (`:189`) has already swapped the default label for the real, short title, **re-shrinking** the tab. The "insert at full width" fix only helps the geometry at add time; it does not help the *deferred* measurement because the label is still in flux when the scroll finally fires.

The APPEARING-transition disabling (`TermuxSessionTabsController.java:65-66`) was meant to keep width stable during `runEndScroll`. But the **title-driven re-populate** (`populateTabView` `:299`) still changes the width after the view is added — a `CHANGING`-free but *content*-driven width change that the gate does not account for. The `LayoutTransition` is detached only *inside* `runEndScroll` (`:579`), which is *after* `updateTabs` already mutated the title. So the width has already moved before the transition guard ever engages.

---

## 4. The fallback timer does not mask this

`updateTabs` runs synchronously inside `onTitleChanged:189`, so by the time `onTitleChanged` returns, the title is already the real `~`. The 250ms fallback (`TermuxTerminalSessionActivityClient.java:206`) only fires `scrollStripToEnd()` *again* if no title ever arrived — but a title *does* arrive, so the fallback is cancelled at `:185`. When the fallback is cancelled, the only end-scroll is the one scheduled at `:187`, which measures the already-shrunk strip. So the fallback neither helps nor hurts the short-label case; the damage is done by `:189` before any scroll measurement.

---

## 5. Call-order trace (short label `~`)

```
[creation] SessionPagerManager.commitPlaceholderToSession()
  ├─ setEndScrollReserved(true)            SessionPagerManager.java:354   → mEndScrollActive = true
  └─ markPendingEndScrollSession(session)  SessionPagerManager.java:356
        → mPendingEndScrollSession = session, postDelayed(fallback,250)

[shell emits OSC title "~"]
  onTitleChanged(session)  TermuxTerminalSessionActivityClient.java:173
    ├─ (mPendingEndScrollSession == session)  :182  true
    ├─ scrollStripToEnd()                     :187
    │     → mEndScrollActive = true           TermuxSessionTabsController.java:727
    │     → requestScroll(SCROLL_END)         :730
    │           → mTabsScroll.post(mScrollRunnable)   :627   (QUEUED, not run)
    ├─ termuxSessionListNotifyUpdated()       :189  ← SYNCHRONOUS (runs NOW)
    │     → updateTabs(sessions)              TermuxSessionTabsController.java:116
    │           → populateTabView(...~)       :156 → titleView.setText("~")  :299
    │           → strip width SHRINKS (default label was longer)
    └─ return                                :190

[later, looper dequeues mScrollRunnable]
  mScrollRunnable.run()                      TermuxSessionTabsController.java:490
    → runEndScroll()                         :501
        widthBefore = measuredWidth()        :544  ← ALREADY SHRUNK
        addOnGlobalLayoutListener            :611
          onGlobalLayout():
            newWidth = measuredWidth()       :556  ≤ widthBefore  ⇒ gate :559 TRUE
            mRetries++ ; return              :561  (repeat up to 6×)
          [after 6 retries]  maxScroll = childW - scrollW   :572  (small, ~0)
            if startX == maxScroll → return  :582  (NO ANIMATION)   ← symptom
            else animate to a tiny maxScroll (weak/imperceptible scroll)
```

For a LONG title the SHRINK at `:299` becomes a GROW (or the strip was already wide and stays wide), `newWidth > widthBefore` ⇒ gate passes on first `onGlobalLayout` ⇒ full animation to a large `maxScroll`. ✅

---

## 6. Root-cause verdict

**Yes — the deferred-on-title design is the root cause, and the label-width sign (grow vs. shrink) determines success.**

- The end-scroll is intentionally deferred until `onTitleChanged` so the strip never scrolls to a default/`"Terminal"` placeholder (`TermuxTerminalSessionActivityClient.java:179-181`, `:197-201`).
- But `onTitleChanged` calls `scrollStripToEnd()` (`:187`) **before** `termuxSessionListNotifyUpdated()` (`:189`), and `scrollStripToEnd` only *posts* a runnable; it performs **no measurement** (`TermuxSessionTabsController.java:724-731` → `requestScroll` `:615-629`).
- `termuxSessionListNotifyUpdated()` then synchronously re-populates the tab with the **real** title via `populateTabView` (`:299`), mutating the strip width *before* `runEndScroll` ever measures.
- `runEndScroll` captures `widthBefore` at `:544` **after** the shrink, then gates on `newWidth <= widthBefore` (`:559`). The gate's implicit contract is "wait for growth," but for a short title the geometry only ever **shrinks**, so the gate either burns its 6 retries (`mRetries < 6`) or proceeds to a `maxScroll` that is ~0 or equals `startX` — producing `startX == maxScroll` (`:582`) and **no visible scroll**.

The `widthBefore` comment (`TermuxSessionTabsController.java:542-544`) is **factually wrong** in the deferred path: `widthBefore` is not captured "before the add"; it is captured after `updateTabs` already applied the real (short) label. The gate therefore cannot distinguish "still settling" from "already shrunk."

### Minimal fix direction (for the implementer, not applied here)
Capture the strip width **at the moment the tab is structurally added** (in `updateTabs` when `newCount > sessionCount`, `TermuxSessionTabsController.java:134-141`, or in `setEndScrollReserved`/`markPendingEndScrollSession`), and use *that* as the comparison baseline — not the post-title width. Alternatively, replace the "wait for growth" gate with a "wait until width stabilizes" gate (compare two consecutive `onGlobalLayout` measurements instead of `newWidth <= widthBefore`), or simply scroll-to-end unconditionally once the deferred trigger fires (since `mEndScrollActive` already suppresses competing scrolls). The label-width sign must not gate the scroll.

package com.termux.app.terminal.io.autocomplete;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Build;
import android.text.Editable;
import android.text.Layout;
import android.text.SpannableString;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.Window;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import com.termux.R;
import com.termux.app.terminal.TermuxColorSchemeManager;


/**
 * Self-contained controller that owns the entire message-history auto-complete
 * suggestion popup logic (formerly embedded in TermuxActivity).
 *
 * <p>It tracks the 3-way dispatch (full rescan / additive filter / reposition),
 * the incremental history-version optimization, the popup window, the suggestion
 * views, and history-add-on-submit. It does NOT delegate back to the Activity for
 * any of that — the Activity only wires it up (passing the EditText, the
 * {@link MessageHistoryController}, the {@link TermuxColorSchemeManager} and a
 * couple of optional callbacks) and may call its public entry points
 * ({@link #onTextChanged()}, {@link #dismiss()}, {@link #isShowing()},
 * {@link #addToMessageHistory(String)}, {@link #loadSuggestions()}, …).
 */
public final class AutoCompleteController implements AutoCompleteDataProvider {


    private final Context mContext;
    private EditText mInputField;
    private final MessageHistoryController mMessageHistoryCtrl;
    private final TermuxColorSchemeManager mColorSchemeManager;
    private final SharedPreferences mPrefs;

    /** Called when a suggestion is chosen, so the host can dismiss its message-history popup. */
    @NonNull private Runnable mMessageHistoryDismissListener = () -> {};
    /** Provides the current working directory used as the per-directory history key. */
    @NonNull private CwdProvider mCwdProvider = () -> ".";

    /** True while the host is in an invalid state and auto-complete must be suppressed. */
    private boolean mIsInvalidState;

    // ── Auto-complete suggestions: TWO separate popups ──
    // The shell (bash) completion candidates and the message-history candidates
    // are shown in TWO distinct, visually identical popup windows. The shell
    // window is anchored ABOVE the history window (both float above the input
    // field); each is its own PopupWindow so they can be positioned/shown/dismissed
    // independently. There is no in-window divider anymore.
    //
    // Ownership split: this controller owns the SUGGESTION DATA and the
    // fetch/merge/input pipeline; {@link AutoCompletePopupManager} owns the two
    // popup windows and all of their rendering/positioning.
    /** Current list of suggestion strings being displayed (message history). */
    private final java.util.ArrayList<String> mCurrentSuggestions = new java.util.ArrayList<>();

    // ── Prefix Trie (avoids O(N) history scan per keystroke) ──
    // A prefix tree over the lowercased history. Each node stores the full list of
    // commands that pass through it (i.e. have that node's path as a prefix),
    // insertion-ordered so the newest-first history order is preserved. A descent
    // for a prefix of length L costs O(L) and returns the already-filtered list.
    /**
     * Prefix-tree node: {@link #words} holds every history command that has this
     * node's path as a (case-insensitive) prefix; {@link #children} maps the next
     * lowercased character code to the child node.
     */
    static final class TrieNode {
        /** Commands passing through this node (prefix matches), newest-first (insertion order). */
        final ArrayList<String> words = new ArrayList<>();
        /** Next-character → child node. */
        final android.util.SparseArray<TrieNode> children = new android.util.SparseArray<>();
    }
    /** Root of the prefix trie over the current history; null until built. */
    private TrieNode mPrefixTrie = null;
    /** History version at which {@link #mPrefixTrie} is valid. */
    private int mPrefixCacheVersion = -1;
    /**
     * Parallel to {@link #mCurrentSuggestions}: always {@code false} (history
     * suggestions only — shell completion has been removed).
     */
    private final java.util.ArrayList<Boolean> mCurrentIsShell = new java.util.ArrayList<>();
    /**
     * Maximum number of suggestions to RENDER in the popup (the user setting
     * "suggestions_max_count"). The stored {@link #mCurrentSuggestions} list may
     * hold more (especially all cached shell candidates), but only this many are
     * displayed; the rest remain available to Path B local filtering.
     */
    private int mDisplayMax = 4;
    /** Suppress auto-complete popup during programmatic text changes (suggestion tap, etc.). */
    private boolean mSuppressAutoComplete;
    /** True while a session's saved input is being restored into the field (tab switch,
     *  panel re-show). Mutes every afterTextChanged event for that restore so the popup is
     *  not triggered by the programmatic setText(). Distinct from mSuppressAutoComplete so
     *  it does not interact with the swipe/compose guards or the live typing path. */
    private boolean mRestoringInput;
    /** Suppress auto-complete popup during an active swipe-to-select gesture. */
    private boolean mSwipeSuppressed;
    /** Force a full rescan (Path A) on the next update — set after a swipe commits
     *  arbitrary text, which the incremental additive filter cannot handle. */
    private boolean mForceFullRescan;

    /** Reusable scratch set to avoid per-keystroke HashSet allocations in the hot path. */
    private final HashSet<String> mSeenSet = new HashSet<>();
    /** Reusable scratch list for the backspace in-place filter (avoids new ArrayList per Backspace). */
    private final ArrayList<String> mBackspaceScratch = new ArrayList<>();
    /** Shared immutable empty list returned by getCandidatesForPrefix when there are no matches. */
    private static final ArrayList<String> EMPTY_LIST = new ArrayList<>();

    // ── Incremental auto-complete optimization fields ──
    /** Previous text (CharSequence reference, no copy) before a change, for additive detection. */
    private CharSequence mAutoCompletePrevText = "";
    /** Cheap up-front signal: the pending change is an IME composition (after != before). */
    private boolean mComposingChangePending = false;
    /** Start index of the pending TextWatcher change (from beforeTextChanged). */
    private int mAutoCompleteChangeStart;
    /** Count of characters being replaced (from onTextChanged). */
    private int mAutoCompleteChangeBefore;
    /** Count of new characters being inserted (from onTextChanged). */
    private int mAutoCompleteChangeCount;
    /** Last text prefix used to build mCurrentSuggestions. */
    private String mLastAppliedPrefix = "";

    /** History version captured the last time the suggestion set was rebuilt. */
    private int mLastBuiltHistoryVersion = -1;

    /** Owns and renders the two suggestion popup windows. */
    @NonNull private final AutoCompletePopupManager mPopupManager;

    // ── IME composing coalescing (no delay on committed input) ──
    private final android.os.Handler mImeHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private Runnable mComposingCoalesce = null;

    // ── Swipe-to-select gesture on suggestion items ──
    /**
     * Horizontal swipe on a suggestion row progressively appends the next
     * word(s) of that suggestion into a live text selection in the input field.
     * Swipe right adds words, swipe left removes them; the selection is dropped
     * (committed) when the finger is lifted. All per-gesture state lives in the
     * handler.
     */
    @NonNull private final AutoCompleteSwipeHandler mSwipeHandler;

    // ── Auto-complete popup dimensions (read once from resources) ──
    private final int mPopupCornerRadiusPx;
    private final int mPopupElevationPx;
    private final int mPopupItemPadHPx;
    private final int mPopupItemPadVPx;
    private final int mPopupMinWidthPx;
    private final int mPopupWidthMarginPx;
    private final float mPopupWidthFraction;
    private final int mPopupXOffsetPx;
    private final int mPopupEdgeMarginPx;
    private final int mPopupYOffsetPx;
    private final int mPopupMinYPx;
    private final float mPopupContentAlpha;
    private final float mPopupShadowAlpha;

    /** Provider for the current working directory (per-directory history key). */
    public interface CwdProvider {
        @NonNull String getCwd();
    }

    /**
     * @param context              the host Activity/Context (used for resources and the window).
     * @param inputField           the terminal toolbar EditText the suggestions are anchored to.
     * @param messageHistoryCtrl   the message-history controller (history list + version + add/save/load).
     * @param colorSchemeManager   the colour-scheme manager that vends popup colours.
     */
    public AutoCompleteController(@NonNull Context context, @NonNull EditText inputField,
            @NonNull MessageHistoryController messageHistoryCtrl,
            @NonNull TermuxColorSchemeManager colorSchemeManager) {
        mContext = context;
        mInputField = inputField;
        mMessageHistoryCtrl = messageHistoryCtrl;
        mColorSchemeManager = colorSchemeManager;
        mPrefs = context.getSharedPreferences("termux_prefs", Context.MODE_PRIVATE);
        int maxCount = mPrefs.getInt("suggestions_max_count", 4);
        if (maxCount < 0) maxCount = 0;
        if (maxCount > 10) maxCount = 10;
        mDisplayMax = maxCount;
        Resources res = context.getResources();
        mPopupCornerRadiusPx = res.getDimensionPixelSize(R.dimen.autocomplete_popup_corner_radius);
        mPopupElevationPx = res.getDimensionPixelSize(R.dimen.autocomplete_popup_elevation);
        mPopupItemPadHPx = res.getDimensionPixelSize(R.dimen.autocomplete_popup_item_padding_horizontal);
        mPopupItemPadVPx = res.getDimensionPixelSize(R.dimen.autocomplete_popup_item_padding_vertical);
        mPopupMinWidthPx = res.getDimensionPixelSize(R.dimen.autocomplete_popup_min_width);
        mPopupWidthMarginPx = res.getDimensionPixelSize(R.dimen.autocomplete_popup_width_margin);
        mPopupWidthFraction = res.getFraction(R.fraction.autocomplete_popup_width_fraction, 1, 1);
        mPopupXOffsetPx = res.getDimensionPixelSize(R.dimen.autocomplete_popup_x_offset);
        mPopupEdgeMarginPx = res.getDimensionPixelSize(R.dimen.autocomplete_popup_edge_margin);
        mPopupYOffsetPx = res.getDimensionPixelSize(R.dimen.autocomplete_popup_y_offset);
        mPopupMinYPx = res.getDimensionPixelSize(R.dimen.autocomplete_popup_min_y);
        mPopupContentAlpha = res.getFraction(R.fraction.autocomplete_popup_content_alpha, 1, 1);
        mPopupShadowAlpha = res.getFraction(R.fraction.autocomplete_popup_shadow_alpha, 1, 1);
        // Build the popup-window manager, handing it the pre-read resource dimensions.
        mPopupManager = new AutoCompletePopupManager(context, this, colorSchemeManager,
                mPopupCornerRadiusPx, mPopupElevationPx,
                mPopupItemPadHPx, mPopupItemPadVPx,
                mPopupMinWidthPx, mPopupWidthMarginPx,
                mPopupWidthFraction, mPopupXOffsetPx,
                mPopupEdgeMarginPx, mPopupYOffsetPx,
                mPopupMinYPx, mPopupContentAlpha, mPopupShadowAlpha);
        // Swipe-to-select gesture handler: needs the live input field (it may be
        // swapped by tests) plus callbacks to refresh suggestions and to set/clear
        // the auto-complete suppression guard on gesture start/end. The swipe uses
        // its OWN suppress flag (distinct from the tap-to-insert guard) so a tap
        // cannot accidentally clear a swipe's suppression and vice-versa.
        mSwipeHandler = new AutoCompleteSwipeHandler(mContext,
                () -> mInputField,
                this::refreshAfterSwipe,
                () -> setSwipeSuppress(true),
                () -> setSwipeSuppress(false));
        attachInputListeners();
    }

    // ── AutoCompleteDataProvider: expose suggestion data + callbacks to the popup manager ──

    @Override @NonNull public ArrayList<String> getSuggestions() { return mCurrentSuggestions; }
    @Override @NonNull public ArrayList<Boolean> getIsShell() { return mCurrentIsShell; }
    @Override public int getDisplayMax() { return mDisplayMax; }
    @Override public boolean isSwipeActive() { return mSwipeHandler.isEngaged(); }
    @Override @Nullable public EditText getInputField() { return mInputField; }
    @Override @NonNull public TermuxColorSchemeManager getColorSchemeManager() { return mColorSchemeManager; }
    @Override @Nullable public Window getWindow() { return getWindowInternal(); }
    @Override @NonNull public TextView buildSuggestionTextView(@NonNull String suggestion, @NonNull String input, boolean isShell) {
        return buildSuggestionTextViewInternal(suggestion, input, isShell);
    }
    @Override public int getHistoryVersion() { return mMessageHistoryCtrl.getHistoryVersion(); }
    @Override public void onSuggestionDismissed() {
        // Clear the suggestion data the controller owns.
        mCurrentSuggestions.clear();
        mCurrentIsShell.clear();
        mLastAppliedPrefix = "";
    }

    // ── Wiring callbacks (optional) ───────────────────

    public void setMessageHistoryDismissListener(@NonNull Runnable listener) {
        mMessageHistoryDismissListener = listener;
    }

    public void setCwdProvider(@NonNull CwdProvider provider) {
        mCwdProvider = provider;
    }

    public void setInvalidState(boolean invalid) {
        mIsInvalidState = invalid;
    }

    /** Test-only: current merged suggestion list (history only). */
    java.util.ArrayList<String> debugSuggestions() {
        return mCurrentSuggestions;
    }

    /** Test-only: parallel isShell flags for {@link #debugSuggestions()}. */
    java.util.ArrayList<Boolean> debugIsShell() {
        return mCurrentIsShell;
    }

    /** Test-only: install the text the dispatcher believes preceded this change. */
    void debugSetPrevText(@NonNull String prev) {
        mAutoCompletePrevText = prev;
    }

    /** Test-only: install the change-count (new chars inserted) the dispatcher expects. */
    void debugSetChangeCount(int count) {
        mAutoCompleteChangeCount = count;
    }

    /** Test-only: install the before-count (chars replaced) the dispatcher expects. */
    void debugSetChangeBefore(int before) {
        mAutoCompleteChangeBefore = before;
    }

    /** Test-only: total number of suggestions that will be rendered (capped by maxCount). */
    int debugDisplayCount() {
        return Math.min(mCurrentSuggestions.size(), mDisplayMax);
    }

    /**
     * Release resources. Call from the host's lifecycle teardown.
     */
    public void destroy() {
        mSwipeHandler.resetIfEngaged();
        mSwipeSuppressed = false;
        mSuppressAutoComplete = false;
        if (mComposingCoalesce != null) { mImeHandler.removeCallbacks(mComposingCoalesce); mComposingCoalesce = null; }
    }

    public void setSuppressAutoComplete(boolean suppress) {
        mSuppressAutoComplete = suppress;
    }

    /**
     * Mute all text-change handling while a session's saved input is restored into the
     * field via a programmatic setText(). Set true before setText() and false after.
     *
     * <p>While true, afterTextChanged returns immediately, so no recompute (sync or
     * deferred) is ever queued for the restore — the popup stays dismissed for the
     * restored line and live typing afterwards is unaffected. Crucially this must NOT
     * cancel any pending recompute from real user input (e.g. a backspace taken on the
     * previous tab): the restore's own setText() never reaches the coalesce/post path
     * because it is muted first, so there is nothing of ours to drop, and dropping a
     * user's pending recompute would make the popup appear frozen.
     */
    public void setRestoringInput(boolean restoring) {
        mRestoringInput = restoring;
    }

    /** Set/clear the swipe-gesture auto-complete suppression guard (distinct from the tap guard). */
    void setSwipeSuppress(boolean suppress) {
        mSwipeSuppressed = suppress;
    }

    /** Force a history-version mismatch so the next update takes Path A (full rescan). */
    public void invalidateHistoryVersion() {
        mLastBuiltHistoryVersion = -1;
        mPrefixCacheVersion = -1;
        mPrefixTrie = null;
    }

    // ── Public entry points ───────────────────────────

    /** Called from the host's focus change handler when the input field loses focus. */
    public void onInputFocusLost() {
        // A lost focus mid-gesture means the swipe can never complete; clear its
        // suppression guard so auto-complete is not permanently disabled.
        mSwipeHandler.resetIfEngaged();
        mSwipeSuppressed = false;
        dismissAutoCompleteSuggestions();
    }

    /** Reposition the popup (e.g. after a caret move with no text change). */
    public void onCaretMoved() {
        repositionAutoCompletePopup();
    }

    /** Triggered by the host when the input text changes (compatibility entry point). */
    public void onTextChanged() {
        updateAutoCompleteSuggestions();
    }

    /** Dismiss and clear the suggestions popup. */
    public void dismiss() {
        mSwipeHandler.resetIfEngaged();
        mSwipeSuppressed = false;
        dismissAutoCompleteSuggestions();
    }

    /** Whether either the shell or the history suggestion window is currently showing. */
    public boolean isShowing() {
        return mPopupManager.isShowing();
    }

    /** Load suggestions for the current directory (delegates to the history controller). */
    public void loadSuggestions() {
        mMessageHistoryCtrl.load(mCwdProvider.getCwd());
    }

    /** Remember a non-empty sent message in the history (dedup, newest first). */
    public void addToMessageHistory(@NonNull String message) {
        if (TextUtils.isEmpty(message)) return;
        mMessageHistoryCtrl.addToMessageHistory(message, mCwdProvider.getCwd());
    }

    // ── Internal: listeners ───────────────────────────

    private void attachInputListeners() {
        mInputField.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                // Snapshot the text as an immutable String. The EditText's text is a
                // live, mutable Editable shared by reference with s; storing s directly
                // would mean prevText mutates in place to the NEW text by the time
                // updateAutoCompleteSuggestions() runs, defeating every text comparison
                // (equals, length-delta, prefix regionMatches) and corrupting the
                // additive/backspace detection. A toString() copy freezes the pre-edit
                // value, which is exactly what "prevText" must mean.
                mAutoCompletePrevText = s == null ? "" : s.toString();
                mAutoCompleteChangeStart = start;
                // Cheap composing signal, captured in advance. after!=count means a
                // range replace/insert/delete (composition, paste, autocorrect,
                // delete) — i.e. NOT a clean committed character (where after==count==1).
                // Committed input is synchronous and instant; for composing events
                // afterTextChanged collapses the chain via mImeHandler.post (NO timer).
                // The hasComposingSpan span-scan is thus never called on the hot path
                // of a committed character.
                mComposingChangePending = (after - count) != 0;
            }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                mAutoCompleteChangeBefore = before;
                mAutoCompleteChangeCount = count;
            }
            @Override
            public void afterTextChanged(android.text.Editable s) {
                // A session's saved input is being restored into the field (tab switch /
                // panel re-show). Mute the whole event so the popup stays dismissed for the
                // restored line and no recompute is deferred.
                if (mRestoringInput) return;
                // Safety net against a "stuck" swipe suppression: if the gesture's
                // UP/CANCEL was never delivered (e.g. app backgrounded mid-swipe) the
                // suppress guard would stay latched and auto-complete would stay
                // silent on every keystroke. Clear the guard whenever a swipe is no
                // longer genuinely in progress (engaged=false covers a dead gesture
                // whose UP/CANCEL was lost — the original condition required
                // !isPointerDown(), which the dead gesture also satisfies, so it never
                // fired). Clearing on !isEngaged() actually resets the stuck state.
                if (mSwipeSuppressed && !mSwipeHandler.isEngaged()) {
                    mSwipeHandler.resetIfEngaged();
                }
                final EditText f = mInputField;
                // A "composing" change (after != count) is ambiguous: it is true for BOTH
                // real IME composition AND for plain delete/insert/paste of a range.
                //
                // CRITICAL: any DELETION (before > after, i.e. characters removed) must be
                // recomputed SYNCHRONOUSLY, never deferred. During IME composition the
                // composing span is still attached to the (now shorter) word, so
                // hasComposingSpan() returns true for a backspace — and deferring it to a
                // posted run makes updateAutoCompleteSuggestions() hit the caret/composition
                // bounce and dismiss or freeze the popup (the reported "popup stops updating
                // on backspace" bug). So we only take the deferred compose path when this is
                // a genuine additive composition (after > before AND a composing span is
                // present). A pure deletion is always treated as a committed edit.
                boolean isDeletion = mComposingChangePending && (mAutoCompleteChangeBefore > mAutoCompleteChangeCount);
                boolean realCompose = f != null && mComposingChangePending && !isDeletion && hasComposingSpan(f);
                if (realCompose) {
                    if (mComposingCoalesce != null) mImeHandler.removeCallbacks(mComposingCoalesce);
                    final CharSequence snapshotPrev = mAutoCompletePrevText;
                    mComposingCoalesce = () -> {
                        mComposingCoalesce = null;
                        if (mSuppressAutoComplete || mSwipeSuppressed) return;
                        updateAutoCompleteSuggestions();
                    };
                    mImeHandler.post(mComposingCoalesce); // collapses the compose chain into 1 recompute, NO timer
                    return;
                }
                if (mComposingCoalesce != null) { mImeHandler.removeCallbacks(mComposingCoalesce); mComposingCoalesce = null; }
                updateAutoCompleteSuggestions(); // a committed character (or a backspace) is computed instantly
            }
        });

        // Reposition the auto-complete popup when the caret moves without text changes
        // (e.g. tapping a different position in the text). We use ACTION_UP on the EditText
        // because setOnSelectionChangedListener is API 29+ and the project targets API 28.
        mInputField.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_UP) {
                v.post(() -> {
                    if (mInputField == null) return;
                    android.text.Editable t = mInputField.getText();
                    if (mInputField.getSelectionStart() == t.length()) repositionAutoCompletePopup();
                });
            }
            return false; // don't consume the event, let the EditText handle it
        });
    }

    @Nullable
    private Window getWindowInternal() {
        if (mContext instanceof Activity) return ((Activity) mContext).getWindow();
        return null;
    }

    /**
     * True when the input field's text currently carries an IME composing span.
     * Detected via the public {@link android.text.Spanned#SPAN_COMPOSING} flag
     * (the hidden {@code android.text.style.ComposingSpan} class is not part of
     * the public SDK, so we inspect span flags instead). During composition the
     * reported caret may transiently drift off the text end, so callers use this
     * to avoid a spurious dismiss while the user is still additively typing.
     */
    static boolean hasComposingSpan(@Nullable EditText inputField) {
        if (inputField == null) return false;
        CharSequence cs = inputField.getText();
        if (!(cs instanceof android.text.Spanned)) return false;
        android.text.Spanned sp = (android.text.Spanned) cs;
        Object[] spans = sp.getSpans(0, sp.length(), Object.class);
        for (Object span : spans) {
            if ((sp.getSpanFlags(span) & android.text.Spanned.SPAN_COMPOSING) != 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * Three-way dispatcher for the auto-complete popup:
     *
     * Path A (full rebuild) — called when the user deletes, replaces, pastes, or the
     * history has changed externally.  Re-scans the full mMessageHistoryCtrl.getHistoryList() and creates a
     * brand-new PopupWindow (dismiss + showAtLocation).
     *
     * Path B (additive filter) — called when the user only types more characters without
     * deleting any text.  Filters mCurrentSuggestions in place (O(maxCount) instead of
     * O(mMessageHistoryCtrl.getHistoryList())), removes non-matching views from the existing popup, top-ups
     * from history if the result is smaller than maxCount, recalculates bold spans, and
     * updates the popup size/position (one IPC instead of two).
     *
     * Path C (reposition only) — not truly a separate path here; when the text hasn't
     * changed w.r.t. the previous call the OnGlobalLayoutListener and OnTouchListener
     * already call repositionAutoCompletePopup() separately.  The dispatcher here always
     * receives a text-change event.
     */

    /**
     * Called after a swipe-to-select gesture commits arbitrary text into the input
     * field. The incremental additive filter cannot handle the resulting text (it is
     * not a pure prefix extension of the pre-swipe line), so we force a full rescan
     * on the next dispatch and run it off the touch path (the caller posts this).
     */
    private void refreshAfterSwipe() {
        mForceFullRescan = true;
        updateAutoCompleteSuggestions();
    }

    private void updateAutoCompleteSuggestions() {
        if (mIsInvalidState) {
            return;
        }
        if (mSuppressAutoComplete || mSwipeSuppressed) {
            return;
        }

        final EditText inputField = mInputField;
        if (inputField == null) {
            return;
        }

        final String text = inputField.getText().toString();

        if (TextUtils.isEmpty(text)) {
            dismissAutoCompleteSuggestions();
            return;
        }

        // A deletion (backspace / range delete) is detected by comparing against the
        // pre-edit snapshot. During IME composition the reported caret can transiently
        // sit INSIDE the still-attached composing span (caret != text.length()) even
        // right after a backspace, which would otherwise send us into the caret bounce
        // below and dismiss/freeze the popup. For a deletion we must NOT bounce: the
        // correct behaviour is to shorten the filter and refresh. We accept the deletion
        // whenever the caret (or the selection end) is at the end of the shortened text,
        // which is the normal end-of-line backspace case.
        final CharSequence prevSnapshot = mAutoCompletePrevText;
        final boolean deletion = prevSnapshot != null && prevSnapshot.length() > text.length()
                && TextUtils.regionMatches(prevSnapshot, 0, text, 0, text.length());

        // Only show auto-complete when the caret sits at the end of the input field.
        // When the caret is anywhere else the contextual popup must stay hidden —
        // UNLESS a swipe-to-select gesture is in progress (the gesture deliberately
        // holds a selection inside the text; dismissing mid-swipe would defeat it).
        int caret = inputField.getSelectionStart();
        boolean caretAtEnd = (caret == text.length())
                || (deletion && inputField.getSelectionEnd() == text.length());
        if (caret < 0 || !caretAtEnd) {
            // Do NOT dismiss while an IME composition is in progress: during compose
            // the reported selection can transiently sit inside the composing span
            // (caret != length) even though the user is still additively typing.
            // Treat it like an active swipe — keep the popup and bail out.
            if (isSwipeActive()) {
                return;
            }
            if (hasComposingSpan(inputField)) {
                if (deletion) {
                    // A backspace during composition: do NOT bounce to bold-only and do
                    // NOT dismiss. Fall through so the deletion handler (below) shortens
                    // the filter and refreshes the popup. Dismissing here is exactly the
                    // "popup stops updating on backspace" bug.
                } else {
                    // Only keep the popup (bold-only refresh, no rebuild/dismiss) when the
                    // shown suggestions still actually match the composing text. A
                    // glide/swipe-typed word that matches no suggestion can leave the
                    // composing span attached until the next word is typed; in that case
                    // the stale, mismatched popup must NOT be kept — fall through so the
                    // recompute below dismisses it (fullRescanSuggestions also refuses to
                    // keep an empty, non-matching result while composing).
                    if (isShowing() && suggestionsMatchText(text)) {
                        return; // popup shows correct suggestions, no bold update needed
                    }
                    // Not a matching composition: do not bail here — let the code below
                    // re-scan and dismiss the now-irrelevant popup.
                }
            }
            dismissAutoCompleteSuggestions();
            return;
        }

        int maxCount = mDisplayMax;
        if (maxCount == 0) {
            dismissAutoCompleteSuggestions();
            return;
        }

        // ── Early skip for IME re-compose of same text ──
        // Gboard (and likely other IMEs) re-composes an unchanged composing span
        // on certain interactions (e.g. after tapping a suggestion candidate in
        // the IME's own bar). The text is identical to prevText, so the additive
        // detection correctly says false (length didn't grow), but Path A would
        // re-scan history and rebuild the popup unnecessarily. Skip the entire
        // update when text is unchanged, the history version is current, the
        // popup is already showing, AND the shown suggestions still actually
        // match the typed text. The last clause is essential: a glide/swipe-typed
        // commit can replace the line with a word matching no suggestion while
        // prevText was rewritten to the same committed text — without the match
        // check the stale, mismatched popup would be kept until the next edit.
        CharSequence prevText = mAutoCompletePrevText;
        if (text.equals(prevText) && mAutoCompleteChangeCount == mAutoCompleteChangeBefore) {
            if (mMessageHistoryCtrl.getHistoryVersion() == mLastBuiltHistoryVersion
                    && isShowing()
                    && !mCurrentSuggestions.isEmpty()
                    && suggestionsMatchText(text)) {
                return;
            }
        }

        // ── Detect whether this is an additive (append-only) change ──
        // Language/IME-agnostic: the new text must extend prevText by appending
        // (prevText stays a prefix and text grew). We do NOT require before == 0,
        // because IME composition (e.g. Gboard Cyrillic) replaces the composing span
        // on every keystroke (before > 0), which made the old check take Path A on
        // each Cyrillic char. The non-empty list guard forces Path A to bootstrap on
        // the first keystroke (Path B on an empty list would wrongly dismiss).
        boolean additive = false;
        if (text.length() > prevText.length()
                && TextUtils.regionMatches(text, 0, prevText, 0, prevText.length())
                && !mCurrentSuggestions.isEmpty()) {
            additive = true; // clean append of characters at the end
        }
        // After a swipe the text changes arbitrarily (not a clean prefix append),
        // so the incremental filter is inapplicable — force a full rescan.
        if (mForceFullRescan) {
            additive = false;
            mForceFullRescan = false;
        }

        // ── Backspace: lightweight path without a full rescan ──
        // If the text is shorter than prevText and prevText starts with text
        // (characters deleted from the end), filter the current list by prefix
        // instead of taking Path A.
        if (!additive && mCurrentSuggestions != null && !mCurrentSuggestions.isEmpty()
                && prevText != null && prevText.length() > text.length()
                && TextUtils.regionMatches(prevText, 0, text, 0, text.length())) {
            // Reuse the scratch list instead of a new ArrayList on every Backspace.
            ArrayList<String> filtered = mBackspaceScratch;
            filtered.clear();
            for (String s : mCurrentSuggestions) {
                if (s != null && s.regionMatches(true, 0, text.toString(), 0, text.length())) filtered.add(s);
            }
            if (filtered.isEmpty()) {
                // dropped to 0 — full rescan
            } else {
                mCurrentSuggestions.clear();
                mCurrentSuggestions.addAll(filtered);
                updatePopupContent(text, inputField);
                return;
            }
        }

        // ── Path A (full rebuild): not additive OR history changed externally ──
        if (!additive || mMessageHistoryCtrl.getHistoryVersion() != mLastBuiltHistoryVersion) {
            fullRescanSuggestions(text, maxCount);
            return;
        }

        // ── Path B (additive filter): only appending characters ──
        // Filter mCurrentSuggestions in-place using the single unified routine that
        // matches shell candidates against the last word and history against the whole
        // line (kept in lock-step with mCurrentIsShell). The cached, full shell list
        // stays intact so no re-query is needed; only local narrowing happens.
        final int preFilterCount = mCurrentSuggestions.size();
        filterSuggestionsByPrefix(text, mCurrentSuggestions, mCurrentIsShell);
        int filteredRemoved = preFilterCount - mCurrentSuggestions.size();

        if (mCurrentSuggestions.isEmpty()) {
            // The additive filter emptied the list. This happens when there was
            // nothing to filter to begin with (e.g. the very first keystroke after
            // the field was cleared, so mCurrentSuggestions is still empty rather
            // than a previously-shown popup being filtered down). A plain dismiss
            // here would silently drop a legitimate character. Fall back to a full
            // history rescan: it shows suggestions if any match, otherwise it still
            // dismisses correctly.
            fullRescanSuggestions(text, maxCount);
            return;
        }

        // Top-up from history if filtered list is smaller than maxCount.
        // Reuse the prefix trie instead of scanning the full history again.
        if (mCurrentSuggestions.size() < maxCount) {
            if (mPrefixTrie == null
                    || mPrefixCacheVersion != mMessageHistoryCtrl.getHistoryVersion()) {
                buildTrie();
            }
            mSeenSet.clear();
            mSeenSet.addAll(mCurrentSuggestions);
            final int tLen = text.length();
            for (String msg : getCandidatesForPrefix(text)) {
                if (mCurrentSuggestions.size() >= maxCount) break;
                if (mSeenSet.add(msg)
                        && msg.length() > tLen
                        && !msg.equals(text)) {
                    mCurrentSuggestions.add(msg);
                }
            }
        }


        // If neither window is showing yet, build them fresh
        if (!isShowing()) {
            showAutoCompletePopup(inputField);
            return;
        }

        // In-place update of the existing popup content
        updatePopupContent(text, inputField);
    }

    /**
     * Rebuild the prefix trie over the entire (live, newest-first) history list.
     * Called only when the history version changed (so the live list may differ
     * from the cached snapshot) or the trie is null. Each history command is
     * inserted char-by-char; every node along its path collects the command in
     * its {@link TrieNode#words} list, so a descent for any prefix returns the
     * already-filtered, still-newest-first candidate list in O(L) where L is the
     * prefix length.
     */
    private void buildTrie() {
        mPrefixTrie = new TrieNode();
        for (String msg : mMessageHistoryCtrl.getHistoryList()) {
            if (msg == null || msg.isEmpty()) continue;
            String lower = msg.toLowerCase();
            TrieNode node = mPrefixTrie;
            node.words.add(msg);
            for (int i = 0; i < lower.length(); i++) {
                int c = lower.charAt(i);
                TrieNode child = node.children.get(c);
                if (child == null) { child = new TrieNode(); node.children.put(c, child); }
                child.words.add(msg);
                node = child;
            }
        }
        mPrefixCacheVersion = mMessageHistoryCtrl.getHistoryVersion();
    }

    /**
     * Return every history command that has {@code text} as a (case-insensitive)
     * prefix, latest-first (insertion order). Descends the trie in O(L) and
     * returns the node's already-filtered word list; an empty list if the prefix
     * has no matches (or the trie is unbuilt).
     */
    @NonNull
    private ArrayList<String> getCandidatesForPrefix(@NonNull String text) {
        if (mPrefixTrie == null) return EMPTY_LIST;
        TrieNode node = mPrefixTrie;
        // Descend the prefix WITHOUT materializing a lowercase copy of the whole
        // field (text.toLowerCase() would allocate a string as long as the entire
        // input on every top-up character). The trie stores keys in lowercase, so
        // we compare each character via Character.toLowerCase on the fly.
        for (int i = 0; i < text.length(); i++) {
            node = node.children.get(Character.toLowerCase(text.charAt(i)));
            if (node == null) return EMPTY_LIST; // no matches for this prefix
        }
        // node.words is already in newest-first order and is used read-only (callers
        // only iterate it and add elements into mCurrentSuggestions, never mutating
        // node.words itself) — return it directly without a copy, avoiding an
        // ArrayList allocation on every character.
        return node.words;
    }

    /**
     * Path A: full re-scan of the message history. History suggestions are gathered
     * immediately and the popup is shown (or dismissed if empty).
     */
    private void fullRescanSuggestions(@NonNull String text, int maxCount) {
        // The user's max setting governs how many items are RENDERED.
        mDisplayMax = maxCount;

        mCurrentSuggestions.clear();
        mCurrentIsShell.clear();

        // Ensure the prefix trie is valid: rebuild if the history version changed
        // (a single version-keyed rebuild covers every prefix at once).
        if (mPrefixTrie == null
                || mPrefixCacheVersion != mMessageHistoryCtrl.getHistoryVersion()) {
            buildTrie();
        }

        // History suggestions: the trie already narrowed history to the typed
        // prefix, so just exclude the exact-typed line and apply maxCount + dedup.
        mSeenSet.clear();
        final int tLen = text.length();
        for (String msg : getCandidatesForPrefix(text)) {
            if (mCurrentSuggestions.size() >= maxCount) break;
            if (mSeenSet.add(msg)
                    && msg.length() > tLen
                    && !msg.equals(text)) {
                mCurrentSuggestions.add(msg);
                mCurrentIsShell.add(Boolean.FALSE);
            }
        }


        if (mCurrentSuggestions.isEmpty()) {
            // During an active composition keep the popup only when it is still
            // showing suggestions that match what the user typed — otherwise a
            // glide/swipe-typed word matching nothing would leave the stale popup
            // stuck until the next edit. If composition is active but the typed
            // text no longer matches the (previously shown) suggestions, dismiss.
            if (mInputField != null && hasComposingSpan(mInputField)
                    && suggestionsMatchText(text)) {
                return; // genuinely composing a matching prefix — wait for commit
            }
            dismissAutoCompleteSuggestions();
        } else {
            showAutoCompletePopup(mInputField);
        }
    }

    /**
     * True only when every currently shown suggestion still has {@code text} as a
     * prefix (case-insensitive, matching the prefix logic used elsewhere for
     * filtering). Used by the early-skip guard and the composition dismiss-guard so
     * a popup is kept across an unchanged-text recompose ONLY when it is actually
     * relevant to what the user typed. A glide/swipe commit that leaves prevText
     * equal to the committed word but with stale suggestions must NOT pass this
     * check, which forces a re-scan that dismisses the mismatched popup.
     */
    private boolean suggestionsMatchText(@NonNull CharSequence text) {
        if (mCurrentSuggestions.isEmpty()) return false;
        final int len = text.length();
        if (len == 0) return false;
        for (int i = 0; i < mCurrentSuggestions.size(); i++) {
            final String s = mCurrentSuggestions.get(i);
            if (s == null || s.length() < len || !s.regionMatches(true, 0, text.toString(), 0, len)) {
                return false;
            }
        }
        return true;
    }

    /** Path separator set used by {@link AutoCompleteTextRenderer#wordStartOffset} to find word boundaries. */
    private static final String WORD_SEPARATORS = " /";  // space + slash

    /**
     * Returns the last whitespace-delimited token of {@code s} (the word currently
     * being typed/completed). Words are delimited by any character in
     * {@link #WORD_SEPARATORS}. History candidates continue to be matched against
     * the whole line.
     */
    @NonNull
    static String lastWordOf(@NonNull String s) {
        int end = s.length();
        int start = end;
        while (start > 0) {
            char c = s.charAt(start - 1);
            if (c == ' ' || c == '/') break;
            start--;
        }
        return s.substring(start, end);
    }

    /**
     * Last word with one layer of surrounding shell quotes stripped, matching the
     * unquoted form used for local prefix-matching.
     */
    @NonNull
    static String lastWordOfStripped(@NonNull String s) {
        String last = lastWordOf(s);
        if (last.length() >= 2) {
            char first = last.charAt(0);
            char lastc = last.charAt(last.length() - 1);
            if ((first == '\'' && lastc == '\'') || (first == '"' && lastc == '"')) {
                return last.substring(1, last.length() - 1);
            }
        }
        return last;
    }

    /**
     * Unified local filter used by every additive (Path B) update. Mutates the
     * parallel {@code sugg} / {@code isShell} lists in lock-step, dropping entries
     * that no longer match the typed text. Shell-completion candidates are matched
     * against the LAST WORD of the line (the token actually being completed);
     * history candidates are matched against the WHOLE line. A trailing slash on a
     * shell prefix also accepts candidates equal to the prefix minus that slash
     * (the provider strips trailing {@code '/'} during normalization, so a typed
     * "dir/" must still keep the normalized "dir" candidate).
     */
    void filterSuggestionsByPrefix(@NonNull String text,
            @NonNull List<String> sugg, @NonNull List<Boolean> isShell) {
        int tLen = text.length();
        for (int i = sugg.size() - 1; i >= 0; i--) {
            String s = sugg.get(i);
            int pLen = tLen;
            boolean matches = (pLen == 0)
                    || (s.regionMatches(true, 0, text.toString(), 0, pLen)
                        || (text.endsWith("/")
                            && s.regionMatches(true, 0, text.toString(), 0, pLen - 1)));
            if (s.length() <= pLen || !matches || s.equals(text)) {
                sugg.remove(i);
                if (i < isShell.size()) isShell.remove(i);
            }
        }
    }

    /**
     * Path B: update the existing popups in-place after an additive text change.
     *
     * <p>The controller first top-ups the suggestion list from history (data
     * concern), then hands off to {@link AutoCompletePopupManager} which rebuilds
     * the per-window content, refreshes spans and resizes/positions the two
     * windows (shell stacked above history).
     */
    private void updatePopupContent(@NonNull String newText, @NonNull EditText inputField) {
        // mCurrentSuggestions is already filtered and top-upped (Path B's
        // filterSuggestionsByPrefix + top-up in updateAutoCompleteSuggestions).
        // Only refresh the popup (mDisplayMax already holds the render cap).
        mPopupManager.update(newText, inputField);
    }

    /**
     * Number of suggestions to actually render (capped at the user's max setting),
     * even though {@link #mCurrentSuggestions} may store more candidates for
     * Path B local filtering.
     */
    // ── Package-private debug hooks for unit tests ──
    // (debugSuggestions / debugIsShell are declared earlier.)

    /** Current incremental-change field used by the additive-detection logic. */
    void debugSetChangeState(@NonNull String prevText, int changeCount) {
        mAutoCompletePrevText = prevText;
        mAutoCompleteChangeCount = changeCount;
        mAutoCompleteChangeBefore = 0;
    }

    /**
     * Seed the current suggestion list with history items (isShell = false),
     * resetting the shell group. Lets merge tests populate the controller state
     * without going through the popup-building code paths.
     */
    void debugSeedHistorySuggestions(@NonNull String... history) {
        mCurrentSuggestions.clear();
        mCurrentIsShell.clear();
        for (String h : history) {
            mCurrentSuggestions.add(h);
            mCurrentIsShell.add(Boolean.FALSE);
        }
    }

    /** Build a single suggestion TextView (reusable helper). */
    private TextView buildSuggestionTextViewInternal(@NonNull String suggestion, @NonNull String input, boolean isShell) {
        int padH = mPopupItemPadHPx;
        int padV = mPopupItemPadVPx;
        TextView tv = new TextView(mContext);

        tv.setTextColor(mColorSchemeManager.getHistoryTextColor());
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        tv.setPadding(padH, padV, padH, padV);

        // Available text width = popup width minus horizontal padding. Mirrors the
        // width the popup is sized to in showAutoCompletePopup (sumWidth).
        int fieldWidth = mInputField != null ? mInputField.getWidth() : 0;
        int popupWidth = mPopupManager.computePopupWidth(fieldWidth);
        int availWidth = Math.max(0, popupWidth - 2 * padH);

        // Word-based leading truncation + manual trailing '…' (TextView's
        // setEllipsize(END) is unreliable for maxLines>1 on API 21-28).
        SpannableString ss = AutoCompleteTextRenderer.buildSuggestionSpannable(
                suggestion, input, availWidth, tv.getPaint(), isShell);
        tv.setText(ss, TextView.BufferType.SPANNABLE);
        tv.setMaxLines(2);
        tv.setEllipsize(TextUtils.TruncateAt.END); // backup; text already fits 2 lines
        tv.setTextColor(mColorSchemeManager.getHistoryTextColor());
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        tv.setPadding(padH, padV, padH, padV);
        // Accessibility: describe shells vs history and the candidate type.
        tv.setContentDescription((isShell ? "Completion: " : "History: ") + suggestion);
        tv.setTag(suggestion);
        // Solid press highlight matching the message-history popup (per-theme)
        StateListDrawable sel = new StateListDrawable();
        int highlightColor = mColorSchemeManager.getHistoryHighlightFill();
        sel.addState(new int[]{android.R.attr.state_pressed},
                new ColorDrawable(highlightColor));
        sel.addState(new int[]{},
                new ColorDrawable(Color.TRANSPARENT));
        sel.setEnterFadeDuration(0);
        sel.setExitFadeDuration(0);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            tv.setBackground(sel);
        } else {
            tv.setBackgroundDrawable(sel);
        }
        tv.setClickable(true);
        final String finalSuggestion = suggestion;

        tv.setOnClickListener(v -> {
            mSuppressAutoComplete = true;
            try {
                if (mInputField != null) {
                    insertCandidate(finalSuggestion);
                }
            } finally {
                mSuppressAutoComplete = false;
            }
            // Promote the selected item to the front of history (index 0 = newest),
            // so in the reversed rendering it appears at the very bottom.
            addToMessageHistory(finalSuggestion);
            invalidateHistoryVersion();
            dismissAutoCompleteSuggestions();
            mMessageHistoryDismissListener.run();
        });
        tv.setOnTouchListener(mSwipeHandler::onTouch);
        return tv;
    }

    /**
     * Insert a chosen suggestion into the input field. The popup only ever shows a
     * candidate that starts with the typed text (history: whole-line prefix; shell:
     * last-word prefix), so we replace the maximal trailing substring of the typed
     * text that is itself a prefix of the candidate. This unifies both cases and
     * avoids duplicating the already-typed leading part — e.g. typing "git com"
     * and picking "git commit -m" yields "git commit -m", not "git git commit -m".
     */
    private void insertCandidate(@NonNull String candidate) {
        if (mInputField == null) return;
        Editable editable = mInputField.getText();
        if (editable == null) return;
        String text = editable.toString();
        int textLen = text.length();

        int caret = mInputField.getSelectionStart();
        if (caret < 0 || caret > textLen) caret = textLen;
        // When the caret sits inside the line, only the text BEFORE the caret can
        // be the typed prefix we replace; anything after the caret is kept intact.
        int caretPos = caret;

        // Longest suffix of text[0..caretPos) that is a prefix of candidate.
        int matchLen = 0;
        int maxK = Math.min(caretPos, candidate.length());
        for (int k = maxK; k >= 1; k--) {
            if (text.regionMatches(caretPos - k, candidate, 0, k)) {
                matchLen = k;
                break;
            }
        }

        int replaceStart = caretPos - matchLen;
        int replaceEnd = caretPos;

        String newText = text.substring(0, replaceStart) + candidate + text.substring(replaceEnd);
        mInputField.setText(newText);
        mInputField.setSelection(replaceStart + candidate.length());
    }

    private void showAutoCompletePopup(@NonNull EditText inputField) {
        mPopupManager.show(inputField);
    }

    /** Reposition the auto-complete popups at the current caret position. */
    public void repositionAutoCompletePopup() {
        if (!isShowing()) return;
        // Hide the popups if the caret is no longer at the end of the input field —
        // unless a swipe gesture holds a deliberate selection (keep it alive).
        if (mInputField != null && !isSwipeActive() && !hasComposingSpan(mInputField)) {
            int caret = mInputField.getSelectionStart();
            if (caret < 0 || caret != mInputField.getText().length()) {
                dismissAutoCompleteSuggestions();
                return;
            }
        }
        final EditText inputField = mInputField;
        if (inputField == null) return;
        applyPopupGeometry(inputField);
    }

    private void applyPopupGeometry(@NonNull EditText inputField) {
        mPopupManager.applyGeometry(inputField);
    }

    private void dismissAutoCompleteSuggestions() {
        mPopupManager.dismiss();
    }

    // ── Additional test-only accessors (package-private) ──

    /** Directly inject a history list for deterministic dispatch tests. */
    void debugSetHistory(@NonNull java.util.List<String> history) {
        mMessageHistoryCtrl.clearAllPerDirectory();
        for (String h : history) mMessageHistoryCtrl.addToMessageHistory(h, ".");
    }

    /** Test-only: install a null input field to exercise the mInputField==null guard. */
    void debugSetInputFieldNull() {
        mInputField = null;
    }

    /** Test-only: restore a (non-null) input field after {@link #debugSetInputFieldNull()}. */
    void debugSetInputField(@NonNull EditText field) {
        mInputField = field;
    }

    /** Test-only: the current input field (so tests can mutate its text). */
    @Nullable EditText debugGetInputField() {
        return mInputField;
    }

    /** Test-only: the message-history window, or null when not shown. */
    @Nullable PopupWindow debugHistoryPopup() {
        return mPopupManager.debugHistoryPopup();
    }

    /** Test-only: the history window's LinearLayout content. */
    @Nullable LinearLayout debugHistoryContent() {
        return mPopupManager.debugHistoryContent();
    }

    /** Test-only: last computed Y of the history window. */
    int debugGetHistoryY() {
        return mPopupManager.debugGetHistoryY();
    }

    /** Test-only: directly (re)compute popup geometry for the current input field. */
    void debugApplyPopupGeometry() {
        applyPopupGeometry(debugGetInputField());
    }
}

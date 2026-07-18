package com.termux.app.terminal.io.autocomplete;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Build;
import android.text.Editable;
import android.text.Layout;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
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

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

import com.termux.BuildConfig;
import com.termux.R;
import com.termux.app.terminal.TermuxColorSchemeManager;
import com.termux.shared.logger.Logger;


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

    private static final String LOG_TAG = "AutoCompleteController";

    /** Shared BOLD style span instance (reused across all suggestion rows). */
    private static final StyleSpan BOLD_SPAN = new StyleSpan(Typeface.BOLD);

    /**
     * Persistent trace log for diagnosing the shell-completion flyout. Written to
     * the shared Download folder so it survives and can be pulled without root.
     * Mirrors every interesting auto-complete event (text change, path A/B, fetch
     * gate decision, bash result, merge size) with a millisecond timestamp and a
     * sequence number so the whole pipeline can be reconstructed offline.
     */
    private static final File TRACE_FILE =
            new File("/storage/emulated/0/Download/termux/autocomplete_log.txt");
    private static final AtomicInteger TRACE_SEQ = new AtomicInteger();
    private static volatile boolean sTraceFileOkay = true;

    private static void trace(@NonNull String tag, @NonNull String msg) {
        if (!BuildConfig.DEBUG) return;
        Logger.logInfo(LOG_TAG, msg);
        if (!sTraceFileOkay) return;
        try {
            StringBuilder sb = new StringBuilder();
            sb.append(new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(new Date()));
            sb.append(" #").append(String.format(Locale.US, "%05d", TRACE_SEQ.incrementAndGet()));
            sb.append(" [").append(tag).append("] ").append(msg).append("\n");
            try (FileWriter fw = new FileWriter(TRACE_FILE, true)) {
                fw.write(sb.toString());
            }
        } catch (Throwable t) {
            // Never let tracing break the UI. Disable on first failure.
            sTraceFileOkay = false;
        }
    }

    /** Append a one-line header (call once at session start) marking a fresh run. */
    private static void traceHeader() {
        if (!BuildConfig.DEBUG) return;
        try {
            try (FileWriter fw = new FileWriter(TRACE_FILE, true)) {
                fw.write("\n===== autocomplete trace " +
                        new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()) +
                        " =====\n");
            }
        } catch (Throwable ignored) { /* best effort */ }
    }

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
    /** Suppress auto-complete popup during an active swipe-to-select gesture. */
    private boolean mSwipeSuppressed;

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
                this::updateAutoCompleteSuggestions,
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
                // Keep the CharSequence reference directly (no full-field copy) — the
                // additive check below compares via regionMatches/contentEquals which
                // both accept CharSequence, so the toString() copy is unnecessary.
                mAutoCompletePrevText = s;
                mAutoCompleteChangeStart = start;
                // Дешёвый сигнал composing, перехваченный заранее. after!=count
                // означает замену/вставку/удаление диапазона (composition, paste,
                // autocorrect, delete) — то есть НЕ чистый committed-символ
                // (где after==count==1). Committed-ввод идёт синхронно и мгновенно;
                // для composing-событий послеTextChanged схлопывает цепочку через
                // mImeHandler.post (БЕЗ таймера). Span-скан hasComposingSpan при этом
                // не вызывается на горячем пути committed-символа.
                mComposingChangePending = (after - count) != 0;
            }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                mAutoCompleteChangeBefore = before;
                mAutoCompleteChangeCount = count;
            }
            @Override
            public void afterTextChanged(android.text.Editable s) {
                // Safety net against a "stuck" swipe suppression: if the gesture's
                // UP/CANCEL was never delivered (e.g. app backgrounded mid-swipe) the
                // suppress guard would stay latched and auto-complete would stay
                // silent on every keystroke. If the pointer is no longer down, the
                // swipe can't still be in progress — clear the stale guard so typing
                // brings the popup back.
                if (mSwipeSuppressed && mSwipeHandler.isEngaged() && !mSwipeHandler.isPointerDown()) {
                    mSwipeHandler.resetIfEngaged();
                }
                final EditText f = mInputField;
                // Use the cheap up-front signal instead of scanning all spans on every
                // keystroke (committed input never carries a composing span, so this
                // avoids a getSpans() Object[] allocation per keystroke).
                if (f != null && mComposingChangePending) {
                    if (mComposingCoalesce != null) mImeHandler.removeCallbacks(mComposingCoalesce);
                    mComposingCoalesce = () -> { mComposingCoalesce = null; updateAutoCompleteSuggestions(); };
                    mImeHandler.post(mComposingCoalesce); // схлопывает цепочку compose в 1 пересчёт, БЕЗ таймера
                    return;
                }
                if (mComposingCoalesce != null) { mImeHandler.removeCallbacks(mComposingCoalesce); mComposingCoalesce = null; }
                updateAutoCompleteSuggestions(); // committed-символ считается мгновенно
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
    private void updateAutoCompleteSuggestions() {
        if (mIsInvalidState) {
            if (BuildConfig.DEBUG) trace("ACC", "updateAutoCompleteSuggestions SKIP mIsInvalidState=true");
            return;
        }
        if (mSuppressAutoComplete || mSwipeSuppressed) {
            if (BuildConfig.DEBUG) trace("ACC", "updateAutoCompleteSuggestions SKIP mSuppressAutoComplete=" + mSuppressAutoComplete
                    + " mSwipeSuppressed=" + mSwipeSuppressed);
            return;
        }

        final EditText inputField = mInputField;
        if (inputField == null) {
            if (BuildConfig.DEBUG) trace("ACC", "updateAutoCompleteSuggestions SKIP mInputField==null");
            return;
        }

        final String text = inputField.getText().toString();
        if (BuildConfig.DEBUG) traceHeader();
        if (BuildConfig.DEBUG) trace("ACC", "updateAutoCompleteSuggestions text=\"" + text + "\"");

        if (TextUtils.isEmpty(text)) {
            dismissAutoCompleteSuggestions();
            return;
        }

        // Only show auto-complete when the caret sits at the end of the input field.
        // When the caret is anywhere else the contextual popup must stay hidden —
        // UNLESS a swipe-to-select gesture is in progress (the gesture deliberately
        // holds a selection inside the text; dismissing mid-swipe would defeat it).
        int caret = inputField.getSelectionStart();
        if (caret < 0 || caret != text.length()) {
            // Do NOT dismiss while an IME composition is in progress: during compose
            // the reported selection can transiently sit inside the composing span
            // (caret != length) even though the user is still additively typing.
            // Treat it like an active swipe — keep the popup and bail out.
            if (isSwipeActive()) {
                if (BuildConfig.DEBUG) trace("ACC", "updateAutoCompleteSuggestions SKIP caret not at end (swipe, keep popup)");
                return;
            }
            if (hasComposingSpan(inputField)) {
                if (isShowing()) mPopupManager.updateBoldOnly(text); // только bold без rebuild/dismiss
                if (BuildConfig.DEBUG) trace("ACC", "updateAutoCompleteSuggestions SKIP caret not at end (composing, keep popup, updateBoldOnly)");
                return;
            }
            if (BuildConfig.DEBUG) trace("ACC", "updateAutoCompleteSuggestions SKIP caret not at end (caret=" + caret + " len=" + text.length() + ")");
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
        // update when text is unchanged, the history version is current, and
        // the popup is already showing valid suggestions.
        CharSequence prevText = mAutoCompletePrevText;
        if (text.equals(prevText) && mAutoCompleteChangeCount == mAutoCompleteChangeBefore) {
            if (mMessageHistoryCtrl.getHistoryVersion() == mLastBuiltHistoryVersion
                    && isShowing()
                    && !mCurrentSuggestions.isEmpty()) {
                if (BuildConfig.DEBUG) trace("ACC", "updateAutoCompleteSuggestions SKIP recompose (text==prevText, popup showing)");
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
            additive = true; // чистое добавление символов в конец
        }
        if (BuildConfig.DEBUG) trace("ACC", "prev=\"" + prevText + "\" before="
                + mAutoCompleteChangeBefore + " count=" + mAutoCompleteChangeCount
                + " additive=" + additive + " hVer=" + mMessageHistoryCtrl.getHistoryVersion()
                + "/" + mLastBuiltHistoryVersion + " maxCount=" + maxCount);

        // ── Backspace: лёгкий путь без полного rescAN ──
        // Если текст короче prevText и prevText начинается с text (удаление символов
        // с конца), отфильтруем текущий список по префику вместо Path A.
        if (!additive && mCurrentSuggestions != null && !mCurrentSuggestions.isEmpty()
                && prevText != null && prevText.length() > text.length()
                && TextUtils.regionMatches(prevText, 0, text, 0, text.length())) {
            // Переиспользуем scratch-список вместо new ArrayList на каждый Backspace.
            ArrayList<String> filtered = mBackspaceScratch;
            filtered.clear();
            for (String s : mCurrentSuggestions) {
                if (s != null && s.regionMatches(true, 0, text, 0, text.length())) filtered.add(s);
            }
            if (filtered.isEmpty()) {
                // упали до 0 — полный rescAN
            } else {
                mCurrentSuggestions.clear();
                mCurrentSuggestions.addAll(filtered);
                updatePopupContent(text, inputField);
                return;
            }
        }

        // ── Path A (full rebuild): not additive OR history changed externally ──
        if (!additive || mMessageHistoryCtrl.getHistoryVersion() != mLastBuiltHistoryVersion) {
            if (BuildConfig.DEBUG) trace("ACC", "→ PATH A (fullRescan) reason="
                    + (!additive ? "non-additive" : "history=" + mMessageHistoryCtrl.getHistoryVersion() + "≠" + mLastBuiltHistoryVersion));
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
        if (BuildConfig.DEBUG) trace("ACC", "→ PATH B filteredRemoved=" + filteredRemoved
                + " remaining=" + mCurrentSuggestions.size());

        if (mCurrentSuggestions.isEmpty()) {
            // The additive filter emptied the list. This happens when there was
            // nothing to filter to begin with (e.g. the very first keystroke after
            // the field was cleared, so mCurrentSuggestions is still empty rather
            // than a previously-shown popup being filtered down). A plain dismiss
            // here would silently drop a legitimate character. Fall back to a full
            // history rescan: it shows suggestions if any match, otherwise it still
            // dismisses correctly.
            if (BuildConfig.DEBUG) trace("ACC", "filter emptied list → full rescan");
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

        if (BuildConfig.DEBUG) trace("ACC", "after top-up suggestions=" + mCurrentSuggestions.size());

        // If neither window is showing yet, build them fresh
        if (!isShowing()) {
            if (BuildConfig.DEBUG) trace("ACC", "→ showAutoCompletePopup (popup null or not showing)");
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
        // Спуск по префиксу БЕЗ материализации нижнерегистровой копии всего поля
        // (text.toLowerCase() аллоцировал бы строку длиной во весь ввод на каждый
        // символ при top-up). Trie хранит ключи в нижнем регистре, поэтому
        // сравниваем каждый символ через Character.toLowerCase на лету.
        for (int i = 0; i < text.length(); i++) {
            node = node.children.get(Character.toLowerCase(text.charAt(i)));
            if (node == null) return EMPTY_LIST; // no matches for this prefix
        }
        // node.words уже в порядке newest-first и используется только для чтения
        // (вызывающие коды лишь итерируют его и добавляют элементы в
        // mCurrentSuggestions, не мутируя сам node.words) — возвращаем напрямую
        // без копии, чтобы убрать аллокацию ArrayList на каждый символ.
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

        if (BuildConfig.DEBUG) trace("ACC", "fullRescan text=\"" + text + "\" max=" + maxCount
                + " history=" + mCurrentSuggestions.size());

        if (mCurrentSuggestions.isEmpty()) {
            if (mInputField != null && hasComposingSpan(mInputField)) {
                return; // во время compose не гасим — дождёмся commit, иначе моргание
            }
            dismissAutoCompleteSuggestions();
        } else {
            showAutoCompletePopup(mInputField);
        }
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
                    || (s.regionMatches(true, 0, text, 0, pLen)
                        || (text.endsWith("/")
                            && s.regionMatches(true, 0, text, 0, pLen - 1)));
            if (s.length() <= pLen || !matches || s.equals(text)) {
                sugg.remove(i);
                if (i < isShell.size()) isShell.remove(i);
            }
        }
    }

    /**
     * (Optimize auto-complete: three-path dispatch, additive filtering, history version guard)
     * Path B (optimized): update bold spans on the existing popup content when the
     * suggestion set is unchanged and only the typed prefix grew longer.
     * Mutates the Spannable buffer in-place via {@code tv.getText()} so no
     * requestLayout / remeasure is triggered — only {@code invalidate()} for redraw.
     * When the word-start anchor changes (e.g. user crosses a space/slash boundary)
     * falls back to {@code setText()} to rebuild the truncated display.
     *
     * <p>With two separate windows this refreshes BOTH the shell window and the
     * history window in their own view walks.
     */
    /** Apply (or rebuild) the bold-span for a single suggestion row. */
    private void applyBoldSpan(@NonNull TextView tv, @NonNull String suggestion,
            @NonNull String newText, boolean isShell) {
        String matchStr = isShell ? lastWordOf(newText) : newText;
        final int inputLen = matchStr.length();
        final int wordStart = Math.min(AutoCompleteTextRenderer.wordStartOffset(matchStr), suggestion.length());
        final int boldLen = inputLen - wordStart;
        final boolean hasLastWord = boldLen > 0;
        final String prefix = (wordStart > 0) ? "... " : "";
        final int prefixLen = prefix.length();

        CharSequence currentText = tv.getText();
        // Лениво вычисляем ожидаемую строку отображения — она нужна только в
        // ветке ребилда (редкий случай пересечения границы слова). В обычном
        // случае (префикс растёт внутри слова) currentText совпадает с suggestion,
        // и мы дешево проверяем это без конкатенации substring+prefix.
        final int ws = Math.min(wordStart, suggestion.length());
        final int curLen = currentText.length();
        final int sugLen = suggestion.length();
        boolean displayMatches;
        if (prefixLen == 0) {
            displayMatches = (curLen == sugLen) && suggestion.contentEquals(currentText);
        } else {
            displayMatches = (curLen == prefixLen + (sugLen - ws))
                    && prefix.contentEquals(currentText.subSequence(0, prefixLen))
                    && TextUtils.regionMatches(suggestion, ws, currentText, prefixLen, sugLen - ws);
        }
        if (!displayMatches) {
            // Word-boundary anchor shifted → rebuild text with setText (rare:
            // crossing a space or / boundary). Re-measure and re-truncate too
            // so the trailing '…' stays correct for long suggestions.
            String expectedDisplay = (prefixLen > 0)
                    ? prefix + suggestion.substring(ws)
                    : suggestion;
            int availWidth = tv.getWidth() - tv.getPaddingLeft() - tv.getPaddingRight();
            SpannableString ss = AutoCompleteTextRenderer.buildSuggestionSpannable(
                    suggestion, newText, Math.max(0, availWidth), tv.getPaint(), isShell);
            tv.setText(ss, TextView.BufferType.SPANNABLE);
        } else {
            // Display buffer unchanged → only mutate bold spans in-place (fast path).
            // Единственный bold-span известен (BOLD_SPAN), поэтому снимаем его
            // напрямую без getSpans()-аллокации массива на каждую строку.
            Spannable sp = (Spannable) currentText;
            sp.removeSpan(BOLD_SPAN);
            if (hasLastWord && prefixLen + boldLen <= sp.length()
                    && suggestion.regionMatches(true, 0, matchStr, 0, inputLen)) {
                sp.setSpan(BOLD_SPAN,
                        prefixLen, prefixLen + boldLen,
                        SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            tv.invalidate();
        }
    }

    /**
     * Path B: update the existing popups in-place after an additive text change.
     *
     * <p>The controller first top-ups the suggestion list from history (data
     * concern), then hands off to {@link AutoCompletePopupManager} which rebuilds
     * the per-window content, refreshes bold spans and resizes/positions the two
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
            dismissAutoCompleteSuggestions();
            mMessageHistoryDismissListener.run();
        });
        tv.setOnTouchListener(mSwipeHandler::onTouch);
        return tv;
    }

    /**
     * Insert a chosen suggestion into the input field. For history items the whole
     * line is inserted verbatim with no trailing decoration.
     */
    private void insertCandidate(@NonNull String candidate) {
        if (mInputField == null) return;
        Editable editable = mInputField.getText();
        if (editable == null) return;
        String text = editable.toString();
        String lastWord = lastWordOf(text);
        int tailStart = text.length() - lastWord.length();
        if (tailStart < 0) tailStart = 0;

        // Respect a caret that is NOT at the end of the line: replace from the
        // start of the last word up to the caret, and keep whatever follows the
        // caret intact. When the caret is at/past the end we drop the whole tail.
        int caret = mInputField.getSelectionStart();
        if (caret < 0) caret = text.length();
        int replaceStart = tailStart;
        int replaceEnd = text.length();
        if (caret >= tailStart && caret < text.length()) {
            replaceEnd = caret;
        }

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
        Logger.logInfo(LOG_TAG, "[autocomplete] repositionAutoCompletePopup");
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

package com.termux.app.terminal.io;

import android.annotation.SuppressLint;
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
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import com.termux.R;
import com.termux.app.terminal.TermuxColorSchemeManager;
import com.termux.shared.logger.Logger;
import com.termux.app.terminal.io.ShellCompletionProvider;

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
public final class AutoCompleteController {

    private static final String LOG_TAG = "AutoCompleteController";

    private final Context mContext;
    private final EditText mInputField;
    private final MessageHistoryController mMessageHistoryCtrl;
    private final TermuxColorSchemeManager mColorSchemeManager;
    private final SharedPreferences mPrefs;

    /** Called when a suggestion is chosen, so the host can dismiss its message-history popup. */
    @NonNull private Runnable mMessageHistoryDismissListener = () -> {};
    /** Provides the current working directory used as the per-directory history key. */
    @NonNull private CwdProvider mCwdProvider = () -> ".";

    /** True while the host is in an invalid state and auto-complete must be suppressed. */
    private boolean mIsInvalidState;

    // ── Auto-complete suggestions popup ────────────────
    /** Popup window showing auto-complete suggestions from message history. */
    @Nullable private PopupWindow mSuggestionsPopup;
    /** Current list of suggestion strings being displayed (shell completions first, then history). */
    private final java.util.ArrayList<String> mCurrentSuggestions = new java.util.ArrayList<>();
    /**
     * Parallel to {@link #mCurrentSuggestions}: {@code true} at index i means the
     * i-th suggestion came from the shell (bash) completion provider rather than
     * message history. Used to place a divider between the two groups and to keep
     * the shell group at the top of the popup.
     */
    private final java.util.ArrayList<Boolean> mCurrentIsShell = new java.util.ArrayList<>();
    /**
     * Parallel to {@link #mCurrentSuggestions}: the coarse {@link CandidateType}
     * of a shell candidate (for type-aware insertion/rendering). Entries for
     * history items are {@code null}.
     */
    private final java.util.ArrayList<ShellCompletionProvider.CandidateType> mCurrentShellType =
            new java.util.ArrayList<>();
    /**
     * Parallel to {@link #mCurrentSuggestions}: effective completion options for
     * a shell candidate (e.g. do-not-append-space). Entries for history items are
     * null.
     */
    private final java.util.ArrayList<ShellCompletionProvider.ShellCandidate> mCurrentShellMeta =
            new java.util.ArrayList<>();
    /** Number of leading shell-completion entries in {@link #mCurrentSuggestions} (the divider sits after them). */
    private int mShellSuggestionCount = 0;
    /**
     * Maximum number of suggestions to RENDER in the popup (the user setting
     * "suggestions_max_count"). The stored {@link #mCurrentSuggestions} list may
     * hold more (especially all cached shell candidates), but only this many are
     * displayed; the rest remain available to Path B local filtering.
     */
    private int mDisplayMax = 4;
    /** Suppress auto-complete popup during programmatic text changes (suggestion tap, etc.). */
    private boolean mSuppressAutoComplete;
    /** Global layout listener on the input field, used to reposition the auto-complete popup. */
    @Nullable private android.view.ViewTreeObserver.OnGlobalLayoutListener mSuggestionsLayoutListener;

    // ── Incremental auto-complete optimization fields ──
    /** Previous text tracked before a TextWatcher change, for additive-change detection. */
    private String mAutoCompletePrevText = "";
    /** Start index of the pending TextWatcher change (from beforeTextChanged). */
    private int mAutoCompleteChangeStart;
    /** Count of characters being replaced (from onTextChanged). */
    private int mAutoCompleteChangeBefore;
    /** Count of new characters being inserted (from onTextChanged). */
    private int mAutoCompleteChangeCount;
    /** Last text prefix used to build mCurrentSuggestions. */
    private String mLastAppliedPrefix = "";
    /** LinearLayout content inside the popup, for in-place view updates. */
    @Nullable private LinearLayout mSuggestionsContent;
    /** Cached version at the time the current popup was built (compared against the controller's history version). */
    private int mLastBuiltHistoryVersion = -1;
    /** Last explicit width passed to mSuggestionsPopup.update() (avoid -1 on API 21-22). */
    private int mLastPopupWidth = 0;
    /** Last explicit height passed to mSuggestionsPopup.update() (avoid -1 on API 21-22). */
    private int mLastPopupHeight = 0;
    /** Last X position passed to mSuggestionsPopup.update() (suppress redundant no-op calls). */
    private int mLastPopupX = 0;
    /** Last Y position passed to mSuggestionsPopup.update() (suppress redundant no-op calls). */
    private int mLastPopupY = 0;

    // ── Swipe-to-select gesture on suggestion items ──
    /**
     * Horizontal swipe on a suggestion row progressively appends the next
     * word(s) of that suggestion into a live text selection in the input field.
     * Swipe right adds words, swipe left removes them; the selection is dropped
     * (committed) when the finger is lifted. State is per-gesture and reset in
     * {@link #resetSwipeState()}.
     */
    private int mSwipeTouchSlop = -1;
    /** Raw X at ACTION_DOWN, used to compute horizontal displacement. */
    private float mSwipeStartX;
    /** Raw Y at ACTION_DOWN, used to require the gesture stays horizontal-dominant. */
    private float mSwipeStartY;
    /** True once horizontal movement exceeded the touch slop (swipe engaged). */
    private boolean mSwipeEngaged;
    /** Words remaining to be inserted from the swiped suggestion (in order). */
    @Nullable private String[] mSwipeWords;
    /** Text kept before any swipe-added words (the user's already-typed prefix / base). */
    @Nullable private String mSwipeBaseText;
    /** Whether a leading space is needed before the first appended word. */
    private boolean mSwipeNeedsLeadingSpace;
    /** Selection anchor (start) — stays fixed while the selection end grows/shrinks. */
    private int mSwipeAnchorStart;
    /** Number of words currently appended to the selection. */
    private int mSwipeWordsAdded;
    /** Horizontal pixels of finger travel that add/remove one word. */
    private int mSwipeUnitWidthPx;
    /** Suggestion string the current gesture started on (matched by tag, not index). */
    @Nullable private String mSwipeSuggestion;
    /** Parent whose touch-interception we disabled while engaged, so we can restore it. */
    @Nullable private android.view.ViewParent mSwipeDisallowParent;
    /** Suggestion row held in the pressed state during the swipe, cleared on end. */
    @Nullable private View mSwipePressedView;

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

    /** Key (in the controller's SharedPreferences) for the shell-completion toggle. */
    private static final String KEY_SHELL_COMPLETION_ENABLED = "shell_completion_enabled";

    /** Optional source of command completions from the user's shell (bash). Null when disabled. */
    @Nullable private ShellCompletionProvider mShellCompletionProvider;

    /**
     * Single-thread executor that runs the (potentially slow) bash completion
     * fetch off the UI thread. All {@link ShellCompletionProvider#complete} calls
     * happen here so the keystroke that triggers a fetch never blocks input.
     */
    private final ExecutorService mShellExec = Executors.newSingleThreadExecutor();

    /**
     * Epoch/generation token for in-flight shell fetches. Incremented on every new
     * fetch request; a result is applied only if its captured generation still
     * matches, so a late result from a previous word is discarded instead of
     * clobbering the current suggestions.
     */
    private final AtomicInteger mShellGen = new AtomicInteger();

    /**
     * Debounce for the (potentially expensive) shell fetch. History candidates are
     * shown instantly; only the background bash fetch is delayed slightly so rapid
     * keystrokes don't each spawn a subprocess. The generation guard still discards
     * any stale result that arrives after a newer fetch.
     */
    private final android.os.Handler mShellDebounceHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private static final long SHELL_FETCH_DEBOUNCE_MS = 70;

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
        syncShellCompletionEnabled();
        attachInputListeners();
    }

    // ── Wiring callbacks (optional) ───────────────────

    public void setMessageHistoryDismissListener(@NonNull Runnable listener) {
        mMessageHistoryDismissListener = listener;
    }

    public void setCwdProvider(@NonNull CwdProvider provider) {
        mCwdProvider = provider;
        // The working directory is part of the shell-completion cache key, but the
        // provider only learns the new cwd on its next query. Drop the cache now so
        // no stale (previous-directory) candidates linger until the next fetch.
        if (mShellCompletionProvider != null) mShellCompletionProvider.clearCache();
    }

    public void setInvalidState(boolean invalid) {
        mIsInvalidState = invalid;
    }

    /**
     * Enable or disable sourcing auto-complete candidates from the system shell
     * (bash) programmable completion. When enabled, a {@link ShellCompletionProvider}
     * is created (lazily, only if bash is available) and its candidates are merged
     * with the message-history suggestions. When disabled, shell completion is
     * never consulted. This is OPTIONAL and off by default.
     */
    public void setShellCompletionEnabled(boolean enabled) {
        if (enabled) {
            if (mShellCompletionProvider == null) {
                mShellCompletionProvider = new ShellCompletionProvider();
            }
        } else {
            // Drop the provider (and its per-word cache) when disabled.
            if (mShellCompletionProvider != null) mShellCompletionProvider.clearCache();
            mShellCompletionProvider = null;
            // Cancel any in-flight async fetch so it can't re-enable the group.
            mShellGen.incrementAndGet();
        }
    }

    /** Re-read the shell-completion toggle from the backing SharedPreferences. */
    public void syncShellCompletionEnabled() {
        setShellCompletionEnabled(mPrefs.getBoolean(KEY_SHELL_COMPLETION_ENABLED, false));
    }

    public void setSuppressAutoComplete(boolean suppress) {
        mSuppressAutoComplete = suppress;
    }

    /** Force a history-version mismatch so the next update takes Path A (full rescan). */
    public void invalidateHistoryVersion() {
        mLastBuiltHistoryVersion = -1;
    }

    // ── Public entry points ───────────────────────────

    /** Called from the host's focus change handler when the input field loses focus. */
    public void onInputFocusLost() {
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
        dismissAutoCompleteSuggestions();
    }

    /** Whether the suggestions popup is currently showing. */
    public boolean isShowing() {
        return mSuggestionsPopup != null && mSuggestionsPopup.isShowing();
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
                mAutoCompletePrevText = s.toString();
                mAutoCompleteChangeStart = start;
            }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                mAutoCompleteChangeBefore = before;
                mAutoCompleteChangeCount = count;
            }
            @Override
            public void afterTextChanged(android.text.Editable s) {
                updateAutoCompleteSuggestions();
            }
        });

        // Reposition the auto-complete popup when the caret moves without text changes
        // (e.g. tapping a different position in the text). We use ACTION_UP on the EditText
        // because setOnSelectionChangedListener is API 29+ and the project targets API 28.
        mInputField.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_UP) {
                v.post(() -> repositionAutoCompletePopup());
            }
            return false; // don't consume the event, let the EditText handle it
        });
    }

    @Nullable
    private Window getWindow() {
        if (mContext instanceof Activity) return ((Activity) mContext).getWindow();
        return null;
    }

    /**
     * Compute the popup width from the input field width: inset by the horizontal
     * margin, then shrink by {@code autocomplete_popup_width_fraction} so the popup
     * is shorter horizontally than the full field. Never below the minimum width.
     */
    private int computePopupWidth(int fieldWidth) {
        int avail = fieldWidth - mPopupWidthMarginPx;
        return Math.max(mPopupMinWidthPx, (int) (avail * mPopupWidthFraction));
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
        if (mIsInvalidState) return;
        if (mSuppressAutoComplete) return;

        final EditText inputField = mInputField;
        if (inputField == null) return;

        final String text = inputField.getText().toString();
        Logger.logInfo(LOG_TAG, "[autocomplete] text=\"" + text + "\"");

        if (TextUtils.isEmpty(text)) {
            dismissAutoCompleteSuggestions();
            return;
        }

        int maxCount = mPrefs.getInt("suggestions_max_count", 4);
        if (maxCount < 0) maxCount = 0;
        if (maxCount > 10) maxCount = 10;
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
        String prevText = mAutoCompletePrevText;
        if (text.equals(prevText) && mAutoCompleteChangeCount == mAutoCompleteChangeBefore) {
            if (mMessageHistoryCtrl.getHistoryVersion() == mLastBuiltHistoryVersion
                    && mSuggestionsPopup != null && mSuggestionsPopup.isShowing()
                    && !mCurrentSuggestions.isEmpty()) {
                Logger.logInfo(LOG_TAG, "[autocomplete] skip recompose (text==prevText, popup showing)");
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
        if (mAutoCompleteChangeCount > 0
                && text.length() > prevText.length()
                && text.startsWith(prevText)
                && !mCurrentSuggestions.isEmpty()) {
            additive = true;
        }
        Logger.logInfo(LOG_TAG, "[autocomplete] prev=\"" + prevText + "\" before="
                + mAutoCompleteChangeBefore + " count=" + mAutoCompleteChangeCount
                + " additive=" + additive + " hVer=" + mMessageHistoryCtrl.getHistoryVersion()
                + "/" + mLastBuiltHistoryVersion);

        // ── Path A (full rebuild): not additive OR history changed externally ──
        if (!additive || mMessageHistoryCtrl.getHistoryVersion() != mLastBuiltHistoryVersion) {
            Logger.logInfo(LOG_TAG, "[autocomplete] → PATH A (fullRescan) reason="
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
        Logger.logInfo(LOG_TAG, "[autocomplete] → PATH B filteredRemoved=" + filteredRemoved
                + " remaining=" + mCurrentSuggestions.size());

        if (mCurrentSuggestions.isEmpty()) {
            // The additive filter emptied the list. This happens when there was
            // nothing to filter to begin with (e.g. the very first keystroke after
            // the field was cleared, so mCurrentSuggestions is still empty rather
            // than a previously-shown popup being filtered down). A plain dismiss
            // here would silently drop a legitimate character. Fall back to a full
            // history rescan: it shows suggestions if any match, otherwise it still
            // dismisses correctly.
            Logger.logInfo(LOG_TAG, "[autocomplete] filter emptied list → full rescan");
            fullRescanSuggestions(text, maxCount);
            return;
        }

        // Top-up from history if filtered list is smaller than maxCount
        if (mCurrentSuggestions.size() < maxCount) {
            for (String msg : mMessageHistoryCtrl.getHistoryList()) {
                if (mCurrentSuggestions.size() >= maxCount) break;
                if (!mCurrentSuggestions.contains(msg)
                        && msg.length() > text.length()
                        && msg.regionMatches(true, 0, text, 0, text.length())
                        && !msg.equals(text)) {
                    mCurrentSuggestions.add(msg);
                }
            }
        }

        Logger.logInfo(LOG_TAG, "[autocomplete] after top-up suggestions=" + mCurrentSuggestions.size());

        // If popup isn't showing yet, build it fresh
        if (mSuggestionsPopup == null || !mSuggestionsPopup.isShowing()) {
            Logger.logInfo(LOG_TAG, "[autocomplete] → showAutoCompletePopup (popup null or not showing)");
            showAutoCompletePopup(inputField);
            return;
        }

        // In-place update of the existing popup content
        updatePopupContent(text, inputField);
    }

    /**
     * Path A: full re-scan of the message history and (asynchronously) the shell
     * completion candidates. History is merged and the popup is shown immediately
     * so typing never blocks; the shell group is fetched on a background thread
     * and injected on top when it arrives (see {@link #mergeShellCandidates}).
     */
    private void fullRescanSuggestions(@NonNull String text, int maxCount) {
        // The user's max setting governs how many items are RENDERED; the stored
        // list may be larger (all cached shell candidates) so Path B can keep
        // filtering locally without re-querying the shell.
        mDisplayMax = maxCount;

        mCurrentSuggestions.clear();
        mCurrentIsShell.clear();
        mCurrentShellType.clear();
        mCurrentShellMeta.clear();
        mShellSuggestionCount = 0;

        // History suggestions first (instant, no shell involved).
        for (String msg : mMessageHistoryCtrl.getHistoryList()) {
            if (mCurrentSuggestions.size() >= 512) break;
            if (!mCurrentSuggestions.contains(msg)
                    && msg.length() > text.length()
                    && msg.regionMatches(true, 0, text, 0, text.length())
                    && !msg.equals(text)) {
                mCurrentSuggestions.add(msg);
                mCurrentIsShell.add(Boolean.FALSE);
            }
        }

        Logger.logInfo(LOG_TAG, "[autocomplete] fullRescan text=\"" + text + "\" max=" + maxCount
                + " history=" + mCurrentSuggestions.size());

        if (mCurrentSuggestions.isEmpty()) {
            // No history yet — but shell may still produce candidates. Don't dismiss;
            // the async shell merge will either show the popup or dismiss if empty.
            fetchShellCandidatesAsync(text);
        } else {
            showAutoCompletePopup(mInputField);
            if (mShellCompletionProvider != null) {
                // Shell candidates arrive async; the popup already shows history.
                fetchShellCandidatesAsync(text);
            }
        }
    }

    /**
     * Fetch shell-completion candidates off the UI thread. When the result arrives
     * and is still current (same generation and unchanged input), merge it into the
     * suggestion list on top, preserving history below a divider.
     */
    private void fetchShellCandidatesAsync(@NonNull String text) {
        if (mShellCompletionProvider == null) return;
        final int gen = mShellGen.incrementAndGet();
        final String line = text;
        final int cursor = (mInputField != null) ? mInputField.getSelectionStart() : line.length();
        final String cwd = mCwdProvider.getCwd();
        // Debounce the (expensive) background fetch; the generation guard discards
        // the result if a newer fetch supersedes this one before it runs.
        mShellDebounceHandler.removeCallbacksAndMessages(null);
        mShellDebounceHandler.postDelayed(() -> {
            if (gen != mShellGen.get()) return; // superseded during the debounce window
            mShellExec.execute(() -> {
                ShellCompletionProvider.CompletionResult result =
                        mShellCompletionProvider.complete(line, cwd, cursor);
                if (mInputField != null) {
                    mInputField.post(() -> {
                        if (gen != mShellGen.get()) return; // stale word → discard
                        // Guard against a now-detached/stale input field (e.g. session
                        // switch between fetch and delivery).
                        if (mInputField == null || mSuggestionsContent == null) return;
                        // If the user kept typing the same word, the fetched list (built
                        // for the prefix at fetch time) still covers the current prefix,
                        // so merge against the LIVE text. Only a different word slot
                        // (generation already bumped by a newer fetch) discards this.
                        String liveText = mInputField.getText().toString();
                        mergeShellCandidates(result, liveText);
                    });
                }
            });
        }, SHELL_FETCH_DEBOUNCE_MS);
    }

    /**
     * Insert fetched shell candidates at the front of {@link #mCurrentSuggestions},
     * replacing any previous shell group, and refresh the popup. Matching is against
     * the last word (the token actually being completed). Runs on the UI thread.
     */
    private void mergeShellCandidates(@NonNull ShellCompletionProvider.CompletionResult result,
                                      @NonNull String text) {
        // Remove any pre-existing shell entries (from a previous, now-replaced group).
        for (int i = mCurrentSuggestions.size() - 1; i >= 0; i--) {
            if (i < mCurrentIsShell.size() && mCurrentIsShell.get(i)) {
                mCurrentSuggestions.remove(i);
                mCurrentIsShell.remove(i);
                if (i < mCurrentShellType.size()) mCurrentShellType.remove(i);
                if (i < mCurrentShellMeta.size()) mCurrentShellMeta.remove(i);
            }
        }
        mShellSuggestionCount = 0;

        String lastWord = lastWordOf(text);
        // Insert at front so shell stays above the history group.
        int insertAt = 0;
        for (ShellCompletionProvider.ShellCandidate cand : result.candidates) {
            String value = cand.value;
            if (mCurrentSuggestions.size() >= 512) break;
            if (!mCurrentSuggestions.contains(value)
                    && value.length() > lastWord.length()
                    && value.regionMatches(true, 0, lastWord, 0, lastWord.length())
                    && !value.equals(lastWord)) {
                mCurrentSuggestions.add(insertAt, value);
                mCurrentIsShell.add(insertAt, Boolean.TRUE);
                mCurrentShellType.add(insertAt, cand.type);
                mCurrentShellMeta.add(insertAt, cand);
                insertAt++;
            }
        }
        mShellSuggestionCount = insertAt;

        Logger.logInfo(LOG_TAG, "[autocomplete] mergeShell shell=" + mShellSuggestionCount
                + " total=" + mCurrentSuggestions.size());
        if (mCurrentSuggestions.isEmpty()) {
            dismissAutoCompleteSuggestions();
        } else if (mSuggestionsPopup == null || !mSuggestionsPopup.isShowing()) {
            showAutoCompletePopup(mInputField);
        } else {
            updatePopupContent(text, mInputField);
        }
    }

    /** Path separator set used by {@link #wordStartOffset} to find word boundaries. */
    private static final String WORD_SEPARATORS = " /";  // space + slash

    /**
     * Returns the starting position of the last word (token) in {@code s},
     * where words are delimited by any character in {@link #WORD_SEPARATORS}.
     * Returns 0 if no separator is found (the whole string is the only word)
     * or if {@code s} is empty.
     */
    /**
     * Returns the last whitespace-delimited token of {@code s} (the word currently
     * being typed/completed). Delegates to {@link ShellCompletionProvider#lastWordOf}
     * so the matching token is exactly what bash was asked to complete. History
     * candidates continue to be matched against the whole line.
     */
    @NonNull
    private static String lastWordOf(@NonNull String s) {
        return ShellCompletionProvider.lastWordOf(s);
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
    private void filterSuggestionsByPrefix(@NonNull String text,
            @NonNull List<String> sugg, @NonNull List<Boolean> isShell) {
        String lastWord = lastWordOf(text);
        int lwLen = lastWord.length();
        int tLen = text.length();
        for (int i = sugg.size() - 1; i >= 0; i--) {
            String s = sugg.get(i);
            boolean isShellItem = (i < isShell.size()) && isShell.get(i);
            String prefix = isShellItem ? lastWord : text;
            int pLen = isShellItem ? lwLen : tLen;
            boolean matches;
            if (pLen == 0) {
                // Empty prefix: shell candidates require at least one char (fetch is
                // deferred until a char is typed); history matches the whole empty line
                // only when it is non-empty itself — guarded by s.length() > pLen below.
                matches = !isShellItem;
            } else {
                matches = s.regionMatches(true, 0, prefix, 0, pLen)
                        || (isShellItem && prefix.endsWith("/")
                            && s.regionMatches(true, 0, prefix, 0, pLen - 1));
            }
            if (s.length() <= pLen || !matches || s.equals(prefix)) {
                sugg.remove(i);
                if (i < isShell.size()) isShell.remove(i);
                if (i < mCurrentShellType.size()) mCurrentShellType.remove(i);
                if (i < mCurrentShellMeta.size()) mCurrentShellMeta.remove(i);
            }
        }
    }

    private static int wordStartOffset(@NonNull String s) {
        int i = s.length();
        // Skip trailing separators so a space at the end of the typed text
        // (e.g. "hello ") is treated as "still finishing word 0", not as a
        // boundary into an empty next word.
        while (i > 0) {
            char c = s.charAt(i - 1);
            if (c != ' ' && c != '/') break;
            i--;
        }
        while (i > 0) {
            char c = s.charAt(i - 1);
            if (c == ' ' || c == '/') return i;
            i--;
        }
        return 0;
    }

    /**
     * Build the display {@link SpannableString} for an auto-complete
     * suggestion. Applies the word-based leading truncation (the {@code "... "}
     * prefix added when the match starts mid-word) and, when the result would
     * exceed {@code maxLines} lines, manually truncates it and appends a trailing
     * {@code '…'}.
     *
     * <p>Why manual truncation instead of {@code TextView.setEllipsize(END)}:
     * on Android (API 21-28 in particular) {@code ellipsize=end} is only reliably
     * honored for <b>single-line</b> text. With {@code setMaxLines(n)} where
     * {@code n > 1} the framework routes to {@code StaticLayout} but the trailing
     * ellipsis on the last line is unreliable and frequently never appears. We
     * therefore measure and cut the text ourselves so the {@code '…'} is
     * guaranteed for long suggestions/messages regardless of OS version. The
     * matched input prefix is rendered in BOLD on top of the (possibly truncated)
     * display text.
     *
     * @param availWidth available text width in px (popup width minus padding);
     *                   pass {@code 0} to skip truncation (e.g. not yet laid out).
     */
    @NonNull
    private SpannableString buildSuggestionSpannable(@NonNull String suggestion,
            @NonNull String input, int availWidth, @NonNull TextPaint paint, boolean isShell) {
        // Shell candidates are matched against the last word of the line (the token
        // bash completed); history against the whole line. The bold highlight must
        // cover the exact matched token.
        String matchStr = isShell ? lastWordOf(input) : input;
        int wordStart = Math.min(wordStartOffset(matchStr), suggestion.length());
        int boldLen = matchStr.length() - wordStart;
        boolean hasLastWord = boldLen > 0;
        String prefix = (wordStart > 0) ? "... " : "";
        int prefixLen = prefix.length();
        String displayText = (prefixLen > 0) ? prefix + suggestion.substring(wordStart) : suggestion;

        if (availWidth > 0) {
            displayText = truncateToLines(displayText, availWidth, paint, 2, false);
        }

        SpannableString ss = new SpannableString(displayText);
        int spanEnd = Math.min(prefixLen + boldLen, displayText.length());
        if (hasLastWord && spanEnd > prefixLen
                && suggestion.regionMatches(true, 0, matchStr, 0, matchStr.length())) {
            ss.setSpan(new StyleSpan(Typeface.BOLD),
                    prefixLen, spanEnd, SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return ss;
    }

    /**
     * Truncate {@code text} so it fits within {@code maxLines} lines of width
     * {@code availWidth}. Returns the original text unchanged if it already fits.
     * Otherwise keeps the start (when {@code middle} is false) and appends a
     * single {@code '…'}, or keeps the start and end and replaces the dropped
     * middle with a {@code '…'} (when {@code middle} is true, for paths).
     */
    @NonNull
    private static String truncateToLines(@NonNull String text, int availWidth,
            @NonNull TextPaint paint, int maxLines, boolean middle) {
        if (text.length() == 0 || fitsLines(text, availWidth, paint, maxLines)) {
            return text;
        }
        if (!middle) {
            int lo = 0, hi = text.length();
            while (lo < hi) {
                int mid = (lo + hi + 1) / 2;
                if (fitsLines(text.substring(0, mid) + "…", availWidth, paint, maxLines)) {
                    lo = mid;
                } else {
                    hi = mid - 1;
                }
            }
            return text.substring(0, lo) + "…";
        }
        int lo = 0, hi = text.length();
        while (lo < hi) {
            int mid = (lo + hi + 1) / 2;
            String cand = middleCandidate(text, mid);
            if (fitsLines(cand, availWidth, paint, maxLines)) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        return middleCandidate(text, lo);
    }

    @NonNull
    private static String middleCandidate(@NonNull String text, int keep) {
        int head = keep / 2;
        int tail = keep - head;
        if (head > text.length()) head = text.length();
        if (tail > text.length() - head) tail = text.length() - head;
        if (tail < 0) tail = 0;
        return text.substring(0, head) + "…" + text.substring(text.length() - tail);
    }

    /** True when {@code text} lays out to at most {@code maxLines} lines of {@code availWidth}. */
    @SuppressLint("DeprecatedApi")
    private static boolean fitsLines(@NonNull String text, int availWidth,
            @NonNull TextPaint paint, int maxLines) {
        StaticLayout layout;
        if (Build.VERSION.SDK_INT >= 23) {
            layout = StaticLayout.Builder.obtain(
                    text, 0, text.length(), paint, availWidth)
                    .setMaxLines(maxLines)
                    .setEllipsize(null)
                    .build();
        } else {
            layout = new StaticLayout(
                    text, paint, availWidth,
                    Layout.Alignment.ALIGN_NORMAL, 1.0f, 0.0f, false);
        }
        return layout.getLineCount() <= maxLines;
    }

    /**
     * (Optimize auto-complete: three-path dispatch, additive filtering, history version guard)
     * Path B (optimized): update bold spans on the existing popup content when the
     * suggestion set is unchanged and only the typed prefix grew longer.
     * Mutates the Spannable buffer in-place via {@code tv.getText()} so no
     * requestLayout / remeasure is triggered — only {@code invalidate()} for redraw.
     * When the word-start anchor changes (e.g. user crosses a space/slash boundary)
     * falls back to {@code setText()} to rebuild the truncated display.
     */
    private void updateBoldSpansOnly(@NonNull String newText) {
        if (mSuggestionsPopup == null || !mSuggestionsPopup.isShowing()) return;
        ScrollView scroll = (ScrollView) mSuggestionsPopup.getContentView();
        if (scroll == null) return;
        LinearLayout content = (LinearLayout) scroll.getChildAt(0);
        if (content == null) return;

        // Walk the rendered suggestion views in the SAME order that
        // rebuildSuggestionViews lays them out: the first `shellN` shell candidates,
        // then the leading history candidates. This keeps the isShell flag aligned
        // per row so shell candidates highlight their last word (not the whole line).
        final int n = displayCount();
        final int shellN = displayShellCount();
        Logger.logInfo(LOG_TAG, "[autocomplete] updateBoldSpansOnly views=" + content.getChildCount());
        int rendered = 0;
        int viewIdx = 0;
        // Shell rows
        for (int s = 0; s < shellN && rendered < n; s++, rendered++) {
            TextView tv = textViewAt(content, viewIdx++);
            if (tv == null) break;
            String suggestion = mCurrentSuggestions.get(s);
            applyBoldSpan(tv, suggestion, newText, true);
        }
        // Divider (not a TextView) — skip it in the view walk.
        if (shellN > 0 && rendered < n) viewIdx++;
        // History rows
        for (int h = mShellSuggestionCount;
                h < mCurrentSuggestions.size() && rendered < n; h++, rendered++) {
            TextView tv = textViewAt(content, viewIdx++);
            if (tv == null) break;
            String suggestion = mCurrentSuggestions.get(h);
            applyBoldSpan(tv, suggestion, newText, false);
        }
    }

    /** Returns the i-th TextView child of {@code content}, or null if it's not a TextView. */
    @Nullable
    private static TextView textViewAt(@NonNull LinearLayout content, int i) {
        if (i < 0 || i >= content.getChildCount()) return null;
        View child = content.getChildAt(i);
        return (child instanceof TextView) ? (TextView) child : null;
    }

    /** Apply (or rebuild) the bold-span for a single suggestion row. */
    private void applyBoldSpan(@NonNull TextView tv, @NonNull String suggestion,
            @NonNull String newText, boolean isShell) {
        String matchStr = isShell ? lastWordOf(newText) : newText;
        final int inputLen = matchStr.length();
        final int wordStart = Math.min(wordStartOffset(matchStr), suggestion.length());
        final int boldLen = inputLen - wordStart;
        final boolean hasLastWord = boldLen > 0;
        final String prefix = (wordStart > 0) ? "... " : "";
        final int prefixLen = prefix.length();

        // Compute the expected word-truncated display string
        int ws = Math.min(wordStart, suggestion.length());
        String expectedDisplay = (prefixLen > 0)
                ? prefix + suggestion.substring(ws)
                : suggestion;

        CharSequence currentText = tv.getText();
        if (!expectedDisplay.contentEquals(currentText)) {
            // Word-boundary anchor shifted → rebuild text with setText (rare:
            // crossing a space or / boundary). Re-measure and re-truncate too
            // so the trailing '…' stays correct for long suggestions.
            int availWidth = tv.getWidth() - tv.getPaddingLeft() - tv.getPaddingRight();
            SpannableString ss = buildSuggestionSpannable(
                    suggestion, newText, Math.max(0, availWidth), tv.getPaint(), isShell);
            tv.setText(ss, TextView.BufferType.SPANNABLE);
        } else {
            // Display buffer unchanged → only mutate bold spans in-place (fast path)
            Spannable sp = (Spannable) currentText;
            StyleSpan[] old = sp.getSpans(0, sp.length(), StyleSpan.class);
            for (StyleSpan s : old) sp.removeSpan(s);
            if (hasLastWord && prefixLen + boldLen <= sp.length()
                    && suggestion.regionMatches(true, 0, matchStr, 0, inputLen)) {
                sp.setSpan(new StyleSpan(Typeface.BOLD),
                        prefixLen, prefixLen + boldLen,
                        SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            tv.invalidate();
        }
    }

    /**
     * Path B: update the existing popup in-place after an additive text change.
     *
     * Removes non-matching views from {@link #mSuggestionsContent},
     * recalculates bold-spans for survivors, top-ups with new views if history
     * had more matches, then measures and resizes the popup.
     */
    private void updatePopupContent(@NonNull String newText, @NonNull EditText inputField) {
        if (mSuggestionsPopup == null || !mSuggestionsPopup.isShowing()) {
            showAutoCompletePopup(inputField);
            return;
        }
        ScrollView scroll = (ScrollView) mSuggestionsPopup.getContentView();
        if (scroll == null) { showAutoCompletePopup(inputField); return; }
        LinearLayout content = (LinearLayout) scroll.getChildAt(0);
        if (content == null) { showAutoCompletePopup(inputField); return; }

        // NOTE: mCurrentSuggestions is already filtered (by Path B's
        // filterSuggestionsByPrefix, or by mergeShellCandidates which inserts
        // prefix-matched shell entries with correct isShell flags). Re-filtering
        // here would be a redundant O(n) scan, so we only recompute the leading
        // shell count.
        mShellSuggestionCount = 0;
        for (boolean isShell : mCurrentIsShell) {
            if (isShell) mShellSuggestionCount++;
            else break;
        }

        // Top-up from history if the rendered set still has room (history items
        // are always appended after the shell group, so the divider is preserved).
        int maxCount = mPrefs.getInt("suggestions_max_count", 4);
        if (maxCount < 0) maxCount = 0;
        if (maxCount > 10) maxCount = 10;
        mDisplayMax = maxCount;
        if (displayCount() < maxCount) {
            final int storeCap = Math.max(maxCount, 512);
            for (String msg : mMessageHistoryCtrl.getHistoryList()) {
                if (displayCount() >= maxCount) break;
                if (mCurrentSuggestions.size() >= storeCap) break;
                if (!mCurrentSuggestions.contains(msg)
                        && msg.length() > newText.length()
                        && msg.regionMatches(true, 0, newText, 0, newText.length())
                        && !msg.equals(newText)) {
                    mCurrentSuggestions.add(msg);
                    mCurrentIsShell.add(Boolean.FALSE);
                }
            }
        }

        // Detect whether the visible set changed vs the currently shown views, using
        // the SAME shell-then-history mapping that rebuildSuggestionViews uses.
        final int expectedChildren = expectedChildCount();
        boolean contentChanged = (content.getChildCount() != expectedChildren);
        if (!contentChanged) {
            final int n = displayCount();
            final int shellN = displayShellCount();
            int viewIdx = 0;
            int rendered = 0;
            // Shell rows
            boolean mismatch = false;
            for (int s = 0; s < shellN && rendered < n; s++, rendered++) {
                TextView tv = textViewAt(content, viewIdx++);
                if (tv == null || !tv.getText().toString().equals(mCurrentSuggestions.get(s))) {
                    mismatch = true; break;
                }
            }
            if (shellN > 0 && rendered < n) viewIdx++; // skip the divider view
            // History rows
            for (int h = mShellSuggestionCount;
                    !mismatch && h < mCurrentSuggestions.size() && rendered < n;
                    h++, rendered++) {
                TextView tv = textViewAt(content, viewIdx++);
                if (tv == null || !tv.getText().toString().equals(mCurrentSuggestions.get(h))) {
                    mismatch = true; break;
                }
            }
            contentChanged = mismatch;
        }

        if (contentChanged) {
            // Rebuild the views (including the divider) to match the filtered lists.
            rebuildSuggestionViews(content, newText);
        }

        Logger.logInfo(LOG_TAG, "[autocomplete] updatePopupContent contentChanged=" + contentChanged
                + " views=" + content.getChildCount() + " suggestions=" + mCurrentSuggestions.size());

        // Fast path: no structural change — only update bold-spans in-place, skip measure/update
        if (!contentChanged) {
            Logger.logInfo(LOG_TAG, "[autocomplete] → FAST PATH (bold-spans only)");
            updateBoldSpansOnly(newText);
            mLastAppliedPrefix = newText;
            applyPopupGeometry(inputField);
            return;
        }

        // Structural change — measure, resize. (Bold spans were already applied by
        // rebuildSuggestionViews via buildSuggestionSpannable, so no extra pass.)
        Logger.logInfo(LOG_TAG, "[autocomplete] → STRUCTURAL CHANGE (measure+update)");

        // Reset scroll to top synchronously before measure (no post() needed,
        // the ScrollView is already laid out and showing).
        scroll.setScrollY(0);
        content.measure(
                View.MeasureSpec.makeMeasureSpec(mLastPopupWidth, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int newHeight = content.getMeasuredHeight();

        // Resize and reposition
        mLastPopupHeight = newHeight;
        mLastAppliedPrefix = newText;
        applyPopupGeometry(inputField);
    }

    /**
     * Build the thin horizontal divider shown between the shell-completion group
     * (top) and the message-history group (bottom). It carries no suggestion tag,
     * so the additive filter treats it as a non-suggestion child and leaves it
     * alone.
     */
    @NonNull
    private View buildDividerView() {
        View divider = new View(mContext);
        int thickness = Math.max(1, (int) (mContext.getResources().getDisplayMetrics().density));
        divider.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, thickness));
        divider.setBackgroundColor(mColorSchemeManager.getHistoryPopupSepColor());
        return divider;
    }

    /**
     * Number of suggestions to actually render (capped at the user's max setting),
     * even though {@link #mCurrentSuggestions} may store more candidates for
     * Path B local filtering.
     */
    private int displayCount() {
        return Math.min(mCurrentSuggestions.size(), mDisplayMax);
    }

    /** Whether the rendered popup should show the shell/history divider. */
    private boolean dividerShown() {
        int shellN = displayShellCount();
        return shellN > 0 && displayCount() > shellN;
    }

    /** Expected number of popup children (suggestions + optional divider). */
    private int expectedChildCount() {
        return displayCount() + (dividerShown() ? 1 : 0);
    }

    /**
     * Number of leading shell-completion entries to render. Capped at the display
     * limit, but when the shell group overflows the display AND history matches
     * exist, we reserve one slot for history so the user never loses their
     * message-history recall entirely. The divider sits after this many rendered
     * shell items, but only if at least one history item is also rendered below it.
     */
    private int displayShellCount() {
        int historyCount = mCurrentSuggestions.size() - mShellSuggestionCount;
        if (historyCount > 0 && mShellSuggestionCount > mDisplayMax) {
            return Math.max(0, mDisplayMax - 1);
        }
        return Math.min(mShellSuggestionCount, mDisplayMax);
    }

    /**
     * Rebuild the popup content from {@link #mCurrentSuggestions}, inserting a
     * divider after the leading shell-completion group (the first
     * {@link #displayShellCount()} rendered entries) when both groups are present.
     * Only the first {@link #displayCount()} suggestions are rendered; the rest
     * remain in the stored list for Path B filtering. Used by every code path
     * that (re)creates the suggestion views so ordering and the divider stay
     * consistent.
     */
    private void rebuildSuggestionViews(@NonNull LinearLayout content, @NonNull String input) {
        content.removeAllViews();
        final int n = displayCount();
        final int shellN = displayShellCount();
        // Render the first `shellN` shell candidates, then the leading history
        // candidates (those stored after the shell group), capping at `n` total.
        // This keeps shell items above the divider and history below it even when
        // the shell group is truncated to reserve a history slot.
        int rendered = 0;
        for (int i = 0; i < shellN && rendered < n; i++, rendered++) {
            String suggestion = mCurrentSuggestions.get(i);
            TextView tv = buildSuggestionTextView(suggestion, input, true);
            content.addView(tv, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
        }
        if (shellN > 0 && rendered < n) {
            content.addView(buildDividerView());
        }
        for (int i = mShellSuggestionCount; i < mCurrentSuggestions.size() && rendered < n; i++, rendered++) {
            String suggestion = mCurrentSuggestions.get(i);
            TextView tv = buildSuggestionTextView(suggestion, input, false);
            content.addView(tv, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
        }
    }

    /** Build a single suggestion TextView (reusable helper). */
    private TextView buildSuggestionTextView(@NonNull String suggestion, @NonNull String input, boolean isShell) {
        int padH = mPopupItemPadHPx;
        int padV = mPopupItemPadVPx;
        TextView tv = new TextView(mContext);

        tv.setTextColor(mColorSchemeManager.getHistoryTextColor());
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        tv.setPadding(padH, padV, padH, padV);

        // Available text width = popup width minus horizontal padding. Mirrors the
        // width the popup is sized to in showAutoCompletePopup (sumWidth).
        int fieldWidth = mInputField != null ? mInputField.getWidth() : 0;
        int popupWidth = computePopupWidth(fieldWidth);
        int availWidth = Math.max(0, popupWidth - 2 * padH);

        // Word-based leading truncation + manual trailing '…' (TextView's
        // setEllipsize(END) is unreliable for maxLines>1 on API 21-28).
        SpannableString ss = buildSuggestionSpannable(
                suggestion, input, availWidth, tv.getPaint(), isShell);
        tv.setText(ss, TextView.BufferType.SPANNABLE);
        tv.setMaxLines(2);
        tv.setEllipsize(TextUtils.TruncateAt.END); // backup; text already fits 2 lines
        tv.setTextColor(mColorSchemeManager.getHistoryTextColor());
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        tv.setPadding(padH, padV, padH, padV);
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
        // Capture the candidate's metadata so we can insert it readline-style (only
        // the current word is replaced; directories get a trailing '/', files/commands
        // a trailing space; options and -o nospace candidates get no space).
        final ShellCompletionProvider.ShellCandidate finalMeta = isShell
                ? findShellMeta(suggestion) : null;

        tv.setOnClickListener(v -> {
            mSuppressAutoComplete = true;
            try {
                if (mInputField != null) {
                    insertCandidate(finalSuggestion, finalMeta);
                }
            } finally {
                mSuppressAutoComplete = false;
            }
            dismissAutoCompleteSuggestions();
            mMessageHistoryDismissListener.run();
        });
        tv.setOnTouchListener(this::onSuggestionSwipeTouch);
        return tv;
    }

    /**
     * Find the {@link ShellCompletionProvider.ShellCandidate} metadata for a
     * displayed shell suggestion (matched by its value string). Returns null for
     * history items or when not found.
     */
    @Nullable
    private ShellCompletionProvider.ShellCandidate findShellMeta(@NonNull String suggestion) {
        for (int i = 0; i < mCurrentSuggestions.size(); i++) {
            if (i < mCurrentIsShell.size() && mCurrentIsShell.get(i)
                    && i < mCurrentShellMeta.size()
                    && suggestion.equals(mCurrentSuggestions.get(i))) {
                return mCurrentShellMeta.get(i);
            }
        }
        return null;
    }

    /**
     * Insert a chosen shell candidate into the input field the way readline would:
     * only the CURRENT last word is replaced (the rest of the line is preserved),
     * and a trailing decoration is appended based on the candidate type:
     *
     * <ul>
     *   <li>directory → append {@code '/'} (no space), so the next segment continues inside;</li>
     *   <li>file → append a space (unless the compspec said {@code -o nospace});</li>
     *   <li>command / alias / function → append a space (unless {@code -o nospace});</li>
     *   <li>option → append nothing (flags are usually followed by {@code =} or an argument);</li>
     *   <li>unknown → append a space (unless {@code -o nospace}).</li>
     * </ul>
     *
     * The candidate value already includes any {@code -P}/{@code -S} prefix/suffix
     * produced by bash, so it is inserted verbatim.
     */
    private void insertCandidate(@NonNull String candidate,
                                 @Nullable ShellCompletionProvider.ShellCandidate meta) {
        if (mInputField == null) return;
        Editable editable = mInputField.getText();
        if (editable == null) return;
        String text = editable.toString();
        String lastWord = lastWordOf(text);
        int replaceStart = text.length() - lastWord.length();
        if (replaceStart < 0) replaceStart = 0;

        String insert = candidate;
        boolean addSpace = false;
        if (meta != null) {
            switch (meta.type) {
                case DIRECTORY:
                    if (!insert.endsWith("/")) insert += "/";
                    addSpace = false;
                    break;
                case FILE:
                    addSpace = !meta.noSpace;
                    break;
                case OPTION:
                    addSpace = false;
                    break;
                case COMMAND:
                case ALIAS:
                case FUNCTION:
                case UNKNOWN:
                default:
                    addSpace = !meta.noSpace;
                    break;
            }
        } else {
            // History item: insert the whole line verbatim, no trailing space.
            addSpace = false;
        }
        if (addSpace) insert += " ";

        String newText = text.substring(0, replaceStart) + insert;
        mInputField.setText(newText);
        mInputField.setSelection(newText.length());
    }

    /**
     * Touch handler implementing the swipe-to-select gesture on a suggestion row.
     *
     * <p>While the finger is down and moving horizontally past the touch slop,
     * each {@link #mSwipeUnitWidthPx} of rightward travel appends the next word
     * of the suggestion into a live selection in {@link #mInputField}; leftward
     * travel removes previously-added words. The selection is highlighted while
     * the finger is down and collapsed (committed) on release. A pure tap never
     * engages the swipe and falls through to the item's {@code OnClickListener}
     * (tap-to-insert), because we return {@code false} until the slop is crossed.
     */
    private boolean onSuggestionSwipeTouch(@NonNull View v, @NonNull MotionEvent event) {
        if (mInputField == null) return false;

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                mSwipeStartX = event.getRawX();
                mSwipeStartY = event.getRawY();
                mSwipeEngaged = false;
                mSwipeWordsAdded = 0;

                if (mSwipeTouchSlop < 0) {
                    mSwipeTouchSlop = android.view.ViewConfiguration.get(mContext).getScaledTouchSlop();
                }
                // ~24dp of travel per word — a light, responsive swipe.
                float density = mContext.getResources().getDisplayMetrics().density;
                mSwipeUnitWidthPx = Math.max(1, (int) (24 * density));

                Object tag = v.getTag();
                mSwipeSuggestion = (tag instanceof String) ? (String) tag : null;
                if (mSwipeSuggestion == null) return false;

                // The words to insert are the suggestion's remainder past the
                // already-typed prefix, so we never re-append what's already there.
                String base = mInputField.getText() != null ? mInputField.getText().toString() : "";
                mSwipeBaseText = base;
                boolean isPrefixMatch = !base.isEmpty()
                        && mSwipeSuggestion.length() >= base.length()
                        && mSwipeSuggestion.regionMatches(true, 0, base, 0, base.length());
                String remainder = computeRemainder(mSwipeSuggestion, base);
                mSwipeWords = splitShellWords(remainder);
                // Add a leading space before the first word only when the first
                // word starts a NEW token: i.e. the suggestion is NOT a prefix
                // continuation of what's typed (so the word doesn't complete a
                // half-typed token) AND the base doesn't already end in a
                // separator (space or '/').
                mSwipeNeedsLeadingSpace = !isPrefixMatch
                        && !base.isEmpty()
                        && !isWordSeparator(base.charAt(base.length() - 1));
                mSwipeAnchorStart = base.length();
                // Not consumed yet: allow a tap to become a click.
                return false;
            }

            case MotionEvent.ACTION_MOVE: {
                if (mSwipeWords == null || mSwipeWords.length == 0) {
                    // Single-word (or empty-remainder) suggestion: nothing to add.
                    return mSwipeEngaged;
                }
                float dx = event.getRawX() - mSwipeStartX;
                float dy = event.getRawY() - mSwipeStartY;
                if (!mSwipeEngaged) {
                    if (Math.abs(dx) <= mSwipeTouchSlop) return false;
                    // Ignore vertical-dominant drags so the popup can still scroll
                    // (and diagonal scroll gestures aren't hijacked as word swipes).
                    if (Math.abs(dy) > Math.abs(dx)) return false;
                    // Engage swipe mode and stop the popup ScrollView from stealing
                    // the gesture. Keep the row in the pressed state so it shows the
                    // exact same highlight as a tap for the whole duration of the swipe.
                    mSwipeEngaged = true;
                    v.setPressed(true);
                    v.cancelLongPress();
                    mSwipePressedView = v;
                    mSwipeDisallowParent = v.getParent();
                    if (mSwipeDisallowParent != null) {
                        mSwipeDisallowParent.requestDisallowInterceptTouchEvent(true);
                    }
                    mSuppressAutoComplete = true;
                    // The selection highlight only renders while the field is focused.
                    if (!mInputField.isFocused()) mInputField.requestFocus();
                }
                int target = (int) (dx / mSwipeUnitWidthPx);
                if (target < 0) target = 0;
                if (target > mSwipeWords.length) target = mSwipeWords.length;
                if (target != mSwipeWordsAdded) {
                    applyWordSelection(target);
                    mSwipeWordsAdded = target;
                }
                return true;
            }

            case MotionEvent.ACTION_UP: {
                boolean wasEngaged = mSwipeEngaged;
                if (wasEngaged) {
                    // Finger lifted intentionally: collapse the selection to its
                    // end = commit the appended words.
                    int end = mInputField.getSelectionEnd();
                    if (end < 0) end = mInputField.length();
                    mInputField.setSelection(end, end);
                }
                resetSwipeState();
                if (wasEngaged) {
                    // The field text changed during the swipe (which was suppressed):
                    // refresh the auto-complete popup for the newly committed text.
                    updateAutoCompleteSuggestions();
                }
                // If engaged we consumed the gesture (suppress the click); otherwise
                // return false so a pure tap still triggers tap-to-insert.
                return wasEngaged;
            }

            case MotionEvent.ACTION_CANCEL: {
                boolean wasEngaged = mSwipeEngaged;
                if (wasEngaged) {
                    // Gesture cancelled (not a real lift): revert to the untouched
                    // base text so no words are left committed.
                    applyWordSelection(0);
                    int end = mInputField.length();
                    mInputField.setSelection(end, end);
                }
                resetSwipeState();
                if (wasEngaged) {
                    updateAutoCompleteSuggestions();
                }
                return wasEngaged;
            }

            default:
                return mSwipeEngaged;
        }
    }

    /**
     * Returns the portion of {@code suggestion} that still needs to be inserted
     * given the already-typed {@code base}. If the suggestion starts with the
     * base (case-insensitively, matching the popup's own prefix logic), only the
     * tail is returned; otherwise the whole suggestion is returned.
     */
    @NonNull
    private static String computeRemainder(@NonNull String suggestion, @NonNull String base) {
        if (!base.isEmpty()
                && suggestion.length() >= base.length()
                && suggestion.regionMatches(true, 0, base, 0, base.length())) {
            return suggestion.substring(base.length());
        }
        return suggestion;
    }

    /**
     * Split a remainder into "words" delimited by {@link #WORD_SEPARATORS}
     * (space and '/'), keeping each word's <b>trailing</b> separator so the
     * original spacing/path structure is restored exactly when re-appended.
     * A leading run of separators is folded into the first word's prefix.
     *
     * <p>Example: {@code "usr/bin/env"} → {@code ["usr/", "bin/", "env"]}
     * (no trailing separator fabricated for the last word);
     * {@code " commit -m"} → {@code [" commit ", "-m"]}.
     */
    @NonNull
    private static String[] splitShellWords(@NonNull String s) {
        if (s.isEmpty()) return new String[0];
        java.util.ArrayList<String> out = new java.util.ArrayList<>();
        int i = 0;
        int n = s.length();
        // Fold any leading separators into the prefix of the first token so that
        // the boundary between the base text and the first word is preserved.
        int leadStart = i;
        while (i < n && isWordSeparator(s.charAt(i))) i++;
        String lead = s.substring(leadStart, i);
        while (i < n) {
            int wordStart = i;
            while (i < n && !isWordSeparator(s.charAt(i))) i++;
            // Include the run of trailing separators that actually followed this
            // word in the original suggestion (e.g. '/' for paths, one or more
            // spaces for arguments). Nothing is added if the word ended the
            // string, so a separator that wasn't in the original is never fabricated.
            while (i < n && isWordSeparator(s.charAt(i))) i++;
            String token = s.substring(wordStart, i);
            if (!lead.isEmpty()) {
                token = lead + token;
                lead = "";
            }
            out.add(token);
        }
        // A remainder consisting only of separators (rare) still yields the lead.
        if (!lead.isEmpty()) out.add(lead);
        return out.toArray(new String[0]);
    }

    /** True if {@code c} is one of {@link #WORD_SEPARATORS} (space or '/'). */
    private static boolean isWordSeparator(char c) {
        return c == ' ' || c == '/';
    }

    /**
     * Rebuild the input field so that exactly {@code n} of {@link #mSwipeWords}
     * are appended after {@link #mSwipeBaseText}, then select the appended range.
     * Deterministic (recomputed from scratch each call) so rapid direction
     * reversals stay correct.
     */
    private void applyWordSelection(int n) {
        if (mInputField == null || mSwipeWords == null || mSwipeBaseText == null) return;
        StringBuilder sb = new StringBuilder(mSwipeBaseText);
        for (int i = 0; i < n; i++) {
            // A leading space is inserted before the first word only when it
            // begins a new token and the base doesn't already end in a separator.
            if (i == 0 && mSwipeNeedsLeadingSpace) sb.append(' ');
            // Each token already carries its own trailing separator (space or '/')
            // captured from the suggestion, so path/argument structure is restored
            // exactly and the text stays ready for the next token.
            sb.append(mSwipeWords[i]);
        }
        String newText = sb.toString();
        mInputField.setText(newText);
        int end = newText.length();
        int start = Math.min(mSwipeAnchorStart, end);
        mInputField.setSelection(start, end);
    }

    /**
     * Clear all per-gesture swipe state. Always lifts the auto-complete
     * suppression guard and the parent touch-interception block so neither can
     * leak past the end of a gesture regardless of which exit path ran.
     */
    private void resetSwipeState() {
        if (mSwipePressedView != null) {
            mSwipePressedView.setPressed(false);
            mSwipePressedView = null;
        }
        if (mSwipeDisallowParent != null) {
            mSwipeDisallowParent.requestDisallowInterceptTouchEvent(false);
            mSwipeDisallowParent = null;
        }
        if (mSwipeEngaged) {
            mSuppressAutoComplete = false;
        }
        mSwipeEngaged = false;
        mSwipeWords = null;
        mSwipeBaseText = null;
        mSwipeSuggestion = null;
        mSwipeWordsAdded = 0;
        mSwipeNeedsLeadingSpace = false;
    }

    /**
     * Calculate the X position for the popup at the input caret.
     * Reusable helper shared by showAutoCompletePopup, updatePopupContent and reposition.
     */
    private int calcPopupX(@NonNull EditText inputField, int popupWidth) {
        int cursorPos = inputField.getSelectionStart();
        Layout layout = inputField.getLayout();
        float cursorX = 0;
        if (layout != null && cursorPos >= 0) {
            cursorX = layout.getPrimaryHorizontal(cursorPos);
        }
        int[] loc = new int[2];
        inputField.getLocationInWindow(loc);
        // Subtract the horizontal scroll offset so the popup tracks the visible
        // caret when the EditText content is scrolled horizontally.
        int popupX = loc[0] + (int) cursorX - inputField.getScrollX() + mPopupXOffsetPx;
        int displayWidth = mContext.getResources().getDisplayMetrics().widthPixels;
        if (popupX + popupWidth > displayWidth - mPopupEdgeMarginPx) {
            popupX = displayWidth - popupWidth - mPopupEdgeMarginPx;
        }
        if (popupX < mPopupEdgeMarginPx) popupX = mPopupEdgeMarginPx;
        return popupX;
    }

    /**
     * Calculate the Y position for the popup (above the caret, fallback below).
     */
    private int calcPopupY(@NonNull EditText inputField, int popupHeight, int popupWidth) {
        int cursorPos = inputField.getSelectionStart();
        Layout layout = inputField.getLayout();
        float cursorY = 0;
        if (layout != null && cursorPos >= 0) {
            cursorY = layout.getLineTop(layout.getLineForOffset(cursorPos));
        }
        int[] loc = new int[2];
        inputField.getLocationInWindow(loc);
        // Subtract the vertical scroll offset so the popup tracks the visible
        // caret when the EditText content is scrolled vertically. Without this
        // the popup stays pinned to the layout's top line and drifts away from
        // the caret as the field is scrolled.
        int popupY = loc[1] + (int) cursorY - inputField.getScrollY() - popupHeight - mPopupYOffsetPx;
        if (popupY < mPopupMinYPx) {
            popupY = loc[1] + (int) cursorY - inputField.getScrollY() + mPopupEdgeMarginPx;
        }
        return popupY;
    }

    private void showAutoCompletePopup(@NonNull EditText inputField) {
        Logger.logInfo(LOG_TAG, "[autocomplete] showAutoCompletePopup suggestions="
                + mCurrentSuggestions.size() + " popupShown=" + (mSuggestionsPopup != null));

        // ── Reuse the existing window to avoid dismiss→show flicker ──
        if (mSuggestionsPopup != null && mSuggestionsPopup.isShowing()
                && mSuggestionsContent != null && !mCurrentSuggestions.isEmpty()
                && mSuggestionsContent.getParent() != null) {
            final String input = inputField.getText().toString();

            // Expected number of children: rendered suggestions plus (possibly) one
            // divider between the shell group and the history group.
            final int expectedChildren = expectedChildCount();

            // Content-changed guard: skip the removeAllViews + addViews + measure cycle
            // when the suggestion list is identical to what's already shown (e.g. an IME
            // re-compose of the same text that re-triggers Path A). Mirrors the
            // contentChanged check in updatePopupContent(), comparing the EXISTING
            // mSuggestionsContent children against mCurrentSuggestions.
            boolean contentChanged = mSuggestionsContent.getChildCount() != expectedChildren;
            if (!contentChanged) {
                int suggestionIdx = 0;
                for (int i = 0; i < mSuggestionsContent.getChildCount(); i++) {
                    View child = mSuggestionsContent.getChildAt(i);
                    if (!(child instanceof TextView)) continue; // divider
                    String existing = ((TextView) child).getText().toString();
                    if (suggestionIdx >= mCurrentSuggestions.size()
                            || !existing.equals(mCurrentSuggestions.get(suggestionIdx))) {
                        contentChanged = true;
                        break;
                    }
                    suggestionIdx++;
                }
            }

            if (!contentChanged) {
                // Fast path: identical suggestions — refresh bold spans for the current
                // prefix in-place (no relayout) and reposition. Height is unchanged, so
                // no re-measure needed. Keep version tracking in sync with the rebuild path.
                Logger.logInfo(LOG_TAG, "[autocomplete] popup reuse FAST PATH (bold-spans only)");
                updateBoldSpansOnly(input);
                mLastBuiltHistoryVersion = mMessageHistoryCtrl.getHistoryVersion();
                mLastAppliedPrefix = input;
                applyPopupGeometry(inputField);
                return;
            }

            // Reset scroll to top before rebuilding content in-place, so the
            // user isn't left staring at an empty scroll area after the remove.
            if (mSuggestionsContent.getParent() instanceof ScrollView) {
                ((ScrollView) mSuggestionsContent.getParent()).setScrollY(0);
            }

            rebuildSuggestionViews(mSuggestionsContent, input);
            mSuggestionsContent.measure(
                    View.MeasureSpec.makeMeasureSpec(mLastPopupWidth, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            mLastPopupHeight = mSuggestionsContent.getMeasuredHeight();
            mLastBuiltHistoryVersion = mMessageHistoryCtrl.getHistoryVersion();
            mLastAppliedPrefix = input;
            Logger.logInfo(LOG_TAG, "[autocomplete] popup content replaced in-place (no dismiss/show)");
            applyPopupGeometry(inputField);
            return;
        }

        // Dismiss any previously shown popup WITHOUT clearing mCurrentSuggestions:
        // dismissAutoCompleteSuggestions() also clears that list, and we still need it
        // below to build the suggestion views. Clearing it here would leave the popup
        // empty (completely silent auto-complete).
        if (mSuggestionsPopup != null) {
            try { mSuggestionsPopup.dismiss(); } catch (Exception ignored) {}
            mSuggestionsPopup = null;
        }

        LinearLayout content = new LinearLayout(mContext);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setBackgroundColor(Color.TRANSPARENT);

        int padH = mPopupItemPadHPx;
        int padV = mPopupItemPadVPx;
        final String input = inputField.getText().toString();

        rebuildSuggestionViews(content, input);

        // Wrap content in a ScrollView and size via WRAP_CONTENT + update() — this mirrors
        // the WORKING mHistoryPopup and avoids the zero-height / degenerate-shadow bug that a
        // non-focusable PopupWindow hits when given an explicit pixel height + setOutsideTouchable(true)
        // + a plain (possibly transparent) ColorDrawable background. On a non-focusable window
        // setOutsideTouchable() is a no-op, and a transparent ColorDrawable makes
        // GradientDrawable.getOutline() bail, collapsing the window to a thin shadow line.
        ScrollView scroll = new ScrollView(mContext);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(content, new android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT));
        // Clip children to the popup's rounded corners so highlights and
        // separators near the edges don't spill outside the rounded shape.
        // Guard against 0 dims during WRAP_CONTENT resize: if w or h is 0 the
        // outline is left empty (no clipping) instead of clipping everything.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            scroll.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    int w = view.getWidth();
                    int h = view.getHeight();
                    if (w > 0 && h > 0) {
                        outline.setRoundRect(0, 0, w, h, mPopupCornerRadiusPx);
                    }
                }
            });
            scroll.setClipToOutline(true);
        }
        // 10% visual transparency on the content (not the background drawable, so the
        // elevation shadow outline stays valid).
        scroll.setAlpha(mPopupContentAlpha);

        // Position the popup at the input cursor (caret) position
        int sumWidth = computePopupWidth(inputField.getWidth());
        content.measure(
                View.MeasureSpec.makeMeasureSpec(sumWidth, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int popupHeight = content.getMeasuredHeight();
        int popupX = calcPopupX(inputField, sumWidth);
        int popupY = calcPopupY(inputField, popupHeight, sumWidth);

        // focusable=false is the key: a non-focusable PopupWindow receives
        // FLAG_NOT_FOCUSABLE + (default) FLAG_ALT_FOCUSABLE_IM, so it stays
        // touchable (user can tap a suggestion) WITHOUT stealing window focus
        // from the EditText. The IME therefore stays up and the text panel
        // stays open. This is the same pattern as mHistoryPopup, which works
        // correctly; the only difference is touchable=true so items are tappable.
        mSuggestionsPopup = new PopupWindow(scroll, sumWidth,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT, false);
        // Smooth elevation shadow — background drawable must be fully opaque for the
        // WindowManager to derive a valid Outline (GradientDrawable.getOutline bails
        // when alpha < 255). The 10% visual transparency is on the ScrollView above.
        mSuggestionsPopup.setElevation(mPopupElevationPx);
        GradientDrawable popupBgDrawable = new GradientDrawable() {
            @Override
            public void getOutline(@NonNull Outline outline) {
                super.getOutline(outline);
                if (!outline.isEmpty() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    // Keep elevation large, but make the shadow softer/transparent.
                    outline.setAlpha(mPopupShadowAlpha);
                }
            }
        };
        popupBgDrawable.setShape(GradientDrawable.RECTANGLE);
        popupBgDrawable.setCornerRadius(mPopupCornerRadiusPx);
        popupBgDrawable.setColor(mColorSchemeManager.getHistoryPopupBg()); // must be opaque for getOutline
        mSuggestionsPopup.setBackgroundDrawable(popupBgDrawable);
        mSuggestionsPopup.setClippingEnabled(true);
        // Touchable so the suggestion items remain clickable; focusable=false so the
        // EditText keeps focus and the IME stays open. No setOutsideTouchable (it is a
        // no-op on a non-focusable window and was the trigger for the zero-height collapse).
        mSuggestionsPopup.setTouchable(true);
        mSuggestionsPopup.setFocusable(false);

        mSuggestionsPopup.setOnDismissListener(() -> mSuggestionsPopup = null);

        try {
            Window window = getWindow();
            if (window == null) {
                Logger.logWarn(LOG_TAG, "[autocomplete] cannot show popup: no window");
                return;
            }
            mSuggestionsPopup.showAtLocation(inputField, Gravity.NO_GRAVITY, popupX, popupY);
            // Apply the measured height (WRAP_CONTENT in the ctor would let a long
            // list grow past the screen; update() fixes the real height).
            mSuggestionsPopup.update(popupX, popupY, sumWidth, popupHeight);
            // Track layout changes (IME, scroll, resize) to keep the popup at the caret.
            if (mSuggestionsLayoutListener == null) {
                mSuggestionsLayoutListener = () -> repositionAutoCompletePopup();
                window.getDecorView().getViewTreeObserver().addOnGlobalLayoutListener(mSuggestionsLayoutListener);
            }
            // Save references for in-place updates
            mSuggestionsContent = content;
            mLastPopupWidth = sumWidth;
            mLastPopupHeight = popupHeight;
            mLastPopupX = popupX;
            mLastPopupY = popupY;
            mLastBuiltHistoryVersion = mMessageHistoryCtrl.getHistoryVersion();
            mLastAppliedPrefix = input;
            Logger.logInfo(LOG_TAG, "[autocomplete] popup shown at (" + popupX + "," + popupY
                    + ") w=" + sumWidth + " h=" + popupHeight);
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to show auto-complete popup", e);
            mSuggestionsPopup = null;
        }
    }

    /** Reposition the auto-complete popup at the current caret position. */
    public void repositionAutoCompletePopup() {
        if (mSuggestionsPopup == null || !mSuggestionsPopup.isShowing()) return;
        // Use a FRESH findViewById each call (matching the original TermuxActivity).
        // mInputField is captured once in the constructor and can go stale if the
        // input field view is detached/re-attached (e.g. on session switch), which
        // would make getLocationInWindow() return (0,0) and pin the popup at origin.
        final EditText inputField = mContext instanceof Activity
                ? ((Activity) mContext).findViewById(R.id.terminal_toolbar_text_input)
                : mInputField;
        if (inputField == null) return;
        Logger.logInfo(LOG_TAG, "[autocomplete] repositionAutoCompletePopup");
        applyPopupGeometry(inputField);
    }

    /**
     * Unified popup positioning: calculates x/y from caret, calls
     * {@code PopupWindow.update()} only when geometry actually changed.
     * Use from both {@link #updatePopupContent} and {@link #repositionAutoCompletePopup}
     * to avoid redundant IPC / shadow redraw.
     */
    private void applyPopupGeometry(@NonNull EditText inputField) {
        if (mSuggestionsPopup == null || !mSuggestionsPopup.isShowing()) {
            Logger.logInfo(LOG_TAG, "[autocomplete] applyPopupGeometry skip (popup not showing)");
            return;
        }
        Layout layout = inputField.getLayout();
        if (layout == null) {
            Logger.logInfo(LOG_TAG, "[autocomplete] applyPopupGeometry skip (layout null) — retrying");
            // Layout isn't ready yet (IME animation / initial frame). Retry
            // once after the next layout pass — without this the popup stays
            // pinned to (0,0) forever.
            inputField.post(() -> applyPopupGeometry(inputField));
            return;
        }
        int w = mLastPopupWidth > 0 ? mLastPopupWidth : computePopupWidth(inputField.getWidth());
        int h = mLastPopupHeight;
        if (w <= 0 || h <= 0) {
            Logger.logInfo(LOG_TAG, "[autocomplete] applyPopupGeometry skip (w=" + w + " h=" + h + ")");
            return;
        }
        int x = calcPopupX(inputField, w);
        int y = calcPopupY(inputField, h, w);
        if (x != mLastPopupX || y != mLastPopupY || h != mLastPopupHeight) {
            Logger.logInfo(LOG_TAG, "[autocomplete] applyPopupGeometry update(" + x + "," + y + " " + w + "x" + h
                    + ") old=(" + mLastPopupX + "," + mLastPopupY + " h=" + mLastPopupHeight + ")");
            mSuggestionsPopup.update(x, y, w, h);
            mLastPopupX = x;
            mLastPopupY = y;
        } else {
            Logger.logInfo(LOG_TAG, "[autocomplete] applyPopupGeometry skip (no change)");
        }
    }

    private void dismissAutoCompleteSuggestions() {
        // Guard against redundant calls (e.g. afterTextChanged firing repeatedly on
        // a clear). When nothing is pending to clean up, skip the diagnostic log and
        // the rest of the teardown so we don't emit repeated noise.
        if (mSuggestionsPopup == null && mCurrentSuggestions.isEmpty()) {
            return;
        }
        // Cancel any in-flight swipe-to-select gesture: its target row is going
        // away. resetSwipeState() also drops the suppression guard and restores
        // the parent touch-interception flag.
        if (mSwipeEngaged) {
            resetSwipeState();
        }
        Logger.logInfo(LOG_TAG, "[autocomplete] dismiss (popup=" + (mSuggestionsPopup != null)
                + " suggestions=" + mCurrentSuggestions.size() + ")");
        if (mSuggestionsPopup != null) {
            try { mSuggestionsPopup.dismiss(); } catch (Exception ignored) {}
            mSuggestionsPopup = null;
        }
        mCurrentSuggestions.clear();
        mCurrentIsShell.clear();
        mCurrentShellType.clear();
        mCurrentShellMeta.clear();
        mShellSuggestionCount = 0;
        // Invalidate any in-flight async shell fetch so its result can't resurrect
        // a dismissed popup or clobber the next word's suggestions.
        mShellGen.incrementAndGet();
        mShellDebounceHandler.removeCallbacksAndMessages(null);
        mSuggestionsContent = null;
        mLastAppliedPrefix = "";
        mLastPopupX = 0;
        mLastPopupY = 0;
        // NOTE: we intentionally do NOT clear the shell per-word cache here. The
        // provider caches candidates per (word-slot, cwd, prefix), so a dismissed
        // popup that is later rebuilt for the same word reuses the cached list
        // instead of re-spawning bash. The cache is invalidated automatically by a
        // different cword/cwd or a divergent prefix (e.g. backspace+retype), and is
        // explicitly cleared on session/cwd change (setCwdProvider) and toggle-off.
        if (mSuggestionsLayoutListener != null) {
            final android.view.View decor = (mContext instanceof Activity)
                    ? ((Activity) mContext).getWindow().getDecorView() : null;
            if (decor != null) {
                decor.getViewTreeObserver().removeOnGlobalLayoutListener(mSuggestionsLayoutListener);
            }
            mSuggestionsLayoutListener = null;
        }
    }
}

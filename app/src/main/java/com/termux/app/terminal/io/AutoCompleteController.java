package com.termux.app.terminal.io;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
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

import com.termux.app.TermuxActivityUtils;
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
    /** Current list of suggestion strings being displayed. */
    private final java.util.ArrayList<String> mCurrentSuggestions = new java.util.ArrayList<>();
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
        attachInputListeners();
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

    private int dpToPx(int dp) {
        return TermuxActivityUtils.dpToPx(mContext, dp);
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
        // Filter mCurrentSuggestions in-place
        final int preFilterCount = mCurrentSuggestions.size();
        for (int i = mCurrentSuggestions.size() - 1; i >= 0; i--) {
            String s = mCurrentSuggestions.get(i);
            if (s.length() <= text.length()
                    || !s.regionMatches(true, 0, text, 0, text.length())
                    || s.equals(text)) {
                mCurrentSuggestions.remove(i);
            }
        }
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
     * Path A: full re-scan of mMessageHistoryCtrl.getHistoryList() and fresh popup creation.
     * Same logic as the original updateAutoCompleteSuggestions().
     */
    private void fullRescanSuggestions(@NonNull String text, int maxCount) {
        mCurrentSuggestions.clear();
        for (String msg : mMessageHistoryCtrl.getHistoryList()) {
            if (mCurrentSuggestions.size() >= maxCount) break;
            if (msg.length() > text.length()
                    && msg.regionMatches(true, 0, text, 0, text.length())
                    && !msg.equals(text)) {
                mCurrentSuggestions.add(msg);
            }
        }

        Logger.logInfo(LOG_TAG, "[autocomplete] fullRescan text=\"" + text + "\" max=" + maxCount
                + " matches=" + mCurrentSuggestions.size());

        if (mCurrentSuggestions.isEmpty()) {
            dismissAutoCompleteSuggestions();
        } else {
            showAutoCompletePopup(mInputField);
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
    private static int wordStartOffset(@NonNull String s) {
        int i = s.length();
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
            @NonNull String input, int availWidth, @NonNull TextPaint paint) {
        int wordStart = Math.min(wordStartOffset(input), suggestion.length());
        int boldLen = input.length() - wordStart;
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
                && suggestion.regionMatches(true, 0, input, 0, input.length())) {
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

        // Precompute word-boundary info (shared across all child views)
        final int inputLen = newText.length();
        final int wordStart = Math.min(wordStartOffset(newText), inputLen);
        final int boldLen = inputLen - wordStart;
        final boolean hasLastWord = boldLen > 0;
        final String prefix = (wordStart > 0) ? "... " : "";
        final int prefixLen = prefix.length();

        Logger.logInfo(LOG_TAG, "[autocomplete] updateBoldSpansOnly wordStart=" + wordStart
                + " boldLen=" + boldLen + " views=" + content.getChildCount());
        for (int i = 0; i < content.getChildCount(); i++) {
            View child = content.getChildAt(i);
            if (!(child instanceof TextView)) continue;
            TextView tv = (TextView) child;
            String suggestion = (String) tv.getTag();
            if (suggestion == null) continue;

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
                        suggestion, newText, Math.max(0, availWidth), tv.getPaint());
                tv.setText(ss, TextView.BufferType.SPANNABLE);
            } else {
                // Display buffer unchanged → only mutate bold spans in-place (fast path)
                Spannable sp = (Spannable) currentText;
                StyleSpan[] old = sp.getSpans(0, sp.length(), StyleSpan.class);
                for (StyleSpan s : old) sp.removeSpan(s);
                if (hasLastWord && prefixLen + boldLen <= sp.length()
                        && suggestion.regionMatches(true, 0, newText, 0, inputLen)) {
                    sp.setSpan(new StyleSpan(Typeface.BOLD),
                            prefixLen, prefixLen + boldLen,
                            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
                tv.invalidate();
            }
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

        // Remove non-matching views (iterate backward so indices stay valid)
        final int inputLen = newText.length();
        final int prevChildCount = content.getChildCount();
        for (int i = prevChildCount - 1; i >= 0; i--) {
            View child = content.getChildAt(i);
            String suggestion = (String) child.getTag();
            if (suggestion == null
                    || suggestion.length() <= inputLen
                    || !suggestion.regionMatches(true, 0, newText, 0, inputLen)
                    || suggestion.equals(newText)) {
                content.removeViewAt(i);
            }
        }
        boolean contentChanged = (content.getChildCount() != prevChildCount);

        // Rebuild mCurrentSuggestions list to match the current views
        mCurrentSuggestions.clear();
        for (int i = 0; i < content.getChildCount(); i++) {
            View child = content.getChildAt(i);
            String tag = (String) child.getTag();
            if (tag != null) mCurrentSuggestions.add(tag);
        }

        // Top-up from history if needed
        int maxCount = mPrefs.getInt("suggestions_max_count", 4);
        if (maxCount < 0) maxCount = 0;
        if (maxCount > 10) maxCount = 10;
        final int preTopUpCount = content.getChildCount();
        if (mCurrentSuggestions.size() < maxCount) {
            for (String msg : mMessageHistoryCtrl.getHistoryList()) {
                if (mCurrentSuggestions.size() >= maxCount) break;
                if (!mCurrentSuggestions.contains(msg)
                        && msg.length() > newText.length()
                        && msg.regionMatches(true, 0, newText, 0, newText.length())
                        && !msg.equals(newText)) {
                    mCurrentSuggestions.add(msg);
                    // Create a new TextView for this top-up item
                    TextView tv = buildSuggestionTextView(msg, newText);
                    content.addView(tv, new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT));
                }
            }
        }
        if (content.getChildCount() != preTopUpCount) contentChanged = true;

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

        // Structural change — update bold-spans, measure, resize
        Logger.logInfo(LOG_TAG, "[autocomplete] → STRUCTURAL CHANGE (measure+update)");
        updateBoldSpansOnly(newText);

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

    /** Build a single suggestion TextView (reusable helper). */
    private TextView buildSuggestionTextView(@NonNull String suggestion, @NonNull String input) {
        int padH = dpToPx(14);
        int padV = dpToPx(10);
        TextView tv = new TextView(mContext);

        tv.setTextColor(mColorSchemeManager.getHistoryTextColor());
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        tv.setPadding(padH, padV, padH, padV);

        // Available text width = popup width minus horizontal padding. Mirrors the
        // width the popup is sized to in showAutoCompletePopup (sumWidth).
        int popupWidth = Math.max(dpToPx(200),
                (mInputField != null ? mInputField.getWidth() : 0) - dpToPx(16));
        int availWidth = Math.max(0, popupWidth - 2 * padH);

        // Word-based leading truncation + manual trailing '…' (TextView's
        // setEllipsize(END) is unreliable for maxLines>1 on API 21-28).
        SpannableString ss = buildSuggestionSpannable(
                suggestion, input, availWidth, tv.getPaint());
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

        tv.setOnClickListener(v -> {
            mSuppressAutoComplete = true;
            try {
                if (mInputField != null) {
                    mInputField.setText(finalSuggestion);
                    mInputField.setSelection(finalSuggestion.length());
                }
            } finally {
                mSuppressAutoComplete = false;
            }
            dismissAutoCompleteSuggestions();
            mMessageHistoryDismissListener.run();
        });
        return tv;
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
        int popupX = loc[0] + (int) cursorX + dpToPx(4);
        int displayWidth = mContext.getResources().getDisplayMetrics().widthPixels;
        if (popupX + popupWidth > displayWidth - dpToPx(8)) {
            popupX = displayWidth - popupWidth - dpToPx(8);
        }
        if (popupX < dpToPx(8)) popupX = dpToPx(8);
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
        int popupY = loc[1] + (int) cursorY - popupHeight - dpToPx(4);
        if (popupY < dpToPx(16)) {
            popupY = loc[1] + (int) cursorY + dpToPx(8);
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

            // Content-changed guard: skip the removeAllViews + addViews + measure cycle
            // when the suggestion list is identical to what's already shown (e.g. an IME
            // re-compose of the same text that re-triggers Path A). Mirrors the
            // contentChanged check in updatePopupContent(), comparing the EXISTING
            // mSuggestionsContent children against mCurrentSuggestions.
            boolean contentChanged = mSuggestionsContent.getChildCount() != mCurrentSuggestions.size();
            if (!contentChanged) {
                for (int i = 0; i < mCurrentSuggestions.size(); i++) {
                    String existing = ((TextView) mSuggestionsContent.getChildAt(i)).getText().toString();
                    if (!existing.equals(mCurrentSuggestions.get(i))) {
                        contentChanged = true;
                        break;
                    }
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

            mSuggestionsContent.removeAllViews();
            for (int i = 0; i < mCurrentSuggestions.size(); i++) {
                mSuggestionsContent.addView(
                        buildSuggestionTextView(mCurrentSuggestions.get(i), input),
                        new LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT));
            }
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

        int padH = dpToPx(14);
        int padV = dpToPx(10);
        final String input = inputField.getText().toString();

        for (int i = 0; i < mCurrentSuggestions.size(); i++) {
            final String suggestion = mCurrentSuggestions.get(i);
            TextView tv = buildSuggestionTextView(suggestion, input);
            content.addView(tv, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
        }

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
                        outline.setRoundRect(0, 0, w, h, dpToPx(12));
                    }
                }
            });
            scroll.setClipToOutline(true);
        }
        // 10% visual transparency on the content (not the background drawable, so the
        // elevation shadow outline stays valid).
        scroll.setAlpha(0.9f);

        // Position the popup at the input cursor (caret) position
        int sumWidth = Math.max(dpToPx(200), inputField.getWidth() - dpToPx(16));
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
        mSuggestionsPopup.setElevation(dpToPx(16));
        GradientDrawable popupBgDrawable = new GradientDrawable() {
            @Override
            public void getOutline(@NonNull Outline outline) {
                super.getOutline(outline);
                if (!outline.isEmpty() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    // Keep elevation large, but make the shadow softer/transparent.
                    outline.setAlpha(0.65f);
                }
            }
        };
        popupBgDrawable.setShape(GradientDrawable.RECTANGLE);
        popupBgDrawable.setCornerRadius(dpToPx(12));
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
    private void repositionAutoCompletePopup() {
        if (mSuggestionsPopup == null || !mSuggestionsPopup.isShowing()) return;
        final EditText inputField = mInputField;
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
        int w = mLastPopupWidth > 0 ? mLastPopupWidth :
                Math.max(dpToPx(200), inputField.getWidth() - dpToPx(16));
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
        Logger.logInfo(LOG_TAG, "[autocomplete] dismiss (popup=" + (mSuggestionsPopup != null)
                + " suggestions=" + mCurrentSuggestions.size() + ")");
        if (mSuggestionsPopup != null) {
            try { mSuggestionsPopup.dismiss(); } catch (Exception ignored) {}
            mSuggestionsPopup = null;
        }
        mCurrentSuggestions.clear();
        mSuggestionsContent = null;
        mLastAppliedPrefix = "";
        mLastPopupX = 0;
        mLastPopupY = 0;
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

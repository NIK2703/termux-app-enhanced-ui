package com.termux.app.terminal.io.autocomplete;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.text.Layout;
import android.text.TextUtils;
import android.view.Gravity;
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

import com.termux.R;
import com.termux.app.terminal.TermuxColorSchemeManager;

/**
 * Owns the message-history auto-complete popup window and all of its lifecycle:
 * building, showing, positioning, content rebuild, bold-span refresh and
 * dismissal.
 *
 * <p>This class contains NO suggestion data and NO input/fetch logic — it is
 * handed everything it needs through {@link AutoCompleteDataProvider} and a
 * small bundle of pre-read resource dimensions. That keeps the rendering
 * concern fully isolated from {@code AutoCompleteController}'s orchestration.
 */
final class AutoCompletePopupManager {

    @NonNull private final Context mContext;
    @NonNull private final AutoCompleteDataProvider mData;
    @NonNull private final TermuxColorSchemeManager mColorSchemeManager;

    // Resource dimensions captured once by the host.
    private final int mPopupCornerRadiusPx;
    private final int mPopupElevationPx;
    private final int mPopupMinWidthPx;
    private final int mPopupWidthMarginPx;
    private final float mPopupWidthFraction;
    private final int mPopupXOffsetPx;
    private final int mPopupEdgeMarginPx;
    private final int mPopupYOffsetPx;
    private final int mPopupMinYPx;
    private final float mPopupContentAlpha;
    private final float mPopupShadowAlpha;

    // ── Popup window state (owned here, not in the controller) ──
    @Nullable private PopupWindow mHistoryPopup;
    @Nullable private LinearLayout mHistoryContent;
    /**
     * Layout-change listener attached to the INPUT FIELD (not the decor view), so a
     * reposition is only triggered when the input field's own layout changes — not
     * on every Activity-wide layout pass (which caused a redundant second reposition
     * and visible jitter).
     */
    @Nullable private View.OnLayoutChangeListener mSuggestionsLayoutListener;
    /** The view the layout listener is registered on, so we can unregister the exact same view. */
    @Nullable private View mLayoutListenerTarget;

    private int mLastPopupWidth = 0;
    private int mLastPopupHeight = 0;
    private int mLastPopupX = 0;
    private int mLastPopupY = 0;

    // Reused scratch array for getLocationInWindow in the hot positioning path
    // (every keystroke) — avoids a new int[2] allocation per call.
    private final int[] mTmpLoc = new int[2];

    // Reused BOLD span instance — avoids a new StyleSpan allocation on every
    // character typed (applyBoldSpan runs on each visible line per keystroke).
    private static final android.text.style.StyleSpan BOLD_SPAN =
            new android.text.style.StyleSpan(android.graphics.Typeface.BOLD);

    private int mLastBuiltHistoryVersion = -1;
    @Nullable private String mLastAppliedPrefix = "";

    AutoCompletePopupManager(@NonNull Context context,
                             @NonNull AutoCompleteDataProvider data,
                             @NonNull TermuxColorSchemeManager colorSchemeManager,
                             int popupCornerRadiusPx, int popupElevationPx,
                             int popupItemPadHPx, int popupItemPadVPx,
                             int popupMinWidthPx, int popupWidthMarginPx,
                             float popupWidthFraction, int popupXOffsetPx,
                             int popupEdgeMarginPx, int popupYOffsetPx,
                             int popupMinYPx, float popupContentAlpha,
                             float popupShadowAlpha) {
        mContext = context;
        mData = data;
        mColorSchemeManager = colorSchemeManager;
        mPopupCornerRadiusPx = popupCornerRadiusPx;
        mPopupElevationPx = popupElevationPx;
        mPopupMinWidthPx = popupMinWidthPx;
        mPopupWidthMarginPx = popupWidthMarginPx;
        mPopupWidthFraction = popupWidthFraction;
        mPopupXOffsetPx = popupXOffsetPx;
        mPopupEdgeMarginPx = popupEdgeMarginPx;
        mPopupYOffsetPx = popupYOffsetPx;
        mPopupMinYPx = popupMinYPx;
        mPopupContentAlpha = popupContentAlpha;
        mPopupShadowAlpha = popupShadowAlpha;
        // Item padding is read by the host's buildSuggestionTextView, not here.
        // (kept in the signature for symmetry / future use)
        //noinspection unused
        int _padH = popupItemPadHPx; // referenced by host only
        //noinspection unused
        int _padV = popupItemPadVPx;
    }

    // ── Public surface used by AutoCompleteController ──

    boolean isShowing() {
        return (mHistoryPopup != null && mHistoryPopup.isShowing());
    }

    void show(@NonNull EditText inputField) {
        showAutoCompletePopup(inputField);
    }

    void update(@NonNull String newText, @NonNull EditText inputField) {
        updatePopupContent(newText, inputField);
    }

    void applyGeometry(@NonNull EditText inputField) {
        applyPopupGeometry(inputField);
    }

    void reposition() {
        repositionAutoCompletePopup();
    }

    void dismiss() {
        dismissAutoCompleteSuggestions();
    }

    @Nullable PopupWindow debugHistoryPopup() { return mHistoryPopup; }
    @Nullable LinearLayout debugHistoryContent() { return mHistoryContent; }
    int debugGetHistoryY() { return mLastPopupY; }

    // ── Width / geometry (shared by both windows) ──

    int computePopupWidth(int fieldWidth) {
        int base = Math.max(fieldWidth, mPopupMinWidthPx);
        int displayWidth = mContext.getResources().getDisplayMetrics().widthPixels;
        int maxWidth = (int) (displayWidth * mPopupWidthFraction);
        maxWidth = Math.min(maxWidth, displayWidth - 2 * mPopupWidthMarginPx);
        return Math.min(base, maxWidth);
    }

    private int calcPopupX(@NonNull EditText inputField, int popupWidth) {
        int[] loc = mTmpLoc;
        inputField.getLocationInWindow(loc);
        return calcPopupX(inputField, popupWidth, loc[0], loc[1]);
    }

    private int calcPopupX(@NonNull EditText inputField, int popupWidth, int locX, int locY) {
        int cursorPos = inputField.getSelectionStart();
        Layout layout = inputField.getLayout();
        float cursorX = 0;
        if (layout != null && cursorPos >= 0) {
            cursorX = layout.getPrimaryHorizontal(cursorPos);
        }
        int popupX = locX + (int) cursorX - inputField.getScrollX() + mPopupXOffsetPx;
        int displayWidth = mContext.getResources().getDisplayMetrics().widthPixels;
        if (popupX + popupWidth > displayWidth - mPopupEdgeMarginPx) {
            popupX = displayWidth - popupWidth - mPopupEdgeMarginPx;
        }
        if (popupX < mPopupEdgeMarginPx) popupX = mPopupEdgeMarginPx;
        return popupX;
    }

    private int calcPopupY(@NonNull EditText inputField, int popupHeight, int popupWidth) {
        int[] loc = mTmpLoc;
        inputField.getLocationInWindow(loc);
        return calcPopupY(inputField, popupHeight, popupWidth, loc[0], loc[1]);
    }

    private int calcPopupY(@NonNull EditText inputField, int popupHeight, int popupWidth, int locX, int locY) {
        int cursorPos = inputField.getSelectionStart();
        Layout layout = inputField.getLayout();
        float cursorY = 0;
        if (layout != null && cursorPos >= 0) {
            cursorY = layout.getLineTop(layout.getLineForOffset(cursorPos));
        }
        // Anchor the popup to the caret: it appears ABOVE the caret's line in the
        // input field. If there is no room above (it would fall above the allowed
        // minimum), show it BELOW the caret line instead — so it never covers the caret.
        int popupY = locY + (int) cursorY - inputField.getScrollY() - popupHeight - mPopupYOffsetPx;
        if (popupY < mPopupMinYPx) {
            popupY = locY + (int) cursorY - inputField.getScrollY() + mPopupEdgeMarginPx;
        }
        return popupY;
    }

    // ── Show / build ──

    private void showAutoCompletePopup(@NonNull EditText inputField) {
        final String input = inputField.getText().toString();
        int historyN = mData.getSuggestions().size();
        final boolean hasHistory = historyN > 0;

        boolean historyReuse = mHistoryPopup != null && mHistoryPopup.isShowing()
                && mHistoryContent != null && mHistoryContent.getParent() != null;

        if (!hasHistory || (historyReuse && !contentChangedForWindow())) {
            // Rebuild (not just re-bold) so the highlight tracks the edited prefix
            // reliably — an in-place span mutation inside a showing PopupWindow is
            // not guaranteed to repaint (and is exactly why a backspace left the
            // old characters bold).
            if (mHistoryContent != null) rebuildHistoryViews(mHistoryContent, input);
            mLastBuiltHistoryVersion = mData.getHistoryVersion();
            mLastAppliedPrefix = input;
            applyPopupGeometry(inputField);
            return;
        }

        if (!hasHistory && mHistoryPopup != null) { try { mHistoryPopup.dismiss(); } catch (Exception ignored) {} mHistoryPopup = null; mHistoryContent = null; }

        if (hasHistory) {
            if (historyReuse && mHistoryContent != null) {
                final int savedScroll = mHistoryContent.getScrollY();
                rebuildHistoryViews(mHistoryContent, input);
                mHistoryContent.setScrollY(savedScroll);
            } else {
                if (mHistoryPopup != null) { try { mHistoryPopup.dismiss(); } catch (Exception ignored) {} mHistoryPopup = null; }
                mHistoryContent = new LinearLayout(mContext);
                mHistoryContent.setOrientation(LinearLayout.VERTICAL);
                mHistoryContent.setBackgroundColor(Color.TRANSPARENT);
                rebuildHistoryViews(mHistoryContent, input);
                mHistoryPopup = buildPopupWindow(mHistoryContent);
                showPopupAtCaret(mHistoryPopup, inputField);
            }
        }

        mLastBuiltHistoryVersion = mData.getHistoryVersion();
        mLastAppliedPrefix = input;
        applyPopupGeometry(inputField);

        if (mSuggestionsLayoutListener == null) {
            mSuggestionsLayoutListener =
                    (v, l, t, r, b, ol, ot, or, ob) -> {
                        // Geometry unchanged (size of the input field is the same) → no
                        // reposition. This drops the redundant reposition on per-character
                        // input where the text changes but the field's box does not.
                        if (l == ol && t == ot && r == or && b == ob) return;
                        repositionAutoCompletePopup();
                    };
            mLayoutListenerTarget = inputField;
            inputField.addOnLayoutChangeListener(mSuggestionsLayoutListener);
        }
    }

    private void showPopupAtCaret(@NonNull PopupWindow popup, @NonNull EditText inputField) {
        int w = computePopupWidth(inputField.getWidth());
        int h = 0;
        if (popup.getContentView() instanceof ScrollView) {
            View child = ((ScrollView) popup.getContentView()).getChildAt(0);
            if (child != null) h = child.getMeasuredHeight();
        }
        int[] loc = mTmpLoc;
        inputField.getLocationInWindow(loc);
        int x = calcPopupX(inputField, w, loc[0], loc[1]);
        int y = calcPopupY(inputField, h, w, loc[0], loc[1]);
        try {
            popup.showAtLocation(inputField, Gravity.NO_GRAVITY, x, y);
        } catch (Exception ignored) {
        }
    }

    @NonNull
    private PopupWindow buildPopupWindow(@NonNull LinearLayout content) {
        int sumWidth = computePopupWidth(mData.getInputField() != null ? mData.getInputField().getWidth() : 0);

        ScrollView scroll = new ScrollView(mContext);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(content, new android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT));
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
        scroll.setAlpha(mPopupContentAlpha);

        content.measure(
                View.MeasureSpec.makeMeasureSpec(sumWidth, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));

        PopupWindow popup = new PopupWindow(scroll, sumWidth,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT, false);
        popup.setElevation(mPopupElevationPx);
        GradientDrawable popupBgDrawable = new GradientDrawable() {
            @Override
            public void getOutline(@NonNull Outline outline) {
                super.getOutline(outline);
                if (!outline.isEmpty() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    outline.setAlpha(mPopupShadowAlpha);
                }
            }
        };
        popupBgDrawable.setShape(GradientDrawable.RECTANGLE);
        popupBgDrawable.setCornerRadius(mPopupCornerRadiusPx);
        popupBgDrawable.setColor(mColorSchemeManager.getHistoryPopupBg());
        popup.setBackgroundDrawable(popupBgDrawable);
        popup.setClippingEnabled(true);
        popup.setTouchable(true);
        popup.setFocusable(false);
        popup.setOnDismissListener(() -> {
            if (popup == mHistoryPopup) { mHistoryPopup = null; mHistoryContent = null; }
        });
        return popup;
    }

    // ── Reposition / geometry ──

    void repositionAutoCompletePopup() {
        if (!isShowing()) return;
        // Keep the popup alive while a swipe-to-select gesture holds a deliberate
        // text selection (it repeatedly moves the caret inside the text). Without
        // this guard a layout event mid-swipe would dismiss the popup behind the
        // gesture, which both fixes miss.
        if (mData.isSwipeActive()) return;
        EditText inputField = mData.getInputField();
        // Do not dismiss while an IME composition is active: the caret can briefly
        // sit inside the composing span even though the user is still typing.
        if (inputField != null && !AutoCompleteController.hasComposingSpan(inputField)) {
            int caret = inputField.getSelectionStart();
            if (caret < 0 || caret != inputField.getText().length()) {
                dismissAutoCompleteSuggestions();
                return;
            }
        }
        if (inputField == null) return;
        applyPopupGeometry(inputField);
    }

    private void applyPopupGeometry(@NonNull EditText inputField) {
        if (!isShowing()) {
            return;
        }
        Layout layout = inputField.getLayout();
        if (layout == null) {
            inputField.post(() -> applyPopupGeometry(inputField));
            return;
        }
        int w = mLastPopupWidth > 0 ? mLastPopupWidth : computePopupWidth(inputField.getWidth());
        if (w <= 0) {
            return;
        }

        int[] loc = mTmpLoc;
        inputField.getLocationInWindow(loc);

        // First measure the content height, then compute Y with the real height —
        // otherwise the geometry guard would compare a Y computed with height 0 and
        // desync from the initial show.
        int historyH = 0;
        if (mHistoryPopup != null && mHistoryPopup.isShowing() && mHistoryContent != null) {
            mHistoryContent.measure(
                    View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            historyH = mHistoryContent.getMeasuredHeight();
        }

        int historyX = calcPopupX(inputField, w, loc[0], loc[1]);
        int historyY = calcPopupY(inputField, historyH, w, loc[0], loc[1]);

        // Geometry guard: if the popup's X/Y did not change, the caret did not
        // visually move — skip the content measure entirely and avoid PopupWindow
        // update jitter on every keystroke.
        if (mHistoryPopup != null && mHistoryPopup.isShowing()
                && historyX == mLastPopupX && historyY == mLastPopupY) {
            return;
        }

        if (mHistoryPopup != null && mHistoryPopup.isShowing()) {
            mLastPopupWidth = w;
            mHistoryPopup.update(historyX, historyY, w, historyH);
            mLastPopupX = historyX;
            mLastPopupY = historyY;
            mLastPopupHeight = historyH;
        }
    }

    // ── Content update (Path B) ──

    private void updatePopupContent(@NonNull String newText, @NonNull EditText inputField) {
        boolean historyShowing = mHistoryPopup != null && mHistoryPopup.isShowing();
        if (!historyShowing) {
            showAutoCompletePopup(inputField);
            return;
        }
        final int historyN = mData.getSuggestions().size();
        if (historyN > 0 && !historyShowing) {
            showAutoCompletePopup(inputField);
            return;
        }

        boolean historyChanged = contentChangedForWindow();
        boolean prefixChanged = !newText.equals(mLastAppliedPrefix);

        if ((historyChanged || prefixChanged) && mHistoryContent != null) {
            // Rebuild the views whenever the shown set OR the typed prefix changed.
            // Rebuilding (instead of mutating bold spans in place) is what makes the
            // highlight track deletions reliably: an in-place Spannable mutation on a
            // TextView inside an already-showing PopupWindow is not guaranteed to
            // repaint (and the geometry guard below can skip popup.update()), so a
            // backspace would leave the old characters bold. A fresh rebuild always
            // renders the correct bold region.
            final int savedScroll = mHistoryContent.getScrollY();
            rebuildHistoryViews(mHistoryContent, newText);
            mHistoryContent.setScrollY(savedScroll);
        }

        // mLastAppliedPrefix is updated inside rebuildHistoryViews()/show.
        applyPopupGeometry(inputField);
    }

    // ── Per-window rebuild + bold spans ──

    private void rebuildHistoryViews(@NonNull LinearLayout content, @NonNull String input) {
        content.removeAllViews();
        final int n = displayCount();
        int rendered = 0;
        // Reversed rendering: oldest item on top, newest (history index 0) at the
        // very bottom of the list. History entries are stored newest-first, so we
        // walk from (n-1) down to 0.
        for (int i = n - 1; i >= 0 && rendered < n; i--, rendered++) {
            String suggestion = mData.getSuggestions().get(i);
            TextView tv = mData.buildSuggestionTextView(suggestion, input, false);
            content.addView(tv, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
        }
        // The rebuilt views reflect the current prefix, so record it as applied.
        // Without this, updatePopupContent would treat every subsequent keystroke
        // as a prefix change and rebuild unconditionally.
        mLastAppliedPrefix = input;
    }

    private void updateBoldSpansOnly(@NonNull String newText) {
        updateBoldSpansInWindow(mHistoryPopup, mHistoryContent, newText);
    }

    /** Cheap refresh of bold spans only (no rebuild/dismiss) for the active popup. */
    void updateBoldOnly(@NonNull String newText) {
        updateBoldSpansOnly(newText);
    }

    private void updateBoldSpansInWindow(@Nullable PopupWindow popup,
                                          @Nullable LinearLayout content,
                                          @NonNull String newText) {
        if (popup == null || !popup.isShowing() || content == null) return;
        // Skip entirely if the prefix text did not change.
        if (newText.equals(mLastAppliedPrefix)) return;
        mLastAppliedPrefix = newText;
        final int n = displayCount();
        int rendered = 0;
        int viewIdx = 0;
        // In sync with rebuildHistoryViews: the view at position viewIdx shows the
        // history entry with index (n-1 - viewIdx), i.e. newest at the bottom.
        for (int displayIdx = n - 1; displayIdx >= 0 && rendered < n; displayIdx--, rendered++) {
            TextView tv = textViewAt(content, viewIdx++);
            if (tv == null) break;
            applyBoldSpan(tv, mData.getSuggestions().get(displayIdx), newText);
        }
    }

    private static TextView textViewAt(@NonNull LinearLayout content, int i) {
        if (i < 0 || i >= content.getChildCount()) return null;
        View child = content.getChildAt(i);
        return (child instanceof TextView) ? (TextView) child : null;
    }

    private void applyBoldSpan(@NonNull TextView tv, @NonNull String suggestion,
            @NonNull String newText) {
        final int inputLen = newText.length();
        final int wordStart = Math.min(AutoCompleteTextRenderer.wordStartOffset(newText), suggestion.length());
        final int boldLen = inputLen - wordStart;
        final boolean hasLastWord = boldLen > 0;
        final String prefix = (wordStart > 0) ? "... " : "";
        final int prefixLen = prefix.length();

        int ws = Math.min(wordStart, suggestion.length());

        CharSequence currentText = tv.getText();
        // Lazily compute the expected display string — only on the rebuild branch
        // (rare case of crossing a word boundary). Normally currentText already
        // equals suggestion, so we compare without concatenating substring+prefix.
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
            int availWidth = tv.getWidth() - tv.getPaddingLeft() - tv.getPaddingRight();
            android.text.SpannableString ss = AutoCompleteTextRenderer.buildSuggestionSpannable(
                    suggestion, newText, Math.max(0, availWidth), tv.getPaint(), false);
            tv.setText(ss, TextView.BufferType.SPANNABLE);
        } else {
            // The only bold-span is known (BOLD_SPAN), so remove it directly
            // without a getSpans() array allocation per line per character.
            android.text.Spannable sp = (android.text.Spannable) currentText;
            sp.removeSpan(BOLD_SPAN);
            if (hasLastWord && prefixLen + boldLen <= sp.length()
                    && suggestion.regionMatches(true, 0, newText, 0, inputLen)) {
                sp.setSpan(BOLD_SPAN,
                        prefixLen, prefixLen + boldLen,
                        android.text.SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            // Re-set the (already mutated) Spannable so the TextView re-measures and
            // repaints. invalidate() alone is unreliable for an in-place span change
            // inside a showing PopupWindow (and is why the popup could appear frozen
            // on backspace, where caret X often doesn't move enough to force a window
            // update()).
            tv.setText(sp, TextView.BufferType.SPANNABLE);
        }
    }

    // ── Display math ──

    private int displayCount() {
        return Math.min(displayMax(), mData.getSuggestions().size());
    }

    private int displayMax() {
        int maxCount = mData.getDisplayMax();
        if (maxCount < 0) maxCount = 0;
        if (maxCount > 10) maxCount = 10;
        return maxCount;
    }

    private boolean contentChangedForWindow() {
        LinearLayout content = mHistoryContent;
        PopupWindow popup = mHistoryPopup;
        if (popup == null || !popup.isShowing() || content == null) return false;
        final int n = displayCount();
        if (content.getChildCount() != Math.min(n, mData.getSuggestions().size())) return true;
        int rendered = 0;
        int viewIdx = 0;
        // The view order is reversed relative to the history: view viewIdx maps to
        // the history entry with index (n-1 - viewIdx).
        for (int displayIdx = n - 1; displayIdx >= 0 && rendered < n; displayIdx--, rendered++) {
            TextView tv = textViewAt(content, viewIdx++);
            if (tv == null) break;
            String expectedText = mData.getSuggestions().get(displayIdx);
            if (!tv.getTag().equals(expectedText)) return true;
        }
        return false;
    }

    // ── Dismiss ──

    private void dismissAutoCompleteSuggestions() {
        if (mHistoryPopup == null) {
            return;
        }
        if (mHistoryPopup != null) {
            try { mHistoryPopup.dismiss(); } catch (Exception ignored) {}
            mHistoryPopup = null;
        }
        mHistoryContent = null;
        mLastAppliedPrefix = "";
        mLastPopupX = 0;
        mLastPopupY = 0;
        if (mSuggestionsLayoutListener != null) {
            if (mLayoutListenerTarget != null) {
                mLayoutListenerTarget.removeOnLayoutChangeListener(mSuggestionsLayoutListener);
            }
            mSuggestionsLayoutListener = null;
            mLayoutListenerTarget = null;
        }
        // The controller owns the suggestion data; it clears its own lists and
        // resets the fetch state in onSuggestionDismissed().
        mData.onSuggestionDismissed();
    }
}

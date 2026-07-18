package com.termux.app.terminal.io.autocomplete;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.text.Layout;
import android.util.Log;
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
import com.termux.shared.logger.Logger;

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

    private static final String LOG_TAG = "AutoCompletePopupManager";

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
    @Nullable private android.view.ViewTreeObserver.OnGlobalLayoutListener mSuggestionsLayoutListener;

    private int mLastPopupWidth = 0;
    private int mLastPopupHeight = 0;
    private int mLastPopupX = 0;
    private int mLastPopupY = 0;

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
        int cursorPos = inputField.getSelectionStart();
        Layout layout = inputField.getLayout();
        float cursorX = 0;
        if (layout != null && cursorPos >= 0) {
            cursorX = layout.getPrimaryHorizontal(cursorPos);
        }
        int[] loc = new int[2];
        inputField.getLocationInWindow(loc);
        int popupX = loc[0] + (int) cursorX - inputField.getScrollX() + mPopupXOffsetPx;
        int displayWidth = mContext.getResources().getDisplayMetrics().widthPixels;
        if (popupX + popupWidth > displayWidth - mPopupEdgeMarginPx) {
            popupX = displayWidth - popupWidth - mPopupEdgeMarginPx;
        }
        if (popupX < mPopupEdgeMarginPx) popupX = mPopupEdgeMarginPx;
        return popupX;
    }

    private int calcPopupY(@NonNull EditText inputField, int popupHeight, int popupWidth) {
        int cursorPos = inputField.getSelectionStart();
        Layout layout = inputField.getLayout();
        float cursorY = 0;
        if (layout != null && cursorPos >= 0) {
            cursorY = layout.getLineTop(layout.getLineForOffset(cursorPos));
        }
        int[] loc = new int[2];
        inputField.getLocationInWindow(loc);
        int popupY = loc[1] + (int) cursorY - inputField.getScrollY() - popupHeight - mPopupYOffsetPx;
        if (popupY < mPopupMinYPx) {
            popupY = loc[1] + (int) cursorY - inputField.getScrollY() + mPopupEdgeMarginPx;
        }
        return popupY;
    }

    // ── Show / build ──

    private void showAutoCompletePopup(@NonNull EditText inputField) {
        Logger.logInfo(LOG_TAG, "[autocomplete] showAutoCompletePopup suggestions="
                + mData.getSuggestions().size());

        final String input = inputField.getText().toString();
        int historyN = mData.getSuggestions().size();
        final boolean hasHistory = historyN > 0;

        boolean historyReuse = mHistoryPopup != null && mHistoryPopup.isShowing()
                && mHistoryContent != null && mHistoryContent.getParent() != null;

        if (!hasHistory || (historyReuse && !contentChangedForWindow())) {
            Logger.logInfo(LOG_TAG, "[autocomplete] popup reuse FAST PATH (bold-spans only)");
            updateBoldSpansOnly(input);
            mLastBuiltHistoryVersion = mData.getHistoryVersion();
            mLastAppliedPrefix = input;
            applyPopupGeometry(inputField);
            return;
        }

        if (!hasHistory && mHistoryPopup != null) { try { mHistoryPopup.dismiss(); } catch (Exception ignored) {} mHistoryPopup = null; mHistoryContent = null; }

        if (hasHistory) {
            if (historyReuse && mHistoryContent != null) {
                mHistoryContent.setScrollY(0);
                rebuildHistoryViews(mHistoryContent, input);
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
        Logger.logInfo(LOG_TAG, "[autocomplete] popup built (history=" + hasHistory + ")");
        applyPopupGeometry(inputField);

        Window window = mData.getWindow();
        if (window != null && mSuggestionsLayoutListener == null) {
            mSuggestionsLayoutListener = this::repositionAutoCompletePopup;
            window.getDecorView().getViewTreeObserver().addOnGlobalLayoutListener(mSuggestionsLayoutListener);
        }
    }

    private void showPopupAtCaret(@NonNull PopupWindow popup, @NonNull EditText inputField) {
        int w = computePopupWidth(inputField.getWidth());
        int h = 0;
        if (popup.getContentView() instanceof ScrollView) {
            View child = ((ScrollView) popup.getContentView()).getChildAt(0);
            if (child != null) h = child.getMeasuredHeight();
        }
        int x = calcPopupX(inputField, w);
        int y = calcPopupY(inputField, h, w);
        try {
            popup.showAtLocation(inputField, Gravity.NO_GRAVITY, x, y);
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to show auto-complete popup", e);
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
        if (inputField != null) {
            int caret = inputField.getSelectionStart();
            String text = inputField.getText().toString();
            if (caret < 0 || caret != text.length()) {
                dismissAutoCompleteSuggestions();
                return;
            }
        }
        final EditText field = mContext instanceof Activity
                ? ((Activity) mContext).findViewById(R.id.terminal_toolbar_text_input)
                : inputField;
        if (field == null) return;
        Logger.logInfo(LOG_TAG, "[autocomplete] repositionAutoCompletePopup");
        applyPopupGeometry(field);
    }

    private void applyPopupGeometry(@NonNull EditText inputField) {
        if (!isShowing()) {
            Logger.logInfo(LOG_TAG, "[autocomplete] applyPopupGeometry skip (no window showing)");
            return;
        }
        Layout layout = inputField.getLayout();
        if (layout == null) {
            Logger.logInfo(LOG_TAG, "[autocomplete] applyPopupGeometry skip (layout null) — retrying");
            inputField.post(() -> applyPopupGeometry(inputField));
            return;
        }
        int w = mLastPopupWidth > 0 ? mLastPopupWidth : computePopupWidth(inputField.getWidth());
        if (w <= 0) {
            Logger.logInfo(LOG_TAG, "[autocomplete] applyPopupGeometry skip (w=" + w + ")");
            return;
        }

        int historyH = 0;
        if (mHistoryPopup != null && mHistoryPopup.isShowing() && mHistoryContent != null) {
            mHistoryContent.measure(
                    View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            historyH = mHistoryContent.getMeasuredHeight();
        }

        int historyX = calcPopupX(inputField, w);
        int historyY = calcPopupY(inputField, historyH, w);

        if (mHistoryPopup != null && mHistoryPopup.isShowing()) {
            mLastPopupWidth = w;
            if (historyX != mLastPopupX || historyY != mLastPopupY || historyH != mLastPopupHeight) {
                mHistoryPopup.update(historyX, historyY, w, historyH);
                mLastPopupX = historyX;
                mLastPopupY = historyY;
                mLastPopupHeight = historyH;
            }
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

        if (historyChanged && mHistoryContent != null) rebuildHistoryViews(mHistoryContent, newText);

        Logger.logInfo(LOG_TAG, "[autocomplete] updatePopupContent historyChanged=" + historyChanged);
        mLastAppliedPrefix = newText;
        applyPopupGeometry(inputField);
    }

    // ── Per-window rebuild + bold spans ──

    private void rebuildHistoryViews(@NonNull LinearLayout content, @NonNull String input) {
        content.removeAllViews();
        final int n = displayCount();
        int rendered = 0;
        for (int i = 0; i < mData.getSuggestions().size() && rendered < n; i++, rendered++) {
            String suggestion = mData.getSuggestions().get(i);
            TextView tv = mData.buildSuggestionTextView(suggestion, input, false);
            content.addView(tv, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
        }
    }

    private void updateBoldSpansOnly(@NonNull String newText) {
        updateBoldSpansInWindow(mHistoryPopup, mHistoryContent, newText);
    }

    private void updateBoldSpansInWindow(@Nullable PopupWindow popup,
                                          @Nullable LinearLayout content,
                                          @NonNull String newText) {
        if (popup == null || !popup.isShowing() || content == null) return;
        final int n = displayCount();
        Logger.logInfo(LOG_TAG, "[autocomplete] updateBoldSpansOnly(history) views=" + content.getChildCount());
        int rendered = 0;
        int viewIdx = 0;
        for (int i = 0; i < mData.getSuggestions().size() && rendered < n; i++) {
            TextView tv = textViewAt(content, viewIdx++);
            if (tv == null) break;
            applyBoldSpan(tv, mData.getSuggestions().get(i), newText);
            rendered++;
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
        String expectedDisplay = (prefixLen > 0)
                ? prefix + suggestion.substring(ws)
                : suggestion;

        CharSequence currentText = tv.getText();
        if (!expectedDisplay.contentEquals(currentText)) {
            int availWidth = tv.getWidth() - tv.getPaddingLeft() - tv.getPaddingRight();
            android.text.SpannableString ss = AutoCompleteTextRenderer.buildSuggestionSpannable(
                    suggestion, newText, Math.max(0, availWidth), tv.getPaint(), false);
            tv.setText(ss, TextView.BufferType.SPANNABLE);
        } else {
            android.text.Spannable sp = (android.text.Spannable) currentText;
            android.text.style.StyleSpan[] old = sp.getSpans(0, sp.length(), android.text.style.StyleSpan.class);
            for (android.text.style.StyleSpan s : old) sp.removeSpan(s);
            if (hasLastWord && prefixLen + boldLen <= sp.length()
                    && suggestion.regionMatches(true, 0, newText, 0, inputLen)) {
                sp.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
                        prefixLen, prefixLen + boldLen,
                        android.text.SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            tv.invalidate();
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
        for (int i = 0; i < mData.getSuggestions().size() && rendered < n; i++) {
            TextView tv = textViewAt(content, viewIdx++);
            if (tv == null) break;
            String expectedText = mData.getSuggestions().get(i);
            if (!tv.getTag().equals(expectedText)) return true;
            rendered++;
        }
        return false;
    }

    // ── Dismiss ──

    private void dismissAutoCompleteSuggestions() {
        if (mHistoryPopup == null) {
            return;
        }
        Logger.logInfo(LOG_TAG, "[autocomplete] dismiss (history=" + (mHistoryPopup != null) + ")");
        if (mHistoryPopup != null) {
            try { mHistoryPopup.dismiss(); } catch (Exception ignored) {}
            mHistoryPopup = null;
        }
        mHistoryContent = null;
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
        // The controller owns the suggestion data; it clears its own lists and
        // resets the fetch state in onSuggestionDismissed().
        mData.onSuggestionDismissed();
    }
}

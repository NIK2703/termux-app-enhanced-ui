package com.termux.app.terminal.io.autocomplete;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.termux.R;
import com.termux.app.TermuxActivityUtils;
import com.termux.app.terminal.TermuxColorSchemeManager;

import java.util.ArrayList;

/**
 * Self-contained controller for the message (command) history popup that is
 * anchored above the pencil / toggle-text-input button.
 * <p>
 * This class is a pure extraction of the history-popup logic that previously
 * lived inside {@code TermuxActivity} ({@code showMessageHistoryPopup()},
 * {@code updateHistoryHighlight()}, {@code autoScrollHistoryNearEdge()},
 * {@code dismissMessageHistoryPopup()}, and the selection / clear-all handling).
 * It owns the {@link PopupWindow}, the per-item {@link TextView} list, the
 * highlight state and the edge auto-scroll loop, and builds the popup content
 * itself (newest-at-the-bottom ordering, the synthetic "CLEAR HISTORY…" and
 * "Clear" rows, rounded background, bounded height, open-at-bottom scroll).
 * <p>
 * Two concerns remain the activity's responsibility and are surfaced through
 * {@link MessageHistoryPopupCallback}: reading the current working directory
 * (for per-directory history sync) and mutating the text-input field when a
 * message is picked or the input is cleared. Everything else is handled here.
 */
public final class MessageHistoryPopupController {

    // Synthetic tag for the top "CLEAR HISTORY…" row.
    private static final int MESSAGE_HISTORY_CLEAR_ALL_TAG = -3;
    // Synthetic tag for the bottom "Clear" (clear input) row.
    private static final int MESSAGE_HISTORY_CLEAR_TAG = -2;
    private static final int MESSAGE_HISTORY_POPUP_MAX_HEIGHT_DP = 520;
    private static final int MESSAGE_HISTORY_POPUP_GAP_DP = 24;

    @NonNull private final Activity mActivity;
    @NonNull private final MessageHistoryController mMessageHistoryCtrl;
    @NonNull private final TermuxColorSchemeManager mColorSchemeManager;
    @NonNull private final MessageHistoryPopupCallback mCallback;

    @Nullable private PopupWindow mHistoryPopup;
    @NonNull private final ArrayList<TextView> mHistoryItemViews = new ArrayList<>();
    @Nullable private ScrollView mHistoryScroll;
    private float mHistoryFingerY = 0f;
    private boolean mHistoryAutoScrolling = false;
    private long mHistoryLastScrollTimeMs = 0;
    private int mHistoryHighlightIndex = -1;
    private boolean mHistoryEmptyHintShown = false;

    public MessageHistoryPopupController(@NonNull Activity activity,
                                         @NonNull MessageHistoryController messageHistoryCtrl,
                                         @NonNull TermuxColorSchemeManager colorSchemeManager,
                                         @NonNull MessageHistoryPopupCallback callback) {
        mActivity = activity;
        mMessageHistoryCtrl = messageHistoryCtrl;
        mColorSchemeManager = colorSchemeManager;
        mCallback = callback;
    }

    /**
     * Callbacks the controller needs from the surrounding activity. Input-field
     * mutation is intentionally kept in the activity because it depends on the
     * text-input panel, soft-keyboard and per-session text store.
     */
    public interface MessageHistoryPopupCallback {
        /** Current working directory, used as the per-directory history key. */
        @NonNull
        String getCurrentCwd();

        /** A history item was chosen — insert {@code message} into the input field. */
        void onMessagePicked(@NonNull String message);

        /** Bottom "Clear" row — push the current input into history, then empty the field. */
        void onClearInputRequested();
    }

    // ── Public API (mirrors the old TermuxActivity methods) ──

    /** Reset the one-shot empty-state hint guard (call on ACTION_DOWN). */
    public void onActionDown() {
        mHistoryEmptyHintShown = false;
    }

    /**
     * Build and show the message-history popup anchored above the pencil button.
     * Items are laid out newest-at-the-BOTTOM (nearest the button): the content is
     * filled top-to-bottom oldest-first, so index 0 (newest) ends up at the bottom.
     */
    public void show(@NonNull View anchor) {
        dismiss();
        mHistoryItemViews.clear();
        mHistoryHighlightIndex = -1;

        // Sync per-directory history if the current directory changed since
        // the last swap (e.g. after `cd` or a tab switch where the client
        // callback was missed). Without this, the popup would show the
        // previous directory's history until the user sends a message.
        if (mMessageHistoryCtrl.isPerDirectoryEnabled()) {
            String cwd = mCallback.getCurrentCwd();
            if (!cwd.equals(mMessageHistoryCtrl.getHistoryCurrentDirectory())) {
                String oldCwd = mMessageHistoryCtrl.getHistoryCurrentDirectory();
                mMessageHistoryCtrl.onHistoryDirectoryChanged(
                        oldCwd != null ? oldCwd : cwd, cwd);
            }
        }

        // Early-exit: no history and no typed text → show an empty-state hint.
        boolean hasHistory = !mMessageHistoryCtrl.getHistoryList().isEmpty();
        String currInputText = "";
        EditText inputFieldRO = mActivity.findViewById(R.id.terminal_toolbar_text_input);
        if (inputFieldRO != null) {
            CharSequence cs = inputFieldRO.getText();
            if (cs != null) currInputText = cs.toString();
        }
        boolean hasInput = !TextUtils.isEmpty(currInputText);
        if (!hasHistory && !hasInput) {
            // Show a one-shot Toast for the empty state. A guard flag prevents
            // re-triggering on every ACTION_MOVE pixel while the finger drags.
            if (!mHistoryEmptyHintShown) {
                mHistoryEmptyHintShown = true;
                Toast bottomToast = Toast.makeText(mActivity,
                        mActivity.getString(R.string.message_history_empty), Toast.LENGTH_SHORT);
                bottomToast.setGravity(Gravity.BOTTOM, 0, TermuxActivityUtils.dpToPx(mActivity, 48));
                bottomToast.show();
            }
            return;
        }

        LinearLayout content = new LinearLayout(mActivity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setBackgroundColor(Color.TRANSPARENT);

        int padH = TermuxActivityUtils.dpToPx(mActivity, 14);
        int padV = TermuxActivityUtils.dpToPx(mActivity, 10);

        // Synthetic "CLEAR HISTORY…" row pinned at the TOP of the popup.
        // Selecting it opens a confirmation dialog; confirming wipes all history.
        // Shown only when there is history to clear. Coexists with the bottom
        // "Clear" row (clears the input), it is not a replacement for it.
        if (!mMessageHistoryCtrl.getHistoryList().isEmpty()) {
            TextView tv = new TextView(mActivity);
            tv.setText(mActivity.getString(R.string.message_history_clear_all));
            tv.setGravity(Gravity.CENTER);
            tv.setAllCaps(true);
            tv.setTextColor(mColorSchemeManager.getHistoryTextColor());
            tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15);
            tv.setTypeface(tv.getTypeface(), android.graphics.Typeface.BOLD);
            tv.setPadding(padH, padV, padH, padV);
            tv.setClickable(true);
            tv.setTag(MESSAGE_HISTORY_CLEAR_ALL_TAG);
            content.addView(tv, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            mHistoryItemViews.add(tv);

            // Thin separator below the clear all row to visually group it.
            View sep = new View(mActivity);
            sep.setBackgroundColor(mColorSchemeManager.getHistoryPopupSepColor());
            content.addView(sep, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, TermuxActivityUtils.dpToPx(mActivity, 1)));
        }
        // Displayed order (spec): newest at the BOTTOM (nearest the pencil button,
        // first reached by a swipe-up), oldest at the top. A re-sent message moves
        // to index 0 (front) of mMessageHistoryCtrl.getHistoryList(), so iterate in REVERSE (end -> 0)
        // to fill the vertical layout top-to-bottom with the newest last (bottom).
        for (int i = mMessageHistoryCtrl.getHistoryList().size() - 1; i >= 0; i--) {
            final String message = mMessageHistoryCtrl.getHistoryList().get(i);
            TextView tv = new TextView(mActivity);
            // Preview: collapse newlines to spaces, wrap to at most 2 lines and add
            // an ellipsis when the message is longer than that.
            tv.setText(message.replace("\n", " ").trim());
            tv.setMaxLines(2);
            tv.setEllipsize(TextUtils.TruncateAt.END);
            tv.setTextColor(mColorSchemeManager.getHistoryTextColor());
            tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15);
            tv.setPadding(padH, padV, padH, padV);
            tv.setClickable(true);
            // Tag with the real history index so highlight/selection maps back.
            tv.setTag(i);
            content.addView(tv, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            mHistoryItemViews.add(tv);
        }

        // Synthetic "Clear" row pinned at the BOTTOM of the popup (nearest the
        // pencil button): remembers the current input text in history, then empties
        // the input field. Shown only when the input panel actually has text.
        final EditText inputField = mActivity.findViewById(R.id.terminal_toolbar_text_input);
        final String inputText = inputField != null ? inputField.getText().toString() : "";
        if (!TextUtils.isEmpty(inputText)) {
            TextView tv = new TextView(mActivity);
            tv.setText(mActivity.getString(R.string.message_history_clear));
            tv.setGravity(Gravity.CENTER);
            tv.setAllCaps(true);
            tv.setTextColor(mColorSchemeManager.getHistoryTextColor());
            tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15);
            tv.setTypeface(tv.getTypeface(), android.graphics.Typeface.BOLD);
            tv.setPadding(padH, padV, padH, padV);
            tv.setClickable(true);
            tv.setTag(MESSAGE_HISTORY_CLEAR_TAG);
            content.addView(tv, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            mHistoryItemViews.add(tv);

            // Thin separator above the bottom "Clear" row acts as a visual
            // divider between the history list and the action. Only meaningful
            // when there IS a history list to separate it from.
            if (!mMessageHistoryCtrl.getHistoryList().isEmpty()) {
                View sepBottom = new View(mActivity);
                sepBottom.setBackgroundColor(mColorSchemeManager.getHistoryPopupSepColor());
                content.addView(sepBottom, content.getChildCount() - 1,
                        new LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT, TermuxActivityUtils.dpToPx(mActivity, 1)));
            }
        }

        int popupWidth = Math.min(
                mActivity.getResources().getDisplayMetrics().widthPixels - TermuxActivityUtils.dpToPx(mActivity, 24),
                TermuxActivityUtils.dpToPx(mActivity, 320));

        // Wrap in a ScrollView: the popup is a bounded box (never edge-to-edge),
        // and a taller history scrolls inside it. Kept for edge auto-scroll while
        // the finger drags near the top/bottom of the box.
        ScrollView scroll = new ScrollView(mActivity);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(content, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        mHistoryScroll = scroll;
        // Clip children to the popup's rounded corners so highlights and
        // separators near the edges don't spill outside the rounded shape.
        // Guard against 0 dims during WRAP_CONTENT resize: if w or h is 0
        // the outline is left empty (no clipping) instead of clipping to nothing.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            scroll.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    int w = view.getWidth();
                    int h = view.getHeight();
                    if (w > 0 && h > 0) {
                        outline.setRoundRect(0, 0, w, h, TermuxActivityUtils.dpToPx(mActivity, 12));
                    }
                }
            });
            scroll.setClipToOutline(true);
        }

        mHistoryPopup = new PopupWindow(scroll, popupWidth,
                ViewGroup.LayoutParams.WRAP_CONTENT, false);
        // Smooth elevation shadow — background drawable must be fully opaque for the
        // WindowManager to derive a valid Outline (GradientDrawable.getOutline bails
        // when alpha < 255). The 10% visual transparency is applied to the ScrollView
        // itself via setAlpha(), which does not affect the popup's background outline.
        // Larger elevation (16dp) for a bigger shadow, but outline alpha is
        // reduced so the shadow renders more transparent/softer.
        mHistoryPopup.setElevation(TermuxActivityUtils.dpToPx(mActivity, 16));
        // Background: rounded rect, fully opaque scheme composite colour.
        // getOutline() is overridden to call outline.setAlpha() — this controls
        // the shadow opacity independently from the elevation size.
        GradientDrawable popupBgDrawable = new GradientDrawable() {
            @Override
            public void getOutline(@NonNull Outline outline) {
                super.getOutline(outline);
                if (!outline.isEmpty()) {
                    // Keep elevation large, but make the shadow softer/transparent.
                    // setAlpha requires API 31+.
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        outline.setAlpha(0.65f);
                    }
                }
            }
        };
        popupBgDrawable.setShape(GradientDrawable.RECTANGLE);
        popupBgDrawable.setCornerRadius(TermuxActivityUtils.dpToPx(mActivity, 12));
        popupBgDrawable.setColor(mColorSchemeManager.getHistoryPopupBg()); // must be opaque for getOutline
        mHistoryPopup.setBackgroundDrawable(popupBgDrawable);
        // 10% visual transparency on the content (not the background drawable, so the
        // elevation shadow outline stays valid).
        scroll.setAlpha(0.9f);
        mHistoryPopup.setClippingEnabled(true);
        // Do NOT let the popup intercept touches: the pencil button keeps the
        // gesture so we track the finger over items via raw coordinates.
        mHistoryPopup.setTouchable(false);
        mHistoryPopup.setFocusable(false);

        // Anchor above the button, right-aligned to it.
        mHistoryPopup.showAsDropDown(anchor, 0, 0, Gravity.START);
        // Reposition to sit ABOVE the anchor instead of below: measure content
        // then offset upward. showAsDropDown places below, so we shift up here.
        content.measure(
                View.MeasureSpec.makeMeasureSpec(popupWidth, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int contentHeight = content.getMeasuredHeight();
        // Bounded box: min(content, configured max, room above the button).
        int[] anchorLoc = new int[2];
        anchor.getLocationOnScreen(anchorLoc);
        // Gap between the button's top and the popup's bottom edge.
        int popupGap = TermuxActivityUtils.dpToPx(mActivity, MESSAGE_HISTORY_POPUP_GAP_DP);
        int roomAbove = Math.max(TermuxActivityUtils.dpToPx(mActivity, 48), anchorLoc[1] - TermuxActivityUtils.dpToPx(mActivity, 8) - popupGap);
        int maxHeight = Math.min(TermuxActivityUtils.dpToPx(mActivity, MESSAGE_HISTORY_POPUP_MAX_HEIGHT_DP), roomAbove);
        int popupHeight = Math.min(contentHeight, maxHeight);
        mHistoryPopup.update(anchor,
                0,
                -(anchor.getHeight() + popupHeight + popupGap),
                popupWidth,
                popupHeight);

        // Open at the END of the list: newest is at the bottom, so start scrolled
        // fully down so the newest messages (nearest the button) are visible.
        // jump straight to the bottom — fullScroll(FOCUS_DOWN) animates, which looks
        // like the list is scrolling past entries as the popup appears.
        final ScrollView scrollRef = scroll;
        scrollRef.post(() -> {
            View child = scrollRef.getChildAt(0);
            if (child != null) scrollRef.scrollTo(0, child.getHeight());
        });
    }

    /**
     * Whether the popup has anything worth showing: either there is saved
     * history, or the input panel currently holds some (unsent) text.
     */
    public boolean shouldShow() {
        return true; // always show – either history, or the "empty" hint
    }

    /** Highlight the history item currently under the finger (raw screen coords). */
    public void updateHighlight(float rawX, float rawY) {
        // Remember the finger position so the continuous edge auto-scroll keeps
        // running even when the finger rests (no ACTION_MOVE) on the edge band.
        mHistoryFingerY = rawY;
        autoScrollNearEdge();

        int newIndex = -1;
        int[] loc = new int[2];
        for (TextView tv : mHistoryItemViews) {
            tv.getLocationOnScreen(loc);
            if (rawX >= loc[0] && rawX <= loc[0] + tv.getWidth()
                    && rawY >= loc[1] && rawY <= loc[1] + tv.getHeight()) {
                Object tag = tv.getTag();
                if (tag instanceof Integer) newIndex = (Integer) tag;
                break;
            }
        }
        if (newIndex == mHistoryHighlightIndex) return;
        mHistoryHighlightIndex = newIndex;

        // Sharp (non-pulse) highlight: solid theme accent fill + contrast text on
        // the item under the finger, plain text otherwise.
        for (TextView tv : mHistoryItemViews) {
            Object tag = tv.getTag();
            boolean active = tag instanceof Integer && (Integer) tag == mHistoryHighlightIndex;
            if (active) {
                tv.setBackgroundColor(mColorSchemeManager.getHistoryHighlightFill());
            } else {
                tv.setBackgroundColor(Color.TRANSPARENT);
            }
            tv.setTextColor(mColorSchemeManager.getHistoryTextColor());   // default colour in both states
        }
    }

    /**
     * Handle the finger release over the popup. Dismisses and, on a real
     * ACTION_UP, dispatches the selection (clear-all dialog / clear-input /
     * message pick) to the activity via the callback.
     */
    public void onRelease(@NonNull MotionEvent event) {
        int selected = mHistoryHighlightIndex;
        dismiss();
        if (event.getActionMasked() == MotionEvent.ACTION_UP) {
            if (selected == MESSAGE_HISTORY_CLEAR_ALL_TAG) {
                confirmClearAllHistory();
            } else if (selected == MESSAGE_HISTORY_CLEAR_TAG) {
                mCallback.onClearInputRequested();
            } else if (selected >= 0 && selected < mMessageHistoryCtrl.getHistoryList().size()) {
                final String picked = mMessageHistoryCtrl.getHistoryList().get(selected);
                // On a tap-selection, promote the item to the front of history (index 0 =
                // newest), so in the "newest at bottom" rendering it shows at the very
                // bottom of the list on the next open.
                mMessageHistoryCtrl.addToMessageHistory(picked, mCallback.getCurrentCwd());
                mCallback.onMessagePicked(picked);
            }
        }
    }

    /**
     * Continuously scroll the popup's ScrollView while the finger rests/drags
     * within an edge band at the top or bottom (like the keyboard-accent popup:
     * it keeps moving even without finger motion). Driven by a self-rescheduling
     * postDelayed loop that keeps running as long as the finger stays in the band
     * and the popup is open, so edge auto-scroll continues even when the finger
     * stops moving.
     *
     * NOTE: the loop reschedules ITSELF on every tick (not just when first
     * started) — otherwise it would stop after a single step because the
     * mHistoryAutoScrolling flag is already true on the next tick.
     */
    private void autoScrollNearEdge() {
        if (mHistoryScroll == null || !isShowing()) {
            mHistoryAutoScrolling = false;
            return;
        }
        int[] loc = new int[2];
        mHistoryScroll.getLocationOnScreen(loc);
        int top = loc[1];
        int bottom = loc[1] + mHistoryScroll.getHeight();
        int band = TermuxActivityUtils.dpToPx(mActivity, 36);      // edge-sensitive zone
        int maxStep = TermuxActivityUtils.dpToPx(mActivity, 24);   // max px scrolled per 16ms reference interval

        // Time-based step: scale by actual frame time so scroll speed is
        // consistent across 60/90/120 Hz displays (Choreographer / postOnAnimation).
        long now = SystemClock.uptimeMillis();
        float frameRatio;
        if (mHistoryLastScrollTimeMs == 0) {
            frameRatio = 1f;
        } else {
            long dt = Math.min(now - mHistoryLastScrollTimeMs, 48L);
            frameRatio = dt / 16f;
        }
        mHistoryLastScrollTimeMs = now;

        float rawY = mHistoryFingerY;
        int step;
        if (rawY < top + band) {
            float t = Math.min(1f, (top + band - rawY) / band);
            step = -Math.round(maxStep * t * frameRatio);
        } else if (rawY > bottom - band) {
            float t = Math.min(1f, (rawY - (bottom - band)) / band);
            step = Math.round(maxStep * t * frameRatio);
        } else {
            mHistoryAutoScrolling = false;
            return;
        }

        mHistoryScroll.scrollBy(0, step);

        // Keep the loop alive; reschedule on next vsync for smooth scrolling.
        mHistoryAutoScrolling = true;
        mHistoryScroll.postOnAnimation(this::autoScrollNearEdge);
    }

    /**
     * Ask the user to confirm wiping the message history. In per-directory mode
     * the dialog has three buttons: OK (current directory only), All (all
     * directories), Cancel. In global mode it stays as OK + Cancel.
     */
    private void confirmClearAllHistory() {
        final MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(mActivity)
                .setTitle(mActivity.getString(R.string.message_history_clear_question))
                .setNegativeButton(android.R.string.cancel, null);

        if (mMessageHistoryCtrl.isPerDirectoryEnabled()) {
            builder.setMessage(mActivity.getString(R.string.message_history_clear_current_only_question))
                    .setPositiveButton(mActivity.getString(R.string.message_history_clear_ok), (d, w) -> clearAllHistory())
                    .setNeutralButton(mActivity.getString(R.string.message_history_clear_all_btn), (d, w) -> clearAllDirectoriesHistory());
        } else {
            builder.setMessage(mActivity.getString(R.string.message_history_clear_all_question))
                    .setPositiveButton(android.R.string.ok, (d, w) -> clearAllHistory());
        }

        androidx.appcompat.app.AlertDialog dialog = builder.create();
        dialog.show();
    }

    /** Wipe message history for ALL directories (per-directory mode only). */
    private void clearAllDirectoriesHistory() {
        mMessageHistoryCtrl.clearAllPerDirectory();
    }

    /** Wipe the message history for the current context (global or current directory). */
    private void clearAllHistory() {
        mMessageHistoryCtrl.clearCurrent(mCallback.getCurrentCwd());
    }

    /** Dismiss the history popup and reset highlight state. */
    public void dismiss() {
        if (mHistoryPopup != null) {
            try { mHistoryPopup.dismiss(); } catch (Exception ignored) {}
            mHistoryPopup = null;
        }
        mHistoryItemViews.clear();
        mHistoryScroll = null;
        mHistoryAutoScrolling = false;   // stop any pending edge-scroll loop
        mHistoryHighlightIndex = -1;
    }

    /** Whether the popup is currently showing. */
    public boolean isShowing() {
        return mHistoryPopup != null && mHistoryPopup.isShowing();
    }
}

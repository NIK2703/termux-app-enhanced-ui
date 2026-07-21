package com.termux.shared.termux.extrakeys;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.ColorStateList;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.AttributeSet;
import android.util.TypedValue;

import android.text.TextPaint;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ScheduledExecutorService;

import java.util.Map;
import java.util.HashMap;
import java.util.stream.Collectors;

import android.view.HapticFeedbackConstants;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.GridLayout;
import android.widget.PopupWindow;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.google.android.material.button.MaterialButton;
import com.termux.shared.R;
import com.termux.shared.termux.settings.properties.TermuxAppSharedProperties;
import com.termux.shared.termux.settings.properties.TermuxPropertyConstants;
import com.termux.shared.termux.terminal.io.TerminalExtraKeys;
import com.termux.shared.theme.ThemeUtils;

/**
 * A {@link View} showing extra keys (such as Escape, Ctrl, Alt) not normally available on an Android soft
 * keyboards.
 *
 * To use it, add following to a layout file and import it in your activity layout file or inflate
 * it with a {@link androidx.viewpager.widget.ViewPager}.:
 * {@code
 * <?xml version="1.0" encoding="utf-8"?>
 * <com.termux.shared.termux.extrakeys.ExtraKeysView xmlns:android="http://schemas.android.com/apk/res/android"
 *     android:id="@+id/extra_keys"
 *     style="?android:attr/buttonBarStyle"
 *     android:layout_width="match_parent"
 *     android:layout_height="match_parent"
 *     android:layout_alignParentBottom="true"
 *     android:orientation="horizontal" />
 * }
 *
 * Then in your activity, get its reference by a call to {@link android.app.Activity#findViewById(int)}
 * or {@link LayoutInflater#inflate(int, ViewGroup)} if using {@link androidx.viewpager.widget.ViewPager}.
 * Then call {@link #setExtraKeysViewClient(IExtraKeysView)} and pass it the implementation of
 * {@link IExtraKeysView} so that you can receive callbacks. You can also override other values set
 * in {@link ExtraKeysView#ExtraKeysView(Context, AttributeSet)} by calling the respective functions.
 * If you extend {@link ExtraKeysView}, you can also set them in the constructor, but do call super().
 *
 * After this you will have to make a call to {@link ExtraKeysView#reload(ExtraKeysInfo, float) and pass
 * it the {@link ExtraKeysInfo} to load and display the extra keys. Read its class javadocs for more
 * info on how to create it.
 *
 * Termux app defines the view in res/layout/view_terminal_toolbar_extra_keys and
 * inflates it in TerminalToolbarViewPager.instantiateItem() and sets the {@link ExtraKeysView} client
 * and calls {@link ExtraKeysView#reload(ExtraKeysInfo).
 * The {@link ExtraKeysInfo} is created by TermuxAppSharedProperties.setExtraKeys().
 * Then its got and the view height is adjusted in TermuxActivity.setTerminalToolbarHeight().
 * The client used is TermuxTerminalExtraKeys, which extends
 * {@link TerminalExtraKeys } to handle Termux app specific logic and
 * leave the rest to the super class.
 */
public final class ExtraKeysView extends GridLayout {

    /** Direction for swipe gestures detected in editor mode. */
    public enum SwipeDirection {
        UP, DOWN, LEFT, RIGHT
    }

    /** Listener for editor-mode gestures on the extra keys view. */
    public interface EditorGestureListener {
        /**
         * Called when a button is tapped (short press without significant movement).
         * @param button The button view that was tapped.
         * @param row Row index of the button.
         * @param col Column index of the button.
         */
        void onKeyTap(View button, int row, int col);

        /**
         * Called when a swipe gesture is detected on a button.
         * @param button The button view that was swiped.
         * @param row Row index of the button.
         * @param col Column index of the button.
         * @param direction The direction of the swipe.
         */
        void onKeySwipe(View button, int row, int col, SwipeDirection direction);
    }

    /** The client for the {@link ExtraKeysView}. */
    public interface IExtraKeysView {

        /**
         * This is called by {@link ExtraKeysView} when a button is clicked. This is also called
         * for {@link #mRepetitiveKeys} and {@link ExtraKeyButton} that have a popup set.
         * However, this is not called for {@link #mSpecialButtons}, whose state can instead be read
         * via a call to {@link #readSpecialButton(SpecialButton, boolean)}.
         *
         * @param view The view that was clicked.
         * @param buttonInfo The {@link ExtraKeyButton} for the button that was clicked.
         *                   The button may be a {@link ExtraKeyButton#KEY_MACRO} set which can be
         *                   checked with a call to {@link ExtraKeyButton#isMacro()}.
         * @param button The {@link MaterialButton} that was clicked.
         */
        void onExtraKeyButtonClick(View view, ExtraKeyButton buttonInfo, MaterialButton button);

        /**
         * This is called by {@link ExtraKeysView} when a button is clicked so that the client
         * can perform any hepatic feedback. This is only called in the {@link MaterialButton.OnClickListener}
         * and not for every repeat. Its also called for {@link #mSpecialButtons}.
         *
         * @param view The view that was clicked.
         * @param buttonInfo The {@link ExtraKeyButton} for the button that was clicked.
         * @param button The {@link MaterialButton} that was clicked.
         * @return Return {@code true} if the client handled the feedback, otherwise {@code false}
         * so that {@link ExtraKeysView#performExtraKeyButtonHapticFeedback(View, ExtraKeyButton, MaterialButton)}
         * can handle it depending on system settings.
         */
        boolean performExtraKeyButtonHapticFeedback(View view, ExtraKeyButton buttonInfo, MaterialButton button);

    }


    /** Defines the default value for {@link #mButtonTextColor} defined by current theme. */
    public static final int ATTR_BUTTON_TEXT_COLOR = R.attr.extraKeysButtonTextColor;
    /** Defines the default value for {@link #mButtonActiveTextColor} defined by current theme. */
    public static final int ATTR_BUTTON_ACTIVE_TEXT_COLOR = R.attr.extraKeysButtonActiveTextColor;
    /** Defines the default value for {@link #mButtonBackgroundColor} defined by current theme. */
    public static final int ATTR_BUTTON_BACKGROUND_COLOR = R.attr.extraKeysButtonBackgroundColor;
    /** Defines the default value for {@link #mButtonActiveBackgroundColor} defined by current theme. */
    public static final int ATTR_BUTTON_ACTIVE_BACKGROUND_COLOR = R.attr.extraKeysButtonActiveBackgroundColor;

    /** Defines the default fallback value for {@link #mButtonTextColor} if {@link #ATTR_BUTTON_TEXT_COLOR} is undefined. */
    public static final int DEFAULT_BUTTON_TEXT_COLOR = 0xFFFFFFFF;
    /** Defines the default fallback value for {@link #mButtonActiveTextColor} if {@link #ATTR_BUTTON_ACTIVE_TEXT_COLOR} is undefined. */
    public static final int DEFAULT_BUTTON_ACTIVE_TEXT_COLOR = 0xFF80DEEA;
    /** Defines the default fallback value for {@link #mButtonBackgroundColor} if {@link #ATTR_BUTTON_BACKGROUND_COLOR} is undefined. */
    public static final int DEFAULT_BUTTON_BACKGROUND_COLOR = 0xFF1A1A1A;
    /** Defines the default fallback value for {@link #mButtonActiveBackgroundColor} if {@link #ATTR_BUTTON_ACTIVE_BACKGROUND_COLOR} is undefined. */
    public static final int DEFAULT_BUTTON_ACTIVE_BACKGROUND_COLOR = 0xFF424242;

    /** Button horizontal margin in dp (distance between buttons will be 2x margin). */
    public static final int BUTTON_MARGIN_HORIZONTAL_DP = 2;
    /** Button vertical margin in dp (distance between buttons will be 2x margin). */
    public static final int BUTTON_MARGIN_VERTICAL_DP = 2;
    /** Default button corner radius in dp. */
    public static final int BUTTON_CORNER_RADIUS_DP = 12;

    /** Current button corner radius in dp, loaded from preferences. */
    private int mButtonCornerRadiusDp = BUTTON_CORNER_RADIUS_DP;



    /** Defines the minimum allowed duration in milliseconds for {@link #mLongPressTimeout}. */
    public static final int MIN_LONG_PRESS_DURATION = 200;
    /** Defines the maximum allowed duration in milliseconds for {@link #mLongPressTimeout}. */
    public static final int MAX_LONG_PRESS_DURATION = 3000;
    /** Defines the fallback duration in milliseconds for {@link #mLongPressTimeout}. */
    public static final int FALLBACK_LONG_PRESS_DURATION = 400;

    /** Defines the minimum allowed duration in milliseconds for {@link #mLongPressRepeatDelay}. */
    public static final int MIN_LONG_PRESS__REPEAT_DELAY = 5;
    /** Defines the maximum allowed duration in milliseconds for {@link #mLongPressRepeatDelay}. */
    public static final int MAX_LONG_PRESS__REPEAT_DELAY = 2000;
    /** Defines the default duration in milliseconds for {@link #mLongPressRepeatDelay}. */
    public static final int DEFAULT_LONG_PRESS_REPEAT_DELAY = 80;



    /** The implementation of the {@link IExtraKeysView} that acts as a client for the {@link ExtraKeysView}. */
    protected IExtraKeysView mExtraKeysViewClient;

    /** The map for the {@link SpecialButton} and their {@link SpecialButtonState}. Defaults to
     * the one returned by {@link #getDefaultSpecialButtons(ExtraKeysView)}. */
    protected Map<SpecialButton, SpecialButtonState> mSpecialButtons;

    /** The keys for the {@link SpecialButton} added to {@link #mSpecialButtons}. This is automatically
     * set when the call to {@link #setSpecialButtons(Map)} is made. */
    protected Set<String> mSpecialButtonsKeys;


    /**
     * The list of keys for which auto repeat of key should be triggered if its extra keys button
     * is long pressed. This is done by calling {@link IExtraKeysView#onExtraKeyButtonClick(View, ExtraKeyButton, MaterialButton)}
     * every {@link #mLongPressRepeatDelay} seconds after {@link #mLongPressTimeout} has passed.
     * The default keys are defined by {@link ExtraKeysConstants#PRIMARY_REPETITIVE_KEYS}.
     */
    protected List<String> mRepetitiveKeys;


    /** The text color for the extra keys button. Defaults to {@link #DEFAULT_BUTTON_TEXT_COLOR}. */
    protected int mButtonTextColor;
    /** The text color for the extra keys button when its active.
     * Defaults to {@link #DEFAULT_BUTTON_ACTIVE_TEXT_COLOR}. */
    protected int mButtonActiveTextColor;
    /** The background color for the extra keys button. Defaults to {@link #DEFAULT_BUTTON_BACKGROUND_COLOR}. */
    protected int mButtonBackgroundColor;
    /** The background color for the extra keys button when its active. Defaults to
     * {@link #DEFAULT_BUTTON_ACTIVE_BACKGROUND_COLOR}. */
    protected int mButtonActiveBackgroundColor;

    /** Defines whether text for the extra keys button should be all capitalized automatically. */
    protected boolean mButtonTextAllCaps = true;


    /**
     * Defines the duration in milliseconds before a press turns into a long press. The default
     * duration used is the one returned by a call to {@link ViewConfiguration#getLongPressTimeout()}
     * which will return the system defined duration which can be changed in accessibility settings.
     * The duration must be in between {@link #MIN_LONG_PRESS_DURATION} and {@link #MAX_LONG_PRESS_DURATION},
     * otherwise {@link #FALLBACK_LONG_PRESS_DURATION} is used.
     */
    protected int mLongPressTimeout;

    /**
     * Defines the duration in milliseconds for the delay between trigger of each repeat of
     * {@link #mRepetitiveKeys}. The default value is defined by {@link #DEFAULT_LONG_PRESS_REPEAT_DELAY}.
     * The duration must be in between {@link #MIN_LONG_PRESS__REPEAT_DELAY} and
     * {@link #MAX_LONG_PRESS__REPEAT_DELAY}, otherwise {@link #DEFAULT_LONG_PRESS_REPEAT_DELAY} is used.
     */
    protected int mLongPressRepeatDelay;


    /**
     * Defines how the {@link #mSpecialButtons} behave when pressed.
     * {@link SpecialButtonMode#STICKY} (default): a tap toggles the button on/off (latching), and a
     * long hold locks it on until toggled off.
     * {@link SpecialButtonMode#HOLD}: a touch activates the button immediately and it stays active
     * only while the finger is held down, deactivating on release. No long-press competition since
     * the hold engages as soon as the button is touched.
     */
    protected SpecialButtonMode mSpecialButtonMode = SpecialButtonMode.STICKY;

    /** The behaviour mode for the {@link #mSpecialButtons}. */
    public enum SpecialButtonMode {
        /** Tap toggles the button on/off; long hold locks it on. */
        STICKY,
        /** Button is active only while touched, deactivating on release. */
        HOLD
    }


    /** The popup window shown if {@link ExtraKeyButton#getPopup()} returns a {@code non-null} value
     * and a swipe up action is done on an extra key. */
    protected PopupWindow mPopupWindow;

    /** Editor gesture listener. When non-null, the view is in editor mode. */
    @Nullable
    private EditorGestureListener mEditorListener;

    /** Pointer state for editor gesture tracking. */
    private static final int INVALID_POINTER_ID = -1;
    private int mActivePointerId = INVALID_POINTER_ID;
    private float mDownX;
    private float mDownY;
    private boolean mGestureConsumed;
    @Nullable private View mActiveChild;
    private int mSwipeThreshold; // pixels

    /** Runtime swipe detection: X coordinate of finger down. */
    private float mTouchDownX;
    /** Runtime swipe detection: Y coordinate of finger down. */
    private float mTouchDownY;
    /** Runtime swipe detection: non-null when a swipe has been detected during the current touch sequence. */
    @Nullable
    private SwipeDirection mRuntimeSwipeDirection;

    protected ScheduledExecutorService mScheduledExecutor;
    protected Handler mHandler;
    protected SpecialButtonsLongHoldRunnable mSpecialButtonsLongHoldRunnable;
    protected int mLongPressCount;


    public ExtraKeysView(Context context, AttributeSet attrs) {
        super(context, attrs);

        setRepetitiveKeys(ExtraKeysConstants.PRIMARY_REPETITIVE_KEYS);
        setSpecialButtons(getDefaultSpecialButtons(this));

        setButtonColors(
            ThemeUtils.getSystemAttrColor(context, ATTR_BUTTON_TEXT_COLOR, DEFAULT_BUTTON_TEXT_COLOR),
            ThemeUtils.getSystemAttrColor(context, ATTR_BUTTON_ACTIVE_TEXT_COLOR, DEFAULT_BUTTON_ACTIVE_TEXT_COLOR),
            ThemeUtils.getSystemAttrColor(context, ATTR_BUTTON_BACKGROUND_COLOR, DEFAULT_BUTTON_BACKGROUND_COLOR),
            ThemeUtils.getSystemAttrColor(context, ATTR_BUTTON_ACTIVE_BACKGROUND_COLOR, DEFAULT_BUTTON_ACTIVE_BACKGROUND_COLOR));

        setLongPressTimeout(ViewConfiguration.getLongPressTimeout());
        setLongPressRepeatDelay(DEFAULT_LONG_PRESS_REPEAT_DELAY);

        ViewConfiguration vc = ViewConfiguration.get(context);
        int touchSlop = vc.getScaledTouchSlop();
        int minThresholdDp = 16;
        int minThresholdPx = (int) (minThresholdDp * getResources().getDisplayMetrics().density);
        mSwipeThreshold = Math.max(touchSlop, minThresholdPx);
    }


    /** Get {@link #mExtraKeysViewClient}. */
    public IExtraKeysView getExtraKeysViewClient() {
        return mExtraKeysViewClient;
    }

    /** Set {@link #mExtraKeysViewClient}. */
    public void setExtraKeysViewClient(IExtraKeysView extraKeysViewClient) {
        mExtraKeysViewClient = extraKeysViewClient;
    }


    /** Get {@link #mRepetitiveKeys}. */
    public List<String> getRepetitiveKeys() {
        if (mRepetitiveKeys == null) return null;
        return mRepetitiveKeys.stream().map(String::new).collect(Collectors.toList());
    }

    /** Set {@link #mRepetitiveKeys}. Must not be {@code null}. */
    public void setRepetitiveKeys(@NonNull List<String> repetitiveKeys) {
        mRepetitiveKeys = repetitiveKeys;
    }


    /** Get {@link #mSpecialButtons}. */
    public Map<SpecialButton, SpecialButtonState> getSpecialButtons() {
        if (mSpecialButtons == null) return null;
        return mSpecialButtons.entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    /** Get {@link #mSpecialButtonsKeys}. */
    public Set<String> getSpecialButtonsKeys() {
        if (mSpecialButtonsKeys == null) return null;
        return mSpecialButtonsKeys.stream().map(String::new).collect(Collectors.toSet());
    }

    /** Set {@link #mSpecialButtonsKeys}. Must not be {@code null}. */
    public void setSpecialButtons(@NonNull Map<SpecialButton, SpecialButtonState> specialButtons) {
        mSpecialButtons = specialButtons;
        mSpecialButtonsKeys = this.mSpecialButtons.keySet().stream().map(SpecialButton::getKey).collect(Collectors.toSet());
    }


    /**
     * Set the {@link ExtraKeysView} button colors.
     *
     * @param buttonTextColor The value for {@link #mButtonTextColor}.
     * @param buttonActiveTextColor The value for {@link #mButtonActiveTextColor}.
     * @param buttonBackgroundColor The value for {@link #mButtonBackgroundColor}.
     * @param buttonActiveBackgroundColor The value for {@link #mButtonActiveBackgroundColor}.
     */
    public void setButtonColors(int buttonTextColor, int buttonActiveTextColor, int buttonBackgroundColor, int buttonActiveBackgroundColor) {
        mButtonTextColor = buttonTextColor;
        mButtonActiveTextColor = buttonActiveTextColor;
        mButtonBackgroundColor = buttonBackgroundColor;
        mButtonActiveBackgroundColor = buttonActiveBackgroundColor;
        // Re-tint any buttons already laid out (e.g. when the color scheme changes at runtime,
        // after reload() has built the buttons). New buttons created by a later reload() read
        // these fields directly.
        applyColorsToExistingButtons();
    }

    /** Re-apply the current button colors to every child view already added by {@link #reload(ExtraKeysInfo, float)}. */
    private void applyColorsToExistingButtons() {
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (!(child instanceof MaterialButton)) continue;
            MaterialButton button = (MaterialButton) child;
            button.setTextColor(mButtonTextColor);
            button.setBackgroundTintList(ColorStateList.valueOf(mButtonBackgroundColor));
        }
        // Keep tinted special buttons consistent with their current active state.
        for (SpecialButtonState state : mSpecialButtons.values()) {
            for (MaterialButton button : state.buttons) {
                button.setTextColor(state.isActive ? mButtonActiveTextColor : mButtonTextColor);
                button.setBackgroundTintList(ColorStateList.valueOf(
                        state.isActive ? mButtonActiveBackgroundColor : mButtonBackgroundColor));
            }
        }
    }


    /** Get {@link #mButtonTextColor}. */
    public int getButtonTextColor() {
        return mButtonTextColor;
    }

    /** Set {@link #mButtonTextColor}. */
    public void setButtonTextColor(int buttonTextColor) {
        mButtonTextColor = buttonTextColor;
    }


    /** Get {@link #mButtonActiveTextColor}. */
    public int getButtonActiveTextColor() {
        return mButtonActiveTextColor;
    }

    /** Set {@link #mButtonActiveTextColor}. */
    public void setButtonActiveTextColor(int buttonActiveTextColor) {
        mButtonActiveTextColor = buttonActiveTextColor;
    }


    /** Get {@link #mButtonBackgroundColor}. */
    public int getButtonBackgroundColor() {
        return mButtonBackgroundColor;
    }

    /** Set {@link #mButtonBackgroundColor}. */
    public void setButtonBackgroundColor(int buttonBackgroundColor) {
        mButtonBackgroundColor = buttonBackgroundColor;
    }


    /** Get {@link #mButtonActiveBackgroundColor}. */
    public int getButtonActiveBackgroundColor() {
        return mButtonActiveBackgroundColor;
    }

    /** Set {@link #mButtonActiveBackgroundColor}. */
    public void setButtonActiveBackgroundColor(int buttonActiveBackgroundColor) {
        mButtonActiveBackgroundColor = buttonActiveBackgroundColor;
    }

    /** Set {@link #mButtonTextAllCaps}. */
    public void setButtonTextAllCaps(boolean buttonTextAllCaps) {
        mButtonTextAllCaps = buttonTextAllCaps;
    }


    /** Get {@link #mLongPressTimeout}. */
    public int getLongPressTimeout() {
        return mLongPressTimeout;
    }

    /** Set {@link #mLongPressTimeout}. */
    public void setLongPressTimeout(int longPressDuration) {
        if (longPressDuration >= MIN_LONG_PRESS_DURATION && longPressDuration <= MAX_LONG_PRESS_DURATION) {
            mLongPressTimeout = longPressDuration;
        } else {
            mLongPressTimeout = FALLBACK_LONG_PRESS_DURATION;
        }
    }

    /** Get {@link #mLongPressRepeatDelay}. */
    public int getLongPressRepeatDelay() {
        return mLongPressRepeatDelay;
    }

    /** Set {@link #mLongPressRepeatDelay}. */
    public void setLongPressRepeatDelay(int longPressRepeatDelay) {
        if (mLongPressRepeatDelay >= MIN_LONG_PRESS__REPEAT_DELAY && mLongPressRepeatDelay <= MAX_LONG_PRESS__REPEAT_DELAY) {
            mLongPressRepeatDelay = longPressRepeatDelay;
        } else {
            mLongPressRepeatDelay = DEFAULT_LONG_PRESS_REPEAT_DELAY;
        }
    }


    /** Get {@link #mSpecialButtonMode}. */
    @NonNull
    public SpecialButtonMode getSpecialButtonMode() {
        return mSpecialButtonMode;
    }

    /** Set {@link #mSpecialButtonMode}. Must not be {@code null}. */
    public void setSpecialButtonMode(@NonNull SpecialButtonMode specialButtonMode) {
        mSpecialButtonMode = specialButtonMode;
    }


    /** Get the default map that can be used for {@link #mSpecialButtons}. */
    @NonNull
    public Map<SpecialButton, SpecialButtonState> getDefaultSpecialButtons(ExtraKeysView extraKeysView) {
        return new HashMap<SpecialButton, SpecialButtonState>() {{
            put(SpecialButton.CTRL, new SpecialButtonState(extraKeysView));
            put(SpecialButton.ALT, new SpecialButtonState(extraKeysView));
            put(SpecialButton.SHIFT, new SpecialButtonState(extraKeysView));
            put(SpecialButton.FN, new SpecialButtonState(extraKeysView));
        }};
    }



    /**
     * Reload this instance of {@link ExtraKeysView} with the info passed in {@code extraKeysInfo}.
     *
     * @param extraKeysInfo The {@link ExtraKeysInfo} that defines the necessary info for the extra keys.
     * @param heightPx The height in pixels of the parent surrounding the {@link ExtraKeysView}. It must
     *                 be a single child.
     */
    @SuppressLint("ClickableViewAccessibility")
    public void reload(ExtraKeysInfo extraKeysInfo, float heightPx) {
        if (extraKeysInfo == null)
            return;

        for(SpecialButtonState state : mSpecialButtons.values())
            state.buttons = new ArrayList<>();

        removeAllViews();

        // Load corner radius from preferences
        TermuxAppSharedProperties props = TermuxAppSharedProperties.getProperties();
        mButtonCornerRadiusDp = props != null ? props.getExtraKeysCornerRadius() : BUTTON_CORNER_RADIUS_DP;

        ExtraKeyButton[][] buttons = extraKeysInfo.getMatrix();

        setRowCount(buttons.length);
        setColumnCount(maximumLength(buttons));

        for (int row = 0; row < buttons.length; row++) {
            for (int col = 0; col < buttons[row].length; col++) {
                final ExtraKeyButton buttonInfo = buttons[row][col];

                MaterialButton button;
                if (isSpecialButton(buttonInfo)) {
                    button = createSpecialButton(buttonInfo.getKey(), true);
                    if (button == null) return;
                } else {
                    button = new MaterialButton(getContext(), null, android.R.attr.buttonBarButtonStyle);
                }

                button.setText(buttonInfo.getDisplay());
                // Stage 1: choose font size (14sp or 12sp) based on single-line width
                // Stage 2: compute maxLines from button height, enable ellipsis
                CharSequence displayText = button.getText();
                if (button.getTransformationMethod() != null) {
                    CharSequence transformed = button.getTransformationMethod().getTransformation(displayText, button);
                    if (transformed != null) displayText = transformed;
                }
                TextPaint paint = new TextPaint(button.getPaint());
                float displayDensity = getResources().getDisplayMetrics().density;

                // Subtract margins from total cell height to get actual button content height
                int vMarginPx = (int) (BUTTON_MARGIN_VERTICAL_DP * displayDensity);
                int textAreaH = (int) (heightPx + 0.5f) - 2 * vMarginPx
                    - button.getCompoundPaddingTop() - button.getCompoundPaddingBottom();
                // Cell width from layout: totalViewWidth / columnCount
                int totalCols = getColumnCount();
                int textAreaW = totalCols > 0 && getWidth() > 0
                    ? (getWidth() / totalCols) - button.getCompoundPaddingStart() - button.getCompoundPaddingEnd()
                    : Integer.MAX_VALUE;

                // Try 14sp first
                float normalPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 14f, getResources().getDisplayMetrics());
                float smallPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 12f, getResources().getDisplayMetrics());
                paint.setTextSize(normalPx);
                boolean tooWide = textAreaW > 0 && (float) Math.ceil(paint.measureText(displayText.toString())) > textAreaW;
                float chosenSizePx = tooWide ? smallPx : normalPx;

                // Apply chosen size
                button.setTextSize(TypedValue.COMPLEX_UNIT_PX, chosenSizePx);

                // Compute maxLines from available height
                paint.setTextSize(chosenSizePx);
                int lineHeight = paint.getFontMetricsInt(null);
                // Account for button's line spacing
                float lineSpacing = button.getLineSpacingMultiplier();
                if (lineSpacing > 0f) {
                    lineHeight = (int) (lineHeight * lineSpacing);
                }
                lineHeight += (int) button.getLineSpacingExtra();
                int maxLines = Math.max(1, textAreaH / Math.max(1, lineHeight));

                button.setMaxLines(maxLines);
                button.setEllipsize(TextUtils.TruncateAt.END);
                button.setSingleLine(false);
                button.setHorizontallyScrolling(false);
                button.setTextColor(mButtonTextColor);
                button.setBackgroundTintList(ColorStateList.valueOf(mButtonBackgroundColor));
                button.setCornerRadius((int) (mButtonCornerRadiusDp * getResources().getDisplayMetrics().density));
                button.setAllCaps(mButtonTextAllCaps);
                button.setPadding(0, 0, 0, 0);
                button.setMinHeight(0);
                button.setMinimumHeight(0);
                // Remove MaterialButton built-in insets and font padding
                button.setIncludeFontPadding(false);
                button.setInsetTop(0);
                button.setInsetBottom(0);

                button.setOnClickListener(view -> {
                    performExtraKeyButtonHapticFeedback(view, buttonInfo, button, false);
                    onAnyExtraKeyButtonClick(view, buttonInfo, button);
                });

                button.setOnTouchListener((view, event) -> {
                    switch (event.getAction()) {
                        case MotionEvent.ACTION_DOWN:
                            // Save touch start position for swipe detection
                            mTouchDownX = event.getX();
                            mTouchDownY = event.getY();
                            mRuntimeSwipeDirection = null;

                            button.setBackgroundTintList(ColorStateList.valueOf(mButtonActiveBackgroundColor));

                            // In HOLD mode a special button activates immediately on touch and stays
                            // active only while held. There is no long-press competition, so we do not
                            // start any scheduled executors and just engage the hold.
                            if (mSpecialButtonMode == SpecialButtonMode.HOLD && isSpecialButton(buttonInfo)) {
                                SpecialButtonState holdState = mSpecialButtons.get(SpecialButton.valueOf(buttonInfo.getKey()));
                                if (holdState != null) {
                                    holdState.setIsActive(true);
                                    holdState.setIsHolding(true);
                                }
                                return true;
                            }
                            // Start long press scheduled executors which will be stopped in next MotionEvent
                            startScheduledExecutors(view, buttonInfo, button);
                            return true;

                        case MotionEvent.ACTION_MOVE:
                            // If a swipe was already detected, no further processing needed
                            if (mRuntimeSwipeDirection != null) return true;

                            // Check for 4-direction swipe: compute displacement from touch down
                            float dx = event.getX() - mTouchDownX;
                            float dy = event.getY() - mTouchDownY;
                            SwipeDirection swipeDir = detectDirection(dx, dy);
                            if (swipeDir != null && getSwipeExtraKeyButton(buttonInfo, swipeDir) != null) {
                                mRuntimeSwipeDirection = swipeDir;
                                stopScheduledExecutors();
                                button.setBackgroundTintList(ColorStateList.valueOf(mButtonBackgroundColor));
                                // If in HOLD mode, end the hold since the swipe takes priority
                                if (mSpecialButtonMode == SpecialButtonMode.HOLD && isSpecialButton(buttonInfo)) {
                                    endSpecialButtonHold(buttonInfo);
                                }
                                // Execute the swipe action immediately with haptic feedback,
                                // bypassing onAnyExtraKeyButtonClick to avoid modifier toggle
                                ExtraKeyButton swipeBtn = getSwipeExtraKeyButton(buttonInfo, swipeDir);
                                if (isSpecialButton(swipeBtn)) {
                                    // Single modifier as swipe → activate in ExtraKeysView sticky state
                                    // so the next tap can consume it via readSpecialButton(autoSetInActive=true)
                                    SpecialButtonState state = mSpecialButtons.get(SpecialButton.valueOf(swipeBtn.getKey()));
                                    if (state != null) state.setIsActive(true);
                                } else {
                                    // Regular key or macro → send directly to terminal
                                    onExtraKeyButtonClick(view, swipeBtn, button);
                                }
                                performExtraKeyButtonHapticFeedback(view, swipeBtn, button, true);
                                return true;
                            }

                            return true;

                        case MotionEvent.ACTION_CANCEL:
                            mRuntimeSwipeDirection = null;
                            button.setBackgroundTintList(ColorStateList.valueOf(mButtonBackgroundColor));
                            stopScheduledExecutors();
                            // In HOLD mode a cancelled touch ends the hold
                            if (mSpecialButtonMode == SpecialButtonMode.HOLD && isSpecialButton(buttonInfo)) {
                                endSpecialButtonHold(buttonInfo);
                            }
                            return true;

                        case MotionEvent.ACTION_UP:
                            button.setBackgroundTintList(ColorStateList.valueOf(mButtonBackgroundColor));
                            stopScheduledExecutors();

                            // In HOLD mode a special button deactivates on release
                            if (mSpecialButtonMode == SpecialButtonMode.HOLD && isSpecialButton(buttonInfo)) {
                                endSpecialButtonHold(buttonInfo);
                                return true;
                            }

                            // If a swipe was already executed in ACTION_MOVE, just suppress the tap
                            if (mRuntimeSwipeDirection != null) {
                                mRuntimeSwipeDirection = null;
                                return true;
                            }

                            // If not a long-press repeat, perform normal click
                            if (mLongPressCount == 0) {
                                view.performClick();
                            }
                            return true;

                        default:
                            return true;
                    }
                });

                LayoutParams param = new GridLayout.LayoutParams();
                param.width = 0;
                if(Build.VERSION.SDK_INT == Build.VERSION_CODES.LOLLIPOP) {
                   param.height = (int)(heightPx + 0.5);
                } else {
                    param.height = 0;
                }
                float density = getResources().getDisplayMetrics().density;
                int marginHorizontalPx = (int) (BUTTON_MARGIN_HORIZONTAL_DP * density);
                int marginVerticalPx = (int) (BUTTON_MARGIN_VERTICAL_DP * density);
                param.setMargins(marginHorizontalPx, marginVerticalPx, marginHorizontalPx, marginVerticalPx);
                param.columnSpec = GridLayout.spec(col, GridLayout.FILL, 1.f);
                param.rowSpec = GridLayout.spec(row, GridLayout.FILL, 1.f);
                button.setLayoutParams(param);

                addView(button);
            }
        }
    }



    public void onExtraKeyButtonClick(View view, ExtraKeyButton buttonInfo, MaterialButton button) {
        if (mExtraKeysViewClient != null)
            mExtraKeysViewClient.onExtraKeyButtonClick(view, buttonInfo, button);
    }

    public void performExtraKeyButtonHapticFeedback(View view, ExtraKeyButton buttonInfo, MaterialButton button, boolean isGesture) {
        // Check the haptic mode setting
        int hapticMode = TermuxPropertyConstants.DEFAULT_IVALUE_EXTRA_KEYS_HAPTIC;
        TermuxAppSharedProperties props = TermuxAppSharedProperties.getProperties();
        if (props != null) {
            hapticMode = props.getExtraKeysHaptic();
        }

        if (hapticMode == TermuxPropertyConstants.IVALUE_EXTRA_KEYS_HAPTIC_OFF) return;
        if (!isGesture && hapticMode == TermuxPropertyConstants.IVALUE_EXTRA_KEYS_HAPTIC_GESTURES) return;

        if (mExtraKeysViewClient != null) {
            // If client handled the feedback, then just return
            if (mExtraKeysViewClient.performExtraKeyButtonHapticFeedback(view, buttonInfo, button))
                return;
        }

        if (Settings.System.getInt(getContext().getContentResolver(),
            Settings.System.HAPTIC_FEEDBACK_ENABLED, 0) != 0) {

            if (Build.VERSION.SDK_INT >= 28) {
                button.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            } else {
                // Perform haptic feedback only if no total silence mode enabled.
                if (Settings.Global.getInt(getContext().getContentResolver(), "zen_mode", 0) != 2) {
                    button.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                }
            }
        }
    }



    public void onAnyExtraKeyButtonClick(View view, @NonNull ExtraKeyButton buttonInfo, MaterialButton button) {
        if (isSpecialButton(buttonInfo)) {
            if (mLongPressCount > 0) return;
            // In HOLD mode the special button is driven entirely by touch events, so a click
            // (which would normally toggle) must not interfere with the hold state.
            if (mSpecialButtonMode == SpecialButtonMode.HOLD) return;
            SpecialButtonState state = mSpecialButtons.get(SpecialButton.valueOf(buttonInfo.getKey()));
            if (state == null) return;

            // Toggle active state and disable lock state if new state is not active
            state.setIsActive(!state.isActive);
            if (!state.isActive)
                state.setIsLocked(false);
        } else {
            onExtraKeyButtonClick(view, buttonInfo, button);
        }
    }


    public void startScheduledExecutors(View view, ExtraKeyButton buttonInfo, MaterialButton button) {
        stopScheduledExecutors();
        mLongPressCount = 0;
        if (mRepetitiveKeys.contains(buttonInfo.getKey())) {
            // Auto repeat key if long pressed until ACTION_UP stops it by calling stopScheduledExecutors.
            // Currently, only one (last) repeat key can run at a time. Old ones are stopped.
            mScheduledExecutor = Executors.newSingleThreadScheduledExecutor();
            mScheduledExecutor.scheduleWithFixedDelay(() -> {
                mLongPressCount++;
                onExtraKeyButtonClick(view, buttonInfo, button);
            }, mLongPressTimeout, mLongPressRepeatDelay, TimeUnit.MILLISECONDS);
        } else if (isSpecialButton(buttonInfo)) {
            // Lock the key if long pressed by running mSpecialButtonsLongHoldRunnable after
            // waiting for mLongPressTimeout milliseconds. If user does not long press, then the
            // ACTION_UP triggered will cancel the runnable by calling stopScheduledExecutors before
            // it has a chance to run.
            SpecialButtonState state = mSpecialButtons.get(SpecialButton.valueOf(buttonInfo.getKey()));
            if (state == null) return;
            if (mHandler == null)
                mHandler = new Handler(Looper.getMainLooper());
            mSpecialButtonsLongHoldRunnable = new SpecialButtonsLongHoldRunnable(state);
            mHandler.postDelayed(mSpecialButtonsLongHoldRunnable, mLongPressTimeout);
        }
    }

    public void stopScheduledExecutors() {
        if (mScheduledExecutor != null) {
            mScheduledExecutor.shutdownNow();
            mScheduledExecutor = null;
        }

        if (mSpecialButtonsLongHoldRunnable != null && mHandler != null) {
            mHandler.removeCallbacks(mSpecialButtonsLongHoldRunnable);
            mSpecialButtonsLongHoldRunnable = null;
        }
    }

    /**
     * Deactivate a special button that was engaged in {@link SpecialButtonMode#HOLD} mode because the
     * finger was released (or the touch was cancelled). Only affects buttons currently held.
     *
     * @param buttonInfo The {@link ExtraKeyButton} for the special button being released.
     */
    private void endSpecialButtonHold(ExtraKeyButton buttonInfo) {
        SpecialButtonState state = mSpecialButtons.get(SpecialButton.valueOf(buttonInfo.getKey()));
        if (state == null) return;
        if (state.isHolding) {
            state.setIsHolding(false);
            state.setIsActive(false);
        }
    }

    public class SpecialButtonsLongHoldRunnable implements Runnable {
        public final SpecialButtonState mState;

        public SpecialButtonsLongHoldRunnable(SpecialButtonState state) {
            mState = state;
        }

        public void run() {
            // Toggle active and lock state
            mState.setIsLocked(!mState.isActive);
            mState.setIsActive(!mState.isActive);
            mLongPressCount++;
        }
    }



    void showPopup(View view, ExtraKeyButton extraButton) {
        int width = view.getMeasuredWidth();
        int height = view.getMeasuredHeight();
        MaterialButton button;
        if (isSpecialButton(extraButton)) {
            button = createSpecialButton(extraButton.getKey(), false);
            if (button == null) return;
        } else {
            button = new MaterialButton(getContext(), null, android.R.attr.buttonBarButtonStyle);
            button.setTextColor(mButtonTextColor);
        }
        button.setText(extraButton.getDisplay());
        button.setAllCaps(mButtonTextAllCaps);
        button.setPadding(0, 0, 0, 0);
        button.setMinHeight(0);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setMinimumHeight(0);
        button.setIncludeFontPadding(false);
        button.setInsetTop(0);
        button.setInsetBottom(0);
        button.setWidth(width);
        button.setHeight(height);
        button.setBackgroundTintList(ColorStateList.valueOf(mButtonActiveBackgroundColor));
        mPopupWindow = new PopupWindow(this);
        mPopupWindow.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(mButtonBackgroundColor));
        mPopupWindow.setWidth(LayoutParams.WRAP_CONTENT);
        mPopupWindow.setHeight(LayoutParams.WRAP_CONTENT);
        mPopupWindow.setContentView(button);
        mPopupWindow.setOutsideTouchable(true);
        mPopupWindow.setFocusable(false);
        mPopupWindow.showAsDropDown(view, 0, -2 * height);
    }

    public void dismissPopup() {
        mPopupWindow.setContentView(null);
        mPopupWindow.dismiss();
        mPopupWindow = null;
    }



    /** Check whether a {@link ExtraKeyButton} is a {@link SpecialButton}. */
    public boolean isSpecialButton(ExtraKeyButton button) {
        return mSpecialButtonsKeys.contains(button.getKey());
    }

    /**
     * Read whether {@link SpecialButton} registered in {@link #mSpecialButtons} is active or not.
     *
     * @param specialButton The {@link SpecialButton} to read.
     * @param autoSetInActive Set to {@code true} if {@link SpecialButtonState#isActive} should be
     *                        set {@code false} if button is not locked.
     * @return Returns {@code null} if button does not exist in {@link #mSpecialButtons}. If button
     *         exists, then returns {@code true} if the button is created in {@link ExtraKeysView}
     *         and is active, otherwise {@code false}.
     */
    @Nullable
    public Boolean readSpecialButton(SpecialButton specialButton, boolean autoSetInActive) {
        SpecialButtonState state = mSpecialButtons.get(specialButton);
        if (state == null) return null;

        if (!state.isCreated || !state.isActive)
            return false;

        // Disable active state only if not locked and not currently held down
        if (autoSetInActive && !state.isLocked && !state.isHolding)
            state.setIsActive(false);

        return true;
    }

    public MaterialButton createSpecialButton(String buttonKey, boolean needUpdate) {
        SpecialButtonState state = mSpecialButtons.get(SpecialButton.valueOf(buttonKey));
        if (state == null) return null;
        state.setIsCreated(true);
        MaterialButton button = new MaterialButton(getContext(), null, android.R.attr.buttonBarButtonStyle);
        button.setTextColor(state.isActive ? mButtonActiveTextColor : mButtonTextColor);
        button.setBackgroundTintList(ColorStateList.valueOf(state.isActive ? mButtonActiveBackgroundColor : mButtonBackgroundColor));
        button.setCornerRadius((int) (mButtonCornerRadiusDp * getResources().getDisplayMetrics().density));
        button.setPadding(0, 0, 0, 0);
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        button.setIncludeFontPadding(false);
        button.setInsetTop(0);
        button.setInsetBottom(0);
        if (needUpdate) {
            state.buttons.add(button);
        }
        return button;
    }



    @Nullable
    private static ExtraKeyButton getSwipeExtraKeyButton(ExtraKeyButton buttonInfo, SwipeDirection direction) {
        if (buttonInfo == null || direction == null) return null;
        switch (direction) {
            case UP:    return buttonInfo.getSwipeUp();
            case DOWN:  return buttonInfo.getSwipeDown();
            case LEFT:  return buttonInfo.getSwipeLeft();
            case RIGHT: return buttonInfo.getSwipeRight();
        }
        return null;
    }

    /** Set the editor gesture listener. Non-null activates editor mode. */
    public void setEditorGestureListener(@Nullable EditorGestureListener listener) {
        mEditorListener = listener;
    }

    /** Cancel any ongoing editor gesture and reset touch state. */
    public void cancelEditorGesture() {
        resetTouchState();
    }

    @Nullable
    private SwipeDirection detectDirection(float dx, float dy) {
        float absDx = Math.abs(dx);
        float absDy = Math.abs(dy);
        if (Math.max(absDx, absDy) < mSwipeThreshold) return null;
        if (absDx > absDy) {
            return dx > 0 ? SwipeDirection.RIGHT : SwipeDirection.LEFT;
        } else {
            return dy > 0 ? SwipeDirection.DOWN : SwipeDirection.UP;
        }
    }

    @Nullable
    private View findChildAt(float x, float y) {
        android.graphics.Rect rect = new android.graphics.Rect();
        for (int i = getChildCount() - 1; i >= 0; i--) {
            View child = getChildAt(i);
            if (child.getVisibility() != VISIBLE) continue;
            child.getHitRect(rect);
            if (rect.contains((int) x, (int) y)) return child;
        }
        return null;
    }

    private boolean isInsideView(@Nullable View view, float x, float y) {
        if (view == null) return false;
        android.graphics.Rect rect = new android.graphics.Rect();
        view.getHitRect(rect);
        return rect.contains((int) x, (int) y);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        if (mEditorListener != null) {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    View child = findChildAt(ev.getX(), ev.getY());
                    if (child != null) {
                        mActiveChild = child;
                        return true;
                    }
                    return false;
                case MotionEvent.ACTION_MOVE:
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (mActiveChild != null) return true;
                    return false;
            }
            return false;
        }
        return super.onInterceptTouchEvent(ev);
    }

    @Override
    @SuppressLint("ClickableViewAccessibility")
    public boolean onTouchEvent(MotionEvent ev) {
        if (mEditorListener == null) {
            return super.onTouchEvent(ev);
        }
        return handleEditorGesture(ev);
    }

    private boolean handleEditorGesture(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                if (mActiveChild == null) return false;
                ViewParent parent = getParent();
                if (parent != null) parent.requestDisallowInterceptTouchEvent(true);

                mActivePointerId = ev.getPointerId(0);
                mDownX = ev.getX();
                mDownY = ev.getY();
                mGestureConsumed = false;

                mActiveChild.setPressed(true);
                return true;
            }

            case MotionEvent.ACTION_MOVE: {
                if (mActivePointerId == INVALID_POINTER_ID || mGestureConsumed) return true;

                int pointerIndex = ev.findPointerIndex(mActivePointerId);
                if (pointerIndex < 0) return true;

                float x = ev.getX(pointerIndex);
                float y = ev.getY(pointerIndex);
                float dx = x - mDownX;
                float dy = y - mDownY;

                SwipeDirection direction = detectDirection(dx, dy);
                if (direction != null) {
                    fireSwipe(mActiveChild, direction);
                }
                return true;
            }

            case MotionEvent.ACTION_UP: {
                if (mActivePointerId == INVALID_POINTER_ID) return true;

                int pointerIndex = ev.findPointerIndex(mActivePointerId);
                if (pointerIndex >= 0 && !mGestureConsumed) {
                    float x = ev.getX(pointerIndex);
                    float y = ev.getY(pointerIndex);
                    float dx = x - mDownX;
                    float dy = y - mDownY;

                    SwipeDirection direction = detectDirection(dx, dy);
                    if (direction != null) {
                        fireSwipe(mActiveChild, direction);
                    } else if (isInsideView(mActiveChild, x, y)) {
                        fireTap(mActiveChild);
                    }
                }

                resetTouchState();
                return true;
            }

            case MotionEvent.ACTION_CANCEL: {
                resetTouchState();
                return true;
            }

            case MotionEvent.ACTION_POINTER_DOWN: {
                if (!mGestureConsumed) resetTouchState();
                return true;
            }

            case MotionEvent.ACTION_POINTER_UP: {
                int pointerIndexAction = ev.getActionIndex();
                int pointerId = ev.getPointerId(pointerIndexAction);
                if (pointerId == mActivePointerId) {
                    if (!mGestureConsumed) resetTouchState();
                    else mActivePointerId = INVALID_POINTER_ID;
                }
                return true;
            }
        }
        return false;
    }

    private void fireTap(@Nullable View button) {
        if (button == null) return;
        Object tag = button.getTag();
        if (!(tag instanceof int[])) return;
        int[] coord = (int[]) tag;

        final View fButton = button;
        final int row = coord[0];
        final int col = coord[1];

        button.post(() -> {
            if (!isAttachedToWindow() || mEditorListener == null) return;
            mEditorListener.onKeyTap(fButton, row, col);
        });
    }

    private void fireSwipe(@Nullable View button, SwipeDirection direction) {
        if (button == null) return;
        Object tag = button.getTag();
        if (!(tag instanceof int[])) return;
        int[] coord = (int[]) tag;

        final View fButton = button;
        final int row = coord[0];
        final int col = coord[1];
        final SwipeDirection fDir = direction;

        mGestureConsumed = true;

        button.setPressed(false);
        button.cancelLongPress();

        button.post(() -> {
            if (!isAttachedToWindow() || mEditorListener == null) return;
            mEditorListener.onKeySwipe(fButton, row, col, fDir);
        });
    }

    private void resetTouchState() {
        if (mActiveChild != null) {
            mActiveChild.setPressed(false);
        }
        mActiveChild = null;
        mActivePointerId = INVALID_POINTER_ID;
        mGestureConsumed = false;

        ViewParent parent = getParent();
        if (parent != null) {
            parent.requestDisallowInterceptTouchEvent(false);
        }
    }

    /**
     * General util function to compute the longest column length in a matrix.
     */
    public static int maximumLength(Object[][] matrix) {
        int m = 0;
        for (Object[] row : matrix)
            m = Math.max(m, row.length);
        return m;
    }

}

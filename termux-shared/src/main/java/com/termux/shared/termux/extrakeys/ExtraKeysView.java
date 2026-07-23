package com.termux.shared.termux.extrakeys;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.ColorStateList;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.AttributeSet;
import android.util.ArrayMap;
import android.util.TypedValue;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;

import java.util.HashSet;
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

    /** Editor mode: assign signals vs move/swap cells. */
    public enum EditorMode {
        ASSIGN,
        MOVE
    }

    /** Listener for move-mode cell swap operations. */
    public interface EditorMoveListener {
        /**
         * Called when a drag-and-drop move completes in MOVE mode.
         * Coordinates are row/col indices from the view child tags (0-based, relative to visible grid).
         */
        void onCellMove(int fromRow, int fromCol, int toRow, int toCol);
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

    /** Current button margin horizontal in dp, loaded from preferences. */
    private float mButtonMarginHorizontalDp = BUTTON_MARGIN_HORIZONTAL_DP;
    /** Current button margin vertical in dp, loaded from preferences. */
    private float mButtonMarginVerticalDp = BUTTON_MARGIN_VERTICAL_DP;

    private boolean mEditorEdgeIndicatorsEnabled;
    private int mEditorEdgeColor = 0xFF888888;
    private final Paint mEditorEdgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final float EDITOR_EDGE_THICKNESS_DP = 2f;


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
    protected ArrayMap<SpecialButton, SpecialButtonState> mSpecialButtons;

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

    /** If true, font size is reduced when column count or macro bind count increases. */
    private boolean mDynamicFontSize = true;

    /** The base font size in sp for button labels. Defaults to 14. */
    private int mBaseFontSizeSp = 14;


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

    protected final ScheduledExecutorService mScheduledExecutor = Executors.newSingleThreadScheduledExecutor();
    protected ScheduledFuture<?> mRepetitiveFuture;
    protected Handler mHandler;
    protected SpecialButtonsLongHoldRunnable mSpecialButtonsLongHoldRunnable;
    protected int mLongPressCount;

    private ColorStateList mButtonBgTint;
    private ColorStateList mButtonActiveBgTint;

    private final List<MaterialButton> mButtonPool = new ArrayList<>();

    private final float mDensity;

    private final TextPaint mMeasPaint = new TextPaint();

    private final RectF mEditorOval = new RectF();
    private final Path mEditorPath = new Path();

    private final android.graphics.Rect mHitRect = new android.graphics.Rect();

    @Nullable
    private SwipeDirection mEditorSwipeDir;

    /** Current editor mode. Defaults to ASSIGN (tap → signal picker, swipe → gesture picker). */
    private EditorMode mEditorMode = EditorMode.ASSIGN;

    /** Listener for move-mode drag-and-drop operations. Null unless MOVE mode is active. */
    @Nullable
    private EditorMoveListener mEditorMoveListener;


    public ExtraKeysView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setUseDefaultMargins(false);

        mDensity = getResources().getDisplayMetrics().density;

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
        int minThresholdPx = (int) (minThresholdDp * mDensity);
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
        return new ArrayList<>(mRepetitiveKeys);
    }

    /** Set {@link #mRepetitiveKeys}. Must not be {@code null}. */
    public void setRepetitiveKeys(@NonNull List<String> repetitiveKeys) {
        mRepetitiveKeys = repetitiveKeys;
    }


    /** Get {@link #mSpecialButtons}. */
    public ArrayMap<SpecialButton, SpecialButtonState> getSpecialButtons() {
        if (mSpecialButtons == null) return null;
        return mSpecialButtons;
    }

    /** Get {@link #mSpecialButtonsKeys}. */
    public Set<String> getSpecialButtonsKeys() {
        if (mSpecialButtonsKeys == null) return null;
        return new HashSet<>(mSpecialButtonsKeys);
    }

    /** Set {@link #mSpecialButtonsKeys}. Must not be {@code null}. */
    public void setSpecialButtons(@NonNull ArrayMap<SpecialButton, SpecialButtonState> specialButtons) {
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
        mButtonBgTint = ColorStateList.valueOf(mButtonBackgroundColor);
        mButtonActiveBgTint = ColorStateList.valueOf(mButtonActiveBackgroundColor);
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
            button.setBackgroundTintList(mButtonBgTint);
        }
        // Keep tinted special buttons consistent with their current active state.
        for (SpecialButtonState state : mSpecialButtons.values()) {
            for (MaterialButton button : state.buttons) {
                button.setTextColor(state.isActive ? mButtonActiveTextColor : mButtonTextColor);
                button.setBackgroundTintList(state.isActive ? mButtonActiveBgTint : mButtonBgTint);
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

    public void setDynamicFontSize(boolean enabled) {
        mDynamicFontSize = enabled;
    }

    public void setBaseFontSizeSp(int sp) {
        mBaseFontSizeSp = sp;
    }

    public void setButtonMargins(float dp) {
        mButtonMarginHorizontalDp = dp;
        mButtonMarginVerticalDp = dp;
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
    public ArrayMap<SpecialButton, SpecialButtonState> getDefaultSpecialButtons(ExtraKeysView extraKeysView) {
        ArrayMap<SpecialButton, SpecialButtonState> map = new ArrayMap<>(4);
        map.put(SpecialButton.CTRL, new SpecialButtonState(extraKeysView));
        map.put(SpecialButton.ALT, new SpecialButtonState(extraKeysView));
        map.put(SpecialButton.SHIFT, new SpecialButtonState(extraKeysView));
        map.put(SpecialButton.FN, new SpecialButtonState(extraKeysView));
        return map;
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

        stopScheduledExecutors();
        mButtonPool.clear();
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child instanceof MaterialButton) {
                mButtonPool.add((MaterialButton) child);
            }
        }

        for(SpecialButtonState state : mSpecialButtons.values())
            state.buttons = new ArrayList<>();

        removeAllViews();

        TermuxAppSharedProperties props = TermuxAppSharedProperties.getProperties();
        mButtonCornerRadiusDp = props != null ? props.getExtraKeysCornerRadius() : BUTTON_CORNER_RADIUS_DP;
        mButtonMarginHorizontalDp = props != null ? props.getExtraKeysButtonMargin() : BUTTON_MARGIN_HORIZONTAL_DP;
        mButtonMarginVerticalDp = mButtonMarginHorizontalDp;
        mBaseFontSizeSp = props != null ? props.getExtraKeysFontSize() : 14;

        ExtraKeyButton[][] buttons = extraKeysInfo.getMatrix();

        setRowCount(buttons.length);
        setColumnCount(maximumLength(buttons));

        int maxCols = getColumnCount();
        for (int row = 0; row < buttons.length; row++) {
            for (int col = 0; col < buttons[row].length; col++) {
                final ExtraKeyButton buttonInfo = buttons[row][col];

                MaterialButton button;
                if (isSpecialButton(buttonInfo)) {
                    button = createSpecialButton(buttonInfo.getKey(), true);
                    if (button == null) return;
                } else if (!mButtonPool.isEmpty()) {
                    button = mButtonPool.remove(0);
                    button.setText(null);
                    button.setOnClickListener(null);
                    button.setOnTouchListener(null);
                    button.setTag(null);
                    button.setPressed(false);
                } else {
                    button = createDefaultMaterialButton();
                }
                button.setText(buttonInfo.getDisplay());

                // Font size based on column count: base for <=7 columns, -1sp per extra column
                float fontSizeSp = mBaseFontSizeSp;
                // Single-character labels keep full base regardless of columns
                String displayText = buttonInfo.getDisplay();
                boolean isSingleChar = displayText != null && displayText.codePointCount(0, displayText.length()) == 1;
                if (!isSingleChar) {
                    if (mDynamicFontSize) {
                        int totalCols = buttons[row].length;
                        fontSizeSp = mBaseFontSizeSp - Math.max(0, totalCols - 7);
                        // Macro keys (multiple sequential binds) get an additional -2sp
                        if (buttonInfo.isMacro()) fontSizeSp -= 2f;
                        fontSizeSp = Math.max(fontSizeSp, 8f);
                    } else {
                        fontSizeSp = mBaseFontSizeSp;
                    }
                }

                button.setAllCaps(mButtonTextAllCaps);
                button.setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSizeSp);

                if (isSingleChar) {
                    button.setMaxLines(1);
                    button.setSingleLine(true);
                } else {
                    // Multi-line with ellipsize
                    button.setSingleLine(false);
                    button.setHorizontallyScrolling(false);

                    // Calculate max lines from available height
                    int vMarginPx = (int) (mButtonMarginVerticalDp * mDensity);
                    int buttonH = (int) (heightPx + 0.5f) - 2 * vMarginPx;
                    int textAreaH = buttonH;  // padding already zeroed

                    mMeasPaint.setTypeface(button.getPaint().getTypeface());
                    mMeasPaint.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, fontSizeSp, getResources().getDisplayMetrics()));
                    int lineHeight = mMeasPaint.getFontMetricsInt(null);
                    float lineSpacing = button.getLineSpacingMultiplier();
                    if (lineSpacing > 0f) lineHeight = (int) (lineHeight * lineSpacing);
                    lineHeight += (int) button.getLineSpacingExtra();
                    int maxLines = Math.max(1, (int) Math.floor(textAreaH / (float) Math.max(1, lineHeight)));

                    // For macro buttons: custom truncation at bind boundaries.
                    // If layout hasn't happened yet (getWidth() == 0), defer truncation
                    // to a post-layout callback.
                    if (buttonInfo.isMacro() && displayText != null && displayText.contains(" ")) {
                        int totalCols = maxCols;
                        if (totalCols > 0 && getWidth() > 0) {
                            int marginHPx = (int) (mButtonMarginHorizontalDp * mDensity);
                            int cellW = getWidth() / totalCols;
                            int buttonW = cellW - 2 * marginHPx;
                            int textAreaW = buttonW;
                            if (textAreaW > 0) {
                                String truncated = truncateMacroText(displayText, mMeasPaint, textAreaW, maxLines);
                                if (truncated != null) {
                                    button.setText(truncated);
                                    button.setMaxLines(maxLines);
                                    button.setEllipsize(null);
                                } else {
                                    button.setMaxLines(maxLines);
                                    button.setEllipsize(TextUtils.TruncateAt.END);
                                }
                            } else {
                                button.setMaxLines(maxLines);
                            }
                        } else {
                            button.setMaxLines(maxLines);
                            button.setEllipsize(TextUtils.TruncateAt.END);
                        }
                    } else {
                        button.setMaxLines(maxLines);
                        button.setEllipsize(TextUtils.TruncateAt.END);
                    }
                }
                button.setTextColor(mButtonTextColor);
                button.setBackgroundTintList(mButtonBgTint);
                button.setCornerRadius((int) (mButtonCornerRadiusDp * mDensity));

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

                            button.setBackgroundTintList(mButtonActiveBgTint);

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
                                button.setBackgroundTintList(mButtonBgTint);
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
                            button.setBackgroundTintList(mButtonBgTint);
                            stopScheduledExecutors();
                            // In HOLD mode a cancelled touch ends the hold
                            if (mSpecialButtonMode == SpecialButtonMode.HOLD && isSpecialButton(buttonInfo)) {
                                endSpecialButtonHold(buttonInfo);
                            }
                            return true;

                        case MotionEvent.ACTION_UP:
                            button.setBackgroundTintList(mButtonBgTint);
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

                            // Also check for swipe at release time (may have crossed threshold during liftoff)
                            if (buttonInfo != null) {
                                float upDx = event.getX() - mTouchDownX;
                                float upDy = event.getY() - mTouchDownY;
                                SwipeDirection upSwipe = detectDirection(upDx, upDy);
                                if (upSwipe != null) {
                                    ExtraKeyButton swipeBtn = getSwipeExtraKeyButton(buttonInfo, upSwipe);
                                    if (swipeBtn != null) {
                                        mRuntimeSwipeDirection = upSwipe;
                                        // Execute swipe action with haptic feedback
                                        if (mSpecialButtonMode == SpecialButtonMode.HOLD && isSpecialButton(buttonInfo)) {
                                            endSpecialButtonHold(buttonInfo);
                                        }
                                        if (isSpecialButton(swipeBtn)) {
                                            SpecialButtonState state = mSpecialButtons.get(SpecialButton.valueOf(swipeBtn.getKey()));
                                            if (state != null) state.setIsActive(true);
                                        } else {
                                            onExtraKeyButtonClick(view, swipeBtn, button);
                                        }
                                        performExtraKeyButtonHapticFeedback(view, swipeBtn, button, true);
                                        return true;
                                    }
                                }
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
                param.height = 0;
                int marginHorizontalPx = (int) (mButtonMarginHorizontalDp * mDensity);
                int marginVerticalPx = (int) (mButtonMarginVerticalDp * mDensity);
                param.setMargins(marginHorizontalPx, marginVerticalPx, marginHorizontalPx, marginVerticalPx);
                param.columnSpec = GridLayout.spec(col, GridLayout.FILL, 1.f);
                param.rowSpec = GridLayout.spec(row, GridLayout.FILL, 1.f);
                button.setLayoutParams(param);

                addView(button);
            }
        }
        // Defer macro text truncation to after layout since getWidth() may be 0 during reload()
        post(this::applyMacroTruncationAfterLayout);
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
            if (mRepetitiveFuture != null) {
                mRepetitiveFuture.cancel(false);
                mRepetitiveFuture = null;
            }
            mRepetitiveFuture = mScheduledExecutor.scheduleWithFixedDelay(() -> {
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
        if (mRepetitiveFuture != null) {
            mRepetitiveFuture.cancel(false);
            mRepetitiveFuture = null;
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

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        stopScheduledExecutors();
        if (mHandler != null) {
            mHandler.removeCallbacksAndMessages(null);
            mHandler = null;
        }
        if (mPopupWindow != null && mPopupWindow.isShowing()) {
            mPopupWindow.dismiss();
            mPopupWindow = null;
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
            button = createDefaultMaterialButton();
            button.setTextColor(mButtonTextColor);
        }
        button.setText(extraButton.getDisplay());
        button.setAllCaps(mButtonTextAllCaps);
        button.setWidth(width);
        button.setHeight(height);
        button.setBackgroundTintList(mButtonActiveBgTint);
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

    /**
     * Create a MaterialButton configured with zero internal padding/insets.
     * This ensures the text area equals the button area for accurate multi-line
     * measurement. Boilerplate is defined once here instead of at every creation site.
     */
    private MaterialButton createDefaultMaterialButton() {
        MaterialButton button = new MaterialButton(getContext(), null, android.R.attr.buttonBarButtonStyle);
        button.setPadding(0, 0, 0, 0);
        button.setInsetTop(0);
        button.setInsetBottom(0);
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        button.setIncludeFontPadding(false);
        return button;
    }

    public MaterialButton createSpecialButton(String buttonKey, boolean needUpdate) {
        SpecialButtonState state = mSpecialButtons.get(SpecialButton.valueOf(buttonKey));
        if (state == null) return null;
        state.setIsCreated(true);
        MaterialButton button = createDefaultMaterialButton();
        button.setTextColor(state.isActive ? mButtonActiveTextColor : mButtonTextColor);
        button.setBackgroundTintList(state.isActive ? mButtonActiveBgTint : mButtonBgTint);
        button.setCornerRadius((int) (mButtonCornerRadiusDp * mDensity));
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

    /** Set the editor edge indicator color and enable edge indicators. */
    public void setEditorEdgeColor(int color) {
        mEditorEdgeColor = color;
        mEditorEdgeIndicatorsEnabled = true;
    }

    /** Set the editor interaction mode. */
    public void setEditorMode(@NonNull EditorMode mode) {
        mEditorMode = mode;
    }

    /** Set the listener for move-mode drag-and-drop operations. */
    public void setEditorMoveListener(@Nullable EditorMoveListener listener) {
        mEditorMoveListener = listener;
    }

    /** Cancel any ongoing editor gesture and reset touch state. */
    public void cancelEditorGesture() {
        resetTouchState();
    }

    private void applyMacroTruncationAfterLayout() {
        if (getWidth() <= 0 || getColumnCount() <= 0) return;
        int cellW = getWidth() / getColumnCount();
        int marginHPx = (int) (mButtonMarginHorizontalDp * mDensity);
        int buttonW = cellW - 2 * marginHPx;
        if (buttonW <= 0) return;

        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (!(child instanceof MaterialButton)) continue;
            MaterialButton button = (MaterialButton) child;
            CharSequence text = button.getText();
            if (text == null || text.length() == 0) continue;
            String display = text.toString();
            if (!display.contains(" ")) continue;

            int maxLines = button.getMaxLines();
            if (maxLines <= 0) continue;

            mMeasPaint.setTypeface(button.getPaint().getTypeface());
            mMeasPaint.setTextSize(button.getTextSize());

            String truncated = truncateMacroText(display, mMeasPaint, buttonW, maxLines);
            if (truncated != null) {
                button.setText(truncated);
                button.setEllipsize(null);
            }
        }
    }

    @Override
    public void dispatchDraw(Canvas canvas) {
        super.dispatchDraw(canvas);
        if (!mEditorEdgeIndicatorsEnabled || mEditorListener == null) return;

        float thickness = EDITOR_EDGE_THICKNESS_DP * mDensity;
        float halfThick = thickness / 2f;
        float cornerPx = mButtonCornerRadiusDp * mDensity;
        if (cornerPx <= 0) cornerPx = 1f;
        float centerRadius = Math.max(0, cornerPx - halfThick);

        mEditorEdgePaint.setColor(mEditorEdgeColor);
        mEditorEdgePaint.setStyle(Paint.Style.STROKE);
        mEditorEdgePaint.setStrokeWidth(thickness);
        mEditorEdgePaint.setStrokeCap(Paint.Cap.BUTT);
        mEditorEdgePaint.setAntiAlias(true);

        mEditorPath.rewind();

        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            Object tag = child.getTag();
            if (!(tag instanceof int[])) continue;
            int[] arr = (int[]) tag;
            if (arr.length < 3) continue;
            int flags = arr[2];

            float l = child.getLeft();
            float t = child.getTop();
            float r = child.getRight();
            float b = child.getBottom();

            float bw = r - l;
            float bh = b - t;
            float maxR = Math.min(bw, bh) / 2f;
            float effectiveCornerPx = Math.min(cornerPx, maxR);
            if (effectiveCornerPx <= 0) effectiveCornerPx = 1f;
            float effectiveCenterRadius = Math.max(0f, effectiveCornerPx - halfThick);

            if (effectiveCenterRadius <= 0f) {
                mEditorEdgePaint.setStyle(Paint.Style.FILL);
                mEditorEdgePaint.setStrokeWidth(0);
                if ((flags & 1) != 0) { // top
                    canvas.drawRect(l + halfThick, t, r - halfThick, t + thickness, mEditorEdgePaint);
                }
                if ((flags & 2) != 0) { // bottom
                    canvas.drawRect(l + halfThick, b - thickness, r - halfThick, b, mEditorEdgePaint);
                }
                if ((flags & 4) != 0) { // left
                    canvas.drawRect(l, t + halfThick, l + thickness, b - halfThick, mEditorEdgePaint);
                }
                if ((flags & 8) != 0) { // right
                    canvas.drawRect(r - thickness, t + halfThick, r, b - halfThick, mEditorEdgePaint);
                }
                mEditorEdgePaint.setStyle(Paint.Style.STROKE);
                mEditorEdgePaint.setStrokeWidth(thickness);
                continue;
            }

            if ((flags & 1) != 0) {
                mEditorPath.reset();
                mEditorOval.set(l + effectiveCornerPx - effectiveCenterRadius, t + effectiveCornerPx - effectiveCenterRadius,
                         l + effectiveCornerPx + effectiveCenterRadius, t + effectiveCornerPx + effectiveCenterRadius);
                mEditorPath.arcTo(mEditorOval, 240f, 30f, true);
                mEditorOval.set(r - effectiveCornerPx - effectiveCenterRadius, t + effectiveCornerPx - effectiveCenterRadius,
                         r - effectiveCornerPx + effectiveCenterRadius, t + effectiveCornerPx + effectiveCenterRadius);
                mEditorPath.arcTo(mEditorOval, 270f, 30f, false);
                canvas.drawPath(mEditorPath, mEditorEdgePaint);
            }
            if ((flags & 2) != 0) {
                mEditorPath.reset();
                mEditorOval.set(r - effectiveCornerPx - effectiveCenterRadius, b - effectiveCornerPx - effectiveCenterRadius,
                         r - effectiveCornerPx + effectiveCenterRadius, b - effectiveCornerPx + effectiveCenterRadius);
                mEditorPath.arcTo(mEditorOval, 60f, 30f, true);
                mEditorOval.set(l + effectiveCornerPx - effectiveCenterRadius, b - effectiveCornerPx - effectiveCenterRadius,
                         l + effectiveCornerPx + effectiveCenterRadius, b - effectiveCornerPx + effectiveCenterRadius);
                mEditorPath.arcTo(mEditorOval, 90f, 30f, false);
                canvas.drawPath(mEditorPath, mEditorEdgePaint);
            }
            if ((flags & 4) != 0) {
                mEditorPath.reset();
                mEditorOval.set(l + effectiveCornerPx - effectiveCenterRadius, t + effectiveCornerPx - effectiveCenterRadius,
                         l + effectiveCornerPx + effectiveCenterRadius, t + effectiveCornerPx + effectiveCenterRadius);
                mEditorPath.arcTo(mEditorOval, 210f, -30f, true);
                mEditorOval.set(l + effectiveCornerPx - effectiveCenterRadius, b - effectiveCornerPx - effectiveCenterRadius,
                         l + effectiveCornerPx + effectiveCenterRadius, b - effectiveCornerPx + effectiveCenterRadius);
                mEditorPath.arcTo(mEditorOval, 180f, -30f, false);
                canvas.drawPath(mEditorPath, mEditorEdgePaint);
            }
            if ((flags & 8) != 0) {
                mEditorPath.reset();
                mEditorOval.set(r - effectiveCornerPx - effectiveCenterRadius, b - effectiveCornerPx - effectiveCenterRadius,
                         r - effectiveCornerPx + effectiveCenterRadius, b - effectiveCornerPx + effectiveCenterRadius);
                mEditorPath.arcTo(mEditorOval, 30f, -30f, true);
                mEditorOval.set(r - effectiveCornerPx - effectiveCenterRadius, t + effectiveCornerPx - effectiveCenterRadius,
                         r - effectiveCornerPx + effectiveCenterRadius, t + effectiveCornerPx + effectiveCenterRadius);
                mEditorPath.arcTo(mEditorOval, 360f, -30f, false);
                canvas.drawPath(mEditorPath, mEditorEdgePaint);
            }
        }


    }

    @Nullable
    private SwipeDirection detectDirection(float dx, float dy) {
        float absDx = Math.abs(dx);
        float absDy = Math.abs(dy);
        if (Math.max(absDx, absDy) < mSwipeThreshold) return null;
        if (absDx > absDy * 1.5f) {
            return dx > 0 ? SwipeDirection.RIGHT : SwipeDirection.LEFT;
        } else if (absDy > absDx * 1.5f) {
            return dy > 0 ? SwipeDirection.DOWN : SwipeDirection.UP;
        } else {
            return null; // diagonal — ambiguous, reject
        }
    }

    @Nullable
    private View findChildAt(float x, float y) {
        for (int i = getChildCount() - 1; i >= 0; i--) {
            View child = getChildAt(i);
            if (child.getVisibility() != VISIBLE) continue;
            child.getHitRect(mHitRect);
            if (mHitRect.contains((int) x, (int) y)) return child;
        }
        return null;
    }

    private boolean isInsideView(@Nullable View view, float x, float y) {
        if (view == null) return false;
        view.getHitRect(mHitRect);
        return mHitRect.contains((int) x, (int) y);
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

                // In MOVE mode, skip swipe detection — just track for drag destination
                if (mEditorMode == EditorMode.MOVE) return true;

                int pointerIndex = ev.findPointerIndex(mActivePointerId);
                if (pointerIndex < 0) return true;

                float x = ev.getX(pointerIndex);
                float y = ev.getY(pointerIndex);
                float dx = x - mDownX;
                float dy = y - mDownY;

                SwipeDirection direction = detectDirection(dx, dy);
                if (direction != null) {
                    mEditorSwipeDir = direction;
                    invalidate();
                    fireSwipe(mActiveChild, direction);
                }
                return true;
            }

            case MotionEvent.ACTION_UP: {
                if (mActivePointerId == INVALID_POINTER_ID) return true;

                // In MOVE mode, resolve drop target and fire move listener
                if (mEditorMode == EditorMode.MOVE) {
                    handleEditorMoveUp(ev);
                    resetTouchState();
                    return true;
                }

                int pointerIndex = ev.findPointerIndex(mActivePointerId);
                if (pointerIndex >= 0 && !mGestureConsumed) {
                    float x = ev.getX(pointerIndex);
                    float y = ev.getY(pointerIndex);
                    float dx = x - mDownX;
                    float dy = y - mDownY;

                    SwipeDirection direction = detectDirection(dx, dy);
                    if (direction != null) {
                        mEditorSwipeDir = direction;
                        invalidate();
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
                resetTouchState();
                return true;
            }

            case MotionEvent.ACTION_POINTER_UP: {
                int pointerIndexAction = ev.getActionIndex();
                int pointerId = ev.getPointerId(pointerIndexAction);
                if (pointerId == mActivePointerId) {
                    resetTouchState();
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

    /** Resolve drop target on ACTION_UP in MOVE mode and fire EditorMoveListener. */
    private void handleEditorMoveUp(@NonNull MotionEvent ev) {
        if (mActiveChild == null || mEditorMoveListener == null) return;

        Object srcTag = mActiveChild.getTag();
        if (!(srcTag instanceof int[]) || ((int[]) srcTag).length < 2) return;
        int fromRow = ((int[]) srcTag)[0];
        int fromCol = ((int[]) srcTag)[1];

        int pointerIndex = ev.findPointerIndex(mActivePointerId);
        if (pointerIndex < 0) return;

        View target = findChildAt(ev.getX(pointerIndex), ev.getY(pointerIndex));
        if (target == null || target == mActiveChild) return;

        Object dstTag = target.getTag();
        if (!(dstTag instanceof int[]) || ((int[]) dstTag).length < 2) return;
        int toRow = ((int[]) dstTag)[0];
        int toCol = ((int[]) dstTag)[1];

        if (fromRow == toRow && fromCol == toCol) return;

        final int fR = fromRow, fC = fromCol, tR = toRow, tC = toCol;
        mActiveChild.post(() -> {
            if (!isAttachedToWindow() || mEditorMoveListener == null) return;
            mEditorMoveListener.onCellMove(fR, fC, tR, tC);
        });
    }

    private void resetTouchState() {
        if (mActiveChild != null) {
            mActiveChild.setPressed(false);
        }
        mActiveChild = null;
        mActivePointerId = INVALID_POINTER_ID;
        mGestureConsumed = false;
        mEditorSwipeDir = null;

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

    /**
     * Truncate a macro button's display text at bind boundaries.
     * Returns null if the full text fits (no truncation needed).
     * Returns a shortened string with "+..." suffix if truncation is needed.
     */
    private static String truncateMacroText(String fullText, TextPaint paint, int widthPx, int maxLines) {
        String[] binds = fullText.split(" ");
        if (binds.length <= 1) return null;

        // Check if full text already fits
        if (fitsInLines(fullText, paint, widthPx, maxLines)) {
            return null;
        }

        // Linear scan from largest to smallest (optimal for typical 2-10 bind macros)
        for (int n = binds.length - 1; n >= 1; n--) {
            String candidate = joinBinds(binds, n) + "+...";
            if (fitsInLines(candidate, paint, widthPx, maxLines)) {
                return candidate;
            }
        }

        // Even first bind + "+..." doesn't fit — let Android handle truncation
        return null;
    }

    /**
     * Check if text fits within the given width in the given number of lines using StaticLayout.
     */
    private static boolean fitsInLines(String text, TextPaint paint, int widthPx, int maxLines) {
        if (widthPx <= 0 || maxLines <= 0 || text == null || text.isEmpty()) return true;
        StaticLayout layout;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            layout = StaticLayout.Builder.obtain(text, 0, text.length(), paint, widthPx)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(0f, 1f)
                .setIncludePad(false)
                .build();
        } else {
            layout = new StaticLayout(text, paint, widthPx, Layout.Alignment.ALIGN_NORMAL, 1f, 0f, false);
        }
        return layout.getLineCount() <= maxLines;
    }

    /**
     * Join the first N binds with spaces.
     */
    private static String joinBinds(String[] binds, int count) {
        StringBuilder sb = new StringBuilder();
        int n = Math.min(count, binds.length);
        for (int i = 0; i < n; i++) {
            if (i > 0) sb.append(' ');
            sb.append(binds[i]);
        }
        return sb.toString();
    }
}

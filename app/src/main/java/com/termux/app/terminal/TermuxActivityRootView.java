package com.termux.app.terminal;

import android.content.Context;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.WindowInsets;
import android.widget.LinearLayout;

import androidx.annotation.Nullable;
import androidx.core.view.WindowInsetsCompat;

import com.termux.app.TermuxActivity;
import com.termux.shared.logger.Logger;
import com.termux.shared.view.ViewUtils;


/**
 * The {@link TermuxActivity} relies on {@link android.view.WindowManager.LayoutParams#SOFT_INPUT_ADJUST_RESIZE)}
 * set by {@link TermuxTerminalViewClient#setSoftKeyboardState(boolean, boolean)} to automatically
 * resize the view and push the terminal up when soft keyboard is opened. However, this does not
 * always work properly. When `enforce-char-based-input=true` is set in `termux.properties`
 * and {@link com.termux.view.TerminalView#onCreateInputConnection(EditorInfo)} sets the inputType
 * to `InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS`
 * instead of the default `InputType.TYPE_NULL` for termux, some keyboards may still show suggestions.
 * Gboard does too, but only when text is copied and clipboard suggestions **and** number keys row
 * toggles are enabled in its settings. When number keys row toggle is not enabled, Gboard will still
 * show the row but will switch it with suggestions if needed. If its enabled, then number keys row
 * is always shown and suggestions are shown in an additional row on top of it. This additional row is likely
 * part of the candidates view returned by the keyboard app in {@link InputMethodService#onCreateCandidatesView()}.
 *
 * With the above configuration, the additional clipboard suggestions row partially covers the
 * extra keys/terminal. Reopening the keyboard/activity does not fix the issue. This is either a bug
 * in the Android OS where it does not consider the candidate's view height in its calculation to push
 * up the view or because Gboard does not include the candidate's view height in the height reported
 * to android that should be used, hence causing an overlap.
 *
 * Gboard logs the following entry to `logcat` when its opened with or without the suggestions bar showing:
 * I/KeyboardViewUtil: KeyboardViewUtil.calculateMaxKeyboardBodyHeight():62 leave 500 height for app when screen height:2392, header height:176 and isFullscreenMode:false, so the max keyboard body height is:1716
 * where `keyboard_height = screen_height - height_for_app - header_height` (62 is a hardcoded value in Gboard source code and may be a version number)
 * So this may in fact be due to Gboard but https://stackoverflow.com/questions/57567272 suggests
 * otherwise. Another similar report https://stackoverflow.com/questions/66761661.
 * Also check https://github.com/termux/termux-app/issues/1539.
 *
 * To fix these issues, `activity_termux.xml` has the constant 1sp transparent
 * `activity_termux_bottom_space_view` View at the bottom. This will appear as a line matching the
 * activity theme. When {@link TermuxActivity} {@link ViewTreeObserver.OnGlobalLayoutListener} is
 * called when any of the sub view layouts change,  like keyboard opening/closing keyboard,
 * extra keys/input view switched, etc, we check if the bottom space view is visible or not.
 * If its not, then we add a margin to the bottom of the root view, so that the keyboard does not
 * overlap the extra keys/terminal, since the margin will push up the view. By default the margin
 * added is equal to the height of the hidden part of extra keys/terminal. For Gboard's case, the
 * hidden part equals the `header_height`. The updates to margins may cause a jitter in some cases
 * when the view is redrawn if the margin is incorrect, but logic has been implemented to avoid that.
 *
 * The bottom margin is the only source of truth for the layout. It is derived from a single
 * invariant: "the bottom space view must sit at (or above) the bottom of the available window".
 * If it is hidden by `pxHidden` pixels, the margin is `pxHidden`; otherwise it is 0. The margin is
 * re-applied only when it actually changes (within {@code root_view_layout_tolerance} of sensor
 * noise), so a flickering freeform window never triggers a set/reset oscillation of the bottom
 * panel. A genuine change (keyboard shown/hidden, real resize, split divider moved) is always far
 * larger than the tolerance and is applied immediately.
 */
public class TermuxActivityRootView extends LinearLayout implements ViewTreeObserver.OnGlobalLayoutListener {

    public TermuxActivity mActivity;

    /** Log root view events. */
    private boolean ROOT_VIEW_LOGGING_ENABLED = false;

    private static final String LOG_TAG = "TermuxActivityRootView";

    private static int mStatusBarHeight;

    // Layout tolerance (px), loaded lazily from @dimen/root_view_layout_tolerance. Any difference
    // between the currently applied bottom margin and the freshly measured one that is smaller than
    // this band is treated as sensor noise (the system window rect flickers a few px per frame) and
    // ignored, so the margin is not recomputed/re-applied every frame.
    private int mLayoutTolerancePx = -1;


    public TermuxActivityRootView(Context context) {
        super(context);
    }

    public TermuxActivityRootView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public TermuxActivityRootView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public void setActivity(TermuxActivity activity) {
        mActivity = activity;
    }

    /**
     * Sets whether root view logging is enabled or not.
     *
     * @param value The boolean value that defines the state.
     */
    public void setIsRootViewLoggingEnabled(boolean value) {
        ROOT_VIEW_LOGGING_ENABLED = value;
    }

    @Override
    public void onGlobalLayout() {
        if (mActivity == null || !mActivity.isVisible()) return;

        View bottomSpaceView = mActivity.getTermuxActivityBottomSpaceView();
        if (bottomSpaceView == null) return;

        boolean root_view_logging_enabled = ROOT_VIEW_LOGGING_ENABLED;

        if (root_view_logging_enabled)
            Logger.logVerbose(LOG_TAG, ":\nonGlobalLayout:");

        ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) getLayoutParams();

        // Get the position Rects of the bottom space view and the main window holding it
        Rect[] windowAndViewRects = ViewUtils.getWindowAndViewRects(bottomSpaceView, mStatusBarHeight);
        if (windowAndViewRects == null)
            return;

        Rect windowAvailableRect = windowAndViewRects[0];
        Rect bottomSpaceViewRect = windowAndViewRects[1];

        if (mLayoutTolerancePx < 0) {
            mLayoutTolerancePx = Math.round(getResources().getDimensionPixelSize(
                    com.termux.R.dimen.root_view_layout_tolerance));
        }

        if (root_view_logging_enabled) {
            Logger.logVerbose(LOG_TAG, "windowAvailableRect " + ViewUtils.toRectString(windowAvailableRect) + ", bottomSpaceViewRect " + ViewUtils.toRectString(bottomSpaceViewRect));
            Logger.logVerbose(LOG_TAG, "windowAvailableRect.bottom " + windowAvailableRect.bottom +
                ", bottomSpaceViewRect.bottom " + bottomSpaceViewRect.bottom +
                ", diff " + (bottomSpaceViewRect.bottom - windowAvailableRect.bottom) + ", bottom " + params.bottomMargin +
                ", isRectAbove " + ViewUtils.isRectAbove(windowAvailableRect, bottomSpaceViewRect));
        }

        // Single invariant: the bottom space view must not be hidden behind the window bottom.
        // pxHidden > 0  -> view is hidden, push it up by that many px (margin).
        // pxHidden <= 0 -> view is fully visible, no margin needed.
        int pxHidden = bottomSpaceViewRect.bottom - windowAvailableRect.bottom;
        if (pxHidden < 0) pxHidden = 0;

        int currentMargin = params.bottomMargin;
        int desiredMargin = pxHidden;

        // Apply only on a real change. Within the tolerance band the measured window rect is just
        // sensor noise (freeform window flicker), so we keep the existing margin and avoid the
        // set/reset feedback loop that previously jittered the bottom panel every frame. A genuine
        // layout change is always larger than the band and still snaps to the new value immediately.
        int delta = Math.abs(desiredMargin - currentMargin);
        if (delta <= mLayoutTolerancePx) {
            if (root_view_logging_enabled)
                Logger.logVerbose(LOG_TAG, "Margin within tolerance of desired (" + currentMargin +
                        " vs " + desiredMargin + ", delta " + delta + " <= " + mLayoutTolerancePx + "), leaving as is");
            return;
        }

        if (root_view_logging_enabled)
            Logger.logVerbose(LOG_TAG, "Setting bottom margin to " + desiredMargin);
        params.setMargins(0, 0, 0, desiredMargin);
        setLayoutParams(params);
    }

    public static class WindowInsetsListener implements View.OnApplyWindowInsetsListener {
        @Override
        public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
            mStatusBarHeight =  WindowInsetsCompat.toWindowInsetsCompat(insets).getInsets(WindowInsetsCompat.Type.statusBars()).top;
            // Let view window handle insets however it wants
            return v.onApplyWindowInsets(insets);
        }
    }

}

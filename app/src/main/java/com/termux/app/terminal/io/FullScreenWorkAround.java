package com.termux.app.terminal.io;

import android.content.res.Resources;
import android.graphics.Rect;
import android.view.View;
import android.view.ViewGroup;

import com.termux.R;
import com.termux.app.TermuxActivity;

/**
 * Work around for fullscreen mode in Termux to fix ExtraKeysView not being visible.
 * This class is derived from:
 * https://stackoverflow.com/questions/7417123/android-how-to-adjust-layout-in-full-screen-mode-when-softkeyboard-is-visible
 * and has some additional tweaks
 * ---
 * For more information, see https://issuetracker.google.com/issues/36911528
 *
 * The only layout reaction here is resizing the content view when the usable display height changes
 * (e.g. soft keyboard shown/hidden). The change is applied only when it is a real one: in small
 * floating / freeform windows the system reports a slightly different usable height every frame
 * (sensor noise from the navigation bar / window decor appearing and disappearing), and re-laying
 * out the content on every such frame both wastes work and amplifies the bottom-panel jitter. Any
 * difference smaller than {@code root_view_layout_tolerance} is therefore treated as noise and
 * ignored; a genuine change (keyboard, real resize) is far larger and still applied immediately.
 */
public class FullScreenWorkAround {
    private final View mChildOfContent;
    private int mUsableHeightPrevious;
    private final ViewGroup.LayoutParams mViewGroupLayoutParams;

    private final int mNavBarHeight;

    // Layout tolerance (px), loaded lazily from @dimen/root_view_layout_tolerance. Sub-band
    // changes of the reported usable height are ignored (see possiblyResizeChildOfContent()).
    private int mLayoutTolerancePx = -1;

    public static void apply(TermuxActivity activity) {
        new FullScreenWorkAround(activity);
    }

    private FullScreenWorkAround(TermuxActivity activity) {
        ViewGroup content = activity.findViewById(android.R.id.content);
        mChildOfContent = content.getChildAt(0);
        mViewGroupLayoutParams = mChildOfContent.getLayoutParams();
        mNavBarHeight = activity.getNavBarHeight();
        mChildOfContent.getViewTreeObserver().addOnGlobalLayoutListener(this::possiblyResizeChildOfContent);
    }

    private void possiblyResizeChildOfContent() {
        int usableHeightNow = computeUsableHeight();

        if (mLayoutTolerancePx < 0)
            mLayoutTolerancePx = Math.round(mChildOfContent.getResources().getDimensionPixelSize(
                    R.dimen.root_view_layout_tolerance));

        // Ignore sensor noise: a flickering freeform window reports a slightly different usable
        // height every frame. Without this guard we would requestLayout() on every such frame,
        // which both wastes work and feeds the bottom-panel jitter. Genuine changes (keyboard
        // shown/hidden, real resize) move the frame by far more than the band and are applied.
        if (mUsableHeightPrevious != 0
                && Math.abs(usableHeightNow - mUsableHeightPrevious) <= mLayoutTolerancePx) {
            return;
        }

        int usableHeightSansKeyboard = mChildOfContent.getRootView().getHeight();
        int heightDifference = usableHeightSansKeyboard - usableHeightNow;
        if (heightDifference > (usableHeightSansKeyboard / 4)) {
            // keyboard probably just became visible

            // ensures that usable layout space does not extend behind the
            // soft keyboard, causing the extra keys to not be visible
            mViewGroupLayoutParams.height = (usableHeightSansKeyboard - heightDifference) + getNavBarHeight();
        } else {
            // keyboard probably just became hidden
            mViewGroupLayoutParams.height = usableHeightSansKeyboard;
        }
        mChildOfContent.requestLayout();
        mUsableHeightPrevious = usableHeightNow;
    }

    private int getNavBarHeight() {
        return mNavBarHeight;
    }

    private int computeUsableHeight() {
        Rect r = new Rect();
        mChildOfContent.getWindowVisibleDisplayFrame(r);
        return (r.bottom - r.top);
    }

}

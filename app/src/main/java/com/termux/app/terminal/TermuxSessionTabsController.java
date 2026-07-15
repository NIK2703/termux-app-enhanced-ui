package com.termux.app.terminal;

import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.shared.termux.shell.command.runner.terminal.TermuxSession;
import com.termux.terminal.TerminalSession;

import java.util.List;

public class TermuxSessionTabsController {

    private final TermuxActivity mActivity;
    private final LinearLayout mTabsContainer;
    private final HorizontalScrollView mTabsScroll;
    private int mCurrentSessionIndex = -1;

    // Last terminal scheme colors applied via applySchemeColorsToTabs(). Stored so that tabs
    // created later in updateTabs() (e.g. a freshly opened session) are colored from the
    // Termux:Style scheme too, instead of the hardcoded layout default color.
    private int mSchemeTextColor = 0xFF000000;
    private int mSchemeBg = 0x4D000000;
    private int mSchemeBgActive = 0x80000000;
    private boolean mSchemeApplied = false;

    public TermuxSessionTabsController(TermuxActivity activity) {
        this.mActivity = activity;
        this.mTabsContainer = activity.findViewById(R.id.session_tabs);
        this.mTabsScroll = activity.findViewById(R.id.session_tabs_scroll);
    }

    /** Returns tab height in pixels based on the user's tab-height preference. */
    private int getTabHeightPx() {
        String mode = mActivity.getSharedPreferences("termux_prefs", android.content.Context.MODE_PRIVATE)
                .getString("tab_height_mode", "single");
        int dimenId = "double".equals(mode)
                ? R.dimen.terminal_tab_height_double
                : R.dimen.terminal_tab_height_single;
        return Math.round(mActivity.getResources().getDimension(dimenId));
    }

    /** Whether the tab title should be limited to one line. */
    private boolean isSingleLineMode() {
        return !"double".equals(mActivity.getSharedPreferences("termux_prefs",
                android.content.Context.MODE_PRIVATE).getString("tab_height_mode", "single"));
    }

    /** Re-apply height, max-lines and start padding to all existing tab views. */
    public void applyTabHeightMode() {
        if (mTabsContainer == null) return;
        int heightPx = getTabHeightPx();
        boolean singleLine = isSingleLineMode();
        int padStartDimenId = singleLine
                ? R.dimen.terminal_tab_padding_start_single
                : R.dimen.terminal_tab_padding_start_double;
        int padStartPx = Math.round(mActivity.getResources().getDimension(padStartDimenId));
        for (int i = 0; i < mTabsContainer.getChildCount() - 1; i++) {
            View tabView = mTabsContainer.getChildAt(i);
            ViewGroup.LayoutParams lp = tabView.getLayoutParams();
            lp.height = heightPx;
            tabView.setLayoutParams(lp);
            tabView.setPadding(padStartPx,
                    tabView.getPaddingTop(),
                    tabView.getPaddingRight(),
                    tabView.getPaddingBottom());
            TextView title = tabView.findViewById(R.id.session_tab_title);
            if (title != null) {
                title.setMaxLines(singleLine ? 1 : 2);
            }
        }
    }

    public void updateTabs(List<TermuxSession> sessions) {
        if (mTabsContainer == null) return;

        TerminalSession currentSession = mActivity.getCurrentSession();
        int currentSessionIndex = -1;
        for (int i = 0; i < sessions.size(); i++) {
            TermuxSession s = sessions.get(i);
            if (s != null && s.getTerminalSession() == currentSession) {
                currentSessionIndex = i;
                break;
            }
        }

        // The add-tab button is the LAST child of mTabsContainer, so the real
        // session count is always childCount - 1.
        int sessionCount = mTabsContainer.getChildCount() - 1;
        int newCount = sessions.size();

        if (newCount > sessionCount) {
            // Add new tab views immediately before the add button. Existing views
            // (and the add button) stay in place, scrollX preserved.
            for (int i = sessionCount; i < newCount; i++) {
                TermuxSession termuxSession = sessions.get(i);
                View tabView = createTabView(termuxSession, i, i == currentSessionIndex);
                mTabsContainer.addView(tabView, mTabsContainer.getChildCount() - 1);
            }
        } else if (newCount < sessionCount) {
            // Remove trailing tab views (not the add button). No full wipe, scrollX preserved.
            mTabsContainer.removeViews(newCount, sessionCount - newCount);
        }

        // Update titles, colors and selection state on ALL tabs in-place.
        // This covers the equal-count case (no structural change) and also syncs the
        // newly-added tabs above. No removeAllViews(), so the HorizontalScrollView keeps
        // its current scrollX. Skips the add button (last child).
        for (int i = 0; i < mTabsContainer.getChildCount() - 1 && i < newCount; i++) {
            TermuxSession termuxSession = sessions.get(i);
            View tabView = mTabsContainer.getChildAt(i);
            populateTabView(tabView, termuxSession, i, i == currentSessionIndex);
        }

        // Always scroll to the active tab after the rebuild.  The post from
        // scrollToTab() uses last-call-wins (removeCallbacks + Runnable field),
        // so setCurrentSession() calling scrollToTab() first and updateTabs()
        // calling it right after does NOT queue two competing scrolls.
        mCurrentSessionIndex = currentSessionIndex;
        if (currentSessionIndex >= 0) {
            scrollToTab(currentSessionIndex);
        }
    }

    /**
     * Inflate a brand-new tab view.  The caller is responsible for populating it
     * (populateTabView is called at the end).
     */
    private View createTabView(TermuxSession termuxSession, int position, boolean isSelected) {
        LayoutInflater inflater = LayoutInflater.from(mActivity);
        View tabView = inflater.inflate(R.layout.item_session_tab, mTabsContainer, false);

        // Apply the configured tab height (compact single-line vs tall two-line).
        ViewGroup.LayoutParams lp = tabView.getLayoutParams();
        lp.height = getTabHeightPx();
        tabView.setLayoutParams(lp);

        // Populate text, colors, selection state and (re)bind click listeners.
        // populateTabView() always attaches click listeners to the view with the
        // correct session reference, so we do NOT set them here — doing so would
        // be immediately overwritten anyway.
        populateTabView(tabView, termuxSession, position, isSelected);
        return tabView;
    }

    /**
     * Populate or refresh an existing tab view's content (title, colours, selection state,
     * close button visibility) and rebind click listeners to the current session.
     *
     * <p>Click listeners are <b>always</b> re-attached here because updateTabs() reuses
     * tab views by position: when a middle session is closed the views shift down, and
     * without rebinding the old listener's closure would still reference the (dead)
     * session that was at this position when the view was <em>created</em>.</p>
     */
    private void populateTabView(View tabView, TermuxSession termuxSession, int position, boolean isSelected) {
        TextView titleView = tabView.findViewById(R.id.session_tab_title);
        ImageButton closeButton = tabView.findViewById(R.id.session_tab_close);
        if (titleView == null) return;

        // Apply the configured max lines (single-line or two-line mode).
        titleView.setMaxLines(isSingleLineMode() ? 1 : 2);

        // Tighter start padding for single-line (compact) tabs; keep the XML default
        // (12dp) for double-line tabs so the title has room for two lines.
        if (isSingleLineMode()) {
            int padStart = Math.round(mActivity.getResources()
                    .getDimension(R.dimen.terminal_tab_padding_start_single));
            tabView.setPadding(padStart, tabView.getPaddingTop(),
                    tabView.getPaddingRight(), tabView.getPaddingBottom());
        } else {
            int padStart = Math.round(mActivity.getResources()
                    .getDimension(R.dimen.terminal_tab_padding_start_double));
            tabView.setPadding(padStart, tabView.getPaddingTop(),
                    tabView.getPaddingRight(), tabView.getPaddingBottom());
        }

        TerminalSession terminalSession = termuxSession.getTerminalSession();

        // Re-bind the click listener to the current (correct) session reference.
        // updateTabs() reuses existing tab views by position — without this the
        // closure would still point at the session that was at this position
        // when the view was *created*, not the one that sits here NOW.  That is
        // why closing a non-last tab made the tab strip show the right title
        // but clicking it tried to switch to a dead session (stale listener).
        tabView.setOnClickListener(v -> {
            if (terminalSession != null)
                mActivity.getTermuxTerminalSessionClient().setCurrentSession(terminalSession, false);
        });
        tabView.setOnLongClickListener(v -> {
            if (terminalSession != null)
                mActivity.getTermuxTerminalSessionClient().renameSession(terminalSession);
            return true;
        });
        if (closeButton != null) {
            closeButton.setOnClickListener(v -> {
                if (terminalSession != null)
                    closeSession(terminalSession);
            });
        }

        if (terminalSession != null) {
            String name = terminalSession.mSessionName;
            String title = terminalSession.getTitle();

            String displayTitle = (name != null && !name.isEmpty()) ? name :
                                  (title != null && !title.isEmpty()) ? title : mActivity.getString(R.string.session_default_title);

            titleView.setText(displayTitle);

            // Text colour: red for finished-with-error sessions, otherwise scheme foreground.
            boolean sessionRunning = terminalSession.isRunning();
            if (!sessionRunning && terminalSession.getExitStatus() != 0) {
                int errorColor = androidx.core.content.ContextCompat.getColor(
                    mActivity, com.termux.shared.R.color.terminal_tab_text_error);
                titleView.setTextColor(errorColor);
            } else if (mSchemeApplied) {
                titleView.setTextColor(mSchemeTextColor);
            }

            // Close-icon colour from the scheme.
            if (closeButton != null && mSchemeApplied) {
                closeButton.setColorFilter(mSchemeTextColor, android.graphics.PorterDuff.Mode.SRC_ATOP);
            }

            // Translucent background matching the signal panel.
            if (mSchemeApplied) {
                tabView.setBackgroundTintList(ColorStateList.valueOf(
                        isSelected ? mSchemeBgActive : mSchemeBg));
            }

            // Strike through for finished sessions.
            if (!sessionRunning) {
                titleView.setPaintFlags(titleView.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG);
            } else {
                titleView.setPaintFlags(titleView.getPaintFlags() & ~Paint.STRIKE_THRU_TEXT_FLAG);
            }
        }

        // Selection state and close button visibility.
        tabView.setSelected(isSelected);
        if (closeButton != null) {
            closeButton.setVisibility(isSelected ? View.VISIBLE : View.INVISIBLE);
        }
    }

    /**
     * Close a terminal session - finish it if running and then remove
     */
    private void closeSession(TerminalSession session) {
        if (session == null) return;
        
        // Finish the session by sending SIGKILL - onSessionFinished callback will handle removal
        session.finishIfRunning();
    }

    /** Index scheduled for the next scroll runnable. */
    private int mPendingTabScrollIndex = -1;

    /**
     * Last-call-wins scroll to the tab at {@code index}.  If a scroll is already
     * queued (from a caller that is about to call again — e.g. addNewSession →
     * setCurrentSession + updateTabs both call scrollToTab), the earlier one is
     * cancelled and only the final target survives.  This prevents the animation
     * fighting that made the tab strip look abrupt/jerky.
     */
    private void scrollToTab(int index) {
        if (mTabsContainer == null || mTabsScroll == null) return;
        if (index < 0 || index >= mTabsContainer.getChildCount() - 1) return;

        final View tabView = mTabsContainer.getChildAt(index);
        if (tabView == null) return;

        mPendingTabScrollIndex = index;
        mTabsScroll.removeCallbacks(mTabScrollRunnable);
        mTabsScroll.post(mTabScrollRunnable);
    }

    private final Runnable mTabScrollRunnable = new Runnable() {
        @Override
        public void run() {
            final int idx = mPendingTabScrollIndex;
            if (idx < 0 || idx >= mTabsContainer.getChildCount() - 1) return;
            final View tabView = mTabsContainer.getChildAt(idx);
            if (tabView == null) return;
            int scrollX = tabView.getLeft() - mTabsScroll.getWidth() / 2 + tabView.getWidth() / 2;
            mTabsScroll.smoothScrollTo(scrollX, 0);
        }
    };

    /**
     * Re-apply the terminal color scheme to every existing tab view: text/close-icon color and a
     * translucent background matching the signal panel. Called from
     * {@code TermuxTerminalSessionActivityClient.applyPanelColors()} whenever the scheme changes.
     *
     * @param textColor  Foreground color derived from the scheme (contrast vs background).
     * @param bg         Translucent button background (dark for light schemes, light for dark).
     * @param bgActive   Translucent background for the selected tab.
     */
    public void applySchemeColorsToTabs(int textColor, int bg, int bgActive) {
        // Remember the scheme colors so tabs created later in updateTabs() are colored too.
        mSchemeTextColor = textColor;
        mSchemeBg = bg;
        mSchemeBgActive = bgActive;
        mSchemeApplied = true;

        if (mTabsContainer == null) return;
        int errorColor = androidx.core.content.ContextCompat.getColor(
                mActivity, com.termux.shared.R.color.terminal_tab_text_error);
        for (int i = 0; i < mTabsContainer.getChildCount() - 1; i++) {
            View tabView = mTabsContainer.getChildAt(i);
            TextView title = tabView.findViewById(R.id.session_tab_title);
            ImageButton close = tabView.findViewById(R.id.session_tab_close);
            // Error (finished-with-exit) tabs keep their red color; normal tabs use the scheme fg.
            if (title != null && title.getCurrentTextColor() != errorColor) {
                title.setTextColor(textColor);
            }
            if (close != null) {
                close.setColorFilter(textColor, android.graphics.PorterDuff.Mode.SRC_ATOP);
            }
            tabView.setBackgroundTintList(ColorStateList.valueOf(
                    tabView.isSelected() ? bgActive : bg));
        }
    }

    public void setCurrentSession(int index) {
        if (mTabsContainer == null) return;
        if (index < 0 || index >= mTabsContainer.getChildCount() - 1) return;

        // Update selection state and close button visibility for all tabs
        for (int i = 0; i < mTabsContainer.getChildCount() - 1; i++) {
            View child = mTabsContainer.getChildAt(i);
            boolean isSelected = (i == index);
            child.setSelected(isSelected);

            // Re-tint the background to follow the selection: selected tab uses the active
            // control background, the rest use the normal control background (same colors as the
            // other bottom-panel controls). Only once the scheme colors have been applied.
            if (mSchemeApplied) {
                child.setBackgroundTintList(ColorStateList.valueOf(
                        isSelected ? mSchemeBgActive : mSchemeBg));
            }

            ImageButton closeButton = child.findViewById(R.id.session_tab_close);
            if (closeButton != null) {
                closeButton.setVisibility(isSelected ? View.VISIBLE : View.INVISIBLE);
            }
        }

        mCurrentSessionIndex = index;
        scrollToTab(index);
    }

    // ── Intermediate page-scroll support ──────────────────────────────────

    /**
     * Called during a horizontal page swipe ({@code onPageScrolled} from ViewPager2).
     * Interpolates the tab strip scroll position and the selection background of the
     * two adjacent tabs so the user sees a smooth transition between tabs rather than
     * an abrupt snap.
     *
     * @param position       the index of the left (leaving) page
     * @param positionOffset fraction from 0.0 (left page fully visible) to 1.0 (right
     *                       page fully visible)
     */
    public void onPageScrolled(int position, float positionOffset) {
        if (mTabsContainer == null || mTabsScroll == null) return;
        if (!mSchemeApplied) return;

        int leftIdx = position;
        int rightIdx = position + 1;

        // At offset 0 there is no transition, and at the very first frame of a
        // swipe the blend would be a no-op anyway (ratio ≈ 0). Guard to stay
        // efficient, but allow offset up to 1.0 so the final frame of a
        // cancelled gesture is full-strength.
        if (positionOffset <= 0f) return;
        if (leftIdx < 0 || rightIdx >= mTabsContainer.getChildCount() - 1) return;

        View leftTab = mTabsContainer.getChildAt(leftIdx);
        View rightTab = mTabsContainer.getChildAt(rightIdx);
        if (leftTab == null || rightTab == null) return;

        // 1. Interpolate the HorizontalScrollView scroll position so the tab
        //    strip follows the finger during the swipe.
        int leftCenter = leftTab.getLeft() + leftTab.getWidth() / 2;
        int rightCenter = rightTab.getLeft() + rightTab.getWidth() / 2;
        int interpolatedCenter = (int) (leftCenter + (rightCenter - leftCenter) * positionOffset);
        int targetScrollX = interpolatedCenter - mTabsScroll.getWidth() / 2;
        mTabsScroll.scrollTo(targetScrollX, 0);

        // 2. Interpolate background tints: left tab fades from active → normal,
        //    right tab fades from normal → active.
        int blendedLeft = blendColors(mSchemeBgActive, mSchemeBg, positionOffset);
        int blendedRight = blendColors(mSchemeBg, mSchemeBgActive, positionOffset);
        leftTab.setBackgroundTintList(ColorStateList.valueOf(blendedLeft));
        rightTab.setBackgroundTintList(ColorStateList.valueOf(blendedRight));

        // 3. Show the close button on whichever tab the user is closer to.
        ImageButton leftClose = leftTab.findViewById(R.id.session_tab_close);
        ImageButton rightClose = rightTab.findViewById(R.id.session_tab_close);
        boolean closerToLeft = positionOffset < 0.5f;
        if (leftClose != null) leftClose.setVisibility(closerToLeft ? View.VISIBLE : View.INVISIBLE);
        if (rightClose != null) rightClose.setVisibility(closerToLeft ? View.INVISIBLE : View.VISIBLE);
    }

    /**
     * Reset the visual tab selection state to a clean, non-interpolated state for
     * the given {@code currentIndex}.  Called from
     * {@code onPageScrollStateChanged(IDLE)} to clean up any intermediate blending
     * left by {@link #onPageScrolled} when a swipe gesture was cancelled (the
     * user started swiping then released back to the original page, which does
     * <b>not</b> fire {@code onPageSelected}).
     * <p>
     * This mirrors what {@link #setCurrentSession} does for selection state and
     * background tint, but without calling {@code smoothScrollTo} (which would
     * fight the already-correct scroll position).
     */
    public void resetPageSelection(int currentIndex) {
        if (mTabsContainer == null) return;
        for (int i = 0; i < mTabsContainer.getChildCount() - 1; i++) {
            View child = mTabsContainer.getChildAt(i);
            boolean isSelected = (i == currentIndex);
            child.setSelected(isSelected);
            if (mSchemeApplied) {
                child.setBackgroundTintList(ColorStateList.valueOf(
                        isSelected ? mSchemeBgActive : mSchemeBg));
            }
            ImageButton closeButton = child.findViewById(R.id.session_tab_close);
            if (closeButton != null) {
                closeButton.setVisibility(isSelected ? View.VISIBLE : View.INVISIBLE);
            }
        }
        mCurrentSessionIndex = currentIndex;
    }

    /**
     * Linearly interpolate between two ARGB colours.
     *
     * @param from  colour at ratio = 0.0
     * @param to    colour at ratio = 1.0
     * @param ratio blend factor in [0, 1]
     */
    private static int blendColors(int from, int to, float ratio) {
        int a = (int) (Color.alpha(from) * (1f - ratio) + Color.alpha(to) * ratio);
        int r = (int) (Color.red(from) * (1f - ratio) + Color.red(to) * ratio);
        int g = (int) (Color.green(from) * (1f - ratio) + Color.green(to) * ratio);
        int b = (int) (Color.blue(from) * (1f - ratio) + Color.blue(to) * ratio);
        return Color.argb(a, r, g, b);
    }
}

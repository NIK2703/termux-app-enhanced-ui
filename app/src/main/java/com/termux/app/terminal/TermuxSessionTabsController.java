package com.termux.app.terminal;
import android.animation.Animator;

import android.animation.AnimatorListenerAdapter;
import android.text.TextUtils;
import android.animation.LayoutTransition;
import android.animation.ObjectAnimator;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

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

    /**
     * False until the strip has been built at least once. The very first updateTabs() after a
     * cold start (Activity finished via BACK and reopened) must reveal the RESTORED active tab,
     * not pin the strip to the right end — so we distinguish "initial population" from a
     * user-initiated tab add (which legitimately scrolls to the end).
     */
    private boolean mBuilt = false;

    // Last terminal scheme colors applied via applySchemeColorsToTabs(). Stored so that tabs
    // created later in updateTabs() (e.g. a freshly opened session) are colored from the
    // Termux:Style scheme too, instead of the hardcoded layout default color.
    private int mSchemeTextColor = 0xFF000000;
    private int mSchemeBg = 0x4D000000;
    private int mSchemeBgActive = 0x80000000;
    private boolean mSchemeApplied = false;

    /** Whether the trailing placeholder page is currently present (for tab-strip blending). */
    private boolean mPlaceholderActive = false;

    private enum TabScrollMode { IDLE, FOLLOW_PAGER, ANIMATE_TO_TAB }
    private TabScrollMode mScrollMode = TabScrollMode.IDLE;
    private android.animation.ValueAnimator mTabAnimator = null;
    private boolean mEndScrollActive = false;
    private Runnable mPendingEndScroll = null;
    private static final long END_SCROLL_DELAY_MS = 50;
    private android.animation.ValueAnimator mEndScrollAnim = null;

    public TermuxSessionTabsController(TermuxActivity activity) {
        this.mActivity = activity;
        this.mTabsContainer = activity.findViewById(R.id.session_tabs);
        this.mTabsScroll = activity.findViewById(R.id.session_tabs_scroll);

        // Enable the CHANGING layout transition only: when a tab is removed the tabs to its right
        // glide smoothly into the freed space (and our own close animation drives the width). We do
        // NOT enable APPEARING/CHANGE_APPEARING — those animate a newly-added tab's width 0->full,
        // which makes the scrollable width unstable for ~220ms after an add and was the root cause of
        // the janky/under-scrolling end-scroll. Adding a tab at its full measured width lets the
        // strip geometry settle within one layout pass, so the right-end scroll reads correct widths.
        LayoutTransition transition = new LayoutTransition();
        transition.enableTransitionType(LayoutTransition.CHANGING);
        transition.disableTransitionType(LayoutTransition.APPEARING);
        transition.disableTransitionType(LayoutTransition.CHANGE_APPEARING);
        transition.disableTransitionType(LayoutTransition.DISAPPEARING);
        transition.disableTransitionType(LayoutTransition.CHANGE_DISAPPEARING);
        transition.setDuration(220);
        transition.setInterpolator(LayoutTransition.CHANGING,
                new AccelerateDecelerateInterpolator());
        mTabsContainer.setLayoutTransition(transition);
    }

    private int getTabCount() {
        if (mTabsContainer == null) return 0;
        return mTabsContainer.getChildCount() - 1; // последний child — это (+) кнопка, не таб
    }

    @Nullable
    private View getTabAt(int index) {
        if (mTabsContainer == null) return null;
        return mTabsContainer.getChildAt(index);
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
        for (int i = 0; i < getTabCount(); i++) {
            View tabView = getTabAt(i);
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
            // A tab was closed — any sticky end-scroll from a previous add no longer applies.
            mEndScrollActive = false;
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

        // Scroll after the rebuild, through the single-owner requestScroll() (last-call-wins, so a
        // centre request from setCurrentSession() and this end request can never run two competing
        // smoothScrollTo()s in one frame).
        mCurrentSessionIndex = currentSessionIndex;
        if (!mBuilt) {
            // FIRST build (cold start after the app was finished via BACK, or a fresh launch).
            // Smoothly scroll the strip so the restored active tab is centred/visible. We go through
            // requestScroll(SCROLL_CENTRE) so the geometry is measured only AFTER the container is
            // laid out (deferred OnGlobalLayoutListener) — this fixes the old cold-start bug where a
            // plain post()'d centre read tabView.getLeft() before layout (geometry 0) and no-op'd to
            // scrollX=0, leaving the active tab off-screen. A user-initiated add (next branch) still
            // scrolls to the right end via scrollStripToEnd().
            final int firstBuildIdx = currentSessionIndex;
            mTabsScroll.post(() -> snapToTabCenter(firstBuildIdx));
            mBuilt = true;
        } else if (newCount > sessionCount) {
            // A new tab was just added: scroll the strip to its absolute right end so the new tab
            // AND the trailing (+) button are fully revealed. Both add paths (the (+) button tap and
            // the right-swipe placeholder commit) funnel through here, so this single call covers
            // them both. scrollStripToEnd() measures geometry only after the container is laid out
            // (see runEndScroll), so it always reaches the true right edge — no under-scroll.
            scrollStripToEnd();
        } else if (currentSessionIndex >= 0) {
            // No size change (e.g. a title-only refresh after the shell sets its window title via
            // OSC). If we are still in the sticky end-scroll from a just-added tab, do NOT recentre
            // — that would yank the strip back from the right end. A real tab switch goes through
            // setCurrentSession(), which clears mEndScrollActive first.
            if (!mEndScrollActive) snapToTabCenter(currentSessionIndex);
        }

        // Hide the (+) add-tab button once the terminal session limit is reached,
        // and restore it whenever a slot frees up (a tab is closed).
        updateAddButtonVisibility(sessions.size());
    }

    /**
     * Show or hide the trailing (+) add-tab button based on the number of open sessions.
     * Once {@link TermuxTerminalSessionActivityClient#MAX_SESSIONS} is reached there is
     * nothing left to add, so the button is hidden; it reappears as soon as a slot frees up.
     */
    public void updateAddButtonVisibility(int sessionCount) {
        if (mTabsContainer == null) return;
        View addBtn = mTabsContainer.findViewById(R.id.new_session_tab_button);
        if (addBtn == null) return;
        // Use GONE once the session limit is reached so the button's footprint (width + marginEnd)
        // is released and no dead trailing gap is left in the strip. This is safe now that the
        // end-scroll is a self-driven ValueAnimator calling scrollTo() to a fixed target (no live
        // HSV re-clamp), and the target is measured in endScrollLayout AFTER the button has already
        // been hidden — so the strip scrolls exactly to the last real tab with no extra offset.
        addBtn.setVisibility(sessionCount >= TermuxTerminalSessionActivityClient.MAX_SESSIONS
                ? View.GONE : View.VISIBLE);
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

        // Bind the session reference onto the view so closeSession() can locate
        // the exact tab to animate without relying on positional index (which
        // shifts as other tabs open/close).
        tabView.setTag(R.id.session_tab_session_tag, terminalSession);

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

            String displayTitle = SessionTitleUtils.resolveDisplayName(mActivity, name, title);

            if (!TextUtils.equals(titleView.getText(), displayTitle)) {
                titleView.setText(displayTitle);
            }

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
                setTabBackground(tabView, isSelected ? mSchemeBgActive : mSchemeBg);
            }

            // Strike through for finished sessions.
            SessionAppearanceUtils.applyFinishedSessionStyling(titleView, sessionRunning,
                    terminalSession.getExitStatus(),
                    androidx.core.content.ContextCompat.getColor(mActivity, com.termux.shared.R.color.terminal_tab_text_error),
                    mSchemeTextColor);
        }

        // Selection state and close button visibility.
        tabView.setSelected(isSelected);
        if (closeButton != null) {
            closeButton.setVisibility(isSelected ? View.VISIBLE : View.INVISIBLE);
        }
    }

    /**
     * Close a terminal session.  Runs a short "fly away & dissolve" animation on the
     * tab being closed, then finishes the underlying session.  The neighbours to the
     * right slide into the freed space via the container's LayoutTransition.
     */
    private void closeSession(TerminalSession session) {
        if (session == null) return;

        // Find the tab view that maps to this session.
        View tabView = null;
        if (mTabsContainer != null) {
            for (int i = 0; i < getTabCount(); i++) {
                View child = getTabAt(i);
                if (child == null) continue;
                Object tag = child.getTag(R.id.session_tab_session_tag);
                if (tag == session) {
                    tabView = child;
                    break;
                }
            }
        }

        if (tabView == null) {
            // No view found (e.g. race) — just finish the session, the normal
            // updateTabs() removal will follow.
            session.finishIfRunning();
            return;
        }

        // Guard against double-closing the same tab.
        if (Boolean.TRUE.equals(tabView.getTag(R.id.session_tab_closing_tag))) return;
        tabView.setTag(R.id.session_tab_closing_tag, Boolean.TRUE);

        animateTabClose(tabView, session);
    }

    /**
     * Animate the closing tab as the exact inverse of the open animation.
     *
     * <p>When a tab opens, its view is added to the container and its width grows
     * from 0 to full while the neighbours are pushed aside (the container's
     * LayoutTransition APPEARING + CHANGING animators). Closing mirrors this: we
     * collapse the closing view's width from its current value down to 0, which
     * makes the LinearLayout re-measure every frame so the surrounding tabs glide
     * inward to fill the freed space. A short alpha fade rides along so the shrinking
     * tab dissolves rather than clipping its contents.</p>
     *
     * <p>The view is removed from the container manually at the end of the animation
     * (before {@link TerminalSession#finishIfRunning()}), so the collapsed view never
     * flashes back to its original size: by the time updateTabs() runs the child is
     * already gone and the session/view counts already agree, so no trailing view is
     * removed and no stale view is re-shown.</p>
     */
    private void animateTabClose(final View tabView, final TerminalSession session) {
        // Disable the per-tab click while it animates out.
        tabView.setClickable(false);
        ImageButton closeBtn = tabView.findViewById(R.id.session_tab_close);
        if (closeBtn != null) closeBtn.setClickable(false);

        // Freeze the current pixel width and drive it down to 0. Using a fixed width
        // (instead of WRAP_CONTENT) lets the LinearLayout re-measure smoothly.
        final int startWidth = tabView.getWidth();
        if (startWidth <= 0) {
            // Not laid out yet — nothing to animate; remove immediately.
            removeClosingTab(tabView, session);
            return;
        }

        final ViewGroup.LayoutParams lp = tabView.getLayoutParams();

        // Suppress the container's DISAPPEARING/CHANGE reflow while we drive the
        // width ourselves, otherwise the two effects fight. CHANGING is already used
        // for the *open* case; here our per-frame requestLayout does the reflow.
        android.animation.ValueAnimator collapse =
                android.animation.ValueAnimator.ofInt(startWidth, 0);
        collapse.setDuration(150);
        collapse.setInterpolator(new AccelerateDecelerateInterpolator());
        collapse.addUpdateListener(animation -> {
            lp.width = (int) animation.getAnimatedValue();
            tabView.setLayoutParams(lp);
        });

        ObjectAnimator alpha = ObjectAnimator.ofFloat(tabView, View.ALPHA, 1f, 0f);

        android.animation.AnimatorSet set = new android.animation.AnimatorSet();
        set.playTogether(collapse, alpha);
        set.setDuration(150);
        set.setInterpolator(new AccelerateDecelerateInterpolator());
        set.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                removeClosingTab(tabView, session);
            }
        });
        set.start();
    }

    /**
     * Remove a fully-collapsed closing tab view from the container and finish its
     * session. Removing the view first keeps the container child count in sync with
     * the session count before updateTabs() runs, so no view is re-shown or
     * double-removed.
     */
    private void removeClosingTab(final View tabView, final TerminalSession session) {
        if (mTabsContainer != null) {
            mTabsContainer.removeView(tabView);
        }
        // Restore transient state on the (now detached) view in case it is ever
        // recycled by the layout system.
        tabView.setAlpha(1f);
        ViewGroup.LayoutParams lp = tabView.getLayoutParams();
        if (lp != null) {
            lp.width = ViewGroup.LayoutParams.WRAP_CONTENT;
            tabView.setLayoutParams(lp);
        }
        tabView.setTag(R.id.session_tab_closing_tag, null);
        session.finishIfRunning();
    }



    private void cancelEndScroll() {
        if (mEndScrollAnim != null) {
            mEndScrollAnim.cancel();
            mEndScrollAnim = null;
        }
        if (mPendingEndScroll != null && mTabsScroll != null) {
            mTabsScroll.removeCallbacks(mPendingEndScroll);
            mPendingEndScroll = null;
        }
    }

    private void runEndScroll() {
        if (mTabsContainer == null || mTabsScroll == null) return;

        // Cancel any in-flight end-scroll (a newer request, or a re-entry) before starting fresh.
        cancelEndScroll();

        // Wait for the new tab's width animation to settle before measuring the right end.
        mPendingEndScroll = new Runnable() {
            @Override
            public void run() {
                mPendingEndScroll = null;
                if (mTabsContainer == null || mTabsScroll == null) return;

                // Authoritative scrollable extent: the HorizontalScrollView clamps scroll to
                // [0, childMeasuredWidth - viewportWidth]. getMeasuredWidth() of the
                // wrap_content container already includes the (+) button's marginEnd and the
                // container padding, so this equals the true right end with nothing clipped.
                final int childMeasuredW = mTabsContainer.getMeasuredWidth();
                final int scrollW = mTabsScroll.getWidth();
                final int maxScroll = Math.max(0, childMeasuredW - scrollW);

                final int startX = mTabsScroll.getScrollX();
                if (startX == maxScroll) {
                    mEndScrollActive = false;
                    return;
                }
                // Self-driven scroll: we own the target, HSV's per-frame re-clamp is bypassed.
                mEndScrollAnim = android.animation.ValueAnimator.ofInt(startX, maxScroll);
                mEndScrollAnim.setDuration(250);
                mEndScrollAnim.setInterpolator(
                        new android.view.animation.AccelerateDecelerateInterpolator());
                mEndScrollAnim.addUpdateListener(anim ->
                        mTabsScroll.scrollTo((int) anim.getAnimatedValue(), 0));
                mEndScrollAnim.addListener(new android.animation.AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(android.animation.Animator animation) {
                        mEndScrollActive = false;
                        mEndScrollAnim = null;
                    }
                    @Override
                    public void onAnimationCancel(android.animation.Animator animation) {
                        mEndScrollAnim = null;
                    }
                });
                mEndScrollAnim.start();
            }
        };
        mTabsScroll.postDelayed(mPendingEndScroll, END_SCROLL_DELAY_MS);
    }

    public void scrollStripToEnd() {
        if (mTabsContainer == null || mTabsScroll == null) return;
        cancelTabAnimator();
        mScrollMode = TabScrollMode.IDLE;
        mEndScrollActive = true;
        runEndScroll();
    }



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
        for (int i = 0; i < getTabCount(); i++) {
            View tabView = getTabAt(i);
            TextView title = tabView.findViewById(R.id.session_tab_title);
            ImageButton close = tabView.findViewById(R.id.session_tab_close);
            // Error (finished-with-exit) tabs keep their red color; normal tabs use the scheme fg.
            if (title != null && title.getCurrentTextColor() != errorColor) {
                title.setTextColor(textColor);
            }
            if (close != null) {
                close.setColorFilter(textColor, android.graphics.PorterDuff.Mode.SRC_ATOP);
            }
            setTabBackground(tabView, tabView.isSelected() ? bgActive : bg);
        }

        // Give the (+) add button (last child, excluded from the loop above) its scheme
        // background while preserving the press/swipe active-state visual.
        applyAddButtonSchemeBackground();
    }

    /** Tell the controller whether the trailing placeholder page is present, so an in-progress
     *  right-swipe from the last tab blends that tab into the (+) add button. */
    public void setPlaceholderActive(boolean active) {
        mPlaceholderActive = active;
    }

    /**
     * Reserve the right-end scroll for a freshly-added tab. While reserved, the pager's
     * onPageScrolled() instant scrollTo() is suppressed (so it cannot fight the smooth END scroll)
     * and setCurrentSession() will not post a competing CENTRE scroll. The reservation is cleared
     * when the END scroll actually fires (after the new tab's label is set) or when the user makes
     * a genuine tab switch / closes a tab.
     */
    public void setEndScrollReserved(boolean reserved) {
        mEndScrollActive = reserved;
        if (!reserved) {
            cancelTabAnimator();
            cancelEndScroll();
        } else {
            cancelTabAnimator();
            mScrollMode = TabScrollMode.IDLE;
        }
    }

    public void onDragStarted() {
        cancelTabAnimator();
        mScrollMode = TabScrollMode.FOLLOW_PAGER;
    }

    public void onProgrammaticScrollStarted(int targetIndex, int fromIndex) {
        if (mEndScrollActive) return;
        cancelTabAnimator();
        mScrollMode = TabScrollMode.ANIMATE_TO_TAB;
        animateToTab(targetIndex, fromIndex);
    }

    public void onAdjacentScrollStarted() {
        mScrollMode = TabScrollMode.FOLLOW_PAGER;
    }

    public void onScrollFinished(int activeIndex) {
        mCurrentSessionIndex = activeIndex;
        cancelTabAnimator();
        mScrollMode = TabScrollMode.IDLE;
        snapToTabCenter(activeIndex);
        applyTabSelectionState(activeIndex);
    }

    public void snapToTabCenter(int index) {
        if (mEndScrollActive || mTabsContainer == null || mTabsScroll == null) return;
        if (index < 0 || index >= mTabsContainer.getChildCount() - 1) return;
        View tabView = mTabsContainer.getChildAt(index);
        if (tabView == null) return;
        int scrollW = mTabsScroll.getWidth();
        int maxScroll = Math.max(0, mTabsContainer.getMeasuredWidth() - scrollW);
        int target = tabView.getLeft() - scrollW / 2 + tabView.getWidth() / 2;
        if (target < 0) target = 0;
        if (target > maxScroll) target = maxScroll;
        mTabsScroll.scrollTo(target, 0);
    }

    private void animateToTab(int targetIndex, int fromIndex) {
        if (mTabsContainer == null || mTabsScroll == null) return;
        if (targetIndex >= mTabsContainer.getChildCount() - 1) return;
        View tabView = mTabsContainer.getChildAt(targetIndex);
        if (tabView == null) {
            mTabsContainer.post(() -> snapToTabCenter(targetIndex));
            mScrollMode = TabScrollMode.IDLE;
            return;
        }
        int scrollW = mTabsScroll.getWidth();
        int maxScroll = Math.max(0, mTabsContainer.getMeasuredWidth() - scrollW);
        int target = tabView.getLeft() - scrollW / 2 + tabView.getWidth() / 2;
        if (target < 0) target = 0;
        if (target > maxScroll) target = maxScroll;
        int fromX = mTabsScroll.getScrollX();
        if (fromX == target) {
            mScrollMode = TabScrollMode.IDLE;
            return;
        }
        long duration = Math.min(420L, Math.max(180L, 180L + Math.abs(targetIndex - fromIndex) * 60L));
        mTabAnimator = android.animation.ValueAnimator.ofInt(fromX, target);
        mTabAnimator.setDuration(duration);
        mTabAnimator.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
        mTabAnimator.addUpdateListener(anim -> {
            if (mTabsScroll != null) mTabsScroll.scrollTo((int) anim.getAnimatedValue(), 0);
        });
        mTabAnimator.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) { mTabAnimator = null; }
            @Override
            public void onAnimationCancel(android.animation.Animator animation) { mTabAnimator = null; }
        });
        mTabAnimator.start();
    }

    private void cancelTabAnimator() {
        if (mTabAnimator != null) {
            mTabAnimator.cancel();
            mTabAnimator = null;
        }
    }

    private void applyTabSelectionState(int index) {
        if (mTabsContainer == null) return;
        for (int i = 0; i < getTabCount(); i++) {
            View child = getTabAt(i);
            boolean isSelected = (i == index);
            child.setSelected(isSelected);
            if (mSchemeApplied) {
                setTabBackground(child, isSelected ? mSchemeBgActive : mSchemeBg);
            }
            ImageButton closeButton = child.findViewById(R.id.session_tab_close);
            if (closeButton != null) {
                closeButton.setVisibility(isSelected ? View.VISIBLE : View.INVISIBLE);
            }
        }
        applyAddButtonSchemeBackground();
    }

    private void applyAddButtonSchemeBackground() {
        if (mTabsContainer == null || !mSchemeApplied) return;
        View addBtn = mTabsContainer.getChildAt(mTabsContainer.getChildCount() - 1);
        if (addBtn != null) setAddButtonBackground(addBtn);
    }

    public void setCurrentSession(int index) {
        if (mTabsContainer == null) return;
        if (index < 0 || index >= mTabsContainer.getChildCount() - 1) return;
        applyTabSelectionState(index);
        mCurrentSessionIndex = index;
        if (mEndScrollActive) return;
        mEndScrollActive = false;
    }

    // ── Intermediate page-scroll support ──────────────────────────────────

    public void onPageScrolled(int position, float positionOffset, int positionOffsetPixels) {
        if (mTabsContainer == null || mTabsScroll == null) return;
        if (!mSchemeApplied) return;
        if (mEndScrollActive) return;

        // ── ANIMATE_TO_TAB mode: pager-driven updates suppressed ──
        if (mScrollMode == TabScrollMode.ANIMATE_TO_TAB) return;

        // ── Final frame (pager exactly on page): snap to exact center ──
        if (positionOffset <= 0f) {
            if (mScrollMode == TabScrollMode.FOLLOW_PAGER) {
                snapToTabCenter(position);
                mScrollMode = TabScrollMode.IDLE;
            }
            return;
        }

        // ── Intermediate frames: interpolate between adjacent tab centers ──
        if (mScrollMode != TabScrollMode.FOLLOW_PAGER) return;

        int leftIdx = position;
        int rightIdx = position + 1;
        if (leftIdx < 0) return;
        if (rightIdx >= mTabsContainer.getChildCount() - 1
                && !(mPlaceholderActive && leftIdx == mTabsContainer.getChildCount() - 2)) return;

        View leftTab = mTabsContainer.getChildAt(leftIdx);
        View rightTab = mTabsContainer.getChildAt(rightIdx);
        if (leftTab == null || rightTab == null) return;

        int leftCenter = leftTab.getLeft() + leftTab.getWidth() / 2;
        int rightCenter = rightTab.getLeft() + rightTab.getWidth() / 2;
        int interpolatedCenter = (int) (leftCenter + (rightCenter - leftCenter) * positionOffset);
        int targetScrollX = interpolatedCenter - mTabsScroll.getWidth() / 2;
        mTabsScroll.scrollTo(targetScrollX, 0);

        int blendedLeft = blendColors(mSchemeBgActive, mSchemeBg, positionOffset);
        int blendedRight = blendColors(mSchemeBg, mSchemeBgActive, positionOffset);
        setTabBackground(leftTab, blendedLeft);
        setTabBackground(rightTab, blendedRight);

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
        applyTabSelectionState(currentIndex);
        mCurrentSessionIndex = currentIndex;
    }

    /**
     * Linearly interpolate between two ARGB colours.
     *
     * @param from  colour at ratio = 0.0
     * @param to    colour at ratio = 1.0
     * @param ratio blend factor in [0, 1]
     */
    /**
     * Set a rounded-rectangle background on a tab/view using the given scheme color,
     * preserving the rounded shape (the previous {@code setBackgroundTintList} flattened it).
     */
    /**
     * Set the background of the trailing (+) add button as an oval {@link StateListDrawable}
     * that shows an active (pressed / swipe-selected) fill. Unlike {@link #setTabBackground},
     * this preserves the press/swipe visual activation required by the add button, instead of
     * replacing it with a flat state-less rectangle.
     */
    private void setAddButtonBackground(View view) {
        int strokePx = Math.round(mActivity.getResources().getDimension(R.dimen.terminal_text_input_stroke));
        view.setBackground(buildOvalStateListDrawable(strokePx));
    }

    private StateListDrawable buildOvalStateListDrawable(int strokePx) {
        GradientDrawable idle = new GradientDrawable();
        idle.setShape(GradientDrawable.OVAL);
        idle.setColor(mSchemeBg);
        idle.setStroke(strokePx, mSchemeBgActive);

        GradientDrawable active = new GradientDrawable();
        active.setShape(GradientDrawable.OVAL);
        active.setColor(mSchemeBgActive);
        active.setStroke(strokePx, mSchemeBgActive);

        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_pressed}, active);
        states.addState(new int[]{android.R.attr.state_selected}, active);
        states.addState(new int[]{}, idle);
        return states;
    }

    private void setTabBackground(View view, int color) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setCornerRadius(mActivity.getResources().getDimension(R.dimen.terminal_tab_corner_radius));
        d.setColor(color);
        view.setBackground(d);
    }

    private static int blendColors(int from, int to, float ratio) {
        int a = (int) (Color.alpha(from) * (1f - ratio) + Color.alpha(to) * ratio);
        int r = (int) (Color.red(from) * (1f - ratio) + Color.red(to) * ratio);
        int g = (int) (Color.green(from) * (1f - ratio) + Color.green(to) * ratio);
        int b = (int) (Color.blue(from) * (1f - ratio) + Color.blue(to) * ratio);
        return Color.argb(a, r, g, b);
    }
}

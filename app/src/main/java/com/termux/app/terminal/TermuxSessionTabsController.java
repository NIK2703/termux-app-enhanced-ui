package com.termux.app.terminal;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.LayoutTransition;
import android.animation.ObjectAnimator;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateDecelerateInterpolator;
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

    /** Whether the trailing placeholder page is currently present (for tab-strip blending). */
    private boolean mPlaceholderActive = false;

    public TermuxSessionTabsController(TermuxActivity activity) {
        this.mActivity = activity;
        this.mTabsContainer = activity.findViewById(R.id.session_tabs);
        this.mTabsScroll = activity.findViewById(R.id.session_tabs_scroll);

        // Enable layout animations so that when a tab is removed the tabs to its
        // right glide smoothly into the freed space (instead of an instant jump).
        // The DISAPPEARING animation is disabled below — we run our own closing
        // animation on the removed tab, and a built-in DISAPPEARING fade would
        // both delay the slide-in of the neighbours and fight our effect.
        LayoutTransition transition = new LayoutTransition();
        transition.enableTransitionType(LayoutTransition.CHANGING);
        transition.enableTransitionType(LayoutTransition.APPEARING);
        transition.enableTransitionType(LayoutTransition.CHANGE_APPEARING);
        transition.disableTransitionType(LayoutTransition.DISAPPEARING);
        transition.disableTransitionType(LayoutTransition.CHANGE_DISAPPEARING);
        transition.setDuration(220);
        transition.setInterpolator(LayoutTransition.CHANGING,
                new AccelerateDecelerateInterpolator());
        transition.setInterpolator(LayoutTransition.CHANGE_APPEARING,
                new AccelerateDecelerateInterpolator());
        mTabsContainer.setLayoutTransition(transition);
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
     * Close a terminal session.  Runs a short "fly away & dissolve" animation on the
     * tab being closed, then finishes the underlying session.  The neighbours to the
     * right slide into the freed space via the container's LayoutTransition.
     */
    private void closeSession(TerminalSession session) {
        if (session == null) return;

        // Find the tab view that maps to this session.
        View tabView = null;
        if (mTabsContainer != null) {
            for (int i = 0; i < mTabsContainer.getChildCount() - 1; i++) {
                View child = mTabsContainer.getChildAt(i);
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

    /**
     * Force the tab strip to reveal a specific tab, waiting until the strip layout
     * has fully settled before measuring.
     *
     * <p>Used after committing the trailing "swipe-right for new tab" placeholder. The
     * naive approach (a single {@code post} + centre-on-tab) mis-scrolled because the
     * freshly-added tab enters via the container's {@link LayoutTransition} APPEARING
     * animation: its width grows from 0 to full over 220ms while the neighbours slide,
     * so a scroll computed one frame after {@code addView} reads unsettled
     * {@code getLeft()}/{@code getWidth()} values and lands the strip somewhere in the
     * middle. Here we defer the scroll until the APPEARING transition ends (via a
     * one-shot {@link LayoutTransition.TransitionListener}); if no transition is
     * running we scroll on the next layout pass.</p>
     */
    public void scrollToTabIndex(int index) {
        if (mTabsContainer == null || mTabsScroll == null) return;

        final LayoutTransition transition = mTabsContainer.getLayoutTransition();
        if (transition != null && transition.isRunning()) {
            transition.addTransitionListener(new LayoutTransition.TransitionListener() {
                @Override
                public void startTransition(LayoutTransition t, ViewGroup container,
                                            View view, int transitionType) { }

                @Override
                public void endTransition(LayoutTransition t, ViewGroup container,
                                          View view, int transitionType) {
                    // Only act once the whole transition batch has finished so the
                    // final geometry is stable. Remove the listener so it fires once.
                    if (t.isRunning()) return;
                    t.removeTransitionListener(this);
                    scrollToTab(index);
                }
            });
            return;
        }

        // No transition running — scroll after the next layout pass so a just-added
        // tab that has not been measured yet gets its final position first.
        mTabsScroll.post(() -> scrollToTab(index));
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

        // Give the (+) add button (last child, excluded from the loop above) the normal scheme
        // background so it reads as a tab and blends correctly when the placeholder swipe reaches it.
        if (mSchemeApplied) {
            View addBtn = mTabsContainer.getChildAt(mTabsContainer.getChildCount() - 1);
            if (addBtn != null) addBtn.setBackgroundTintList(ColorStateList.valueOf(bg));
        }
    }

    /** Tell the controller whether the trailing placeholder page is present, so an in-progress
     *  right-swipe from the last tab blends that tab into the (+) add button. */
    public void setPlaceholderActive(boolean active) {
        mPlaceholderActive = active;
    }

    public void setCurrentSession(int index) {        if (mTabsContainer == null) return;
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

        // The (+) add button (last child) never becomes selected; keep it at the normal scheme bg,
        // overriding any blend left over from the placeholder swipe.
        if (mSchemeApplied) {
            View addBtn = mTabsContainer.getChildAt(mTabsContainer.getChildCount() - 1);
            if (addBtn != null) addBtn.setBackgroundTintList(ColorStateList.valueOf(mSchemeBg));
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
        if (leftIdx < 0) return;
        // Normally the (+) add button (last child) is excluded from blending. While the trailing
        // placeholder page is present, allow the last real tab (leftIdx == childCount-2) to blend
        // into the (+) button (rightIdx == childCount-1) so the strip follows the swipe exactly like
        // a normal tab-to-tab transition.
        if (rightIdx >= mTabsContainer.getChildCount() - 1
                && !(mPlaceholderActive && leftIdx == mTabsContainer.getChildCount() - 2)) return;

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

        // Reset the (+) add button (last child) to the normal scheme bg after a cancelled swipe.
        if (mSchemeApplied) {
            View addBtn = mTabsContainer.getChildAt(mTabsContainer.getChildCount() - 1);
            if (addBtn != null) addBtn.setBackgroundTintList(ColorStateList.valueOf(mSchemeBg));
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

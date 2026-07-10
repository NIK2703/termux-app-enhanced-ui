package com.termux.app.terminal;

import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.LayoutInflater;
import android.view.View;
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

    public void updateTabs(List<TermuxSession> sessions) {
        if (mTabsContainer == null) return;

        mTabsContainer.removeAllViews();

        TerminalSession currentSession = mActivity.getCurrentSession();
        int currentSessionIndex = -1;

        for (int i = 0; i < sessions.size(); i++) {
            TermuxSession termuxSession = sessions.get(i);
            TerminalSession terminalSession = termuxSession.getTerminalSession();

            if (terminalSession == currentSession) {
                currentSessionIndex = i;
            }

            View tabView = createTabView(termuxSession, i, terminalSession == currentSession);
            mTabsContainer.addView(tabView);
        }

        if (currentSessionIndex != mCurrentSessionIndex) {
            mCurrentSessionIndex = currentSessionIndex;
            scrollToTab(currentSessionIndex);
        }
    }

    private View createTabView(TermuxSession termuxSession, int position, boolean isSelected) {
        LayoutInflater inflater = LayoutInflater.from(mActivity);
        View tabView = inflater.inflate(R.layout.item_session_tab, mTabsContainer, false);

        TextView titleView = tabView.findViewById(R.id.session_tab_title);
        ImageButton closeButton = tabView.findViewById(R.id.session_tab_close);

        TerminalSession terminalSession = termuxSession.getTerminalSession();
        if (terminalSession != null) {
            String name = terminalSession.mSessionName;
            String title = terminalSession.getTitle();

            String displayTitle = (name != null && !name.isEmpty()) ? name : 
                                  (title != null && !title.isEmpty()) ? title : "Terminal";
            
            // Truncate if too long
            if (displayTitle.length() > 15) {
                displayTitle = displayTitle.substring(0, 12) + "...";
            }
            
            titleView.setText(displayTitle);

            // Text color: red for finished-with-error sessions. Otherwise use the terminal color
            // scheme foreground so every tab (including freshly created ones) follows the
            // Termux:Style scheme instead of the hardcoded layout default color. If the scheme has
            // not been applied yet, fall back to the layout default until applySchemeColorsToTabs
            // runs.
            boolean sessionRunning = terminalSession.isRunning();
            if (!sessionRunning && terminalSession.getExitStatus() != 0) {
                int errorColor = androidx.core.content.ContextCompat.getColor(
                    mActivity, com.termux.shared.R.color.terminal_tab_text_error);
                titleView.setTextColor(errorColor);
            } else if (mSchemeApplied) {
                titleView.setTextColor(mSchemeTextColor);
            }

            // Close-icon color from the scheme (error tabs keep the close icon neutral too).
            if (closeButton != null && mSchemeApplied) {
                closeButton.setColorFilter(mSchemeTextColor, android.graphics.PorterDuff.Mode.SRC_ATOP);
            }

            // Translucent background matching the signal panel (selected = active tone).
            if (mSchemeApplied) {
                tabView.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                        isSelected ? mSchemeBgActive : mSchemeBg));
            }

            // Strike through for finished sessions
            if (!sessionRunning) {
                titleView.setPaintFlags(titleView.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG);
            } else {
                titleView.setPaintFlags(titleView.getPaintFlags() & ~Paint.STRIKE_THRU_TEXT_FLAG);
            }
        }

        // Set selection state
        tabView.setSelected(isSelected);
        
        // Show close button only for selected tab
        closeButton.setVisibility(isSelected ? View.VISIBLE : View.INVISIBLE);

        // Click to switch session (without toast notification)
        tabView.setOnClickListener(v -> {
            if (terminalSession != null) {
                mActivity.getTermuxTerminalSessionClient().setCurrentSession(terminalSession, false);
            }
        });

        // Long click to rename
        tabView.setOnLongClickListener(v -> {
            if (terminalSession != null) {
                mActivity.getTermuxTerminalSessionClient().renameSession(terminalSession);
            }
            return true;
        });

        // Close button - finish and remove session
        closeButton.setOnClickListener(v -> {
            if (terminalSession != null) {
                closeSession(terminalSession);
            }
        });

        return tabView;
    }

    /**
     * Close a terminal session - finish it if running and then remove
     */
    private void closeSession(TerminalSession session) {
        if (session == null) return;
        
        // Finish the session by sending SIGKILL - onSessionFinished callback will handle removal
        session.finishIfRunning();
    }

    private void scrollToTab(int index) {
        if (mTabsContainer == null || mTabsScroll == null) return;
        if (index < 0 || index >= mTabsContainer.getChildCount()) return;

        final View tabView = mTabsContainer.getChildAt(index);
        if (tabView == null) return;

        mTabsScroll.post(() -> {
            int scrollX = tabView.getLeft() - mTabsScroll.getWidth() / 2 + tabView.getWidth() / 2;
            mTabsScroll.smoothScrollTo(scrollX, 0);
        });
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
        for (int i = 0; i < mTabsContainer.getChildCount(); i++) {
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
            tabView.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                    tabView.isSelected() ? bgActive : bg));
        }
    }

    public void setCurrentSession(int index) {
        if (mTabsContainer == null) return;
        if (index < 0 || index >= mTabsContainer.getChildCount()) return;

        // Update selection state and close button visibility for all tabs
        for (int i = 0; i < mTabsContainer.getChildCount(); i++) {
            View child = mTabsContainer.getChildAt(i);
            boolean isSelected = (i == index);
            child.setSelected(isSelected);

            // Re-tint the background to follow the selection: selected tab uses the active
            // control background, the rest use the normal control background (same colors as the
            // other bottom-panel controls). Only once the scheme colors have been applied.
            if (mSchemeApplied) {
                child.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
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
}

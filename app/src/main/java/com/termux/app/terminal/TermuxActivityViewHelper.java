package com.termux.app.terminal;

import android.app.Activity;
import android.content.Intent;
import android.view.ContextMenu;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.ImageButton;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.app.activities.HelpActivity;
import com.termux.app.activities.SettingsActivity;
import com.termux.app.terminal.io.DirectoryHistoryPopupController;
import com.termux.shared.activity.ActivityUtils;
import com.termux.shared.activity.media.AppCompatActivityUtils;
import com.termux.shared.termux.interact.TextInputDialogUtils;
import com.termux.shared.termux.theme.TermuxThemeUtils;
import com.termux.shared.theme.NightMode;
import com.termux.terminal.TerminalSession;
import com.termux.view.TerminalView;

/**
 * Extracted View-setup helper for {@link TermuxActivity}.
 *
 * <p>Previously these methods lived directly on the {@link TermuxActivity} god class. They only
 * touch public API on the activity (getters / {@code findViewById} / shared static utilities) plus
 * an injected {@link DirectoryHistoryPopupController}, so this helper can be hosted in the
 * {@code com.termux.app.terminal} package without modifying {@link TermuxActivity}.
 *
 * <p>The activity wires this helper in a later step by constructing it with the activity + inflater
 * and calling the {@code setup*()} methods from {@code onCreate()}.
 */
public class TermuxActivityViewHelper {

    // Context-menu item ids (mirror the private constants on TermuxActivity).
    private static final int CONTEXT_MENU_SELECT_URL_ID = 0;
    private static final int CONTEXT_MENU_SHARE_TRANSCRIPT_ID = 1;
    private static final int CONTEXT_MENU_AUTOFILL_PASSWORD = 2;
    private static final int CONTEXT_MENU_RESET_TERMINAL_ID = 3;
    private static final int CONTEXT_MENU_KILL_PROCESS_ID = 4;
    private static final int CONTEXT_MENU_STYLING_ID = 5;
    private static final int CONTEXT_MENU_TOGGLE_KEEP_SCREEN_ON = 6;
    private static final int CONTEXT_MENU_HELP_ID = 7;
    private static final int CONTEXT_MENU_SETTINGS_ID = 8;
    private static final int CONTEXT_MENU_REPORT_ID = 9;
    private static final int CONTEXT_MENU_SHARE_SELECTED_TEXT = 10;
    private static final int CONTEXT_MENU_AUTOFILL_USERNAME = 11;
    private static final int CONTEXT_MENU_FONT_ID = 12;

    @NonNull
    private final TermuxActivity mActivity;
    @NonNull
    private final LayoutInflater mLayoutInflater;

    // Injected by the activity (set after construction) so the new-session tab button can drive
    // the directory-history swipe-up gesture without the helper reaching into activity-private state.
    @Nullable
    private DirectoryHistoryPopupController mDirectoryHistoryPopupCtrl;

    public TermuxActivityViewHelper(@NonNull TermuxActivity activity, @NonNull LayoutInflater layoutInflater) {
        this.mActivity = activity;
        this.mLayoutInflater = layoutInflater;
    }

    public void setDirectoryHistoryPopupController(@Nullable DirectoryHistoryPopupController controller) {
        this.mDirectoryHistoryPopupCtrl = controller;
    }

    // ============================================================================
    // View setup methods
    // ============================================================================

    /**
     * Wire the new-session button. The new-session button now lives in the tabs bar and is handled
     * in {@link #setupSessionsListView(View)}, so this is intentionally a no-op (kept for symmetry
     * with the original {@code TermuxActivity#setNewSessionButtonView()}).
     */
    public void setupNewSessionButton(@NonNull View rootView) {
        // New session button is now in the tabs bar, handled in setupSessionsListView().
    }

    /**
     * Wire the toggle-keyboard button. The toggle-keyboard button was removed (its functionality
     * moved to the extra keys), so this is intentionally a no-op (mirrors
     * {@code TermuxActivity#setToggleKeyboardView()}).
     */
    public void setupToggleKeyboardButton(@NonNull View rootView) {
        // Toggle keyboard button removed - functionality moved to extra keys.
    }

    /** Wire the horizontal session-pager tabs, including the new-session tab button. */
    public void setupSessionsListView(@NonNull View rootView) {
        ImageButton newSessionTabButton = rootView.findViewById(R.id.new_session_tab_button);
        if (newSessionTabButton != null) {
            // Tap opens a new tab (default cwd); long-press creates a named session.
            newSessionTabButton.setOnClickListener(v ->
                mActivity.getTermuxTerminalSessionClient().addNewSession(false, null));
            newSessionTabButton.setOnLongClickListener(v -> {
                TextInputDialogUtils.textInput(mActivity, R.string.title_create_named_session, null,
                    R.string.action_create_named_session_confirm,
                    text -> mActivity.getTermuxTerminalSessionClient().addNewSession(false, text),
                    R.string.action_new_session_failsafe,
                    text -> mActivity.getTermuxTerminalSessionClient().addNewSession(true, text),
                    -1, null, null);
                return true;
            });

            // A swipe-up (drag off the button) opens the directory-history popup, mirroring the
            // pencil button's gesture. We return false from the touch listener until a swipe is
            // actually detected, so a plain tap / long-press still reaches the listeners above.
            if (mDirectoryHistoryPopupCtrl != null) {
                attachDirectoryHistoryGesture(newSessionTabButton);
            }
        }
    }

    private void attachDirectoryHistoryGesture(@NonNull ImageButton newSessionTabButton) {
        final DirectoryHistoryPopupController popup = mDirectoryHistoryPopupCtrl;
        final int touchSlop = ViewConfiguration.get(mActivity).getScaledTouchSlop();
        final float[] downXY = new float[2];
        final boolean[] gestureActive = { false };
        final boolean[] swipeConsumed = { false };

        newSessionTabButton.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    downXY[0] = event.getRawX();
                    downXY[1] = event.getRawY();
                    gestureActive[0] = true;
                    swipeConsumed[0] = false;
                    return false;   // let click / long-press proceed
                case android.view.MotionEvent.ACTION_MOVE: {
                    if (!gestureActive[0]) return false;
                    if (popup.isShowing()) {
                        popup.updateHighlight(event.getRawX(), event.getRawY());
                        return true;
                    }
                    float dy = event.getRawY() - downXY[1];
                    float dx = event.getRawX() - downXY[0];
                    if (dy < -touchSlop && Math.abs(dy) > Math.abs(dx) && popup.shouldShow()) {
                        v.setPressed(false);
                        swipeConsumed[0] = true;
                        popup.show(v);
                        return true;   // consume: cancels pending click/long-press
                    }
                    return false;
                }
                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL: {
                    boolean wasPopup = popup.isShowing();
                    if (wasPopup) {
                        int selected = popup.getHighlightIndex();
                        popup.dismiss();
                        if (event.getActionMasked() == android.view.MotionEvent.ACTION_UP) {
                            if (selected == DirectoryHistoryPopupController.CLEAR_ALL_TAG) {
                                popup.confirmClear();
                            } else if (selected >= 0) {
                                popup.pick(selected);
                            }
                        }
                    }
                    gestureActive[0] = false;
                    return !wasPopup;   // not a swipe -> allow onClick
                }
            }
            return false;
        });
    }

    /**
     * Apply the user-selected theme / night mode. Mirrors {@code TermuxActivity#applyTermuxTheme()}.
     * Note: the terminal color scheme repaint is intentionally NOT done here (see the original
     * method's documentation) — it runs later once the terminal view and client exist.
     */
    public void applyTheme() {
        // Update NightMode.APP_NIGHT_MODE.
        TermuxThemeUtils.setAppNightMode(mActivity.getProperties().getNightMode());

        // Set activity night mode. If NightMode.SYSTEM is set, android will automatically trigger
        // recreation of the activity when uiMode/dark mode configuration changes so the day/night
        // theme takes effect.
        AppCompatActivityUtils.setNightMode(mActivity, NightMode.getAppNightMode().getName(), true);
    }

    /**
     * Minimal toolbar setup: show/hide the terminal toolbar container per the user preference.
     * (The full toolbar wiring — extra keys, text input, height — remains in
     * {@code TermuxActivity#setTerminalToolbarView(Bundle)} and is out of scope for this helper.)
     */
    public void setupToolbar() {
        LinearLayout terminalToolbarContainer = mActivity.getTerminalToolbarContainer();
        if (terminalToolbarContainer != null && mActivity.getPreferences().shouldShowTerminalToolbar())
            terminalToolbarContainer.setVisibility(View.VISIBLE);
    }

    // ============================================================================
    // Context menu (delegated from TermuxActivity)
    // ============================================================================

    public void onCreateContextMenu(ContextMenu menu, View v, ContextMenu.ContextMenuInfo menuInfo) {
        TerminalSession currentSession = mActivity.getCurrentSession();
        if (currentSession == null) return;

        TerminalView terminalView = mActivity.getTerminalView();
        if (terminalView == null) return;

        boolean autoFillEnabled = terminalView.isAutoFillEnabled();

        menu.add(Menu.NONE, CONTEXT_MENU_SELECT_URL_ID, Menu.NONE, R.string.action_select_url);
        menu.add(Menu.NONE, CONTEXT_MENU_SHARE_TRANSCRIPT_ID, Menu.NONE, R.string.action_share_transcript);
        if (!com.termux.shared.data.DataUtils.isNullOrEmpty(terminalView.getStoredSelectedText()))
            menu.add(Menu.NONE, CONTEXT_MENU_SHARE_SELECTED_TEXT, Menu.NONE, R.string.action_share_selected_text);
        if (autoFillEnabled)
            menu.add(Menu.NONE, CONTEXT_MENU_AUTOFILL_USERNAME, Menu.NONE, R.string.action_autofill_username);
        if (autoFillEnabled)
            menu.add(Menu.NONE, CONTEXT_MENU_AUTOFILL_PASSWORD, Menu.NONE, R.string.action_autofill_password);
        menu.add(Menu.NONE, CONTEXT_MENU_RESET_TERMINAL_ID, Menu.NONE, R.string.action_reset_terminal);
        menu.add(Menu.NONE, CONTEXT_MENU_KILL_PROCESS_ID, Menu.NONE,
            mActivity.getResources().getString(R.string.action_kill_process, currentSession.getPid()))
            .setEnabled(currentSession.isRunning());
        menu.add(Menu.NONE, CONTEXT_MENU_STYLING_ID, Menu.NONE, R.string.action_style_terminal);
        menu.add(Menu.NONE, CONTEXT_MENU_FONT_ID, Menu.NONE, R.string.action_font_terminal);
        menu.add(Menu.NONE, CONTEXT_MENU_TOGGLE_KEEP_SCREEN_ON, Menu.NONE, R.string.action_toggle_keep_screen_on)
            .setCheckable(true).setChecked(mActivity.getPreferences().shouldKeepScreenOn());
        menu.add(Menu.NONE, CONTEXT_MENU_HELP_ID, Menu.NONE, R.string.action_open_help);
        menu.add(Menu.NONE, CONTEXT_MENU_SETTINGS_ID, Menu.NONE, R.string.action_open_settings);
        menu.add(Menu.NONE, CONTEXT_MENU_REPORT_ID, Menu.NONE, R.string.action_report_issue);
    }

    public boolean onContextItemSelected(MenuItem item) {
        TerminalSession session = mActivity.getCurrentSession();
        TerminalView terminalView = mActivity.getTerminalView();

        switch (item.getItemId()) {
            case CONTEXT_MENU_SELECT_URL_ID:
                mActivity.getTermuxTerminalViewClient().showUrlSelection();
                return true;
            case CONTEXT_MENU_SHARE_TRANSCRIPT_ID:
                mActivity.getTermuxTerminalViewClient().shareSessionTranscript();
                return true;
            case CONTEXT_MENU_SHARE_SELECTED_TEXT:
                mActivity.getTermuxTerminalViewClient().shareSelectedText();
                return true;
            case CONTEXT_MENU_AUTOFILL_USERNAME:
                if (terminalView != null) terminalView.requestAutoFillUsername();
                return true;
            case CONTEXT_MENU_AUTOFILL_PASSWORD:
                if (terminalView != null) terminalView.requestAutoFillPassword();
                return true;
            case CONTEXT_MENU_REPORT_ID:
                mActivity.getTermuxTerminalViewClient().reportIssueFromTranscript();
                return true;
            case CONTEXT_MENU_HELP_ID:
                ActivityUtils.startActivity(mActivity, new Intent(mActivity, HelpActivity.class));
                return true;
            case CONTEXT_MENU_SETTINGS_ID:
                ActivityUtils.startActivity(mActivity, new Intent(mActivity, SettingsActivity.class));
                return true;
            // RESET / KILL / STYLING / FONT / KEEP_SCREEN_ON require activity-internal dialog
            // methods; the activity's own onContextItemSelected handles those (this helper is a
            // partial extraction and returns false for items it cannot service via public API).
            default:
                return false;
        }
    }

    public void onContextMenuClosed(Menu menu) {
        // onContextMenuClosed() is triggered twice if back button is pressed to dismiss instead of
        // tap for some reason.
        TerminalView terminalView = mActivity.getTerminalView();
        if (terminalView != null) terminalView.onContextMenuClosed(menu);
    }
}

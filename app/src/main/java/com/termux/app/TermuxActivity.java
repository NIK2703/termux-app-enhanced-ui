package com.termux.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.SharedPreferences;
import android.content.ServiceConnection;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.ContextMenu;
import android.view.ContextMenu.ContextMenuInfo;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupWindow;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.core.content.ContextCompat;
import androidx.core.view.WindowInsetsCompat;

import com.termux.R;
import com.termux.app.api.file.FileReceiverActivity;
import com.termux.app.terminal.TermuxActivityRootView;
import com.termux.app.terminal.TermuxTerminalSessionActivityClient;
import com.termux.app.terminal.io.TermuxTerminalExtraKeys;
import com.termux.shared.activities.ReportActivity;
import com.termux.shared.activity.ActivityUtils;
import com.termux.shared.activity.media.AppCompatActivityUtils;
import com.termux.shared.data.IntentUtils;
import com.termux.shared.android.PermissionUtils;
import com.termux.shared.data.DataUtils;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.TermuxConstants.TERMUX_APP.TERMUX_ACTIVITY;
import com.termux.app.activities.HelpActivity;
import com.termux.app.activities.SettingsActivity;
import com.termux.shared.termux.crash.TermuxCrashUtils;
import com.termux.shared.termux.settings.preferences.TermuxAppSharedPreferences;
import com.termux.app.terminal.TermuxSessionsListViewController;
import com.termux.app.terminal.TermuxSessionTabsController;
import com.termux.app.terminal.TermuxTerminalViewClient;
import com.termux.app.terminal.io.FullScreenWorkAround;
import com.termux.shared.termux.extrakeys.ExtraKeysView;
import com.termux.shared.termux.extrakeys.ColorSchemeUtils;
import com.termux.shared.termux.interact.TextInputDialogUtils;
import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxUtils;
import com.termux.shared.termux.settings.properties.TermuxAppSharedProperties;
import com.termux.shared.termux.theme.TermuxThemeUtils;
import com.termux.shared.theme.NightMode;
import com.termux.shared.theme.ThemeUtils;
import com.termux.shared.view.ViewUtils;
import com.termux.terminal.TerminalSession;
import com.termux.terminal.TerminalSessionClient;
import com.termux.shared.shell.command.ExecutionCommand;
import com.termux.shared.termux.shell.command.runner.terminal.TermuxSession;
import com.termux.view.TerminalView;
import com.termux.view.TerminalViewClient;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * A terminal emulator activity.
 * <p/>
 * See
 * <ul>
 * <li>http://www.mongrel-phones.com.au/default/how_to_make_a_local_service_and_bind_to_it_in_android</li>
 * <li>https://code.google.com/p/android/issues/detail?id=6426</li>
 * </ul>
 * about memory leaks.
 */
public final class TermuxActivity extends AppCompatActivity implements ServiceConnection {

    /**
     * The connection to the {@link TermuxService}. Requested in {@link #onCreate(Bundle)} with a call to
     * {@link #bindService(Intent, ServiceConnection, int)}, and obtained and stored in
     * {@link #onServiceConnected(ComponentName, IBinder)}.
     */
    TermuxService mTermuxService;

    /**
     * The {@link TerminalView} shown in  {@link TermuxActivity} that displays the terminal.
     */
    TerminalView mTerminalView;

    /**
     *  The {@link TerminalViewClient} interface implementation to allow for communication between
     *  {@link TerminalView} and {@link TermuxActivity}.
     */
    TermuxTerminalViewClient mTermuxTerminalViewClient;

    /**
     *  The {@link TerminalSessionClient} interface implementation to allow for communication between
     *  {@link TerminalSession} and {@link TermuxActivity}.
     */
    TermuxTerminalSessionActivityClient mTermuxTerminalSessionActivityClient;

    /**
     * Termux app shared preferences manager.
     */
    private TermuxAppSharedPreferences mPreferences;

    /**
     * Termux app SharedProperties loaded from termux.properties
     */
    private TermuxAppSharedProperties mProperties;

    /**
     * The root view of the {@link TermuxActivity}.
     */
    TermuxActivityRootView mTermuxActivityRootView;

    /**
     * The space at the bottom of {@link @mTermuxActivityRootView} of the {@link TermuxActivity}.
     */
    View mTermuxActivityBottomSpaceView;

    /**
     * The terminal extra keys view.
     */
    ExtraKeysView mExtraKeysView;

    /**
     * The client for the {@link #mExtraKeysView}.
     */
    TermuxTerminalExtraKeys mTermuxTerminalExtraKeys;

    /**
     * The termux sessions list controller.
     */
    TermuxSessionsListViewController mTermuxSessionListViewController;

    /**
     * The termux session tabs controller.
     */
    TermuxSessionTabsController mTermuxSessionTabsController;

    /**
     * The {@link TermuxActivity} broadcast receiver for various things like terminal style configuration changes.
     */
    private final BroadcastReceiver mTermuxActivityBroadcastReceiver = new TermuxActivityBroadcastReceiver();

    /**
     * The last toast shown, used cancel current toast before showing new in {@link #showToast(String, boolean)}.
     */
    Toast mLastToast;

    /**
     * If between onResume() and onStop(). Note that only one session is in the foreground of the terminal view at the
     * time, so if the session causing a change is not in the foreground it should probably be treated as background.
     */
    private boolean mIsVisible;

    /**
     * True once onPause() has run (app going to background / screen turning off).
     * Used to suppress the IME-hidden handler that would otherwise close the text
     * input panel when the soft keyboard is dismissed by the system on pause.
     */
    private boolean mIsPaused = false;

    /**
     * If onResume() was called after onCreate().
     */
    private boolean mIsOnResumeAfterOnCreate = false;

    /**
     * If activity was restarted like due to call to {@link #recreate()} after receiving
     * {@link TERMUX_ACTIVITY#ACTION_RELOAD_STYLE}, system dark night mode was changed or activity
     * was killed by android.
     */
    private boolean mIsActivityRecreated = false;

    /**
     * The {@link TermuxActivity} is in an invalid state and must not be run.
     */
    private boolean mIsInvalidState;

    private int mNavBarHeight;
    // Tracks the last known IME (soft keyboard) visibility so we can react to it
    // being hidden while the text input panel is open.
    private boolean mSoftKeyboardVisible = false;

    /**
     * True for a short window right after onStart(), i.e. just after the app
     * returned from the background / was recreated. Used to suppress the
     * IME-hidden auto-close of the text input panel: on return the system may
     * emit transient "IME visible -> hidden" insets frames (especially if the
     * keyboard was active before backgrounding, or on a config change). Without
     * this guard the panel would be closed by that spurious transition. The flag
     * is cleared shortly after by a delayed handler, so normal in-foreground
     * keyboard dismissal still closes the panel as expected.
     */
    private boolean mJustResumed = false;

    // Per-session text input content, keyed by TerminalSession.mHandle,
    // so each tab keeps its own input field text across tab switches.
    private final HashMap<String, String> mTextInputPerSession = new HashMap<>();

    // Per-session whether the text input panel is open, keyed by TerminalSession.mHandle.
    private final HashMap<String, Boolean> mTextInputVisiblePerSession = new HashMap<>();

    // Per-session whether focus (and thus input) is on the text input panel
    // (true) or on the terminal view (false), keyed by TerminalSession.mHandle.
    private final HashMap<String, Boolean> mFocusOnInputPerSession = new HashMap<>();

    // Per-session caret (cursor) position in the text input field, keyed by
    // TerminalSession.mHandle, so each tab restores its own caret on switch.
    private final HashMap<String, Integer> mTextInputCaretPerSession = new HashMap<>();

    private float mTerminalToolbarDefaultHeight;

    // ---- Sent-message history (shown as a context menu on pencil-button swipe) ----

    /**
     * Ordered list of previously sent (finished) input-panel messages. Newest is
     * at index 0. Deduplicated by content: re-sending an existing message moves
     * it back to the front. Displayed bottom-to-top (first = bottom of the popup),
     * so the newest sits at the bottom, nearest the pencil button.
     */
    private final ArrayList<String> mMessageHistory = new ArrayList<>();

    /** The popup showing the message history while the pencil button is held. */
    @Nullable private PopupWindow mHistoryPopup;

    /** Item views inside the history popup, index-aligned with the displayed order. */
    private final ArrayList<TextView> mHistoryItemViews = new ArrayList<>();

    /** The scrollable container inside the popup, used for edge auto-scroll. */
    @Nullable private android.widget.ScrollView mHistoryScroll;

    /** Last finger Y (screen) while the popup is open, for continuous edge scroll. */
    private float mHistoryFingerY = 0f;

    /** True while the edge auto-scroll loop is scheduled/running. */
    private boolean mHistoryAutoScrolling = false;

    /** Solid highlight fill for the item under the finger (theme accent). */
    private int mHistoryHighlightFill = 0;

    /** Default (non-highlighted) item text colour. */
    private int mHistoryTextColor = 0;

    /** Currently highlighted history item index while dragging, or -1 for none. */
    private int mHistoryHighlightIndex = -1;

    /** Default max number of remembered messages (overridable in Settings). */
    private static final int MESSAGE_HISTORY_MAX_DEFAULT = 20;

    /** Live max number of remembered messages, read from Settings (default 20). */
    private int mMessageHistoryMax = MESSAGE_HISTORY_MAX_DEFAULT;

    /** Tag value marking the synthetic "Clear" row (clears the input field). */
    private static final int MESSAGE_HISTORY_CLEAR_TAG = -2;

    /** Tag value marking the top "Clear message history…" row (wipes all history). */
    private static final int MESSAGE_HISTORY_CLEAR_ALL_TAG = -3;

    /** Max popup height in dp (bounded, never edge-to-edge); scrolls beyond this. */
    private static final int MESSAGE_HISTORY_POPUP_MAX_HEIGHT_DP = 520;

    /** Gap in dp between the button's top and the popup's bottom edge. */
    private static final int MESSAGE_HISTORY_POPUP_GAP_DP = 24;

    /** Recently visited directories, newest first (index 0). Deduplicated by path. */
    private final ArrayList<String> mDirectoryHistory = new ArrayList<>();

    /** Live max number of remembered directories (overridable, default 20). */
    private int mDirectoryHistoryMax = DIRECTORY_HISTORY_MAX_DEFAULT;

    /** Tag marking the top "CLEAR HISTORY…" row of the directory-history popup. */
    private static final int DIRECTORY_HISTORY_CLEAR_ALL_TAG = -2;

    /** Default max number of remembered directories. */
    private static final int DIRECTORY_HISTORY_MAX_DEFAULT = 20;

    /** Pref key (in termux_prefs) persisting the directory history JSON array. */
    private static final String PREF_DIRECTORY_HISTORY = "directory_history";

    /** Pref key (in termux_prefs) toggling restore-open-tabs-on-launch (default on). */
    private static final String PREF_RESTORE_SESSIONS = "restore_sessions";

    /** Pref key (in termux_prefs) persisting the open-tabs snapshot JSON. */
    private static final String PREF_SESSION_SNAPSHOT = "session_snapshot";

    private static final int CONTEXT_MENU_SELECT_URL_ID = 0;
    private static final int CONTEXT_MENU_SHARE_TRANSCRIPT_ID = 1;
    private static final int CONTEXT_MENU_SHARE_SELECTED_TEXT = 10;
    private static final int CONTEXT_MENU_AUTOFILL_USERNAME = 11;
    private static final int CONTEXT_MENU_AUTOFILL_PASSWORD = 2;
    private static final int CONTEXT_MENU_RESET_TERMINAL_ID = 3;
    private static final int CONTEXT_MENU_KILL_PROCESS_ID = 4;
    private static final int CONTEXT_MENU_STYLING_ID = 5;
    private static final int CONTEXT_MENU_TOGGLE_KEEP_SCREEN_ON = 6;
    private static final int CONTEXT_MENU_HELP_ID = 7;
    private static final int CONTEXT_MENU_SETTINGS_ID = 8;
    private static final int CONTEXT_MENU_REPORT_ID = 9;

    private static final String ARG_TERMINAL_TOOLBAR_TEXT_INPUT = "terminal_toolbar_text_input";
    private static final String ARG_TEXT_INPUT_PER_SESSION = "text_input_per_session";
    private static final String ARG_TEXT_INPUT_CARET_PER_SESSION = "text_input_caret_per_session";
    private static final String ARG_TEXT_INPUT_VISIBLE_PER_SESSION = "text_input_visible_per_session";
    private static final String ARG_FOCUS_ON_INPUT_PER_SESSION = "focus_on_input_per_session";
    private static final String ARG_ACTIVITY_RECREATED = "activity_recreated";
    private static final String PREF_TEXT_INPUT_VISIBLE = "text_input_visible";
    private static final String PREF_MESSAGE_HISTORY = "message_history";

    private static final String LOG_TAG = "TermuxActivity";

    @Override
    public void onCreate(Bundle savedInstanceState) {
        Logger.logDebug(LOG_TAG, "onCreate");
        mIsOnResumeAfterOnCreate = true;

        if (savedInstanceState != null)
            mIsActivityRecreated = savedInstanceState.getBoolean(ARG_ACTIVITY_RECREATED, false);

        // Restore per-session text input content saved before recreation.
        if (savedInstanceState != null) {
            android.os.Bundle textInputBundle = savedInstanceState.getBundle(ARG_TEXT_INPUT_PER_SESSION);
            if (textInputBundle != null) {
                for (String key : textInputBundle.keySet()) {
                    String value = textInputBundle.getString(key);
                    if (value != null) mTextInputPerSession.put(key, value);
                }
            }

            // Restore per-session panel visibility saved before recreation.
            android.os.Bundle visBundle = savedInstanceState.getBundle(ARG_TEXT_INPUT_VISIBLE_PER_SESSION);
            if (visBundle != null) {
                for (String key : visBundle.keySet()) {
                    mTextInputVisiblePerSession.put(key, visBundle.getBoolean(key));
                }
            }

            // Restore per-session focus (panel vs terminal) saved before recreation.
            android.os.Bundle focusBundle = savedInstanceState.getBundle(ARG_FOCUS_ON_INPUT_PER_SESSION);
            if (focusBundle != null) {
                for (String key : focusBundle.keySet()) {
                    mFocusOnInputPerSession.put(key, focusBundle.getBoolean(key));
                }
            }

            // Restore per-session caret position saved before recreation.
            android.os.Bundle caretBundle = savedInstanceState.getBundle(ARG_TEXT_INPUT_CARET_PER_SESSION);
            if (caretBundle != null) {
                for (String key : caretBundle.keySet()) {
                    mTextInputCaretPerSession.put(key, caretBundle.getInt(key));
                }
            }
        }

        // Delete ReportInfo serialized object files from cache older than 14 days
        ReportActivity.deleteReportInfoFilesOlderThanXDays(this, 14, false);

        // Load Termux app SharedProperties from disk
        mProperties = TermuxAppSharedProperties.getProperties();
        reloadProperties();

        applyTermuxTheme();

        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_termux);

        // Apply the user's screen-orientation choice (Settings -> Screen orientation).
        applyScreenOrientation(this);

        // Load termux shared preferences
        // This will also fail if TermuxConstants.TERMUX_PACKAGE_NAME does not equal applicationId
        mPreferences = TermuxAppSharedPreferences.build(this, true);
        if (mPreferences == null) {
            // An AlertDialog should have shown to kill the app, so we don't continue running activity code
            mIsInvalidState = true;
            return;
        }

        setMargins();

        mTermuxActivityRootView = findViewById(R.id.activity_termux_root_view);
        mTermuxActivityRootView.setActivity(this);
        mTermuxActivityBottomSpaceView = findViewById(R.id.activity_termux_bottom_space_view);
        mTermuxActivityRootView.setOnApplyWindowInsetsListener(new TermuxActivityRootView.WindowInsetsListener());

        View content = findViewById(android.R.id.content);
        content.setOnApplyWindowInsetsListener((v, insets) -> {
            mNavBarHeight = insets.getSystemWindowInsetBottom();

            // React to the soft keyboard (IME) being hidden while the text input
            // panel is open: switch the slot back to the extra keys panel.
            // Skip this while paused (app backgrounded / screen off) — Android
            // dismisses the IME on pause, but we must keep the panel open so it
            // stays visible when the app returns to the foreground.
            boolean imeVisible = WindowInsetsCompat.toWindowInsetsCompat(insets)
                    .isVisible(WindowInsetsCompat.Type.ime());
            if (!mIsPaused && !mJustResumed && mSoftKeyboardVisible && !imeVisible && isTextInputVisible()) {
                setTextInputVisible(false);
                updateToggleTextInputButtonIcon();
            }
            mSoftKeyboardVisible = imeVisible;

            return insets;
        });

        if (mProperties.isUsingFullScreen()) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        }

        setTermuxTerminalViewAndClients();

        setTerminalToolbarView(savedInstanceState);

        setSettingsButtonView();

        setNewSessionButtonView();

        setToggleTextInputButtonView();

        setToggleKeyboardView();

        registerForContextMenu(mTerminalView);

        FileReceiverActivity.updateFileReceiverActivityComponentsState(this);

        // Register broadcast receiver in onCreate so it works even when activity is in background
        registerTermuxActivityBroadcastReceiver();

        try {
            // Start the {@link TermuxService} and make it run regardless of who is bound to it
            Intent serviceIntent = new Intent(this, TermuxService.class);
            startService(serviceIntent);

            // Attempt to bind to the service, this will call the {@link #onServiceConnected(ComponentName, IBinder)}
            // callback if it succeeds.
            if (!bindService(serviceIntent, this, 0))
                throw new RuntimeException("bindService() failed");
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG,"TermuxActivity failed to start TermuxService", e);
            Logger.showToast(this,
                getString(e.getMessage() != null && e.getMessage().contains("app is in background") ?
                    R.string.error_termux_service_start_failed_bg : R.string.error_termux_service_start_failed_general),
                true);
            mIsInvalidState = true;
            return;
        }

        // Send the {@link TermuxConstants#BROADCAST_TERMUX_OPENED} broadcast to notify apps that Termux
        // app has been opened.
        TermuxUtils.sendTermuxOpenedBroadcast(this);
    }

    @Override
    public void onStart() {
        super.onStart();

        Logger.logDebug(LOG_TAG, "onStart");

        if (mIsInvalidState) return;

        mIsVisible = true;

        // Reset the IME-visibility latch on (re)start. It may be left stale as
        // true from before the app was backgrounded, when the soft keyboard was
        // dismissed by the system. Without this reset, the first insets after
        // resume (with IME still hidden) would falsely read as "IME just hidden
        // while panel open" and close the text input panel. onStart runs before
        // the insets listener fires on resume.
        mSoftKeyboardVisible = false;

        // Open the "just resumed" window: suppress the IME-hidden auto-close of
        // the panel until the transient post-return insets frames have settled.
        // Cleared after a short delay so ordinary keyboard dismissal still works.
        mJustResumed = true;
        new Handler(Looper.getMainLooper()).postDelayed(() -> mJustResumed = false, 400);

        if (mTermuxTerminalSessionActivityClient != null)
            mTermuxTerminalSessionActivityClient.onStart();

        if (mTermuxTerminalViewClient != null)
            mTermuxTerminalViewClient.onStart();

        if (mPreferences.isTerminalMarginAdjustmentEnabled())
            addTermuxActivityRootViewGlobalLayoutListener();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);

        // When Termux regains focus (e.g. after returning from Settings), apply
        // the screen-orientation choice immediately so the change is visible
        // without restarting the app.
        if (hasFocus) {
            applyScreenOrientation(this);
        }
    }

    @Override
    public void onResume() {
        super.onResume();

        Logger.logVerbose(LOG_TAG, "onResume");

        if (mIsInvalidState) return;

        // Snapshot the remembered focus target BEFORE the terminal view client runs.
        // mTermuxTerminalViewClient.onResume() -> setSoftKeyboardState() calls
        // terminalView.requestFocus(), which fires the terminal's onFocusChange
        // listener and overwrites mFocusOnInputPerSession to "terminal" (false) —
        // clobbering the "focus was on panel" state we saved in onStop(). We
        // re-assert this snapshot before restoring, so the panel focus + caret is
        // honoured on return from the background.
        final boolean resumeFocusWasOnInput =
            !mIsOnResumeAfterOnCreate && isFocusOnInputForSession(getCurrentSession());

        if (mTermuxTerminalSessionActivityClient != null)
            mTermuxTerminalSessionActivityClient.onResume();

        if (mTermuxTerminalViewClient != null)
            mTermuxTerminalViewClient.onResume();

        // Check if a crash happened on last run of the app or if a plugin crashed and show a
        // notification with the crash details if it did
        TermuxCrashUtils.notifyAppCrashFromCrashLogFile(this, LOG_TAG);

        // On return from the background (not a fresh create — that path restores
        // with applyFocus=false at startup), re-apply the current session's panel
        // visibility together with its remembered focus target and caret, exactly
        // as a tab switch does. Restores keyboard-on-panel or focus-on-terminal.
        if (!mIsOnResumeAfterOnCreate) {
            // Restore the pre-background focus target clobbered by the client above.
            setFocusOnInputForCurrentSession(resumeFocusWasOnInput);
            applyTextInputVisibilityForSession(getCurrentSession(), true);
        }

        mIsOnResumeAfterOnCreate = false;
        mIsPaused = false;
    }

    @Override
    protected void onPause() {
        super.onPause();

        // Mark paused so the IME-hidden handler (WindowInsetsListener) does not
        // close the text input panel when the system dismisses the soft keyboard
        // on background. The panel must stay open and reappear on resume.
        mIsPaused = true;
    }

    @Override
    protected void onStop() {
        super.onStop();

        Logger.logDebug(LOG_TAG, "onStop");

        if (mIsInvalidState) return;

        mIsVisible = false;

        // Dismiss the message-history popup if it is somehow still showing, to
        // avoid a leaked window when the activity goes to the background.
        dismissMessageHistoryPopup();

        // Remember, for the current session, whether focus was on the input panel
        // or the terminal, and persist the caret position — exactly as a tab
        // switch does before leaving a tab. This lets onResume() restore the same
        // focus target and caret when the app returns from the background.
        final EditText toolbarTextInput = findViewById(R.id.terminal_toolbar_text_input);
        setFocusOnInputForCurrentSession(toolbarTextInput != null && toolbarTextInput.hasFocus());
        saveTextInputForCurrentSession();

        if (mTermuxTerminalSessionActivityClient != null)
            mTermuxTerminalSessionActivityClient.onStop();

        // Snapshot open tabs (cwd/name) so a later cold start can reopen them.
        saveSessionSnapshot();

        if (mTermuxTerminalViewClient != null)
            mTermuxTerminalViewClient.onStop();

        removeTermuxActivityRootViewGlobalLayoutListener();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        Logger.logDebug(LOG_TAG, "onDestroy");

        if (mIsInvalidState) return;

        unregisterTermuxActivityBroadcastReceiver();

        if (mTermuxService != null) {
            // Do not leave service and session clients with references to activity.
            mTermuxService.unsetTermuxTerminalSessionClient();
            mTermuxService = null;
        }

        try {
            unbindService(this);
        } catch (Exception e) {
            // ignore.
        }
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle savedInstanceState) {
        Logger.logVerbose(LOG_TAG, "onSaveInstanceState");

        super.onSaveInstanceState(savedInstanceState);
        saveTerminalToolbarTextInput(savedInstanceState);
        savedInstanceState.putBoolean(ARG_ACTIVITY_RECREATED, true);

        // Persist per-session text input content across activity recreation
        // (e.g. rotation, theme change). Keyed by session mHandle.
        if (!mTextInputPerSession.isEmpty()) {
            android.os.Bundle textInputBundle = new android.os.Bundle();
            for (java.util.Map.Entry<String, String> e : mTextInputPerSession.entrySet()) {
                textInputBundle.putString(e.getKey(), e.getValue());
            }
            savedInstanceState.putBundle(ARG_TEXT_INPUT_PER_SESSION, textInputBundle);
        }

        // Persist per-session panel visibility across activity recreation.
        if (!mTextInputVisiblePerSession.isEmpty()) {
            android.os.Bundle visBundle = new android.os.Bundle();
            for (java.util.Map.Entry<String, Boolean> e : mTextInputVisiblePerSession.entrySet()) {
                visBundle.putBoolean(e.getKey(), e.getValue());
            }
            savedInstanceState.putBundle(ARG_TEXT_INPUT_VISIBLE_PER_SESSION, visBundle);
        }

        // Persist per-session focus (panel vs terminal) across activity recreation.
        if (!mFocusOnInputPerSession.isEmpty()) {
            android.os.Bundle focusBundle = new android.os.Bundle();
            for (java.util.Map.Entry<String, Boolean> e : mFocusOnInputPerSession.entrySet()) {
                focusBundle.putBoolean(e.getKey(), e.getValue());
            }
            savedInstanceState.putBundle(ARG_FOCUS_ON_INPUT_PER_SESSION, focusBundle);
        }

        // Persist per-session caret position across activity recreation.
        if (!mTextInputCaretPerSession.isEmpty()) {
            android.os.Bundle caretBundle = new android.os.Bundle();
            for (java.util.Map.Entry<String, Integer> e : mTextInputCaretPerSession.entrySet()) {
                caretBundle.putInt(e.getKey(), e.getValue());
            }
            savedInstanceState.putBundle(ARG_TEXT_INPUT_CARET_PER_SESSION, caretBundle);
        }
    }





    /**
     * Part of the {@link ServiceConnection} interface. The service is bound with
     * {@link #bindService(Intent, ServiceConnection, int)} in {@link #onCreate(Bundle)} which will cause a call to this
     * callback method.
     */
    @Override
    public void onServiceConnected(ComponentName componentName, IBinder service) {
        Logger.logDebug(LOG_TAG, "onServiceConnected");

        mTermuxService = ((TermuxService.LocalBinder) service).service;

        setTermuxSessionsListView();

        final Intent intent = getIntent();
        setIntent(null);

        // After a data restore, close all stale sessions and open a fresh one so the user
        // is not left looking at a terminal whose shell/config no longer matches the container.
        boolean resetSessions = intent != null
            && intent.getBooleanExtra(TERMUX_ACTIVITY.EXTRA_RESET_SESSIONS, false);
        if (resetSessions && mTermuxService != null) {
            mTermuxService.removeAllTermuxSessions();
        }

        if (mTermuxService.isTermuxSessionsEmpty()) {
            if (mIsVisible) {
                TermuxInstaller.setupBootstrapIfNeeded(TermuxActivity.this, () -> {
                    if (mTermuxService == null) return; // Activity might have been destroyed.
                    try {
                        boolean launchFailsafe = false;
                        if (intent != null && intent.getExtras() != null) {
                            launchFailsafe = intent.getExtras().getBoolean(TERMUX_ACTIVITY.EXTRA_FAILSAFE_SESSION, false);
                        }
                        // Reopen the tabs from the last session if the feature is on and a
                        // snapshot exists; otherwise fall back to a single fresh session.
                        if (!launchFailsafe && restoreSessionSnapshot()) return;
                        mTermuxTerminalSessionActivityClient.addNewSession(launchFailsafe, null);
                    } catch (WindowManager.BadTokenException e) {
                        // Activity finished - ignore.
                    }
                });
            } else {
                // The service connected while not in foreground - just bail out.
                finishActivityIfNotFinishing();
            }
        } else {
            // If termux was started from launcher "New session" shortcut and activity is recreated,
            // then the original intent will be re-delivered, resulting in a new session being re-added
            // each time.
            if (!mIsActivityRecreated && intent != null && Intent.ACTION_RUN.equals(intent.getAction())) {
                // Android 7.1 app shortcut from res/xml/shortcuts.xml.
                boolean isFailSafe = intent.getBooleanExtra(TERMUX_ACTIVITY.EXTRA_FAILSAFE_SESSION, false);
                mTermuxTerminalSessionActivityClient.addNewSession(isFailSafe, null);
            } else {
                mTermuxTerminalSessionActivityClient.setCurrentSession(mTermuxTerminalSessionActivityClient.getCurrentStoredSessionOrLast());
            }
        }

        // Update the {@link TerminalSession} and {@link TerminalEmulator} clients.
        mTermuxService.setTermuxTerminalSessionClient(mTermuxTerminalSessionActivityClient);

        // Re-apply terminal fonts/colors now that the session is bound. This is required when the
        // activity was recreated (e.g. on a system day/night theme change) while the session was
        // not yet attached, so checkForFontAndColors() called earlier had no session to repaint.
        if (mTermuxTerminalSessionActivityClient != null)
            mTermuxTerminalSessionActivityClient.checkForFontAndColors();
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
        Logger.logDebug(LOG_TAG, "onServiceDisconnected");

        // Respect being stopped from the {@link TermuxService} notification action.
        finishActivityIfNotFinishing();
    }






    private void reloadProperties() {
        mProperties.loadTermuxPropertiesFromDisk();

        if (mTermuxTerminalViewClient != null)
            mTermuxTerminalViewClient.onReloadProperties();
    }



    private void applyTermuxTheme() {
        // Update NightMode.APP_NIGHT_MODE
        TermuxThemeUtils.setAppNightMode(mProperties.getNightMode());

        // Set activity night mode. If NightMode.SYSTEM is set, then android will automatically
        // trigger recreation of activity when uiMode/dark mode configuration is changed so that
        // day or night theme takes affect.
        AppCompatActivityUtils.setNightMode(this, NightMode.getAppNightMode().getName(), true);

        // NOTE: The terminal color scheme (checkForFontAndColors()) is intentionally NOT applied
        // here. At this point (onCreate line ~218) setContentView() has not run yet, so
        // mTerminalView is still null and mTermuxTerminalSessionActivityClient has not been
        // constructed. Applying it here would be a silent no-op (getTerminalView() == null),
        // which is why a hot theme swap (recreate / day-night switch) left the terminal
        // unpainted while only the toolbar+status bar updated. The terminal repaint is done
        // in setTermuxTerminalViewAndClients() once the view and client exist.
    }

    /**
     * Apply the user-selected screen orientation to the given activity.
     * Reads "screen_orientation" from the "termux_prefs" file (written by the
     * Settings screen-orientation list). Valid values: "sensor", "portrait",
     * "landscape". Default is "sensor" on tablets (smallestScreenWidthDp >= 600)
     * and "portrait" on phones.
     */
    public static void applyScreenOrientation(@NonNull Activity activity) {
        final SharedPreferences prefs = activity.getSharedPreferences("termux_prefs", MODE_PRIVATE);
        final boolean isTablet = activity.getResources().getConfiguration().smallestScreenWidthDp >= 600;
        final String value = prefs.getString("screen_orientation",
                isTablet ? "sensor" : "portrait");
        final int orientation;
        switch (value) {
            case "portrait":
                orientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
                break;
            case "landscape":
                orientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
                break;
            case "portrait_follow_sensor":
                orientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT;
                break;
            case "landscape_follow_sensor":
                orientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE;
                break;
            case "sensor":
            default:
                orientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR;
                break;
        }
        activity.setRequestedOrientation(orientation);
    }

    private void setMargins() {
        RelativeLayout relativeLayout = findViewById(R.id.activity_termux_root_relative_layout);
        int marginHorizontal = mProperties.getTerminalMarginHorizontal();
        int marginVertical = mProperties.getTerminalMarginVertical();
        ViewUtils.setLayoutMarginsInDp(relativeLayout, marginHorizontal, marginVertical, marginHorizontal, marginVertical);
    }



    public void addTermuxActivityRootViewGlobalLayoutListener() {
        getTermuxActivityRootView().getViewTreeObserver().addOnGlobalLayoutListener(getTermuxActivityRootView());
    }

    public void removeTermuxActivityRootViewGlobalLayoutListener() {
        if (getTermuxActivityRootView() != null)
            getTermuxActivityRootView().getViewTreeObserver().removeOnGlobalLayoutListener(getTermuxActivityRootView());
    }



    private void setTermuxTerminalViewAndClients() {
        // Set termux terminal view and session clients
        mTermuxTerminalSessionActivityClient = new TermuxTerminalSessionActivityClient(this);
        mTermuxTerminalViewClient = new TermuxTerminalViewClient(this, mTermuxTerminalSessionActivityClient);

        // Set termux terminal view
        mTerminalView = findViewById(R.id.terminal_view);
        mTerminalView.setTerminalViewClient(mTermuxTerminalViewClient);

        if (mTermuxTerminalViewClient != null)
            mTermuxTerminalViewClient.onCreate();

        if (mTermuxTerminalSessionActivityClient != null)
            mTermuxTerminalSessionActivityClient.onCreate();
    }

    private void setTermuxSessionsListView() {
        // Initialize session tabs controller
        mTermuxSessionTabsController = new TermuxSessionTabsController(this);
        
        // Set up new session tab button
        ImageButton newSessionTabButton = findViewById(R.id.new_session_tab_button);
        if (newSessionTabButton != null) {
            // Tap opens a new tab (default cwd); long-press creates a named session.
            newSessionTabButton.setOnClickListener(v -> mTermuxTerminalSessionActivityClient.addNewSession(false, null));
            newSessionTabButton.setOnLongClickListener(v -> {
                TextInputDialogUtils.textInput(TermuxActivity.this, R.string.title_create_named_session, null,
                    R.string.action_create_named_session_confirm, text -> mTermuxTerminalSessionActivityClient.addNewSession(false, text),
                    R.string.action_new_session_failsafe, text -> mTermuxTerminalSessionActivityClient.addNewSession(true, text),
                    -1, null, null);
                return true;
            });

            // A swipe-up (drag off the button) opens the directory-history popup,
            // mirroring the pencil button's gesture. We return false from the
            // touch listener until a swipe is actually detected, so a plain tap or
            // long-press still reaches the click / long-click listeners above.
            final int touchSlop = ViewConfiguration.get(this).getScaledTouchSlop();
            final float[] downXY = new float[2];
            final boolean[] gestureActive = { false };
            final boolean[] swipeConsumed = { false };

            newSessionTabButton.setOnTouchListener((v, event) -> {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downXY[0] = event.getRawX();
                        downXY[1] = event.getRawY();
                        gestureActive[0] = true;
                        swipeConsumed[0] = false;
                        return false;   // let click / long-press proceed
                    case MotionEvent.ACTION_MOVE: {
                        if (!gestureActive[0]) return false;
                        // Popup already open: track the finger to highlight items.
                        if (isHistoryPopupShowing()) {
                            updateHistoryHighlight(event.getRawX(), event.getRawY());
                            return true;
                        }
                        float dy = event.getRawY() - downXY[1];
                        float dx = event.getRawX() - downXY[0];
                        if (dy < -touchSlop && Math.abs(dy) > Math.abs(dx)
                                && shouldShowDirectoryHistoryPopup()) {
                            v.setPressed(false);
                            swipeConsumed[0] = true;
                            showDirectoryHistoryPopup(v);
                            return true;   // consume: cancels pending click/long-press
                        }
                        return false;
                    }
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL: {
                        boolean wasPopup = isHistoryPopupShowing();
                        if (wasPopup) {
                            int selected = mHistoryHighlightIndex;
                            dismissMessageHistoryPopup();
                            if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                                if (selected == DIRECTORY_HISTORY_CLEAR_ALL_TAG) {
                                    confirmClearDirectoryHistory();
                                } else if (selected >= 0 && selected < mDirectoryHistory.size()) {
                                    onHistoryDirectoryPicked(mDirectoryHistory.get(selected));
                                }
                            }
                            gestureActive[0] = false;
                            return true;
                        }
                        gestureActive[0] = false;
                        return false;   // not a swipe -> allow onClick
                    }
                }
                return false;
            });
        }
    }



    private void setTerminalToolbarView(Bundle savedInstanceState) {
        mTermuxTerminalExtraKeys = new TermuxTerminalExtraKeys(this, mTerminalView,
            mTermuxTerminalViewClient, mTermuxTerminalSessionActivityClient);

        final LinearLayout terminalToolbarContainer = getTerminalToolbarContainer();
        if (mPreferences.shouldShowTerminalToolbar()) terminalToolbarContainer.setVisibility(View.VISIBLE);

        // Set default height for toolbar items (37.5dp as in original layout)
        mTerminalToolbarDefaultHeight = (int) (37.5f * getResources().getDisplayMetrics().density);

        // Setup ExtraKeysView
        ExtraKeysView extraKeysView = findViewById(R.id.terminal_toolbar_extra_keys);
        extraKeysView.setExtraKeysViewClient(mTermuxTerminalExtraKeys);
        extraKeysView.setButtonTextAllCaps(mProperties.shouldExtraKeysTextBeAllCaps());
        setExtraKeysView(extraKeysView);

        // apply extra keys fix if enabled in prefs
        if (mProperties.isUsingFullScreen() && mProperties.isUsingFullScreenWorkAround()) {
            FullScreenWorkAround.apply(this);
        }

        setTerminalToolbarHeight();

        // Load extra keys buttons - needed after activity recreate (theme change)
        if (mTermuxTerminalExtraKeys.getExtraKeysInfo() != null) {
            extraKeysView.reload(mTermuxTerminalExtraKeys.getExtraKeysInfo(), mTerminalToolbarDefaultHeight);
        }

        // Setup text input
        final EditText editText = findViewById(R.id.terminal_toolbar_text_input);
        // Per-session input text is restored via restoreTextInputForSession() on tab switch
        // and panel show; on first creation just clear it.
        editText.setText("");

        // Record per-session focus: when the text input gains focus, remember that
        // input goes to the panel (true) for the current session.
        editText.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) setFocusOnInputForCurrentSession(true);
        });

        editText.setOnEditorActionListener((v, actionId, event) -> {
            TerminalSession session = getCurrentSession();
            if (session != null) {
                if (session.isRunning()) {
                    String textToSend = editText.getText().toString();
                    boolean hasText = textToSend.length() > 0;
                    // Remember non-empty sent messages in the history (dedup, newest first).
                    if (hasText) addToMessageHistory(textToSend);
                    if (!hasText) textToSend = "\r";
                    session.write(textToSend);

                    // Clear the field and the per-session saved text/caret BEFORE
                    // hiding the panel. Otherwise setTextInputVisible(false) ->
                    // saveTextInputForCurrentSession() re-persists the just-sent
                    // text (the field is still non-empty at that point) and it
                    // reappears when the panel is opened again.
                    editText.setText("");
                    mTextInputPerSession.remove(session.mHandle);
                    mTextInputCaretPerSession.remove(session.mHandle);

                    // Always return to the extra keys panel after sending.
                    // The "send" key replaces the newline key on the soft keyboard
                    // (textMultiLine removed), so this fires on every committed send.
                    setTextInputVisible(false);
                    updateToggleTextInputButtonIcon();
                } else {
                    mTermuxTerminalSessionActivityClient.removeFinishedSession(session);
                }
                editText.setText("");
            }
            return true;
        });

        // Restore text input panel visibility state for the current session (if any).
        // Falls back to the legacy global preference for sessions not yet tracked.
        // The text input panel and extra keys share one slot, so they stay inverted.
        // Pass applyFocus=false so we don't pop the keyboard at startup (respects
        // setSoftKeyboardState's startup-hidden preference).
        applyTextInputVisibilityForSession(getCurrentSession(), false);
    }

    private void setTerminalToolbarHeight() {
        final ExtraKeysView extraKeysView = getExtraKeysView();
        if (extraKeysView == null) return;

        ViewGroup.LayoutParams layoutParams = extraKeysView.getLayoutParams();
        layoutParams.height = Math.round(mTerminalToolbarDefaultHeight *
            (mTermuxTerminalExtraKeys.getExtraKeysInfo() == null ? 0 : mTermuxTerminalExtraKeys.getExtraKeysInfo().getMatrix().length) *
            mProperties.getTerminalToolbarHeightScaleFactor());
        extraKeysView.setLayoutParams(layoutParams);
    }

    public void toggleTerminalToolbar() {
        final LinearLayout terminalToolbarContainer = getTerminalToolbarContainer();
        if (terminalToolbarContainer == null) return;

        final boolean showNow = mPreferences.toogleShowTerminalToolbar();
        Logger.showToast(this, (showNow ? getString(R.string.msg_enabling_terminal_toolbar) : getString(R.string.msg_disabling_terminal_toolbar)), true);
        terminalToolbarContainer.setVisibility(showNow ? View.VISIBLE : View.GONE);
    }

    private void saveTerminalToolbarTextInput(Bundle savedInstanceState) {
        if (savedInstanceState == null) return;

        final EditText textInputView = findViewById(R.id.terminal_toolbar_text_input);
        if (textInputView != null) {
            String textInput = textInputView.getText().toString();
            if (!textInput.isEmpty()) savedInstanceState.putString(ARG_TERMINAL_TOOLBAR_TEXT_INPUT, textInput);
        }
    }

    /**
     * Save the current text input field content into the per-session map,
     * keyed by the current TerminalSession.mHandle.
     */
    public void saveTextInputForCurrentSession() {
        final TerminalSession session = getCurrentSession();
        if (session == null) return;
        final EditText textInputView = findViewById(R.id.terminal_toolbar_text_input);
        if (textInputView == null) return;
        String text = textInputView.getText().toString();
        if (!text.isEmpty()) {
            mTextInputPerSession.put(session.mHandle, text);
        } else {
            mTextInputPerSession.remove(session.mHandle);
        }
        // Remember the caret position so it can be restored on tab switch and on
        // panel hide/show for the same session. The EditText keeps its selection
        // even after losing focus, so getSelectionStart() is valid regardless of
        // focus — we always record it (no hasFocus() guard), otherwise hiding the
        // panel after the focus had already moved to the terminal would drop the
        // caret and it would jump to the end on re-open.
        mTextInputCaretPerSession.put(session.mHandle, textInputView.getSelectionStart());
    }

    /**
     * Restore the text input field content for the given session (by mHandle)
     * into the EditText. Called on tab switch and panel show.
     */
    public void restoreTextInputForSession(@Nullable TerminalSession session) {
        final EditText textInputView = findViewById(R.id.terminal_toolbar_text_input);
        if (textInputView == null) return;
        if (session == null) {
            textInputView.setText("");
            return;
        }
        String text = mTextInputPerSession.get(session.mHandle);
        textInputView.setText(text != null ? text : "");
        // Restore the caret position saved for this session (clamped to text length).
        Integer caret = mTextInputCaretPerSession.get(session.mHandle);
        if (caret != null) {
            int pos = Math.min(caret, textInputView.length());
            textInputView.setSelection(pos);
        }
    }

    /**
     * Remove the saved text input content for a session (e.g. when the session is closed),
     * so the per-session map does not grow with stale entries.
     */
    public void clearTextInputForSession(@NonNull TerminalSession session) {
        mTextInputPerSession.remove(session.mHandle);
        mTextInputVisiblePerSession.remove(session.mHandle);
        mFocusOnInputPerSession.remove(session.mHandle);
        mTextInputCaretPerSession.remove(session.mHandle);
    }

    /**
     * Record, for the current session, whether focus (input) is on the text
     * input panel (focusOnInput=true) or on the terminal view (false).
     */
    public void setFocusOnInputForCurrentSession(boolean focusOnInput) {
        final TerminalSession session = getCurrentSession();
        if (session != null) {
            mFocusOnInputPerSession.put(session.mHandle, focusOnInput);
        }
    }

    /**
     * Get whether, for the given session, focus was last on the text input
     * panel (true) or on the terminal view (false). Defaults to false
     * (terminal) for unknown sessions.
     */
    public boolean isFocusOnInputForSession(@Nullable TerminalSession session) {
        if (session != null && mFocusOnInputPerSession.containsKey(session.mHandle)) {
            return mFocusOnInputPerSession.get(session.mHandle);
        }
        return false;
    }



    private void setSettingsButtonView() {
        ImageButton settingsButton = findViewById(R.id.settings_button);
        settingsButton.setOnClickListener(v -> {
            ActivityUtils.startActivity(this, new Intent(this, SettingsActivity.class));
        });
        updateSettingsButtonVisibility();
    }

    /** Whether the tabs-panel settings button is enabled in settings (default: shown). */
    public boolean isSettingsButtonEnabled() {
        return getSharedPreferences("termux_prefs", MODE_PRIVATE).getBoolean("settings_button_enabled", true);
    }

    /** Show/hide the tabs-panel settings button based on the setting. */
    public void updateSettingsButtonVisibility() {
        ImageButton settingsButton = findViewById(R.id.settings_button);
        if (settingsButton != null)
            settingsButton.setVisibility(isSettingsButtonEnabled() ? View.VISIBLE : View.GONE);
    }

    private void setNewSessionButtonView() {
        // New session button is now in the tabs bar, handled in setTermuxSessionsListView
    }

    private void setToggleTextInputButtonView() {
        ImageButton toggleTextInputButton = findViewById(R.id.toggle_text_input_button);
        if (toggleTextInputButton != null) {
            // Load the persisted sent-message history once.
            mMessageHistoryMax = getSharedPreferences("termux_prefs", MODE_PRIVATE)
                    .getInt("message_history_max", MESSAGE_HISTORY_MAX_DEFAULT);
            loadMessageHistory();
            // Load the persisted recent-directories history once.
            mDirectoryHistoryMax = getSharedPreferences("termux_prefs", MODE_PRIVATE)
                    .getInt("directory_history_max", DIRECTORY_HISTORY_MAX_DEFAULT);
            loadDirectoryHistory();

            // A touch listener drives two gestures on the pencil button:
            //   - a plain tap toggles the text input panel (old click behaviour);
            //   - a swipe up (drag off the button) opens the message-history popup,
            //     which stays open while the finger is held; releasing over an item
            //     picks it, releasing elsewhere just dismisses.
            final int touchSlop = ViewConfiguration.get(this).getScaledTouchSlop();
            final float[] downXY = new float[2];
            final boolean[] gestureActive = { false };

            toggleTextInputButton.setOnTouchListener((v, event) -> {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downXY[0] = event.getRawX();
                        downXY[1] = event.getRawY();
                        gestureActive[0] = true;
                        v.setPressed(true);
                        return true;
                    case MotionEvent.ACTION_MOVE: {
                        if (!gestureActive[0]) return true;
                        float dy = event.getRawY() - downXY[1];
                        float dx = event.getRawX() - downXY[0];
                        // Open the history popup once the finger has dragged up past
                        // the touch slop (and the drag is more vertical than sideways).
                        if (!isHistoryPopupShowing()
                                && dy < -touchSlop && Math.abs(dy) > Math.abs(dx)
                                && shouldShowHistoryPopup()) {
                            v.setPressed(false);
                            showMessageHistoryPopup(v);
                        }
                        if (isHistoryPopupShowing()) {
                            updateHistoryHighlight(event.getRawX(), event.getRawY());
                        }
                        return true;
                    }
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL: {
                        v.setPressed(false);
                        boolean wasPopup = isHistoryPopupShowing();
                        if (wasPopup) {
                            int selected = mHistoryHighlightIndex;
                            dismissMessageHistoryPopup();
                            if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                                if (selected == MESSAGE_HISTORY_CLEAR_ALL_TAG) {
                                    confirmClearAllHistory();
                                } else if (selected == MESSAGE_HISTORY_CLEAR_TAG) {
                                    clearInputToHistory();
                                } else if (selected >= 0 && selected < mMessageHistory.size()) {
                                    onHistoryMessagePicked(mMessageHistory.get(selected));
                                }
                            }
                        } else if (gestureActive[0]
                                && event.getActionMasked() == MotionEvent.ACTION_UP) {
                            // No popup was opened: treat as a plain tap -> toggle panel.
                            boolean currentlyVisible = isTextInputVisible();
                            setTextInputVisible(!currentlyVisible);
                            updateToggleTextInputButtonIcon();
                        }
                        gestureActive[0] = false;
                        return true;
                    }
                }
                return false;
            });

            // Set initial visibility based on settings
            boolean enabled = isTextInputEnabled();
            toggleTextInputButton.setVisibility(enabled ? View.VISIBLE : View.GONE);

            // Set initial icon if visible
            if (enabled) {
                updateToggleTextInputButtonIcon();
            }
        }
    }

    private void updateToggleTextInputButtonIcon() {
        ImageButton toggleTextInputButton = findViewById(R.id.toggle_text_input_button);
        if (toggleTextInputButton != null) {
            boolean isVisible = isTextInputVisible();
            toggleTextInputButton.setImageResource(isVisible ? R.drawable.ic_keyboard_hide : R.drawable.ic_keyboard_show);
            toggleTextInputButton.setContentDescription(getString(R.string.action_toggle_text_input));
        }
    }

    // ============================================================================
    //  Sent-message history
    // ============================================================================

    /**
     * Add a just-sent message to the history. Deduplicated by content: if the
     * same text already exists it is removed and re-inserted at the front, so a
     * re-sent message rises to the front (index 0), which is rendered at the
     * BOTTOM of the popup (newest = bottom, nearest the button). Persists the list.
     */
    private void addToMessageHistory(@NonNull String message) {
        if (TextUtils.isEmpty(message)) return;
        mMessageHistory.remove(message);          // dedup by content
        mMessageHistory.add(0, message);          // newest first
        while (mMessageHistory.size() > mMessageHistoryMax) {
            mMessageHistory.remove(mMessageHistory.size() - 1);
        }
        saveMessageHistory();
    }

    /** Load the persisted message history from preferences (JSON array). */
    private void loadMessageHistory() {
        mMessageHistory.clear();
        String json = getSharedPreferences("termux_prefs", MODE_PRIVATE)
                .getString(PREF_MESSAGE_HISTORY, null);
        if (json == null) return;
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                String s = arr.optString(i, null);
                if (!TextUtils.isEmpty(s) && !mMessageHistory.contains(s)) {
                    mMessageHistory.add(s);
                }
            }
        } catch (JSONException e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to parse message history", e);
        }
        // Trim to the configured max (e.g. the user lowered the limit in Settings).
        boolean trimmed = false;
        while (mMessageHistory.size() > mMessageHistoryMax) {
            mMessageHistory.remove(mMessageHistory.size() - 1);
            trimmed = true;
        }
        if (trimmed) saveMessageHistory();
    }

    /** Persist the current message history to preferences as a JSON array. */
    private void saveMessageHistory() {
        JSONArray arr = new JSONArray();
        for (String s : mMessageHistory) arr.put(s);
        getSharedPreferences("termux_prefs", MODE_PRIVATE).edit()
                .putString(PREF_MESSAGE_HISTORY, arr.toString()).apply();
    }

    private boolean isHistoryPopupShowing() {
        return mHistoryPopup != null && mHistoryPopup.isShowing();
    }

    /**
     * Build and show the message-history popup anchored above the pencil button.
     * Items are laid out newest-at-the-BOTTOM (nearest the button): the content is
     * filled top-to-bottom oldest-first, so index 0 (newest) ends up at the bottom.
     */
    private void showMessageHistoryPopup(@NonNull View anchor) {
        dismissMessageHistoryPopup();
        mHistoryItemViews.clear();
        mHistoryHighlightIndex = -1;

        // Nothing worth showing: no history and nothing typed in the input panel.
        if (!shouldShowHistoryPopup()) return;

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int bg = ContextCompat.getColor(this, com.termux.shared.R.color.terminal_toolbar_text_input_bg);
        content.setBackgroundColor(bg);

        int padH = dpToPx(14);
        int padV = dpToPx(10);
        int textColor = ContextCompat.getColor(this, com.termux.shared.R.color.terminal_toolbar_text_color);
        // Sharp (non-pulse) highlight: a faint 10%-alpha wash, black in light
        // theme and white in dark theme, detected via uiMode so it works even
        // for near-white popup backgrounds like #f0f0f0.
        mHistoryTextColor = textColor;
        boolean isNight = (getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        mHistoryHighlightFill = isNight
                ? Color.argb(26, 255, 255, 255)   // dark theme  -> white @10%
                : Color.argb(26, 0,   0,   0);     // light theme -> black @10%

        // Synthetic "CLEAR HISTORY…" row pinned at the TOP of the popup.
        // Selecting it opens a confirmation dialog; confirming wipes all history.
        // Shown only when there is history to clear. Coexists with the bottom
        // "Clear" row (clears the input), it is not a replacement for it.
        if (!mMessageHistory.isEmpty()) {
            TextView tv = new TextView(this);
            tv.setText("CLEAR HISTORY…");
            tv.setGravity(Gravity.CENTER);
            tv.setAllCaps(true);
            tv.setTextColor(textColor);
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            tv.setTypeface(tv.getTypeface(), android.graphics.Typeface.BOLD);
            tv.setPadding(padH, padV, padH, padV);
            tv.setClickable(true);
            tv.setTag(MESSAGE_HISTORY_CLEAR_ALL_TAG);
            content.addView(tv, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            mHistoryItemViews.add(tv);

            // Thin separator below the "clear all" row to visually group it.
            View sep = new View(this);
            sep.setBackgroundColor(Color.argb(60, 128, 128, 128));
            content.addView(sep, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dpToPx(1)));
        }
        // Displayed order (ТЗ): newest at the BOTTOM (nearest the pencil button,
        // first reached by a swipe-up), oldest at the top. A re-sent message moves
        // to index 0 (front) of mMessageHistory, so iterate in REVERSE (end -> 0)
        // to fill the vertical layout top-to-bottom with the newest last (bottom).
        for (int i = mMessageHistory.size() - 1; i >= 0; i--) {
            final String message = mMessageHistory.get(i);
            TextView tv = new TextView(this);
            // Preview: collapse newlines to spaces, wrap to at most 2 lines and add
            // an ellipsis when the message is longer than that.
            tv.setText(message.replace("\n", " ").trim());
            tv.setMaxLines(2);
            tv.setEllipsize(TextUtils.TruncateAt.END);
            tv.setTextColor(textColor);
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
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
        final EditText inputField = findViewById(R.id.terminal_toolbar_text_input);
        final String inputText = inputField != null ? inputField.getText().toString() : "";
        if (!TextUtils.isEmpty(inputText)) {
            TextView tv = new TextView(this);
            tv.setText("Clear");
            tv.setGravity(Gravity.CENTER);
            tv.setAllCaps(true);
            tv.setTextColor(textColor);
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            tv.setTypeface(tv.getTypeface(), android.graphics.Typeface.BOLD);
            tv.setPadding(padH, padV, padH, padV);
            tv.setClickable(true);
            tv.setTag(MESSAGE_HISTORY_CLEAR_TAG);
            content.addView(tv, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            mHistoryItemViews.add(tv);

            // Thin separator above the bottom "Clear" row acts as a visual
            // divider between the history list and the action.  Only meaningful
            // when there IS a history list to separate it from.
            if (!mMessageHistory.isEmpty()) {
                View sepBottom = new View(this);
                sepBottom.setBackgroundColor(Color.argb(60, 128, 128, 128));
                content.addView(sepBottom, content.getChildCount() - 1,
                        new LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT, dpToPx(1)));
            }
        }

        int popupWidth = Math.min(
                getResources().getDisplayMetrics().widthPixels - dpToPx(24),
                dpToPx(320));

        // Wrap in a ScrollView: the popup is a bounded box (never edge-to-edge),
        // and a taller history scrolls inside it. Kept for edge auto-scroll while
        // the finger drags near the top/bottom of the box.
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(content, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        mHistoryScroll = scroll;

        mHistoryPopup = new PopupWindow(scroll, popupWidth,
                ViewGroup.LayoutParams.WRAP_CONTENT, false);
        mHistoryPopup.setElevation(dpToPx(8));
        mHistoryPopup.setBackgroundDrawable(
                ContextCompat.getDrawable(this, R.drawable.text_input_background));
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
        int popupGap = dpToPx(MESSAGE_HISTORY_POPUP_GAP_DP);
        int roomAbove = Math.max(dpToPx(48), anchorLoc[1] - dpToPx(8) - popupGap);
        int maxHeight = Math.min(dpToPx(MESSAGE_HISTORY_POPUP_MAX_HEIGHT_DP), roomAbove);
        int popupHeight = Math.min(contentHeight, maxHeight);
        mHistoryPopup.update(anchor,
                0,
                -(anchor.getHeight() + popupHeight + popupGap),
                popupWidth,
                popupHeight);

        // Open at the END of the list: newest is at the bottom, so start scrolled
        // fully down so the newest messages (nearest the button) are visible.
        final android.widget.ScrollView scrollRef = scroll;
        scrollRef.post(() -> scrollRef.fullScroll(View.FOCUS_DOWN));
    }

    /**
     * Whether the popup has anything worth showing: either there is saved
     * history, or the input panel currently holds some (unsent) text.
     */
    private boolean shouldShowHistoryPopup() {
        if (!mMessageHistory.isEmpty()) return true;
        final EditText inputField = findViewById(R.id.terminal_toolbar_text_input);
        return inputField != null && !TextUtils.isEmpty(inputField.getText().toString());
    }

    /** Highlight the history item currently under the finger (raw screen coords). */
    private void updateHistoryHighlight(float rawX, float rawY) {
        // Remember the finger position so the continuous edge auto-scroll keeps
        // running even when the finger rests (no ACTION_MOVE) on the edge band.
        mHistoryFingerY = rawY;
        autoScrollHistoryNearEdge();

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
                tv.setBackgroundColor(mHistoryHighlightFill);
            } else {
                tv.setBackgroundColor(Color.TRANSPARENT);
            }
            tv.setTextColor(mHistoryTextColor);   // default colour in both states
        }
    }

    /**
     * Continuously scroll the popup's ScrollView while the finger rests/drags
     * within an edge band at the top or bottom (like the keyboard-accent popup:
     * it keeps moving even without finger motion). Driven by a self-rescheduling
     * postDelayed loop that stops once the finger leaves the band or the popup
     * closes.
     */
    private void autoScrollHistoryNearEdge() {
        if (mHistoryScroll == null) return;
        int[] loc = new int[2];
        mHistoryScroll.getLocationOnScreen(loc);
        int top = loc[1];
        int bottom = loc[1] + mHistoryScroll.getHeight();
        int band = dpToPx(36);      // edge-sensitive zone
        int maxStep = dpToPx(18);   // max px scrolled per tick

        float rawY = mHistoryFingerY;
        int step;
        if (rawY < top + band) {
            float t = Math.min(1f, (top + band - rawY) / band);
            step = -Math.round(maxStep * t);
        } else if (rawY > bottom - band) {
            float t = Math.min(1f, (rawY - (bottom - band)) / band);
            step = Math.round(maxStep * t);
        } else {
            mHistoryAutoScrolling = false;   // left the band; stop the loop
            return;
        }

        mHistoryScroll.scrollBy(0, step);

        // Reschedule while still open and still in the band.
        if (!mHistoryAutoScrolling) {
            mHistoryAutoScrolling = true;
            mHistoryScroll.postDelayed(this::autoScrollHistoryNearEdge, 16);
        }
    }

    /**
     * Ask the user to confirm wiping the entire message history. The popup is
     * already dismissed by the time we get here (finger released over the top
     * "Clear message history…" row).
     */
    private void confirmClearAllHistory() {
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Clear message history?")
                .setMessage("This will permanently remove all remembered messages.")
                .setPositiveButton(android.R.string.ok, (d, w) -> clearAllHistory())
                .setNegativeButton(android.R.string.cancel, null)
                .show();
        // Dark theme dialog text can blend with the background on MIUI/HyperOS
        // (the system theme overlay overrides MaterialComponents); fix it so the
        // text/buttons stay readable in night mode.
        applyNightDialogColors(dialog);
    }

    /** Wipe the whole message history (in memory + persisted). */
    private void clearAllHistory() {
        mMessageHistory.clear();
        getSharedPreferences("termux_prefs", MODE_PRIVATE).edit()
                .remove(PREF_MESSAGE_HISTORY).apply();
    }

    /** Dismiss the history popup and reset highlight state. */
    private void dismissMessageHistoryPopup() {
        if (mHistoryPopup != null) {
            try { mHistoryPopup.dismiss(); } catch (Exception ignored) {}
            mHistoryPopup = null;
        }
        mHistoryItemViews.clear();
        mHistoryScroll = null;
        mHistoryAutoScrolling = false;   // stop any pending edge-scroll loop
        mHistoryHighlightIndex = -1;
    }

    /** "Clear message history..." item: ask for confirmation, then wipe all history. */
    private void confirmClearHistory() {
        final AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setIcon(android.R.drawable.ic_dialog_alert);
        b.setTitle("Clear message history");
        b.setMessage("Clear the entire message history? This cannot be undone.");
        b.setPositiveButton(android.R.string.yes, (dialog, id) -> {
            dialog.dismiss();
            mMessageHistory.clear();
            saveMessageHistory();
            showToast("Message history cleared", true);
        });
        b.setNegativeButton(android.R.string.no, null);
        b.show();
    }

    /** Bottom "Clear" item: remember the current input text in history, then empty the field. */
    private void clearInputToHistory() {
        final EditText editText = findViewById(R.id.terminal_toolbar_text_input);
        if (editText == null) return;

        // Open the panel FIRST if closed, so a previously typed (unsent) text is
        // restored into the field and thus not lost before we snapshot it.
        if (!isTextInputVisible()) {
            setTextInputVisible(true);
            updateToggleTextInputButtonIcon();
        }

        String existing = editText.getText().toString();
        if (!TextUtils.isEmpty(existing)) {
            addToMessageHistory(existing);
        }

        editText.setText("");
        setFocusOnInputForCurrentSession(true);
        saveTextInputForCurrentSession();
        editText.requestFocus();
    }

    /**
     * A history item was chosen (finger released over it). Opens the input panel
     * and inserts the picked message. If the panel already held some text, that
     * text is first pushed into the history (dedup) so it is not lost.
     */
    private void onHistoryMessagePicked(@NonNull String message) {
        final EditText editText = findViewById(R.id.terminal_toolbar_text_input);
        if (editText == null) return;

        // Open the panel FIRST if it is closed: setTextInputVisible(true) restores
        // this session's previously typed (but unsent) text into the field. We read
        // `existing` only after that, so text saved for a closed panel is not lost.
        if (!isTextInputVisible()) {
            setTextInputVisible(true);
            updateToggleTextInputButtonIcon();
        }

        // If the panel already holds unsent text, push it into the history (dedup)
        // so it is preserved before we overwrite the field with the picked message.
        String existing = editText.getText().toString();
        if (!TextUtils.isEmpty(existing) && !existing.equals(message)) {
            addToMessageHistory(existing);
        }

        editText.setText(message);
        editText.setSelection(message.length());
        setFocusOnInputForCurrentSession(true);
        // Persist into the per-session store so the inserted text survives switches.
        saveTextInputForCurrentSession();

        editText.requestFocus();
        editText.post(() -> {
            android.view.inputmethod.InputMethodManager imm =
                    (android.view.inputmethod.InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.showSoftInput(editText, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
            }
        });
    }

    // ============================================================================
    //  Recent directories history (for the "new tab" button popup)
    // ============================================================================

    /**
     * Record the current session's working directory into the directory history
     * (dedup, newest first), trimming to the configured max and persisting.
     * Returns the recorded path (or null if unavailable). Called whenever a
     * session becomes current, and right before the directory popup is shown,
     * so the latest "cd" is captured even if it hasn't been committed yet.
     */
    @Nullable
    public String recordCurrentDirectory() {
        TerminalSession session = getCurrentSession();
        if (session == null) return null;
        String cwd = session.getCwd();
        if (TextUtils.isEmpty(cwd)) return null;
        addToDirectoryHistory(cwd);
        return cwd;
    }

    /**
     * Add a visited directory to the history. Deduplicated by path: if the path
     * already exists it is removed and re-inserted at the front (newest first).
     */
    private void addToDirectoryHistory(@NonNull String directory) {
        if (TextUtils.isEmpty(directory)) return;
        mDirectoryHistory.remove(directory);   // dedup by path
        mDirectoryHistory.add(0, directory);  // newest first
        while (mDirectoryHistory.size() > mDirectoryHistoryMax) {
            mDirectoryHistory.remove(mDirectoryHistory.size() - 1);
        }
        saveDirectoryHistory();
    }

    /** Load the persisted directory history from preferences (JSON array). */
    private void loadDirectoryHistory() {
        mDirectoryHistory.clear();
        String json = getSharedPreferences("termux_prefs", MODE_PRIVATE)
                .getString(PREF_DIRECTORY_HISTORY, null);
        if (json == null) return;
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                String s = arr.optString(i, null);
                if (!TextUtils.isEmpty(s) && !mDirectoryHistory.contains(s)) {
                    mDirectoryHistory.add(s);
                }
            }
        } catch (JSONException e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to parse directory history", e);
        }
        boolean trimmed = false;
        while (mDirectoryHistory.size() > mDirectoryHistoryMax) {
            mDirectoryHistory.remove(mDirectoryHistory.size() - 1);
            trimmed = true;
        }
        if (trimmed) saveDirectoryHistory();
    }

    /** Persist the current directory history to preferences as a JSON array. */
    private void saveDirectoryHistory() {
        JSONArray arr = new JSONArray();
        for (String s : mDirectoryHistory) arr.put(s);
        getSharedPreferences("termux_prefs", MODE_PRIVATE).edit()
                .putString(PREF_DIRECTORY_HISTORY, arr.toString()).apply();
    }

    // ============================================================================
    //  Session (open tabs) restore across app restarts
    // ============================================================================

    /** Whether restoring open tabs on launch is enabled (default: on). */
    private boolean isRestoreSessionsEnabled() {
        return getSharedPreferences("termux_prefs", MODE_PRIVATE)
                .getBoolean(PREF_RESTORE_SESSIONS, true);
    }

    /**
     * Snapshot the currently open tabs (working directory, name, failsafe flag)
     * plus the index of the active tab, and persist it as JSON. Called on onStop
     * so a later cold start (service killed) can reopen the same tabs. When the
     * feature is off, the stored snapshot is cleared instead.
     */
    private void saveSessionSnapshot() {
        final android.content.SharedPreferences prefs = getSharedPreferences("termux_prefs", MODE_PRIVATE);
        if (!isRestoreSessionsEnabled()) {
            prefs.edit().remove(PREF_SESSION_SNAPSHOT).apply();
            return;
        }
        TermuxService service = mTermuxService;
        if (service == null) return;
        // While the service is shutting down (e.g. the notification Exit action
        // kills sessions one-by-one, each firing termuxSessionListNotifyUpdated
        // which calls this method) skip saving so the last full snapshot (taken
        // while all tabs were alive) is preserved instead of being shrunk to the
        // last surviving tab.
        if (service.isWantsToStop()) return;
        List<TermuxSession> sessions = service.getTermuxSessions();
        // An empty list means the sessions were already killed (e.g. the Exit
        // notification action fires before onStop). Do NOT wipe the snapshot in
        // that case: keep the last non-empty snapshot so it can be restored.
        if (sessions == null || sessions.isEmpty()) return;

        TerminalSession current = getCurrentSession();
        JSONArray tabs = new JSONArray();
        int activeIndex = 0;
        for (int i = 0; i < sessions.size(); i++) {
            TermuxSession ts = sessions.get(i);
            TerminalSession terminal = ts.getTerminalSession();
            ExecutionCommand cmd = ts.getExecutionCommand();
            try {
                JSONObject tab = new JSONObject();
                String cwd = terminal != null ? terminal.getCwd() : null;
                if (TextUtils.isEmpty(cwd) && cmd != null) cwd = cmd.workingDirectory;
                tab.put("cwd", cwd == null ? "" : cwd);
                tab.put("name", cmd != null && cmd.shellName != null ? cmd.shellName : "");
                tab.put("failsafe", cmd != null && cmd.isFailsafe);
                tabs.put(tab);
            } catch (JSONException e) {
                Logger.logStackTraceWithMessage(LOG_TAG, "Failed to snapshot session", e);
            }
            if (current != null && terminal == current) activeIndex = i;
        }

        try {
            JSONObject snapshot = new JSONObject();
            snapshot.put("tabs", tabs);
            snapshot.put("active", activeIndex);
            prefs.edit().putString(PREF_SESSION_SNAPSHOT, snapshot.toString()).apply();
        } catch (JSONException e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to persist session snapshot", e);
        }
    }

    /**
     * Reopen the tabs saved by {@link #saveSessionSnapshot()} in the same order,
     * directories and names, then select the previously-active tab. Returns true
     * if at least one tab was restored. Called from onServiceConnected only when
     * the service has no live sessions (i.e. a genuine cold start).
     */
    private boolean restoreSessionSnapshot() {
        if (!isRestoreSessionsEnabled()) return false;
        String json = getSharedPreferences("termux_prefs", MODE_PRIVATE)
                .getString(PREF_SESSION_SNAPSHOT, null);
        if (TextUtils.isEmpty(json)) return false;

        TermuxService service = mTermuxService;
        if (service == null) return false;

        int active = 0;
        int restored = 0;
        try {
            JSONObject snapshot = new JSONObject(json);
            active = snapshot.optInt("active", 0);
            JSONArray tabs = snapshot.optJSONArray("tabs");
            if (tabs == null || tabs.length() == 0) return false;
            for (int i = 0; i < tabs.length(); i++) {
                JSONObject tab = tabs.optJSONObject(i);
                if (tab == null) continue;
                String cwd = tab.optString("cwd", "");
                String name = tab.optString("name", "");
                boolean failsafe = tab.optBoolean("failsafe", false);
                String workingDirectory = TextUtils.isEmpty(cwd)
                        ? mProperties.getDefaultWorkingDirectory() : cwd;
                TermuxSession session = service.createTermuxSession(null, null, null,
                        workingDirectory, failsafe, TextUtils.isEmpty(name) ? null : name);
                if (session != null) restored++;
            }
        } catch (JSONException e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to restore session snapshot", e);
            return false;
        }

        if (restored == 0) return false;

        List<TermuxSession> sessions = service.getTermuxSessions();
        if (sessions != null && !sessions.isEmpty()) {
            int idx = Math.max(0, Math.min(active, sessions.size() - 1));
            mTermuxTerminalSessionActivityClient.setCurrentSession(
                    sessions.get(idx).getTerminalSession());
        }
        return true;
    }

    /**
     * Whether the directory popup has anything worth showing: the recording of
     * the current directory succeeded (so there is at least one entry) or an
     * older history already exists.
     */
    private boolean shouldShowDirectoryHistoryPopup() {
        if (!mDirectoryHistory.isEmpty()) return true;
        // Try to capture the current directory on demand (e.g. first time).
        return recordCurrentDirectory() != null;
    }

    /**
     * Build and show the directory-history popup anchored above the new-tab
     * button. Mirrors {@link #showMessageHistoryPopup(View)} layout/gesture:
     * newest-at-bottom, swipe-up from the button reaches the newest first, a
     * top "CLEAR HISTORY…" row wipes the whole list. Picking a directory opens
     * a fresh session starting in that directory.
     */
    private void showDirectoryHistoryPopup(@NonNull View anchor) {
        dismissMessageHistoryPopup();
        mHistoryItemViews.clear();
        mHistoryHighlightIndex = -1;

        // Capture the current directory first so it appears in the list.
        recordCurrentDirectory();
        if (mDirectoryHistory.isEmpty()) return;

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int bg = ContextCompat.getColor(this, com.termux.shared.R.color.terminal_toolbar_text_input_bg);
        content.setBackgroundColor(bg);

        int padH = dpToPx(14);
        int padV = dpToPx(10);
        int textColor = ContextCompat.getColor(this, com.termux.shared.R.color.terminal_toolbar_text_color);
        mHistoryTextColor = textColor;
        boolean isNight = (getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        mHistoryHighlightFill = isNight
                ? Color.argb(26, 255, 255, 255)
                : Color.argb(26, 0, 0, 0);

        // Synthetic "CLEAR HISTORY…" row pinned at the TOP of the popup.
        if (!mDirectoryHistory.isEmpty()) {
            TextView tv = new TextView(this);
            tv.setText("CLEAR HISTORY…");
            tv.setGravity(Gravity.CENTER);
            tv.setAllCaps(true);
            tv.setTextColor(textColor);
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            tv.setTypeface(tv.getTypeface(), android.graphics.Typeface.BOLD);
            tv.setPadding(padH, padV, padH, padV);
            tv.setClickable(true);
            tv.setTag(DIRECTORY_HISTORY_CLEAR_ALL_TAG);
            content.addView(tv, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            mHistoryItemViews.add(tv);

            View sep = new View(this);
            sep.setBackgroundColor(Color.argb(60, 128, 128, 128));
            content.addView(sep, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dpToPx(1)));
        }

        // Newest at the BOTTOM: iterate REVERSE so index 0 (newest) ends last.
        for (int i = mDirectoryHistory.size() - 1; i >= 0; i--) {
            final String directory = mDirectoryHistory.get(i);
            TextView tv = new TextView(this);
            tv.setText(directory);
            tv.setMaxLines(2);
            tv.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            tv.setTextColor(textColor);
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            tv.setPadding(padH, padV, padH, padV);
            tv.setClickable(true);
            tv.setTag(i);
            content.addView(tv, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            mHistoryItemViews.add(tv);
        }

        int popupWidth = Math.min(
                getResources().getDisplayMetrics().widthPixels - dpToPx(24),
                dpToPx(320));

        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(content, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        mHistoryScroll = scroll;

        mHistoryPopup = new PopupWindow(scroll, popupWidth,
                ViewGroup.LayoutParams.WRAP_CONTENT, false);
        mHistoryPopup.setElevation(dpToPx(8));
        mHistoryPopup.setBackgroundDrawable(
                ContextCompat.getDrawable(this, R.drawable.text_input_background));
        mHistoryPopup.setClippingEnabled(true);
        mHistoryPopup.setTouchable(false);
        mHistoryPopup.setFocusable(false);

        mHistoryPopup.showAsDropDown(anchor, 0, 0, Gravity.START);
        content.measure(
                View.MeasureSpec.makeMeasureSpec(popupWidth, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int contentHeight = content.getMeasuredHeight();
        int[] anchorLoc = new int[2];
        anchor.getLocationOnScreen(anchorLoc);
        int popupGap = dpToPx(MESSAGE_HISTORY_POPUP_GAP_DP);
        int roomAbove = Math.max(dpToPx(48), anchorLoc[1] - dpToPx(8) - popupGap);
        int maxHeight = Math.min(dpToPx(MESSAGE_HISTORY_POPUP_MAX_HEIGHT_DP), roomAbove);
        int popupHeight = Math.min(contentHeight, maxHeight);
        mHistoryPopup.update(anchor,
                0,
                -(anchor.getHeight() + popupHeight + popupGap),
                popupWidth,
                popupHeight);

        final android.widget.ScrollView scrollRef = scroll;
        scrollRef.post(() -> scrollRef.fullScroll(View.FOCUS_DOWN));
    }

    /** A directory from the popup was chosen: open a new session there. */
    private void onHistoryDirectoryPicked(@NonNull String directory) {
        mTermuxTerminalSessionActivityClient.addNewSessionInDirectory(directory);
    }

    /** "CLEAR HISTORY…" item: confirm, then wipe the whole directory history. */
    private void confirmClearDirectoryHistory() {
        final AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setTitle("Clear directory history");
        b.setMessage("Clear the entire directory history? This cannot be undone.");
        b.setPositiveButton(android.R.string.yes, (dialog, id) -> {
            dialog.dismiss();
            mDirectoryHistory.clear();
            saveDirectoryHistory();
            showToast("Directory history cleared", true);
        });
        b.setNegativeButton(android.R.string.no, null);
        applyNightDialogColors(b.show());
    }

    /**
     * On MIUI/HyperOS the system day-night overlay can leave dialog title,
     * message and buttons a near-black colour that blends into the dark
     * background. In night mode only, force them white so they stay readable;
     * in light mode the default (dark) colour is left untouched.
     */
    private void applyNightDialogColors(@NonNull AlertDialog dialog) {
        boolean isNight = (getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        if (!isNight) return;
        TextView msg = dialog.findViewById(android.R.id.message);
        if (msg != null) msg.setTextColor(Color.WHITE);
        TextView title = dialog.findViewById(android.R.id.title);
        if (title != null) title.setTextColor(Color.WHITE);
        Button pos = dialog.getButton(DialogInterface.BUTTON_POSITIVE);
        if (pos != null) pos.setTextColor(Color.WHITE);
        Button neg = dialog.getButton(DialogInterface.BUTTON_NEGATIVE);
        if (neg != null) neg.setTextColor(Color.WHITE);
    }

    private int dpToPx(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }

    /**
     * Check if text input field is enabled in settings.
     * @return true if text input field should be shown, false otherwise
     */
    public boolean isTextInputEnabled() {
        return getSharedPreferences("termux_prefs", MODE_PRIVATE).getBoolean("text_input_enabled", true);
    }

    /**
     * Update the toggle text input button visibility based on settings.
     * Also updates the text input container visibility.
     */
    public void updateToggleTextInputButtonVisibility() {
        ImageButton toggleTextInputButton = findViewById(R.id.toggle_text_input_button);
        View textInputContainer = findViewById(R.id.terminal_toolbar_text_input_container);
        
        boolean enabled = isTextInputEnabled();
        boolean wasDisabled = toggleTextInputButton != null &&
                              toggleTextInputButton.getVisibility() != View.VISIBLE;
        
        if (toggleTextInputButton != null) {
            toggleTextInputButton.setVisibility(enabled ? View.VISIBLE : View.GONE);
        }
        
        // Also update text input container visibility
        if (textInputContainer != null) {
            if (!enabled) {
                // Hide text input when disabled in settings
                textInputContainer.setVisibility(View.GONE);
            } else {
                // If setting was just enabled (transition from disabled to enabled),
                // show panel in visible state
                if (wasDisabled) {
                    setTextInputVisible(true);
                } else {
                    // Restore text input visibility based on saved state.
                    // Use setTextInputVisible so the extra keys slot stays inverted.
                    setTextInputVisible(isTextInputVisible());
                }
            }
        }
        
        // Update button icon after visibility state is finalized
        if (enabled && toggleTextInputButton != null) {
            updateToggleTextInputButtonIcon();
        }
    }

    private void setToggleKeyboardView() {
        // Toggle keyboard button removed - functionality moved to extra keys
    }





    @SuppressLint("RtlHardcoded")
    @Override
    public void onBackPressed() {
        finishActivityIfNotFinishing();
    }

    public void finishActivityIfNotFinishing() {
        // prevent duplicate calls to finish() if called from multiple places
        if (!TermuxActivity.this.isFinishing()) {
            finish();
        }
    }

    /** Show a toast and dismiss the last one if still visible. */
    public void showToast(String text, boolean longDuration) {
        if (text == null || text.isEmpty()) return;
        if (mLastToast != null) mLastToast.cancel();
        mLastToast = Toast.makeText(TermuxActivity.this, text, longDuration ? Toast.LENGTH_LONG : Toast.LENGTH_SHORT);
        mLastToast.setGravity(Gravity.TOP, 0, 0);
        mLastToast.show();
    }



    @Override
    public void onCreateContextMenu(ContextMenu menu, View v, ContextMenuInfo menuInfo) {
        TerminalSession currentSession = getCurrentSession();
        if (currentSession == null) return;

        boolean autoFillEnabled = mTerminalView.isAutoFillEnabled();

        menu.add(Menu.NONE, CONTEXT_MENU_SELECT_URL_ID, Menu.NONE, R.string.action_select_url);
        menu.add(Menu.NONE, CONTEXT_MENU_SHARE_TRANSCRIPT_ID, Menu.NONE, R.string.action_share_transcript);
        if (!DataUtils.isNullOrEmpty(mTerminalView.getStoredSelectedText()))
            menu.add(Menu.NONE, CONTEXT_MENU_SHARE_SELECTED_TEXT, Menu.NONE, R.string.action_share_selected_text);
        if (autoFillEnabled)
            menu.add(Menu.NONE, CONTEXT_MENU_AUTOFILL_USERNAME, Menu.NONE, R.string.action_autofill_username);
        if (autoFillEnabled)
            menu.add(Menu.NONE, CONTEXT_MENU_AUTOFILL_PASSWORD, Menu.NONE, R.string.action_autofill_password);
        menu.add(Menu.NONE, CONTEXT_MENU_RESET_TERMINAL_ID, Menu.NONE, R.string.action_reset_terminal);
        menu.add(Menu.NONE, CONTEXT_MENU_KILL_PROCESS_ID, Menu.NONE, getResources().getString(R.string.action_kill_process, getCurrentSession().getPid())).setEnabled(currentSession.isRunning());
        menu.add(Menu.NONE, CONTEXT_MENU_STYLING_ID, Menu.NONE, R.string.action_style_terminal);
        menu.add(Menu.NONE, CONTEXT_MENU_TOGGLE_KEEP_SCREEN_ON, Menu.NONE, R.string.action_toggle_keep_screen_on).setCheckable(true).setChecked(mPreferences.shouldKeepScreenOn());
        menu.add(Menu.NONE, CONTEXT_MENU_HELP_ID, Menu.NONE, R.string.action_open_help);
        menu.add(Menu.NONE, CONTEXT_MENU_SETTINGS_ID, Menu.NONE, R.string.action_open_settings);
        menu.add(Menu.NONE, CONTEXT_MENU_REPORT_ID, Menu.NONE, R.string.action_report_issue);
    }

    /** Hook system menu to show context menu instead. */
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        mTerminalView.showContextMenu();
        return false;
    }

    @Override
    public boolean onContextItemSelected(MenuItem item) {
        TerminalSession session = getCurrentSession();

        switch (item.getItemId()) {
            case CONTEXT_MENU_SELECT_URL_ID:
                mTermuxTerminalViewClient.showUrlSelection();
                return true;
            case CONTEXT_MENU_SHARE_TRANSCRIPT_ID:
                mTermuxTerminalViewClient.shareSessionTranscript();
                return true;
            case CONTEXT_MENU_SHARE_SELECTED_TEXT:
                mTermuxTerminalViewClient.shareSelectedText();
                return true;
            case CONTEXT_MENU_AUTOFILL_USERNAME:
                mTerminalView.requestAutoFillUsername();
                return true;
            case CONTEXT_MENU_AUTOFILL_PASSWORD:
                mTerminalView.requestAutoFillPassword();
                return true;
            case CONTEXT_MENU_RESET_TERMINAL_ID:
                onResetTerminalSession(session);
                return true;
            case CONTEXT_MENU_KILL_PROCESS_ID:
                showKillSessionDialog(session);
                return true;
            case CONTEXT_MENU_STYLING_ID:
                showStylingDialog();
                return true;
            case CONTEXT_MENU_TOGGLE_KEEP_SCREEN_ON:
                toggleKeepScreenOn();
                return true;
            case CONTEXT_MENU_HELP_ID:
                ActivityUtils.startActivity(this, new Intent(this, HelpActivity.class));
                return true;
            case CONTEXT_MENU_SETTINGS_ID:
                ActivityUtils.startActivity(this, new Intent(this, SettingsActivity.class));
                return true;
            case CONTEXT_MENU_REPORT_ID:
                mTermuxTerminalViewClient.reportIssueFromTranscript();
                return true;
            default:
                return super.onContextItemSelected(item);
        }
    }

    @Override
    public void onContextMenuClosed(Menu menu) {
        super.onContextMenuClosed(menu);
        // onContextMenuClosed() is triggered twice if back button is pressed to dismiss instead of tap for some reason
        mTerminalView.onContextMenuClosed(menu);
    }

    private void showKillSessionDialog(TerminalSession session) {
        if (session == null) return;

        final AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setIcon(android.R.drawable.ic_dialog_alert);
        b.setMessage(R.string.title_confirm_kill_process);
        b.setPositiveButton(android.R.string.yes, (dialog, id) -> {
            dialog.dismiss();
            session.finishIfRunning();
        });
        b.setNegativeButton(android.R.string.no, null);
        b.show();
    }

    private void onResetTerminalSession(TerminalSession session) {
        if (session != null) {
            session.reset();
            showToast(getResources().getString(R.string.msg_terminal_reset), true);

            if (mTermuxTerminalSessionActivityClient != null)
                mTermuxTerminalSessionActivityClient.onResetTerminalSession();
        }
    }

    private void showStylingDialog() {
        // Show our own color-scheme picker (the same dialog used in Settings) for the currently
        // active theme, applying the choice live to that theme. This assigns the scheme to the
        // active light/dark theme instead of the shared colors.properties, so per-theme selection
        // stays consistent whether chosen from here or from Settings.
        final NightMode appNightMode = NightMode.getAppNightMode();
        final boolean isNight = (appNightMode == NightMode.SYSTEM)
            ? ThemeUtils.isSystemNightModeEnabled()
            : (appNightMode == NightMode.TRUE);
        ColorSchemeUtils.showColorSchemeDialog(this, isNight, getString(R.string.color_scheme_dialog_title),
            getString(R.string.error_styling_not_installed),
            () -> updateTermuxActivityStyling(this, false));
    }
    private void toggleKeepScreenOn() {
        if (mTerminalView.getKeepScreenOn()) {
            mTerminalView.setKeepScreenOn(false);
            mPreferences.setKeepScreenOn(false);
        } else {
            mTerminalView.setKeepScreenOn(true);
            mPreferences.setKeepScreenOn(true);
        }
    }



    /**
     * For processes to access primary external storage (/sdcard, /storage/emulated/0, ~/storage/shared),
     * termux needs to be granted legacy WRITE_EXTERNAL_STORAGE or MANAGE_EXTERNAL_STORAGE permissions
     * if targeting targetSdkVersion 30 (android 11) and running on sdk 30 (android 11) and higher.
     */
    public void requestStoragePermission(boolean isPermissionCallback) {
        // Must run on the UI thread: PermissionUtils.requestPermissions() ends up calling
        // Activity.requestPermissions(), which is a no-op / throws off the UI thread on Android 11+,
        // so the permission dialog would never appear. All callers (onReceive, onActivityResult,
        // onRequestPermissionsResult) already run on the UI thread, so no threading is needed here.
        // Do not ask for permission again
        int requestCode = isPermissionCallback ? -1 : PermissionUtils.REQUEST_GRANT_STORAGE_PERMISSION;

        // If permission is granted, then also setup storage symlinks.
        if(PermissionUtils.checkAndRequestLegacyOrManageExternalStoragePermission(
            TermuxActivity.this, requestCode, !isPermissionCallback)) {
            if (isPermissionCallback)
                Logger.logInfoAndShowToast(TermuxActivity.this, LOG_TAG,
                    getString(com.termux.shared.R.string.msg_storage_permission_granted_on_request));

            // Create the storage symlinks off the UI thread (native symlink calls).
            new Thread(() -> TermuxInstaller.setupStorageSymlinks(TermuxActivity.this)).start();
        } else {
            if (isPermissionCallback)
                Logger.logInfoAndShowToast(TermuxActivity.this, LOG_TAG,
                    getString(com.termux.shared.R.string.msg_storage_permission_not_granted_on_request));
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        Logger.logVerbose(LOG_TAG, "onActivityResult: requestCode: " + requestCode + ", resultCode: "  + resultCode + ", data: "  + IntentUtils.getIntentString(data));
        if (requestCode == PermissionUtils.REQUEST_GRANT_STORAGE_PERMISSION) {
            requestStoragePermission(true);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        Logger.logVerbose(LOG_TAG, "onRequestPermissionsResult: requestCode: " + requestCode + ", permissions: "  + Arrays.toString(permissions) + ", grantResults: "  + Arrays.toString(grantResults));
        if (requestCode == PermissionUtils.REQUEST_GRANT_STORAGE_PERMISSION) {
            requestStoragePermission(true);
        }
    }



    public int getNavBarHeight() {
        return mNavBarHeight;
    }

    public TermuxActivityRootView getTermuxActivityRootView() {
        return mTermuxActivityRootView;
    }

    public View getTermuxActivityBottomSpaceView() {
        return mTermuxActivityBottomSpaceView;
    }

    public ExtraKeysView getExtraKeysView() {
        return mExtraKeysView;
    }

    public TermuxTerminalExtraKeys getTermuxTerminalExtraKeys() {
        return mTermuxTerminalExtraKeys;
    }

    public void setExtraKeysView(ExtraKeysView extraKeysView) {
        mExtraKeysView = extraKeysView;
    }


    public LinearLayout getTerminalToolbarContainer() {
        return (LinearLayout) findViewById(R.id.terminal_toolbar_container);
    }

    public float getTerminalToolbarDefaultHeight() {
        return mTerminalToolbarDefaultHeight;
    }


    public void termuxSessionListNotifyUpdated() {
        if (mTermuxSessionTabsController != null && mTermuxService != null) {
            mTermuxSessionTabsController.updateTabs(mTermuxService.getTermuxSessions());
        }
        // Keep the open-tabs snapshot fresh while sessions are alive, so a later
        // exit (e.g. the notification's Exit action, which kills sessions before
        // onStop runs) still leaves a snapshot to restore on next launch.
        saveSessionSnapshot();
    }

    public boolean isVisible() {
        return mIsVisible;
    }

    public boolean isOnResumeAfterOnCreate() {
        return mIsOnResumeAfterOnCreate;
    }

    public boolean isActivityRecreated() {
        return mIsActivityRecreated;
    }



    public TermuxService getTermuxService() {
        return mTermuxService;
    }

    public TerminalView getTerminalView() {
        return mTerminalView;
    }

    public TermuxTerminalViewClient getTermuxTerminalViewClient() {
        return mTermuxTerminalViewClient;
    }

    public TermuxTerminalSessionActivityClient getTermuxTerminalSessionClient() {
        return mTermuxTerminalSessionActivityClient;
    }

    public TermuxSessionTabsController getTermuxSessionTabsController() {
        return mTermuxSessionTabsController;
    }

    @Nullable
    public TerminalSession getCurrentSession() {
        if (mTerminalView != null)
            return mTerminalView.getCurrentSession();
        else
            return null;
    }

    public TermuxAppSharedPreferences getPreferences() {
        return mPreferences;
    }

    public TermuxAppSharedProperties getProperties() {
        return mProperties;
    }


    /**
     * Set visibility of the text input panel.
     * @param visible true to show, false to hide
     */
    public void setTextInputVisible(boolean visible) {
        View textInputContainer = findViewById(R.id.terminal_toolbar_text_input_container);
        if (textInputContainer != null) {
            // The text input panel and the extra keys share one slot below the tabs:
            // showing one hides the other.
            textInputContainer.setVisibility(visible ? View.VISIBLE : View.GONE);
            final ExtraKeysView extraKeysView = getExtraKeysView();
            if (extraKeysView != null) {
                extraKeysView.setVisibility(visible ? View.GONE : View.VISIBLE);
            }
            // Save state to preferences
            getSharedPreferences("termux_prefs", MODE_PRIVATE).edit().putBoolean(PREF_TEXT_INPUT_VISIBLE, visible).apply();

            // Track per-session panel visibility so each tab remembers its own state.
            final TerminalSession session = getCurrentSession();
            if (session != null) {
                mTextInputVisiblePerSession.put(session.mHandle, visible);
            }

            // Switch focus based on visibility
            if (visible) {
                // Restore this session's saved input text before showing the panel.
                restoreTextInputForSession(getCurrentSession());
                // Focus on text input and show keyboard
                EditText textInput = findViewById(R.id.terminal_toolbar_text_input);
                if (textInput != null) {
                    textInput.requestFocus();
                    textInput.post(() -> {
                        android.view.inputmethod.InputMethodManager imm = (android.view.inputmethod.InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                        if (imm != null) {
                            imm.showSoftInput(textInput, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
                        }
                    });
                }
            } else {
                // Save the current input text for this session before hiding the panel.
                saveTextInputForCurrentSession();
                // Focus on terminal view without reopening the keyboard
                if (mTermuxTerminalViewClient != null)
                    mTermuxTerminalViewClient.ignoreOnceSoftKeyboardOnFocus();
                if (mTerminalView != null) {
                    mTerminalView.requestFocus();
                }
            }
        }
    }

    /**
     * Get saved visibility state of text input panel for the current session.
     * Falls back to the legacy global preference for sessions not yet tracked.
     * @return true if should be visible, false otherwise
     */
    public boolean isTextInputVisible() {
        final TerminalSession session = getCurrentSession();
        if (session != null && mTextInputVisiblePerSession.containsKey(session.mHandle)) {
            return mTextInputVisiblePerSession.get(session.mHandle);
        }
        // New sessions (no recorded per-session state) default to hidden.
        return false;
    }

    /**
     * Apply the per-session text input panel visibility for the given session,
     * updating the container/extra-keys slot. When {@code applyFocus} is true,
     * also moves focus/keyboard to match the panel state (used on tab switch).
     * When false, only the slot visibility is set (used at startup so we do not
     * fight setSoftKeyboardState's startup keyboard-hidden preference).
     * Does not re-record per-session state.
     */
    public void applyTextInputVisibilityForSession(@Nullable TerminalSession session, boolean applyFocus) {
        View textInputContainer = findViewById(R.id.terminal_toolbar_text_input_container);
        if (textInputContainer == null) return;

        boolean enabled = isTextInputEnabled();
        boolean hasRecorded = session != null && mTextInputVisiblePerSession.containsKey(session.mHandle);
        boolean visible = enabled && (hasRecorded
                ? mTextInputVisiblePerSession.get(session.mHandle)
                : isTextInputVisible());

        textInputContainer.setVisibility(visible ? View.VISIBLE : View.GONE);
        final ExtraKeysView extraKeysView = getExtraKeysView();
        if (extraKeysView != null) {
            extraKeysView.setVisibility(visible ? View.GONE : View.VISIBLE);
        }

        if (applyFocus) {
            // On switch, restore where focus was last for this session:
            // on the panel (with keyboard) or on the terminal.
            if (visible && isFocusOnInputForSession(session)) {
                restoreTextInputForSession(session);
                EditText textInput = findViewById(R.id.terminal_toolbar_text_input);
                if (textInput != null) {
                    textInput.requestFocus();
                    textInput.post(() -> {
                        android.view.inputmethod.InputMethodManager imm = (android.view.inputmethod.InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                        if (imm != null) {
                            imm.showSoftInput(textInput, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
                        }
                    });
                }
            } else {
                // Panel hidden, or focus was on the terminal: focus the terminal.
                if (mTermuxTerminalViewClient != null)
                    mTermuxTerminalViewClient.ignoreOnceSoftKeyboardOnFocus();
                if (mTerminalView != null) {
                    mTerminalView.requestFocus();
                }
            }
        } else if (visible) {
            // At startup we still want the saved text restored into the field,
            // just without grabbing focus / popping the keyboard.
            restoreTextInputForSession(session);
        }
        updateToggleTextInputButtonIcon();
    }

    /** Apply per-session panel visibility with focus move (tab switch). */
    public void applyTextInputVisibilityForSession(@Nullable TerminalSession session) {
        applyTextInputVisibilityForSession(session, true);
    }




    public static void updateTermuxActivityStyling(Context context, boolean recreateActivity) {
        // Make sure that terminal styling is always applied.
        Intent stylingIntent = new Intent(TERMUX_ACTIVITY.ACTION_RELOAD_STYLE);
        stylingIntent.putExtra(TERMUX_ACTIVITY.EXTRA_RECREATE_ACTIVITY, recreateActivity);
        context.sendBroadcast(stylingIntent);
    }

    private static final String ACTION_TEXT_INPUT_VISIBILITY_CHANGED = "com.termux.TEXT_INPUT_VISIBILITY_CHANGED";
    private static final String ACTION_TEXT_INPUT_ENABLED_CHANGED = "com.termux.TEXT_INPUT_ENABLED_CHANGED";
    private static final String ACTION_SETTINGS_BUTTON_ENABLED_CHANGED = "com.termux.SETTINGS_BUTTON_ENABLED_CHANGED";

    private void registerTermuxActivityBroadcastReceiver() {
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(TERMUX_ACTIVITY.ACTION_NOTIFY_APP_CRASH);
        intentFilter.addAction(TERMUX_ACTIVITY.ACTION_RELOAD_STYLE);
        intentFilter.addAction(TERMUX_ACTIVITY.ACTION_REQUEST_PERMISSIONS);
        intentFilter.addAction(ACTION_TEXT_INPUT_VISIBILITY_CHANGED);
        intentFilter.addAction(ACTION_TEXT_INPUT_ENABLED_CHANGED);
        intentFilter.addAction(ACTION_SETTINGS_BUTTON_ENABLED_CHANGED);

        registerReceiver(mTermuxActivityBroadcastReceiver, intentFilter);
    }

    private void unregisterTermuxActivityBroadcastReceiver() {
        unregisterReceiver(mTermuxActivityBroadcastReceiver);
    }

    private void fixTermuxActivityBroadcastReceiverIntent(Intent intent) {
        if (intent == null) return;

        String extraReloadStyle = intent.getStringExtra(TERMUX_ACTIVITY.EXTRA_RELOAD_STYLE);
        if ("storage".equals(extraReloadStyle)) {
            intent.removeExtra(TERMUX_ACTIVITY.EXTRA_RELOAD_STYLE);
            intent.setAction(TERMUX_ACTIVITY.ACTION_REQUEST_PERMISSIONS);
        }
    }

    class TermuxActivityBroadcastReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null) return;

            String action = intent.getAction();

            // Handle text input visibility change even when activity is in background
            if (ACTION_TEXT_INPUT_VISIBILITY_CHANGED.equals(action)) {
                Logger.logDebug(LOG_TAG, "Received intent to change text input visibility");
                boolean visible = intent.getBooleanExtra("visible", true);
                setTextInputVisible(visible);
                return;
            }

            // Handle text input enabled/disabled setting change
            if (ACTION_TEXT_INPUT_ENABLED_CHANGED.equals(action)) {
                Logger.logDebug(LOG_TAG, "Received intent to change text input enabled state");
                updateToggleTextInputButtonVisibility();
                return;
            }

            if (ACTION_SETTINGS_BUTTON_ENABLED_CHANGED.equals(action)) {
                Logger.logDebug(LOG_TAG, "Received intent to change settings button enabled state");
                updateSettingsButtonVisibility();
                return;
            }

            // Reload styling must work even when the activity is in the background
            // (e.g. when theme is changed from Settings while TermuxActivity is behind it),
            // Rewrite the action first: termux-setup-storage sends `reload_style=storage` which
            // must become ACTION_REQUEST_PERMISSIONS before any other action handling, regardless
            // of activity visibility, so the storage permission dialog is never dropped.
            fixTermuxActivityBroadcastReceiverIntent(intent);
            action = intent.getAction();

            // Storage-permission request (termux-setup-storage). Handle even when the activity
            // is in the background or mid-recreate, otherwise the request is silently dropped and
            // termux-setup-storage fails (no permission dialog is ever shown).
            if (TERMUX_ACTIVITY.ACTION_REQUEST_PERMISSIONS.equals(action)) {
                Logger.logDebug(LOG_TAG, "Received intent to request storage permissions");
                requestStoragePermission(false);
                return;
            }

            // Reload styling must work even when the activity is in the background
            // (e.g. theme changed from Settings while TermuxActivity is behind it).
            if (TERMUX_ACTIVITY.ACTION_RELOAD_STYLE.equals(action)) {
                Logger.logWarn(LOG_TAG, "THEME-DEBUG: received ACTION_RELOAD_STYLE, recreateActivity=" + intent.getBooleanExtra(TERMUX_ACTIVITY.EXTRA_RECREATE_ACTIVITY, true));
                reloadActivityStyling(intent.getBooleanExtra(TERMUX_ACTIVITY.EXTRA_RECREATE_ACTIVITY, true));
                return;
            }

            if (mIsVisible) {
                switch (action) {
                    case TERMUX_ACTIVITY.ACTION_NOTIFY_APP_CRASH:
                        Logger.logDebug(LOG_TAG, "Received intent to notify app crash");
                        TermuxCrashUtils.notifyAppCrashFromCrashLogFile(context, LOG_TAG);
                        return;
                    default:
                }
            }
        }
    }

    private void reloadActivityStyling(boolean recreateActivity) {
        if (mProperties != null) {
            reloadProperties();

            if (mExtraKeysView != null) {
                mExtraKeysView.setButtonTextAllCaps(mProperties.shouldExtraKeysTextBeAllCaps());
                mExtraKeysView.reload(mTermuxTerminalExtraKeys.getExtraKeysInfo(), mTerminalToolbarDefaultHeight);
            }

            // Update NightMode.APP_NIGHT_MODE
            TermuxThemeUtils.setAppNightMode(mProperties.getNightMode());
        }

        setMargins();
        setTerminalToolbarHeight();

        FileReceiverActivity.updateFileReceiverActivityComponentsState(this);

        if (mTermuxTerminalSessionActivityClient != null)
            mTermuxTerminalSessionActivityClient.onReloadActivityStyling();

        if (mTermuxTerminalViewClient != null)
            mTermuxTerminalViewClient.onReloadActivityStyling();

        // To change the activity and drawer theme, activity needs to be recreated.
        // It will destroy the activity, including all stored variables and views, and onCreate()
        // will be called again. Extra keys input text, terminal sessions and transcripts will be preserved.
        if (recreateActivity) {
            Logger.logDebug(LOG_TAG, "Recreating activity");
            TermuxActivity.this.recreate();
        }
    }



    public static void startTermuxActivity(@NonNull final Context context) {
        ActivityUtils.startActivity(context, newInstance(context));
    }

    public static Intent newInstance(@NonNull final Context context) {
        Intent intent = new Intent(context, TermuxActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return intent;
    }

    /** Start TermuxActivity and close all existing terminal sessions, opening a fresh one.
     * Used after a data restore so the user does not keep stale sessions. */
    public static void startTermuxActivityWithSessionReset(@NonNull final Context context) {
        Intent intent = newInstance(context);
        intent.putExtra(TERMUX_ACTIVITY.EXTRA_RESET_SESSIONS, true);
        ActivityUtils.startActivity(context, intent);
    }

}

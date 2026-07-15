package com.termux.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Outline;
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
import android.view.ViewOutlineProvider;
import android.view.WindowManager;
import android.widget.EditText;
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
import com.termux.app.TermuxActivityUtils;
import com.termux.app.api.file.FileReceiverActivity;
import com.termux.app.terminal.TermuxActivityRootView;
import com.termux.app.terminal.TermuxServiceConnectionManager;
import com.termux.app.terminal.TermuxSessionSnapshotManager;
import com.termux.app.terminal.TermuxTerminalSessionActivityClient;
import com.termux.app.terminal.SessionPagerManager;
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
import com.termux.app.terminal.TermuxColorSchemeManager;
import com.termux.app.terminal.io.DirectoryHistoryController;
import com.termux.app.terminal.io.DirectoryHistoryPopupController;
import com.termux.app.terminal.io.MessageHistoryController;
import com.termux.app.terminal.io.TextInputSessionStateManager;
import com.termux.app.terminal.io.DirectoryHistoryController;
import com.termux.app.terminal.io.MessageHistoryController;
import com.termux.app.terminal.io.TextInputSessionStateManager;
import com.termux.app.terminal.io.FullScreenWorkAround;
import com.termux.shared.termux.extrakeys.ExtraKeysView;
import com.termux.shared.termux.extrakeys.ColorSchemeUtils;
import com.termux.shared.termux.extrakeys.FontUtils;
import com.termux.shared.termux.interact.TextInputDialogUtils;
import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxUtils;
import com.termux.shared.termux.settings.properties.TermuxAppSharedProperties;
import com.termux.shared.termux.theme.TermuxThemeUtils;
import com.termux.shared.theme.NightMode;
import com.termux.shared.theme.ThemeUtils;
import com.termux.shared.view.ViewUtils;
import com.termux.terminal.TerminalColors;
import com.termux.terminal.TerminalSession;
import com.termux.terminal.TerminalSessionClient;
import com.termux.terminal.TextStyle;
import android.graphics.drawable.GradientDrawable;
import com.termux.shared.shell.command.ExecutionCommand;
import com.termux.shared.termux.shell.command.runner.terminal.TermuxSession;
import com.termux.view.TerminalView;
import com.termux.view.TerminalViewClient;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
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
public final class TermuxActivity extends AppCompatActivity {

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(TermuxLocaleUtils.wrapContext(newBase));
    }

    /**
     * Owns the {@link TermuxService} binding for this activity. Created in {@link #onCreate(Bundle)}
     * and used to start/bind/unbind the service and to access the bound {@link TermuxService}.
     */
    TermuxServiceConnectionManager mServiceConnectionManager;

    /**
     * The terminal view shown in  {@link TermuxActivity} that displays the terminal.
     * <p/>
     * With the horizontal session pager (ViewPager2), this field is a <em>dynamic</em> pointer to
     * the {@link TerminalView} of the <b>currently selected</b> page. It is updated in
     * {@link #onTerminalPageSelected(int)} whenever the user swipes to a different session, so the
     * rest of the codebase that calls {@link #getTerminalView()} keeps working unchanged.
     */
    TerminalView mTerminalView;

    /**
     * Manager that owns all ViewPager2 horizontal session-pager logic (page selection, adapter
     * sync, IME-churn guards). Created in {@link #setTermuxTerminalViewAndClients()} once the pager
     * view is resolved.
     */
    SessionPagerManager mSessionPagerManager;

    public void setPendingInitialSession(@Nullable TerminalSession session) {
        if (mSessionPagerManager != null) mSessionPagerManager.setPendingInitialSession(session);
    }

    public void setColdStartSessionPending(boolean pending) {
        if (mSessionPagerManager != null) mSessionPagerManager.setColdStartSessionPending(pending);
    }

    public boolean isColdStartSessionPending() {
        return mSessionPagerManager != null && mSessionPagerManager.isColdStartSessionPending();
    }

    /**
     * Populate the pager with the live session list and select the initial page. Called from
     * {@link #onServiceConnected} once sessions exist. Honours a pending session requested earlier
     * by {@code setCurrentSession}, otherwise restores the stored/last session.
     */
    public void syncTerminalPagerToService() {
        if (mSessionPagerManager != null) mSessionPagerManager.syncTerminalPagerToService();
    }

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
     * Persists/restores the open terminal tabs (working directory, name, failsafe
     * flag) plus the active tab index across app restarts.
     */
    TermuxSessionSnapshotManager mSessionSnapshotManager;

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

    // ── Auto-complete suggestions popup ────────────────
    /** Popup window showing auto-complete suggestions from message history. */
    private PopupWindow mSuggestionsPopup;
    /** Current list of suggestion strings being displayed. */
    private final ArrayList<String> mCurrentSuggestions = new ArrayList<>();
    /** Suppress auto-complete popup during programmatic text changes (tab switch, history tap, suggestion tap). */
    private boolean mSuppressAutoComplete;
    /** Global layout listener on the input field, used to reposition the auto-complete popup. */
    @Nullable private android.view.ViewTreeObserver.OnGlobalLayoutListener mSuggestionsLayoutListener;

    // ── Incremental auto-complete optimization fields ──
    /** Previous text tracked before a TextWatcher change, for additive-change detection. */
    private String mAutoCompletePrevText = "";
    /** Start index of the pending TextWatcher change (from beforeTextChanged). */
    private int mAutoCompleteChangeStart;
    /** Count of characters being replaced (from onTextChanged). */
    private int mAutoCompleteChangeBefore;
    /** Count of new characters being inserted (from onTextChanged). */
    private int mAutoCompleteChangeCount;
    /** Last text prefix used to build mCurrentSuggestions. */
    private String mLastAppliedPrefix = "";
    /** LinearLayout content inside the popup, for in-place view updates. */
    @Nullable private LinearLayout mSuggestionsContent;
    /** Cached version at the time the current popup was built (compared against the controller's history version). */
    private int mLastBuiltHistoryVersion = -1;
    /** Last explicit width passed to mSuggestionsPopup.update() (avoid -1 on API 21-22). */
    private int mLastPopupWidth = 0;
    /** Last explicit height passed to mSuggestionsPopup.update() (avoid -1 on API 21-22). */
    private int mLastPopupHeight = 0;
    /** Last X position passed to mSuggestionsPopup.update() (suppress redundant no-op calls). */
    private int mLastPopupX = 0;
    /** Last Y position passed to mSuggestionsPopup.update() (suppress redundant no-op calls). */
    private int mLastPopupY = 0;

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

    /**
     * True while a pager page switch is in progress (tab click -> setCurrentItem, or the smooth
     * settle of a swipe). While set, the per-page focus listener
     * ({@code TermuxTerminalViewClient.registerTerminalViewFocusListener}) suppresses ALL IME
     * show/hide churn so the old page losing focus mid-switch cannot pop the keyboard.
     * {@link #onTerminalPageSelected(int)} is the single authority that re-asserts the keyboard
     * for the landed page and clears this flag. See scenario #InputPanel6.
     */
    private boolean mTerminalPageSwitchInProgress = false;

    public void setTerminalPageSwitchInProgress(boolean inProgress) {
        mTerminalPageSwitchInProgress = inProgress;
    }

    public boolean isTerminalPageSwitchInProgress() {
        return mTerminalPageSwitchInProgress;
    }

    /** Per-session text input state (content, visibility, focus, caret). */
    private final TextInputSessionStateManager mTextInputState = new TextInputSessionStateManager();

    private float mTerminalToolbarDefaultHeight;

    // ---- Sent-message history (shown as a context menu on pencil-button swipe) ----

    /** Message history controller — owns command history list and per-directory store. */
    private MessageHistoryController mMessageHistoryCtrl = null;
    private boolean mPerDirectoryMessageHistory = false;

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

    /** Cached color scheme manager — computes and vends all scheme-derived colours. */
    private final TermuxColorSchemeManager mColorSchemeManager = new TermuxColorSchemeManager();

    /** Currently highlighted history item index while dragging, or -1 for none. */
    private int mHistoryHighlightIndex = -1;

    /** Default max number of remembered messages (overridable in Settings). */
    private static final int MESSAGE_HISTORY_MAX_DEFAULT = 20;


    /** Tag value marking the synthetic "Clear" row (clears the input field). */
    private static final int MESSAGE_HISTORY_CLEAR_TAG = -2;

    /** Tag value marking the top "Clear message history…" row (wipes all history). */
    private static final int MESSAGE_HISTORY_CLEAR_ALL_TAG = -3;

    /** Max popup height in dp (bounded, never edge-to-edge); scrolls beyond this. */
    private static final int MESSAGE_HISTORY_POPUP_MAX_HEIGHT_DP = 520;

    /** Gap in dp between the button's top and the popup's bottom edge. */
    private static final int MESSAGE_HISTORY_POPUP_GAP_DP = 24;

    /** Directory history controller — owns visited-CWD list. */
    private DirectoryHistoryController mDirectoryHistoryCtrl = null;

    /** Directory-history popup controller — owns the directory popup UI + gesture state. */
    private DirectoryHistoryPopupController mDirectoryHistoryPopupCtrl = null;

    /** Default max number of remembered directories. */
    private static final int DIRECTORY_HISTORY_MAX_DEFAULT = 20;

    private static final int CONTEXT_MENU_SELECT_URL_ID = 0;
    private static final int CONTEXT_MENU_SHARE_TRANSCRIPT_ID = 1;
    private static final int CONTEXT_MENU_SHARE_SELECTED_TEXT = 10;
    private static final int CONTEXT_MENU_AUTOFILL_USERNAME = 11;
    private static final int CONTEXT_MENU_AUTOFILL_PASSWORD = 2;
    private static final int CONTEXT_MENU_RESET_TERMINAL_ID = 3;
    private static final int CONTEXT_MENU_KILL_PROCESS_ID = 4;
    private static final int CONTEXT_MENU_STYLING_ID = 5;
    private static final int CONTEXT_MENU_FONT_ID = 12;
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

    /** Pref key (in termux_prefs) persisting the per-directory message history map (JSON object). */
    private static final String PREF_MESSAGE_HISTORY_PER_DIR = "message_history_per_dir";

    /** True once the empty-history Toast has been shown during the current gesture. */
    private boolean mHistoryEmptyHintShown = false;

    /** Listens for per-directory history settings change from Settings activity. */
    private final SharedPreferences.OnSharedPreferenceChangeListener mPerDirPrefListener =
            (prefs, key) -> {
                if (!"per_directory_message_history".equals(key)) return;
                boolean newValue = prefs.getBoolean("per_directory_message_history", false);
                if (newValue == mMessageHistoryCtrl.isPerDirectoryEnabled()) return;

                // Persist history under the current (old) mode before switching.
                mMessageHistoryCtrl.save();
                mMessageHistoryCtrl.setPerDirectoryEnabled(newValue);
                loadMessageHistory();
            };

    private static final String LOG_TAG = "TermuxActivity";

    @Override
    public void onCreate(Bundle savedInstanceState) {
        Logger.logDebug(LOG_TAG, "onCreate");
        mIsOnResumeAfterOnCreate = true;

        if (savedInstanceState != null)
            mIsActivityRecreated = savedInstanceState.getBoolean(ARG_ACTIVITY_RECREATED, false);

        // Restore per-session text input state saved before recreation.
        if (savedInstanceState != null) {
            mTextInputState.restoreFromBundle(savedInstanceState);
        }

        // Delete ReportInfo serialized object files from cache older than 14 days
        ReportActivity.deleteReportInfoFilesOlderThanXDays(this, 14, false);

        // Load Termux app SharedProperties from disk
        mProperties = TermuxAppSharedProperties.getProperties();
        reloadProperties();

        // Initialise the history controllers (they own the in-memory lists + persistence).
        mMessageHistoryCtrl = new MessageHistoryController(getSharedPreferences("termux_prefs", MODE_PRIVATE));
        mDirectoryHistoryCtrl = new DirectoryHistoryController(getSharedPreferences("termux_prefs", MODE_PRIVATE));

        // Directory-history popup controller — owns its own popup window + gesture state
        // (fully decoupled from the message-history popup, which keeps its own).
        mSessionSnapshotManager = new TermuxSessionSnapshotManager(this);
        mDirectoryHistoryPopupCtrl = new DirectoryHistoryPopupController(this, mDirectoryHistoryCtrl,
                mColorSchemeManager, new DirectoryHistoryPopupController.Callback() {
                    @Nullable
                    @Override
                    public String recordCurrentDirectory() {
                        return TermuxActivity.this.recordCurrentDirectory();
                    }

                    @Override
                    public void onDirectoryPicked(@NonNull String directory) {
                        mTermuxTerminalSessionActivityClient.addNewSessionInDirectory(directory);
                    }

                    @Override
                    public void onClearAllDirectories() {
                        mDirectoryHistoryCtrl.clear();
                        showToast(getString(R.string.directory_history_cleared), true);
                    }
                }, MESSAGE_HISTORY_POPUP_GAP_DP, MESSAGE_HISTORY_POPUP_MAX_HEIGHT_DP);

        applyTermuxTheme();

        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_termux);

        // Apply the user's screen-orientation choice (Settings -> Screen orientation).
        TermuxActivityUtils.applyScreenOrientation(this);

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
                dismissAutoCompleteSuggestions();
                setTextInputVisible(false);
                updateToggleTextInputButtonIcon();
            }
            boolean imeWasVisible = mSoftKeyboardVisible;
            mSoftKeyboardVisible = imeVisible;

            // Reposition the auto-complete popup when IME shows/hides, since
            // the input field's on-screen position may change.
            if (imeVisible != imeWasVisible && mSuggestionsPopup != null
                    && mSuggestionsPopup.isShowing()) {
                repositionAutoCompletePopup();
            }

            // If preference is on, show/hide extra keys with keyboard
            if (imeVisible != imeWasVisible && mExtraKeysView != null
                    && getTerminalToolbarContainer().getVisibility() == View.VISIBLE
                    && !isTextInputVisible()
                    && mPreferences.shouldHideExtraKeysWithKeyboard()) {
                mExtraKeysView.setVisibility(imeVisible ? View.VISIBLE : View.GONE);
                Logger.logDebug(LOG_TAG, "Auto-" + (imeVisible ? "showing" : "hiding") + " extra keys with keyboard");
            }

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

        // NOTE: the terminal context menu is registered per-page on each TerminalView inside
        // TerminalPagerAdapter (onBindViewHolder), NOT on the activity root view. Registering it on
        // the root made a long-press on the sibling text-input panel bubble up to the root's context
        // menu and show the terminal menu over the input field (regression: long-press on the input
        // panel opened the terminal context menu instead of selecting a word).

        FileReceiverActivity.updateFileReceiverActivityComponentsState(this);

        // Register broadcast receiver in onCreate so it works even when activity is in background
        registerTermuxActivityBroadcastReceiver();

        // Create the service connection manager that owns the TermuxService binding lifecycle.
        mServiceConnectionManager = new TermuxServiceConnectionManager(this);

        // Start the {@link TermuxService} and bind to it. On failure mark the activity invalid
        // and stop — a toast explaining the failure is shown by the manager.
        if (!mServiceConnectionManager.startAndBindService()) {
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
        if (mPreferences.isTerminalMarginAdjustmentEnabled()) {
            final TermuxActivityRootView rootView = getTermuxActivityRootView();
            if (rootView != null)
                rootView.forceRelayout();
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);

        // When Termux regains focus (e.g. after returning from Settings), apply
        // the screen-orientation choice immediately so the change is visible
        // without restarting the app.
        if (hasFocus) {
            TermuxActivityUtils.applyScreenOrientation(this);
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
        if (mPreferences.isTerminalMarginAdjustmentEnabled()) {
            final TermuxActivityRootView rootView = getTermuxActivityRootView();
            if (rootView != null)
                new Handler(Looper.getMainLooper()).postDelayed(rootView::forceRelayout, 300);
        }
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

        // Dismiss any history popup still showing, to avoid a leaked window when
        // the activity goes to the background.
        dismissMessageHistoryPopup();
        if (mDirectoryHistoryPopupCtrl != null) mDirectoryHistoryPopupCtrl.dismiss();

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

        getSharedPreferences("termux_prefs", MODE_PRIVATE)
                .unregisterOnSharedPreferenceChangeListener(mPerDirPrefListener);

        unregisterTermuxActivityBroadcastReceiver();

        // Unbind the TermuxService, releasing the session client so the service no longer holds
        // a reference to this activity.
        mServiceConnectionManager.unbindService();
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle savedInstanceState) {
        Logger.logVerbose(LOG_TAG, "onSaveInstanceState");

        super.onSaveInstanceState(savedInstanceState);
        saveTerminalToolbarTextInput(savedInstanceState);
        savedInstanceState.putBoolean(ARG_ACTIVITY_RECREATED, true);

        // Persist per-session text input state across activity recreation
        mTextInputState.saveToBundle(savedInstanceState);
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
        TermuxActivityUtils.applyScreenOrientation(activity);
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

        // Set up the horizontal session pager (ViewPager2). The pager owns the TerminalViews now;
        // mTerminalView is (re)assigned to the active page in SessionPagerManager.onTerminalPageSelected().
        androidx.viewpager2.widget.ViewPager2 pager = findViewById(R.id.terminal_view_pager);
        mSessionPagerManager = new SessionPagerManager(this, pager);
        mSessionPagerManager.setup();

        if (mTermuxTerminalViewClient != null)
            mTermuxTerminalViewClient.onCreate();

        if (mTermuxTerminalSessionActivityClient != null)
            mTermuxTerminalSessionActivityClient.onCreate();
    }

    public void setTermuxSessionsListView() {
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
                        if (mDirectoryHistoryPopupCtrl.isShowing()) {
                            mDirectoryHistoryPopupCtrl.updateHighlight(event.getRawX(), event.getRawY());
                            return true;
                        }
                        float dy = event.getRawY() - downXY[1];
                        float dx = event.getRawX() - downXY[0];
                        if (dy < -touchSlop && Math.abs(dy) > Math.abs(dx)
                                && mDirectoryHistoryPopupCtrl.shouldShow()) {
                            v.setPressed(false);
                            swipeConsumed[0] = true;
                            mDirectoryHistoryPopupCtrl.show(v);
                            return true;   // consume: cancels pending click/long-press
                        }
                        return false;
                    }
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL: {
                        boolean wasPopup = mDirectoryHistoryPopupCtrl.isShowing();
                        if (wasPopup) {
                            int selected = mDirectoryHistoryPopupCtrl.getHighlightIndex();
                            mDirectoryHistoryPopupCtrl.dismiss();
                            if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                                if (selected == DirectoryHistoryPopupController.CLEAR_ALL_TAG) {
                                    mDirectoryHistoryPopupCtrl.confirmClear();
                                } else if (selected >= 0) {
                                    mDirectoryHistoryPopupCtrl.pick(selected);
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
        mTermuxTerminalExtraKeys = new TermuxTerminalExtraKeys(this, getTerminalView(),
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
            if (!hasFocus) dismissAutoCompleteSuggestions();
        });

        // Auto-complete from message history as the user types
        editText.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                mAutoCompletePrevText = s.toString();
                mAutoCompleteChangeStart = start;
            }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                mAutoCompleteChangeBefore = before;
                mAutoCompleteChangeCount = count;
            }
            @Override
            public void afterTextChanged(android.text.Editable s) {
                updateAutoCompleteSuggestions();
            }
        });

        // Reposition the auto-complete popup when the caret moves without text changes
        // (e.g. tapping a different position in the text). We use ACTION_UP on the EditText
        // because setOnSelectionChangedListener is API 29+ and the project targets API 28.
        editText.setOnTouchListener((v, event) -> {
            if (event.getAction() == android.view.MotionEvent.ACTION_UP) {
                v.post(() -> repositionAutoCompletePopup());
            }
            return false; // don't consume the event, let the EditText handle it
        });

        editText.setOnEditorActionListener((v, actionId, event) -> {
            TerminalSession session = getCurrentSession();
            if (session != null) {
                if (session.isRunning()) {
                    String textToSend = editText.getText().toString();
                    boolean hasText = textToSend.length() > 0;
                    // Remember non-empty sent messages in the history (dedup, newest first).
                    // NOTE: the history stores the raw text WITHOUT the trailing newline.
                    if (hasText) {
                        addToMessageHistory(textToSend);
                        // By default append a carriage return so the line is SUBMITTED (Enter).
                        // If the user disabled "Append Enter on send" in settings, write raw text
                        // only (no trailing newline) — this is what some users want when sending
                        // partial input that should not execute a command yet.
                        if (mPreferences.shouldTextInputAppendEnter()) {
                            session.write(textToSend + "\r");
                        } else {
                            session.write(textToSend);
                        }
                    } else {
                        // Empty field: just send a lone Enter (newline) to the session.
                        session.write("\r");
                    }

                    // Clear the field and the per-session saved text/caret BEFORE
                    // hiding the panel. Otherwise setTextInputVisible(false) ->
                    // saveTextInputForCurrentSession() re-persists the just-sent
                    // text (the field is still non-empty at that point) and it
                    // reappears when the panel is opened again.
                    editText.setText("");
                    mTextInputState.clear(session.mHandle);
                    mTextInputState.setCaret(session.mHandle, -1);

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
        mTextInputState.saveInput(session.mHandle, text);
        // Remember the caret position so it can be restored on tab switch and on
        // panel hide/show for the same session. The EditText keeps its selection
        // even after losing focus, so getSelectionStart() is valid regardless of
        // focus — we always record it (no hasFocus() guard), otherwise hiding the
        // panel after the focus had already moved to the terminal would drop the
        // caret and it would jump to the end on re-open.
        mTextInputState.setCaret(session.mHandle, textInputView.getSelectionStart());
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
        String text = mTextInputState.getInputText(session.mHandle);
        mSuppressAutoComplete = true;
        textInputView.setText(text != null ? text : "");
        mSuppressAutoComplete = false;
        // Restore the caret position saved for this session (clamped to text length).
        Integer caret = mTextInputState.hasCaret(session.mHandle) ? mTextInputState.getCaret(session.mHandle) : null;
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
        mTextInputState.clear(session);
    }

    /**
     * Record, for the current session, whether focus (input) is on the text
     * input panel (focusOnInput=true) or on the terminal view (false).
     */
    public void setFocusOnInputForCurrentSession(boolean focusOnInput) {
        mTextInputState.setFocusOnInput(getCurrentSession(), focusOnInput);
    }

    /**
     * Get whether, for the given session, focus was last on the text input
     * panel (true) or on the terminal view (false). Defaults to false
     * (terminal) for unknown sessions.
     */
    public boolean isFocusOnInputForSession(@Nullable TerminalSession session) {
        return mTextInputState.isFocusOnInput(session);
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
            mMessageHistoryCtrl.setMaxSize(getSharedPreferences("termux_prefs", MODE_PRIVATE)
                    .getInt("message_history_max", MESSAGE_HISTORY_MAX_DEFAULT));
            mMessageHistoryCtrl.setPerDirectoryEnabled(getSharedPreferences("termux_prefs", MODE_PRIVATE)
                    .getBoolean("per_directory_message_history", false));
            loadMessageHistory();

            // Hot-reload: when the user toggles per-directory history in Settings
            // and returns to the terminal, the mode switches immediately.
            getSharedPreferences("termux_prefs", MODE_PRIVATE)
                    .registerOnSharedPreferenceChangeListener(mPerDirPrefListener);

            // Load the persisted recent-directories history once.
            mDirectoryHistoryCtrl.setMaxSize(getSharedPreferences("termux_prefs", MODE_PRIVATE)
                    .getInt("directory_history_max", DIRECTORY_HISTORY_MAX_DEFAULT));
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
                        mHistoryEmptyHintShown = false;
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
                                } else if (selected >= 0 && selected < mMessageHistoryCtrl.getHistoryList().size()) {
                                    onHistoryMessagePicked(mMessageHistoryCtrl.getHistoryList().get(selected));
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
     * Called when the current terminal session or working directory has changed
     * (e.g. tab switch). If per-directory message history is enabled, saves the
     * current directory's history and loads the new directory's entries into
     * mMessageHistoryCtrl.getHistoryList().
     *
     * Also performs lazy migration of global history when a real session CWD
     * is first encountered and the per-dir store has no entry for it yet
     * (handles the cold-start case where the session wasn't ready during
     * {@link #loadMessageHistoryPerDirectory()}).
     */
    public void onHistoryDirectoryChanged() {
        if (!mMessageHistoryCtrl.isPerDirectoryEnabled()) return;
        String oldCwd = mMessageHistoryCtrl.getHistoryCurrentDirectory();
        String newCwd = getCurrentCwdForHistory();
        mMessageHistoryCtrl.onHistoryDirectoryChanged(
                oldCwd != null ? oldCwd : newCwd, newCwd);
    }

    /**
     * Add a just-sent message to the history. Deduplicated by content: if the
     * same text already exists it is removed and re-inserted at the front, so a
     * re-sent message rises to the front (index 0), which is rendered at the
     * BOTTOM of the popup (newest = bottom, nearest the button). Persists the list.
     *
     * In per-directory mode, detects cross-directory moves (e.g. after `cd`
     * within the same tab) and cleanly separates histories so messages sent from
     * one directory never leak into another.
     */
    private void addToMessageHistory(@NonNull String message) {
        if (TextUtils.isEmpty(message)) return;
        String cwd = getCurrentCwdForHistory();
        mMessageHistoryCtrl.addToMessageHistory(message, cwd);
    }

    /**
     * Returns the current working directory to use as a per-directory history key,
     * or a fallback if unavailable.
     */
    @NonNull
    private String getCurrentCwdForHistory() {
        TerminalSession session = getCurrentSession();
        if (session != null) {
            String cwd = session.getCwd();
            if (!TextUtils.isEmpty(cwd)) return cwd;
        }
        return ".";
    }

    private void loadMessageHistory() {
        mMessageHistoryCtrl.load(getCurrentCwdForHistory());
    }

    private void saveMessageHistory() {
        mMessageHistoryCtrl.save();
    }

    private boolean isHistoryPopupShowing() {
        return mHistoryPopup != null && mHistoryPopup.isShowing();
    }


    // ── Auto-complete suggestions from message history ────────────────

    /**
     * Three-way dispatcher for the auto-complete popup:
     *
     * Path A (full rebuild) — called when the user deletes, replaces, pastes, or the
     * history has changed externally.  Re-scans the full mMessageHistoryCtrl.getHistoryList() and creates a
     * brand-new PopupWindow (dismiss + showAtLocation).
     *
     * Path B (additive filter) — called when the user only types more characters without
     * deleting any text.  Filters mCurrentSuggestions in place (O(maxCount) instead of
     * O(mMessageHistoryCtrl.getHistoryList())), removes non-matching views from the existing popup, top-ups
     * from history if the result is smaller than maxCount, recalculates bold spans, and
     * updates the popup size/position (one IPC instead of two).
     *
     * Path C (reposition only) — not truly a separate path here; when the text hasn't
     * changed w.r.t. the previous call the {@link OnGlobalLayoutListener} and
     * {@link OnTouchListener} already call {@link #repositionAutoCompletePopup()}
     * separately.  The dispatcher here always receives a text-change event.
     */
    private void updateAutoCompleteSuggestions() {
        if (mIsInvalidState) return;
        if (mSuppressAutoComplete) return;

        final EditText inputField = findViewById(R.id.terminal_toolbar_text_input);
        if (inputField == null) return;

        final String text = inputField.getText().toString();
        Logger.logInfo(LOG_TAG, "[autocomplete] text=\"" + text + "\"");

        if (TextUtils.isEmpty(text)) {
            dismissAutoCompleteSuggestions();
            return;
        }

        int maxCount = getSharedPreferences("termux_prefs", MODE_PRIVATE)
                .getInt("suggestions_max_count", 4);
        if (maxCount < 0) maxCount = 0;
        if (maxCount > 10) maxCount = 10;
        if (maxCount == 0) {
            dismissAutoCompleteSuggestions();
            return;
        }

        // ── Early skip for IME re-compose of same text ──
        // Gboard (and likely other IMEs) re-composes an unchanged composing span
        // on certain interactions (e.g. after tapping a suggestion candidate in
        // the IME's own bar). The text is identical to prevText, so the additive
        // detection correctly says false (length didn't grow), but Path A would
        // re-scan history and rebuild the popup unnecessarily. Skip the entire
        // update when text is unchanged, the history version is current, and
        // the popup is already showing valid suggestions.
        String prevText = mAutoCompletePrevText;
        if (text.equals(prevText) && mAutoCompleteChangeCount == mAutoCompleteChangeBefore) {
            if (mMessageHistoryCtrl.getHistoryVersion() == mLastBuiltHistoryVersion
                    && mSuggestionsPopup != null && mSuggestionsPopup.isShowing()
                    && !mCurrentSuggestions.isEmpty()) {
                Logger.logInfo(LOG_TAG, "[autocomplete] skip recompose (text==prevText, popup showing)");
                return;
            }
        }

        // ── Detect whether this is an additive (append-only) change ──
        // Language/IME-agnostic: the new text must extend prevText by appending
        // (prevText stays a prefix and text grew). We do NOT require before == 0,
        // because IME composition (e.g. Gboard Cyrillic) replaces the composing span
        // on every keystroke (before > 0), which made the old check take Path A on
        // each Cyrillic char. The non-empty list guard forces Path A to bootstrap on
        // the first keystroke (Path B on an empty list would wrongly dismiss).
        boolean additive = false;
        if (mAutoCompleteChangeCount > 0
                && text.length() > prevText.length()
                && text.startsWith(prevText)
                && !mCurrentSuggestions.isEmpty()) {
            additive = true;
        }
        Logger.logInfo(LOG_TAG, "[autocomplete] prev=\"" + prevText + "\" before="
                + mAutoCompleteChangeBefore + " count=" + mAutoCompleteChangeCount
                + " additive=" + additive + " hVer=" + mMessageHistoryCtrl.getHistoryVersion()
                + "/" + mLastBuiltHistoryVersion);

        // ── Path A (full rebuild): not additive OR history changed externally ──
        if (!additive || mMessageHistoryCtrl.getHistoryVersion() != mLastBuiltHistoryVersion) {
            Logger.logInfo(LOG_TAG, "[autocomplete] → PATH A (fullRescan) reason="
                    + (!additive ? "non-additive" : "history=" + mMessageHistoryCtrl.getHistoryVersion() + "≠" + mLastBuiltHistoryVersion));
            fullRescanSuggestions(text, maxCount);
            return;
        }

        // ── Path B (additive filter): only appending characters ──
        // Filter mCurrentSuggestions in-place
        final int preFilterCount = mCurrentSuggestions.size();
        for (int i = mCurrentSuggestions.size() - 1; i >= 0; i--) {
            String s = mCurrentSuggestions.get(i);
            if (s.length() <= text.length()
                    || !s.regionMatches(true, 0, text, 0, text.length())
                    || s.equals(text)) {
                mCurrentSuggestions.remove(i);
            }
        }
        int filteredRemoved = preFilterCount - mCurrentSuggestions.size();
        Logger.logInfo(LOG_TAG, "[autocomplete] → PATH B filteredRemoved=" + filteredRemoved
                + " remaining=" + mCurrentSuggestions.size());

        if (mCurrentSuggestions.isEmpty()) {
            // The additive filter emptied the list. This happens when there was
            // nothing to filter to begin with (e.g. the very first keystroke after
            // the field was cleared, so mCurrentSuggestions is still empty rather
            // than a previously-shown popup being filtered down). A plain dismiss
            // here would silently drop a legitimate character. Fall back to a full
            // history rescan: it shows suggestions if any match, otherwise it still
            // dismisses correctly.
            Logger.logInfo(LOG_TAG, "[autocomplete] filter emptied list → full rescan");
            fullRescanSuggestions(text, maxCount);
            return;
        }

        // Top-up from history if filtered list is smaller than maxCount
        if (mCurrentSuggestions.size() < maxCount) {
            for (String msg : mMessageHistoryCtrl.getHistoryList()) {
                if (mCurrentSuggestions.size() >= maxCount) break;
                if (!mCurrentSuggestions.contains(msg)
                        && msg.length() > text.length()
                        && msg.regionMatches(true, 0, text, 0, text.length())
                        && !msg.equals(text)) {
                    mCurrentSuggestions.add(msg);
                }
            }
        }

        Logger.logInfo(LOG_TAG, "[autocomplete] after top-up suggestions=" + mCurrentSuggestions.size());

        // If popup isn't showing yet, build it fresh
        if (mSuggestionsPopup == null || !mSuggestionsPopup.isShowing()) {
            Logger.logInfo(LOG_TAG, "[autocomplete] → showAutoCompletePopup (popup null or not showing)");
            showAutoCompletePopup(inputField);
            return;
        }

        // In-place update of the existing popup content
        updatePopupContent(text, inputField);
    }

    /**
     * Path A: full re-scan of mMessageHistoryCtrl.getHistoryList() and fresh popup creation.
     * Same logic as the original updateAutoCompleteSuggestions().
     */
    private void fullRescanSuggestions(@NonNull String text, int maxCount) {
        mCurrentSuggestions.clear();
        for (String msg : mMessageHistoryCtrl.getHistoryList()) {
            if (mCurrentSuggestions.size() >= maxCount) break;
            if (msg.length() > text.length()
                    && msg.regionMatches(true, 0, text, 0, text.length())
                    && !msg.equals(text)) {
                mCurrentSuggestions.add(msg);
            }
        }

        Logger.logInfo(LOG_TAG, "[autocomplete] fullRescan text=\"" + text + "\" max=" + maxCount
                + " matches=" + mCurrentSuggestions.size());

        if (mCurrentSuggestions.isEmpty()) {
            dismissAutoCompleteSuggestions();
        } else {
            showAutoCompletePopup(findViewById(R.id.terminal_toolbar_text_input));
        }
    }

    /** Path separator set used by {@link #wordStartOffset} to find word boundaries. */
    private static final String WORD_SEPARATORS = " /";  // space + slash

    /**
     * Returns the starting position of the last word (token) in {@code s},
     * where words are delimited by any character in {@link #WORD_SEPARATORS}.
     * Returns 0 if no separator is found (the whole string is the only word)
     * or if {@code s} is empty.
     */
    private static int wordStartOffset(@NonNull String s) {
        int i = s.length();
        while (i > 0) {
            char c = s.charAt(i - 1);
            if (c == ' ' || c == '/') return i;
            i--;
        }
        return 0;
    }

    /**

     * Build the display {@link android.text.SpannableString} for an auto-complete
     * suggestion. Applies the word-based leading truncation (the {@code "... "}
     * prefix added when the match starts mid-word) and, when the result would
     * exceed {@code maxLines} lines, manually truncates it and appends a trailing
     * {@code '…'}.
     *
     * <p>Why manual truncation instead of {@code TextView.setEllipsize(END)}:
     * on Android (API 21-28 in particular) {@code ellipsize=end} is only reliably
     * honored for <b>single-line</b> text. With {@code setMaxLines(n)} where
     * {@code n > 1} the framework routes to {@code StaticLayout} but the trailing
     * ellipsis on the last line is unreliable and frequently never appears. We
     * therefore measure and cut the text ourselves so the {@code '…'} is
     * guaranteed for long suggestions/messages regardless of OS version. The
     * matched input prefix is rendered in BOLD on top of the (possibly truncated)
     * display text.
     *
     * @param availWidth available text width in px (popup width minus padding);
     *                   pass {@code 0} to skip truncation (e.g. not yet laid out).
     */
    @NonNull
    private android.text.SpannableString buildSuggestionSpannable(@NonNull String suggestion,
            @NonNull String input, int availWidth, @NonNull android.text.TextPaint paint) {
        int wordStart = Math.min(wordStartOffset(input), suggestion.length());
        int boldLen = input.length() - wordStart;
        boolean hasLastWord = boldLen > 0;
        String prefix = (wordStart > 0) ? "... " : "";
        int prefixLen = prefix.length();
        String displayText = (prefixLen > 0) ? prefix + suggestion.substring(wordStart) : suggestion;

        if (availWidth > 0) {
            displayText = truncateToLines(displayText, availWidth, paint, 2, false);
        }

        android.text.SpannableString ss = new android.text.SpannableString(displayText);
        int spanEnd = Math.min(prefixLen + boldLen, displayText.length());
        if (hasLastWord && spanEnd > prefixLen
                && suggestion.regionMatches(true, 0, input, 0, input.length())) {
            ss.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
                    prefixLen, spanEnd, android.text.SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return ss;
    }

    /**
     * Truncate {@code text} so it fits within {@code maxLines} lines of width
     * {@code availWidth}. Returns the original text unchanged if it already fits.
     * Otherwise keeps the start (when {@code middle} is false) and appends a
     * single {@code '…'}, or keeps the start and end and replaces the dropped
     * middle with a {@code '…'} (when {@code middle} is true, for paths).
     */
    @NonNull
    private static String truncateToLines(@NonNull String text, int availWidth,
            @NonNull android.text.TextPaint paint, int maxLines, boolean middle) {
        if (text.length() == 0 || fitsLines(text, availWidth, paint, maxLines)) {
            return text;
        }
        if (!middle) {
            int lo = 0, hi = text.length();
            while (lo < hi) {
                int mid = (lo + hi + 1) / 2;
                if (fitsLines(text.substring(0, mid) + "…", availWidth, paint, maxLines)) {
                    lo = mid;
                } else {
                    hi = mid - 1;
                }
            }
            return text.substring(0, lo) + "…";
        }
        int lo = 0, hi = text.length();
        while (lo < hi) {
            int mid = (lo + hi + 1) / 2;
            String cand = middleCandidate(text, mid);
            if (fitsLines(cand, availWidth, paint, maxLines)) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        return middleCandidate(text, lo);
    }

    @NonNull
    private static String middleCandidate(@NonNull String text, int keep) {
        int head = keep / 2;
        int tail = keep - head;
        if (head > text.length()) head = text.length();
        if (tail > text.length() - head) tail = text.length() - head;
        if (tail < 0) tail = 0;
        return text.substring(0, head) + "…" + text.substring(text.length() - tail);
    }

    /** True when {@code text} lays out to at most {@code maxLines} lines of {@code availWidth}. */
    @SuppressWarnings("deprecation")
    private static boolean fitsLines(@NonNull String text, int availWidth,
            @NonNull android.text.TextPaint paint, int maxLines) {
        android.text.StaticLayout layout;
        if (android.os.Build.VERSION.SDK_INT >= 23) {
            layout = android.text.StaticLayout.Builder.obtain(
                    text, 0, text.length(), paint, availWidth)
                    .setMaxLines(maxLines)
                    .setEllipsize(null)
                    .build();
        } else {
            layout = new android.text.StaticLayout(
                    text, paint, availWidth,
                    android.text.Layout.Alignment.ALIGN_NORMAL, 1.0f, 0.0f, false);
        }
        return layout.getLineCount() <= maxLines;
    }

    /**
     * (Optimize auto-complete: three-path dispatch, additive filtering, history version guard)
     * Path B (optimized): update bold spans on the existing popup content when the
     * suggestion set is unchanged and only the typed prefix grew longer.
     * Mutates the Spannable buffer in-place via {@code tv.getText()} so no
     * requestLayout / remeasure is triggered — only {@code invalidate()} for redraw.
     * When the word-start anchor changes (e.g. user crosses a space/slash boundary)
     * falls back to {@code setText()} to rebuild the truncated display.
     */
    private void updateBoldSpansOnly(@NonNull String newText) {
        if (mSuggestionsPopup == null || !mSuggestionsPopup.isShowing()) return;
        android.widget.ScrollView scroll = (android.widget.ScrollView) mSuggestionsPopup.getContentView();
        if (scroll == null) return;
        LinearLayout content = (LinearLayout) scroll.getChildAt(0);
        if (content == null) return;

        // Precompute word-boundary info (shared across all child views)
        final int inputLen = newText.length();
        final int wordStart = Math.min(wordStartOffset(newText), inputLen);
        final int boldLen = inputLen - wordStart;
        final boolean hasLastWord = boldLen > 0;
        final String prefix = (wordStart > 0) ? "... " : "";
        final int prefixLen = prefix.length();

        Logger.logInfo(LOG_TAG, "[autocomplete] updateBoldSpansOnly wordStart=" + wordStart
                + " boldLen=" + boldLen + " views=" + content.getChildCount());
        for (int i = 0; i < content.getChildCount(); i++) {
            View child = content.getChildAt(i);
            if (!(child instanceof TextView)) continue;
            TextView tv = (TextView) child;
            String suggestion = (String) tv.getTag();
            if (suggestion == null) continue;

            // Compute the expected word-truncated display string
            int ws = Math.min(wordStart, suggestion.length());
            String expectedDisplay = (prefixLen > 0)
                    ? prefix + suggestion.substring(ws)
                    : suggestion;

            CharSequence currentText = tv.getText();
            if (!expectedDisplay.contentEquals(currentText)) {
                // Word-boundary anchor shifted → rebuild text with setText (rare:

                // crossing a space or / boundary). Re-measure and re-truncate too
                // so the trailing '…' stays correct for long suggestions.
                int availWidth = tv.getWidth() - tv.getPaddingLeft() - tv.getPaddingRight();
                android.text.SpannableString ss = buildSuggestionSpannable(
                        suggestion, newText, Math.max(0, availWidth), tv.getPaint());
                // (Optimize auto-complete: three-path dispatch, additive filtering, history version guard)
                tv.setText(ss, TextView.BufferType.SPANNABLE);
            } else {
                // Display buffer unchanged → only mutate bold spans in-place (fast path)
                android.text.Spannable sp = (android.text.Spannable) currentText;
                android.text.style.StyleSpan[] old = sp.getSpans(0, sp.length(),
                        android.text.style.StyleSpan.class);
                for (android.text.style.StyleSpan s : old) sp.removeSpan(s);
                if (hasLastWord && prefixLen + boldLen <= sp.length()
                        && suggestion.regionMatches(true, 0, newText, 0, inputLen)) {
                    sp.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
                            prefixLen, prefixLen + boldLen,
                            android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
                tv.invalidate();
            }
        }
    }

    /**
     * Path B: update the existing popup in-place after an additive text change.
     *
     * Removes non-matching views from {@link #mSuggestionsContent},
     * recalculates bold-spans for survivors, top-ups with new views if history
     * had more matches, then measures and resizes the popup.
     */
    private void updatePopupContent(@NonNull String newText, @NonNull EditText inputField) {
        if (mSuggestionsPopup == null || !mSuggestionsPopup.isShowing()) {
            showAutoCompletePopup(inputField);
            return;
        }
        android.widget.ScrollView scroll = (android.widget.ScrollView) mSuggestionsPopup.getContentView();
        if (scroll == null) { showAutoCompletePopup(inputField); return; }
        LinearLayout content = (LinearLayout) scroll.getChildAt(0);
        if (content == null) { showAutoCompletePopup(inputField); return; }

        // Remove non-matching views (iterate backward so indices stay valid)
        final int inputLen = newText.length();
        final int prevChildCount = content.getChildCount();
        for (int i = prevChildCount - 1; i >= 0; i--) {
            View child = content.getChildAt(i);
            String suggestion = (String) child.getTag();
            if (suggestion == null
                    || suggestion.length() <= inputLen
                    || !suggestion.regionMatches(true, 0, newText, 0, inputLen)
                    || suggestion.equals(newText)) {
                content.removeViewAt(i);
            }
        }
        boolean contentChanged = (content.getChildCount() != prevChildCount);

        // Rebuild mCurrentSuggestions list to match the current views
        mCurrentSuggestions.clear();
        for (int i = 0; i < content.getChildCount(); i++) {
            View child = content.getChildAt(i);
            String tag = (String) child.getTag();
            if (tag != null) mCurrentSuggestions.add(tag);
        }

        // Top-up from history if needed
        int maxCount = getSharedPreferences("termux_prefs", MODE_PRIVATE)
                .getInt("suggestions_max_count", 4);
        if (maxCount < 0) maxCount = 0;
        if (maxCount > 10) maxCount = 10;
        final int preTopUpCount = content.getChildCount();
        if (mCurrentSuggestions.size() < maxCount) {
            for (String msg : mMessageHistoryCtrl.getHistoryList()) {
                if (mCurrentSuggestions.size() >= maxCount) break;
                if (!mCurrentSuggestions.contains(msg)
                        && msg.length() > newText.length()
                        && msg.regionMatches(true, 0, newText, 0, newText.length())
                        && !msg.equals(newText)) {
                    mCurrentSuggestions.add(msg);
                    // Create a new TextView for this top-up item
                    TextView tv = buildSuggestionTextView(msg, newText);
                    content.addView(tv, new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT));
                }
            }
        }
        if (content.getChildCount() != preTopUpCount) contentChanged = true;

        Logger.logInfo(LOG_TAG, "[autocomplete] updatePopupContent contentChanged=" + contentChanged
                + " views=" + content.getChildCount() + " suggestions=" + mCurrentSuggestions.size());

        // Fast path: no structural change — only update bold-spans in-place, skip measure/update
        if (!contentChanged) {
            Logger.logInfo(LOG_TAG, "[autocomplete] → FAST PATH (bold-spans only)");
            updateBoldSpansOnly(newText);
            mLastAppliedPrefix = newText;
            applyPopupGeometry(inputField);
            return;
        }

        // Structural change — update bold-spans, measure, resize
        Logger.logInfo(LOG_TAG, "[autocomplete] → STRUCTURAL CHANGE (measure+update)");
        updateBoldSpansOnly(newText);

        // Reset scroll to top synchronously before measure (no post() needed,
        // the ScrollView is already laid out and showing).
        scroll.setScrollY(0);
        content.measure(
                View.MeasureSpec.makeMeasureSpec(mLastPopupWidth, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int newHeight = content.getMeasuredHeight();

        // Resize and reposition
        mLastPopupHeight = newHeight;
        mLastAppliedPrefix = newText;
        applyPopupGeometry(inputField);
    }

    /** Build a single suggestion TextView (reusable helper). */
    private TextView buildSuggestionTextView(@NonNull String suggestion, @NonNull String input) {
        int padH = dpToPx(14);
        int padV = dpToPx(10);
        TextView tv = new TextView(this);


        tv.setTextColor(mColorSchemeManager.getHistoryTextColor());
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        tv.setPadding(padH, padV, padH, padV);

        // Available text width = popup width minus horizontal padding. Mirrors the
        // width the popup is sized to in showAutoCompletePopup (sumWidth).
        final EditText inputField = (EditText) findViewById(R.id.terminal_toolbar_text_input);
        int popupWidth = Math.max(dpToPx(200),
                (inputField != null ? inputField.getWidth() : 0) - dpToPx(16));
        int availWidth = Math.max(0, popupWidth - 2 * padH);

        // Word-based leading truncation + manual trailing '…' (TextView's
        // setEllipsize(END) is unreliable for maxLines>1 on API 21-28).
        android.text.SpannableString ss = buildSuggestionSpannable(
                suggestion, input, availWidth, tv.getPaint());
        tv.setText(ss, TextView.BufferType.SPANNABLE);
        tv.setMaxLines(2);
        tv.setEllipsize(android.text.TextUtils.TruncateAt.END); // backup; text already fits 2 lines
         // (Optimize auto-complete: three-path dispatch, additive filtering, history version guard)
        tv.setTextColor(mColorSchemeManager.getHistoryTextColor());
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        tv.setPadding(padH, padV, padH, padV);
        tv.setTag(suggestion);
        // Solid press highlight matching the message-history popup (per-theme)
        android.graphics.drawable.StateListDrawable sel = new android.graphics.drawable.StateListDrawable();
        int highlightColor = mColorSchemeManager.getHistoryHighlightFill();
        sel.addState(new int[]{android.R.attr.state_pressed},
                new android.graphics.drawable.ColorDrawable(highlightColor));
        sel.addState(new int[]{},
                new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
        sel.setEnterFadeDuration(0);
        sel.setExitFadeDuration(0);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            tv.setBackground(sel);
        } else {
            tv.setBackgroundDrawable(sel);
        }
        tv.setClickable(true);
        final String finalSuggestion = suggestion;

        // (Optimize auto-complete: three-path dispatch, additive filtering, history version guard)
        tv.setOnClickListener(v -> {
            mSuppressAutoComplete = true;
            try {
                if (inputField != null) {
                    inputField.setText(finalSuggestion);
                    inputField.setSelection(finalSuggestion.length());
                }
            } finally {
                mSuppressAutoComplete = false;
            }
            dismissAutoCompleteSuggestions();
            dismissMessageHistoryPopup();
            if (mDirectoryHistoryPopupCtrl != null) mDirectoryHistoryPopupCtrl.dismiss();
        });
        return tv;
    }

    /**
     * Calculate the X position for the popup at the input caret.
     * Reusable helper shared by showAutoCompletePopup, updatePopupContent and reposition.
     */
    private int calcPopupX(@NonNull EditText inputField, int popupWidth) {
        int cursorPos = inputField.getSelectionStart();
        android.text.Layout layout = inputField.getLayout();
        float cursorX = 0;
        if (layout != null && cursorPos >= 0) {
            cursorX = layout.getPrimaryHorizontal(cursorPos);
        }
        int[] loc = new int[2];
        inputField.getLocationInWindow(loc);
        int popupX = loc[0] + (int) cursorX + dpToPx(4);
        int displayWidth = getResources().getDisplayMetrics().widthPixels;
        if (popupX + popupWidth > displayWidth - dpToPx(8)) {
            popupX = displayWidth - popupWidth - dpToPx(8);
        }
        if (popupX < dpToPx(8)) popupX = dpToPx(8);
        return popupX;
    }

    /**
     * Calculate the Y position for the popup (above the caret, fallback below).
     */
    private int calcPopupY(@NonNull EditText inputField, int popupHeight, int popupWidth) {
        int cursorPos = inputField.getSelectionStart();
        android.text.Layout layout = inputField.getLayout();
        float cursorY = 0;
        if (layout != null && cursorPos >= 0) {
            cursorY = layout.getLineTop(layout.getLineForOffset(cursorPos));
        }
        int[] loc = new int[2];
        inputField.getLocationInWindow(loc);
        int popupY = loc[1] + (int) cursorY - popupHeight - dpToPx(4);
        if (popupY < dpToPx(16)) {
            popupY = loc[1] + (int) cursorY + dpToPx(8);
        }
        return popupY;
    }

    private void showAutoCompletePopup(@NonNull EditText inputField) {
        Logger.logInfo(LOG_TAG, "[autocomplete] showAutoCompletePopup suggestions="
                + mCurrentSuggestions.size() + " popupShown=" + (mSuggestionsPopup != null));

        // ── Reuse the existing window to avoid dismiss→show flicker ──
        if (mSuggestionsPopup != null && mSuggestionsPopup.isShowing()
                && mSuggestionsContent != null && !mCurrentSuggestions.isEmpty()
                && mSuggestionsContent.getParent() != null) {
            final String input = inputField.getText().toString();

            // Content-changed guard: skip the removeAllViews + addViews + measure cycle
            // when the suggestion list is identical to what's already shown (e.g. an IME
            // re-compose of the same text that re-triggers Path A). Mirrors the
            // contentChanged check in updatePopupContent(), comparing the EXISTING
            // mSuggestionsContent children against mCurrentSuggestions.
            boolean contentChanged = mSuggestionsContent.getChildCount() != mCurrentSuggestions.size();
            if (!contentChanged) {
                for (int i = 0; i < mCurrentSuggestions.size(); i++) {
                    String existing = ((TextView) mSuggestionsContent.getChildAt(i)).getText().toString();
                    if (!existing.equals(mCurrentSuggestions.get(i))) {
                        contentChanged = true;
                        break;
                    }
                }
            }

            if (!contentChanged) {
                // Fast path: identical suggestions — refresh bold spans for the current
                // prefix in-place (no relayout) and reposition. Height is unchanged, so
                // no re-measure needed. Keep version tracking in sync with the rebuild path.
                Logger.logInfo(LOG_TAG, "[autocomplete] popup reuse FAST PATH (bold-spans only)");
                updateBoldSpansOnly(input);
                mLastBuiltHistoryVersion = mMessageHistoryCtrl.getHistoryVersion();
                mLastAppliedPrefix = input;
                applyPopupGeometry(inputField);
                return;
            }

            // Reset scroll to top before rebuilding content in-place, so the
            // user isn't left staring at an empty scroll area after the remove.
            if (mSuggestionsContent.getParent() instanceof android.widget.ScrollView) {
                ((android.widget.ScrollView) mSuggestionsContent.getParent()).setScrollY(0);
            }

            mSuggestionsContent.removeAllViews();
            for (int i = 0; i < mCurrentSuggestions.size(); i++) {
                mSuggestionsContent.addView(
                        buildSuggestionTextView(mCurrentSuggestions.get(i), input),
                        new LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            mSuggestionsContent.measure(
                    View.MeasureSpec.makeMeasureSpec(mLastPopupWidth, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            mLastPopupHeight = mSuggestionsContent.getMeasuredHeight();
            mLastBuiltHistoryVersion = mMessageHistoryCtrl.getHistoryVersion();
            mLastAppliedPrefix = input;
            Logger.logInfo(LOG_TAG, "[autocomplete] popup content replaced in-place (no dismiss/show)");
            applyPopupGeometry(inputField);
            return;
        }

        // Dismiss any previously shown popup WITHOUT clearing mCurrentSuggestions:
        // dismissAutoCompleteSuggestions() also clears that list, and we still need it
        // below to build the suggestion views. Clearing it here would leave the popup
        // empty (completely silent auto-complete).
        if (mSuggestionsPopup != null) {
            try { mSuggestionsPopup.dismiss(); } catch (Exception ignored) {}
            mSuggestionsPopup = null;
        }

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setBackgroundColor(Color.TRANSPARENT);

        int padH = dpToPx(14);
        int padV = dpToPx(10);
        final String input = inputField.getText().toString();

        for (int i = 0; i < mCurrentSuggestions.size(); i++) {
            final String suggestion = mCurrentSuggestions.get(i);
            TextView tv = buildSuggestionTextView(suggestion, input);
            content.addView(tv, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        // Wrap content in a ScrollView and size via WRAP_CONTENT + update() — this mirrors
        // the WORKING mHistoryPopup and avoids the zero-height / degenerate-shadow bug that a
        // non-focusable PopupWindow hits when given an explicit pixel height + setOutsideTouchable(true)
        // + a plain (possibly transparent) ColorDrawable background. On a non-focusable window
        // setOutsideTouchable() is a no-op, and a transparent ColorDrawable makes
        // GradientDrawable.getOutline() bail, collapsing the window to a thin shadow line.
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(content, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        // Clip children to the popup's rounded corners so highlights and
        // separators near the edges don't spill outside the rounded shape.
        // Guard against 0 dims during WRAP_CONTENT resize: if w or h is 0 the
        // outline is left empty (no clipping) instead of clipping everything.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            scroll.setOutlineProvider(new android.view.ViewOutlineProvider() {
                @Override
                public void getOutline(View view, android.graphics.Outline outline) {
                    int w = view.getWidth();
                    int h = view.getHeight();
                    if (w > 0 && h > 0) {
                        outline.setRoundRect(0, 0, w, h, dpToPx(12));
                    }
                }
            });
            scroll.setClipToOutline(true);
        }
        // 10% visual transparency on the content (not the background drawable, so the
        // elevation shadow outline stays valid).
        scroll.setAlpha(0.9f);

        // Position the popup at the input cursor (caret) position
        int sumWidth = Math.max(dpToPx(200), inputField.getWidth() - dpToPx(16));
        content.measure(
                View.MeasureSpec.makeMeasureSpec(sumWidth, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int popupHeight = content.getMeasuredHeight();
        int popupX = calcPopupX(inputField, sumWidth);
        int popupY = calcPopupY(inputField, popupHeight, sumWidth);

        // focusable=false is the key: a non-focusable PopupWindow receives
        // FLAG_NOT_FOCUSABLE + (default) FLAG_ALT_FOCUSABLE_IM, so it stays
        // touchable (user can tap a suggestion) WITHOUT stealing window focus
        // from the EditText. The IME therefore stays up and the text panel
        // stays open. This is the same pattern as mHistoryPopup, which works
        // correctly; the only difference is touchable=true so items are tappable.
        mSuggestionsPopup = new PopupWindow(scroll, sumWidth,
                ViewGroup.LayoutParams.WRAP_CONTENT, false);
        // Smooth elevation shadow — background drawable must be fully opaque for the
        // WindowManager to derive a valid Outline (GradientDrawable.getOutline bails
        // when alpha < 255). The 10% visual transparency is on the ScrollView above.
        mSuggestionsPopup.setElevation(dpToPx(16));
        GradientDrawable popupBgDrawable = new GradientDrawable() {
            @Override
            public void getOutline(@NonNull Outline outline) {
                super.getOutline(outline);
                if (!outline.isEmpty() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    // Keep elevation large, but make the shadow softer/transparent.
                    outline.setAlpha(0.65f);
                }
            }
        };
        popupBgDrawable.setShape(GradientDrawable.RECTANGLE);
        popupBgDrawable.setCornerRadius(dpToPx(12));
        popupBgDrawable.setColor(mColorSchemeManager.getHistoryPopupBg()); // must be opaque for getOutline
        mSuggestionsPopup.setBackgroundDrawable(popupBgDrawable);
        mSuggestionsPopup.setClippingEnabled(true);
        // Touchable so the suggestion items remain clickable; focusable=false so the
        // EditText keeps focus and the IME stays open. No setOutsideTouchable (it is a
        // no-op on a non-focusable window and was the trigger for the zero-height collapse).
        mSuggestionsPopup.setTouchable(true);
        mSuggestionsPopup.setFocusable(false);

        mSuggestionsPopup.setOnDismissListener(() -> mSuggestionsPopup = null);

        try {
            mSuggestionsPopup.showAtLocation(inputField, Gravity.NO_GRAVITY, popupX, popupY);
            // Apply the measured height (WRAP_CONTENT in the ctor would let a long
            // list grow past the screen; update() fixes the real height).
            mSuggestionsPopup.update(popupX, popupY, sumWidth, popupHeight);
            // Track layout changes (IME, scroll, resize) to keep the popup at the caret.
            if (mSuggestionsLayoutListener == null) {
                mSuggestionsLayoutListener = () -> repositionAutoCompletePopup();
                getWindow().getDecorView().getViewTreeObserver().addOnGlobalLayoutListener(mSuggestionsLayoutListener);
            }
            // Save references for in-place updates
            mSuggestionsContent = content;
            mLastPopupWidth = sumWidth;
            mLastPopupHeight = popupHeight;
            mLastPopupX = popupX;
            mLastPopupY = popupY;
            mLastBuiltHistoryVersion = mMessageHistoryCtrl.getHistoryVersion();
            mLastAppliedPrefix = input;
            Logger.logInfo(LOG_TAG, "[autocomplete] popup shown at (" + popupX + "," + popupY
                    + ") w=" + sumWidth + " h=" + popupHeight);
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to show auto-complete popup", e);
            mSuggestionsPopup = null;
        }
    }

    /** Reposition the auto-complete popup at the current caret position. */
    private void repositionAutoCompletePopup() {
        if (mSuggestionsPopup == null || !mSuggestionsPopup.isShowing()) return;
        final EditText inputField = findViewById(R.id.terminal_toolbar_text_input);
        if (inputField == null) return;
        Logger.logInfo(LOG_TAG, "[autocomplete] repositionAutoCompletePopup");
        applyPopupGeometry(inputField);
    }

    /**
     * Unified popup positioning: calculates x/y from caret, calls
     * {@code PopupWindow.update()} only when geometry actually changed.
     * Use from both {@link #updatePopupContent} and {@link #repositionAutoCompletePopup}
     * to avoid redundant IPC / shadow redraw.
     */
    private void applyPopupGeometry(@NonNull EditText inputField) {
        if (mSuggestionsPopup == null || !mSuggestionsPopup.isShowing()) {
            Logger.logInfo(LOG_TAG, "[autocomplete] applyPopupGeometry skip (popup not showing)");
            return;
        }
        android.text.Layout layout = inputField.getLayout();
        if (layout == null) {
            Logger.logInfo(LOG_TAG, "[autocomplete] applyPopupGeometry skip (layout null) — retrying");
            // Layout isn't ready yet (IME animation / initial frame). Retry
            // once after the next layout pass — without this the popup stays
            // pinned to (0,0) forever.
            inputField.post(() -> applyPopupGeometry(inputField));
            return;
        }
        int w = mLastPopupWidth > 0 ? mLastPopupWidth :
                Math.max(dpToPx(200), inputField.getWidth() - dpToPx(16));
        int h = mLastPopupHeight;
        if (w <= 0 || h <= 0) {
            Logger.logInfo(LOG_TAG, "[autocomplete] applyPopupGeometry skip (w=" + w + " h=" + h + ")");
            return;
        }
        int x = calcPopupX(inputField, w);
        int y = calcPopupY(inputField, h, w);
        if (x != mLastPopupX || y != mLastPopupY || h != mLastPopupHeight) {
            Logger.logInfo(LOG_TAG, "[autocomplete] applyPopupGeometry update(" + x + "," + y + " " + w + "x" + h
                    + ") old=(" + mLastPopupX + "," + mLastPopupY + " h=" + mLastPopupHeight + ")");
            mSuggestionsPopup.update(x, y, w, h);
            mLastPopupX = x;
            mLastPopupY = y;
        } else {
            Logger.logInfo(LOG_TAG, "[autocomplete] applyPopupGeometry skip (no change)");
        }
    }

    private void dismissAutoCompleteSuggestions() {
        // Guard against redundant calls (e.g. afterTextChanged firing repeatedly on
        // a clear). When nothing is pending to clean up, skip the diagnostic log and
        // the rest of the teardown so we don't emit repeated noise.
        if (mSuggestionsPopup == null && mCurrentSuggestions.isEmpty()) {
            return;
        }
        Logger.logInfo(LOG_TAG, "[autocomplete] dismiss (popup=" + (mSuggestionsPopup != null)
                + " suggestions=" + mCurrentSuggestions.size() + ")");
        if (mSuggestionsPopup != null) {
            try { mSuggestionsPopup.dismiss(); } catch (Exception ignored) {}
            mSuggestionsPopup = null;
        }
        mCurrentSuggestions.clear();
        mSuggestionsContent = null;
        mLastAppliedPrefix = "";
        mLastPopupX = 0;
        mLastPopupY = 0;
        if (mSuggestionsLayoutListener != null) {
            final android.view.View decor = getWindow().getDecorView();
            if (decor != null) {
                decor.getViewTreeObserver().removeOnGlobalLayoutListener(mSuggestionsLayoutListener);
            }
            mSuggestionsLayoutListener = null;
        }
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

        // Sync per-directory history if the current directory changed since
        // the last swap (e.g. after `cd` or a tab switch where the client
        // callback was missed). Without this, the popup would show the
        // previous directory's history until the user sends a message.
        if (mMessageHistoryCtrl.isPerDirectoryEnabled()) {
            String cwd = getCurrentCwdForHistory();
            if (!cwd.equals(mMessageHistoryCtrl.getHistoryCurrentDirectory())) {
                onHistoryDirectoryChanged();
            }
        }

        // Early-exit: no history and no typed text → show an empty-state hint.
        boolean hasHistory = !mMessageHistoryCtrl.getHistoryList().isEmpty();
        String currInputText = "";
        EditText inputFieldRO = findViewById(R.id.terminal_toolbar_text_input);
        if (inputFieldRO != null) {
            CharSequence cs = inputFieldRO.getText();
            if (cs != null) currInputText = cs.toString();
        }
        boolean hasInput = !TextUtils.isEmpty(currInputText);
        if (!hasHistory && !hasInput) {
            // Show a one-shot Toast for the empty state. A guard flag prevents
            // re-triggering on every ACTION_MOVE pixel while the finger drags.
            if (!mHistoryEmptyHintShown) {
                mHistoryEmptyHintShown = true;
                Toast bottomToast = Toast.makeText(this, getString(R.string.message_history_empty), Toast.LENGTH_SHORT);
                bottomToast.setGravity(Gravity.BOTTOM, 0, dpToPx(48));
                bottomToast.show();
            }
            return;
        }

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setBackgroundColor(Color.TRANSPARENT);

        int padH = dpToPx(14);
        int padV = dpToPx(10);

        // Synthetic "CLEAR HISTORY…" row pinned at the TOP of the popup.
        // Selecting it opens a confirmation dialog; confirming wipes all history.
        // Shown only when there is history to clear. Coexists with the bottom
        // "Clear" row (clears the input), it is not a replacement for it.
        if (!mMessageHistoryCtrl.getHistoryList().isEmpty()) {
            TextView tv = new TextView(this);
            tv.setText(getString(R.string.message_history_clear_all));
            tv.setGravity(Gravity.CENTER);
            tv.setAllCaps(true);
            tv.setTextColor(mColorSchemeManager.getHistoryTextColor());
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            tv.setTypeface(tv.getTypeface(), android.graphics.Typeface.BOLD);
            tv.setPadding(padH, padV, padH, padV);
            tv.setClickable(true);
            tv.setTag(MESSAGE_HISTORY_CLEAR_ALL_TAG);
            content.addView(tv, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            mHistoryItemViews.add(tv);

            // Thin separator below the clear all row to visually group it.
            View sep = new View(this);
            sep.setBackgroundColor(mColorSchemeManager.getHistoryPopupSepColor());
            content.addView(sep, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dpToPx(1)));
        }
        // Displayed order (ТЗ): newest at the BOTTOM (nearest the pencil button,
        // first reached by a swipe-up), oldest at the top. A re-sent message moves
        // to index 0 (front) of mMessageHistoryCtrl.getHistoryList(), so iterate in REVERSE (end -> 0)
        // to fill the vertical layout top-to-bottom with the newest last (bottom).
        for (int i = mMessageHistoryCtrl.getHistoryList().size() - 1; i >= 0; i--) {
            final String message = mMessageHistoryCtrl.getHistoryList().get(i);
            TextView tv = new TextView(this);
            // Preview: collapse newlines to spaces, wrap to at most 2 lines and add
            // an ellipsis when the message is longer than that.
            tv.setText(message.replace("\n", " ").trim());
            tv.setMaxLines(2);
            tv.setEllipsize(TextUtils.TruncateAt.END);
            tv.setTextColor(mColorSchemeManager.getHistoryTextColor());
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
            tv.setText(getString(R.string.message_history_clear));
            tv.setGravity(Gravity.CENTER);
            tv.setAllCaps(true);
            tv.setTextColor(mColorSchemeManager.getHistoryTextColor());
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
            if (!mMessageHistoryCtrl.getHistoryList().isEmpty()) {
                View sepBottom = new View(this);
                sepBottom.setBackgroundColor(mColorSchemeManager.getHistoryPopupSepColor());
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
        // Clip children to the popup's rounded corners so highlights and
        // separators near the edges don't spill outside the rounded shape.
        // Guard against 0 dims during WRAP_CONTENT resize: if w or h is 0
        // the outline is left empty (no clipping) instead of clipping to nothing.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            scroll.setOutlineProvider(new android.view.ViewOutlineProvider() {
                @Override
                public void getOutline(View view, android.graphics.Outline outline) {
                    int w = view.getWidth();
                    int h = view.getHeight();
                    if (w > 0 && h > 0) {
                        outline.setRoundRect(0, 0, w, h, dpToPx(12));
                    }
                }
            });
            scroll.setClipToOutline(true);
        }

        mHistoryPopup = new PopupWindow(scroll, popupWidth,
                ViewGroup.LayoutParams.WRAP_CONTENT, false);
        // Smooth elevation shadow — background drawable must be fully opaque for the
        // WindowManager to derive a valid Outline (GradientDrawable.getOutline bails
        // when alpha < 255).  The 10% visual transparency is applied to the ScrollView
        // itself via setAlpha(), which does not affect the popup's background outline.
        // Larger elevation (16dp) for a bigger shadow, but outline alpha is
        // reduced so the shadow renders more transparent/softer.
        mHistoryPopup.setElevation(dpToPx(16));
        // Background: rounded rect, fully opaque scheme composite colour.
        // getOutline() is overridden to call outline.setAlpha() — this controls
        // the shadow opacity independently from the elevation size.
        GradientDrawable popupBgDrawable = new GradientDrawable() {
            @Override
            public void getOutline(@NonNull Outline outline) {
                super.getOutline(outline);
                if (!outline.isEmpty()) {
                    // Keep elevation large, but make the shadow softer/transparent.
                    // setAlpha requires API 31+.
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        outline.setAlpha(0.65f);
                    }
                }
            }
        };
        popupBgDrawable.setShape(GradientDrawable.RECTANGLE);
        popupBgDrawable.setCornerRadius(dpToPx(12));
        popupBgDrawable.setColor(mColorSchemeManager.getHistoryPopupBg()); // must be opaque for getOutline
        mHistoryPopup.setBackgroundDrawable(popupBgDrawable);
        // 10% visual transparency on the content (not the background drawable, so the
        // elevation shadow outline stays valid).
        scroll.setAlpha(0.9f);
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
        // jump straight to the bottom — fullScroll(FOCUS_DOWN) animates, which looks
        // like the list is scrolling past entries as the popup appears.
        final android.widget.ScrollView scrollRef1 = scroll;
        scrollRef1.post(() -> {
            View child = scrollRef1.getChildAt(0);
            if (child != null) scrollRef1.scrollTo(0, child.getHeight());
        });
    }

    /**
     * Whether the popup has anything worth showing: either there is saved
     * history, or the input panel currently holds some (unsent) text.
     */
    private boolean shouldShowHistoryPopup() {
        return true; // always show – either history, or the "empty" hint
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
                tv.setBackgroundColor(mColorSchemeManager.getHistoryHighlightFill());
            } else {
                tv.setBackgroundColor(Color.TRANSPARENT);
            }
            tv.setTextColor(mColorSchemeManager.getHistoryTextColor());   // default colour in both states
        }
    }

    /**
     * Continuously scroll the popup's ScrollView while the finger rests/drags
     * within an edge band at the top or bottom (like the keyboard-accent popup:
     * it keeps moving even without finger motion). Driven by a self-rescheduling
     * postDelayed loop that keeps running as long as the finger stays in the band
     * and the popup is open, so edge auto-scroll continues even when the finger
     * stops moving.
     *
     * NOTE: the loop reschedules ITSELF on every tick (not just when first
     * started) — otherwise it would stop after a single step because the
     * mHistoryAutoScrolling flag is already true on the next tick.
     */
    private void autoScrollHistoryNearEdge() {
        if (mHistoryScroll == null || !isHistoryPopupShowing()) {
            mHistoryAutoScrolling = false;
            return;
        }
        int[] loc = new int[2];
        mHistoryScroll.getLocationOnScreen(loc);
        int top = loc[1];
        int bottom = loc[1] + mHistoryScroll.getHeight();
        int band = dpToPx(36);      // edge-sensitive zone
        int maxStep = dpToPx(3);    // max px scrolled per tick (smooth, not too fast)

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

        // Keep the loop alive while the finger is still in the edge band.
        mHistoryAutoScrolling = true;
        mHistoryScroll.postDelayed(this::autoScrollHistoryNearEdge, 16);
    }

    /**
     * Ask the user to confirm wiping the message history. In per-directory mode
     * the dialog has three buttons: OK (current directory only), All (all
     * directories), Cancel. In global mode it stays as OK + Cancel.
     */
    private void confirmClearAllHistory() {
        final MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this, R.style.ThemeOverlay_TermuxActivity_Dialog)
                .setTitle(getString(R.string.message_history_clear_question))
                .setNegativeButton(android.R.string.cancel, null);

        if (mMessageHistoryCtrl.isPerDirectoryEnabled()) {
            builder.setMessage(getString(R.string.message_history_clear_current_only_question))
                    .setPositiveButton(getString(R.string.message_history_clear_ok), (d, w) -> clearAllHistory())
                    .setNeutralButton(getString(R.string.message_history_clear_all_btn), (d, w) -> clearAllDirectoriesHistory());
        } else {
            builder.setMessage(getString(R.string.message_history_clear_all_question))
                    .setPositiveButton(android.R.string.ok, (d, w) -> clearAllHistory());
        }

        builder.show();
    }

    /** Wipe message history for ALL directories (per-directory mode only). */
    private void clearAllDirectoriesHistory() {
        mMessageHistoryCtrl.clearAllPerDirectory();
    }

    /** Wipe the message history for the current context (global or current directory). */
    private void clearAllHistory() {
        mMessageHistoryCtrl.clearCurrent(getCurrentCwdForHistory());
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
        final MaterialAlertDialogBuilder b = new MaterialAlertDialogBuilder(this, R.style.ThemeOverlay_TermuxActivity_Dialog);
        b.setIcon(android.R.drawable.ic_dialog_alert);
        b.setTitle(getString(R.string.message_history_clear_dialog_title));
        String msg = mMessageHistoryCtrl.isPerDirectoryEnabled()
                ? getString(R.string.message_history_clear_confirm_current)
                : getString(R.string.message_history_clear_confirm_all);
        b.setMessage(msg);
        b.setPositiveButton(android.R.string.yes, (dialog, id) -> {
            dialog.dismiss();
            clearAllHistory();
            showToast(getString(R.string.message_history_cleared), true);
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

        // A history pick replaces the ENTIRE field, so force a full rescan
        // (Path A) instead of the additive-keystroke heuristic. Without this,
        // when the field was empty the TextWatcher sees an "appended" change
        // (before==0) and wrongly takes Path B, which dismisses the popup
        // instead of showing suggestions for the restored message.
        mLastBuiltHistoryVersion = -1; // force mismatch → Path A on next update
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
        return mDirectoryHistoryCtrl.recordCurrentDirectory(getCurrentSession());
    }

    /** Load the persisted directory history from preferences (JSON array). */
    private void loadDirectoryHistory() {
        mDirectoryHistoryCtrl.load();
    }

    /** Persist the current directory history to preferences as a JSON array. */
    private void saveDirectoryHistory() {
        mDirectoryHistoryCtrl.save();
    }

    private int dpToPx(int dp) {
        return TermuxActivityUtils.dpToPx(this, dp);
    }

    /**
     * Alpha-composite {@code overlay} (with alpha) on top of {@code background}
     * (assumed opaque) using standard over operator.
     * @return Fully opaque ARGB colour.
     */
    private static int compositeColors(int background, int overlay) {
        return TermuxColorSchemeManager.compositeColors(background, overlay);
    }

    /**
     * (Re)compute ALL UI colours derived from the terminal colour scheme and panel transparency
     * prefs — panel buttons, text selection highlight, context-popup backgrounds and separators.
     * Must be called whenever the scheme or the inactive-alpha slider changes so that every styled
     * element uses fresh colours without recomputing them on every draw / event.
     * <p>
     * Both light and dark scheme variants are covered: when the scheme switches, this runs again
     * and overwrites the cached fields with the new values.
     */
    public void recomputeUIColors() {
        mColorSchemeManager.recompute(getPreferences());
    }

    /** @return Cached panel/button background colour. */
    public int getButtonBg() { return mColorSchemeManager.getButtonBg(); }
    /** @return Cached panel/button active background colour. */
    public int getButtonActiveBg() { return mColorSchemeManager.getButtonActiveBg(); }
    /** @return Cached panel/button text (scheme foreground) colour. */
    public int getButtonText() { return mColorSchemeManager.getButtonText(); }
    /** @return Cached text selection highlight colour. */
    public int getTextSelectionHighlightColor() { return mColorSchemeManager.getTextSelectionHighlightColor(); }
    /** @return Whether the current scheme is perceived as light. */
    public boolean isCachedSchemeLight() { return mColorSchemeManager.isSchemeLight(); }

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
        TermuxActivityUtils.finishActivityIfNotFinishing(this);
    }

    public void finishActivityIfNotFinishing() {
        TermuxActivityUtils.finishActivityIfNotFinishing(this);
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

        TerminalView terminalView = getTerminalView();
        if (terminalView == null) return;

        boolean autoFillEnabled = terminalView.isAutoFillEnabled();

        menu.add(Menu.NONE, CONTEXT_MENU_SELECT_URL_ID, Menu.NONE, R.string.action_select_url);
        menu.add(Menu.NONE, CONTEXT_MENU_SHARE_TRANSCRIPT_ID, Menu.NONE, R.string.action_share_transcript);
        if (!DataUtils.isNullOrEmpty(terminalView.getStoredSelectedText()))
            menu.add(Menu.NONE, CONTEXT_MENU_SHARE_SELECTED_TEXT, Menu.NONE, R.string.action_share_selected_text);
        if (autoFillEnabled)
            menu.add(Menu.NONE, CONTEXT_MENU_AUTOFILL_USERNAME, Menu.NONE, R.string.action_autofill_username);
        if (autoFillEnabled)
            menu.add(Menu.NONE, CONTEXT_MENU_AUTOFILL_PASSWORD, Menu.NONE, R.string.action_autofill_password);
        menu.add(Menu.NONE, CONTEXT_MENU_RESET_TERMINAL_ID, Menu.NONE, R.string.action_reset_terminal);
        menu.add(Menu.NONE, CONTEXT_MENU_KILL_PROCESS_ID, Menu.NONE, getResources().getString(R.string.action_kill_process, getCurrentSession().getPid())).setEnabled(currentSession.isRunning());
        menu.add(Menu.NONE, CONTEXT_MENU_STYLING_ID, Menu.NONE, R.string.action_style_terminal);
        menu.add(Menu.NONE, CONTEXT_MENU_FONT_ID, Menu.NONE, R.string.action_font_terminal);
        menu.add(Menu.NONE, CONTEXT_MENU_TOGGLE_KEEP_SCREEN_ON, Menu.NONE, R.string.action_toggle_keep_screen_on).setCheckable(true).setChecked(mPreferences.shouldKeepScreenOn());
        menu.add(Menu.NONE, CONTEXT_MENU_HELP_ID, Menu.NONE, R.string.action_open_help);
        menu.add(Menu.NONE, CONTEXT_MENU_SETTINGS_ID, Menu.NONE, R.string.action_open_settings);
        menu.add(Menu.NONE, CONTEXT_MENU_REPORT_ID, Menu.NONE, R.string.action_report_issue);
    }

    /** Hook system menu to show context menu instead. */
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        TerminalView tv = getTerminalView();
        if (tv != null)
            tv.showContextMenu();
        return false;
    }

    @Override
    public boolean onContextItemSelected(MenuItem item) {
        TerminalSession session = getCurrentSession();
        TerminalView terminalView = getTerminalView();

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
                if (terminalView != null) terminalView.requestAutoFillUsername();
                return true;
            case CONTEXT_MENU_AUTOFILL_PASSWORD:
                if (terminalView != null) terminalView.requestAutoFillPassword();
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
            case CONTEXT_MENU_FONT_ID:
                showFontPicker();
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
        TerminalView terminalView = getTerminalView();
        if (terminalView != null) terminalView.onContextMenuClosed(menu);
    }

    private void showKillSessionDialog(TerminalSession session) {
        if (session == null) return;

        final MaterialAlertDialogBuilder b = new MaterialAlertDialogBuilder(this, R.style.ThemeOverlay_TermuxActivity_Dialog);
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
            () -> TermuxActivityUtils.updateTermuxActivityStyling(this, false));
    }

    /**
     * Show the Termux:Style font picker dialog. Lists every font shipped by the installed
     * Termux:Style plugin and applies the selected one live without an activity restart.
     * If Termux:Style is not installed, shows a "not installed" message.
     */
    private void showFontPicker() {
        FontUtils.showFontDialog(this, getString(R.string.error_styling_not_installed),
            () -> {
                TermuxActivityUtils.updateTermuxActivityStyling(this, false);
                showToast(getResources().getString(R.string.msg_terminal_font_applied), true);
            });
    }
    private void toggleKeepScreenOn() {
        TerminalView terminalView = getTerminalView();
        if (terminalView == null) return;
        if (terminalView.getKeepScreenOn()) {
            terminalView.setKeepScreenOn(false);
            mPreferences.setKeepScreenOn(false);
        } else {
            terminalView.setKeepScreenOn(true);
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


    /**
     * Sync the pager adapter and tab strip with the live session list.
     * No-arg overload — delegates to the indexed version with -1.
     */
    public void termuxSessionListNotifyUpdated() {
        termuxSessionListNotifyUpdated(-1);
    }

    /**
     * Sync the pager adapter and tab strip with the live session list.
     *
     * @param preferredIndex When a tab has just been removed, the position of the removed tab in
     *                       the OLD list; the method selects the session that shifted into this
     *                       slot (the RIGHT neighbor).  Pass -1 for non-removal updates, which
     *                       falls back to restoring the current session's position.
     */
    public void termuxSessionListNotifyUpdated(int preferredIndex) {
        // The horizontal pager sync (adapter rebuild + page re-selection + per-session bookkeeping)
        // now lives in SessionPagerManager. It re-points mTerminalView to the correct page; we then
        // refresh the tab strip and snapshot below.
        //
        // NOTE: pager sync is done BEFORE updateTabs() so that onTerminalPageSelected — which fires
        // during the sync — re-points mTerminalView to the correct page. If updateTabs() ran first,
        // getCurrentSession() would still return the closed session and no tab would be highlighted.
        if (mSessionPagerManager != null)
            mSessionPagerManager.termuxSessionListNotifyUpdated(preferredIndex);

        // Update the tab strip AFTER the pager sync, so getCurrentSession() (which reads
        // mTerminalView — set inside onTerminalPageSelected above) returns the correct session
        // and the selected tab is properly highlighted.
        if (mTermuxSessionTabsController != null && mServiceConnectionManager.getTermuxService() != null) {
            mTermuxSessionTabsController.updateTabs(mServiceConnectionManager.getTermuxService().getTermuxSessions());
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
        return mServiceConnectionManager != null ? mServiceConnectionManager.getTermuxService() : null;
    }

    /** Restore open sessions from snapshot, if enabled. */
    public boolean restoreSessionSnapshot() {
        return mSessionSnapshotManager.restoreSessionSnapshot();
    }

    /** Per-session text input state (content, visibility, focus, caret). */
    @NonNull
    public TextInputSessionStateManager getTextInputState() {
        return mTextInputState;
    }

    public TerminalView getTerminalView() {
        return mTerminalView;
    }

    public void setTerminalView(@Nullable TerminalView view) {
        mTerminalView = view;
    }

    /**
     * Resolve the {@link TerminalView} of the currently active pager page, resolving it live rather
     * than from the cached {@link #mTerminalView}. Returns {@link #getTerminalView()} when that is
     * already set, otherwise falls back to the pager's selected page so callers that run in a window
     * where {@code mTerminalView} has not yet been refreshed (e.g. extra-key input right after a
     * session is added to an empty pager, where {@code onPageSelected} is not re-fired for page 0)
     * still reach the correct, bound view instead of getting {@code null} and dropping the action.
     */
    @Nullable
    public TerminalView getActiveTerminalView() {
        if (mSessionPagerManager != null) return mSessionPagerManager.getActiveTerminalView();
        if (mTerminalView != null) return mTerminalView;
        return null;
    }

    public androidx.viewpager2.widget.ViewPager2 getTerminalPager() {
        return mSessionPagerManager != null ? mSessionPagerManager.getTerminalPager() : null;
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

    /** @return The {@link TermuxSessionSnapshotManager} owning session snapshot/restore. */
    public TermuxSessionSnapshotManager getSessionSnapshotManager() {
        return mSessionSnapshotManager;
    }

    /** Persist the open tabs snapshot; delegates to {@link TermuxSessionSnapshotManager}. */
    public void saveSessionSnapshot() {
        mSessionSnapshotManager.saveSessionSnapshot();
    }


    /**
     * Set the shared slot below the tabs: text input container vs extra keys.
     * Showing the text input panel hides the extra keys and vice versa.
     * When the text input panel is hidden, the extra keys visibility also
     * respects the {@code hide_extra_keys_with_keyboard} preference.
     */
    private void setTextInputSlotVisible(boolean visible) {
        View container = findViewById(R.id.terminal_toolbar_text_input_container);
        if (container != null)
            container.setVisibility(visible ? View.VISIBLE : View.GONE);
        ExtraKeysView ekv = getExtraKeysView();
        if (ekv == null) return;
        if (visible) {
            ekv.setVisibility(View.GONE);
        } else if (mPreferences.shouldHideExtraKeysWithKeyboard()
                   && !mSoftKeyboardVisible) {
            ekv.setVisibility(View.GONE);
        } else {
            ekv.setVisibility(View.VISIBLE);
        }
    }

    /**
     * Set visibility of the text input panel.
     * @param visible true to show, false to hide
     */
    public void setTextInputVisible(boolean visible) {
        // Dismiss auto-complete suggestions when hiding the panel
        if (!visible) dismissAutoCompleteSuggestions();

        View textInputContainer = findViewById(R.id.terminal_toolbar_text_input_container);
        if (textInputContainer != null) {
            // The text input panel and the extra keys share one slot below the tabs:
            // showing one hides the other.
            setTextInputSlotVisible(visible);
            // Save state to preferences
            getSharedPreferences("termux_prefs", MODE_PRIVATE).edit().putBoolean(PREF_TEXT_INPUT_VISIBLE, visible).apply();

            // Track per-session panel visibility so each tab remembers its own state.
            final TerminalSession session = getCurrentSession();
            if (session != null) {
                mTextInputState.setVisible(session.mHandle, visible);
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
        if (session != null && mTextInputState.hasVisible(session.mHandle)) {
            return mTextInputState.isVisible(session.mHandle);
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
        boolean hasRecorded = session != null && mTextInputState.hasVisible(session.mHandle);
        boolean visible = enabled && (hasRecorded
                ? mTextInputState.isVisible(session.mHandle)
                : isTextInputVisible());

        setTextInputSlotVisible(visible);

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
                // Panel hidden, or focus was on the terminal: focus the terminal — UNLESS the
                // text input EditText still holds focus. Stealing focus here is what makes a later
                // long-press on the input panel bubble its context menu up to the terminal menu
                // instead of selecting a word (long-press regression on the input panel). The
                // EditText is a sibling of the pager (not inside a page), so it must keep focus
                // across page switches when the user was typing into it.
                final EditText currentInput = findViewById(R.id.terminal_toolbar_text_input);
                if (currentInput == null || !currentInput.hasFocus()) {
                    if (mTermuxTerminalViewClient != null)
                        mTermuxTerminalViewClient.ignoreOnceSoftKeyboardOnFocus();
                    if (mTerminalView != null) {
                        mTerminalView.requestFocus();
                    }
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
        TermuxActivityUtils.updateTermuxActivityStyling(context, recreateActivity);
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

        // Cache all UI colours from the (now-updated) COLOR_SCHEME so every consumer reads
        // fresh values without computing them on the fly.
        mColorSchemeManager.recompute(getPreferences());

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
        TermuxActivityUtils.startTermuxActivity(context);
    }

    public static Intent newInstance(@NonNull final Context context) {
        return TermuxActivityUtils.newInstance(context);
    }

    /** Start TermuxActivity and close all existing terminal sessions, opening a fresh one.
     * Used after a data restore so the user does not keep stale sessions. */
    public static void startTermuxActivityWithSessionReset(@NonNull final Context context) {
        TermuxActivityUtils.startTermuxActivityWithSessionReset(context);
    }

}

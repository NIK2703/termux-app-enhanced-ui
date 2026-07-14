package com.termux.app.terminal;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.media.AudioAttributes;
import android.media.SoundPool;
import android.os.Build;
import android.text.TextUtils;
import android.view.View;
import android.view.Window;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.R;
import com.termux.shared.interact.ShareUtils;
import com.termux.shared.termux.shell.command.runner.terminal.TermuxSession;
import com.termux.shared.termux.interact.TextInputDialogUtils;
import com.termux.app.TermuxActivity;
import com.termux.shared.termux.settings.preferences.TermuxAppSharedPreferences;
import com.termux.shared.termux.terminal.TermuxTerminalSessionClientBase;
import com.termux.shared.termux.TermuxConstants;
import com.termux.app.TermuxService;
import com.termux.shared.termux.settings.properties.TermuxPropertyConstants;
import com.termux.shared.theme.ThemeUtils;
import com.termux.shared.theme.NightMode;
import com.termux.view.TerminalView;
import com.termux.shared.termux.terminal.io.BellHandler;
import com.termux.shared.logger.Logger;
import com.termux.terminal.TerminalColors;
import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalSession;
import com.termux.terminal.TerminalSessionClient;
import com.termux.terminal.TextStyle;
import com.termux.shared.termux.extrakeys.ColorSchemeUtils;
import com.termux.shared.termux.extrakeys.ExtraKeysView;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.Properties;

/** The {@link TerminalSessionClient} implementation that may require an {@link Activity} for its interface methods. */
public class TermuxTerminalSessionActivityClient extends TermuxTerminalSessionClientBase {

    private final TermuxActivity mActivity;

    private static final int MAX_SESSIONS = 8;

    private SoundPool mBellSoundPool;

    private int mBellSoundId;

    private static final String LOG_TAG = "TermuxTerminalSessionActivityClient";

    public TermuxTerminalSessionActivityClient(TermuxActivity activity) {
        this.mActivity = activity;
    }

    /**
     * Should be called when mActivity.onCreate() is called
     */
    public void onCreate() {
        // Set terminal fonts and colors
        checkForFontAndColors();
    }

    /**
     * Should be called when mActivity.onStart() is called
     */
    public void onStart() {
        // The service has connected, but data may have changed since we were last in the foreground.
        // Get the session stored in shared preferences stored by {@link #onStop} if its valid,
        // otherwise get the last session currently running.
        if (mActivity.getTermuxService() != null) {
            setCurrentSession(getCurrentStoredSessionOrLast());
            termuxSessionListNotifyUpdated();
        }

        // Re-apply the terminal color scheme now that the session is (re)attached, but ONLY when
        // the activity was recreated (e.g. System Light<->Dark switch). On a recreate, the
        // mColors.reset() inside applyTerminalColorScheme() is skipped during onCreate() because
        // the session is still null, and the session is only (re)attached here -- so without this
        // the persisted terminal keeps its stale palette and only the panel/status-bar repaint.
        // We deliberately guard with isActivityRecreated() so a normal foreground-from-background
        // does NOT reset mColors, which would otherwise wipe shell-set OSC dynamic colors.
        if (mActivity.isActivityRecreated())
            checkForFontAndColors();

        // The current terminal session may have changed while being away, force
        // a refresh of the displayed terminal.
        TerminalView tv = mActivity.getTerminalView();
        if (tv != null) tv.onScreenUpdated();
    }

    /**
     * Should be called when mActivity.onResume() is called
     */
    public void onResume() {
        // Just initialize the mBellSoundPool and load the sound, otherwise bell might not run
        // the first time bell key is pressed and play() is called, since sound may not be loaded
        // quickly enough before the call to play(). https://stackoverflow.com/questions/35435625
        loadBellSoundPool();
    }

    /**
     * Should be called when mActivity.onStop() is called
     */
    public void onStop() {
        // Store current session in shared preferences so that it can be restored later in
        // {@link #onStart} if needed.
        setCurrentStoredSession();

        // Release mBellSoundPool resources, specially to prevent exceptions like the following to be thrown
        // java.util.concurrent.TimeoutException: android.media.SoundPool.finalize() timed out after 10 seconds
        // Bell is not played in background anyways
        // Related: https://stackoverflow.com/a/28708351/14686958
        releaseBellSoundPool();
    }

    /**
     * Should be called when mActivity.reloadActivityStyling() is called
     */
    public void onReloadActivityStyling() {
        // Set terminal fonts and colors
        checkForFontAndColors();
    }



    @Override
    public void onTextChanged(@NonNull TerminalSession changedSession) {
        if (!mActivity.isVisible()) return;

        if (mActivity.getCurrentSession() == changedSession) {
            TerminalView tv = mActivity.getTerminalView();
            if (tv != null) tv.onScreenUpdated();
        }
    }

    @Override
    public void onTitleChanged(@NonNull TerminalSession updatedSession) {
        if (!mActivity.isVisible()) return;

        // Toast suppressed — the user requested no popups on session events.

        termuxSessionListNotifyUpdated();
    }

    @Override
    public void onSessionFinished(@NonNull TerminalSession finishedSession) {
        TermuxService service = mActivity.getTermuxService();

        if (service == null || service.wantsToStop()) {
            // The service wants to stop as soon as possible.
            mActivity.finishActivityIfNotFinishing();
            return;
        }

        int index = service.getIndexOfSession(finishedSession);

        // For plugin commands that expect the result back, we should immediately close the session
        // and send the result back instead of waiting fo the user to press enter.
        // The plugin can handle/show errors itself.
        boolean isPluginExecutionCommandWithPendingResult = false;
        TermuxSession termuxSession = service.getTermuxSession(index);
        if (termuxSession != null) {
            isPluginExecutionCommandWithPendingResult = termuxSession.getExecutionCommand().isPluginExecutionCommandWithPendingResult();
            if (isPluginExecutionCommandWithPendingResult)
                Logger.logVerbose(LOG_TAG, "The \"" + finishedSession.mSessionName + "\" session will be force finished automatically since result in pending.");
        }

        if (mActivity.getPackageManager().hasSystemFeature(PackageManager.FEATURE_LEANBACK)) {
            // On Android TV devices we need to use older behaviour because we may
            // not be able to have multiple launcher icons.
            if (service.getTermuxSessionsSize() > 1 || isPluginExecutionCommandWithPendingResult) {
                removeFinishedSession(finishedSession);
            }
        } else {
            // Once we have a separate launcher icon for the failsafe session, it
            // should be safe to auto-close session on exit code '0' or '130'.
            // Also auto-close if killed by signal (negative exit status like -9 for SIGKILL)
            int exitStatus = finishedSession.getExitStatus();
            if (exitStatus == 0 || exitStatus == 130 || exitStatus < 0 || isPluginExecutionCommandWithPendingResult) {
                removeFinishedSession(finishedSession);
            }
        }
    }

    @Override
    public void onCopyTextToClipboard(@NonNull TerminalSession session, String text) {
        if (!mActivity.isVisible()) return;

        ShareUtils.copyTextToClipboard(mActivity, text);
    }

    @Override
    public void onPasteTextFromClipboard(@Nullable TerminalSession session) {
        if (!mActivity.isVisible()) return;

        String text = ShareUtils.getTextStringFromClipboardIfSet(mActivity, true);
        if (text != null) {
            TerminalView tv = mActivity.getTerminalView();
            if (tv != null && tv.mEmulator != null) tv.mEmulator.paste(text);
        }
    }

    @Override
    public void onBell(@NonNull TerminalSession session) {
        if (!mActivity.isVisible()) return;

        switch (mActivity.getProperties().getBellBehaviour()) {
            case TermuxPropertyConstants.IVALUE_BELL_BEHAVIOUR_VIBRATE:
                BellHandler.getInstance(mActivity).doBell();
                break;
            case TermuxPropertyConstants.IVALUE_BELL_BEHAVIOUR_BEEP:
                loadBellSoundPool();
                if (mBellSoundPool != null)
                    mBellSoundPool.play(mBellSoundId, 1.f, 1.f, 1, 0, 1.f);
                break;
            case TermuxPropertyConstants.IVALUE_BELL_BEHAVIOUR_IGNORE:
                // Ignore the bell character.
                break;
        }
    }

    @Override
    public void onColorsChanged(@NonNull TerminalSession changedSession) {
        if (mActivity.getCurrentSession() == changedSession)
            updateBackgroundColor();
    }

    @Override
    public void onTerminalCursorStateChange(boolean enabled) {
        // Do not start cursor blinking thread if activity is not visible
        if (enabled && !mActivity.isVisible()) {
            Logger.logVerbose(LOG_TAG, "Ignoring call to start cursor blinking since activity is not visible");
            return;
        }

        // If cursor is to enabled now, then start cursor blinking if blinking is enabled
        // otherwise stop cursor blinking
        TerminalView terminalView = mActivity.getTerminalView();
        if (terminalView != null) terminalView.setTerminalCursorBlinkerState(enabled, false);
    }

    @Override
    public void setTerminalShellPid(@NonNull TerminalSession terminalSession, int pid) {
        TermuxService service = mActivity.getTermuxService();
        if (service == null) return;
        
        TermuxSession termuxSession = service.getTermuxSessionForTerminalSession(terminalSession);
        if (termuxSession != null)
            termuxSession.getExecutionCommand().mPid = pid;
    }


    /**
     * Should be called when mActivity.onResetTerminalSession() is called
     */
    public void onResetTerminalSession() {
        // Ensure blinker starts again after reset if cursor blinking was disabled before reset like
        // with "tput civis" which would have called onTerminalCursorStateChange()
        TerminalView terminalView = mActivity.getTerminalView();
        if (terminalView != null) terminalView.setTerminalCursorBlinkerState(true, true);
    }



    @Override
    public Integer getTerminalCursorStyle() {
        return mActivity.getProperties().getTerminalCursorStyle();
    }



    /** Load mBellSoundPool */
    private synchronized void loadBellSoundPool() {
        if (mBellSoundPool == null) {
            mBellSoundPool = new SoundPool.Builder().setMaxStreams(1).setAudioAttributes(
                new AudioAttributes.Builder().setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).build()).build();

            try {
                mBellSoundId = mBellSoundPool.load(mActivity, com.termux.shared.R.raw.bell, 1);
            } catch (Exception e){
                // Catch java.lang.RuntimeException: Unable to resume activity {com.termux/com.termux.app.TermuxActivity}: android.content.res.Resources$NotFoundException: File res/raw/bell.ogg from drawable resource ID
                Logger.logStackTraceWithMessage(LOG_TAG, "Failed to load bell sound pool", e);
            }
        }
    }

    /** Release mBellSoundPool resources */
    private synchronized void releaseBellSoundPool() {
        if (mBellSoundPool != null) {
            mBellSoundPool.release();
            mBellSoundPool = null;
        }
    }



    /** Try switching to session. */
    public void setCurrentSession(TerminalSession session) {
        setCurrentSession(session, true);
    }

    /**
     * Switch to the given session. In the horizontal-pager model each session has its own
     * TerminalView (bound by {@link TerminalPagerAdapter}), so switching means scrolling the
     * pager to that page. The {@code onPageSelected} callback then re-points the activity's active
     * {@code mTerminalView} and runs the per-session bookkeeping via {@link #onSessionPageSelected}.
     */
    public void setCurrentSession(TerminalSession session, boolean showToast) {
        if (session == null) return;

        // Preserve the text input content of the session we are leaving.
        mActivity.saveTextInputForCurrentSession();

        // If the pager has not been populated yet (e.g. onStart restored the stored session before
        // onServiceConnected filled the adapter), remember it and bail; syncTerminalPagerToService()
        // will select it once sessions exist.
        androidx.viewpager2.widget.ViewPager2 pager = mActivity.getTerminalPager();
        if (pager == null || pager.getAdapter() == null || pager.getAdapter().getItemCount() == 0) {
            mActivity.setPendingInitialSession(session);
            if (showToast) notifyOfSessionChange();
            return;
        }

        TermuxService service = mActivity.getTermuxService();
        if (service == null) return;
        int index = service.getIndexOfSession(session);
        if (index < 0) return;

        // If the target page is already the current page, pager.setCurrentItem() is a no-op
        // (the pager silently skips the scroll and fires NO callbacks).  This leaves
        // switchInProgress stuck at true and onSessionPageSelected() uninvoked, which means
        // tab highlight, text-input restore and directory recording are skipped for what
        // should be a perfectly valid session-switch request (e.g. clicking the already-visible
        // tab). Handle it inline so bookkeeping runs and the flag is cleared.
        if (index == pager.getCurrentItem()) {
            onSessionPageSelected(session);
            mActivity.setTerminalPageSwitchInProgress(false);
            if (showToast) notifyOfSessionChange();
            return;
        }

        if (pager != null) {
            // Mark a page switch in progress BEFORE the smooth scroll so the per-page focus
            // listener does not pop the IME while the old page loses focus mid-animation
            // (scenario #InputPanel6: tab click -> setCurrentItem(true)). onTerminalPageSelected()
            // clears this flag and becomes the single authority that re-asserts the keyboard.
            mActivity.setTerminalPageSwitchInProgress(true);
            // Smoothly scroll the pager to the target page. onPageSelected() will fire and run
            // onSessionPageSelected(), which performs the text-input restore, tab highlight,
            // background colour and directory-history update for the newly-visible session.
            pager.setCurrentItem(index, true);
        }

        if (showToast) {
            notifyOfSessionChange();
        }
    }

    /**
     * Per-session bookkeeping for the page the user just landed on (swipe, tab click or keyboard
     * switch). Kept separate from {@link #setCurrentSession} so a swipe does not re-trigger a pager
     * scroll / toast loop. The page's TerminalView is already bound to the session by the adapter.
     */
    public void onSessionPageSelected(TerminalSession session) {
        if (session == null) return;

        // Re-apply the terminal font/color scheme after the session is attached. This is required
        // after a hot theme swap (recreate / system day-night change): the previously-attached
        // session is re-bound to a brand-new TerminalView here, and a checkForFontAndColors() that
        // ran earlier (e.g. from onServiceConnected) may have executed before the emulator was
        // bound to the new view, so its repaint would have been silently dropped. Re-applying now
        // guarantees the terminal matches the current night mode.
        checkForFontAndColors();

        // NOTE: we deliberately do NOT call checkAndScrollToSession() here. That helper ends in
        // termuxSessionListNotifyUpdated(), which calls notifyDataSetChanged() + setCurrentItem(...,
        // false). On a plain swipe the session COUNT has not changed, so rebuilding the adapter
        // mid-animation destroys the page ViewHolder and the setCurrentItem(false) snaps without
        // the smooth settle — that is exactly the "abrupt page switch" bug. The pager itself
        // already did the smooth scroll to land here; the tab highlight is refreshed separately
        // in onTerminalPageSelected() via TermuxSessionTabsController.setCurrentSession().
        updateBackgroundColor();

        // Refresh tab titles for the newly-current session.  This was done by the second
        // updateTabs() call that used to happen via checkAndScrollToSession() in the legacy
        // code path.  Without it a freshly-created session keeps its default "Terminal" title
        // until an unrelated tab redraw happens.  We call updateTabs() directly here (without
        // the checkAndScrollToSession wrapper) because notifyDataSetChanged+setCurrentItem
        // would destroy the pager's ViewHolders mid-animation.
        TermuxService service = mActivity.getTermuxService();
        if (service != null) {
            mActivity.getTermuxSessionTabsController().updateTabs(service.getTermuxSessions());
        }

        // Load the text input content saved for the newly-current session.
        mActivity.restoreTextInputForSession(session);

        // Apply the per-session text input panel visibility state for the new session.
        mActivity.applyTextInputVisibilityForSession(session);

        // Record the newly-current session's working directory into the
        // recent-directories history (for the "new tab" button popup).
        mActivity.recordCurrentDirectory();

        // If per-directory message history is enabled, swap to the new
        // session's directory history.
        mActivity.onHistoryDirectoryChanged();
    }

    void notifyOfSessionChange() {
        // Suppressed — the user requested no popups when switching tabs.
    }

    public void switchToSession(boolean forward) {
        TermuxService service = mActivity.getTermuxService();
        if (service == null) return;

        TerminalSession currentTerminalSession = mActivity.getCurrentSession();
        int index = service.getIndexOfSession(currentTerminalSession);
        int size = service.getTermuxSessionsSize();
        if (forward) {
            if (++index >= size) index = 0;
        } else {
            if (--index < 0) index = size - 1;
        }

        TermuxSession termuxSession = service.getTermuxSession(index);
        if (termuxSession != null)
            setCurrentSession(termuxSession.getTerminalSession());
    }

    public void switchToSession(int index) {
        TermuxService service = mActivity.getTermuxService();
        if (service == null) return;

        TermuxSession termuxSession = service.getTermuxSession(index);
        if (termuxSession != null)
            setCurrentSession(termuxSession.getTerminalSession());
    }

    @SuppressLint("InflateParams")
    public void renameSession(final TerminalSession sessionToRename) {
        if (sessionToRename == null) return;

        TextInputDialogUtils.textInput(mActivity, R.string.title_rename_session, sessionToRename.mSessionName, R.string.action_rename_session_confirm, text -> {
            renameSession(sessionToRename, text);
            termuxSessionListNotifyUpdated();
        }, -1, null, -1, null, null);
    }

    private void renameSession(TerminalSession sessionToRename, String text) {
        if (sessionToRename == null) return;
        sessionToRename.mSessionName = text;
        TermuxService service = mActivity.getTermuxService();
        if (service != null) {
            TermuxSession termuxSession = service.getTermuxSessionForTerminalSession(sessionToRename);
            if (termuxSession != null)
                termuxSession.getExecutionCommand().shellName = text;
        }
    }

    public void addNewSession(boolean isFailSafe, String sessionName) {
        TermuxService service = mActivity.getTermuxService();
        if (service == null) return;

        if (service.getTermuxSessionsSize() >= MAX_SESSIONS) {
            new AlertDialog.Builder(mActivity).setTitle(R.string.title_max_terminals_reached).setMessage(R.string.msg_max_terminals_reached)
                .setPositiveButton(android.R.string.ok, null).show();
        } else {
            TerminalSession currentSession = mActivity.getCurrentSession();

            String workingDirectory;
            if (currentSession == null) {
                workingDirectory = mActivity.getProperties().getDefaultWorkingDirectory();
            } else {
                workingDirectory = currentSession.getCwd();
            }

            TermuxSession newTermuxSession = service.createTermuxSession(null, null, null, workingDirectory, isFailSafe, sessionName);
            if (newTermuxSession == null) return;

            TerminalSession newTerminalSession = newTermuxSession.getTerminalSession();

            // Cold-start detection: the pager has never been populated (adapter has 0 items)
            // and we just created the first session.  The emulator subprocess
            // (JNI.createSubprocess → fork) takes long enough to cause a visible UI stutter
            // if it runs inside the pager layout pass on the UI thread.  Instead, initialize
            // the emulator with default dimensions on a background thread, then attach the
            // session to the pager once the subprocess is alive.
            androidx.viewpager2.widget.ViewPager2 pager = mActivity.getTerminalPager();
            boolean isColdStart = (pager == null || pager.getAdapter() == null
                    || pager.getAdapter().getItemCount() == 0)
                    && service.getTermuxSessionsSize() == 1
                    && newTerminalSession.getEmulator() == null;
            if (isColdStart) {
                mActivity.setColdStartSessionPending(true);
                final TermuxActivity activity = mActivity;
                final TerminalSession session = newTerminalSession;
                new Thread(() -> {
                    android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DEFAULT);
                    // Initialize the emulator with reasonable default dimensions.
                    // JNI.createSubprocess() (fork + PTY setup) runs here, NOT on the UI thread.
                    session.updateSize(80, 24, 10, 10);
                    activity.runOnUiThread(() -> {
                        if (activity.isFinishing()) return;
                        mActivity.setColdStartSessionPending(false);
                        // Run the full pager sync now.  syncWithServiceList will notify
                        // RecyclerView that items are available, triggering a layout pass
                        // that creates the ViewHolder and binds it to the session via
                        // attachSession() → updateSize().  Since the emulator is already
                        // initialized, that updateSize() will find mEmulator != null and
                        // only resize — no fork on the UI thread.
                        // onTerminalPageSelected will run through its deferred path
                        // (pageView == null initially) and complete once the layout
                        // pass creates the page — all without blocking the UI.
                        activity.syncTerminalPagerToService();
                    });
                }).start();
                return;
            }

            setCurrentSession(newTerminalSession);
        }
    }

    /**
     * Create a new terminal session starting in the given directory. Used by the
     * "new tab" button's directory-history popup: picking a directory opens a fresh
     * session there instead of inheriting the current session's cwd.
     */
    public void addNewSessionInDirectory(@NonNull String directory) {
        TermuxService service = mActivity.getTermuxService();
        if (service == null) return;

        if (service.getTermuxSessionsSize() >= MAX_SESSIONS) {
            new AlertDialog.Builder(mActivity).setTitle(R.string.title_max_terminals_reached).setMessage(R.string.msg_max_terminals_reached)
                .setPositiveButton(android.R.string.ok, null).show();
            return;
        }

        TermuxSession newTermuxSession = service.createTermuxSession(null, null, null, directory, false, null);
        if (newTermuxSession == null) return;

        TerminalSession newTerminalSession = newTermuxSession.getTerminalSession();
        setCurrentSession(newTerminalSession);
    }

    public void setCurrentStoredSession() {
        TerminalSession currentSession = mActivity.getCurrentSession();
        if (currentSession != null)
            mActivity.getPreferences().setCurrentSession(currentSession.mHandle);
        else
            mActivity.getPreferences().setCurrentSession(null);
    }

    /** The current session as stored or the last one if that does not exist. */
    public TerminalSession getCurrentStoredSessionOrLast() {
        TerminalSession stored = getCurrentStoredSession();

        if (stored != null) {
            // If a stored session is in the list of currently running sessions, then return it
            return stored;
        } else {
            // Else return the last session currently running
            TermuxService service = mActivity.getTermuxService();
            if (service == null) return null;

            TermuxSession termuxSession = service.getLastTermuxSession();
            if (termuxSession != null)
                return termuxSession.getTerminalSession();
            else
                return null;
        }
    }

    private TerminalSession getCurrentStoredSession() {
        String sessionHandle = mActivity.getPreferences().getCurrentSession();

        // If no session is stored in shared preferences
        if (sessionHandle == null)
            return null;

        // Check if the session handle found matches one of the currently running sessions
        TermuxService service = mActivity.getTermuxService();
        if (service == null) return null;

        return service.getTerminalSessionForHandle(sessionHandle);
    }

    public void removeFinishedSession(TerminalSession finishedSession) {
        // Return pressed with finished session - remove it.
        TermuxService service = mActivity.getTermuxService();
        if (service == null) return;

        // Drop the per-session saved text input for the removed session.
        mActivity.clearTextInputForSession(finishedSession);

        int index = service.removeTermuxSession(finishedSession);

        int size = service.getTermuxSessionsSize();
        if (size == 0) {
            // There are no sessions to show, so finish the activity.
            mActivity.finishActivityIfNotFinishing();
        } else {
            if (index >= size) {
                index = size - 1;
            }
            // Sync pager and tabs. The pager sync inside termuxSessionListNotifyUpdated() already
            // handles session restoration via its restoreIndex logic:
            //   - If the removed session was the current one: falls back to the pager's old current
            //     item index (clamped to the new list bounds), which selects the session that shifted
            //     into that position or the last session.
            //   - If the removed session was NOT the current one: finds the current session's new
            //     index in the updated list and stays on it.
            // Then onTerminalPageSelected() re-points mTerminalView and runs per-session bookkeeping.
            //
            // IMPORTANT: Do NOT add a setCurrentSession() call here using the stale <index> returned
            // by removeTermuxSession().  That index was the removed session's position in the OLD
            // list and is meaningless in the new list.  Using it to look up a "fallback" session
            // causes two regressions:
            //   (a) Scenario #8 (close tab + new tab race): list [A,B,C,D] → [A,C,D], index=1 (B's
            //       old slot), getTermuxSession(1)=C → switches from D to C.
            //   (b) Non-current session finishes: list [A,B,C] → [A,C], index=1 (B's old slot),
            //       getTermuxSession(1)=C → switches from A to C.
            // In both cases the pager sync already restored the correct session; the stale-index
            // lookup overrides it.
            termuxSessionListNotifyUpdated(index);
        }
    }

    public void termuxSessionListNotifyUpdated() {
        mActivity.termuxSessionListNotifyUpdated(-1);
    }

    public void termuxSessionListNotifyUpdated(int removedIndex) {
        mActivity.termuxSessionListNotifyUpdated(removedIndex);
    }

    public void checkAndScrollToSession(TerminalSession session) {
        if (!mActivity.isVisible()) return;
        TermuxService service = mActivity.getTermuxService();
        if (service == null) return;

        final int indexOfSession = service.getIndexOfSession(session);
        if (indexOfSession < 0) return;
        
        // Update session tabs
        termuxSessionListNotifyUpdated();
    }


    String toToastTitle(TerminalSession session) {
        TermuxService service = mActivity.getTermuxService();
        if (service == null) return null;

        final int indexOfSession = service.getIndexOfSession(session);
        if (indexOfSession < 0) return null;
        StringBuilder toastTitle = new StringBuilder("[" + (indexOfSession + 1) + "]");
        if (!TextUtils.isEmpty(session.mSessionName)) {
            toastTitle.append(" ").append(session.mSessionName);
        }
        String title = session.getTitle();
        if (!TextUtils.isEmpty(title)) {
            // Space to "[${NR}] or newline after session name:
            toastTitle.append(session.mSessionName == null ? " " : "\n");
            toastTitle.append(title);
        }
        return toastTitle.toString();
    }


    /**
     * Apply terminal fonts and colors for the current theme.
     * Determines the effective night mode from Termux's own {@link NightMode#getAppNightMode()}
     * (the source of truth). For {@link NightMode#SYSTEM} the actual system night mode is resolved
     * from the device's system resources via {@link ThemeUtils#isSystemNightModeEnabled()}, NOT the
     * application context and NOT the activity context.
     *
     * <p>Why the <i>system</i> resources and not the application/activity context:
     * <ul>
     *   <li>The Application context's {@code uiMode} is updated independently of the activity
     *       lifecycle and can hold a stale configuration right after a {@code recreate()} — especially
     *       on OEM ROMs (e.g. MIUI/HyperOS) — so it can still report the OLD night state while the
     *       recreated activity already switched. That is the original bug: the toolbar/status bar
     *       (re-themed from the activity config) update, but the terminal (read from the app context)
     *       keeps the previous scheme.</li>
     *   <li>The activity context is better than the application context, but AppCompat applies night
     *       mode to the activity via {@code applyOverrideConfiguration}. When the app uses
     *       {@code MODE_NIGHT_FOLLOW_SYSTEM} it should mirror the <i>actual device</i> day/night
     *       setting, which is exactly what {@link Resources#getSystem()} exposes — the one true
     *       source of the device {@code uiMode}, unaffected by any per-activity override.</li>
     * </ul>
     * Reading the system resources guarantees the terminal color scheme tracks the same device
     * night state that drives the activity/toolbar theme, keeping them in sync after any
     * recreate or direct system {@code uiMode} change.
     */
    public void checkForFontAndColors() {
        final NightMode appNightMode = NightMode.getAppNightMode();
        final boolean isNight;
        if (appNightMode == NightMode.SYSTEM) {
            // Read the authoritative device night state directly from the system resources.
            isNight = ThemeUtils.isSystemNightModeEnabled();
        } else {
            isNight = (appNightMode == NightMode.TRUE);
        }
        applyTerminalColorScheme(isNight);
    }

    /**
     * Apply the current terminal font and color scheme to a specific {@link TerminalView} (a pager
     * page) without touching the shared bottom panel (which is updated once via the active view).
     * Used to theme pages as they are (re)bound to their session in the horizontal pager.
     */
    public void checkForFontAndColorsForView(@NonNull TerminalView terminalView) {
        final NightMode appNightMode = NightMode.getAppNightMode();
        final boolean isNight;
        if (appNightMode == NightMode.SYSTEM) {
            isNight = ThemeUtils.isSystemNightModeEnabled();
        } else {
            isNight = (appNightMode == NightMode.TRUE);
        }
        try {
            File colorsFile = ColorSchemeUtils.getColorSchemeFileForTheme(isNight);
            boolean customApplied = (colorsFile != null) && ColorSchemeUtils.loadTerminalColorScheme(colorsFile);
            if (!customApplied) {
                if (!isNight) {
                    TerminalColors.COLOR_SCHEME.updateWith(getLightTerminalColorScheme());
                } else {
                    TerminalColors.COLOR_SCHEME.updateWith(new Properties());
                }
            }

            TerminalEmulator emulator = terminalView.mEmulator;
            if (emulator == null) {
                TerminalSession session = terminalView.getCurrentSession();
                if (session != null) emulator = session.getEmulator();
            }
            if (emulator != null) {
                emulator.mColors.reset();
            }
            if (terminalView != null) {
                terminalView.invalidate();
                terminalView.onScreenUpdated();
            }

            final Typeface newTypeface = (TermuxConstants.TERMUX_FONT_FILE.exists()
                    && TermuxConstants.TERMUX_FONT_FILE.length() > 0)
                    ? Typeface.createFromFile(TermuxConstants.TERMUX_FONT_FILE) : Typeface.MONOSPACE;
            terminalView.setTypeface(newTypeface);
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Error in checkForFontAndColorsForView()", e);
        }
    }

    /** Apply terminal fonts and the color scheme (light or dark) for the given night mode. */
    private void applyTerminalColorScheme(boolean isNight) {
        try {
            // Resolve the scheme file for this theme. Per-theme colors.light/dark.properties take
            // priority so a light/dark terminal scheme can be assigned independently of the UI
            // theme; falls back to the shared colors.properties.
            File colorsFile = ColorSchemeUtils.getColorSchemeFileForTheme(isNight);
            File fontFile = TermuxConstants.TERMUX_FONT_FILE;

            // Load a user color scheme if one is defined. ColorSchemeUtils.loadTerminalColorScheme
            // returns false for the "Default" marker file (only a comment, no real color keys) so
            // the terminal keeps following the app theme instead of being locked to dark.
            boolean customApplied = (colorsFile != null) && ColorSchemeUtils.loadTerminalColorScheme(colorsFile);
            if (!customApplied) {
                if (!isNight) {
                    // No user colors in light mode: use a built-in light scheme so the
                    // terminal matches the light app theme.
                    TerminalColors.COLOR_SCHEME.updateWith(getLightTerminalColorScheme());
                } else {
                    // No custom colors in dark mode: updateWith() calls reset() FIRST, which
                    // restores the built-in DEFAULT_COLORSCHEME (black background / white
                    // foreground) — i.e. the correct DARK terminal scheme.
                    TerminalColors.COLOR_SCHEME.updateWith(new Properties());
                }
            }

            // Cache all derived colours from the now-applied COLOR_SCHEME before styling the
            // panel, so applyPanelColors() reads fresh values via the activity's getters.
            mActivity.recomputeUIColors();

            // Drive the bottom panel + status bar from the now-applied scheme (works for both
            // custom schemes and the theme-derived light/dark fallback).
            applyPanelColors(ColorSchemeUtils.isTerminalSchemeLight());

            // Reset the colors on the emulator the TerminalView is actually rendering. If the view
            // is not yet attached to a session (e.g. during activity recreation on a system
            // day/night switch, before attachSession()/updateSize() binds mEmulator), fall back to
            // the current session's emulator so the color update is not silently lost. Both paths
            // resolve to the same TerminalEmulator instance once the view is attached.
            TerminalView terminalView = mActivity.getTerminalView();
            TerminalEmulator emulator = (terminalView != null) ? terminalView.mEmulator : null;
            if (emulator == null) {
                TerminalSession session = mActivity.getCurrentSession();
                if (session != null) emulator = session.getEmulator();
            }
            if (emulator != null) {
                emulator.mColors.reset();
            }
            updateBackgroundColor();

            // A full forced redraw is NOT required: TerminalRenderer.render() now clears the entire
            // canvas to the current background color and then repaints every visible row on each
            // onDraw() call, so a plain invalidate()/onScreenUpdated() is a COMPLETE repaint of both
            // the glyphs AND the pane background with the new scheme. We keep the invalidate()
            // unconditional because onScreenUpdated() early-returns when mEmulator is null.
            if (terminalView != null) {
                terminalView.invalidate();
                terminalView.onScreenUpdated();
            }

            final Typeface newTypeface = (fontFile.exists() && fontFile.length() > 0) ? Typeface.createFromFile(fontFile) : Typeface.MONOSPACE;
            if (terminalView != null) terminalView.setTypeface(newTypeface);
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Error in applyTerminalColorScheme()", e);
        }
    }

    /**
     * Apply the bottom-panel styling derived from the active terminal color scheme.
     * - The panel (toolbar container + extra-keys view) background is made transparent so only the
     *   buttons themselves show a background.
     * - Each button background becomes a translucent overlay whose tone is picked from the scheme
     *   lightness: dark translucent for light schemes, light translucent for dark schemes.
     * - The alpha (transparency) of the button backgrounds is read from user preferences so the
     *   value is applied ONCE at change time, not recomputed on every frame.
     * - The interactive scrollbar thumb receives the same pre-computed colours.
     * - The status bar icons/theme follow the scheme lightness (light icons on dark schemes,
     *   dark icons on light schemes).
     *
     * @param isSchemeLight Whether the applied terminal color scheme is light.
     */
    public void applyPanelColors(boolean isSchemeLight) {
        // All derived colours are pre-cached by TermuxActivity.recomputeUIColors().
        final int buttonBg = mActivity.getButtonBg();
        final int buttonActiveBg = mActivity.getButtonActiveBg();
        final int buttonText = mActivity.getButtonText();
        final int selectionHighlight = mActivity.getTextSelectionHighlightColor();

        // Bottom panel + extra keys backgrounds -> transparent.
        View toolbar = mActivity.findViewById(R.id.terminal_toolbar_container);
        if (toolbar != null) toolbar.setBackgroundColor(Color.TRANSPARENT);
        ExtraKeysView extraKeys = mActivity.getExtraKeysView();
        if (extraKeys != null) {
            extraKeys.setBackgroundColor(Color.TRANSPARENT);
            extraKeys.setButtonColors(buttonText, buttonText, buttonBg, buttonActiveBg);
        }

        // Session tabs panel buttons (new session / toggle text input / settings) reuse the same
        // translucent background + scheme foreground so they match the extra-keys panel.
        for (int id : new int[]{ R.id.new_session_tab_button, R.id.toggle_text_input_button, R.id.settings_button }) {
            ImageButton btn = mActivity.findViewById(id);
            if (btn != null) {
                btn.setBackgroundTintList(android.content.res.ColorStateList.valueOf(buttonBg));
                btn.setColorFilter(buttonText, android.graphics.PorterDuff.Mode.SRC_ATOP);
            }
        }

        // Session tabs themselves: translucent background (selected = active tone) + scheme fg.
        TermuxSessionTabsController tabsController = mActivity.getTermuxSessionTabsController();
        if (tabsController != null) {
            tabsController.applySchemeColorsToTabs(buttonText, buttonBg, buttonActiveBg);
        }

        // Text input field: foreground from the scheme, translucent container background.
        EditText textInput = mActivity.findViewById(R.id.terminal_toolbar_text_input);
        if (textInput != null) {
            textInput.setTextColor(buttonText);
            textInput.setHintTextColor((buttonText & 0x00FFFFFF) | 0x80000000);
            // Selection highlight uses the cached colour (recomputedUIColors).
            textInput.setHighlightColor(selectionHighlight);
            // Drag handles for text selection also follow the scheme foreground colour.
            tintSelectionHandles(textInput, buttonText);
        }
        View textInputContainer = mActivity.findViewById(R.id.terminal_toolbar_text_input_container);
        if (textInputContainer != null && textInputContainer.getBackground() instanceof android.graphics.drawable.GradientDrawable) {
            // Background = inactive control color; stroke = active control color (matching the
            // bottom-panel buttons), so the input panel reads as part of the same control family.
            android.graphics.drawable.GradientDrawable d =
                (android.graphics.drawable.GradientDrawable) textInputContainer.getBackground().mutate();
            d.setColor(buttonBg);
            d.setStroke(Math.round(1.5f * mActivity.getResources().getDisplayMetrics().density), buttonActiveBg);
        }

        // Push pre-computed scrollbar thumb colours to TerminalView (alpha baked in ONCE here).
        TerminalView tv = mActivity.getTerminalView();
        if (tv != null) {
            tv.setScrollbarColors(buttonBg, buttonActiveBg);
        }

        // Status bar follows the scheme lightness.
        applyStatusBarTheme(isSchemeLight);
    }

    private void applyStatusBarTheme(boolean isSchemeLight) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Window window = mActivity.getWindow();
            if (window == null) return;
            int flags = window.getDecorView().getSystemUiVisibility();
            if (isSchemeLight) {
                flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            } else {
                flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            }
            window.getDecorView().setSystemUiVisibility(flags);
        }
    }

    public void updateBackgroundColor() {
        TerminalSession session = mActivity.getCurrentSession();
        if (session != null && session.getEmulator() != null) {
            // NOTE: Do NOT reset mColors here. This method is also called from shell-driven OSC
            // dynamic color changes and must only *reflect* the live color array onto the activity
            // background, never overwrite it with COLOR_SCHEME (that would discard OSC 4/11 colors).
            // The live emulator color array is re-synced to COLOR_SCHEME by
            // applyTerminalColorScheme() (invoked from checkForFontAndColors(), which runs again in
            // onServiceConnected() once the session is attached after a recreate()).
            mActivity.getWindow().getDecorView().setBackgroundColor(session.getEmulator().mColors.mCurrentColors[TextStyle.COLOR_INDEX_BACKGROUND]);
        }
    }

    /**
     * Returns a {@link Properties} describing a light terminal color scheme (white background,
     * black foreground, readable 16-color palette) used when the app is in light mode and the
     * user has not defined a custom {@code ~/.termux/colors.properties}.
     */
    private Properties getLightTerminalColorScheme() {
        Properties props = new Properties();
        props.setProperty("background", "#ffffff");
        props.setProperty("foreground", "#000000");
        // First 8 (dim) colors, brightened for readability on a white background.
        props.setProperty("color0",  "#000000");
        props.setProperty("color1",  "#cd0000");
        props.setProperty("color2",  "#00cd00");
        props.setProperty("color3",  "#cdcd00");
        props.setProperty("color4",  "#1060c0");
        props.setProperty("color5",  "#cd00cd");
        props.setProperty("color6",  "#00cdcd");
        props.setProperty("color7",  "#404040");
        // Second 8 (bright) colors.
        props.setProperty("color8",  "#808080");
        props.setProperty("color9",  "#ff0000");
        props.setProperty("color10", "#00ff00");
        props.setProperty("color11", "#ffff00");
        props.setProperty("color12", "#6969ff");
        props.setProperty("color13", "#ff00ff");
        props.setProperty("color14", "#00ffff");
        props.setProperty("color15", "#ffffff");
        // cursor color is auto-picked based on background brightness by TerminalColorScheme.
        return props;
    }

    /**
     * Tint the three text-selection drag handles (left, right, paste/cursor) of the text input field
     * to the given {@code color}, so they match the terminal colour scheme instead of the theme's
     * static accent colour.
     * <p>
     * Uses reflection to call the public {@code getTextSelectHandle*()} / {@code setTextSelectHandle*()}
     * methods (public API since API 29).  The fields themselves are avoided because on modern Android
     * the hidden-API blocklist prevents accessing {@code mSelectHandleLeft} etc. via reflection.
     * <p>
     * On API < 29 this is a silent no-op (no public API, and fields are blocked on API 28+).
     * <p>
     * Silent best-effort: does nothing on API levels or ROM variants that lack these methods.
     */
    @SuppressLint("DiscouragedPrivateApi,PrivateApi")
    private static void tintSelectionHandles(@NonNull EditText editText, int tintColor) {
        try {
            Class<?> tvClass = TextView.class;
            // Public API methods available since API 29 → accessible via reflection even when
            // compileSdk < 29.  Not blocked by hidden API restrictions because they are public.
            String[][] apiDefs = {
                {"getTextSelectHandleLeft",   "setTextSelectHandleLeft"},
                {"getTextSelectHandleRight",  "setTextSelectHandleRight"},
                {"getTextSelectHandle",       "setTextSelectHandle"},
            };
            for (String[] def : apiDefs) {
                String getterName = def[0];
                String setterName = def[1];
                Method getter;
                try {
                    getter = tvClass.getMethod(getterName);
                } catch (NoSuchMethodException e) {
                    continue; // API level below 29 → skip
                }
                Object obj = getter.invoke(editText);
                if (obj instanceof Drawable) {
                    Drawable d = ((Drawable) obj).mutate();
                    d.setTint(tintColor);
                    try {
                        Method setter = tvClass.getMethod(setterName, Drawable.class);
                        setter.invoke(editText, d);
                    } catch (NoSuchMethodException ignored) {
                        // Setter not available on this ROM
                    }
                }
            }
            editText.invalidate();
        } catch (Exception e) {
            // Best-effort — silently ignore.
        }
    }

}

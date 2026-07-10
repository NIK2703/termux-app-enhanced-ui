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
import android.media.AudioAttributes;
import android.media.SoundPool;
import android.os.Build;
import android.text.TextUtils;
import android.view.View;
import android.view.Window;
import android.widget.EditText;
import android.widget.ImageButton;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.R;
import com.termux.shared.interact.ShareUtils;
import com.termux.shared.termux.shell.command.runner.terminal.TermuxSession;
import com.termux.shared.termux.interact.TextInputDialogUtils;
import com.termux.app.TermuxActivity;
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
        mActivity.getTerminalView().onScreenUpdated();
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

        if (mActivity.getCurrentSession() == changedSession) mActivity.getTerminalView().onScreenUpdated();
    }

    @Override
    public void onTitleChanged(@NonNull TerminalSession updatedSession) {
        if (!mActivity.isVisible()) return;

        if (updatedSession != mActivity.getCurrentSession()) {
            // Only show toast for other sessions than the current one, since the user
            // probably consciously caused the title change to change in the current session
            // and don't want an annoying toast for that.
            mActivity.showToast(toToastTitle(updatedSession), true);
        }

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

        if (mActivity.isVisible() && finishedSession != mActivity.getCurrentSession()) {
            // Show toast for non-current sessions that exit.
            // Verify that session was not removed before we got told about it finishing:
            if (index >= 0)
                mActivity.showToast(toToastTitle(finishedSession) + " - exited", true);
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
        if (text != null)
            mActivity.getTerminalView().mEmulator.paste(text);
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
        mActivity.getTerminalView().setTerminalCursorBlinkerState(enabled, false);
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
        mActivity.getTerminalView().setTerminalCursorBlinkerState(true, true);
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

    /** Try switching to session with optional toast notification. */
    public void setCurrentSession(TerminalSession session, boolean showToast) {
        if (session == null) return;

        if (mActivity.getTerminalView().attachSession(session)) {
            // notify about switched session if not already displaying the session
            if (showToast) {
                notifyOfSessionChange();
            }
        }

        // Re-apply the terminal font/color scheme after the session is attached. This is required
        // after a hot theme swap (recreate / system day-night change): the previously-attached
        // session is re-bound to a brand-new TerminalView here, and a checkForFontAndColors()
        // that ran earlier (e.g. from onServiceConnected) may have executed before the emulator
        // was bound to the new view, so its repaint would have been silently dropped. Re-applying
        // now guarantees the terminal matches the current night mode.
        checkForFontAndColors();

        // We call the following even when the session is already being displayed since config may
        // be stale, like current session not selected or scrolled to.
        checkAndScrollToSession(session);
        updateBackgroundColor();
    }

    void notifyOfSessionChange() {
        if (!mActivity.isVisible()) return;

        if (!mActivity.getProperties().areTerminalSessionChangeToastsDisabled()) {
            TerminalSession session = mActivity.getCurrentSession();
            mActivity.showToast(toToastTitle(session), false);
        }
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
            setCurrentSession(newTerminalSession);
        }
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

        int index = service.removeTermuxSession(finishedSession);

        int size = service.getTermuxSessionsSize();
        if (size == 0) {
            // There are no sessions to show, so finish the activity.
            mActivity.finishActivityIfNotFinishing();
        } else {
            if (index >= size) {
                index = size - 1;
            }
            TermuxSession termuxSession = service.getTermuxSession(index);
            if (termuxSession != null)
                setCurrentSession(termuxSession.getTerminalSession());
        }
    }

    public void termuxSessionListNotifyUpdated() {
        mActivity.termuxSessionListNotifyUpdated();
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
            mActivity.getTerminalView().setTypeface(newTypeface);
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
     * - The status bar icons/theme follow the scheme lightness (light icons on dark schemes,
     *   dark icons on light schemes).
     *
     * @param isSchemeLight Whether the applied terminal color scheme is light.
     */
    private void applyPanelColors(boolean isSchemeLight) {
        final int buttonBg = ColorSchemeUtils.getButtonBackground(isSchemeLight);
        final int buttonActiveBg = ColorSchemeUtils.getButtonActiveBackground(isSchemeLight);
        final int buttonText = ColorSchemeUtils.getSchemeForeground();

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

}

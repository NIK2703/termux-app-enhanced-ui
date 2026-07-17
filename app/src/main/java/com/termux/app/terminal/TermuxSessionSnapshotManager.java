package com.termux.app.terminal;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import com.termux.app.TermuxActivity;
import com.termux.app.TermuxService;
import com.termux.shared.logger.Logger;
import com.termux.shared.shell.command.ExecutionCommand;
import com.termux.shared.termux.shell.command.runner.terminal.TermuxSession;
import com.termux.shared.termux.settings.properties.TermuxAppSharedProperties;
import com.termux.terminal.TerminalSession;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Persists and restores the set of open terminal tabs (working directory, name,
 * failsafe flag) plus the active tab index across app restarts, so a cold start
 * (e.g. after the {@code TermuxService} was killed) can reopen the same tabs.
 * <p>
 * The snapshot is stored in {@code termux_prefs} as a JSON string. Saving happens
 * on {@code onStop} (and whenever sessions are alive) and restoring on
 * {@code onServiceConnected} when the service has no live sessions.
 */
public class TermuxSessionSnapshotManager {

    private static final String LOG_TAG = "TermuxSessionSnapshotManager";

    private static final String PREF_RESTORE_SESSIONS = "restore_sessions";
    private static final String PREF_RESTORE_TERMINAL_HISTORY = "restore_terminal_history";
    private static final String PREF_SESSION_SNAPSHOT = "session_snapshot";

    /** Owns the service binding, current session and properties we read from. */
    private final TermuxActivity mActivity;

    public TermuxSessionSnapshotManager(final TermuxActivity activity) {
        mActivity = activity;
    }

    /**
     * Get the {@code termux_prefs} SharedPreferences. The manager only ever
     * reads/writes the two snapshot-related keys and the restore toggle.
     */
    private SharedPreferences getPrefs() {
        return mActivity.getSharedPreferences("termux_prefs", Context.MODE_PRIVATE);
    }

    /** Whether restoring open tabs on launch is enabled (default: on). */
    public boolean isRestoreSessionsEnabled() {
        return getPrefs().getBoolean(PREF_RESTORE_SESSIONS, true);
    }

    /**
     * Whether the full scrollback/transcript of every terminal is persisted
     * (and restored) together with the tab list. Off by default: saving the
     * transcript can produce large snapshots and the restored text is
     * read-only (the shell process is gone, so it can only be scrolled, not
     * edited).
     */
    public boolean isRestoreTerminalHistoryEnabled() {
        return getPrefs().getBoolean(PREF_RESTORE_TERMINAL_HISTORY, false);
    }

    /**
     * Snapshot the currently open tabs (working directory, name, failsafe flag)
     * plus the index of the active tab, and persist it as JSON. Called on onStop
     * so a later cold start (service killed) can reopen the same tabs. When the
     * feature is off, the stored snapshot is cleared instead.
     */
    public void saveSessionSnapshot() {
        final SharedPreferences prefs = getPrefs();
        if (!isRestoreSessionsEnabled()) {
            prefs.edit().remove(PREF_SESSION_SNAPSHOT).apply();
            return;
        }
        TermuxService service = mActivity.getTermuxService();
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

        final boolean saveHistory = isRestoreTerminalHistoryEnabled();
        TerminalSession current = mActivity.getCurrentSession();
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
                if (saveHistory && terminal != null && terminal.getEmulator() != null) {
                    String transcript = terminal.getEmulator().getScreen().getTranscriptText();
                    if (!TextUtils.isEmpty(transcript)) tab.put("history", transcript);
                }
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
    public boolean restoreSessionSnapshot() {
        if (!isRestoreSessionsEnabled()) return false;
        String json = getPrefs().getString(PREF_SESSION_SNAPSHOT, null);
        if (TextUtils.isEmpty(json)) return false;

        TermuxService service = mActivity.getTermuxService();
        if (service == null) return false;

        TermuxAppSharedProperties properties = mActivity.getProperties();
        TermuxTerminalSessionActivityClient sessionClient = mActivity.getTermuxTerminalSessionClient();

        // Saved scrollback for each tab, in creation order. The emulator does
        // not exist yet at this point (it is created lazily on updateSize), so
        // we keep the text here and inject it once the emulators are
        // initialized on the background thread in onServiceConnected.
        List<String> pendingHistory = new ArrayList<>();
        boolean captureHistory = isRestoreTerminalHistoryEnabled();

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
                        ? properties.getDefaultWorkingDirectory() : cwd;
                TermuxSession session = service.createTermuxSession(null, null, null,
                        workingDirectory, failsafe, TextUtils.isEmpty(name) ? null : name);
                if (session == null) continue;
                restored++;
                pendingHistory.add(captureHistory ? tab.optString("history", null) : null);
            }
        } catch (JSONException e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to restore session snapshot", e);
            return false;
        }

        if (restored == 0) return false;

        // Stash the captured scrollback so it can be applied after the emulators
        // are initialized (the background init in TermuxServiceConnectionManager).
        mPendingHistory = captureHistory ? pendingHistory : null;

        List<TermuxSession> sessions = service.getTermuxSessions();
        if (sessions != null && !sessions.isEmpty()) {
            int idx = Math.max(0, Math.min(active, sessions.size() - 1));
            sessionClient.setCurrentSession(sessions.get(idx).getTerminalSession());
        }
        return true;
    }

    /**
     * Pending restored scrollback, keyed by session creation order. Non-null
     * only when {@link #isRestoreTerminalHistoryEnabled()} was on at restore
     * time and at least one tab carried a saved transcript.
     */
    private List<String> mPendingHistory;

    /**
     * Inject any pending restored scrollback into the emulators of the sessions
     * created by {@link #restoreSessionSnapshot()}. Must be called AFTER each
     * session's emulator has been initialized (i.e. after {@code updateSize}),
     * which is why it runs from the background init thread in
     * {@code TermuxServiceConnectionManager}. Safe to call multiple times; the
     * pending list is cleared after the first successful application.
     */
    public void applyPendingTerminalHistory() {
        final List<String> pending = mPendingHistory;
        if (pending == null) return;
        mPendingHistory = null;
        TermuxService service = mActivity.getTermuxService();
        if (service == null) return;
        List<TermuxSession> sessions = service.getTermuxSessions();
        if (sessions == null) return;
        for (int i = 0; i < sessions.size() && i < pending.size(); i++) {
            String history = pending.get(i);
            if (TextUtils.isEmpty(history)) continue;
            TerminalSession terminal = sessions.get(i).getTerminalSession();
            if (terminal != null && terminal.getEmulator() != null) {
                terminal.getEmulator().appendTranscriptText(history);
            }
        }
    }
}

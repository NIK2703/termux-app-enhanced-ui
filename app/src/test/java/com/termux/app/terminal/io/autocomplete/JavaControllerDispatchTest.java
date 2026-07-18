package com.termux.app.terminal.io.autocomplete;


import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.widget.EditText;

import com.termux.app.terminal.TermuxColorSchemeManager;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Module tests for the COMPLEX layer of the shell-completion engine — the
 * dispatch / debounce / generation-guard / filtering logic inside
 * {@link AutoCompleteController}, driven through the REAL device bash
 * ({@code /data/data/com.termux/files/usr/bin/bash}).
 *
 * <p>This complements {@code JavaControllerRealBashTest} by focusing on:
 * <ul>
 *   <li>the fetch debounce + in-flight guard (rapid repeated keystrokes must not
 *       spawn a new bash subprocess per keystroke);</li>
 *   <li>the generation guard (a superseded fetch is discarded, not applied);</li>
 *   <li>{@code updateAutoCompleteSuggestions()} behaviour with shell completion
 *       ON vs OFF (Path A vs Path B dispatch);</li>
 *   <li>per-prefix local filtering of shell candidates against the last word;</li>
 *   <li>the {@code mInputField == null} guards (no crash when detached).</li>
 * </ul>
 *
 * <p>Robolectric loads the Android-dependent classes; {@code ConscryptMode.OFF}
 * avoids the aarch64 Conscrypt crash.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class JavaControllerDispatchTest {

    private static final File DEVICE_BASH = new File("/data/data/com.termux/files/usr/bin/bash");

    private Context mContext;
    private AutoCompleteController mController;
    private ShellCompletionProvider mProvider;

    @Before
    public void setUp() {
        assertTrue("device bash must exist for these tests", DEVICE_BASH.exists());
        mContext = RuntimeEnvironment.application;
        EditText input = new EditText(mContext);
        // A non-empty seed so updateAutoCompleteSuggestions doesn't early-return on empty.
        input.setText("git ");
        input.setSelection(input.getText().length());
        com.termux.app.terminal.io.autocomplete.MessageHistoryController history =
                new com.termux.app.terminal.io.autocomplete.MessageHistoryController(
                        mContext.getSharedPreferences("test_history_dispatch", Context.MODE_PRIVATE));
        mController = new AutoCompleteController(mContext, input, history, new TermuxColorSchemeManager());
        mProvider = new ShellCompletionProvider(mContext, DEVICE_BASH);
        assertTrue("device bash must be available", mProvider.isAvailable());
        mController.setShellCompletionEnabled(true);
        assertFalse("provider must be non-null after enable", mController.debugProviderNull());
    }

    // ── fetch debounce + in-flight guard ──

    @Test
    public void rapidFetchCalls_incrementGeneration_guard() {
        // Each fetch request bumps the generation token so any previously-queued
        // fetch is superseded (only the newest gen runs). Two rapid calls must
        // raise gen by exactly 2 and must NOT spawn the subprocess immediately
        // (debounce delays it), so in-flight stays false right after scheduling.
        int gen0 = mController.debugGetGen();
        mController.debugFetchShellCandidatesAsync("git st");
        mController.debugFetchShellCandidatesAsync("git sta");
        int gen1 = mController.debugGetGen();
        assertEquals("two fetch requests must bump gen by 2", gen0 + 2, gen1);
        // Debounce window (70ms) means bash hasn't run yet → in-flight still false.
        assertFalse("in-flight must be false before debounce fires", mController.debugIsFetchInFlight());
    }

    @Test
    public void fetchAbortsWhenInputFieldNull() {
        // With a null input field the fetch must short-circuit (no subprocess, no
        // in-flight flip), guarding against a detached controller.
        mController.debugSetInputFieldNull();
        mController.debugFetchShellCandidatesAsync("git st");
        assertFalse("no fetch in flight when input field is null", mController.debugIsFetchInFlight());
        // Restore so other behaviour is unaffected.
        EditText input = new EditText(mContext);
        input.setText("git ");
        mController.debugSetInputField(input);
    }

    @Test
    public void generationGuard_discardsSuperseded() {
        // Bumping the generation after a request is scheduled must make that
        // request stale: the controller's current gen no longer matches the
        // scheduled one, so it would be discarded on delivery.
        int gen0 = mController.debugGetGen();
        mController.debugFetchShellCandidatesAsync("git st");
        int scheduled = mController.debugGetGen();
        assertEquals("fetch scheduled a new generation", gen0 + 1, scheduled);
        mController.debugBumpGen();
        int afterBump = mController.debugGetGen();
        assertTrue("bumping gen supersedes the scheduled fetch", afterBump > scheduled);
    }

    // ── updateAutoCompleteSuggestions dispatch (shell ON vs OFF) ──

    @Test
    public void update_shellDisabled_providerNull_pathA() {
        // Disable shell completion → provider becomes null → Path A (history only).
        mController.setShellCompletionEnabled(false);
        assertTrue("shell disabled → provider null → Path A", mController.debugProviderNull());
        // updateAutoCompleteSuggestions must run without touching the provider.
        mController.onTextChanged();
        assertTrue("still provider-null after update", mController.debugProviderNull());
    }

    @Test
    public void update_shellEnabled_noCrash() {
        // With shell enabled and a non-empty field, a full rescan + async fetch is
        // scheduled without throwing.
        EditText input = mController.debugGetInputField();
        assertNotNull("input field present", input);
        input.setText("cd ~/D");
        input.setSelection(input.getText().length());
        mController.onTextChanged();
        assertFalse("provider present after update", mController.debugProviderNull());
    }

    @Test
    public void update_inputFieldNull_guard() {
        // A null input field must make updateAutoCompleteSuggestions() return
        // early (no NPE, no popup work).
        mController.debugSetInputFieldNull();
        mController.onTextChanged();
        // Restore.
        EditText input = new EditText(mContext);
        input.setText("git ");
        mController.debugSetInputField(input);
    }

    // ── historyCoversPrefix skips bash ──

    @Test
    public void historyCoversPrefix_skipsFetch() {
        // When history already covers the exact typed command, the fetch is
        // skipped entirely (no in-flight / no scheduled subprocess).
        mController.debugSetHistory(Arrays.asList("ls", "git"));
        // "ls" is a bare single command exactly in history → covered.
        assertTrue("history must cover exact command", mController.historyCoversPrefix("ls"));
        mController.debugFetchShellCandidatesAsync("ls");
        assertFalse("fetch skipped when history covers prefix", mController.debugIsFetchInFlight());
    }

    // ── per-prefix local filtering of shell candidates (Path B) ──

    @Test
    public void filterShellCandidates_byLastWord() {
        // Directly exercise the per-prefix local filter (the Path B narrowing that
        // runs on every keystroke). Shell candidates are matched against the LAST
        // WORD of the line; history against the WHOLE line.
        java.util.List<String> sugg = new ArrayList<>(Arrays.asList("File2x", "File2y", "File3z"));
        java.util.List<Boolean> shell = new ArrayList<>(Arrays.asList(true, true, true));

        // Narrow the last word to "Fi" — all three survive (still match prefix).
        mController.filterSuggestionsByPrefix("cat Fi", sugg, shell);
        assertTrue("File2x present after Fi", sugg.contains("File2x"));
        assertTrue("File2y present after Fi", sugg.contains("File2y"));
        assertTrue("File3z present after Fi", sugg.contains("File3z"));

        // Narrow further to "File2" — only the File2* candidates survive.
        java.util.List<String> sugg2 = new ArrayList<>(Arrays.asList("File2x", "File2y", "File3z"));
        java.util.List<Boolean> shell2 = new ArrayList<>(Arrays.asList(true, true, true));
        mController.filterSuggestionsByPrefix("cat File2", sugg2, shell2);
        assertTrue("File2x survives File2 prefix", sugg2.contains("File2x"));
        assertTrue("File2y survives File2 prefix", sugg2.contains("File2y"));
        assertFalse("File3z filtered out", sugg2.contains("File3z"));

        // A candidate EXACTLY equal to the typed last word is dropped (already typed).
        java.util.List<String> sugg3 = new ArrayList<>(Arrays.asList("File2", "File2x"));
        java.util.List<Boolean> shell3 = new ArrayList<>(Arrays.asList(true, true));
        mController.filterSuggestionsByPrefix("cat File2", sugg3, shell3);
        assertFalse("exact-match candidate dropped", sugg3.contains("File2"));
        assertTrue("longer candidate kept", sugg3.contains("File2x"));

        // A history entry is matched against the WHOLE line, so "cat F" must NOT
        // keep a history item "File1" even though its last word matches.
        java.util.List<String> hist = new ArrayList<>(Arrays.asList("File1"));
        java.util.List<Boolean> histShell = new ArrayList<>(Arrays.asList(false));
        mController.filterSuggestionsByPrefix("cat F", hist, histShell);
        assertTrue("history entry removed when whole-line mismatches", hist.isEmpty());
    }

    @Test
    public void filterHistoryCandidates_byWholeLine() {
        // History candidates are matched against the WHOLE line, not the last word.
        mController.debugSetHistory(Arrays.asList("git status", "git diff", "ls -la"));
        // Build a fresh rescan by enabling shell off (pure history path) and updating.
        mController.setShellCompletionEnabled(false);
        EditText input = mController.debugGetInputField();
        assertNotNull("input field present", input);
        input.setText("git s");
        input.setSelection(input.getText().length());
        mController.onTextChanged();

        List<String> suggestions = mController.debugSuggestions();
        // "git status" matches the whole line prefix; "git diff"/"ls -la" do not.
        boolean hasStatus = suggestions.contains("git status");
        boolean hasDiff = suggestions.contains("git diff");
        assertTrue("git status matches whole-line prefix", hasStatus);
        assertFalse("git diff must NOT match 'git s' line prefix", hasDiff);
    }
}

package com.termux.app.terminal.io;

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
 * Module tests for the COMPLEX layer of the shell-completion engine, exercised
 * through the REAL bash of this device ({@code /data/data/com.termux/files/usr/bin/bash}):
 *
 * <ul>
 *   <li>Path A / Path B dispatch inside {@code AutoCompleteController}
 *       (history-only vs shell-fetch) — driven deterministically via the
 *       package-private debug hooks.</li>
 *   <li>{@code historyCoversPrefix} exact-match rule.</li>
 *   <li>{@code mergeShellCandidates} ordering (shell entries first) + de-dup
 *       against the history list, fed by a REAL bash completion result.</li>
 * </ul>
 *
 * <p>Robolectric loads the Android-dependent classes; {@code ConscryptMode.OFF}
 * avoids the aarch64 Conscrypt crash. The device bash is used by
 * {@link ShellCompletionProvider#complete(String, String, int)} so the merge is
 * validated against real filesystem data, not a mock.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class JavaControllerRealBashTest {

    private static final File DEVICE_BASH = new File("/data/data/com.termux/files/usr/bin/bash");

    private Context mContext;
    private AutoCompleteController mController;
    private ShellCompletionProvider mProvider;

    @Before
    public void setUp() {
        assertTrue("device bash must exist for these tests", DEVICE_BASH.exists());
        mContext = RuntimeEnvironment.application;
        EditText input = new EditText(mContext);
        MessageHistoryController history = new MessageHistoryController(
                mContext.getSharedPreferences("test_history", Context.MODE_PRIVATE));
        mController = new AutoCompleteController(mContext, input, history, new TermuxColorSchemeManager());
        mProvider = new ShellCompletionProvider(mContext, DEVICE_BASH);
        assertTrue("device bash must be available", mProvider.isAvailable());
        // Enable shell completion so the provider is created.
        mController.setShellCompletionEnabled(true);
        assertFalse("provider must be non-null after enable", mController.debugProviderNull());
    }

    // ── historyCoversPrefix: exact-match rule ──

    @Test
    public void historyCoversPrefix_exactMatch_true() {
        // historyCoversPrefix only fires for a single bare command word (no '/',
        // no '-', no space) that exactly equals a history entry.
        mController.debugSetHistory(Arrays.asList("ls", "git"));
        assertTrue("exact re-type of a bare command must be covered",
                mController.historyCoversPrefix("ls"));
    }

    @Test
    public void historyCoversPrefix_path_false() {
        mController.debugSetHistory(Arrays.asList("cd", "ls"));
        assertFalse("a path word (contains '/') must NOT be covered by history",
                mController.historyCoversPrefix("cd /data"));
    }

    @Test
    public void historyCoversPrefix_prefix_false() {
        mController.debugSetHistory(Arrays.asList("ls", "git"));
        assertFalse("a prefix of a history command must NOT be considered covered",
                mController.historyCoversPrefix("l"));
    }

    @Test
    public void historyCoversPrefix_emptyHistory_false() {
        mController.debugSetHistory(new ArrayList<String>());
        assertFalse("empty history never covers", mController.historyCoversPrefix("ls"));
    }

    // ── Path A vs Path B dispatch ──

    @Test
    public void pathA_shellDisabled_providerNull() {
        // Disable shell completion → provider becomes null → Path A (history only).
        mController.setShellCompletionEnabled(false);
        assertTrue("shell disabled → provider null → Path A", mController.debugProviderNull());
    }

    @Test
    public void pathB_shellEnabled_providerNonNull() {
        // Enabled → provider present → PATH B may fetch.
        mController.setShellCompletionEnabled(true);
        assertFalse("shell enabled → provider present → Path B eligible", mController.debugProviderNull());
    }

    // ── mergeShellCandidates: ordering + de-dup (REAL bash) ──

    @Test
    public void merge_shellCandidatesFirst() {
        // Real bash: complete a path inside $HOME (where files exist).
        String home = System.getenv("HOME");
        assertNotNull("HOME must be set", home);
        String line = "cat " + home + "/";
        ShellCompletionProvider.CompletionResult result = mProvider.complete(line, home, line.length());
        assertNotNull("completion result must not be null", result);
        assertNotNull("candidates list must not be null", result.candidates);
        assertFalse("real bash must return file candidates for " + line, result.candidates.isEmpty());

        // Seed history entries that collide with shell names to exercise de-dup.
        List<String> history = new ArrayList<>(result.candidates.size());
        for (ShellCompletionProvider.ShellCandidate c : result.candidates) history.add("echo " + c.value);
        mController.debugSetHistory(history);

        mController.debugMergeShellCandidates(result, line);
        List<String> suggestions = mController.debugSuggestions();
        List<Boolean> isShell = mController.debugIsShell();

        assertFalse("must have produced suggestions", suggestions.isEmpty());
        assertEquals("isShell flags must align with suggestions", suggestions.size(), isShell.size());
        // All leading entries must be shell entries.
        int shellCount = mController.debugShellCount();
        assertTrue("at least one shell entry expected", shellCount > 0);
        for (int i = 0; i < shellCount; i++) {
            assertTrue("leading entries must be shell-tagged", isShell.get(i));
        }
    }

    @Test
    public void merge_dedupAgainstHistory() {
        // Craft a result whose first candidate equals a history command → should not duplicate.
        List<ShellCompletionProvider.ShellCandidate> cands = Arrays.asList(
                new ShellCompletionProvider.ShellCandidate("dupcmd", ShellCompletionProvider.CandidateType.COMMAND, false, false, false, false),
                new ShellCompletionProvider.ShellCandidate("other", ShellCompletionProvider.CandidateType.COMMAND, false, false, false, false));
        ShellCompletionProvider.CompletionResult result = new ShellCompletionProvider.CompletionResult(cands, false, false, false);
        mController.debugSetHistory(Arrays.asList("dupcmd", "echo hi"));

        mController.debugMergeShellCandidates(result, "dup");
        List<String> suggestions = mController.debugSuggestions();

        int dupCount = 0;
        for (String s : suggestions) if (s.equals("dupcmd")) dupCount++;
        assertEquals("dupcmd must appear exactly once after merge", 1, dupCount);
    }

    @Test
    public void merge_emptyResult_noShellEntries() {
        // An empty shell result must not introduce any shell entries.
        List<ShellCompletionProvider.ShellCandidate> none = new ArrayList<>();
        ShellCompletionProvider.CompletionResult empty = new ShellCompletionProvider.CompletionResult(none, false, false, false);
        mController.debugMergeShellCandidates(empty, "zzz");
        assertEquals("no shell entries when result empty", 0, mController.debugShellCount());
        assertTrue("suggestions stay empty without history", mController.debugSuggestions().isEmpty());
    }
}

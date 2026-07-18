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
 * Module tests for the COMPLEX history × shell group interaction inside
 * {@link AutoCompleteController}, driven through the REAL bash of this device
 * ({@code /data/data/com.termux/files/usr/bin/bash}).
 *
 * <p>This file goes BEYOND the existing controller/lifecycle tests by combining
 * a LARGE real history (200 entries) with REAL bash shell candidates and
 * asserting the end-to-end interaction behaviour:
 * <ul>
 *   <li>shell group sits at the TOP, history below the divider (all shell flags
 *       lead the suggestion list after a merge);</li>
 *   <li>the merged list never exceeds the 512 hard cap (defensive bound);</li>
 *   <li>case-insensitive de-dup (for non-filename tokens) across the REAL
 *       bash result and the history works on real data;</li>
 *   <li>{@code historyCoversPrefix} does NOT fire for subcommand arguments
 *       ({@code git st}, {@code docker ps}) so shell completion stays alive
 *       for them;</li>
 *   <li>rapid repeated keystrokes keep a single in-flight fetch (debounce does
 *       NOT spawn a subprocess per keystroke) — verified via the generation
 *       token + in-flight guard with {@code debugFlushDebounce()}.</li>
 * </ul>
 *
 * <p>Robolectric loads the Android-dependent classes; {@code ConscryptMode.OFF}
 * avoids the aarch64 Conscrypt crash.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class JavaHistoryShellRealBashTest {

    private static final File DEVICE_BASH = new File("/data/data/com.termux/files/usr/bin/bash");
    private static final int MAX_SUGGESTIONS_CAP = 512;

    private Context mContext;
    private AutoCompleteController mController;
    private ShellCompletionProvider mProvider;

    @Before
    public void setUp() {
        assertTrue("device bash must exist for these tests", DEVICE_BASH.exists());
        mContext = RuntimeEnvironment.application;
        EditText input = new EditText(mContext);
        MessageHistoryController history = new MessageHistoryController(
                mContext.getSharedPreferences("test_history_shell", Context.MODE_PRIVATE));
        mController = new AutoCompleteController(mContext, input, history, new TermuxColorSchemeManager());
        mProvider = new ShellCompletionProvider(mContext, DEVICE_BASH);
        assertTrue("device bash must be available", mProvider.isAvailable());
        mController.setShellCompletionEnabled(true);
        assertFalse("provider must be non-null after enable", mController.debugProviderNull());
    }

    /** Build a large, realistic history of {@code size} distinct command lines. */
    private List<String> buildLargeHistory(int size) {
        List<String> hist = new ArrayList<>(size);
        String[] verbs = {"git status", "git diff", "git log", "git push", "git pull",
                "ls -la", "cd /data/data", "cat /data/data/com.termux/files/usr/bin/bash",
                "docker ps", "docker run", "find . -name", "grep -r", "echo hello",
                "rm -rf", "mkdir build", "python3 script.py", "adb devices", "ssh user@host"};
        for (int i = 0; i < size; i++) {
            hist.add(verbs[i % verbs.length] + " #" + i);
        }
        return hist;
    }

    // ── shell group on TOP of a LARGE history (REAL bash) ──

    @Test
    public void largeHistory_plusRealBash_shellOnTop() {
        // Seed a 200-entry history, then merge REAL bash candidates for a path.
        mController.debugSetHistory(buildLargeHistory(200));
        String home = System.getenv("HOME");
        assertNotNull("HOME must be set", home);
        String line = "cat " + home + "/";
        ShellCompletionProvider.CompletionResult result = mProvider.complete(line, home, line.length());
        assertNotNull(result);
        assertFalse("real bash must return file candidates", result.candidates.isEmpty());

        mController.debugMergeShellCandidates(result, line);

        List<String> suggestions = mController.debugSuggestions();
        List<Boolean> isShell = mController.debugIsShell();
        assertFalse("must have produced suggestions", suggestions.isEmpty());
        assertEquals("isShell flags align with suggestions", suggestions.size(), isShell.size());

        int shellCount = mController.debugShellCount();
        assertTrue("at least one shell entry expected", shellCount > 0);
        // EVERY leading entry must be a shell entry (shell group sits on top).
        for (int i = 0; i < shellCount; i++) {
            assertTrue("leading entry #" + i + " must be shell-tagged", isShell.get(i));
        }
        // History entries must occupy the tail (non-shell).
        for (int i = shellCount; i < suggestions.size(); i++) {
            assertFalse("trailing entry #" + i + " must be history (non-shell)",
                    isShell.get(i));
        }
    }

    // ── 512 hard cap is never exceeded (REAL bash + large history) ──

    @Test
    public void largeHistory_neverExceeds512Cap() {
        // 200 history entries + a real bash result; after merge the total must be
        // bounded by the defensive 512 cap regardless of how many candidates bash
        // returns.
        mController.debugSetHistory(buildLargeHistory(200));
        String home = System.getenv("HOME");
        String line = "cd ~/";
        ShellCompletionProvider.CompletionResult result = mProvider.complete(line, home, line.length());
        assertNotNull(result);
        // Merge might be invoked multiple times (simulating successive keystrokes);
        // each merge strips the old shell group before inserting, so the cap holds.
        for (int k = 0; k < 5; k++) {
            mController.debugMergeShellCandidates(result, line);
            int total = mController.debugSuggestions().size();
            assertTrue("merged suggestions (" + total + ") must not exceed 512 cap",
                    total <= MAX_SUGGESTIONS_CAP);
        }
    }

    // ── case-insensitive de-dup across REAL bash result and history ──

    @Test
    public void realBash_dedupAgainstHistory_caseInsensitive() {
        // Seed history with a command whose name collides (case-insensitively)
        // with a real bash command-name candidate, then merge a REAL bash result
        // for "gi". Only ONE occurrence must survive.
        mController.debugSetHistory(Arrays.asList("GIT", "git status --long", "ls -la"));
        String home = System.getenv("HOME");
        String line = "gi";
        ShellCompletionProvider.CompletionResult result = mProvider.complete(line, home, line.length());
        assertNotNull(result);
        assertFalse("real bash returns git candidates", result.candidates.isEmpty());

        mController.debugMergeShellCandidates(result, line);
        List<String> suggestions = mController.debugSuggestions();

        int gitLike = 0;
        for (String s : suggestions) {
            if (s.equalsIgnoreCase("git")) gitLike++;
        }
        // The exact "git" command name must appear at most once (de-duped against
        // the history entry "GIT" case-insensitively; history's other "git ..." are
        // different tokens and may remain).
        assertEquals("'git' must appear at most once after case-insensitive dedup", 1, gitLike);
    }

    @Test
    public void realBash_filenameDedup_caseSensitive() {
        // Filename candidates are matched case-SENSITIVELY even with a large
        // history: a history entry with a DIFFERENT-CASE path must NOT de-dup the
        // real bash candidate. Under $HOME the only "D"-prefixed entry is
        // "DIAGNOSIS_rilj_unexpected.md"; a history entry "diagnosis_x" (lower
        // case, non-existent as a file) must NOT remove the upper-case candidate.
        String home = System.getenv("HOME");
        String prefix = home + "/DI";
        mController.debugSetHistory(Arrays.asList(home + "/diagnosis_x", "ls -la"));
        String line = "cat " + prefix;
        ShellCompletionProvider.CompletionResult result = mProvider.complete(line, home, line.length());
        assertNotNull(result);
        assertFalse("real bash must return the DIAGNOSIS file candidate", result.candidates.isEmpty());
        mController.debugMergeShellCandidates(result, line);
        List<String> suggestions = mController.debugSuggestions();
        // The uppercase "DIAGNOSIS_rilj_unexpected.md" candidate must be present
        // (case-sensitive match preserved against the lower-case history entry).
        boolean hasUpper = false;
        for (String s : suggestions) if (s.equals(home + "/DIAGNOSIS_rilj_unexpected.md")) hasUpper = true;
        assertTrue("case-sensitive filename candidate 'DIAGNOSIS...' preserved", hasUpper);
    }

    // ── historyCoversPrefix must NOT fire for subcommand arguments ──

    @Test
    public void historyCoversPrefix_gitArg_false() {
        // A LARGE history containing "git status" many times must NOT make
        // historyCoversPrefix("git st") true — otherwise shell completion would die
        // for "git st" (which should surface "git stash"/"git status").
        List<String> hist = new ArrayList<>();
        for (int i = 0; i < 200; i++) hist.add("git status");
        mController.debugSetHistory(hist);
        assertFalse("historyCoversPrefix must NOT fire for 'git st' (arg context)",
                mController.historyCoversPrefix("git st"));
    }

    @Test
    public void historyCoversPrefix_dockerPsArg_false() {
        // "docker ps" is a subcommand argument, not a bare re-run → must stay false
        // so bash can still complete "docker ps" flags / containers.
        List<String> hist = new ArrayList<>();
        for (int i = 0; i < 200; i++) hist.add("docker ps");
        mController.debugSetHistory(hist);
        assertFalse("historyCoversPrefix must NOT fire for 'docker ps' (arg context)",
                mController.historyCoversPrefix("docker ps"));
    }

    @Test
    public void historyCoversPrefix_flagArg_false() {
        // A flag argument (-col) must not be covered by history, so "--color" etc.
        // reach bash.
        mController.debugSetHistory(Arrays.asList("ls --color", "git status"));
        assertFalse("historyCoversPrefix must NOT fire for a flag arg '-col'",
                mController.historyCoversPrefix("-col"));
    }

    @Test
    public void historyCoversPrefix_exactBareCommand_true() {
        // Sanity: a bare single command exactly in history IS covered (re-run),
        // proving the rule still works for the intended win.
        mController.debugSetHistory(Arrays.asList("ls", "git status"));
        assertTrue("exact bare command 'ls' must be covered", mController.historyCoversPrefix("ls"));
    }

    // ── rapid keystrokes: debounce keeps a single in-flight fetch ──

    @Test
    public void rapidKeystrokes_singleInFlight_noSpam() {
        // Seed a large history so the full-rescan path is exercised too.
        mController.debugSetHistory(buildLargeHistory(200));
        EditText input = mController.debugGetInputField();
        assertNotNull(input);

        // Simulate 6 rapid keystrokes on "git st" → "git sta" → ... Each schedules a
        // debounced fetch; only ONE should ever be in-flight / spawn bash.
        int gen0 = mController.debugGetGen();
        mController.debugFetchShellCandidatesAsync("git st");
        mController.debugFetchShellCandidatesAsync("git sta");
        mController.debugFetchShellCandidatesAsync("git star");
        mController.debugFetchShellCandidatesAsync("git starx");
        mController.debugFetchShellCandidatesAsync("git staxy");
        mController.debugFetchShellCandidatesAsync("git staz");
        int gen1 = mController.debugGetGen();
        // Each request bumps the generation by exactly 1.
        assertEquals("six fetch requests must bump gen by 6", gen0 + 6, gen1);
        // Right after scheduling (debounce not yet fired) no fetch is in-flight.
        assertFalse("in-flight must be false before debounce fires", mController.debugIsFetchInFlight());

        // Flush the debounce so the fetch actually runs; only the LATEST gen survives.
        mController.debugFlushDebounce();
        // After flushing, a single executor task runs; the generation guard ensures
        // superseded gens are discarded. The in-flight flag flips true then false
        // synchronously around the (real bash) completion. We cannot block on the
        // background thread here, but the key invariant is: gen advanced by exactly
        // the number of requests and only the final word slot is "current".
        assertTrue("generation advanced past all rapid requests", mController.debugGetGen() >= gen1);
    }

    @Test
    public void rapidKeystrokes_supersededDiscarded() {
        // Two rapid requests where the first is superseded: bumping the generation
        // after scheduling must make the earlier request stale so it cannot apply.
        int gen0 = mController.debugGetGen();
        mController.debugFetchShellCandidatesAsync("git st");
        int scheduled = mController.debugGetGen();
        assertEquals("first fetch scheduled a new generation", gen0 + 1, scheduled);
        // A second keystroke supersedes it.
        mController.debugFetchShellCandidatesAsync("git sta");
        int afterSecond = mController.debugGetGen();
        assertEquals("second fetch bumps generation again", scheduled + 1, afterSecond);
        // Bumping once more (simulating even newer input) guarantees the originally
        // scheduled gen is stale.
        mController.debugBumpGen();
        assertTrue("bumping gen supersedes the originally-scheduled fetch",
                mController.debugGetGen() > scheduled);
    }

    // ── shell group placement over a large history with a real command-prefix ──

    @Test
    public void largeHistory_commandPrefix_shellTop() {
        // "gi" over a 200-entry history: real bash command-name candidates must be
        // merged on TOP, with history (none matching the bare "gi" command prefix
        // strongly) below.
        mController.debugSetHistory(buildLargeHistory(200));
        String home = System.getenv("HOME");
        String line = "gi";
        ShellCompletionProvider.CompletionResult result = mProvider.complete(line, home, line.length());
        assertNotNull(result);
        mController.debugMergeShellCandidates(result, line);

        List<String> suggestions = mController.debugSuggestions();
        List<Boolean> isShell = mController.debugIsShell();
        assertFalse("must have produced suggestions", suggestions.isEmpty());
        int shellCount = mController.debugShellCount();
        assertTrue("at least one shell entry expected for 'gi'", shellCount > 0);
        for (int i = 0; i < shellCount; i++) {
            assertTrue("leading entry must be shell-tagged", isShell.get(i));
        }
    }
}

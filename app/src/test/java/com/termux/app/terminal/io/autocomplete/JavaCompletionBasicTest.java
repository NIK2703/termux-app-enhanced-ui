package com.termux.app.terminal.io.autocomplete;


import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;

import java.io.File;
import java.util.List;

/**
 * Module tests for the simpler layers of the shell-completion engine, exercised
 * through the REAL bash of this device ({@code /data/data/com.termux/files/usr/bin/bash}).
 *
 * <p>Robolectric is required only to load the Android-dependent
 * {@link ShellCompletionProvider} class (it references {@code TermuxConstants}).
 * {@code ConscryptMode.OFF} disables Conscrypt, which otherwise fails to
 * initialise on aarch64 Linux (org.conscrypt.Platform NoClassDefFoundError).
 *
 * <p>Part 1 covers the pure-Java tokenizer / classifier logic. Part 2 drives
 * {@link ShellCompletionProvider#complete(String, String, int)} which spawns the
 * device's bash to produce candidates — proving the pipeline reaches real
 * filesystem/command data rather than a mock.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class JavaCompletionBasicTest {

    private ShellCompletionProvider mProvider;

    @Before
    public void setUp() {
        // Use the device's real bash binary so complete() touches the real FS.
        File bash = new File("/data/data/com.termux/files/usr/bin/bash");
        mProvider = new ShellCompletionProvider(RuntimeEnvironment.application, bash);
        assertTrue("device bash must be available for these tests", mProvider.isAvailable());
    }

    // ── Part 1: pure-Java tokenizer / classifier ─────────────────────────

    @Test
    public void lastWordOf() {
        assertEquals("status", ShellCompletionProvider.lastWordOf("git status"));
        assertEquals("git", ShellCompletionProvider.lastWordOf("git"));
        assertEquals("", ShellCompletionProvider.lastWordOf("git "));
        assertEquals("/data/da", ShellCompletionProvider.lastWordOf("cd /data/da"));
        // lastWordOf does NOT honour quotes — a space inside quotes still breaks the token.
        assertEquals("file.txt\"", ShellCompletionProvider.lastWordOf("cp \"my file.txt\""));
    }

    @Test
    public void splitCommandLine() {
        assertArrayEquals(new String[]{"git", "status"},
                ShellCompletionProvider.splitCommandLine("git status"));
        // Quotes are kept INSIDE the word (matching bash COMP_WORDS).
        assertArrayEquals(new String[]{"cp", "\"my file.txt\"", "/tmp/"},
                ShellCompletionProvider.splitCommandLine("cp \"my file.txt\" /tmp/"));
        // Backslash-escaped space stays one token.
        assertArrayEquals(new String[]{"my\\ dir", "x"},
                ShellCompletionProvider.splitCommandLine("my\\ dir x"));
        // Trailing space → empty second token (the word being typed).
        assertArrayEquals(new String[]{"git", ""},
                ShellCompletionProvider.splitCommandLine("git "));
    }

    @Test
    public void stripOuterQuotes() {
        assertEquals("my file", ShellCompletionProvider.stripOuterQuotes("\"my file\""));
        assertEquals("it's", ShellCompletionProvider.stripOuterQuotes("'it's'"));
        assertEquals("plain", ShellCompletionProvider.stripOuterQuotes("plain"));
        assertEquals("/da", ShellCompletionProvider.stripOuterQuotes("/da"));
        assertEquals("partial", ShellCompletionProvider.stripOuterQuotes("\"partial"));
    }

    @Test
    public void dequoteLikeBash() {
        assertEquals("my file", ShellCompletionProvider.dequoteLikeBash("\"my file\""));
        assertEquals("my dir", ShellCompletionProvider.dequoteLikeBash("my\\ dir"));
        assertEquals("plain", ShellCompletionProvider.dequoteLikeBash("plain"));
    }

    @Test
    public void classifyScenarios() {
        assertEquals(ShellCompletionProvider.Scenario.COMMAND_NAME, ShellCompletionProvider.classify("git"));
        assertEquals(ShellCompletionProvider.Scenario.COMMAND_NAME, ShellCompletionProvider.classify(""));
        assertEquals(ShellCompletionProvider.Scenario.COMMAND_NAME, ShellCompletionProvider.classify("git "));
        assertEquals(ShellCompletionProvider.Scenario.PATH, ShellCompletionProvider.classify("cd /da"));
        assertEquals(ShellCompletionProvider.Scenario.PATH, ShellCompletionProvider.classify("cp a.txt ./"));
        assertEquals(ShellCompletionProvider.Scenario.PATH, ShellCompletionProvider.classify("cat ~/D"));
        assertEquals(ShellCompletionProvider.Scenario.PATH, ShellCompletionProvider.classify("LD_LIBRARY_PATH=/opt/"));
        assertEquals(ShellCompletionProvider.Scenario.REDIRECTION, ShellCompletionProvider.classify("cat foo > "));
        assertEquals(ShellCompletionProvider.Scenario.VARIABLE, ShellCompletionProvider.classify("echo $HO"));
        assertEquals(ShellCompletionProvider.Scenario.VARIABLE, ShellCompletionProvider.classify("export PATH=/x"));
        assertEquals(ShellCompletionProvider.Scenario.SIGNAL_JOB, ShellCompletionProvider.classify("kill -"));
        assertEquals(ShellCompletionProvider.Scenario.COMMAND_CONTEXT, ShellCompletionProvider.classify("echo a ; "));
        assertEquals(ShellCompletionProvider.Scenario.COMPSPEC, ShellCompletionProvider.classify("git st"));
        assertEquals(ShellCompletionProvider.Scenario.HISTORY_EXPANSION, ShellCompletionProvider.classify("!gi"));
        assertEquals(ShellCompletionProvider.Scenario.ARRAY_INDEX, ShellCompletionProvider.classify("echo ${ARR["));
    }

    // ── Part 2: complete() against the REAL device bash ──────────────────

    private void assertContains(List<ShellCompletionProvider.ShellCandidate> cands, String contains) {
        assertNotNull(cands);
        boolean found = false;
        for (ShellCompletionProvider.ShellCandidate c : cands) {
            if (c.value.contains(contains)) { found = true; break; }
        }
        assertTrue("expected a candidate containing \"" + contains + "\" but got " + cands, found);
    }

    @Test
    public void completeCommandName_realBash() {
        String home = System.getenv("HOME");
        ShellCompletionProvider.CompletionResult r = mProvider.complete("git", home, 3);
        assertNotNull(r);
        assertFalse("command-name completion must not be empty (real bash)", r.candidates.isEmpty());
        assertContains(r.candidates, "git");
    }

    @Test
    public void completePathTilde_realBash() {
        String home = System.getenv("HOME");
        ShellCompletionProvider.CompletionResult r = mProvider.complete("cd ~/D", home, home.length() + 3);
        assertNotNull(r);
        assertFalse("path completion under $HOME must not be empty (real bash)", r.candidates.isEmpty());
        assertContains(r.candidates, "D");
        assertTrue("path completion must be flagged isFilename", r.isFilename);
    }

    @Test
    public void completePathAbsoluteAccessible_realBash() {
        String home = System.getenv("HOME");
        String prefix = home + "/DI";
        ShellCompletionProvider.CompletionResult r = mProvider.complete("cd " + prefix, home, prefix.length() + 3);
        assertNotNull(r);
        assertFalse("absolute accessible path must not be empty (real bash)", r.candidates.isEmpty());
        assertContains(r.candidates, "DIAGNOSIS");
    }

    @Test
    public void completeVariable_realBash() {
        String home = System.getenv("HOME");
        ShellCompletionProvider.CompletionResult r = mProvider.complete("echo $TER", home, 9);
        assertNotNull(r);
        assertFalse("variable completion must not be empty (real bash)", r.candidates.isEmpty());
        assertContains(r.candidates, "TERM");
    }

    @Test
    public void completeHistoryExpansion_empty() {
        String home = System.getenv("HOME");
        ShellCompletionProvider.CompletionResult r = mProvider.complete("!git", home, 4);
        assertNotNull(r);
        assertTrue("history expansion must short-circuit to EMPTY (no bash spawn)",
                r.candidates.isEmpty());
    }

    @Test
    public void completeEmpty_empty() {
        String home = System.getenv("HOME");
        ShellCompletionProvider.CompletionResult r = mProvider.complete("", home, 0);
        assertNotNull(r);
        assertTrue("empty line must return EMPTY", r.candidates.isEmpty());
    }

    private static void assertArrayEquals(String[] a, String[] b) {
        org.junit.Assert.assertArrayEquals(a, b);
    }
}

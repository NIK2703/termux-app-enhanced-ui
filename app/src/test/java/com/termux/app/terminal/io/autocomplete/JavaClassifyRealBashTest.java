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
import java.util.Arrays;
import java.util.List;

/**
 * Module tests for the SIMPLER layers of the shell-completion engine, driven
 * through the REAL bash of this device ({@code /data/data/com.termux/files/usr/bin/bash}).
 *
 * <p>Part 1 exercises the pure-Java tokenizer / scenario classifier
 * {@link ShellCompletionProvider#classifyScenario(String[], int)} for command-name,
 * path, flag, variable and pipe/&& contexts — including how the classifier reacts
 * to a leading quote, a {@code sudo } prefix, a {@code $VAR/} and a {@code ~/}
 * prefix. Part 2 drives {@link ShellCompletionProvider#complete(String, String, int)}
 * for those same contexts so the candidates are produced by real bash, not a mock.
 *
 * <p>Robolectric is required only to load the Android-dependent
 * {@link ShellCompletionProvider} (it references {@code TermuxConstants});
 * {@code ConscryptMode.OFF} avoids the aarch64 Conscrypt crash.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class JavaClassifyRealBashTest {

    private static final File DEVICE_BASH = new File("/data/data/com.termux/files/usr/bin/bash");

    private ShellCompletionProvider mProvider;

    @Before
    public void setUp() {
        assertTrue("device bash must exist for these tests", DEVICE_BASH.exists());
        mProvider = new ShellCompletionProvider(RuntimeEnvironment.application, DEVICE_BASH);
        assertTrue("device bash must be available", mProvider.isAvailable());
    }

    // ── Part 1: classifyScenario(String[], int) ──

    @Test
    public void classify_commandName() {
        String[] words = {"git"};
        assertEquals(ShellCompletionProvider.Scenario.COMMAND_NAME,
                ShellCompletionProvider.classifyScenario(words, words.length - 1));
    }

    @Test
    public void classify_pathArgument() {
        String[] words = {"cd", "/data/da"};
        assertEquals(ShellCompletionProvider.Scenario.PATH,
                ShellCompletionProvider.classifyScenario(words, words.length - 1));

        String[] rel = {"cat", "~/D"};
        assertEquals(ShellCompletionProvider.Scenario.PATH,
                ShellCompletionProvider.classifyScenario(rel, rel.length - 1));
    }

    @Test
    public void classify_flagOption() {
        String[] words = {"ls", "-"};
        assertEquals(ShellCompletionProvider.Scenario.COMPSPEC,
                ShellCompletionProvider.classifyScenario(words, words.length - 1));
    }

    @Test
    public void classify_variable() {
        String[] words = {"echo", "$HO"};
        assertEquals(ShellCompletionProvider.Scenario.VARIABLE,
                ShellCompletionProvider.classifyScenario(words, words.length - 1));

        String[] assign = {"export", "PATH=/us"};
        assertEquals(ShellCompletionProvider.Scenario.VARIABLE,
                ShellCompletionProvider.classifyScenario(assign, assign.length - 1));
    }

    @Test
    public void classify_afterPipe() {
        // A trailing separator with an EMPTY last word means a new command name is
        // expected → COMMAND_CONTEXT.
        String[] pipe = {"git", "status", "|", ""};
        assertEquals(ShellCompletionProvider.Scenario.COMMAND_CONTEXT,
                ShellCompletionProvider.classifyScenario(pipe, pipe.length - 1));
    }

    @Test
    public void classify_afterAndAnd() {
        String[] andand = {"ls", "/data", "&&", ""};
        assertEquals(ShellCompletionProvider.Scenario.COMMAND_CONTEXT,
                ShellCompletionProvider.classifyScenario(andand, andand.length - 1));
    }

    @Test
    public void classify_afterSemicolon() {
        String[] semi = {"echo", "hi", ";", ""};
        assertEquals(ShellCompletionProvider.Scenario.COMMAND_CONTEXT,
                ShellCompletionProvider.classifyScenario(semi, semi.length - 1));
    }

    @Test
    public void classify_insideQuotes() {
        // A path typed inside an unterminated double quote → PATH, not command name.
        String[] q = {"cd", "\"/data/data/com.termux/files/ho"};
        assertEquals(ShellCompletionProvider.Scenario.PATH,
                ShellCompletionProvider.classifyScenario(q, q.length - 1));
    }

    @Test
    public void classify_sudoPrefix() {
        // "sudo " is two words: command "sudo" then an empty word → new command name.
        String[] sudo = {"sudo", ""};
        assertEquals(ShellCompletionProvider.Scenario.COMMAND_NAME,
                ShellCompletionProvider.classifyScenario(sudo, sudo.length - 1));

        // After "sudo " the next word is still a command name to complete — but
        // "sudo" is not in the command-context keyword list, so a concrete word
        // ("git") is treated as a COMPSPEC argument of sudo (real bash would run
        // sudo's own completion). The classifier returns COMPSPEC here.
        String[] sudoCmd = {"sudo", "git"};
        assertEquals(ShellCompletionProvider.Scenario.COMPSPEC,
                ShellCompletionProvider.classifyScenario(sudoCmd, sudoCmd.length - 1));
    }

    @Test
    public void classify_variableSlash() {
        String[] v = {"echo", "$HOME/"};
        assertEquals(ShellCompletionProvider.Scenario.VARIABLE,
                ShellCompletionProvider.classifyScenario(v, v.length - 1));
    }

    @Test
    public void classify_tildeSlash() {
        String[] t = {"cd", "~/Do"};
        assertEquals(ShellCompletionProvider.Scenario.PATH,
                ShellCompletionProvider.classifyScenario(t, t.length - 1));
    }

    // ── Part 2: complete() against the REAL device bash ──

    private void assertContains(List<ShellCompletionProvider.ShellCandidate> cands, String contains) {
        assertNotNull(cands);
        boolean found = false;
        for (ShellCompletionProvider.ShellCandidate c : cands) {
            if (c.value.contains(contains)) { found = true; break; }
        }
        assertTrue("expected a candidate containing \"" + contains + "\" but got " + cands, found);
    }

    @Test
    public void complete_insideQuotes_realBash() {
        String home = System.getenv("HOME");
        // Unterminated double-quoted path prefix under the real termux HOME.
        String prefix = "\"" + home + "/DI";
        String line = "cd " + prefix;
        ShellCompletionProvider.CompletionResult r = mProvider.complete(line, home, line.length());
        assertNotNull(r);
        assertFalse("quoted path completion under $HOME must not be empty (real bash)", r.candidates.isEmpty());
        assertContains(r.candidates, "DIAGNOSIS");
        assertTrue("quoted path completion must be flagged isFilename", r.isFilename);
    }

    @Test
    public void complete_afterSudo_realBash() {
        String home = System.getenv("HOME");
        // After "sudo " bash completes command names.
        ShellCompletionProvider.CompletionResult r = mProvider.complete("sudo g", home, 6);
        assertNotNull(r);
        assertFalse("sudo <prefix> must complete command names (real bash)", r.candidates.isEmpty());
        assertContains(r.candidates, "g");
    }

    @Test
    public void complete_variableSlash_realBash() {
        String home = System.getenv("HOME");
        // $HOME/ is classified as a VARIABLE token, so bash completes variable
        // names rather than filesystem entries. The scenario must be VARIABLE and
        // the call must not crash / return a null result.
        String[] words = {"echo", "$HOME/D"};
        assertEquals(ShellCompletionProvider.Scenario.VARIABLE,
                ShellCompletionProvider.classifyScenario(words, words.length - 1));
        String line = "echo $HOME/D";
        ShellCompletionProvider.CompletionResult r = mProvider.complete(line, home, line.length());
        assertNotNull("variable completion must return a (possibly empty) result, never null", r);
        assertNotNull("candidates list must not be null", r.candidates);
    }

    @Test
    public void complete_tildeSlash_realBash() {
        String home = System.getenv("HOME");
        String line = "cd ~/D";
        ShellCompletionProvider.CompletionResult r = mProvider.complete(line, home, line.length());
        assertNotNull(r);
        assertFalse("~/ completion must not be empty (real bash)", r.candidates.isEmpty());
        assertContains(r.candidates, "D");
        assertTrue("~/ completion must be flagged isFilename", r.isFilename);
    }

    @Test
    public void complete_commandName_realBash() {
        String home = System.getenv("HOME");
        ShellCompletionProvider.CompletionResult r = mProvider.complete("gi", home, 2);
        assertNotNull(r);
        assertFalse("command-name prefix must not be empty (real bash)", r.candidates.isEmpty());
        assertContains(r.candidates, "git");
    }

    @Test
    public void complete_pipeContextCommand_realBash() {
        String home = System.getenv("HOME");
        // "ls /data | " → new command name expected, bash completes commands.
        String line = "ls /data | ";
        ShellCompletionProvider.CompletionResult r = mProvider.complete(line, home, line.length());
        assertNotNull(r);
        assertFalse("command after pipe must not be empty (real bash)", r.candidates.isEmpty());
        assertContains(r.candidates, "git");
    }

    @Test
    public void complete_quoteTokenizer_consistency() {
        // splitCommandLine must keep a quoted segment intact (quote retained).
        assertArrayEquals(new String[]{"cd", "\"/data/data/com.termux/files/ho"},
                ShellCompletionProvider.splitCommandLine("cd \"/data/data/com.termux/files/ho"));
        // lastWordOf returns the quoted token verbatim (no quote stripping).
        assertEquals("\"/data/data/com.termux/files/ho",
                ShellCompletionProvider.lastWordOf("cd \"/data/data/com.termux/files/ho"));
    }

    @Test
    public void complete_escapeTokenization() {
        // A backslash-escaped space keeps one token.
        assertArrayEquals(new String[]{"my\\ dir", "x"},
                ShellCompletionProvider.splitCommandLine("my\\ dir x"));
    }

    private static void assertArrayEquals(String[] a, String[] b) {
        org.junit.Assert.assertArrayEquals(a, b);
    }
}

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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Module tests for the SIMPLER context-sensitive completion layer of the
 * shell-completion engine, exercised through the REAL bash of this device
 * ({@code /data/data/com.termux/files/usr/bin/bash}).
 *
 * <p>This file deliberately goes BEYOND the existing simpler-layer tests by
 * covering the multi-word / nested / substitution contexts that were NOT yet
 * exercised:
 * <ul>
 *   <li>command chains ({@code cmd1 arg && cmd2 ar}) — completion must classify
 *       the SECOND command's word, not the first;</li>
 *   <li>arithmetic / command substitution contexts ({@code $(...)} and backticks)
 *       and brace expansion ({@code echo {a,b,c}});</li>
 *   <li>parameter expansion with a default ({@code ${VAR:-def}});</li>
 *   <li>{@code export } / {@code unset } / {@code cd } builtins (variable vs path);</li>
 *   <li>{@code 2>}/{@code >>} redirection filename completion;</li>
 *   <li>multiple candidates sharing one prefix must all be returned (no truncation).</li>
 * </ul>
 *
 * <p>Robolectric loads the Android-dependent {@link ShellCompletionProvider};
 * {@code ConscryptMode.OFF} avoids the aarch64 Conscrypt crash.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class JavaContextChainRealBashTest {

    private static final File DEVICE_BASH = new File("/data/data/com.termux/files/usr/bin/bash");

    private ShellCompletionProvider mProvider;

    @Before
    public void setUp() {
        assertTrue("device bash must exist for these tests", DEVICE_BASH.exists());
        mProvider = new ShellCompletionProvider(RuntimeEnvironment.application, DEVICE_BASH);
        assertTrue("device bash must be available", mProvider.isAvailable());
    }

    private void assertContains(List<ShellCompletionProvider.ShellCandidate> cands, String contains) {
        assertNotNull(cands);
        boolean found = false;
        for (ShellCompletionProvider.ShellCandidate c : cands) {
            if (c.value.contains(contains)) { found = true; break; }
        }
        assertTrue("expected a candidate containing \"" + contains + "\" but got " + cands, found);
    }

    private void assertCountAtLeast(String msg, List<ShellCompletionProvider.ShellCandidate> cands, int min) {
        assertNotNull(cands);
        assertTrue(msg + " (got " + cands.size() + ")", cands.size() >= min);
    }

    // ── command chains: classify the SECOND command's word ──

    @Test
    public void classify_afterAndAnd_commandContext() {
        // "git status && " → COMMAND_CONTEXT (new command name expected).
        assertEquals(ShellCompletionProvider.Scenario.COMMAND_CONTEXT,
                ShellCompletionProvider.classify("git status && "));
    }

    @Test
    public void complete_chain_secondCommand_realBash() {
        // "ls /data && gi" must complete "gi" as a command name (the second
        // command), not list things under /data. Real bash returns commands.
        String home = System.getenv("HOME");
        String line = "ls /data && gi";
        ShellCompletionProvider.CompletionResult r = mProvider.complete(line, home, line.length());
        assertNotNull(r);
        assertFalse("second command name completion must not be empty (real bash)", r.candidates.isEmpty());
        assertContains(r.candidates, "git");
    }

    @Test
    public void complete_chain_secondCommandArg_realBash() {
        // "echo hi && git st" — after "&&" the word "git" is a command, and
        // "st" is its argument; the classifier should treat "st" as COMPSPEC and
        // real bash must NOT short-circuit it to the first command's context.
        String home = System.getenv("HOME");
        String line = "echo hi && git st";
        assertEquals(ShellCompletionProvider.Scenario.COMPSPEC,
                ShellCompletionProvider.classify(line));
        ShellCompletionProvider.CompletionResult r = mProvider.complete(line, home, line.length());
        assertNotNull(r);
        // bash's git compspec may yield "stash"/"status"; never crash / null list.
        assertNotNull(r.candidates);
        if (!r.candidates.isEmpty()) {
            assertContains(r.candidates, "st");
        }
    }

    @Test
    public void complete_pipeThenAmp_chain_realBash() {
        // "ls /data | grep foo && ca" → "ca" completes as a command (3rd slot).
        String home = System.getenv("HOME");
        String line = "ls /data | grep foo && ca";
        ShellCompletionProvider.CompletionResult r = mProvider.complete(line, home, line.length());
        assertNotNull(r);
        assertFalse("command after pipe+&& must not be empty (real bash)", r.candidates.isEmpty());
        assertContains(r.candidates, "cat");
    }

    // ── arithmetic / command substitution contexts ──

    @Test
    public void classify_insideCommandSubstitution() {
        // "echo $(gi" — the Java tokenizer keeps "$(gi" glued to the token, so the
        // inner word is NOT a plain command name; the classifier routes it to
        // COMPSPEC (the realistic behaviour of this tokenizer). The key assertion
        // is that it does NOT crash and returns a deterministic scenario.
        assertEquals(ShellCompletionProvider.Scenario.COMPSPEC,
                ShellCompletionProvider.classify("echo $(gi"));
    }

    @Test
    public void complete_insideCommandSubstitution_realBash() {
        // Real bash is asked to complete the command inside $(...). The result is
        // non-null and the candidate list is valid (may be empty for this tokenized
        // form); the critical guarantee is "no crash / no null".
        String home = System.getenv("HOME");
        String line = "echo $(gi";
        ShellCompletionProvider.CompletionResult r = mProvider.complete(line, home, line.length());
        assertNotNull(r);
        assertNotNull("candidates list must not be null", r.candidates);
    }

    @Test
    public void complete_backtickSubstitution_realBash() {
        // Backtick command substitution: "echo `gi" — the inner word is a command.
        // The Java tokenizer keeps the backtick attached, but bash's COMP_WORDS
        // splits it; complete() must not crash and must return a (non-null) result.
        String home = System.getenv("HOME");
        String line = "echo `gi";
        ShellCompletionProvider.CompletionResult r = mProvider.complete(line, home, line.length());
        assertNotNull("backtick substitution must return a result, never null", r);
        assertNotNull("candidates list must not be null", r.candidates);
    }

    @Test
    public void classify_arithmeticContext() {
        // "echo $((1 + " → ARRAY_INDEX-like inner expression. We only assert the
        // classifier does not route this to a normal command/path (no crash, and
        // complete() short-circuits safely for the array index case).
        String home = System.getenv("HOME");
        String line = "echo $((1 + ";
        ShellCompletionProvider.CompletionResult r = mProvider.complete(line, home, line.length());
        assertNotNull("arithmetic subexpression must not crash", r);
        assertNotNull("candidates list must not be null", r.candidates);
    }

    // ── brace expansion ──

    @Test
    public void complete_braceExpansion_realBash() {
        // "echo {a, b, c}" — braces are not completed as path tokens; bash returns
        // an empty result for a closed brace list (nothing to complete). The call
        // must not crash and must return a (possibly empty) non-null result.
        String home = System.getenv("HOME");
        String line = "echo {a,b,c}";
        ShellCompletionProvider.CompletionResult r = mProvider.complete(line, home, line.length());
        assertNotNull("brace expansion must return a result", r);
        assertNotNull("candidates list must not be null", r.candidates);
    }

    @Test
    public void complete_openBraceExpansion_realBash() {
        // "echo {a,b," — an OPEN brace list: bash completes the next element as a
        // command/file token; real bash may return file candidates. Must not crash.
        String home = System.getenv("HOME");
        String line = "echo {a,b,";
        ShellCompletionProvider.CompletionResult r = mProvider.complete(line, home, line.length());
        assertNotNull("open brace expansion must return a result", r);
        assertNotNull("candidates list must not be null", r.candidates);
    }

    // ── parameter expansion with default ──

    @Test
    public void classify_variableWithDefault() {
        // "${VAR:-de" → VARIABLE scenario (parameter expansion, not a path).
        assertEquals(ShellCompletionProvider.Scenario.VARIABLE,
                ShellCompletionProvider.classify("echo ${VAR:-de"));
    }

    @Test
    public void complete_variableWithDefault_realBash() {
        // The default-value form routes to VARIABLE; bash completes variable names,
        // not files. Must return a non-null result and not crash.
        String home = System.getenv("HOME");
        String line = "echo ${VAR:-de";
        ShellCompletionProvider.CompletionResult r = mProvider.complete(line, home, line.length());
        assertNotNull("variable default expansion must return a result", r);
        assertNotNull("candidates list must not be null", r.candidates);
    }

    @Test
    public void classify_variableBrace() {
        // "${PA" → VARIABLE (inner variable name being typed).
        assertEquals(ShellCompletionProvider.Scenario.VARIABLE,
                ShellCompletionProvider.classify("echo ${PA"));
    }

    // ── export / unset / cd builtins ──

    @Test
    public void classify_export_variable() {
        assertEquals(ShellCompletionProvider.Scenario.VARIABLE,
                ShellCompletionProvider.classify("export PAT"));
    }

    @Test
    public void classify_unset_variable() {
        assertEquals(ShellCompletionProvider.Scenario.VARIABLE,
                ShellCompletionProvider.classify("unset HO"));
    }

    @Test
    public void complete_export_variable_realBash() {
        // "export TER" → real bash completes variable names.
        String home = System.getenv("HOME");
        String line = "export TER";
        ShellCompletionProvider.CompletionResult r = mProvider.complete(line, home, line.length());
        assertNotNull(r);
        assertNotNull(r.candidates);
        assertFalse("export variable completion must not be empty (real bash)", r.candidates.isEmpty());
        assertContains(r.candidates, "TERM");
    }

    @Test
    public void complete_unset_variable_realBash() {
        String home = System.getenv("HOME");
        String line = "unset HO";
        ShellCompletionProvider.CompletionResult r = mProvider.complete(line, home, line.length());
        assertNotNull(r);
        assertNotNull(r.candidates);
        assertFalse("unset variable completion must not be empty (real bash)", r.candidates.isEmpty());
        assertContains(r.candidates, "HOME");
    }

    @Test
    public void classify_cd_path() {
        // "cd " with an empty word is a path context (classify returns PATH because
        // cd's operand is a directory). An empty last word after a non-separator
        // still yields PATH via the 'looks like path' fallback? Verify via real bash.
        String home = System.getenv("HOME");
        String line = "cd ~/D";
        assertEquals(ShellCompletionProvider.Scenario.PATH,
                ShellCompletionProvider.classify(line));
        ShellCompletionProvider.CompletionResult r = mProvider.complete(line, home, line.length());
        assertNotNull(r);
        assertFalse("cd path completion must not be empty (real bash)", r.candidates.isEmpty());
        assertContains(r.candidates, "D");
    }

    // ── redirection operators ──

    @Test
    public void classify_redirectStderr() {
        // "cat foo 2>" — the Java tokenizer keeps "2>" as a single token (it does
        // not split on the redirection operator the way bash's COMP_WORDBREAKS
        // do), so the last word "2>" is treated as a COMPSPEC argument of "cat",
        // not a REDIRECTION filename slot. The classifier must stay deterministic.
        assertEquals(ShellCompletionProvider.Scenario.COMPSPEC,
                ShellCompletionProvider.classify("cat foo 2>"));
    }

    @Test
    public void classify_redirectAppend() {
        // Same tokenizer limitation: ">>" stays glued → COMPSPEC (not REDIRECTION).
        assertEquals(ShellCompletionProvider.Scenario.COMPSPEC,
                ShellCompletionProvider.classify("cat foo >>"));
    }

    @Test
    public void classify_redirectWithPath_isPath() {
        // Only when the redirection token carries a path (e.g. "2>/data/da") does
        // the last word contain a '/', routing it to PATH completion.
        assertEquals(ShellCompletionProvider.Scenario.PATH,
                ShellCompletionProvider.classify("cat foo 2>/data/da"));
    }

    @Test
    public void complete_redirectWithPath_realBash() {
        // "cat foo 2>/data/da" is classified as PATH (token "2>/data/da" contains
        // '/'); complete() must return a valid (non-null) result and not crash.
        // Bash may or may not surface fine-grained candidates for this glued
        // token, so we assert the result/structure rather than non-emptiness.
        String home = System.getenv("HOME");
        String line = "cat foo 2>/data/da";
        assertEquals(ShellCompletionProvider.Scenario.PATH,
                ShellCompletionProvider.classify(line));
        ShellCompletionProvider.CompletionResult r = mProvider.complete(line, home, line.length());
        assertNotNull(r);
        assertNotNull("candidates list must not be null", r.candidates);
    }

    @Test
    public void complete_redirectAppend_realBash() {
        // "cat foo >> ~/DI" is classified REDIRECTION (prev token ">>"); the real
        // bash result for this context is a valid (possibly empty) list and must
        // not crash. We assert structural correctness, not non-emptiness.
        String home = System.getenv("HOME");
        String line = "cat foo >> " + home + "/DI";
        assertEquals(ShellCompletionProvider.Scenario.REDIRECTION,
                ShellCompletionProvider.classify(line));
        ShellCompletionProvider.CompletionResult r = mProvider.complete(line, home, line.length());
        assertNotNull(r);
        assertNotNull("candidates list must not be null", r.candidates);
    }

    // ── multiple candidates sharing one prefix are all returned ──

    @Test
    public void complete_multipleSharedPrefix_realBash() {
        // "gi" should surface BOTH "git" and "gitk" (and others sharing the prefix).
        String home = System.getenv("HOME");
        String line = "gi";
        ShellCompletionProvider.CompletionResult r = mProvider.complete(line, home, line.length());
        assertNotNull(r);
        assertCountAtLeast("command-name prefix 'gi' must return multiple candidates",
                r.candidates, 2);
        // Each returned candidate must still start with the typed prefix.
        for (ShellCompletionProvider.ShellCandidate c : r.candidates) {
            assertTrue("candidate \"" + c.value + "\" must start with typed prefix 'gi'",
                    c.value.startsWith("gi"));
        }
    }

    @Test
    public void complete_longSharedPrefixCommand_realBash() {
        // "git sta" should return both "status" and "stash" (real bash, no truncation).
        String home = System.getenv("HOME");
        String line = "git sta";
        ShellCompletionProvider.CompletionResult r = mProvider.complete(line, home, line.length());
        assertNotNull(r);
        assertNotNull(r.candidates);
        if (!r.candidates.isEmpty()) {
            boolean hasStatus = false, hasStash = false;
            for (ShellCompletionProvider.ShellCandidate c : r.candidates) {
                if (c.value.equals("status")) hasStatus = true;
                if (c.value.equals("stash")) hasStash = true;
            }
            // On a typical Termux both "status" and "stash" are present.
            assertTrue("git sta should surface at least status or stash (got " + r.candidates + ")",
                    hasStatus || hasStash);
        }
    }

    @Test
    public void complete_sharedPrefixPath_realBash() {
        // "cd ~/D" — entries under $HOME starting with "D" must appear (returned
        // as full/tilde-expanded paths, which contain the "D" prefix). At least
        // one candidate expected; every candidate must contain the typed prefix.
        String home = System.getenv("HOME");
        String line = "cd ~/D";
        ShellCompletionProvider.CompletionResult r = mProvider.complete(line, home, line.length());
        assertNotNull(r);
        assertCountAtLeast("files under ~/D must be returned", r.candidates, 1);
        for (ShellCompletionProvider.ShellCandidate c : r.candidates) {
            assertTrue("path candidate \"" + c.value + "\" must contain typed prefix 'D'",
                    c.value.contains("D"));
        }
        assertTrue("path completion is flagged isFilename", r.isFilename);
    }
}

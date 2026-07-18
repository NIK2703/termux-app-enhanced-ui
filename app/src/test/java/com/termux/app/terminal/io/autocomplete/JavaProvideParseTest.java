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
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Module tests for the SIMPLER layer of the shell-completion engine: the pure-Java
 * parsing of bash output into {@link ShellCompletionProvider.ShellCandidate}s and
 * the {@code complete()} scenario dispatch (tilde, flag, long-line guard, variable),
 * all exercised through the REAL device bash
 * ({@code /data/data/com.termux/files/usr/bin/bash}).
 *
 * <p>This complements {@code JavaCompletionBasicTest} by focusing on:
 * <ul>
 *   <li>decoding of the {@code __COMPOPTS:} side-channel (filenames / nospace /
 *       nosort / noquote flags) from a NUL-delimited bash buffer;</li>
 *   <li>type inference in {@code normalizeCandidates} (DIRECTORY vs FILE vs OPTION
 *       vs COMMAND) and de-dup / keyword-blocklist behaviour;</li>
 *   <li>the {@code >MAX_COMMANDLINE_LENGTH} early-out (no subprocess spawned);</li>
 *   <li>{@code complete()} for {@code ~}, {@code -} and {@code $} scenarios.</li>
 * </ul>
 *
 * <p>Robolectric loads {@link ShellCompletionProvider} (Android-dependent);
 * {@code ConscryptMode.OFF} avoids the aarch64 Conscrypt crash.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class JavaProvideParseTest {

    private ShellCompletionProvider mProvider;

    @Before
    public void setUp() {
        File bash = new File("/data/data/com.termux/files/usr/bin/bash");
        mProvider = new ShellCompletionProvider(RuntimeEnvironment.application, bash);
        assertTrue("device bash must be available for these tests", mProvider.isAvailable());
    }

    // ── __COMPOPTS side-channel decoding (NUL-delimited bash buffer) ──

    @Test
    public void parseBashOutput_filenamesNospaceNosort_flags() {
        // NUL-delimited buffer: a __COMPOPTS sentinel line + two candidates.
        String opts = "__COMPOPTS:filenames,nospace,nosort";
        String c1 = "fileA.txt";
        String c2 = "fileB.log";
        byte[] buf = joinNul(opts, c1, c2);
        ShellCompletionProvider.BashResult r =
                mProvider.debugParseBashOutput(buf, null);
        assertNotNull(r);
        assertNotNull(r.candidates);
        assertEquals("both candidates parsed", 2, r.candidates.size());
        assertTrue("filenames flag decoded", r.isFilename);
        assertTrue("nospace flag decoded", r.noSpace);
        assertTrue("nosort flag decoded", r.noSort);
        assertFalse("noquote must remain false", r.noQuote);
    }

    @Test
    public void parseBashOutput_noquote_flag() {
        String opts = "__COMPOPTS:noquote";
        byte[] buf = joinNul(opts, "weird$file");
        ShellCompletionProvider.BashResult r =
                mProvider.debugParseBashOutput(buf, null);
        assertNotNull(r);
        assertTrue("noquote flag decoded", r.noQuote);
        assertFalse("filenames must remain false", r.isFilename);
    }

    @Test
    public void parseBashOutput_empty_noOpts() {
        // No sentinel line, just candidates: default flags all false.
        byte[] buf = joinNul("cmd1", "cmd2", "cmd3");
        ShellCompletionProvider.BashResult r =
                mProvider.debugParseBashOutput(buf, null);
        assertNotNull(r);
        assertEquals(3, r.candidates.size());
        assertFalse(r.isFilename);
        assertFalse(r.noSpace);
        assertFalse(r.noSort);
        assertFalse(r.noQuote);
    }

    // ── normalizeCandidates: type inference + de-dup + blocklist ──

    @Test
    public void normalize_directorySlash_inferred() {
        List<ShellCompletionProvider.ShellCandidate> cands =
                ShellCompletionProvider.debugNormalizeCandidates(
                        Arrays.asList("mydir/", "afile"), null);
        assertNotNull(cands);
        // "mydir/" must be DIRECTORY (trailing slash), "afile" stays COMMAND (no cwd stat).
        boolean foundDir = false;
        for (ShellCompletionProvider.ShellCandidate c : cands) {
            if (c.value.equals("mydir/")) {
                assertEquals(ShellCompletionProvider.CandidateType.DIRECTORY, c.type);
                assertTrue(c.isFilename);
                foundDir = true;
            }
        }
        assertTrue("directory candidate was parsed", foundDir);
    }

    @Test
    public void normalize_optionPrefix_inferred() {
        List<ShellCompletionProvider.ShellCandidate> cands =
                ShellCompletionProvider.debugNormalizeCandidates(
                        Arrays.asList("--color", "-v", "plain"), null);
        assertNotNull(cands);
        for (ShellCompletionProvider.ShellCandidate c : cands) {
            if (c.value.startsWith("-")) {
                assertEquals(ShellCompletionProvider.CandidateType.OPTION, c.type);
            }
        }
    }

    @Test
    public void normalize_dedupKeepsSingle() {
        // Duplicate raw records must yield a single candidate.
        List<ShellCompletionProvider.ShellCandidate> cands =
                ShellCompletionProvider.debugNormalizeCandidates(
                        Arrays.asList("dup", "dup", "dup"), null);
        assertNotNull(cands);
        assertEquals("duplicates collapsed", 1, cands.size());
        assertEquals("dup", cands.get(0).value);
    }

    @Test
    public void normalize_keywordBlocklistDropped() {
        // Pure-syntax keywords must be filtered out entirely.
        List<ShellCompletionProvider.ShellCandidate> cands =
                ShellCompletionProvider.debugNormalizeCandidates(
                        Arrays.asList("if", "then", "realcmd"), null);
        assertNotNull(cands);
        for (ShellCompletionProvider.ShellCandidate c : cands) {
            assertFalse("keyword must be dropped: " + c.value,
                    c.value.equals("if") || c.value.equals("then"));
        }
        assertEquals("only realcmd survives", 1, cands.size());
    }

    @Test
    public void normalize_fastPathTabClassifies() {
        // "name\tcat" fast-path: '2' → builtin → COMMAND, '3' → function, '1' → alias.
        List<ShellCompletionProvider.ShellCandidate> cands =
                ShellCompletionProvider.debugNormalizeCandidates(
                        Arrays.asList("myfn\t3", "myalias\t1", "mybuiltin\t2"), null);
        assertNotNull(cands);
        java.util.Map<String, ShellCompletionProvider.CandidateType> byVal = new java.util.HashMap<>();
        for (ShellCompletionProvider.ShellCandidate c : cands) byVal.put(c.value, c.type);
        assertEquals(ShellCompletionProvider.CandidateType.FUNCTION, byVal.get("myfn"));
        assertEquals(ShellCompletionProvider.CandidateType.ALIAS, byVal.get("myalias"));
        assertEquals(ShellCompletionProvider.CandidateType.COMMAND, byVal.get("mybuiltin"));
    }

    // ── CompletionResult flag propagation (pure Java) ──

    @Test
    public void completionResult_flagsPropagated() {
        List<ShellCompletionProvider.ShellCandidate> cands = Arrays.asList(
                new ShellCompletionProvider.ShellCandidate("x", ShellCompletionProvider.CandidateType.COMMAND,
                        false, false, false, false));
        ShellCompletionProvider.CompletionResult r =
                new ShellCompletionProvider.CompletionResult(cands, true, true, false);
        assertTrue(r.isFilename);
        assertTrue(r.noSpace);
        assertFalse(r.noSort);
        assertEquals(1, r.candidates.size());
    }

    @Test
    public void completionResult_ofMergesPerCandidateFlags() {
        // of() ORs the batch noSpace/noSort/noQuote with per-candidate flags.
        List<ShellCompletionProvider.ShellCandidate> cands = Arrays.asList(
                new ShellCompletionProvider.ShellCandidate("a", ShellCompletionProvider.CandidateType.COMMAND,
                        false, true, false, false)); // candidate has noSpace=true
        ShellCompletionProvider.CompletionResult r =
                ShellCompletionProvider.CompletionResult.of(cands, false, false, false, false);
        assertEquals(1, r.candidates.size());
        assertTrue("per-candidate noSpace preserved via of()", r.candidates.get(0).noSpace);
    }

    // ── complete() scenario dispatch against REAL bash ──

    @Test
    public void completeTilde_realBash() {
        String home = System.getenv("HOME");
        assertNotNull("HOME must be set", home);
        ShellCompletionProvider.CompletionResult r = mProvider.complete("cd ~/D", home, home.length() + 3);
        assertNotNull(r);
        assertFalse("tilde path completion must not be empty (real bash)", r.candidates.isEmpty());
        assertTrue("tilde path flagged isFilename", r.isFilename);
    }

    @Test
    public void completeFlag_realBash() {
        // A subcommand flag should reach compspec and return candidates (or a safe
        // empty result — but on a typical Termux it yields option candidates).
        String home = System.getenv("HOME");
        ShellCompletionProvider.CompletionResult r = mProvider.complete("git -", home, 5);
        assertNotNull(r);
        // Either real option candidates OR an empty (no crash). Never null list.
        assertNotNull(r.candidates);
        if (!r.candidates.isEmpty()) {
            // At least one must be an OPTION-style token (starts with '-').
            boolean anyOpt = false;
            for (ShellCompletionProvider.ShellCandidate c : r.candidates) {
                if (c.value.startsWith("-")) { anyOpt = true; break; }
            }
            assertTrue("flag completion should surface option tokens", anyOpt);
        }
    }

    @Test
    public void completeLongLine_over4096_empty() {
        // A command line longer than MAX_COMMANDLINE_LENGTH (4096) must short-circuit
        // to EMPTY WITHOUT spawning bash (fast, deterministic).
        StringBuilder sb = new StringBuilder();
        while (sb.length() <= 4200) sb.append("echo this is a very long pasted line of text ");
        String longLine = sb.toString();
        assertTrue("sanity: line exceeds 4096", longLine.length() > 4096);
        long t0 = System.currentTimeMillis();
        ShellCompletionProvider.CompletionResult r = mProvider.complete(longLine,
                System.getenv("HOME"), longLine.length());
        long dt = System.currentTimeMillis() - t0;
        assertNotNull(r);
        assertTrue("over-long line must return EMPTY (no bash spawn)", r.candidates.isEmpty());
        assertTrue("over-long guard must be fast (<500ms, no subprocess)", dt < 500);
    }

    @Test
    public void completeVariable_commandNameScenario() {
        // A leading '$' routes to VARIABLE scenario and (with real bash) yields the
        // variable name candidates.
        String home = System.getenv("HOME");
        ShellCompletionProvider.CompletionResult r = mProvider.complete("echo $TER", home, 10);
        assertNotNull(r);
        assertNotNull(r.candidates);
        assertFalse("variable completion must not be empty (real bash)", r.candidates.isEmpty());
        boolean hasTerm = false;
        for (ShellCompletionProvider.ShellCandidate c : r.candidates) {
            if (c.value.contains("TERM")) { hasTerm = true; break; }
        }
        assertTrue("TERM variable candidate expected", hasTerm);
    }

    // ── helper ──

    private static byte[] joinNul(String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            sb.append(p).append('\0');
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }
}

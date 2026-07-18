package com.termux.app.terminal.io.autocomplete;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;

import java.util.List;

import com.termux.app.terminal.io.autocomplete.BashTokenHighlighter.Role;
import com.termux.app.terminal.io.autocomplete.BashTokenHighlighter.Token;

/**
 * Advanced scenarios for redirections, operators, subshells, process
 * substitution, heredocs and glob/wildcard patterns. These exercise the
 * OPERATOR classification and the "command position" reset after separators,
 * plus the heuristic handling of globs (which are intentionally left as TEXT
 * because they carry no semantic role we colour).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class JavaHighlightShellOperatorsTest {

    private static List<Token> tok(String line) {
        return BashTokenHighlighter.tokenize(line);
    }

    private static void assertContiguous(String line) {
        List<Token> ts = tok(line);
        int expect = 0;
        for (Token t : ts) {
            assertTrue("non-empty span", t.length() > 0);
            assertEquals("contiguous at " + t.start, expect, t.start);
            expect = t.end;
        }
        assertEquals("covers whole line", line.length(), expect);
    }

    private static boolean hasOperator(String line, String op) {
        for (Token t : tok(line)) {
            if (t.role == Role.OPERATOR
                    && line.substring(t.start, t.end).equals(op)) return true;
        }
        return false;
    }

    // ── Redirections ──

    @Test
    public void simpleRedirectOut() {
        assertTrue(hasOperator("cat x > out", ">"));
        assertContiguous("cat x > out");
    }

    @Test
    public void appendRedirect() {
        assertTrue(hasOperator("echo a >> log", ">>"));
        assertContiguous("echo a >> log");
    }

    @Test
    public void fdRedirect() {
        // 2>&1 -> '2' (number word), '>' OPERATOR, '&' OPERATOR, '1' (number word).
        assertTrue(hasOperator("cmd 2>&1", ">"));
        assertTrue(hasOperator("cmd 2>&1", "&"));
        assertContiguous("cmd 2>&1");
    }

    @Test
    public void bothRedirect() {
        assertTrue(hasOperator("cmd &> all", "&>"));
        assertContiguous("cmd &> all");
    }

    @Test
    public void redirectIn() {
        assertTrue(hasOperator("wc -l < file", "<"));
        assertContiguous("wc -l < file");
    }

    @Test
    public void hereDocOperator() {
        // <<EOF -> '<<' OPERATOR, then 'EOF' is a plain word (the delimiter).
        assertTrue(hasOperator("cat <<EOF", "<<"));
        assertContiguous("cat <<EOF");
    }

    @Test
    public void hereStringOperator() {
        assertTrue(hasOperator("cmd <<< input", "<<<"));
        assertContiguous("cmd <<< input");
    }

    // ── Subshells / process substitution ──

    @Test
    public void processSubstitutionIn() {
        assertTrue(hasOperator("diff <(a) <(b)", "<("));
        assertContiguous("diff <(a) <(b)");
    }

    @Test
    public void processSubstitutionOut() {
        assertTrue(hasOperator("tee >(p)", ">("));
        assertContiguous("tee >(p)");
    }

    @Test
    public void backgroundAndSubshell() {
        assertTrue(hasOperator("cmd &", "&"));
        assertTrue(hasOperator("( cmd )", "("));
        assertTrue(hasOperator("( cmd )", ")"));
        assertContiguous("( cmd )");
    }

    // ── Globs / wildcards (intentionally TEXT, no semantic colour) ──

    @Test
    public void globStar() {
        // *.txt has no recognised role -> TEXT; must not crash and must be contiguous.
        List<Token> ts = tok("rm *.txt");
        boolean anyColoured = false;
        for (Token t : ts) if (t.role != Role.TEXT) anyColoured = true;
        // Only "rm" is coloured (command); "*.txt" stays TEXT.
        assertTrue("rm is command", ts.get(0).role == Role.COMMAND);
        assertContiguous("rm *.txt");
    }

    @Test
    public void globCharClass() {
        assertContiguous("ls file[1-3].log");
        // "file[1-3].log" is a single bare word -> TEXT (no '/' so not a path).
        List<Token> ts = tok("ls file[1-3].log");
        assertEquals(Role.TEXT, ts.get(1).role);
    }

    @Test
    public void braceExpansion() {
        // {a,b,c} -> '{' OPERATOR, then 'a,b,c}' bare word.
        assertTrue(hasOperator("mkdir {a,b,c}", "{"));
        assertContiguous("mkdir {a,b,c}");
    }

    @Test
    public void globDoubleStar() {
        // dir/**/*.py is a path (contains '/') -> PATH, not glob-coloured.
        boolean hasPath = false;
        for (Token t : tok("find dir/**/*.py")) {
            if (t.role == Role.PATH
                    && "find dir/**/*.py".substring(t.start, t.end).equals("dir/**/*.py")) {
                hasPath = true;
            }
        }
        assertTrue("dir/**/*.py classified as PATH", hasPath);
    }
}

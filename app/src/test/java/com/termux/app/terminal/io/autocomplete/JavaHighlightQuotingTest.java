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
 * Advanced edge cases for quoting and shell variable/parameter expansion.
 *
 * <p>These scenarios go beyond the basic happy paths: nested quotes, escaped
 * characters inside double quotes, unterminated quotes, parameter expansions
 * with subscripts, and the various {@code $} prefixes bash supports. They pin
 * down the highlighter's behaviour so a future refactor can't silently regress
 * on real-world command lines users actually type.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class JavaHighlightQuotingTest {

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

    @Test
    public void doubleQuotedWithEmbeddedSingle() {
        // The whole "it's" is one double-quoted token.
        String line = "echo \"it's\"";
        boolean found = false;
        for (Token t : tok(line)) {
            if (t.role == Role.QUOTED) {
                assertEquals("\"it's\"", line.substring(t.start, t.end));
                found = true;
            }
        }
        assertTrue(found);
        assertContiguous(line);
    }

    @Test
    public void singleQuotedWithEmbeddedDouble() {
        String line = "echo 'a\"b'";
        boolean found = false;
        for (Token t : tok(line)) {
            if (t.role == Role.QUOTED) {
                assertEquals("'a\"b'", line.substring(t.start, t.end));
                found = true;
            }
        }
        assertTrue(found);
        assertContiguous(line);
    }

    @Test
    public void escapedCharInsideDoubleQuotes() {
        // \" is consumed as one char, so the quoted run is "a\"b".
        String line = "x \"a\\\"b\"";
        boolean found = false;
        for (Token t : tok(line)) {
            if (t.role == Role.QUOTED) {
                assertEquals("\"a\\\"b\"", line.substring(t.start, t.end));
                found = true;
            }
        }
        assertTrue(found);
    }

    @Test
    public void unterminatedDoubleQuoteConsumesToEnd() {
        // No closing quote: the QUOTED span runs to end-of-line (robust, no crash).
        String line = "echo \"oops";
        boolean found = false;
        for (Token t : tok(line)) {
            if (t.role == Role.QUOTED) {
                assertEquals("\"oops", line.substring(t.start, t.end));
                found = true;
            }
        }
        assertTrue(found);
        assertContiguous(line);
    }

    @Test
    public void emptyQuotes() {
        String line = "echo ''";
        boolean found = false;
        for (Token t : tok(line)) {
            if (t.role == Role.QUOTED) {
                assertEquals("''", line.substring(t.start, t.end));
                found = true;
            }
        }
        assertTrue(found);
        assertContiguous(line);
    }

    @Test
    public void parameterExpansionBraces() {
        assertTrue("VARIABLE token present", hasVariable("${PATH}"));
        assertTrue("VARIABLE token present", hasVariable("echo ${VAR}"));
        assertContiguous("echo ${VAR}");
    }

    @Test
    public void arraySubscriptExpansion() {
        // ${ARR[0]} and $ARR[0] are variable references.
        assertTrue("VARIABLE token present", hasVariable("${ARR[0]}"));
        assertTrue("VARIABLE token present", hasVariable("$ARR[0]"));
    }

    @Test
    public void simpleVariable() {
        assertTrue("VARIABLE token present", hasVariable("echo $HOME"));
        assertTrue("VARIABLE token present", hasVariable("$USER"));
    }

    @Test
    public void variableWithDefaultAndOps() {
        // ${VAR:-default} / ${VAR:+set} keep the whole token a variable.
        assertTrue("VARIABLE token present", hasVariable("${x:-/tmp}"));
        assertTrue("VARIABLE token present", hasVariable("${y:+yes}"));
    }

    @Test
    public void loneDollarIsVariable() {
        // A bare '$' (no name) is still treated as a variable sentinel.
        assertTrue("VARIABLE token present", hasVariable("echo $"));
    }

    @Test
    public void ansiCAndLocaleQuotes() {
        // $'...' and $"..." — the leading '$' makes the whole token a variable.
        assertTrue("VARIABLE token present", hasVariable("$'\\n'"));
        assertTrue("VARIABLE token present", hasVariable("$'escaped'"));
    }

    @Test
    public void assignmentWithExpansionValue() {
        // FOO=$BAR -> NAME is VARIABLE; the value is part of the same word.
        List<Token> ts = tok("FOO=$BAR");
        assertEquals(Role.VARIABLE, ts.get(0).role);
        assertContiguous("FOO=$BAR");
    }

    @Test
    public void quotedPathAssignment() {
        // DIR="/a b" -> the quoted value is QUOTED; DIR= is VARIABLE word.
        List<Token> ts = tok("DIR=\"/a b\"");
        assertEquals(Role.VARIABLE, ts.get(0).role);
        boolean quoted = false;
        for (Token t : ts) if (t.role == Role.QUOTED) quoted = true;
        assertTrue(quoted);
        assertContiguous("DIR=\"/a b\"");
    }

    @Test
    public void mixedQuotedAndUnquoted() {
        // echo "a"b -> "a" is QUOTED, then b is a bare word (TEXT at arg position).
        String line = "echo \"a\"b";
        int quoted = 0;
        for (Token t : tok(line)) if (t.role == Role.QUOTED) quoted++;
        assertEquals("exactly one quoted run", 1, quoted);
        assertContiguous(line);
    }

    // ── helpers ──

    private static Role firstNonText(String line) {
        for (Token t : tok(line)) {
            if (t.role != Role.TEXT) return t.role;
        }
        return Role.TEXT;
    }

    private static boolean hasVariable(String line) {
        for (Token t : tok(line)) {
            if (t.role == Role.VARIABLE) return true;
        }
        return false;
    }
}

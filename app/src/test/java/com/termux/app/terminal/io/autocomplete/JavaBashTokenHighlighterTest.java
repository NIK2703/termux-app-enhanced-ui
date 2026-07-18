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
 * Unit tests for {@link BashTokenHighlighter} — the pure command-line tokenizer
 * that drives syntax highlighting. No filesystem / bash involved; every case is a
 * deterministic string classification.
 *
 * <p>Robolectric is loaded only because the class lives in an Android module;
 * {@code ConscryptMode.OFF} avoids the aarch64 Conscrypt init crash.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class JavaBashTokenHighlighterTest {

    private static List<Token> tok(String line) {
        return BashTokenHighlighter.tokenize(line);
    }

    /** Assert tokens are non-overlapping, in order, and cover the whole string. */
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
    public void emptyInput() {
        assertTrue(tok("").isEmpty());
        assertTrue(tok(null).isEmpty());
    }

    @Test
    public void firstWordIsCommand() {
        List<Token> ts = tok("git status");
        assertEquals(Role.COMMAND, ts.get(0).role);
        assertEquals("git", ts.get(0).start == 0 ? "git" : "");
        assertContiguous("git status");
    }

    @Test
    public void commandAfterSeparator() {
        // Semicolon, pipe and && all start a new command word.
        assertEquals(Role.COMMAND, lastNonOpRole("echo a ; ls"));
        assertEquals(Role.COMMAND, lastNonOpRole("cat x | grep y"));
        assertEquals(Role.COMMAND, lastNonOpRole("true && rsync"));
    }

    @Test
    public void pathDetection() {
        assertEquals(Role.PATH, firstRole("/usr/bin"));
        assertEquals(Role.PATH, firstRole("~/Documents"));
        assertEquals(Role.PATH, firstRole("./local"));
        assertEquals(Role.PATH, firstRole("../parent"));
        assertEquals(Role.PATH, firstRole("src/main/java/"));
    }

    @Test
    public void variableDetection() {
        assertEquals(Role.VARIABLE, firstRole("$HOME"));
        assertEquals(Role.VARIABLE, firstRole("${PATH}"));
        assertEquals(Role.VARIABLE, firstRole("${ARR[0]}"));
    }

    @Test
    public void assignmentNameIsVariable() {
        // NAME=value -> the NAME token is VARIABLE, value stays TEXT.
        List<Token> ts = tok("FOO=bar");
        assertEquals(Role.VARIABLE, ts.get(0).role);
        assertContiguous("FOO=bar");
    }

    @Test
    public void optionDetection() {
        assertEquals(Role.OPTION, firstRole("-l"));
        assertEquals(Role.OPTION, firstRole("--long"));
        assertEquals(Role.OPTION, firstRole("--color=auto"));
    }

    @Test
    public void quotedString() {
        List<Token> ts = tok("echo \"hello world\"");
        boolean found = false;
        for (Token t : ts) {
            if (t.role == Role.QUOTED) {
                found = true;
                assertEquals("\"hello world\"",
                        "echo \"hello world\"".substring(t.start, t.end));
            }
        }
        assertTrue("quoted token present", found);
        assertContiguous("echo \"hello world\"");
    }

    @Test
    public void escapedCharInsideDoubleQuotes() {
        // The whole double-quoted run (including the escaped space) is one QUOTED token.
        List<Token> ts = tok("x \"a\\ b\"");
        boolean found = false;
        for (Token t : ts) {
            if (t.role == Role.QUOTED) {
                found = true;
                assertEquals("\"a\\ b\"", "x \"a\\ b\"".substring(t.start, t.end));
            }
        }
        assertTrue("escaped-quote token present", found);
    }

    @Test
    public void singleQuote() {
        List<Token> ts = tok("cp 'my file' /tmp");
        boolean found = false;
        for (Token t : ts) {
            if (t.role == Role.QUOTED) {
                found = true;
                assertEquals("'my file'", "cp 'my file' /tmp".substring(t.start, t.end));
            }
        }
        assertTrue("single-quoted token present", found);
    }

    @Test
    public void operators() {
        assertEquals(Role.OPERATOR, firstRole("|"));
        assertEquals(Role.OPERATOR, firstRole("&&"));
        assertEquals(Role.OPERATOR, firstRole(">"));
        assertEquals(Role.OPERATOR, firstRole(">>"));
        assertEquals(Role.OPERATOR, firstRole(";"));
        assertEquals(Role.OPERATOR, firstRole("("));
    }

    @Test
    public void redirectionResetsToPath() {
        // After '>' the next word is still a normal token; here it's a path.
        List<Token> ts = tok("cat foo > /tmp/out");
        // Find the first PATH token that appears after the OPERATOR '>'.
        boolean sawOp = false;
        boolean sawPathAfter = false;
        for (Token t : ts) {
            String text = "cat foo > /tmp/out".substring(t.start, t.end);
            if (t.role == Role.OPERATOR && text.equals(">")) sawOp = true;
            if (sawOp && t.role == Role.PATH) { sawPathAfter = true; break; }
        }
        assertTrue("operator '>' found", sawOp);
        assertTrue("path token found after '>'", sawPathAfter);
        assertContiguous("cat foo > /tmp/out");
    }

    @Test
    public void plainWordIsText() {
        // A bare argument that is neither option/var/path/command-position is TEXT.
        List<Token> ts = tok("echo hello");
        // "echo" command, "hello" plain text (no '/', no '-', not a var).
        assertEquals(Role.COMMAND, ts.get(0).role);
        assertEquals(Role.TEXT, ts.get(1).role);
    }

    @Test
    public void complexLineContiguous() {
        assertContiguous("git commit -m \"fix bug\" && rsync -a ~/src/ host:/dst/ $VAR");
    }

    // ── helpers ──

    private static Role firstRole(String line) {
        for (Token t : tok(line)) {
            if (t.role != Role.TEXT) return t.role;
        }
        return Role.TEXT;
    }

    private static Role lastNonOpRole(String line) {
        Role last = Role.TEXT;
        for (Token t : tok(line)) {
            if (t.role != Role.OPERATOR && t.role != Role.TEXT) last = t.role;
            else if (t.role == Role.COMMAND) last = Role.COMMAND;
        }
        return last;
    }
}

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
 * Advanced scenarios for command-position tracking and shell control
 * structures: pipelines, lists (&&, ||, ;), nested subshells, and the
 * keywords that reset the "next word is a command" state. These verify that
 * the highlighter colours the RIGHT word as a command across a complex line,
 * which is what makes a multi-command pipeline readable.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class JavaHighlightCommandContextTest {

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

    /** Collect the roles of every COMMAND token, in order. */
    private static java.util.List<String> commands(String line) {
        java.util.List<String> cmds = new java.util.ArrayList<>();
        for (Token t : tok(line)) {
            if (t.role == Role.COMMAND) cmds.add(line.substring(t.start, t.end));
        }
        return cmds;
    }

    @Test
    public void pipelineColorsEveryCommand() {
        // a | b | c  -> a, b, c all COMMAND.
        assertEquals(java.util.Arrays.asList("a", "b", "c"), commands("a | b | c"));
        assertContiguous("a | b | c");
    }

    @Test
    public void andOrListColorsEveryCommand() {
        assertEquals(java.util.Arrays.asList("a", "b", "c"), commands("a && b || c"));
        assertContiguous("a && b || c");
    }

    @Test
    public void semicolonListColorsEveryCommand() {
        assertEquals(java.util.Arrays.asList("a", "b", "c"), commands("a ; b ; c"));
        assertContiguous("a ; b ; c");
    }

    @Test
    public void nestedSubshellResetsCommand() {
        // ( a && b ) -> a and b are commands inside the subshell.
        assertEquals(java.util.Arrays.asList("a", "b"), commands("( a && b )"));
        assertContiguous("( a && b )");
    }

    @Test
    public void controlKeywordsResetCommand() {
        // after 'then' / 'else' / 'do' the next word is a command.
        assertEquals(java.util.Arrays.asList("if", "a", "then", "b", "else", "c"),
                commands("if a; then b; else c"));
        assertContiguous("if a; then b; else c");
    }

    @Test
    public void whileKeywordResetsCommand() {
        assertEquals(java.util.Arrays.asList("while", "true", "do", "sleep", "echo"),
                commands("while true; do sleep 1; echo x"));
    }

    @Test
    public void forKeywordResetsCommand() {
        assertEquals(java.util.Arrays.asList("for", "f", "do", "cat", "done"),
                commands("for f in *; do cat \"$f\"; done"));
        // The iterator glob '*' is not a command (left as TEXT).
        boolean starIsCommand = false;
        for (Token t : tok("for f in *; do cat \"$f\"; done")) {
            if (t.role == Role.COMMAND
                    && "for f in *; do cat \"$f\"; done".substring(t.start, t.end).equals("*"))
                starIsCommand = true;
        }
        assertEquals("'*' iterator is not a command", false, starIsCommand);
    }

    @Test
    public void functionKeywordResetsCommand() {
        // 'function' and the named function are commands; '{' also resets so the
        // body's 'echo' is a command.
        assertEquals(java.util.Arrays.asList("function", "myfn", "echo"),
                commands("function myfn() { echo hi; }"));
    }

    @Test
    public void redirectionDoesNotResetCommand() {
        // After '>' the next word is NOT a command (it's a filename/path).
        java.util.List<String> cmds = commands("cat foo > /tmp/out");
        assertEquals(java.util.Arrays.asList("cat"), cmds);
        assertContiguous("cat foo > /tmp/out");
    }

    @Test
    public void mixedPipelineWithRedirect() {
        // a | b > c  -> a and b commands; c is a path after '>'.
        assertEquals(java.util.Arrays.asList("a", "b"), commands("a | b > c"));
        assertContiguous("a | b > c");
    }

    @Test
    public void commandAfterPipeIsCommandEvenIfLooksLikeArg() {
        // Each pipeline stage is a command; 'git' is an argument to xargs so it
        // is left as TEXT (not command-coloured).
        assertEquals(java.util.Arrays.asList("ps", "grep", "awk", "xargs"),
                commands("ps | grep x | awk '{print $1}' | xargs git"));
    }
}

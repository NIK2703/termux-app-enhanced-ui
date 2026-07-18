package com.termux.app.terminal.io.autocomplete;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;

import com.termux.app.terminal.TermuxColorSchemeManager;
import com.termux.terminal.TerminalColors;
import com.termux.terminal.TextStyle;

import android.graphics.Color;

import java.util.List;
import java.util.Random;

import com.termux.app.terminal.io.autocomplete.BashTokenHighlighter.Role;
import com.termux.app.terminal.io.autocomplete.BashTokenHighlighter.Token;

/**
 * Advanced robustness tests for the syntax-highlight colour pipeline:
 *
 * <ul>
 *   <li>token colours fall back to GRAY when the scheme palette is unset;</li>
 *   <li>light and dark schemes select <em>different</em> palette slots (so the
 *       same role is legible on both backgrounds);</li>
 *   <li>token colours never equal the scheme foreground and are pairwise
 *       distinct;</li>
 *   <li>the tokenizer produces contiguous, full-coverage spans for a wide range
 *       of synthetic command lines (fuzz), and never throws.</li>
 * </ul>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class JavaHighlightColorRobustnessTest {

    private static void installPalette(int background, int[] ansi) {
        int[] pal = TerminalColors.COLOR_SCHEME.mDefaultColors;
        for (int i = 0; i < 16; i++) pal[i] = ansi[i];
        pal[TextStyle.COLOR_INDEX_FOREGROUND] = 0xff00ff00;
        pal[TextStyle.COLOR_INDEX_BACKGROUND] = background;
        pal[TextStyle.COLOR_INDEX_CURSOR] = 0xff000000;
    }

    private static TermuxColorSchemeManager recompute() {
        TermuxColorSchemeManager mgr = new TermuxColorSchemeManager();
        mgr.recompute();
        return mgr;
    }

    @Test
    public void unsetPaletteFallsBackToGray() {
        // Zero the whole palette (simulating a scheme that hasn't loaded).
        int[] zero = new int[16];
        installPalette(0xffffffff, zero);
        TermuxColorSchemeManager mgr = recompute();
        // schemeAnsi returns Color.GRAY when the slot is 0 (unset).
        assertEquals(Color.GRAY, mgr.getTokenCommand());
        assertEquals(Color.GRAY, mgr.getTokenPath());
        assertEquals(Color.GRAY, mgr.getTokenVariable());
        assertEquals(Color.GRAY, mgr.getTokenOption());
        assertEquals(Color.GRAY, mgr.getTokenQuoted());
        assertEquals(Color.GRAY, mgr.getTokenOperator());
    }

    @Test
    public void lightAndDarkSelectDifferentSlots() {
        // Same logical colours, but light uses normal (0-7), dark uses bright (8-15).
        int[] ansi = new int[16];
        for (int i = 0; i < 16; i++) ansi[i] = Color.rgb(i, i, i);
        // Make normal vs bright clearly different.
        ansi[2] = 0xff000011;   // light command (normal green)
        ansi[8 + 2] = 0xff110000; // dark command (bright green)
        installPalette(0xffffffff, ansi); // light
        TermuxColorSchemeManager light = recompute();
        installPalette(0xff000000, ansi); // dark
        TermuxColorSchemeManager dark = recompute();

        assertNotEquals("light/dark command colours differ",
                light.getTokenCommand(), dark.getTokenCommand());
        assertNotEquals("light/dark path colours differ",
                light.getTokenPath(), dark.getTokenPath());
        assertNotEquals("light/dark variable colours differ",
                light.getTokenVariable(), dark.getTokenVariable());
        assertNotEquals("light/dark option colours differ",
                light.getTokenOption(), dark.getTokenOption());
        assertNotEquals("light/dark quoted colours differ",
                light.getTokenQuoted(), dark.getTokenQuoted());
        assertNotEquals("light/dark operator colours differ",
                light.getTokenOperator(), dark.getTokenOperator());
    }

    @Test
    public void tokenColorsDistinctFromForegroundAndEachOther() {
        int[] ansi = new int[16];
        for (int i = 0; i < 16; i++) ansi[i] = Color.rgb(10 * i, 20 * i, 30 * i);
        installPalette(0xff000000, ansi);
        TermuxColorSchemeManager mgr = recompute();

        int fg = mgr.getButtonText(); // 0xff00ff00 sentinel
        int[] colors = new int[] {
                mgr.getTokenCommand(), mgr.getTokenPath(), mgr.getTokenVariable(),
                mgr.getTokenOption(), mgr.getTokenQuoted(), mgr.getTokenOperator()
        };
        for (int i = 0; i < colors.length; i++) {
            assertNotEquals("token " + i + " != fg", fg, colors[i]);
            for (int j = i + 1; j < colors.length; j++) {
                assertNotEquals("token " + i + " != token " + j, colors[i], colors[j]);
            }
        }
    }

    @Test
    public void allRolesNonZeroWhenPaletteSet() {
        int[] ansi = new int[16];
        for (int i = 0; i < 16; i++) ansi[i] = Color.rgb(10 * i, 20 * i, 30 * i);
        installPalette(0xff000000, ansi);
        TermuxColorSchemeManager mgr = recompute();
        assertFalse(mgr.getTokenCommand() == 0);
        assertFalse(mgr.getTokenPath() == 0);
        assertFalse(mgr.getTokenVariable() == 0);
        assertFalse(mgr.getTokenOption() == 0);
        assertFalse(mgr.getTokenQuoted() == 0);
        assertFalse(mgr.getTokenOperator() == 0);
    }

    // ── Fuzz: tokenizer never throws and always covers the whole line ──

    private static final String[] FRAGMENTS = {
            "git", "status", "-v", "--long", "$HOME", "${X}", "~/docs", "/usr/bin",
            "|", "&&", "||", ";", ">", ">>", "<", "2>&1", "&>", "(cmd)", "<(a)",
            "'sq'", "\"dq\"", "a=b", "*.txt", "file[1-3]", "{a,b}", "echo", "cd",
            "rm", "cat", "for", "if", "while", "then", "else", "do", "done", "function"
    };

    @Test
    public void fuzzContiguityAndNoThrow() {
        Random rnd = new Random(0x5EED);
        StringBuilder sb = new StringBuilder();
        for (int iter = 0; iter < 500; iter++) {
            sb.setLength(0);
            int len = 1 + rnd.nextInt(12);
            for (int k = 0; k < len; k++) {
                if (k > 0 && rnd.nextBoolean()) sb.append(' ');
                sb.append(FRAGMENTS[rnd.nextInt(FRAGMENTS.length)]);
            }
            String line = sb.toString();
            // Must not throw and must cover the whole line contiguously.
            List<Token> ts = BashTokenHighlighter.tokenize(line);
            int expect = 0;
            for (Token t : ts) {
                assertTrue("non-empty span in [" + line + "]", t.length() > 0);
                assertEquals("contiguous in [" + line + "] at " + t.start, expect, t.start);
                expect = t.end;
            }
            assertEquals("full coverage in [" + line + "]", line.length(), expect);
        }
    }

    @Test
    public void fuzzEveryRoleReachable() {
        // A fixed basket of lines that together must exercise every Role at least once.
        String[] lines = {
                "git commit -m \"msg\" && rsync -a ~/src/ host:/d/ $VAR",
                "for f in *.txt; do cat \"$f\"; done",
                "echo ${ARR[0]} > out 2>&1",
                "( cmd1 | cmd2 ) &",
                "rm file[1-3].log {a,b,c}",
                "x='it\\'s' y=$HOME"
        };
        java.util.EnumSet<Role> seen = java.util.EnumSet.noneOf(Role.class);
        for (String line : lines) {
            for (Token t : BashTokenHighlighter.tokenize(line)) seen.add(t.role);
        }
        for (Role r : new Role[]{Role.COMMAND, Role.PATH, Role.VARIABLE, Role.OPTION,
                Role.QUOTED, Role.OPERATOR, Role.TEXT}) {
            assertTrue("role exercised: " + r, seen.contains(r));
        }
    }
}

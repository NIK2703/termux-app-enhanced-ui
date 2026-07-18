package com.termux.app.terminal.io.autocomplete;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;

import com.termux.app.terminal.TermuxColorSchemeManager;
import com.termux.shared.termux.settings.preferences.TermuxAppSharedPreferences;
import com.termux.terminal.TerminalColors;
import com.termux.terminal.TextStyle;

import android.graphics.Color;

/**
 * Tests that syntax-highlight token colours are DERIVED from the active Termux
 * Styling colour scheme (its 16-colour ANSI palette), never hardcoded, and that
 * light vs dark schemes select distinct, legible palette slots.
 *
 * <p>We drive {@link TermuxColorSchemeManager#recompute} against a known palette
 * we install into the static {@link TerminalColors#COLOR_SCHEME}, then assert the
 * token getters return the expected palette entries and differ from the scheme
 * foreground.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class JavaTokenColorSchemeTest {

    /** Install a known palette; background index decides light vs dark. */
    private static void installPalette(int background, int[] ansi) {
        int[] pal = TerminalColors.COLOR_SCHEME.mDefaultColors;
        for (int i = 0; i < 16; i++) pal[i] = ansi[i];
        pal[TextStyle.COLOR_INDEX_FOREGROUND] = 0xff00ff00; // distinct sentinel fg
        pal[TextStyle.COLOR_INDEX_BACKGROUND] = background;
        pal[TextStyle.COLOR_INDEX_CURSOR] = 0xff000000;
    }

    private static TermuxColorSchemeManager recompute() {
        TermuxColorSchemeManager mgr = new TermuxColorSchemeManager();
        mgr.recompute(); // uses isTerminalSchemeLight() from installed palette
        return mgr;
    }

    @Test
    public void lightSchemeUsesNormalAnsiColors() {
        // Dark text palette on a light (white) background.
        int[] ansi = new int[16];
        for (int i = 0; i < 16; i++) ansi[i] = Color.rgb(i, i, i); // monotone so slots differ by index
        ansi[2] = 0xff000011; // normal green (command)
        ansi[4] = 0xff000022; // normal blue  (path)
        ansi[1] = 0xff000033; // normal red   (variable)
        ansi[3] = 0xff000044; // normal yellow (option)
        ansi[6] = 0xff000055; // normal cyan  (quoted)
        ansi[5] = 0xff000066; // normal magenta (operator)
        installPalette(0xffffffff, ansi); // white bg => light scheme

        TermuxColorSchemeManager mgr = recompute();
        assertTrue("light scheme detected", mgr.isSchemeLight());

        // Token colours must equal the NORMAL (0-7) palette slots, not the bright ones.
        assertEquals(0xff000011, mgr.getTokenCommand());
        assertEquals(0xff000022, mgr.getTokenPath());
        assertEquals(0xff000033, mgr.getTokenVariable());
        assertEquals(0xff000044, mgr.getTokenOption());
        assertEquals(0xff000055, mgr.getTokenQuoted());
        assertEquals(0xff000066, mgr.getTokenOperator());
    }

    @Test
    public void darkSchemeUsesBrightAnsiColors() {
        int[] ansi = new int[16];
        for (int i = 0; i < 16; i++) ansi[i] = Color.rgb(i, i, i);
        ansi[8 + 2] = 0xff110000; // bright green
        ansi[8 + 4] = 0xff220000; // bright blue
        ansi[8 + 1] = 0xff330000; // bright red
        ansi[8 + 3] = 0xff440000; // bright yellow
        ansi[8 + 6] = 0xff550000; // bright cyan
        ansi[8 + 5] = 0xff660000; // bright magenta
        installPalette(0xff000000, ansi); // black bg => dark scheme

        TermuxColorSchemeManager mgr = recompute();
        assertTrue("dark scheme detected", !mgr.isSchemeLight());

        assertEquals(0xff110000, mgr.getTokenCommand());
        assertEquals(0xff220000, mgr.getTokenPath());
        assertEquals(0xff330000, mgr.getTokenVariable());
        assertEquals(0xff440000, mgr.getTokenOption());
        assertEquals(0xff550000, mgr.getTokenQuoted());
        assertEquals(0xff660000, mgr.getTokenOperator());
    }

    @Test
    public void tokenColorsDistinctFromForeground() {
        int[] ansi = new int[16];
        for (int i = 0; i < 16; i++) ansi[i] = Color.rgb(10 * i, 20 * i, 30 * i);
        installPalette(0xffffffff, ansi);
        TermuxColorSchemeManager mgr = recompute();

        int fg = mgr.getButtonText(); // scheme foreground sentinel (0xff00ff00)
        assertNotEquals("command != fg", fg, mgr.getTokenCommand());
        assertNotEquals("path != fg", fg, mgr.getTokenPath());
        assertNotEquals("variable != fg", fg, mgr.getTokenVariable());
        assertNotEquals("option != fg", fg, mgr.getTokenOption());
        assertNotEquals("quoted != fg", fg, mgr.getTokenQuoted());
        assertNotEquals("operator != fg", fg, mgr.getTokenOperator());
    }

    @Test
    public void tokenColorsArePairwiseDistinct() {
        int[] ansi = new int[16];
        for (int i = 0; i < 16; i++) ansi[i] = Color.rgb(10 * i, 20 * i, 30 * i);
        installPalette(0xff000000, ansi);
        TermuxColorSchemeManager mgr = recompute();

        int[] colors = new int[] {
                mgr.getTokenCommand(), mgr.getTokenPath(), mgr.getTokenVariable(),
                mgr.getTokenOption(), mgr.getTokenQuoted(), mgr.getTokenOperator()
        };
        for (int i = 0; i < colors.length; i++) {
            assertNotNull(colors[i]);
            for (int j = i + 1; j < colors.length; j++) {
                assertNotEquals("token " + i + " != token " + j, colors[i], colors[j]);
            }
        }
    }
}

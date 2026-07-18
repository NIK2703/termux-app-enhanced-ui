package com.termux.app.terminal.io.autocomplete;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;

import android.app.Application;
import android.text.Editable;
import android.text.Spannable;
import android.text.style.ForegroundColorSpan;
import android.widget.EditText;

import com.termux.app.terminal.TermuxColorSchemeManager;
import com.termux.terminal.TerminalColors;
import com.termux.terminal.TextStyle;

import java.util.ArrayList;
import java.util.List;

import com.termux.shared.termux.settings.preferences.TermuxAppSharedPreferences;

/**
 * Integration test: the {@link AutoCompleteController} must colour recognised
 * bash tokens in the input field using scheme colours, while leaving unrecognised
 * text in the default colour. Drives a real {@link EditText} through the
 * controller and inspects the resulting {@link ForegroundColorSpan}s.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class JavaInputHighlightTest {

    private AutoCompleteController mCtrl;
    private EditText mInput;
    private TermuxColorSchemeManager mScheme;

    @Before
    public void setUp() {
        Application app = RuntimeEnvironment.application;
        // Install a known dark palette so token colours are non-zero/distinct.
        int[] ansi = new int[16];
        for (int i = 0; i < 16; i++) ansi[i] = 0xff111111;
        ansi[2] = 0xff0000ff; // command = blue
        ansi[4] = 0xff00ff00; // path = green
        ansi[1] = 0xffff0000; // variable = red
        ansi[3] = 0xffffff00; // option = yellow
        TerminalColors.COLOR_SCHEME.mDefaultColors[TextStyle.COLOR_INDEX_FOREGROUND] = 0xffffffff;
        TerminalColors.COLOR_SCHEME.mDefaultColors[TextStyle.COLOR_INDEX_BACKGROUND] = 0xff000000;
        for (int i = 0; i < 16; i++)
            TerminalColors.COLOR_SCHEME.mDefaultColors[i] = ansi[i];

        mScheme = new TermuxColorSchemeManager();
        mScheme.recompute();

        mInput = new EditText(app);
        mCtrl = new AutoCompleteController(app, mInput,
                new com.termux.app.terminal.io.autocomplete.MessageHistoryController(
                        app.getSharedPreferences("termux_prefs", android.content.Context.MODE_PRIVATE)),
                mScheme);
    }

    private List<ForegroundColorSpan> spans() {
        Editable e = mInput.getText();
        if (e == null) return new ArrayList<>();
        ForegroundColorSpan[] arr = e.getSpans(0, e.length(), ForegroundColorSpan.class);
        List<ForegroundColorSpan> out = new ArrayList<>();
        for (ForegroundColorSpan s : arr) out.add(s);
        return out;
    }

    @Test
    public void commandWordIsColoured() {
        mInput.setText("git status");
        mCtrl.refreshInputSyntaxHighlighting();
        List<ForegroundColorSpan> sp = spans();
        assertTrue("at least one token span applied", sp.size() >= 1);
        // The "git" command word (offset 0..3) must be coloured.
        boolean coversGit = false;
        for (ForegroundColorSpan s : sp) {
            int st = mInput.getText().getSpanStart(s);
            int en = mInput.getText().getSpanEnd(s);
            if (st <= 0 && en >= 3) coversGit = true;
        }
        assertTrue("command 'git' coloured", coversGit);
        // Colour must equal the scheme's command colour (sourced from the palette),
        // NOT the default view foreground.
        int cmdColor = mScheme.getTokenCommand();
        boolean usesSchemeColor = false;
        for (ForegroundColorSpan s : sp) {
            if (s.getForegroundColor() == cmdColor) usesSchemeColor = true;
        }
        assertTrue("command span uses scheme command colour", usesSchemeColor);
    }

    @Test
    public void pathTokenColouredInInput() {
        mInput.setText("cd /usr/bin");
        mCtrl.refreshInputSyntaxHighlighting();
        // The path token must use the PATH colour (sourced from the scheme).
        int pathColor = mScheme.getTokenPath();
        boolean foundPath = false;
        for (ForegroundColorSpan s : spans()) {
            if (s.getForegroundColor() == pathColor) foundPath = true;
        }
        assertTrue("path token coloured with PATH colour", foundPath);
    }

    @Test
    public void plainArgumentHasNoTokenSpan() {
        // "git" is the command (coloured); "hello" is a plain argument with no
        // recognised role and must NOT receive a foreground span.
        mInput.setText("git hello");
        mCtrl.refreshInputSyntaxHighlighting();
        Editable e = mInput.getText();
        // The plain word "hello" (offset 4..9) must not be spanned.
        for (ForegroundColorSpan s : spans()) {
            int st = e.getSpanStart(s);
            int en = e.getSpanEnd(s);
            boolean overlapsHello = st < 9 && en > 4;
            assertEquals("plain 'hello' must not be coloured", false, overlapsHello);
        }
        // Exactly one span (the command) is expected for this line.
        assertEquals("one token span (command only)", 1, spans().size());
    }

    @Test
    public void rehighlightAfterSchemeChange() {
        mInput.setText("git");
        mCtrl.refreshInputSyntaxHighlighting();
        int before = spans().size();
        assertTrue(before >= 1);

        // Switch to a light scheme and give its command colour (normal index 2) a
        // distinct value, then recompute + re-highlight.
        TerminalColors.COLOR_SCHEME.mDefaultColors[TextStyle.COLOR_INDEX_BACKGROUND] = 0xffffffff;
        int newCmd = 0xffabcdef;
        TerminalColors.COLOR_SCHEME.mDefaultColors[2] = newCmd; // light => normal index
        mScheme.recompute();
        mCtrl.refreshInputSyntaxHighlighting();

        boolean usesNewColor = false;
        for (ForegroundColorSpan s : spans()) {
            if (s.getForegroundColor() == newCmd) usesNewColor = true;
        }
        assertTrue("re-highlight picks up new scheme colour", usesNewColor);
    }
}

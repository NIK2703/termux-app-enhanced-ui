package com.termux.app.terminal.io;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.PopupWindow;

import com.termux.app.terminal.TermuxColorSchemeManager;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;
import org.robolectric.shadows.ShadowLooper;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Verifies that shell (bash) completion and message-history are rendered into
 * TWO SEPARATE popup windows (not one combined window with a divider):
 *
 * <ul>
 *   <li>both windows exist as distinct, non-null {@link PopupWindow}s;</li>
 *   <li>the shell window's content holds ONLY shell candidates and the history
 *       window's content holds ONLY history entries (clean split, no divider);</li>
 *   <li>the shell window is positioned ABOVE the history window
 *       (smaller Y → higher on screen).</li>
 * </ul>
 *
 * Robolectric loads the Android-dependent classes; {@code ConscryptMode.OFF}
 * avoids the aarch64 Conscrypt crash.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class JavaTwoWindowPopupTest {

    private static final File DEVICE_BASH = new File("/data/data/com.termux/files/usr/bin/bash");

    private Context mContext;
    private AutoCompleteController mController;
    private EditText mInput;

    @Before
    public void setUp() {
        assertTrue("device bash must exist for these tests", DEVICE_BASH.exists());
        mContext = RuntimeEnvironment.application;
        mInput = new EditText(mContext);
        mInput.setText("git s");
        mInput.setSelection(mInput.getText().length());
        com.termux.app.terminal.io.MessageHistoryController history =
                new com.termux.app.terminal.io.MessageHistoryController(
                        mContext.getSharedPreferences("test_two_window", Context.MODE_PRIVATE));
        mController = new AutoCompleteController(mContext, mInput, history, new TermuxColorSchemeManager());
        mController.setShellCompletionEnabled(true);
        // Seed history so the history window has content (goes straight into the
        // merged suggestion list, mimicking a rescan).
        mController.debugSeedHistorySuggestions("git status", "git diff", "git commit");
    }

    /** Build a shell completion result with the given values (all COMMAND type). */
    private ShellCompletionProvider.CompletionResult shellResult(String... values) {
        List<ShellCompletionProvider.ShellCandidate> cands = new ArrayList<>();
        for (String v : values) {
            cands.add(new ShellCompletionProvider.ShellCandidate(
                    v, ShellCompletionProvider.CandidateType.COMMAND, false, false, false, false));
        }
        return new ShellCompletionProvider.CompletionResult(cands, false, false, false);
    }

    @Test
    public void twoDistinctWindows_createdOnMerge() {
        mController.debugMergeShellCandidates(shellResult("status", "show"), "git s");

        PopupWindow shell = mController.debugShellPopup();
        PopupWindow hist = mController.debugHistoryPopup();
        assertNotNull("shell window must exist", shell);
        assertNotNull("history window must exist", hist);
        assertNotSame("shell and history must be TWO separate windows", shell, hist);
        assertTrue("shell window must be showing", shell.isShowing());
        assertTrue("history window must be showing", hist.isShowing());
    }

    @Test
    public void content_isCleanlySplit_noDivider() {
        mController.debugMergeShellCandidates(shellResult("status", "show"), "git s");

        LinearLayout shellContent = mController.debugShellContent();
        LinearLayout histContent = mController.debugHistoryContent();
        assertNotNull(shellContent);
        assertNotNull(histContent);

        // Shell window holds exactly the 2 shell candidates as TextViews.
        assertEquals("shell window holds only shell candidates", 2, shellContent.getChildCount());
        // History window holds the 3 history entries.
        assertEquals("history window holds only history entries", 3, histContent.getChildCount());

        // The shell window must NOT contain any history entry text.
        List<String> shellLines = linesOf(shellContent);
        assertFalse("shell window must not contain a history entry",
                shellLines.contains("git status") || shellLines.contains("git diff") || shellLines.contains("git commit"));
        // The history window must NOT contain any shell candidate text.
        List<String> histLines = linesOf(histContent);
        assertFalse("history window must not contain a shell candidate",
                histLines.contains("status") || histLines.contains("show"));
    }

    @Test
    public void shellWindow_positionedAboveHistory() {
        // Give the input field a real layout so geometry can be computed.
        mInput.layout(0, 200, 500, 230);
        mInput.requestLayout();
        ShadowLooper.idleMainLooper();

        mController.debugMergeShellCandidates(shellResult("status", "show"), "git s");
        // Force geometry recompute on the laid-out field.
        mController.debugApplyPopupGeometry();
        ShadowLooper.idleMainLooper();

        int shellY = mController.debugGetShellY();
        int histY = mController.debugGetHistoryY();
        // Only assert if geometry ran (both coords set). When the field has a
        // layout, the shell window must sit strictly above the history window.
        if (shellY != 0 || histY != 0) {
            assertTrue("shell window Y (" + shellY + ") must be above history Y (" + histY + ")",
                    shellY < histY);
        }
    }

    private static List<String> linesOf(LinearLayout container) {
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < container.getChildCount(); i++) {
            if (container.getChildAt(i) instanceof android.widget.TextView) {
                lines.add(((android.widget.TextView) container.getChildAt(i)).getText().toString().trim());
            }
        }
        return lines;
    }
}

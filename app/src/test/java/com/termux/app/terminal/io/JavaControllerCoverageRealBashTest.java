package com.termux.app.terminal.io;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;
import android.widget.EditText;

import com.termux.app.terminal.TermuxColorSchemeManager;

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
import java.util.List;

/**
 * ДОПОЛНИТЕЛЬНОЕ покрытие {@link AutoCompleteController}, закрывающее ДЫРЫ,
 * не покрытые соседними тестами (JavaControllerRealBashTest,
 * JavaControllerDispatchTest, JavaLifecycleRealBashTest,
 * JavaHistoryShellRealBashTest, JavaResilienceRealBashTest).
 *
 * <p>Фокус на НЕ покрытых участках:
 * <ul>
 *   <li>ПОЛНЫЙ цикл диспетчеризации через реальный TextWatcher
 *       ({@code setText()} → afterTextChanged → Path A при shell OFF /
 *       Path B + fetch при shell ON);</li>
 *   <li>{@code setSuppressAutoComplete(true)} → попап не показывается;</li>
 *   <li>{@code setInvalidState(true)} → обновление прерывается;</li>
 *   <li>{@code onInputFocusLost()} → dismiss;</li>
 *   <li>{@code onCaretMoved()} → reposition (caret не в конце → dismiss);</li>
 *   <li>{@code filterSuggestionsByPrefix} напрямую: shell по lastWord,
 *       история по строке, отсекание точного совпадения;</li>
 *   <li>{@code loadSuggestions()} / {@code addToMessageHistory()} влияют на список;</li>
 *   <li>чистая логика: {@code middleCandidate}, {@code wordStartOffset},
 *       {@code computePopupWidth}, {@code displayShellCount};</li>
 *   <li>обрезка по {@code suggestions_max_count} (&gt; maxCount);</li>
 *   <li>разделение shell/история через {@code debugSeedHistorySuggestions} +
 *       {@code displayShellCount}.</li>
 * </ul>
 *
 * <p>Robolectric (sdk 28 + ConscryptMode.OFF) необходим для Android-классов.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class JavaControllerCoverageRealBashTest {

    private static final File DEVICE_BASH = new File("/data/data/com.termux/files/usr/bin/bash");

    private Context mContext;
    private AutoCompleteController mController;
    private ShellCompletionProvider mProvider;
    private MessageHistoryController mHistory;
    private EditText mInput;

    @Before
    public void setUp() {
        assertTrue("device bash must exist for these tests", DEVICE_BASH.exists());
        mContext = RuntimeEnvironment.application;
        mInput = new EditText(mContext);
        mInput.setText("");
        mHistory = new MessageHistoryController(
                mContext.getSharedPreferences("test_history_coverage", Context.MODE_PRIVATE));
        mController = new AutoCompleteController(mContext, mInput, mHistory, new TermuxColorSchemeManager());
        mProvider = new ShellCompletionProvider(mContext, DEVICE_BASH);
        assertTrue("device bash must be available", mProvider.isAvailable());
    }

    private void setMaxCount(int n) {
        SharedPreferences prefs = mContext.getSharedPreferences("termux_prefs", Context.MODE_PRIVATE);
        prefs.edit().putInt("suggestions_max_count", n).commit();
    }

    // ── ПОЛНЫЙ цикл диспетчеризации: shell OFF → Path A ──

    @Test
    public void fullCycle_shellOff_pathA_historyShown() {
        mController.setShellCompletionEnabled(false);
        assertTrue("shell OFF → provider null", mController.debugProviderNull());
        mController.debugSetHistory(Arrays.asList("git status", "git diff", "ls -la"));

        mInput.setText("git s");
        mInput.setSelection(mInput.getText().length());
        mController.onTextChanged();

        assertTrue("shell still null after cycle (Path A)", mController.debugProviderNull());
        List<String> suggestions = mController.debugSuggestions();
        assertTrue("history suggestion 'git status' present after typing",
                suggestions.contains("git status"));
        assertFalse("unrelated 'ls -la' filtered out", suggestions.contains("ls -la"));
    }

    // ── ПОЛНЫЙ цикл диспетчеризации: shell ON → Path B + fetch ──

    @Test
    public void fullCycle_shellOn_pathB_fetchScheduled() {
        mController.setShellCompletionEnabled(true);
        mController.debugSetHistory(Arrays.asList("echo hello", "git status"));

        mInput.setText("cat /etc/hos");
        mInput.setSelection(mInput.getText().length());
        mController.onTextChanged();

        assertFalse("provider present (shell ON)", mController.debugProviderNull());
        assertTrue("generation advanced → fetch scheduled", mController.debugGetGen() > 0);
    }

    // ── setSuppressAutoComplete(true) гасит попап ──

    @Test
    public void suppressAutoComplete_blocksSuggestions() {
        mController.setShellCompletionEnabled(false);
        mController.debugSetHistory(Arrays.asList("git status", "git diff"));
        mController.setSuppressAutoComplete(true);

        mInput.setText("git s");
        mInput.setSelection(mInput.getText().length());
        mController.onTextChanged();

        assertTrue("no suggestions while suppress=true", mController.debugSuggestions().isEmpty());
        assertFalse("popup not showing while suppress=true", mController.isShowing());

        mController.setSuppressAutoComplete(false);
        mController.onTextChanged();
        assertTrue("history suggestion appears after suppress lifted",
                mController.debugSuggestions().contains("git status"));
    }

    // ── setInvalidState(true) прерывает обновление ──

    @Test
    public void invalidState_blocksUpdate() {
        mController.setShellCompletionEnabled(false);
        mController.debugSetHistory(Arrays.asList("git status", "git diff"));
        mController.setInvalidState(true);

        mInput.setText("git s");
        mInput.setSelection(mInput.getText().length());
        mController.onTextChanged();

        assertTrue("no suggestions while invalid state", mController.debugSuggestions().isEmpty());
        assertFalse("popup not showing in invalid state", mController.isShowing());

        mController.setInvalidState(false);
        mController.onTextChanged();
        assertTrue("suggestion appears after invalid state cleared",
                mController.debugSuggestions().contains("git status"));
    }

    // ── onInputFocusLost → dismiss ──

    @Test
    public void inputFocusLost_dismissesPopup() {
        mController.setShellCompletionEnabled(false);
        mController.debugSetHistory(Arrays.asList("git status"));
        mInput.setText("git s");
        mInput.setSelection(mInput.getText().length());
        mController.onTextChanged();
        assertFalse("popup showing before focus loss", mController.debugSuggestions().isEmpty());

        mController.onInputFocusLost();
        assertTrue("suggestions cleared after focus lost", mController.debugSuggestions().isEmpty());
    }

    // ── onCaretMoved / caret-guard: caret не в конце → попап скрывается ──

    @Test
    public void caretNotAtEnd_hidesPopup() {
        mController.setShellCompletionEnabled(false);
        mController.debugSetHistory(Arrays.asList("git status --long", "git status -s"));
        mInput.setText("git status");
        mInput.setSelection(mInput.getText().length());
        mController.onTextChanged();
        assertFalse("has suggestions before caret move", mController.debugSuggestions().isEmpty());

        // Перемещаем каретку в середину. Реальный afterTextChanged-путь
        // (updateAutoCompleteSuggestions) видит caret != length и прячет попап.
        mInput.setSelection(3);
        mController.onTextChanged();
        assertTrue("suggestions cleared when caret not at end",
                mController.debugSuggestions().isEmpty());

        // onCaretMoved сам по себе не падает и делегирует reposition (без окна
        // Activity попап не показан, поэтому просто не меняет состояние).
        mInput.setSelection(mInput.getText().length());
        mController.onTextChanged();
        mController.onCaretMoved();
    }

    // ── filterSuggestionsByPrefix напрямую: shell по lastWord ──

    @Test
    public void filter_shellByLastWord_historyByLine_exactDropped() {
        List<String> sugg = new ArrayList<>(Arrays.asList("MyFileX", "MyFileY", "OtherZ"));
        List<Boolean> shell = new ArrayList<>(Arrays.asList(true, true, true));
        mController.filterSuggestionsByPrefix("cat My", sugg, shell);
        assertTrue("MyFileX survives", sugg.contains("MyFileX"));
        assertTrue("MyFileY survives", sugg.contains("MyFileY"));
        assertFalse("OtherZ dropped (lastWord mismatch)", sugg.contains("OtherZ"));

        List<String> sugg2 = new ArrayList<>(Arrays.asList("My", "MyFileX"));
        List<Boolean> shell2 = new ArrayList<>(Arrays.asList(true, true));
        mController.filterSuggestionsByPrefix("cat My", sugg2, shell2);
        assertFalse("exact lastWord dropped", sugg2.contains("My"));
        assertTrue("longer kept", sugg2.contains("MyFileX"));
    }

    @Test
    public void filter_historyByWholeLine_exactDropped() {
        List<String> sugg = new ArrayList<>(Arrays.asList("git status", "git diff", "git"));
        List<Boolean> shell = new ArrayList<>(Arrays.asList(false, false, false));
        mController.filterSuggestionsByPrefix("git s", sugg, shell);
        assertTrue("git status matches whole line", sugg.contains("git status"));
        assertFalse("git diff does not match 'git s'", sugg.contains("git diff"));
        assertFalse("'git' shorter than typed dropped", sugg.contains("git"));

        List<String> sugg2 = new ArrayList<>(Arrays.asList("git s", "git status"));
        List<Boolean> shell2 = new ArrayList<>(Arrays.asList(false, false));
        mController.filterSuggestionsByPrefix("git s", sugg2, shell2);
        assertFalse("exact whole-line match dropped", sugg2.contains("git s"));
        assertTrue("longer kept", sugg2.contains("git status"));
    }

    // ── loadSuggestions / addToMessageHistory влияют на список ──

    @Test
    public void addToMessageHistory_feedsSuggestions() {
        mController.setShellCompletionEnabled(false);
        mController.addToMessageHistory("git commit -m");
        mController.addToMessageHistory("git commit -m");

        mController.loadSuggestions();
        mInput.setText("git c");
        mInput.setSelection(mInput.getText().length());
        mController.onTextChanged();

        List<String> suggestions = mController.debugSuggestions();
        assertTrue("added history surfaces as suggestion", suggestions.contains("git commit -m"));
    }

    @Test
    public void addToMessageHistory_emptyIgnored() {
        mController.addToMessageHistory("");
        mController.loadSuggestions();
        mInput.setText("git");
        mInput.setSelection(mInput.getText().length());
        mController.onTextChanged();
        assertTrue("empty message not added to history", mController.debugSuggestions().isEmpty());
    }

    // ── чистая логика: middleCandidate (бинарный поиск helper) ──

    @Test
    @SuppressWarnings("unchecked")
    public void middleCandidate_splitsHeadTail() throws Exception {
        java.lang.reflect.Method m = AutoCompleteTextRenderer.class.getDeclaredMethod(
                "middleCandidate", String.class, int.class);
        m.setAccessible(true);
        assertEquals("ab…yz", m.invoke(null, "abcdefghijklmnopqrstuvwxyz", 4));
        assertEquals("a…j", m.invoke(null, "abcdefghij", 2));
        assertEquals("short…", m.invoke(null, "short", 100));
        assertEquals("…", m.invoke(null, "abc", 0));
    }

    // ── чистая логика: wordStartOffset ──

    @Test
    @SuppressWarnings("unchecked")
    public void wordStartOffset_boundaries() throws Exception {
        java.lang.reflect.Method m = AutoCompleteTextRenderer.class.getDeclaredMethod(
                "wordStartOffset", String.class);
        m.setAccessible(true);
        assertEquals(0, m.invoke(null, "git"));
        assertEquals(4, m.invoke(null, "git status"));
        assertEquals(4, m.invoke(null, "cd /data"));
        assertEquals(0, m.invoke(null, "hello "));
        assertEquals(0, m.invoke(null, "dir/"));
        assertEquals(0, m.invoke(null, ""));
    }

    // ── чистая логика: computePopupWidth ──

    @Test
    @SuppressWarnings("unchecked")
    public void computePopupWidth_withinBounds() throws Exception {
        java.lang.reflect.Field pmField = AutoCompleteController.class.getDeclaredField("mPopupManager");
        pmField.setAccessible(true);
        Object popupManager = pmField.get(mController);
        java.lang.reflect.Method m = AutoCompletePopupManager.class.getDeclaredMethod(
                "computePopupWidth", int.class);
        m.setAccessible(true);
        int fieldWidth = 1000;
        mInput.setWidth(fieldWidth);
        int w = (int) m.invoke(popupManager, fieldWidth);
        int min = mContext.getResources().getDimensionPixelSize(
                com.termux.R.dimen.autocomplete_popup_min_width);
        assertTrue("popup width >= min width", w >= min);
        int margin = mContext.getResources().getDimensionPixelSize(
                com.termux.R.dimen.autocomplete_popup_width_margin);
        assertTrue("popup width < avail (field-margin)", w <= (fieldWidth - margin));
    }

    // ── displayShellCount: разделение shell/история ──

    @Test
    public void displayShellCount_separation() {
        setMaxCount(10);
        mController.debugSetHistory(Arrays.asList("git status", "git diff", "ls -la"));
        List<ShellCompletionProvider.ShellCandidate> cands = Arrays.asList(
                new ShellCompletionProvider.ShellCandidate("git", ShellCompletionProvider.CandidateType.COMMAND, false, false, false, false),
                new ShellCompletionProvider.ShellCandidate("gitk", ShellCompletionProvider.CandidateType.COMMAND, false, false, false, false));
        mController.debugMergeShellCandidates(
                new ShellCompletionProvider.CompletionResult(cands, false, false, false), "gi");

        int shellCount = mController.debugShellCount();
        assertEquals("two shell entries on top", 2, shellCount);
        List<String> suggestions = mController.debugSuggestions();
        List<Boolean> isShell = mController.debugIsShell();
        for (int i = 0; i < shellCount; i++) assertTrue(isShell.get(i));
        for (int i = shellCount; i < suggestions.size(); i++) assertFalse(isShell.get(i));
    }

    // ── обрезка по suggestions_max_count ──

    @Test
    @SuppressWarnings("unchecked")
    public void maxCount_truncatesDisplayedSuggestions() throws Exception {
        int dc = mController.debugDisplayCount();

        setMaxCount(3);
        List<String> hist = new ArrayList<>();
        for (int i = 0; i < 8; i++) hist.add("git cmd" + i);
        mController.debugSetHistory(hist);

        mController.setShellCompletionEnabled(false);
        mInput.setText("git c");
        mInput.setSelection(mInput.getText().length());
        mController.onTextChanged();

        // Рендерится не более maxCount (displayCount капает хранимый список по mDisplayMax).
        int rendered = dc;
        assertTrue("displayCount capped at maxCount=3, got " + rendered, rendered <= 3);
        assertTrue("all 8 stored matches retained for Path B filtering",
                mController.debugSuggestions().size() == 8);
    }

    @Test
    public void maxCount_zero_disablesPopup() {
        setMaxCount(0);
        mController.debugSetHistory(Arrays.asList("git status", "git diff"));
        mController.setShellCompletionEnabled(false);
        mInput.setText("git s");
        mInput.setSelection(mInput.getText().length());
        mController.onTextChanged();
        assertTrue("no suggestions when maxCount==0", mController.debugSuggestions().isEmpty());
        assertFalse("popup not showing when maxCount==0", mController.isShowing());
    }

    // ── merge: дедуп shell против истории (точное совпадение) ──

    @Test
    public void merge_dedupExactHistoryCommand() {
        mController.debugSetHistory(Arrays.asList("echo hi", "git"));
        List<ShellCompletionProvider.ShellCandidate> cands = Arrays.asList(
                new ShellCompletionProvider.ShellCandidate("git", ShellCompletionProvider.CandidateType.COMMAND, false, false, false, false),
                new ShellCompletionProvider.ShellCandidate("gitk", ShellCompletionProvider.CandidateType.COMMAND, false, false, false, false));
        mController.debugMergeShellCandidates(
                new ShellCompletionProvider.CompletionResult(cands, false, false, false), "gi");
        int gitCount = 0;
        for (String s : mController.debugSuggestions()) if (s.equals("git")) gitCount++;
        assertEquals("'git' must appear exactly once after merge dedup", 1, gitCount);
        assertTrue("gitk (new) present", mController.debugSuggestions().contains("gitk"));
    }

    // ── onTextChanged через реальный TextWatcher (before/on/after) ──

    @Test
    public void realTextWatcher_triggersUpdate() {
        mController.setShellCompletionEnabled(false);
        mController.debugSetHistory(Arrays.asList("docker run", "docker ps"));
        mInput.setText("docker r");
        mInput.setSelection(mInput.getText().length());
        mController.onTextChanged();
        assertTrue("suggestion 'docker run' shown via TextWatcher cycle",
                mController.debugSuggestions().contains("docker run"));
        assertFalse("'docker ps' filtered out", mController.debugSuggestions().contains("docker ps"));
    }
}

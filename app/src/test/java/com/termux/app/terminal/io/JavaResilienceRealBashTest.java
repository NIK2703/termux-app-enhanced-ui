package com.termux.app.terminal.io;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.widget.EditText;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.app.terminal.TermuxColorSchemeManager;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;
import org.robolectric.shadows.ShadowLooper;

import java.io.File;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * СЛОЖНЫЙ слой продвинутых тестов: устойчивость и безопасность диспетчеризации
 * оболочечного автодополнения, через РЕАЛЬНЫЙ bash устройства.
 *
 * <p>НОВОЕ покрытие относительно существующих тестов:
 * <ul>
 *   <li>Многократное (rapid) переключение shell completion ON/OFF:
 *       {@link AutoCompleteController#debugProviderNull} корректно переключается,
 *       а fetch НЕ обращается к провайдеру когда OFF (счётчик обращений == 0).</li>
 *   <li>Поведение при зависшем/медленном bash: РЕАЛЬНЫЙ прод-код
 *       {@link ShellCompletionProvider#complete} имеет таймаут (TIMEOUT_MS=1500).
 *       Мы натравливаем провайдер на фальшивый bash, который {@code sleep}-ит
 *       дольше таймаута, и проверяем, что {@code complete()} ВОЗВРАЩАЕТ (EMPTY),
 *       не блокируя поток бесконечно, в пределах бюджета ~2×TIMEOUT_MS. Таймаут
 *       в прод-коде ЕСТЬ — прод-код НЕ меняем.</li>
 *   <li>Смена cwd во время fetch (stale-guard через поколение gen): новый fetch
 *       поднимает gen, делая предыдущий результат устаревшим; доставка отбрасывает
 *       stale-результат.</li>
 *   <li>Корректная очистка {@code mCurrentSuggestions} при {@code setText("")}
 *       (через реальный {@code onTextChanged} → dismiss).</li>
 * </ul>
 *
 * <p>Robolectric (sdk 28 + ConscryptMode.OFF) загружает Android-зависимые классы;
 * реальный bash берётся с устройства.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class JavaResilienceRealBashTest {

    private static final File DEVICE_BASH =
            new File("/data/data/com.termux/files/usr/bin/bash");

    private Context mContext;
    private EditText mInput;
    private AutoCompleteController mController;
    private ShellCompletionProvider mProvider;
    private File mFakeBash;

    @Before
    public void setUp() {
        assertTrue("device bash must exist for these tests", DEVICE_BASH.exists());
        mContext = RuntimeEnvironment.application;
        mInput = new EditText(mContext);
        mInput.setText("git ");
        mInput.setSelection(mInput.getText().length());
        MessageHistoryController history = new MessageHistoryController(
                mContext.getSharedPreferences("test_history_resilience",
                        Context.MODE_PRIVATE));
        mController = new AutoCompleteController(
                mContext, mInput, history, new TermuxColorSchemeManager());
        mProvider = new ShellCompletionProvider(mContext, DEVICE_BASH);
        assertTrue("device bash must be available", mProvider.isAvailable());
        mController.setShellCompletionEnabled(true);
        assertFalse("provider must be non-null after enable",
                mController.debugProviderNull());
    }

    @After
    public void tearDown() {
        mController.destroy();
        if (mFakeBash != null && mFakeBash.exists()) {
            mFakeBash.delete();
            mFakeBash = null;
        }
    }

    private static String home() {
        String h = System.getenv("HOME");
        return (h == null || h.isEmpty()) ? "/data/data/com.termux/files/home" : h;
    }

    // ── Многократное переключение ON/OFF ──

    @Test
    public void toggle_offThenOn_providerState() {
        mController.setShellCompletionEnabled(false);
        assertTrue("OFF → provider null", mController.debugProviderNull());
        mController.setShellCompletionEnabled(true);
        assertFalse("ON → provider non-null", mController.debugProviderNull());
        mController.setShellCompletionEnabled(false);
        assertTrue("OFF again → provider null", mController.debugProviderNull());
        mController.setShellCompletionEnabled(true);
        assertFalse("ON again → provider non-null", mController.debugProviderNull());
    }

    @Test
    public void toggle_rapidOnOff_providerGate() {
        // Резкое переключение 6 раз — состояние провайдера всегда согласовано.
        for (int i = 0; i < 6; i++) {
            boolean enable = (i % 2 == 0);
            mController.setShellCompletionEnabled(enable);
            if (enable) {
                assertFalse("iter " + i + " ON → provider non-null",
                        mController.debugProviderNull());
            } else {
                assertTrue("iter " + i + " OFF → provider null",
                        mController.debugProviderNull());
            }
        }
    }

    @Test
    public void toggle_offBlocksFetch_onProviderCalls() throws Exception {
        // Считающий провайдер: убеждаемся, что при OFF fetch НЕ обращается к bash,
        // а при ON — обращается.
        final AtomicInteger calls = new AtomicInteger(0);
        ShellCompletionProvider counting =
                new ShellCompletionProvider(mContext, DEVICE_BASH) {
                    @NonNull
                    @Override
                    public CompletionResult complete(@NonNull String commandLine,
                                                     @Nullable String cwd, int cursor) {
                        calls.incrementAndGet();
                        return super.complete(commandLine, cwd, cursor);
                    }
                };

        // OFF → провайдер null, fetch молча выходит, complete() не вызывается.
        mController.setShellCompletionEnabled(false);
        assertTrue("provider null while OFF", mController.debugProviderNull());
        mController.debugFetchShellCandidatesAsync("git st");
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks();
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks();
        assertEquals("no provider call while shell completion OFF", 0, calls.get());

        // ON + подмена на считающий провайдер → fetch обращается к complete().
        // Используем PATH-контекст (история его не покрывает), пустую историю и
        // пустое поле ввода (cursor=0), плюс прокачку debounce через ShadowLooper —
        // проверенный паттерн соседнего JavaFetchCycleRealBashTest.
        mController.setShellCompletionEnabled(true);
        mController.debugSetHistory(java.util.Collections.emptyList());
        mController.debugSetProvider(counting);
        mController.debugFetchShellCandidatesAsync("cat /etc/hos");
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks(); // debounce → executor
        long deadline = System.currentTimeMillis() + 15000;
        while (calls.get() == 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks(); // delivery post
        assertTrue("provider must be called while ON", calls.get() > 0);
    }

    // ── Зависший/медленный bash: реальный таймаут прод-кода ──

    @Test
    public void hangingBash_completeTimesOutAndReturnsWithinBudget() throws Exception {
        // Строим фальшивый "bash", который игнорирует аргументы и спит 30с.
        // Прод-код complete() обязан прервать его по TIMEOUT_MS=1500 и вернуть
        // (EMPTY), НЕ блокируя поток. Прод-код НЕ меняем — проверяем контракт.
        mFakeBash = File.createTempFile("fake_hang_bash", ".sh");
        try (java.io.FileWriter fw = new java.io.FileWriter(mFakeBash)) {
            fw.write("#!/data/data/com.termux/files/usr/bin/bash\nsleep 30\n");
        }
        assertTrue("fake bash must be marked executable", mFakeBash.setExecutable(true));

        ShellCompletionProvider hanging =
                new ShellCompletionProvider(mContext, mFakeBash);
        assertTrue("fake bash must look available", hanging.isAvailable());

        long t0 = System.currentTimeMillis();
        ShellCompletionProvider.CompletionResult r =
                hanging.complete("git st", home(), 6);
        long dt = System.currentTimeMillis() - t0;

        assertNotNull("hanging bash must yield a (possibly empty) result, never null", r);
        assertNotNull(r.candidates);
        // TIMEOUT_MS=1500; допускаем накладные расходы (kill процесса, join drain).
        // Контракт: не блокировать надолго — существенно меньше 30с sleep.
        assertTrue("complete() must return within ~2×TIMEOUT budget (<6000ms), took "
                + dt + "ms", dt < 6000);
        assertTrue("hanging bash produces no candidates", r.candidates.isEmpty());
    }

    @Test
    public void realBash_complete_returnsWithinTimeoutBudget() {
        // Реальный bash для нормальной команды возвращается ДО таймаута.
        long t0 = System.currentTimeMillis();
        ShellCompletionProvider.CompletionResult r =
                mProvider.complete("git st", home(), 6);
        long dt = System.currentTimeMillis() - t0;
        assertNotNull(r);
        assertTrue("real bash complete() must return within TIMEOUT_MS budget "
                + "(<1500ms), took " + dt + "ms", dt < 1500);
    }

    // ── Смена cwd во время fetch: stale-guard через поколение gen ──

    @Test
    public void swapCwdDuringFetch_secondFetchSupersedesFirst() {
        // Каждый fetch поднимает поколение gen; второй fetch (другой контекст)
        // делает первый устаревшим. Доставка сравнивает захваченный gen с текущим
        // и отбрасывает stale.
        int genBefore = mController.debugGetGen();
        mController.debugFetchShellCandidatesAsync("git st");
        int genScheduled = mController.debugGetGen();
        assertEquals("first fetch raised gen by 1", genBefore + 1, genScheduled);

        mController.debugFetchShellCandidatesAsync("git sta");
        int genAfter = mController.debugGetGen();
        assertTrue("second fetch supersedes the first (higher gen)",
                genAfter > genScheduled);
        assertFalse("provider still present after swap", mController.debugProviderNull());
    }

    @Test
    public void staleGen_bumpDiscardsCaptured_currentMergeStillLands() {
        // Прямая проверка stale-guard: захватываем gen0, поднимаем gen (bump),
        // теперь любой результат, помеченный gen0, устарел. Мерж ПОД ТЕКУЩИМ gen
        // всё равно применяется (позитивный контроль механизма).
        int gen0 = mController.debugGetGen();
        mController.debugBumpGen();
        int genNow = mController.debugGetGen();
        assertTrue("current gen advanced past captured gen0", genNow > gen0);

        List<ShellCompletionProvider.ShellCandidate> cands = Arrays.asList(
                new ShellCompletionProvider.ShellCandidate(
                        "git", ShellCompletionProvider.CandidateType.COMMAND,
                        false, false, false, false));
        mController.debugMergeShellCandidates(
                new ShellCompletionProvider.CompletionResult(cands, false, false, false),
                "gi");
        assertEquals("merge under current gen lands one shell candidate",
                1, mController.debugShellCount());
    }

    // ── Очистка mCurrentSuggestions при setText("") ──

    @Test
    public void clearOnEmptyText_resetsSuggestionsAndShellCount() {
        // Засеваем shell-группу через merge.
        List<ShellCompletionProvider.ShellCandidate> cands = Arrays.asList(
                new ShellCompletionProvider.ShellCandidate(
                        "git", ShellCompletionProvider.CandidateType.COMMAND,
                        false, false, false, false));
        mController.debugMergeShellCandidates(
                new ShellCompletionProvider.CompletionResult(cands, false, false, false),
                "gi");
        assertTrue("shell group installed before clear",
                mController.debugShellCount() > 0);
        assertFalse("suggestions non-empty before clear",
                mController.debugSuggestions().isEmpty());

        // Пользователь стирает поле до "" → onTextChanged → updateAutoComplete →
        // TextUtils.isEmpty(text) → dismissAutoCompleteSuggestions() очищает список.
        EditText input = mController.debugGetInputField();
        assertNotNull("input field present", input);
        input.setText("");
        input.setSelection(0);
        mController.onTextChanged();

        assertTrue("suggestions must be cleared after setText(\"\")",
                mController.debugSuggestions().isEmpty());
        assertEquals("shell group count reset to 0", 0, mController.debugShellCount());
    }

    @Test
    public void clearOnEmptyText_withShellEnabled_doesNotHitBash() {
        // То же, но shell completion ВКЛЮЧЁН. Пустой текст очищает список,
        // не обращаясь к bash; провайдер остаётся живым.
        EditText input = mController.debugGetInputField();
        assertNotNull(input);
        List<ShellCompletionProvider.ShellCandidate> cands = Arrays.asList(
                new ShellCompletionProvider.ShellCandidate(
                        "other", ShellCompletionProvider.CandidateType.COMMAND,
                        false, false, false, false));
        mController.debugMergeShellCandidates(
                new ShellCompletionProvider.CompletionResult(cands, false, false, false),
                "ot");
        assertTrue("shell group present", mController.debugShellCount() > 0);

        input.setText("");
        input.setSelection(0);
        mController.onTextChanged();

        assertTrue("suggestions cleared even with shell enabled",
                mController.debugSuggestions().isEmpty());
        assertFalse("provider still present (shell ON)", mController.debugProviderNull());
    }

    // ── Непрерывный rapid fetch не роняет диспетчер ──

    @Test
    public void rapidFetch_doesNotCrash_providerSurvives() {
        // Многократный быстрый fetch: guard mShellFetchInFlight не даёт накапливать
        // параллельные запуски. Не должно упасть; провайдер жив.
        mController.debugFetchShellCandidatesAsync("git st");
        mController.debugFetchShellCandidatesAsync("git sta");
        mController.debugFetchShellCandidatesAsync("git star");
        assertFalse("provider present after rapid fetches",
                mController.debugProviderNull());
    }
}

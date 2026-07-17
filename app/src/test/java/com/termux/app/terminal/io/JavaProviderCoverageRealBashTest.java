package com.termux.app.terminal.io;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;

import java.io.File;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Дополнительные (не дублирующие) тесты покрытия {@link ShellCompletionProvider},
 * закрывающие ДЫРЫ, не тронутые соседними файлами:
 * <ul>
 *   <li>Circuit-breaker: N таймаутов фейкового {@code sleep}-bash → после
 *       {@code TIMEOUT_CIRCUIT_THRESHOLD} вызовов complete() подавляется и больше
 *       НЕ спавнит bash (EMPTY + mShellSuppressedUntil в будущем). Сброс через
 *       отражение (clearCache() сам mShellSuppressedUntil не сбрасывает).</li>
 *   <li>Кэш: повторный complete() той же строки — из кэша (без спавна bash);
 *       bumpEnvironmentVersion() инвалидирует кэш (пере-спавн).</li>
 *   <li>expandHome / homeOf: {@code ~}, {@code ~/}, {@code ~user} (без спавна).</li>
 *   <li>Оставшиеся ветки classifyScenario: чистый REDIRECTION (prev=">"/"2>"),
 *       test-оператор [{@code -f}, ключевое слово {@code in} цикла, remote-path
 *       ({@code host:/pa}) → COMPSPEC/не-PATH, contextPrefix "F".</li>
 *   <li>normalizeCandidates: пропуск {@code $-}-префиксных кандидатов, жёсткий
 *       предел MAX_CACHED_CANDIDATES, SecurityException при stat.</li>
 *   <li>complete() для недоступного (chmod 000) cwd — не падает, не-null.</li>
 * </ul>
 *
 * <p>Robolectric (sdk 28 + ConscryptMode.OFF) обязателен на aarch64.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class JavaProviderCoverageRealBashTest {

    private static final File DEVICE_BASH =
            new File("/data/data/com.termux/files/usr/bin/bash");

    /** Counting-провайдер: считает реальные спавны bash через переопределение runBash. */
    static class CountingProvider extends ShellCompletionProvider {
        final java.util.concurrent.atomic.AtomicInteger callCount = new java.util.concurrent.atomic.AtomicInteger();
        final java.util.concurrent.atomic.AtomicInteger timedOut = new java.util.concurrent.atomic.AtomicInteger();

        CountingProvider(@Nullable android.content.Context ctx, @NonNull File bash) {
            super(ctx, bash);
        }

        @Nullable
        @Override
        protected BashResult runBash(@NonNull String script, @Nullable String cwd,
                                     @NonNull String compLine, int compPoint,
                                     @NonNull String currentWord) {
            callCount.incrementAndGet();
            return super.runBash(script, cwd, compLine, compPoint, currentWord);
        }
    }

    private File mFakeBash;
    private File mNoPermDir;

    private static String home() {
        String h = System.getenv("HOME");
        return (h == null || h.isEmpty()) ? "/data/data/com.termux/files/home" : h;
    }

    @Before
    public void setUp() {
        assertTrue("device bash must exist for these tests", DEVICE_BASH.exists());
    }

    @After
    public void tearDown() {
        if (mFakeBash != null && mFakeBash.exists()) {
            mFakeBash.delete();
            mFakeBash = null;
        }
        if (mNoPermDir != null && mNoPermDir.exists()) {
            // Восстановим доступ, чтобы удалить.
            mNoPermDir.setReadable(true);
            mNoPermDir.setExecutable(true);
            deleteRecursively(mNoPermDir);
            mNoPermDir = null;
        }
    }

    private static void deleteRecursively(File dir) {
        File[] children = dir.listFiles();
        if (children != null) {
            for (File c : children) {
                if (c.isDirectory()) deleteRecursively(c);
                else c.delete();
            }
        }
        dir.delete();
    }

    private static File makeFakeSleepBash(long seconds) throws Exception {
        File f = File.createTempFile("fake_sleep_bash", ".sh");
        try (java.io.FileWriter fw = new java.io.FileWriter(f)) {
            fw.write("#!/data/data/com.termux/files/usr/bin/bash\nsleep " + seconds + "\n");
        }
        assertTrue("fake bash must be executable", f.setExecutable(true));
        return f;
    }

    // ── Circuit-breaker: N таймаутов → подавление без спавна bash ──

    @Test
    public void circuitBreaker_trippingSuppressesBash() throws Exception {
        // Фейковый bash, который спит дольше TIMEOUT_MS (1500) → каждый вызов
        // таймаутится и complete() возвращает EMPTY, не блокируясь на 30с.
        mFakeBash = makeFakeSleepBash(30);
        CountingProvider p = new CountingProvider(RuntimeEnvironment.application, mFakeBash);
        assertTrue("fake bash looks available", p.isAvailable());

        int threshold = 3; // TIMEOUT_CIRCUIT_THRESHOLD
        long t0 = System.currentTimeMillis();
        for (int i = 0; i < threshold; i++) {
            ShellCompletionProvider.CompletionResult r =
                    p.complete("git st", home(), 6);
            assertNotNull(r);
            assertTrue("timed-out complete must yield EMPTY", r.candidates.isEmpty());
        }
        long elapsedTrips = System.currentTimeMillis() - t0;
        // 3 таймаута по ~1500мс → заметно меньше 3×30с; контракт таймаута работает.
        assertTrue("three timeouts stayed within budget, took " + elapsedTrips + "ms",
                elapsedTrips < 30000);
        assertEquals("three timeouts spawned bash three times", 3, p.callCount.get());

        // После порога — подавление: mShellSuppressedUntil в будущем.
        long suppressedUntil = readSuppressedUntil(p);
        assertTrue("circuit-breaker must set mShellSuppressedUntil in the future",
                suppressedUntil > System.currentTimeMillis());

        // Следующий вызов НЕ должен спавнить bash (возвращает EMPTY быстро).
        int before = p.callCount.get();
        long t1 = System.currentTimeMillis();
        ShellCompletionProvider.CompletionResult r4 =
                p.complete("git st", home(), 6);
        long dt4 = System.currentTimeMillis() - t1;
        assertNotNull(r4);
        assertTrue("suppressed complete must yield EMPTY", r4.candidates.isEmpty());
        assertEquals("no new bash spawn while suppressed", before, p.callCount.get());
        assertTrue("suppressed call returns quickly (<500ms)", dt4 < 500);
    }

    @Test
    public void circuitBreaker_resetViaReflectionAllowsBashAgain() throws Exception {
        mFakeBash = makeFakeSleepBash(30);
        CountingProvider p = new CountingProvider(RuntimeEnvironment.application, mFakeBash);

        // Трипнуть breaker.
        for (int i = 0; i < 3; i++) p.complete("git st", home(), 6);
        assertTrue("breaker tripped", readSuppressedUntil(p) > System.currentTimeMillis());

        // Сбросить mShellSuppressedUntil и mConsecutiveTimeouts через отражение
        // (clearCache() эти поля НЕ трогает — проверяем и это).
        p.clearCache();
        assertTrue("clearCache() alone must NOT clear suppression",
                readSuppressedUntil(p) > System.currentTimeMillis());
        resetCircuitBreaker(p);
        assertEquals("after reset suppression cleared", 0L, readSuppressedUntil(p));
        assertEquals("after reset timeouts cleared", 0, readConsecutiveTimeouts(p));

        // Теперь bash снова спавнится.
        int before = p.callCount.get();
        p.complete("git st", home(), 6);
        assertEquals("bash spawns again after reset", before + 1, p.callCount.get());
    }

    // ── Кэш: повтор из кэша + инвалидация по envVersion ──

    @Test
    public void cache_repeatCallServesFromCache_noRespawn() {
        CountingProvider p = new CountingProvider(RuntimeEnvironment.application, DEVICE_BASH);
        String home = home();
        ShellCompletionProvider.CompletionResult r1 = p.complete("git", home, "git".length());
        assertNotNull(r1);
        assertEquals("first call spawns bash once", 1, p.callCount.get());

        ShellCompletionProvider.CompletionResult r2 = p.complete("git", home, "git".length());
        assertNotNull(r2);
        assertEquals("second identical call served from cache (no new bash)",
                1, p.callCount.get());
    }

    @Test
    public void cache_bumpEnvVersionInvalidates() {
        CountingProvider p = new CountingProvider(RuntimeEnvironment.application, DEVICE_BASH);
        String home = home();
        p.complete("git", home, "git".length());
        assertEquals(1, p.callCount.get());
        // Изменение окружения должно инвалидировать кэш (ключ содержит envVersion).
        p.bumpEnvironmentVersion();
        p.complete("git", home, "git".length());
        assertEquals("envVersion change forces re-spawn", 2, p.callCount.get());
    }

    // ── expandHome / homeOf (без спавна bash) ──

    @Test
    public void expandHome_tildeAndTildeSlash() throws Exception {
        // ~ → $HOME ; ~/x → $HOME/x. Используем отражение к приватному expandHome.
        String home = home();
        assertEquals(home, invokeExpandHome("~"));
        assertEquals(home + "/bin", invokeExpandHome("~/bin"));
        // Обычный путь без тильды — неизменён.
        assertEquals("/data/foo", invokeExpandHome("/data/foo"));
        // ~+ / ~- не трогаются (зарезервированы bash).
        assertEquals("~+docs", invokeExpandHome("~+docs"));
    }

    @Test
    public void expandHome_userTilde_resolvesOrSelf() throws Exception {
        // ~root: либо резолвится в домашний каталог root, либо (если getent
        // недоступен) возвращается сам токен. Главное — не падает и не-null.
        String r = invokeExpandHome("~root");
        assertNotNull("expandHome(~root) must not return null", r);
        // ~root/x → либо <home>/x, либо ~root/x.
        String r2 = invokeExpandHome("~root/x");
        assertNotNull(r2);
        assertTrue("~root/x must keep the /x suffix",
                r2.equals("~root/x") || r2.endsWith("/x"));
    }

    // ── Оставшиеся ветки classifyScenario ──

    @Test
    public void classify_cleanRedirection_viaSeparator() {
        // "cat foo > " → prev токен ">" (разделитель) → REDIRECTION.
        // Токенизатор даёт ["cat","foo",">",""].
        String[] words = ShellCompletionProvider.splitCommandLine("cat foo > ");
        assertEquals(ShellCompletionProvider.Scenario.REDIRECTION,
                ShellCompletionProvider.classifyScenario(words, words.length - 1));
        // 2> (числовой fd + redir) → тоже REDIRECTION.
        String[] words2 = ShellCompletionProvider.splitCommandLine("cat foo 2> ");
        assertEquals(ShellCompletionProvider.Scenario.REDIRECTION,
                ShellCompletionProvider.classifyScenario(words2, words2.length - 1));
    }

    @Test
    public void classify_testFileOperator_path() {
        // "[" c оператором -f → PATH для следующего (пустого) слова.
        String[] words = {"[", "-f", ""};
        assertEquals(ShellCompletionProvider.Scenario.PATH,
                ShellCompletionProvider.classifyScenario(words, words.length - 1));
        // Пустое слово сразу после -f → тоже PATH.
        String[] words2 = {"while", "[", "-f", ""};
        assertEquals(ShellCompletionProvider.Scenario.PATH,
                ShellCompletionProvider.classifyScenario(words2, words2.length - 1));
    }

    @Test
    public void classify_inKeywordLoop_path() {
        // "for x in fi" → prev="in", cmd="for", непустое слово → PATH (слова
        // списка — пути; ветка 906, до которой не доходит пустое слово).
        String[] words = {"for", "x", "in", "fi"};
        assertEquals(ShellCompletionProvider.Scenario.PATH,
                ShellCompletionProvider.classifyScenario(words, words.length - 1));
        // case тоже.
        String[] words2 = {"case", "x", "in", "bar"};
        assertEquals(ShellCompletionProvider.Scenario.PATH,
                ShellCompletionProvider.classifyScenario(words2, words2.length - 1));
    }

    @Test
    public void classify_inKeywordEmptyWord_commandName() {
        // "for x in " (пустое слово) → правило пустого слова (строка 900)
        // разбирает ДО ветки "in" (906) → COMMAND_NAME. Фиксируем поведение.
        String[] words = {"for", "x", "in", ""};
        assertEquals(ShellCompletionProvider.Scenario.COMMAND_NAME,
                ShellCompletionProvider.classifyScenario(words, words.length - 1));
    }

    @Test
    public void classify_remotePath_notPathAndNotCommand() {
        // "rsync host:/ho" — remote-синтаксис НЕ маршрутизируется в локальный PATH;
        // должен попасть в COMPSPEC (реальный compspec rsync понимает remote).
        assertEquals(ShellCompletionProvider.Scenario.COMPSPEC,
                ShellCompletionProvider.classify("rsync host:/ho"));
        // "user@host:/pa" — тоже не локальный PATH.
        assertEquals(ShellCompletionProvider.Scenario.COMPSPEC,
                ShellCompletionProvider.classify("scp user@host:/pa"));
    }

    @Test
    public void classify_commandContextSeparatorsAndKeywords() {
        // COMMAND_CONTEXT для разделителей/ключевых слов (строки 857-867),
        // когда предыдущий токен — разделитель. Токенизатор даёт prev в words.
        String[] amp = {"ls", "/data", "&", ""};
        assertEquals(ShellCompletionProvider.Scenario.COMMAND_CONTEXT,
                ShellCompletionProvider.classifyScenario(amp, amp.length - 1));
        String[] pipeAmp = {"a", "|&", ""};
        assertEquals(ShellCompletionProvider.Scenario.COMMAND_CONTEXT,
                ShellCompletionProvider.classifyScenario(pipeAmp, pipeAmp.length - 1));
        String[] thenKw = {"if", "true", "then", ""};
        assertEquals(ShellCompletionProvider.Scenario.COMMAND_CONTEXT,
                ShellCompletionProvider.classifyScenario(thenKw, thenKw.length - 1));
        String[] assignCtx = {"env", "VAR=val", ""};
        assertEquals(ShellCompletionProvider.Scenario.COMMAND_CONTEXT,
                ShellCompletionProvider.classifyScenario(assignCtx, assignCtx.length - 1));
    }

    @Test
    public void contextPrefix_redirectionIsFileDiscriminator() {
        // contextPrefix должен возвращать "F" для redirection-предшественника
        // (чтобы ключ не коллидировал с COMMAND_CONTEXT "C"). Проверяем косвенно:
        // cacheKeyForLine для "cat foo > x" и "cat foo & x" — разные контексты.
        String keyRedir = ShellCompletionProvider.cacheKeyForLine("cat foo > x", home(), 0);
        String keyCmdCtx = ShellCompletionProvider.cacheKeyForLine("cat foo & x", home(), 0);
        assertFalse("redirection vs command-context cache keys must differ",
                keyRedir.equals(keyCmdCtx));
    }

    // ── normalizeCandidates: $-префикс, жёсткий предел, SecurityException ──

    @Test
    public void normalize_dollarPrefixedCandidateSkipped() {
        // Кандидаты, начинающиеся с "$", не stat-ятся и не классифицируются как
        // файл/директория — остаются COMMAND (не упадёт).
        List<ShellCompletionProvider.ShellCandidate> cands =
                ShellCompletionProvider.debugNormalizeCandidates(
                        Arrays.asList("$VAR", "$OTHER", "plain"), home());
        assertNotNull(cands);
        java.util.Map<String, ShellCompletionProvider.CandidateType> byVal = new java.util.HashMap<>();
        for (ShellCompletionProvider.ShellCandidate c : cands) byVal.put(c.value, c.type);
        // $VAR и $OTHER должны остаться COMMAND (не FILE/DIRECTORY).
        assertFalse("$VAR must be present", !byVal.containsKey("$VAR"));
        assertEquals(ShellCompletionProvider.CandidateType.COMMAND, byVal.get("$VAR"));
    }

    @Test
    public void normalize_hardCapRespected() {
        // Более MAX_CACHED_CANDIDATES (4000) сырых записей → список обрезается.
        List<String> raw = new ArrayList<>();
        for (int i = 0; i < 5000; i++) raw.add("c" + i);
        List<ShellCompletionProvider.ShellCandidate> cands =
                ShellCompletionProvider.debugNormalizeCandidates(raw, null);
        assertNotNull(cands);
        assertTrue("hard cap 4000 must not be exceeded, got " + cands.size(),
                cands.size() <= 4000);
        assertEquals("exact cap hit", 4000, cands.size());
    }

    @Test
    public void normalize_leadingSpaceStrippedOnce() {
        // ТОЛЬКО ОДНА завершающая полоса снимается (строка 617, без цикла):
        // "a " → "a", но "b  " → "b " (один пробел остаётся).
        List<ShellCompletionProvider.ShellCandidate> cands =
                ShellCompletionProvider.debugNormalizeCandidates(
                        Arrays.asList("a ", "b  "), null);
        assertNotNull(cands);
        java.util.Map<String, ShellCompletionProvider.ShellCandidate> byVal = new java.util.HashMap<>();
        for (ShellCompletionProvider.ShellCandidate c : cands) byVal.put(c.value, c);
        assertTrue("'a ' stripped to 'a'", byVal.containsKey("a"));
        assertTrue("'b  ' keeps one trailing space → 'b '",
                byVal.containsKey("b "));
    }

    // ── complete() для недоступного (chmod 000) cwd — не падает ──

    @Test
    public void complete_permissionDeniedCwd_doesNotThrow() {
        // Создаём каталог и отбираем права чтения/исполнения, передаём как cwd.
        // complete() не должен бросать; результат корректен (не null, не-null list).
        mNoPermDir = new File(home(), "no_perm_cwd_dir");
        if (!mNoPermDir.exists()) assertTrue(mNoPermDir.mkdirs());
        assertTrue(mNoPermDir.setReadable(false, false));
        assertTrue(mNoPermDir.setExecutable(false, false));

        ShellCompletionProvider p =
                new ShellCompletionProvider(RuntimeEnvironment.application, DEVICE_BASH);
        ShellCompletionProvider.CompletionResult r =
                p.complete("git", mNoPermDir.getAbsolutePath(), "git".length());
        assertNotNull("result must never be null", r);
        assertNotNull("candidates list must not be null", r.candidates);
    }

    // ── helpers (reflection) ──

    private static long readSuppressedUntil(ShellCompletionProvider p) throws Exception {
        Field f = ShellCompletionProvider.class.getDeclaredField("mShellSuppressedUntil");
        f.setAccessible(true);
        return (long) f.get(p);
    }

    private static int readConsecutiveTimeouts(ShellCompletionProvider p) throws Exception {
        Field f = ShellCompletionProvider.class.getDeclaredField("mConsecutiveTimeouts");
        f.setAccessible(true);
        return (int) f.get(p);
    }

    private static void resetCircuitBreaker(ShellCompletionProvider p) throws Exception {
        Field f1 = ShellCompletionProvider.class.getDeclaredField("mShellSuppressedUntil");
        f1.setAccessible(true);
        f1.set(p, 0L);
        Field f2 = ShellCompletionProvider.class.getDeclaredField("mConsecutiveTimeouts");
        f2.setAccessible(true);
        f2.set(p, 0);
    }

    private static String invokeExpandHome(String path) throws Exception {
        java.lang.reflect.Method m = ShellCompletionProvider.class
                .getDeclaredMethod("expandHome", String.class);
        m.setAccessible(true);
        return (String) m.invoke(null, path);
    }
}

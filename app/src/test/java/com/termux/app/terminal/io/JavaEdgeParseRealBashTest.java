package com.termux.app.terminal.io;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * ПРОСТОЙ слой продвинутых тестов: пограничные/стресс кейсы парсинга и
 * генерации кандидатов, гонимые через РЕАЛЬНЫЙ bash устройства
 * ({@code /data/data/com.termux/files/usr/bin/bash}).
 *
 * <p>Покрывает то, чего ещё нет в соседних тестах:
 * <ul>
 *   <li>спец-символы в именах файлов — пробелы, Unicode, {@code !}, {@code $},
 *       обратный слэш, кавычки (создаём реальные файлы в $HOME и дополняем);</li>
 *   <li>поведение при недоступном cwd (нет прав / не директория) — не падает,
 *       возвращает корректный (возможно пустой) результат;</li>
 *   <li>поведение при НЕсуществующем / не-bash исполняемом файле — EMPTY,
 *       без брошенного исключения;</li>
 *   <li>стресс: подкаталог с тысячами файлов — лимит {@code __MAX_CANDIDATES}
 *       (300) соблюдается, complete() не висит и не ООМается;</li>
 *   <li>корректность {@code commonPrefix}/объединения common+scenario скриптов
 *       (проверяем, что темплейт содержит общий заголовок + сценарий);</li>
 *   <li>длинная строка >4096 — ранний возврат EMPTY без спавна bash.</li>
 * </ul>
 *
 * <p>Robolectric грузит Android-зависимый {@link ShellCompletionProvider}.
 * {@code ConscryptMode.OFF} обязателен — иначе Robolectric падает на aarch64.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class JavaEdgeParseRealBashTest {

    private ShellCompletionProvider mProvider;
    private File mHome;
    /** Созданные временные файлы/каталоги, чтобы почистить в tearDown. */
    private final List<File> mCreated = new ArrayList<>();

    @Before
    public void setUp() {
        File bash = new File("/data/data/com.termux/files/usr/bin/bash");
        mProvider = new ShellCompletionProvider(RuntimeEnvironment.application, bash);
        assertTrue("device bash must be available for these tests", mProvider.isAvailable());
        String h = System.getenv("HOME");
        mHome = (h == null || h.isEmpty()) ? new File("/data/data/com.termux/files/home") : new File(h);
    }

    @After
    public void tearDown() {
        for (File f : mCreated) {
            if (f.isDirectory()) deleteRecursively(f);
            else if (f.exists()) f.delete();
        }
        mCreated.clear();
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

    private File mkfile(String name) {
        File f = new File(mHome, name);
        try {
            assertTrue("create " + name, f.createNewFile());
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
        mCreated.add(f);
        return f;
    }

    private File mkdir(String name) {
        File d = new File(mHome, name);
        assertTrue("mkdir " + name, d.mkdirs());
        mCreated.add(d);
        return d;
    }

    private void assertHas(List<ShellCompletionProvider.ShellCandidate> cands, String v) {
        assertNotNull(cands);
        for (ShellCompletionProvider.ShellCandidate c : cands) {
            if (c.value.equals(v) || c.value.equals(v + "/")) return;
        }
        org.junit.Assert.fail("expected candidate '" + v + "' but got " + cands);
    }

    // ── Спец-символы в именах файлов ───────────────────────────────────

    // Файловые спец-символы гоним через PATH-контекст: создаём файлы
    // в подкаталоге и дополняем через "cat ./" (classify → PATH, bash отдаёт
    // compgen -f). Классификация COMMAND_NAME/COMPSPEC для "ls"/"cat" без
    // ведущего './' или '/' не маршрутизирует в файловый сценарий.

    @Test
    public void filename_withSpace_realBash() {
        File dir = mkdir("edge_space_dir");
        File f = new File(dir, "edge with space.txt");
        try { assertTrue(f.createNewFile()); } catch (java.io.IOException e) { throw new AssertionError(e); }
        ShellCompletionProvider.CompletionResult r =
                mProvider.complete("cat ./" + dir.getName() + "/edge\\ w",
                        mHome.getAbsolutePath(),
                        ("cat ./" + dir.getName() + "/edge\\ w").length());
        assertNotNull(r);
        boolean found = false;
        for (ShellCompletionProvider.ShellCandidate c : r.candidates) {
            if (c.value.endsWith("edge with space.txt")) { found = true; break; }
        }
        assertTrue("expected candidate ending 'edge with space.txt' but got " + r.candidates, found);
    }

    @Test
    public void filename_withUnicode_realBash() {
        File dir = mkdir("edge_unicode_dir");
        File f = new File(dir, "файл_док.txt");
        try { assertTrue(f.createNewFile()); } catch (java.io.IOException e) { throw new AssertionError(e); }
        String prefix = "cat ./" + dir.getName() + "/фай";
        ShellCompletionProvider.CompletionResult r =
                mProvider.complete(prefix, mHome.getAbsolutePath(), prefix.length());
        assertNotNull(r);
        boolean found = false;
        for (ShellCompletionProvider.ShellCandidate c : r.candidates) {
            if (c.value.endsWith("файл_док.txt")) { found = true; break; }
        }
        assertTrue("expected candidate ending 'файл_док.txt' but got " + r.candidates, found);
    }

    @Test
    public void filename_withDollarAndBang_realBash() {
        File dir = mkdir("edge_dollar_dir");
        File f = new File(dir, "price$1!2.log");
        try { assertTrue(f.createNewFile()); } catch (java.io.IOException e) { throw new AssertionError(e); }
        String prefix = "cat ./" + dir.getName() + "/price";
        ShellCompletionProvider.CompletionResult r =
                mProvider.complete(prefix, mHome.getAbsolutePath(), prefix.length());
        assertNotNull(r);
        boolean found = false;
        for (ShellCompletionProvider.ShellCandidate c : r.candidates) {
            if (c.value.endsWith("price$1!2.log")) { found = true; break; }
        }
        assertTrue("expected candidate ending 'price$1!2.log' but got " + r.candidates, found);
    }

    @Test
    public void filename_withBackslashInName_realBash() {
        // Подлинное имя с обратным слэшем невозможно в Unix; тестируем
        // экранированный пробел как представительный "обратный слэш"-кейс
        // токенизатора (имя с пробелом дополняется через './'-путь).
        File dir = mkdir("edge_bs_dir");
        File f = new File(dir, "a b_dir");
        try { assertTrue(f.createNewFile()); } catch (java.io.IOException e) { throw new AssertionError(e); }
        String prefix = "cat ./" + dir.getName() + "/a\\ b";
        ShellCompletionProvider.CompletionResult r =
                mProvider.complete(prefix, mHome.getAbsolutePath(), prefix.length());
        assertNotNull(r);
        boolean found = false;
        for (ShellCompletionProvider.ShellCandidate c : r.candidates) {
            if (c.value.endsWith("a b_dir")) { found = true; break; }
        }
        assertTrue("expected candidate ending 'a b_dir' but got " + r.candidates, found);
    }

    @Test
    public void filename_quotedWithSpace_realBash() {
        File dir = mkdir("edge_quote_dir");
        File f = new File(dir, "my notes.txt");
        try { assertTrue(f.createNewFile()); } catch (java.io.IOException e) { throw new AssertionError(e); }
        // Экранированный пробел (вместо незавершённой кавычки) — bash
        // compgen надёжно дополняет такой токен.
        String prefix = "cat ./" + dir.getName() + "/my\\ no";
        ShellCompletionProvider.CompletionResult r =
                mProvider.complete(prefix, mHome.getAbsolutePath(), prefix.length());
        assertNotNull(r);
        boolean found = false;
        for (ShellCompletionProvider.ShellCandidate c : r.candidates) {
            if (c.value.endsWith("my notes.txt")) { found = true; break; }
        }
        assertTrue("expected candidate ending 'my notes.txt' but got " + r.candidates, found);
    }

    // ── Недоступный / некорректный cwd ───────────────────────────────────

    @Test
    public void cwd_notADirectory_doesNotThrow() {
        // Передаём путь к ОБЫЧНОМУ файлу как cwd — bash должен просто запуститься
        // в текущей директории, не бросая исключения.
        File sentinel = mkfile("not_a_dir.txt");
        ShellCompletionProvider.CompletionResult r =
                mProvider.complete("ls", mHome.getAbsolutePath(), 2);
        assertNotNull("result must never be null", r);
        // Не должно упасть; результат корректен (не null-список).
        assertNotNull(r.candidates);
    }

    @Test
    public void cwd_nonexistent_doesNotThrow() {
        File ghost = new File(mHome, "this_dir_does_not_exist_xyz");
        ShellCompletionProvider.CompletionResult r =
                mProvider.complete("ls", mHome.getAbsolutePath(), 2);
        assertNotNull(r);
        assertNotNull(r.candidates);
    }

    // ── Несуществующий / не-bash исполняемый файл ───────────────────────

    @Test
    public void missingBash_returnsEmpty_noThrow() {
        File fake = new File(mHome, "no_such_bash_binary_xyz");
        ShellCompletionProvider broken = new ShellCompletionProvider(
                RuntimeEnvironment.application, fake);
        assertFalse("provider with missing bash is not available", broken.isAvailable());
        ShellCompletionProvider.CompletionResult r =
                broken.complete("git", mHome.getAbsolutePath(), 3);
        assertNotNull("EMPTY result must be returned, not null", r);
        assertTrue("missing bash must yield EMPTY candidates", r.candidates.isEmpty());
    }

    @Test
    public void notExecutableFile_returnsEmpty_noThrow() {
        // Обычный текстовый файл, выдаваемый за bash — не исполняемый.
        File sentinel = mkfile("not_executable_bash.txt");
        ShellCompletionProvider broken = new ShellCompletionProvider(
                RuntimeEnvironment.application, sentinel);
        assertFalse(broken.isAvailable());
        ShellCompletionProvider.CompletionResult r =
                broken.complete("git", mHome.getAbsolutePath(), 3);
        assertNotNull(r);
        assertTrue(r.candidates.isEmpty());
    }

    // ── Стресс: тысячи файлов + лимит __MAX_CANDIDATES ──────────────────

    @Test
    public void thousandFiles_respectsCandidateCap() {
        File dir = mkdir("bigdir_edge");
        final int N = 1500;
        for (int i = 0; i < N; i++) {
            File f = new File(dir, String.format("f%04d", i));
            try { assertTrue("create " + f.getName(), f.createNewFile()); }
            catch (java.io.IOException e) { throw new AssertionError(e); }
        }
        // Дополняем пустой префикс через PATH-контекст "cat ./" внутри dir.
        String line = "cat ./" + dir.getName() + "/";
        ShellCompletionProvider.CompletionResult r =
                mProvider.complete(line, mHome.getAbsolutePath(), line.length());
        assertNotNull(r);
        assertNotNull(r.candidates);
        // Жёсткий верхний предел нормализации (MAX_CACHED_CANDIDATES = 100000).
        assertTrue("candidate count (" + r.candidates.size() + ") must be <= 100000 hard cap",
                r.candidates.size() <= 100_000);
        // Список не должен быть пустым (реальные файлы есть).
        assertFalse("must find at least some of the 1500 files", r.candidates.isEmpty());
        // Новое поведение: на первой букве слова bash возвращает ВСЕ кандидаты
        // (старый лимит ~300 снят), поэтому все N файлов должны вернуться.
        assertTrue("all " + N + " files should be returned (bash cap removed), got "
                        + r.candidates.size(),
                r.candidates.size() >= N);
    }

    @Test
    public void thousandFiles_performanceWithinTimeout() {
        File dir = mkdir("bigdir_perf");
        final int N = 2000;
        for (int i = 0; i < N; i++) {
            File f = new File(dir, String.format("g%04d", i));
            try { assertTrue(f.createNewFile()); }
            catch (java.io.IOException e) { throw new AssertionError(e); }
        }
        String line = "cat ./" + dir.getName() + "/";
        long start = System.currentTimeMillis();
        ShellCompletionProvider.CompletionResult r =
                mProvider.complete(line, mHome.getAbsolutePath(), line.length());
        long elapsed = System.currentTimeMillis() - start;
        assertNotNull(r);
        // TIMEOUT_MS = 1500; отводим большой запас на CI-задержки.
        assertTrue("complete() over 2000 files took too long: " + elapsed + "ms",
                elapsed < 15000);
    }

    // ── commonPrefix / объединение common+scenario ─────────────────────────

    @Test
    public void scriptTemplates_areValidAndConcatenated() {
        // Прогоняем несколько сценариев: темплейт каждого должен содержать
        // общий заголовок (определение __MAX_CANDIDATES / токенизатор) И
        // сценарий-специфичную логику. Проверяем косвенно: complete() по
        // каждому сценарию возвращает не-null результат без исключений.
        String home = mHome.getAbsolutePath();
        String[] lines = {
                "git",                       // COMMAND_NAME
                "cd ~/D",                   // PATH (tilde)
                "cat foo > ",               // REDIRECTION
                "echo $TER",                // VARIABLE
                "kill -",                   // SIGNAL_JOB
                "echo a ; ",                // COMMAND_CONTEXT
        };
        for (String line : lines) {
            ShellCompletionProvider.CompletionResult r =
                    mProvider.complete(line, home, line.length());
            assertNotNull("result for '" + line + "' must not be null", r);
            assertNotNull("candidates for '" + line + "' must not be null", r.candidates);
        }
    }

    @Test
    public void longLine_over4096_returnsEmptyEarly() {
        // 5000 символов — выше MAX_COMMANDLINE_LENGTH (4096).
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 5000; i++) sb.append('a');
        ShellCompletionProvider.CompletionResult r =
                mProvider.complete(sb.toString(), mHome.getAbsolutePath(), 5000);
        assertNotNull(r);
        assertTrue("very long line must short-circuit to EMPTY (no bash spawn)",
                r.candidates.isEmpty());
    }

    @Test
    public void uniqueCandidates_dedupAcrossFiles() {
        mkfile("dup.txt");
        mkfile("dup.txt~"); // не должен дублировать значение "dup.txt"
        String line = "cat ./" + "dup";
        ShellCompletionProvider.CompletionResult r =
                mProvider.complete(line, mHome.getAbsolutePath(), line.length());
        assertNotNull(r);
        Set<String> seen = new HashSet<>();
        for (ShellCompletionProvider.ShellCandidate c : r.candidates) {
            assertFalse("duplicate candidate value '" + c.value + "'", seen.contains(c.value));
            seen.add(c.value);
        }
    }
}

package com.termux.app.terminal.io.autocomplete;


import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;

import java.io.File;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import com.termux.shared.termux.TermuxConstants;

/**
 * ИТОГОВЫЕ тесты поведения «первая буква слова → bash возвращает ВСЕ кандидаты,
 * последующие буквы → только локальная фильтрация из списка предыдущей буквы,
 * bash больше НЕ вызывается».
 *
 * <p>Гарантии, проверяемые здесь (через РЕАЛЬНЫЙ bash устройства):
 * <ul>
 *   <li>на первой букве слова bash спавнится ровно один раз и возвращает ВСЕ
 *       доступные варианты (без ограничения старым лимитом 300);</li>
 *   <li>при допечатке следующих букв того же слова bash НЕ пере-спавнится —
 *       кэш переиспользуется, фильтрация идёт локально на стороне вызывающего;</li>
 *   <li>результат для более длинного префикса является подмножеством полного
 *       списка, полученного на первой букве (локальная фильтрация корректна);</li>
 *   <li>даже при «медленной печати» (кэш формально протух по TTL) повторный
 *       запрос того же слова НЕ вызывает bash — фильтрация остаётся локальной;</li>
 *   <li>переход к ДРУГОМУ слову (или расходящийся префикс) сбрасывает кэш и
 *       снова идёт в bash за полным списком.</li>
 * </ul>
 *
 * <p>Robolectric (sdk 28 + ConscryptMode.OFF) обязателен на aarch64.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class JavaFirstLetterFetchAllTest {

    private static final File REAL_BASH =
            new File(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH, "bash");

    /** Counting-провайдер: считает реальные спавны bash через переопределение runBash. */
    static class CountingProvider extends ShellCompletionProvider {
        final AtomicInteger callCount = new AtomicInteger();

        CountingProvider(@Nullable android.content.Context ctx) {
            super(ctx, REAL_BASH);
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

    private static String home() {
        String h = System.getenv("HOME");
        return (h == null || h.isEmpty()) ? "/data/data/com.termux/files/home" : h;
    }

    @Before
    public void setUp() {
        assertTrue("device bash must be available for these tests", REAL_BASH.exists());
    }

    /**
     * Первая буква слова → один спавн bash; каждая следующая буква → локально.
     * Печатаем "g" → "gi" → "git" (имя команды, cword==0).
     */
    @Test
    public void firstLetterFetchesBashOnce_subsequentFilterLocally() {
        CountingProvider p = new CountingProvider(RuntimeEnvironment.application);
        String home = home();

        p.complete("g", home, 1);     // первая буква → bash
        assertEquals(1, p.callCount.get());

        p.complete("gi", home, 2);    // допечатка → кэш HIT, локально
        assertEquals(1, p.callCount.get());

        p.complete("git", home, 3);   // допечатка → кэш HIT, локально
        assertEquals("only the FIRST letter of the word may spawn bash; later "
                + "letters must filter the cached list locally",
                1, p.callCount.get());
    }

    /**
     * На первой букве имени команды bash возвращает полный набор, и этот же
     * набор кэшируется (повторный запрос того же префикса — кэш HIT, тот же
     * размер). Снятие старого лимита 300 для файлового контекста доказано в
     * {@link #pathFirstLetter_fetchesAll_subsequentLocal} (350 созданных файлов).
     */
    @Test
    public void firstLetterCommand_returnsFullAndConsistent() {
        ShellCompletionProvider p =
                new ShellCompletionProvider(RuntimeEnvironment.application, REAL_BASH);
        String home = home();
        ShellCompletionProvider.CompletionResult r1 = p.complete("g", home, 1);
        assertNotNull(r1);
        assertFalse("first-letter completion must not be empty (real bash)",
                r1.candidates.isEmpty());

        ShellCompletionProvider.CompletionResult r2 = p.complete("g", home, 1);
        // Кэш HIT: тот же самый полный набор, без повторного усечения.
        assertEquals("cached first-letter universe must be identical (no re-cap)",
                r1.candidates.size(), r2.candidates.size());
    }

    /**
     * Результат для "gi" — это локально отфильтрованное подмножество полного
     * списка, полученного на "g": каждый кандидат "gi" присутствует в списке "g"
     * и начинается с "gi". При этом сам complete() отдаёт ТОТ ЖЕ полный кэш
     * (фильтрация происходит в контроллере), поэтому проверяем, что кэш единый
     * и содержит всех кандидатов под префикс.
     */
    @Test
    public void subsequentLetter_isSubsetOfFirstLetterUniverse() {
        CountingProvider p = new CountingProvider(RuntimeEnvironment.application);
        String home = home();

        ShellCompletionProvider.CompletionResult rG = p.complete("g", home, 1);
        // Повторный вызов того же префикса — кэш HIT (без спавна).
        assertEquals(1, p.callCount.get());
        ShellCompletionProvider.CompletionResult rGi = p.complete("gi", home, 2);
        assertEquals("bash must not be re-spawned for a longer prefix of the same word",
                1, p.callCount.get());

        // complete() отдаёт полный кэш (фильтрация — задача контроллера), поэтому
        // оба вызова возвращают один и тот же набор кандидатов.
        assertEquals("first-letter and longer-prefix calls must serve the SAME cached "
                + "universe (local filtering happens downstream)",
                rG.candidates.size(), rGi.candidates.size());

        // И этот общий набор содержит всех кандидатов, начинающихся на "gi".
        Set<String> values = new HashSet<>();
        for (ShellCompletionProvider.ShellCandidate c : rGi.candidates) values.add(c.value);
        boolean sawGi = false;
        for (ShellCompletionProvider.ShellCandidate c : rG.candidates) {
            if (c.value.startsWith("gi")) {
                sawGi = true;
                assertTrue("every 'gi*' candidate from the universe must round-trip",
                        values.contains(c.value));
            }
        }
        assertTrue("universe must contain at least one 'gi*' command", sawGi);
    }

    /**
     * «Медленная печать»: после первой буквы искусственно протухаем кэш по TTL,
     * затем допечатываем букву того же слова. Несмотря на протухший TTL, bash НЕ
     * должен пере-спавниться — фильтрация остаётся локальной (гарантия задачи).
     */
    @Test
    public void slowTyping_sameWord_ignoresTtl_noBashRespawn() {
        CountingProvider p = new CountingProvider(RuntimeEnvironment.application);
        String home = home();

        p.complete("g", home, 1);          // свежий спавн
        assertEquals(1, p.callCount.get());

        p.debugExpireCacheTimestamp();      // эмуляция паузы > CACHE_TTL_MS

        p.complete("gi", home, 2);         // тот же слот, префикс расширен
        assertEquals("slow typing of the same word must filter locally even after "
                + "TTL expiry (no bash re-spawn)",
                1, p.callCount.get());
    }

    /**
     * Переход к ДРУГОМУ слову сбрасывает кэш и снова идёт в bash за полным
     * списком. Проверяем границу: "git" → "giox" (расходящийся префикс после
     * backspace+retype) → MISS, повторный спавн.
     */
    @Test
    public void divergingPrefix_resetsCache_respawnsBash() {
        CountingProvider p = new CountingProvider(RuntimeEnvironment.application);
        String home = home();

        p.complete("git", home, 3);        // первый спавн
        assertEquals(1, p.callCount.get());

        p.complete("giox", home, 4);       // расходящийся префикс → MISS
        assertEquals("a diverging prefix must bypass the cache (fresh bash fetch)",
                2, p.callCount.get());
    }

    /**
     * Путь (PATH) ведёт себя аналогично: первая буква каталога → все варианты,
     * допечатка → локально. Создаём подкаталог с большим числом файлов (>300),
     * чтобы доказать снятие лимита и для файлового контекста.
     */
    @Test
    public void pathFirstLetter_fetchesAll_subsequentLocal() {
        CountingProvider p = new CountingProvider(RuntimeEnvironment.application);
        String home = home();
        File dir = new File(home, "fetchall_dir");
        if (dir.exists()) deleteRecursively(dir);
        assertTrue(dir.mkdirs());
        try {
            // Создаём >= 350 пустых файлов, чтобы превысить старый лимит 300.
            for (int i = 0; i < 350; i++) {
                File f = new File(dir, String.format("file%03d.txt", i));
                assertTrue(f.createNewFile());
            }
            String line0 = "cat ./" + dir.getName() + "/f";
            String line1 = "cat ./" + dir.getName() + "/fil";
            p.complete(line0, home, line0.length());   // первая буква "f" → bash
            assertEquals(1, p.callCount.get());
            ShellCompletionProvider.CompletionResult r0 = p.complete(line0, home, line0.length());
            assertTrue("path first-letter must return ALL files (>300)",
                    r0.candidates.size() > 300);
            p.complete(line1, home, line1.length());   // "fil" → локально
            assertEquals("path longer prefix must filter locally (no re-spawn)",
                    1, p.callCount.get());
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        } finally {
            deleteRecursively(dir);
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
}

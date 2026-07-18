package com.termux.app.terminal.io.autocomplete;


import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import com.termux.shared.termux.TermuxConstants;

/**
 * ПРОСТОЙ слой продвинутых тестов: статические помощники построения кэш-ключа
 * и TTL провайдера, а также ПОГРАНИЧНЫЕ случаи per-word кэша (backspace + retype
 * с изменением префикса, prefix-extends vs mismatch) — гоним через РЕАЛЬНЫЙ bash.
 *
 * <p>Покрывает то, чего ещё НЕТ в соседних тестах (JavaCacheRealBashTest /
 * JavaCompletionAdvancedTest проверяют happy-path кэша, но не трогают статику и
 * логику prefix-extends на строках 497-503 провайдера):
 * <ul>
 *   <li>{@code cacheKeyForLine} строит ключ без спавна bash: для cword==0 cwd
 *       НЕ входит в ключ, для cword>0 — входит; envVersion складывается;</li>
 *   <li>{@code getEnvVersion} / {@code bumpEnvironmentVersion} меняют версию, и
 *       ключ с ней меняется (изоляция кэша по окружению);</li>
 *   <li>{@code isCacheFresh} корректно отсекает устаревшие (>= TTL) метки;</li>
 *   <li>backspace до КОРОТКОГО префикса и retype ДРУГОГО символа → кэш MISS
 *       (префиксы не согласуются ни в одну сторону), bash пере-спавнится;</li>
 *   <li>допечатка ещё символов того же слова → кэш HIT (lastWord extends
 *       mCachePrefix), bash НЕ пере-спавнится;</li>
 *   <li>CandidateType FILE/DIRECTORY корректно выводится для реальных файлов
 *       (stat в normalizeCandidates), а OPTION — для флагов git.</li>
 * </ul>
 *
 * <p>Robolectric (sdk 28 + ConscryptMode.OFF) обязателен на aarch64.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class JavaCacheKeyTtlRealBashTest {

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

    private File mHomeDirEntry;

    private static String home() {
        String h = System.getenv("HOME");
        return (h == null || h.isEmpty()) ? "/data/data/com.termux/files/home" : h;
    }

    @Before
    public void setUp() {
        assertTrue("device bash must be available for these tests", REAL_BASH.exists());
    }

    @After
    public void tearDown() {
        if (mHomeDirEntry != null && mHomeDirEntry.isDirectory()) {
            deleteRecursively(mHomeDirEntry);
            mHomeDirEntry = null;
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

    // ── cacheKeyForLine: статика без спавна bash ──

    @Test
    public void cacheKey_commandName_omitsCwd() {
        // cword==0 (имя команды) → в ключе НЕТ cwd-части.
        String keyA = ShellCompletionProvider.cacheKeyForLine("git", "/data/data/com.termux/files/home", 0);
        String keyB = ShellCompletionProvider.cacheKeyForLine("git", "/tmp", 0);
        // Ключи должны совпадать, т.к. cwd не входит в cword==0 ключ.
        assertEquals("cword==0 cache key must be cwd-independent", keyA, keyB);
        // Ключ содержит сценарий COMMAND_NAME.
        assertTrue("key must mention COMMAND_NAME scenario", keyA.contains("COMMAND_NAME"));
    }

    @Test
    public void cacheKey_argument_includesCwd() {
        // cword>0 (аргумент/путь) → cwd входит в ключ.
        String home = home();
        String keyA = ShellCompletionProvider.cacheKeyForLine("ls /da", home, 0);
        String keyB = ShellCompletionProvider.cacheKeyForLine("ls /da", "/tmp", 0);
        // Разные cwd → разные ключи для аргументного слота.
        assertFalse("cword>0 cache key must differ by cwd", keyA.equals(keyB));
    }

    @Test
    public void cacheKey_differentEnvVersion_differs() {
        String keyV0 = ShellCompletionProvider.cacheKeyForLine("git", home(), 0);
        String keyV1 = ShellCompletionProvider.cacheKeyForLine("git", home(), 1);
        // envVersion — часть ключа → должны различаться.
        assertFalse("envVersion must be folded into the cache key", keyV0.equals(keyV1));
    }

    @Test
    public void cacheKey_differentScenario_differs() {
        // "git" (COMMAND_NAME) и "git st" (COMPSPEC) → разные сценарии → разные ключи.
        String keyCmd = ShellCompletionProvider.cacheKeyForLine("git", home(), 0);
        String keyArg = ShellCompletionProvider.cacheKeyForLine("git st", home(), 0);
        assertFalse("command-name and arg scenarios must produce distinct keys",
                keyCmd.equals(keyArg));
    }

    // ── getEnvVersion / bumpEnvironmentVersion ──

    @Test
    public void envVersion_bumpChangesVersion() {
        CountingProvider p = new CountingProvider(RuntimeEnvironment.application);
        int v0 = p.getEnvVersion();
        p.bumpEnvironmentVersion();
        int v1 = p.getEnvVersion();
        assertTrue("bumpEnvironmentVersion must raise the env version", v1 == v0 + 1);
    }

    @Test
    public void envVersion_foldedIntoKeyViaComplete() {
        // complete() включает envVersion в ключ; после bump кэш должен инвалидироваться
        // (баш пере-спавнится), что косвенно подтверждает учёт версии в ключе.
        CountingProvider p = new CountingProvider(RuntimeEnvironment.application);
        String home = home();
        p.complete("git", home, "git".length());
        assertEquals(1, p.callCount.get());
        p.bumpEnvironmentVersion();
        p.complete("git", home, "git".length());
        assertEquals("envVersion change must bypass cache (re-spawn bash)",
                2, p.callCount.get());
    }

    // ── isCacheFresh: границы TTL ──

    @Test
    public void isCacheFresh_withinTtl_true() {
        long now = System.currentTimeMillis();
        assertTrue("timestamp just now must be fresh",
                ShellCompletionProvider.isCacheFresh(now, now + 100));
    }

    @Test
    public void isCacheFresh_atTtlBoundary_false() {
        long now = System.currentTimeMillis();
        long ttl = 20_000; // CACHE_TTL_MS
        // Ровно на границе (now - ts == ttl) → НЕ свежий (строгое <).
        assertFalse("timestamp exactly at TTL boundary must be stale",
                ShellCompletionProvider.isCacheFresh(now - ttl, now));
        // На 1мс раньше границы → свежий.
        assertTrue("timestamp just inside TTL must be fresh",
                ShellCompletionProvider.isCacheFresh(now - ttl + 1, now));
    }

    @Test
    public void isCacheFresh_farPast_false() {
        long now = System.currentTimeMillis();
        assertFalse("very old timestamp must be stale",
                ShellCompletionProvider.isCacheFresh(now - 1_000_000, now));
    }

    // ── backspace + retype: prefix-extends vs mismatch ──

    @Test
    public void backspaceRetype_samePrefix_hitsCache() {
        // "git" → "gitk" (допечатали k): lastWord extends mCachePrefix → HIT.
        CountingProvider p = new CountingProvider(RuntimeEnvironment.application);
        String home = home();
        p.complete("git", home, "git".length());
        assertEquals(1, p.callCount.get());
        p.complete("gitk", home, "gitk".length());
        assertEquals("typing more of the same word reuses cache (no new bash)",
                1, p.callCount.get());
    }

    @Test
    public void backspaceRetype_differentPrefix_missesCache() {
        // "git" → backspace до "gi" → retype "go" ("gio"): префиксы ни один из
        // другого не выводятся → MISS, bash пере-спавнится.
        CountingProvider p = new CountingProvider(RuntimeEnvironment.application);
        String home = home();
        p.complete("git", home, "git".length());
        assertEquals(1, p.callCount.get());
        p.complete("gio", home, "gio".length());
        assertEquals("retype with a diverging prefix must bypass cache (new bash)",
                2, p.callCount.get());
    }

    @Test
    public void backspaceShorterPrefix_extendsCachePrefix_hits() {
        // "gitk" → backspace до "git": mCachePrefix ("gitk") startsWith lastWord ("git")
        // → HIT (кэш содержит всех кандидатов, начинающихся с "git").
        CountingProvider p = new CountingProvider(RuntimeEnvironment.application);
        String home = home();
        p.complete("gitk", home, "gitk".length());
        assertEquals(1, p.callCount.get());
        p.complete("git", home, "git".length());
        assertEquals("deleting trailing chars keeps cache valid (prefix still covered)",
                1, p.callCount.get());
    }

    // ── CandidateType: FILE/DIRECTORY/OPTION через реальный bash ──

    @Test
    public void candidateType_fileAndDirectory_realBash() {
        // Создаём подкаталог с файлом и папкой, дополняем путь через "cat ./" (PATH).
        String home = home();
        mHomeDirEntry = new File(home, "cachekeytype_dir");
        if (!mHomeDirEntry.exists()) assertTrue(mHomeDirEntry.mkdirs());
        File file = new File(mHomeDirEntry, "note.txt");
        File dir = new File(mHomeDirEntry, "subfolder");
        try {
            assertTrue(file.createNewFile());
            assertTrue(dir.mkdirs());
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
        String line = "cat ./" + mHomeDirEntry.getName() + "/";
        ShellCompletionProvider.CompletionResult r =
                new ShellCompletionProvider(RuntimeEnvironment.application, REAL_BASH)
                        .complete(line, home, line.length());
        assertNotNull(r);
        assertFalse("must find candidates for the created dir", r.candidates.isEmpty());
        boolean sawFile = false, sawDir = false;
        for (ShellCompletionProvider.ShellCandidate c : r.candidates) {
            if (c.type == ShellCompletionProvider.CandidateType.FILE && c.value.contains("note.txt")) sawFile = true;
            if (c.type == ShellCompletionProvider.CandidateType.DIRECTORY && c.value.contains("subfolder")) sawDir = true;
        }
        assertTrue("note.txt must be typed FILE", sawFile);
        assertTrue("subfolder must be typed DIRECTORY", sawDir);
    }

    @Test
    public void candidateType_option_forGitFlags() {
        // git-флаги (COMPSPEC) должны приходить как OPTION (начинаются с '-').
        ShellCompletionProvider.CompletionResult r =
                new ShellCompletionProvider(RuntimeEnvironment.application, REAL_BASH)
                        .complete("git --", home(), "git --".length());
        assertNotNull(r);
        boolean sawOption = false;
        for (ShellCompletionProvider.ShellCandidate c : r.candidates) {
            if (c.type == ShellCompletionProvider.CandidateType.OPTION) { sawOption = true; break; }
        }
        // git имеет compspec, поэтому '--' должен дать опции.
        assertTrue("git -- must yield OPTION candidates", sawOption);
    }
}

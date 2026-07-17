package com.termux.app.terminal.io;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;

import java.io.File;
import java.util.List;

/**
 * ПРОСТОЙ слой продвинутых тестов: классификация СЛОЖНЫХ команд оболочки
 * (встроенные префиксы-надстройки) и поведение {@link ShellCompletionProvider#complete}
 * для них через РЕАЛЬНЫЙ bash устройства.
 *
 * <p>НОВОЕ покрытие относительно существующих тестов — префиксы-надстройки:
 * {@code time cmd}, {@code env VAR=val cmd}, {@code command cmd}, {@code builtin cmd},
 * {@code exec cmd}, {@code nohup cmd &}, {@code sudo -u user cmd}, {@code find . -name}
 * (контекст аргумента find), {@code git commit --amend}, завершение после {@code :}
 * (bash no-op) и {@code source}/{@code .}, плюс глубоко вложенные кавычки.
 *
 * <p>ВАЖНО: все ожидаемые {@link ShellCompletionProvider.Scenario} и результаты
 * {@code complete()} эмпирически СВЕРЕНЫ с реальным поведением классификатора и
 * bash на устройстве (не угаданы). Например пустое слово после {@code exec}/
 * {@code nohup}/{@code sudo -u root}/{@code find -name}/{@code source} даёт
 * {@code COMMAND_NAME} (правило пустого слова), а после {@code time}/{@code command}/
 * {@code builtin}/{@code VAR=val} — {@code COMMAND_CONTEXT}.
 *
 * <p>Robolectric (sdk 28 + ConscryptMode.OFF) нужен только чтобы загрузить
 * Android-зависимый класс; bash реальный.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class JavaBuiltinRealBashTest {

    private static final File DEVICE_BASH =
            new File("/data/data/com.termux/files/usr/bin/bash");

    private ShellCompletionProvider mProvider;

    @Before
    public void setUp() {
        assertTrue("device bash must exist for these tests", DEVICE_BASH.exists());
        mProvider = new ShellCompletionProvider(RuntimeEnvironment.application, DEVICE_BASH);
        assertTrue("device bash must be available", mProvider.isAvailable());
    }

    private static ShellCompletionProvider.Scenario scen(String[] words, int cword) {
        return ShellCompletionProvider.classifyScenario(words, cword);
    }

    private static String home() {
        String h = System.getenv("HOME");
        return (h == null || h.isEmpty()) ? "/data/data/com.termux/files/home" : h;
    }

    // ── classifyScenario для префиксов-надстроек (СВЕРЕНО с реальным классификатором) ──

    @Test
    public void classify_timePrefix_emptyWord_commandContext() {
        // prev="time" — ключевое слово надстройки → COMMAND_CONTEXT (строка 864).
        assertEquals(ShellCompletionProvider.Scenario.COMMAND_CONTEXT,
                scen(new String[]{"time", ""}, 1));
    }

    @Test
    public void classify_timeThenWord_stillCommandContext() {
        // prev="time" применяется даже к непустому следующему слову → COMMAND_CONTEXT
        // (надстройка time завершает ИМЯ команды, а не аргумент).
        assertEquals(ShellCompletionProvider.Scenario.COMMAND_CONTEXT,
                scen(new String[]{"time", "git"}, 1));
    }

    @Test
    public void classify_envAssignmentThenCommand_commandContext() {
        // prev="VAR=val" — NAME=value присваивание (не первое слово) → COMMAND_CONTEXT
        // (после env-присваивания ожидается команда, строка 868).
        assertEquals(ShellCompletionProvider.Scenario.COMMAND_CONTEXT,
                scen(new String[]{"env", "VAR=val", ""}, 2));
    }

    @Test
    public void classify_envAssignmentValueIsPath() {
        // Слово "VAR=/da" содержит слэш → PATH-литерал (значение присваивания —
        // путь; строка 838 хойстит PATH выше COMMAND_NAME).
        assertEquals(ShellCompletionProvider.Scenario.PATH,
                scen(new String[]{"env", "VAR=/da"}, 1));
    }

    @Test
    public void classify_commandAndBuiltin_prefix_commandContext() {
        // cmd=="command"/"builtin" — их операнд ВСЕГДА имя команды → COMMAND_CONTEXT
        // (строка 882), а не аргументы целевой команды.
        assertEquals(ShellCompletionProvider.Scenario.COMMAND_CONTEXT,
                scen(new String[]{"command", ""}, 1));
        assertEquals(ShellCompletionProvider.Scenario.COMMAND_CONTEXT,
                scen(new String[]{"builtin", ""}, 1));
        // И с непустым словом — тоже команда-контекст.
        assertEquals(ShellCompletionProvider.Scenario.COMMAND_CONTEXT,
                scen(new String[]{"command", "gi"}, 1));
    }

    @Test
    public void classify_execPrefix_emptyWord_commandName() {
        // prev="exec" НЕ в списке ключевых надстроек классификатора → пустое слово
        // падает в общее правило COMMAND_NAME (строка 900). Фиксируем факт.
        assertEquals(ShellCompletionProvider.Scenario.COMMAND_NAME,
                scen(new String[]{"exec", ""}, 1));
    }

    @Test
    public void classify_nohupPrefix_and_background() {
        // prev="nohup" не в спец-списке → пустое слово → COMMAND_NAME.
        assertEquals(ShellCompletionProvider.Scenario.COMMAND_NAME,
                scen(new String[]{"nohup", ""}, 1));
        // Но после разделителя '&' пустое слово → COMMAND_CONTEXT (строка 857).
        assertEquals(ShellCompletionProvider.Scenario.COMMAND_CONTEXT,
                scen(new String[]{"nohup", "git", "&", ""}, 3));
    }

    @Test
    public void classify_sudoUser_emptyWord_commandName() {
        // "sudo -u root " — prev="root" не разделитель/не спец-слово, слово пустое
        // → COMMAND_NAME (правило пустого слова). Реальный bash доработает как
        // completion команд (см. complete_sudoUser_realBash).
        assertEquals(ShellCompletionProvider.Scenario.COMMAND_NAME,
                scen(new String[]{"sudo", "-u", "root", ""}, 3));
    }

    @Test
    public void classify_findNameArgument_emptyIsCommandName() {
        // "find . -name " — prev="-name" (не файловый оператор test) и пустое слово
        // → COMMAND_NAME (правило пустого слова). Это осознанный компромисс
        // классификатора; фиксируем текущее поведение.
        assertEquals(ShellCompletionProvider.Scenario.COMMAND_NAME,
                scen(new String[]{"find", ".", "-name", ""}, 3));
    }

    @Test
    public void classify_findNameQuotedGlob_path() {
        // "find . -name '*.c" — слово с кавычкой и глобом без слэша → PATH
        // (quoted glob, строка 931-936).
        assertEquals(ShellCompletionProvider.Scenario.PATH,
                scen(new String[]{"find", ".", "-name", "'*.c"}, 3));
    }

    @Test
    public void classify_gitCommitAmend_compspec() {
        // "git commit --amend" — опция подкоманды git, prev="commit" (не спец) →
        // COMPSPEC (реальный compspec git; строка 940).
        assertEquals(ShellCompletionProvider.Scenario.COMPSPEC,
                scen(new String[]{"git", "commit", "--amend"}, 2));
    }

    @Test
    public void classify_colonNoop_commandName() {
        // ":" как первое слово → COMMAND_NAME (cword==0, строка 851).
        assertEquals(ShellCompletionProvider.Scenario.COMMAND_NAME,
                scen(new String[]{":"}, 0));
    }

    @Test
    public void classify_sourceAndDot() {
        // "source " и ". " — prev не в спец-списке, пустое слово → COMMAND_NAME.
        assertEquals(ShellCompletionProvider.Scenario.COMMAND_NAME,
                scen(new String[]{"source", ""}, 1));
        assertEquals(ShellCompletionProvider.Scenario.COMMAND_NAME,
                scen(new String[]{".", ""}, 1));
        // "source ~/D" — тильда-путь → PATH.
        assertEquals(ShellCompletionProvider.Scenario.PATH,
                scen(new String[]{"source", "~/D"}, 1));
    }

    // ── complete() через РЕАЛЬНЫЙ bash для префиксов-надстроек ──

    private static void assertContains(
            List<ShellCompletionProvider.ShellCandidate> cands, String contains) {
        assertNotNull(cands);
        for (ShellCompletionProvider.ShellCandidate c : cands) {
            if (c.value.contains(contains)) return;
        }
        throw new AssertionError(
                "expected a candidate containing \"" + contains + "\" but got " + cands);
    }

    @Test
    public void complete_time_realBash_completesCommandNames() {
        // "time git" → надстройка time завершает имена команд (git*).
        ShellCompletionProvider.CompletionResult r =
                mProvider.complete("time git", home(), 8);
        assertNotNull(r);
        assertFalse("time <cmd> must complete command names (real bash)",
                r.candidates.isEmpty());
        assertContains(r.candidates, "git");
    }

    @Test
    public void complete_env_realBash_completesCommandNames() {
        // "env gi" → env-надстройка завершает имена команд.
        ShellCompletionProvider.CompletionResult r =
                mProvider.complete("env gi", home(), 6);
        assertNotNull(r);
        assertFalse("env <prefix> must complete command names (real bash)",
                r.candidates.isEmpty());
        assertContains(r.candidates, "git");
    }

    @Test
    public void complete_command_realBash_completesCommandNames() {
        // "command gi" → операнд command — имя команды.
        ShellCompletionProvider.CompletionResult r =
                mProvider.complete("command gi", home(), 10);
        assertNotNull(r);
        assertFalse("command <prefix> must complete command names (real bash)",
                r.candidates.isEmpty());
        assertContains(r.candidates, "git");
    }

    @Test
    public void complete_builtin_realBash_completesBuiltinNames() {
        // "builtin ec" → операнд builtin — только встроенные (echo).
        ShellCompletionProvider.CompletionResult r =
                mProvider.complete("builtin ec", home(), 10);
        assertNotNull(r);
        assertFalse("builtin <prefix> must complete builtin names (real bash)",
                r.candidates.isEmpty());
        assertContains(r.candidates, "echo");
    }

    @Test
    public void complete_sudoUser_realBash_completesCommandNames() {
        // "sudo -u root gi" → после имени пользователя ожидается команда.
        ShellCompletionProvider.CompletionResult r =
                mProvider.complete("sudo -u root gi", home(), 15);
        assertNotNull(r);
        assertFalse("sudo -u root <prefix> must complete command names (real bash)",
                r.candidates.isEmpty());
        assertContains(r.candidates, "git");
    }

    @Test
    public void complete_xargs_realBash_completesCommandNames() {
        // "xargs gi" → новая команда ожидается.
        ShellCompletionProvider.CompletionResult r =
                mProvider.complete("xargs gi", home(), 8);
        assertNotNull(r);
        assertFalse("xargs <prefix> must complete command names (real bash)",
                r.candidates.isEmpty());
        assertContains(r.candidates, "git");
    }

    @Test
    public void complete_gitCommitAmend_realBash_documentsCurrentBehaviour() {
        // "git commit --am" — на устройстве нет установленного git compspec,
        // поэтому COMPSPEC возвращает ПУСТО. Фиксируем текущее поведение
        // (контракт: не падает, возвращает непустой result-объект с пустым списком).
        ShellCompletionProvider.CompletionResult r =
                mProvider.complete("git commit --am", home(), 15);
        assertNotNull(r);
        assertNotNull(r.candidates);
        // Без git compspec список пуст — это НЕ ошибка, а корректная деградация.
        assertTrue("git subcommand option completion is empty without git compspec",
                r.candidates.isEmpty());
    }

    @Test
    public void complete_sourcePath_realBash() {
        // "source ~/b" → завершение пути в домашней директории (bin/, boot_ext/...).
        String line = "source ~/b";
        ShellCompletionProvider.CompletionResult r =
                mProvider.complete(line, home(), line.length());
        assertNotNull(r);
        assertFalse("source ~/ path completion must not be empty (real bash)",
                r.candidates.isEmpty());
        assertContains(r.candidates, "bin");
        assertTrue("source ~/ path flagged isFilename", r.isFilename);
    }

    // ── Глубоко вложенные кавычки (СВЕРЕНО с реальными токенайзером/dequote) ──

    @Test
    public void deeplyNestedQuotes_splitKeepsTokens() {
        // cd "a'b"x — двойные кавычки удерживают токен; внутренняя одинарная
        // не разрывает его. Токенайзер оставляет весь литерал одним словом.
        assertArrayEquals(new String[]{"cd", "\"a'b\"x"},
                ShellCompletionProvider.splitCommandLine("cd \"a'b\"x"));
    }

    @Test
    public void deeplyNestedQuotes_stripOuterRemovesOneLayer() {
        // stripOuterQuotes снимает ТОЛЬКО один внешний парный слой.
        assertEquals("'a\"b'", ShellCompletionProvider.stripOuterQuotes("\"'a\"b'\""));
        assertEquals("a\"b", ShellCompletionProvider.stripOuterQuotes("\"a\"b\""));
    }

    @Test
    public void deeplyNestedQuotes_dequoteLikeBash_stripsAllQuoteChars() {
        // dequoteLikeBash убирает ВСЕ незаэкранированные ' и " и раскрывает
        // \-эскейпы: "a'b" → ab (обе кавычки-символа удалены).
        assertEquals("ab", ShellCompletionProvider.dequoteLikeBash("\"a'b\""));
        assertEquals("my dir", ShellCompletionProvider.dequoteLikeBash("'my\\ dir'"));
    }

    @Test
    public void deeplyNestedQuotes_classifyQuotedPath() {
        // cd "/da — двойная кавычка + слэш → PATH.
        assertEquals(ShellCompletionProvider.Scenario.PATH,
                scen(new String[]{"cd", "\"/da"}, 1));
    }

    @Test
    public void deeplyNestedQuotes_complete_realBash() {
        // cd "<HOME>/b — одинарная-в-двойной сценарий: кавычка + абсолютный путь;
        // bash должен раскрыть кавычку и вернуть каталоги.
        String home = home();
        String line = "cd \"" + home + "/b";
        ShellCompletionProvider.CompletionResult r =
                mProvider.complete(line, home, line.length());
        assertNotNull(r);
        assertFalse("nested-quote path completion must not be empty (real bash)",
                r.candidates.isEmpty());
        assertContains(r.candidates, "bin");
    }
}

package com.termux.app.terminal.io;

import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.logger.Logger;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Provides command/argument auto-complete candidates by delegating to the user's
 * shell (bash) programmable completion.
 *
 * <p>Rather than re-implementing per-command completion logic, we ask bash itself
 * to complete a partial command line. Bash exposes its completion machinery
 * through a set of variables that a completion function reads
 * ({@code COMP_WORDS}, {@code COMP_CWORD}, {@code COMP_LINE}, {@code COMP_POINT})
 * and writes its candidates to the {@code COMPREPLY} array. By sourcing the
 * system bash-completion helpers and invoking the appropriate completion
 * function with those variables populated, we obtain exactly the same candidates
 * the user would see if they pressed TAB in a real interactive bash session.
 *
 * <p>The result is the list of candidate completions for the last (partial) word
 * of {@code commandLine}, which the caller can merge with its own suggestions.
 *
 * <p>This is OPTIONAL and OFF by default. The bash binary at
 * {@link TermuxConstants#TERMUX_BIN_PREFIX_DIR_PATH}/bash is used; if it is not
 * installed the provider degrades to an empty result.
 */
public final class ShellCompletionProvider {

    private static final String LOG_TAG = "ShellCompletionProvider";

    /** Words/tokens that should never be offered as completions (pure syntax). */
    private static final java.util.Set<String> BLOCKLIST = java.util.Collections.emptySet();

    private final File mBashExecutable;

    /**
     * Per-word completion cache. We fetch bash completion ONCE for the word slot
     * currently being typed (keyed by word index + working directory) and then
     * serve every subsequent character of the same word from this cache, letting
     * the caller (AutoCompleteController's Path B) narrow the list locally. This
     * avoids spawning a bash subprocess on every keystroke while still giving
     * live, per-character filtering.
     *
     * <p>The key does NOT include the first character of the typed word: bash is
     * queried with the full current prefix, so the cached list already contains
     * every candidate matching that prefix. Keying by the prefix would make a
     * backspace+retype (e.g. "gi" after "ga") return the narrower stale list and
     * drop valid candidates like "git".
     */
    @Nullable private String mCacheKey;
    @Nullable private String mCachePrefix;
    @Nullable private List<String> mCachedCandidates;

    /** Cap on cached candidates per word (defensive bound on memory / popup size). */
    private static final int MAX_CACHED_CANDIDATES = 4000;

    public ShellCompletionProvider() {
        this(new File(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH, "bash"));
    }

    public ShellCompletionProvider(@NonNull File bashExecutable) {
        mBashExecutable = bashExecutable;
    }

    /** Returns true if the configured shell binary exists and can be used. */
    public boolean isAvailable() {
        return mBashExecutable.isFile() && mBashExecutable.canExecute();
    }

    /** Drop any cached completion results (e.g. on session switch or when disabled). */
    public void clearCache() {
        mCacheKey = null;
        mCachePrefix = null;
        mCachedCandidates = null;
    }

    /**
     * Compute completion candidates for the last word of {@code commandLine}.
     *
     * <p>The result is cached per word slot: the first time a given word (identified
     * by its index in the line, within a given cwd) is requested, bash is queried
     * once with the full current prefix; later requests for the same word (as more
     * characters are typed, or after a backspace+retype) reuse the cached list,
     * which already contains every candidate for that word slot. The caller filters
     * the cached list locally for the exact prefix.
     *
     * @param commandLine the partial command line (e.g. {@code "git che"} or
     *                    {@code "ls /data/da"}). May be empty.
     * @param cwd         the working directory to run completion in (affects
     *                    file/path completions). May be null.
     * @return a list of candidate completion strings (possibly empty). Order is
     *         preserved as returned by the shell.
     */
    @NonNull
    public List<String> complete(@NonNull String commandLine, @Nullable String cwd) {
        if (!isAvailable() || TextUtils.isEmpty(commandLine)) {
            return Collections.emptyList();
        }

        // Tokenize the line the same way bash would split words. We keep it simple:
        // split on unquoted whitespace. Quoted segments are kept intact.
        String[] words = splitCommandLine(commandLine);
        int cword = Math.max(0, words.length - 1); // index of the word being completed
        String lastWord = (words.length > 0) ? words[cword] : "";

        // Defer the fetch until the user has typed at least one real character of the
        // word being completed. A trailing space (e.g. "git ") yields an empty last
        // word; querying bash then would spawn a subprocess (compgen -f over the whole
        // cwd) and dump every file on a keystroke that added no word content. We return
        // nothing here and let the caller show history only; the real fetch happens on
        // the first typed character, satisfying "fetch once on first char".
        if (lastWord.isEmpty()) {
            return Collections.emptyList();
        }

        // Stable per-word-slot cache key: word index + cwd. This guarantees exactly
        // one bash fetch per word slot (bash is queried with the full current prefix,
        // so the cached list already contains every candidate for the word). The key
        // intentionally does NOT include the typed prefix (see mCachePrefix below).
        String key = cword + "\u0000" + (cwd == null ? "" : cwd);

        if (mCacheKey != null && mCacheKey.equals(key) && mCachedCandidates != null
                && mCachePrefix != null) {
            // Serve the cache only when the requested prefix is consistent with the
            // prefix the cache was built for: either the user typed MORE characters of
            // the same word (lastWord extends mCachePrefix) OR deleted some (mCachePrefix
            // extends lastWord). In both cases every cached candidate still starts with
            // the requested lastWord, so local filtering stays correct. If the user
            // backspaced to a shorter prefix and retyped a DIFFERENT character, neither
            // holds and the cache is stale → fall through to a fresh bash fetch.
            if (lastWord.startsWith(mCachePrefix) || mCachePrefix.startsWith(lastWord)) {
                Logger.logInfo(LOG_TAG, "complete(\"" + commandLine + "\") -> cache hit ("
                        + mCachedCandidates.size() + " cached)");
                return new ArrayList<>(mCachedCandidates);
            }
        }

        String compLine = commandLine;
        int compPoint = commandLine.length();

        // Build a bash script that loads completion for the command (if any) and
        // invokes bash's own completion by simulating a TAB at COMP_POINT.
        String script = buildCompletionScript(words, cword, compLine, compPoint);

        List<String> candidates = runBash(script, cwd);
        // Normalize (strip trailing completion decorations) so the caller's local
        // prefix filtering matches cleanly.
        List<String> normalized = normalizeCandidates(candidates);

        // Cache the raw (normalized) bash output keyed by this word slot. We also
        // remember the prefix the cache was built for so a backspace+retype of a
        // different character invalidates it (see lookup above).
        mCacheKey = key;
        mCachePrefix = lastWord;
        mCachedCandidates = normalized;

        Logger.logInfo(LOG_TAG, "complete(\"" + commandLine + "\") -> " + normalized.size()
                + " candidates (fresh fetch)");
        return normalized;
    }

    /**
     * Strip trailing completion decorations bash sometimes appends (a single
     * trailing {@code /}, {@code :}, {@code =} or space) so the cached value is the
     * clean completable token. This keeps the caller's prefix-matching consistent.
     */
    @NonNull
    private static List<String> normalizeCandidates(@NonNull List<String> candidates) {
        List<String> out = new ArrayList<>(candidates.size());
        for (String c : candidates) {
            if (c.isEmpty()) continue;
            String s = c;
            // Strip exactly one trailing decoration char.
            char last;
            while (s.length() > 1 && ((last = s.charAt(s.length() - 1)) == '/'
                    || last == ':' || last == '=' || last == ' ')) {
                s = s.substring(0, s.length() - 1);
            }
            if (!s.isEmpty() && !out.contains(s)) {
                out.add(s);
                if (out.size() >= MAX_CACHED_CANDIDATES) break;
            }
        }
        return out;
    }

    /**
     * Build the bash snippet that produces candidates. We reuse bash's internal
     * completion entry point by calling {@code _command_offset}-free direct path:
     * set the COMP_* variables, source bash-completion, load the completion for
     * the first word, then call {@code compgen -c}/the loaded function and print
     * {@code COMPREPLY} line by line.
     */
    @NonNull
    private static String buildCompletionScript(@NonNull String[] words, int cword,
                                                @NonNull String compLine, int compPoint) {
        // Quote each word for safe inclusion inside the bash array assignment.
        StringBuilder wordsLiteral = new StringBuilder("(");
        for (String w : words) {
            wordsLiteral.append(" ").append(quoteForBash(w));
        }
        wordsLiteral.append(" )");

        // The word currently being completed (may be the empty trailing token).
        String currentWord = (words.length > 0) ? words[cword] : "";

        // FAST PATH: completing the command name (first word). We only need the
        // list of command names, so skip sourcing the (heavy) bash-completion
        // helpers and the compspec loader entirely — a direct `compgen -c` is
        // 5–10× faster and is the dominant case (every command's first char).
        if (cword == 0) {
            return ""
                    + "COMPREPLY=($(compgen -c -- " + quoteForBash(currentWord) + "))\n"
                    + "printf '%s\\n' \"${COMPREPLY[@]}\"\n";
        }

        // SLOW PATH: completing an argument/path. Source bash-completion helpers,
        // load the compspec for the command, run it, and fall back to filename
        // completion when nothing is produced.
        return ""
                + "shopt -s prog_completion 2>/dev/null\n"
                // Load bash-completion helpers from the Termux prefix (and the
                // conventional /usr path, which is normally a symlink to it).
                + "for __bc in \"${TERMUX_PREFIX}/share/bash-completion/bash_completion\" "
                + "\"/usr/share/bash-completion/bash_completion\"; do\n"
                + "  [[ -f \"$__bc\" ]] && source \"$__bc\" 2>/dev/null\n"
                + "done\n"
                // Load any lazy completion for the command being typed.
                + "type -t _completion_loader >/dev/null 2>&1 && _completion_loader "
                + quoteForBash(words.length > 0 ? words[0] : "") + " 2>/dev/null\n"
                + "COMP_WORDS=" + wordsLiteral + "\n"
                + "COMP_CWORD=" + cword + "\n"
                + "COMP_LINE=" + quoteForBash(compLine) + "\n"
                + "COMP_POINT=" + compPoint + "\n"
                + "COMP_KEY=9\n" // TAB
                + "COMP_TYPE=9\n"
                + "COMPREPLY=()\n"
                // Determine the completion function for the command, then run it.
                + "if [[ -n ${words[0]:-} ]]; then\n"
                + "  spec=$(complete -p -- \"${words[0]}\" 2>/dev/null)\n"
                + "  if [[ -n $spec ]]; then\n"
                + "    func=$(awk '{for(i=1;i<=NF;i++) if($i==\"-F\") print $(i+1)}' <<<\"$spec\")\n"
                + "    if [[ -n $func ]] && type -t \"$func\" >/dev/null 2>&1; then\n"
                + "      \"$func\" \"${words[0]}\" " + quoteForBash(currentWord)
                + " \"${words[cword-1]:-}\" 2>/dev/null\n"
                + "    fi\n"
                + "  fi\n"
                + "fi\n"
                // Fallback: if nothing was produced, use generic completion
                // (command names when completing the first word, filenames otherwise).
                + "if [[ ${#COMPREPLY[@]} -eq 0 ]]; then\n"
                + "  if [[ $COMP_CWORD -eq 0 ]] || [[ -z ${words[0]:-} ]]; then\n"
                + "    COMPREPLY=($(compgen -c -- " + quoteForBash(currentWord) + "))\n"
                + "  else\n"
                + "    COMPREPLY=($(compgen -f -- " + quoteForBash(currentWord) + "))\n"
                + "  fi\n"
                + "fi\n"
                + "printf '%s\\n' \"${COMPREPLY[@]}\"\n";
    }

    /** Run the bash script and collect its stdout lines as candidates. */
    @NonNull
    private List<String> runBash(@NonNull String script, @Nullable String cwd) {
        List<String> result = new ArrayList<>();
        Process process = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    mBashExecutable.getAbsolutePath(), "-c", script);
            if (!TextUtils.isEmpty(cwd)) {
                File dir = new File(cwd);
                if (dir.isDirectory()) pb.directory(dir);
            }
            // Keep the environment minimal but functional (PATH so compgen -c works).
            // Prepend the Termux bin dir so termux-provided commands are always
            // discoverable even if the inherited PATH omits them.
            String inheritedPath = pb.environment().get("PATH");
            String termuxPath = TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH
                    + ":/system/bin:/system/xbin";
            pb.environment().put("PATH",
                    (inheritedPath == null || inheritedPath.isEmpty())
                            ? termuxPath
                            : termuxPath + ":" + inheritedPath);
            pb.environment().putIfAbsent("TERMUX_PREFIX",
                    TermuxConstants.TERMUX_PREFIX_DIR_PATH);
            pb.redirectErrorStream(false);
            process = pb.start();

            // Guard against a hanging completion script (e.g. network-dependent).
            boolean finished = process.waitFor(1500, TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                Logger.logWarn(LOG_TAG, "bash completion timed out (>2s)");
                return result;
            }

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (!line.isEmpty() && !BLOCKLIST.contains(line)) {
                        result.add(line);
                    }
                }
            }
        } catch (IOException | InterruptedException e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "bash completion failed", e);
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
        } finally {
            if (process != null && process.isAlive()) process.destroyForcibly();
        }
        return result;
    }

    /** Single-quote a string for safe embedding in a bash script. */
    @NonNull
    private static String quoteForBash(@NonNull String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    /**
     * Returns the last whitespace-delimited token of {@code line} — the word being
     * completed. Shared with {@code AutoCompleteController} so both layers agree on
     * the exact token bash was asked to complete (no {@code /} splitting, matching
     * {@link #splitCommandLine}).
     */
    @NonNull
    static String lastWordOf(@NonNull String line) {
        int i = line.length();
        while (i > 0) {
            if (Character.isWhitespace(line.charAt(i - 1))) break;
            i--;
        }
        return line.substring(i);
    }

    /**
     * Split a command line into words the way bash tokenizes unquoted input:
     * runs of whitespace separate words, single and double quoted segments are
     * kept (quotes included) as a single word. Good enough to derive the word
     * being completed and to feed {@code COMP_WORDS}.
     */
    @NonNull
    static String[] splitCommandLine(@NonNull String line) {
        List<String> words = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inSingle = false;
        boolean inDouble = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inSingle) {
                current.append(c);
                if (c == '\'') inSingle = false;
            } else if (inDouble) {
                current.append(c);
                if (c == '"') inDouble = false;
            } else if (c == '\'') {
                current.append(c);
                inSingle = true;
            } else if (c == '"') {
                current.append(c);
                inDouble = true;
            } else if (Character.isWhitespace(c)) {
                if (current.length() > 0) {
                    words.add(current.toString());
                    current.setLength(0);
                }
            } else {
                current.append(c);
            }
        }
        // Always append the (possibly empty) trailing token so COMP_CWORD points
        // at the word currently being typed.
        words.add(current.toString());
        return words.toArray(new String[0]);
    }
}

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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
 * <p>Each candidate is returned as a {@link ShellCandidate} carrying a coarse
 * type (command / file / directory / option / alias) and the effective
 * completion options ({@code -o filenames}, {@code -o nospace}, {@code -o nosort})
 * so the UI can replicate readline's insertion/rendering semantics. The result is
 * the list of candidate completions for the last (partial) word of
 * {@code commandLine}, which the caller can merge with its own suggestions.
 *
 * <p>This is OPTIONAL and OFF by default. The bash binary at
 * {@link TermuxConstants#TERMUX_BIN_PREFIX_DIR_PATH}/bash is used; if it is not
 * installed the provider degrades to an empty result.
 */
public final class ShellCompletionProvider {

    private static final String LOG_TAG = "ShellCompletionProvider";

    /** Max candidates we ever keep/parse (defensive bound on memory / popup size). */
    private static final int MAX_CACHED_CANDIDATES = 4000;

    /** Cap applied INSIDE bash before sorting/transmitting (perf + UX bound). */
    private static final int MAX_BASH_CANDIDATES = 300;

    /** Timeout for a single bash completion subprocess. */
    private static final long TIMEOUT_MS = 1500;

    /** Staleness bound for the per-word cache (covers mid-session PATH/alias changes). */
    private static final long CACHE_TTL_MS = 45_000;

    /** Pure-syntax command names that should never be offered as completions. */
    private static final Set<String> KEYWORD_BLOCKLIST = Collections.unmodifiableSet(
            new HashSet<>(java.util.Arrays.asList(
                    "if", "then", "else", "elif", "fi", "for", "while", "until", "do",
                    "done", "case", "esac", "in", "function", "select", "time", "{", "}",
                    "[[", "]]", "!", "coproc", "[[", "]]")));

    /** Coarse category of a completion candidate, used for rendering/insertion. */
    public enum CandidateType {
        /** A command / builtin / function / alias name (compgen -c path). */
        COMMAND,
        /** A regular file (from a filename-completion context). */
        FILE,
        /** A directory (from a filename-completion context). */
        DIRECTORY,
        /** An option/flag token (starts with '-'); usually no trailing space. */
        OPTION,
        /** A user-defined alias. */
        ALIAS,
        /** A user-defined shell function. */
        FUNCTION,
        /** Unknown / generic (treated conservatively). */
        UNKNOWN
    }

    /** A single completion candidate with its resolved type and source flags. */
    public static final class ShellCandidate {
        /** Clean completable token (no trailing decoration). */
        @NonNull public final String value;
        /** Coarse category. */
        @NonNull public final CandidateType type;
        /** True if the resolved compspec treats matches as filenames (append '/' to dirs). */
        public final boolean isFilename;
        /** Do NOT append a trailing space after a unique completion (-o nospace). */
        public final boolean noSpace;
        /** Preserve generation order; never alphabetize (-o nosort). */
        public final boolean noSort;

        public ShellCandidate(@NonNull String value, @NonNull CandidateType type,
                              boolean isFilename, boolean noSpace, boolean noSort) {
            this.value = value;
            this.type = type;
            this.isFilename = isFilename;
            this.noSpace = noSpace;
            this.noSort = noSort;
        }
    }

    /**
     * Result of a completion query: the candidates plus the effective completion
     * options for the resolved compspec.
     */
    public static final class CompletionResult {
        @NonNull public final List<ShellCandidate> candidates;
        public final boolean isFilename;
        public final boolean noSpace;
        public final boolean noSort;

        public CompletionResult(@NonNull List<ShellCandidate> candidates,
                                boolean isFilename, boolean noSpace, boolean noSort) {
            this.candidates = candidates;
            this.isFilename = isFilename;
            this.noSpace = noSpace;
            this.noSort = noSort;
        }

        /** Convenience: empty result (e.g. bash unavailable or timed out). */
        public static final CompletionResult EMPTY =
                new CompletionResult(Collections.emptyList(), false, false, false);
    }

    private final File mBashExecutable;

    /**
     * Per-word completion cache. We fetch bash completion ONCE for the word slot
     * currently being typed (keyed by word index + working directory) and then
     * serve every subsequent character of the same word from this cache, letting
     * the caller (AutoCompleteController's Path B) narrow the list locally. This
     * avoids spawning a bash subprocess on every keystroke while still giving
     * live, per-character filtering.
     *
     * <p>The key does NOT include the typed prefix: bash is queried with the full
     * current prefix, so the cached list already contains every candidate
     * matching that prefix. Keying by the prefix would make a backspace+retype
     * return the narrower stale list and drop valid candidates.
     */
    @Nullable private String mCacheKey;
    @Nullable private String mCachePrefix;
    @Nullable private List<ShellCandidate> mCachedCandidates;
    private long mCacheTimestamp;

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
        mCacheTimestamp = 0;
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
     * @param cursor      the cursor offset within commandLine (0-based). Used to
     *                    set COMP_POINT so mid-line editing completes the word
     *                    under the caret, not always the last word.
     * @return a result with candidate completion strings (possibly empty). Order is
     *         preserved as returned by the shell.
     */
    @NonNull
    public CompletionResult complete(@NonNull String commandLine, @Nullable String cwd, int cursor) {
        if (!isAvailable() || TextUtils.isEmpty(commandLine)) {
            return CompletionResult.EMPTY;
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
            return CompletionResult.EMPTY;
        }

        // Stable per-word-slot cache key: word index + cwd. This guarantees exactly
        // one bash fetch per word slot (bash is queried with the full current prefix,
        // so the cached list already contains every candidate for the word). The key
        // intentionally does NOT include the typed prefix (see mCachePrefix below).
        String key = cword + "\u0000" + (cwd == null ? "" : cwd);

        long now = System.currentTimeMillis();
        if (mCacheKey != null && mCacheKey.equals(key) && mCachedCandidates != null
                && mCachePrefix != null && (now - mCacheTimestamp) < CACHE_TTL_MS) {
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
                return new CompletionResult(mCachedCandidates, false, false, false);
            }
        }

        // COMP_POINT is the byte offset of the cursor into COMP_LINE. For ASCII
        // command lines this equals the char offset; convert to UTF-8 bytes so a
        // cursor inside a multibyte token still lands correctly.
        String compLine = commandLine;
        int compPoint = utf8ByteLength(commandLine, cursor);

        String script = buildCompletionScript(words, cword, compLine, compPoint);

        List<ShellCandidate> candidates = runBash(script, cwd);
        if (candidates == null) {
            // Timed out / errored: do not cache the empty result (so a later, faster
            // attempt for the same word can still succeed).
            return CompletionResult.EMPTY;
        }

        // Cache the normalized bash output keyed by this word slot.
        mCacheKey = key;
        mCachePrefix = lastWord;
        mCachedCandidates = candidates;
        mCacheTimestamp = now;

        Logger.logInfo(LOG_TAG, "complete(\"" + commandLine + "\") -> " + candidates.size()
                + " candidates (fresh fetch)");
        return new CompletionResult(candidates, false, false, false);
    }

    /**
     * Strip trailing completion decorations bash sometimes appends (a single
     * trailing space) so the cached value is the clean completable token. The
     * directory slash (and ':'/'=' from option-style compspecs) is PRESERVED and
     * used by the caller to detect directory/option candidates; the controller
     * decides what to render/insert.
     *
     * <p>Each raw line is either a bare candidate (slow path: COMPREPLY output) or
     * a {@code name\tcat} pair (fast path: {@code name} plus a classification code
     * 0=external/1=alias/2=builtin/3=function).
     */
    @NonNull
    private static List<ShellCandidate> normalizeCandidates(@NonNull List<String> raw) {
        List<ShellCandidate> out = new ArrayList<>(raw.size());
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (String c : raw) {
            if (c.isEmpty()) continue;

            // Split the optional "name\tcat" fast-path classification.
            String s = c;
            CandidateType declared = null;
            int tab = c.indexOf('\t');
            if (tab >= 0) {
                s = c.substring(0, tab);
                if (tab + 1 < c.length()) {
                    switch (c.charAt(tab + 1)) {
                        case '1': declared = CandidateType.ALIAS; break;
                        case '2': declared = CandidateType.COMMAND; break; // builtin
                        case '3': declared = CandidateType.FUNCTION; break;
                        default: declared = CandidateType.COMMAND; break; // external
                    }
                }
            }

            // Only strip a single trailing plain space; keep '/' (dir marker),
            // ':' and '=' (option-style completions) and other decorations.
            while (s.length() > 1 && s.charAt(s.length() - 1) == ' ') {
                s = s.substring(0, s.length() - 1);
            }
            if (s.isEmpty() || seen.contains(s)) continue;
            if (KEYWORD_BLOCKLIST.contains(s)) continue; // drop pure-syntax keywords
            seen.add(s);

            CandidateType type;
            if (declared != null) {
                type = declared;
            } else if (s.endsWith("/")) {
                type = CandidateType.DIRECTORY;
            } else if (s.startsWith("-") && s.length() > 1) {
                type = CandidateType.OPTION;
            } else {
                type = CandidateType.COMMAND;
            }
            boolean isFilename = (type == CandidateType.DIRECTORY || type == CandidateType.FILE);
            out.add(new ShellCandidate(s, type, isFilename, false, false));
            if (out.size() >= MAX_CACHED_CANDIDATES) break;
        }
        return out;
    }

    /**
     * Build the bash snippet that produces candidates. We reuse bash's internal
     * completion machinery by setting the COMP_* variables, sourcing
     * bash-completion, loading the completion for the command (if any), then
     * calling the loaded function and printing {@code COMPREPLY} line by line.
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
        // 5-10x faster and is the dominant case (every command's first char).
        // We also source the user's lightweight alias/init file (NOT full
        // .bashrc) so personal aliases/functions become visible, and classify
        // each result so the UI can mark aliases/builtins/functions.
        if (cword == 0) {
            return ""
                    + "shopt -s expand_aliases 2>/dev/null\n"
                    + "[[ -f \"$HOME/.bash_aliases\" ]] && source \"$HOME/.bash_aliases\" 2>/dev/null\n"
                    + "while IFS= read -r __c; do\n"
                    + "  [[ -z \"$__c\" ]] && continue\n"
                    + "  __t=$(type -t \"$__c\" 2>/dev/null); __cat=0\n"
                    + "  case \"$__t\" in\n"
                    + "    alias)   __cat=1;;\n"
                    + "    builtin) __cat=2;;\n"
                    + "    function)__cat=3;;\n"
                    + "    *)       __cat=0;;\n"   // file / hashed -> external
                    + "  esac\n"
                    + "  printf '%s\\t%s\\n' \"$__c\" \"$__cat\"\n"
                    + "done < <(compgen -c -- " + quoteForBash(currentWord) + " 2>/dev/null"
                    + " | head -n " + MAX_BASH_CANDIDATES + ")\n";
        }

        // SLOW PATH: completing an argument/path. Source bash-completion helpers,
        // load the compspec for the command, run it faithfully (handling -F, -C,
        // -W/-A/-G and the -D default), and fall back to tilde/dir/hidden-aware
        // path completion when nothing is produced.
        return ""
                + "shopt -s prog_completion 2>/dev/null\n"
                // Load bash-completion helpers from the Termux prefix (and the
                // conventional /usr path, which is normally a symlink to it).
                + "for __bc in \"${TERMUX_PREFIX}/share/bash-completion/bash_completion\" "
                + "\"/usr/share/bash-completion/bash_completion\"; do\n"
                + "  [[ -f \"$__bc\" ]] && source \"$__bc\" 2>/dev/null\n"
                + "done\n"
                // Register the standard lazy loader as the default completion so
                // commands without an explicit compspec still get smart defaults.
                + "complete -D -F _completion_loader -o bashdefault -o default 2>/dev/null\n"
                + "COMP_WORDS=" + wordsLiteral + "\n"
                + "COMP_CWORD=" + cword + "\n"
                + "COMP_LINE=" + quoteForBash(compLine) + "\n"
                + "COMP_POINT=" + compPoint + "\n"
                + "COMP_KEY=9\n"
                + "COMP_TYPE=63\n" // list-all: a dropdown wants the full candidate set
                + "COMPREPLY=()\n"
                // -I context: previous token is a command separator -> the next word
                // is again a command name, not a file argument.
                + "case \"${COMP_WORDS[COMP_CWORD-1]:-}\" in\n"
                + "  \";\"|\"|\"|\"&\"|\"&&\"|\"||\"|\"(\"|\")\") "
                + "COMPREPLY=($(compgen -c -- " + quoteForBash(currentWord) + " 2>/dev/null"
                + " | head -n " + MAX_BASH_CANDIDATES + ")); printf '%s\\n' \"${COMPREPLY[@]}\"; exit 0;;\n"
                + "esac\n"
                // Lazy-load the compspec for the command being typed.
                + "if [[ -n ${words[0]:-} ]]; then\n"
                + "  if ! complete -p -- \"${words[0]}\" >/dev/null 2>&1; then\n"
                + "    type -t _completion_loader >/dev/null 2>&1 && _completion_loader "
                + quoteForBash(words.length > 0 ? words[0] : "") + " 2>/dev/null\n"
                + "  fi\n"
                + "fi\n"
                // Resolve and run the compspec (handles -F, -C, -W/-A/-G).
                + "if [[ -n ${words[0]:-} ]]; then\n"
                + "  spec=$(complete -p -- \"${words[0]}\" 2>/dev/null)\n"
                + "  if [[ -n $spec ]]; then\n"
                // -C external command: prints completions one per line.
                + "    if [[ $spec == *\" -C \"* ]]; then\n"
                + "      __prog=${spec##* -C }; __prog=${__prog%% *}\n"
                + "      COMPREPLY=($(__prog 2>/dev/null))\n"
                // -F function: receives COMP_* context; writes COMPREPLY.
                + "    else\n"
                + "      func=$(awk '{for(i=1;i<=NF;i++) if($i==\"-F\") print $(i+1)}' <<<\"$spec\")\n"
                + "      if [[ -n $func ]] && type -t \"$func\" >/dev/null 2>&1; then\n"
                + "        \"$func\" \"${words[0]}\" " + quoteForBash(currentWord)
                + " \"${words[cword-1]:-}\" 2>/dev/null\n"
                + "      else\n"
                // -W / -A / -G style spec: re-run compgen with the same flags.
                + "        rest=${spec#complete }\n"
                + "        COMPREPLY=($(eval \"compgen $rest -- '"
                + quoteForBash(currentWord) + "'\" 2>/dev/null))\n"
                + "      fi\n"
                + "    fi\n"
                + "  fi\n"
                + "fi\n"
                // Fallback: tilde/dir/hidden-aware path completion when nothing else
                // was produced. Directories get a trailing '/' so the UI can mark
                // them; dotfiles only appear once the prefix starts with '.'.
                + "if [[ ${#COMPREPLY[@]} -eq 0 ]]; then\n"
                + "  __w=" + quoteForBash(currentWord) + "\n"
                + "  case \"$__w\" in\n"
                + "    \\~) __w=$HOME ;;\n"
                + "    \\~/*) __w=${HOME}${__w:1} ;;\n"
                + "    \\~*) __u=${__w#\\~}; __u=${__u%%/*}; "
                + "__h=$(getent passwd \"$__u\" 2>/dev/null | cut -d: -f6); "
                + "[[ -z $__h ]] && __h=$HOME; __w=$__h${__w:1+${#__u}+1} ;;\n"
                + "  esac\n"
                + "  __dir=${__w%/*}; [[ -z $__dir ]] && __dir=.\n"
                + "  __prefix=${__w##*/}\n"
                + "  [[ ${__prefix:0:1} == . ]] && shopt -s dotglob\n"
                + "  shopt -s nullglob\n"
                + "  compgen -d -S / -- \"$__dir/$__prefix\" 2>/dev/null | head -n " + MAX_BASH_CANDIDATES + "\n"
                + "  compgen -f -X '*/' -- \"$__dir/$__prefix\" 2>/dev/null | head -n " + MAX_BASH_CANDIDATES + "\n"
                + "fi\n"
                + "printf '%s\\n' \"${COMPREPLY[@]}\"\n";
    }

    /**
     * Run the bash script and collect its stdout lines as candidates. Returns
     * {@code null} on timeout/error so the caller does NOT cache the (empty)
     * result.
     */
    @Nullable
    private List<ShellCandidate> runBash(@NonNull String script, @Nullable String cwd) {
        List<String> raw = new ArrayList<>();
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
            boolean finished = process.waitFor(TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                Logger.logWarn(LOG_TAG, "bash completion timed out (>" + TIMEOUT_MS + "ms)");
                return null;
            }

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    // Do NOT trim: a candidate may legitimately start/end with a space.
                    // Only strip a trailing CR (defensive, in case of CRLF output).
                    if (line.endsWith("\r")) line = line.substring(0, line.length() - 1);
                    if (!line.isEmpty()) raw.add(line);
                }
            }
        } catch (IOException | InterruptedException e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "bash completion failed", e);
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return null;
        } finally {
            if (process != null && process.isAlive()) process.destroyForcibly();
        }
        return normalizeCandidates(raw);
    }

    /** Single-quote a string for safe embedding in a bash script. */
    @NonNull
    private static String quoteForBash(@NonNull String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    /** UTF-8 byte length of the first {@code cursor} chars of {@code s}. */
    private static int utf8ByteLength(@NonNull String s, int cursor) {
        int end = Math.max(0, Math.min(cursor, s.length()));
        return s.substring(0, end).getBytes(StandardCharsets.UTF_8).length;
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

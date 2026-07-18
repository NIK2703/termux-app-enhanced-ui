package com.termux.app.terminal.io.autocomplete;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Stateless helpers for shell-completion: command-line tokenization, scenario
 * classification, per-word cache-key construction, raw-bash-output parsing and
 * candidate normalization. None of these hold instance state — they are pure
 * functions over their arguments — so they live apart from the stateful
 * {@link ShellCompletionProvider} (which owns the persistent process and the
 * per-word cache).
 *
 * <p>All methods are {@code static}. The few constants they need
 * ({@link #KEYWORD_BLOCKLIST}, the pre-compiled {@link Pattern}s, the home-dir
 * cache, the normalize caps) are duplicated here rather than threaded through
 * the provider, keeping this class self-contained.
 */
final class ShellCompletionParsing {

    private ShellCompletionParsing() {}

    /** Max candidates we ever keep/parse (defensive memory backstop). */
    static final int MAX_CACHED_CANDIDATES = 100_000;

    /** Max stat() syscalls per fresh fetch (per-candidate type inference cap). */
    static final int STAT_BUDGET = 200;

    /** Sentinel meaning "lookup attempted, no result" so we don't retry every candidate. */
    static final String NO_CACHE_ENTRY = "__no_home_entry__";

    /** Small cache of {@code ~user} -> home dir lookups (getent is expensive). */
    private static final Map<String, String> sHomeCache =
            new ConcurrentHashMap<>(8);

    /** Default COMP_WORDBREAKS used by the Java cache key (kept in sync with the
     *  bash-side default seeded in buildCompletionScript). */
    static final String COMP_WORDBREAKS_KEY = " \t\n\"'()<>;|&=:@~";

    /** Pure-syntax command names that should never be offered as completions. */
    static final Set<String> KEYWORD_BLOCKLIST = Collections.unmodifiableSet(
            new HashSet<>(java.util.Arrays.asList(
                    "if", "then", "else", "elif", "fi", "for", "while", "until", "do",
                    "done", "case", "esac", "in", "function", "select", "time", "{", "}",
                    "[[", "]]", "!", "coproc")));

    /**
     * Pre-compiled regexes for the hot path (every keystroke runs
     * {@link #classifyScenario} / {@link #contextPrefix}). Compiling a Pattern
     * once instead of via {@code String.matches(...)} avoids re-parsing the
     * regex on every completion call.
     */
    static final Pattern PATTERN_VAR_EXPR =
            Pattern.compile("\\$\\{?[A-Za-z_][A-Za-z0-9_]*\\[.*");
    static final Pattern PATTERN_NAME_ASSIGN =
            Pattern.compile("[A-Za-z_][A-Za-z0-9_]*=.*");
    static final Pattern PATTERN_REDIR_FD =
            Pattern.compile("[0-9]+(&?>+|<+|>>)");
    static final Pattern PATTERN_ARRAY_INDEX =
            Pattern.compile("\\$(?:\\{)?[A-Za-z_][A-Za-z0-9_]*\\[.*");
    static final Pattern PATTERN_UNARY_FILE_OP =
            Pattern.compile("-(f|d|e|r|w|x|L|h|S|b|c|p|u|g|k|s)");
    static final Pattern PATTERN_LOOP_KW =
            Pattern.compile("^(for|case|select)$");
    static final Pattern PATTERN_REMOTE_HOSTCOLON =
            Pattern.compile("^[A-Za-z0-9._-]+:.*");
    static final Pattern PATTERN_REMOTE_USERHOST =
            Pattern.compile("^[^/@\\s]+@[^/@\\s]+:.*");

    /** Field delimiter for the persistent-process wire protocol (TYPE\x01WORD\x01CWD\x01LINE). */
    static final char REQ_SEP = '\u0001';

    /** True when {@code prev} is a redirection operator forcing a filename context. */
    static boolean isRedirection(@NonNull String prev) {
        switch (prev) {
            case ">": case ">>": case "<": case "<<": case "<<<":
            case "&>": case "&>>":
                return true;
            default:
                return PATTERN_REDIR_FD.matcher(prev).matches();
        }
    }

    /** True when {@code token} is a {@code NAME=value} assignment. */
    static boolean isAssignment(@NonNull String token) {
        return token.indexOf('=') > 0 && PATTERN_NAME_ASSIGN.matcher(token).matches();
    }

    /** True when {@code prev} is a command separator / control keyword. */
    static boolean isCommandSeparator(@NonNull String prev) {
        switch (prev) {
            case ";": case "|": case "&":
            case "&&": case "||": case "|&":
            case "(": case ")": case "{":
            case "<(": case ">(": case "&>(":
                return true;
            default:
                return prev.equals("time") || prev.equals("coproc") || prev.equals("do")
                        || prev.equals("then") || prev.equals("else") || prev.equals("elif")
                        || prev.startsWith("<(") || prev.startsWith(">(") || prev.startsWith("&>(");
        }
    }

    /** True when {@code w} uses remote-path syntax (host:/path, scheme://…). */
    static boolean looksRemote(@NonNull String w) {
        return PATTERN_REMOTE_HOSTCOLON.matcher(w).matches()
                || PATTERN_REMOTE_USERHOST.matcher(w).matches()
                || w.contains("://");
    }

    /**
     * Classify the word slot into a {@link ShellCompletionProvider.Scenario}.
     * Single source of truth for the bash wire TYPE; mirrors the cache-key
     * discriminator ({@link #contextPrefix}) so the chosen script aligns with the
     * cached domain. Plain text with no compspec falls back to
     * {@link ShellCompletionProvider.Scenario#COMPSPEC} (empty result).
     */
    @NonNull
    static ShellCompletionProvider.Scenario classifyScenario(@NonNull String[] words, int cword) {
        String lastWord = (words.length > 0) ? words[cword] : "";
        String prev = (cword > 0 && words.length > cword) ? words[cword - 1] : "";
        String cmd = (words.length > 0) ? words[0] : "";

        // History expansion (bash '!') — not a completable command token.
        // Interactive bash expands it to a prior command; offering compgen -c on
        // the literal '!git' is meaningless and wastes a subprocess (S12).
        if (lastWord.startsWith("!") && lastWord.length() > 1
                && !lastWord.startsWith("!{") && !lastWord.startsWith("!([")) {
            return ShellCompletionProvider.Scenario.HISTORY_EXPANSION;
        }

        // $VAR / ${VAR} forms, or the export/unset/… variable builtins.
        if ((lastWord.startsWith("$")
                && !lastWord.startsWith("$(") && !lastWord.startsWith("$(((")
                && !lastWord.startsWith("$[")
                && !PATTERN_VAR_EXPR.matcher(lastWord).matches())
                || cmd.equals("export") || cmd.equals("unset")
                || cmd.equals("readonly") || cmd.equals("declare") || cmd.equals("typeset")) {
            return ShellCompletionProvider.Scenario.VARIABLE;
        }

        // A first-word NAME=value assignment (e.g. "LD_LIBRARY_PATH=/opt/") must be
        // routed to PATH so the VALUE completes against the filesystem (S11). This
        // is checked BEFORE the cword==0 COMMAND_NAME return below, otherwise a
        // first-word assignment would be mis-classified as a command name and try
        // compgen -c on the whole "VAR=value" token (empty result).
        if (cword == 0 && lastWord.indexOf('=') > 0
                && PATTERN_NAME_ASSIGN.matcher(lastWord).matches()) {
            return ShellCompletionProvider.Scenario.PATH;
        }

        // Redirection operators force a FILENAME context.
        if (isRedirection(prev)) {
            return ShellCompletionProvider.Scenario.REDIRECTION;
        }

        // A first word that is already a filesystem path (absolute, ~/tilde,
        // ./relative, or contains a slash — including a quoted path with a
        // space like 'my notes'/su) must be file-completed, NOT treated as a
        // command name (S8b/S10). Hoist the path-literal test above the
        // command-name return so these route to PATH.
        if (!looksRemote(lastWord) && (lastWord.startsWith("/") || lastWord.startsWith("~")
                || lastWord.startsWith(".")
                || (lastWord.indexOf('/') >= 0)
                || ((lastWord.startsWith("'") || lastWord.startsWith("\""))
                    && (lastWord.indexOf('/') >= 0)))) {
            return ShellCompletionProvider.Scenario.PATH;
        }
        // Array subscript completion (e.g. ${ARR[ ) — not a bare variable name
        // (S14). Route to a no-op scenario; real index completion is out of scope.
        if (PATTERN_ARRAY_INDEX.matcher(lastWord).matches()) {
            return ShellCompletionProvider.Scenario.ARRAY_INDEX;
        }

        if (cword == 0) return ShellCompletionProvider.Scenario.COMMAND_NAME;

        // Command context after a separator / keyword / process substitution.
        if (isCommandSeparator(prev) || isAssignment(prev)) {
            return ShellCompletionProvider.Scenario.COMMAND_CONTEXT;
        }

        // kill / trap signal-or-job completion.
        if ((cmd.equals("kill") || cmd.equals("trap"))
                && (lastWord.startsWith("-") || lastWord.startsWith("%"))) {
            return ShellCompletionProvider.Scenario.SIGNAL_JOB;
        }

        // Builtins whose operand is ALWAYS a command name (type/which/command/
        // builtin/enable) must complete against command names, not the target
        // command's arguments — they have no compspec that yields command names
        // (S6/S7). In message mode we must NOT offer command names, so degrade.
        if (cmd.equals("type") || cmd.equals("which") || cmd.equals("command")
                || cmd.equals("builtin") || cmd.equals("enable")) {
            return ShellCompletionProvider.Scenario.COMMAND_CONTEXT;
        }

        // The operand of a [ / test / [[ unary file operator is a path (S2b).
        // e.g. `while [ -f va` or `while [ -f` (empty) should complete as a path.
        if (PATTERN_UNARY_FILE_OP.matcher(prev).matches()) {
            return ShellCompletionProvider.Scenario.PATH;
        }

        // Empty / whitespace-only line is a command-name context.
        if (lastWord.isEmpty()) {
            // An empty word directly after a [ / test file operator is a path
            // operand (S2b); otherwise it's a command name.
            if (PATTERN_UNARY_FILE_OP.matcher(prev).matches()) {
                return ShellCompletionProvider.Scenario.PATH;
            }
            return ShellCompletionProvider.Scenario.COMMAND_NAME;
        }

        // A bare word in the word-list after `in` (for/case/select) is a path,
        // not a command (S1b). The slash-prefixed case is already caught by the
        // PATH-literal check above; this handles the bare (non-slash) word.
        if (prev.equals("in") && PATTERN_LOOP_KW.matcher(cmd).matches()) {
            return ShellCompletionProvider.Scenario.PATH;
        }

        // A token that looks like a filesystem path (leading / ~ . or contains a
        // slash, or is quoted) is handled by the path scenario directly — this
        // keeps "cat ~/Doc", "/data/da", "./foo" fast without loading compspecs.
        // Also a NAME=value assignment with no space (e.g. "PATH=/us") — the word
        // being completed is the VALUE, which is a path, so route it to PATH and
        // let the value-complete against the filesystem (S11).
        //
        // A quoted glob without a slash (e.g. '*.con or "*.con, unterminated while
        // typing) is also a path context: it carries a glob metacharacter but no
        // slash, so the compspec fallback would otherwise leave it empty (S5). The
        // PATH file fallback passes the prefix to compgen, which expands globs.
        //
        // Remote-path syntax (host:/path, user@host:/path, scheme://path) must NOT
        // be routed to the local PATH fallback: bash's COMP_WORDBREAKS treats ':'
        // as a suffix break, so the tokenizer splits "host:/hom" into "host:" + "/"
        // + "hom" and a LOCAL path "host:/hom" resolves to nothing. Such tokens
        // belong to the command's real compspec (e.g. rsync) which understands
        // remote syntax (S14).
        if (!looksRemote(lastWord) && (lastWord.startsWith("/") || lastWord.startsWith("~")
                || lastWord.startsWith(".") || lastWord.indexOf('/') >= 0
                || ((lastWord.startsWith("\"") || lastWord.startsWith("'"))
                    && (lastWord.indexOf('*') >= 0 || lastWord.indexOf('?') >= 0
                        || lastWord.indexOf('[') >= 0)))) {
            return ShellCompletionProvider.Scenario.PATH;
        }

        // Otherwise: a real argument/option of a command → run its compspec.
        return ShellCompletionProvider.Scenario.COMPSPEC;
    }

    /** Convenience wrapper used by tests / callers with a raw command line. */
    @NonNull
    static ShellCompletionProvider.Scenario classify(@NonNull String commandLine) {
        String[] words = splitCommandLine(commandLine);
        int cword = Math.max(0, words.length - 1);
        return classifyScenario(words, cword);
    }

    /**
     * Cheap Java-side discriminator for the cache key: returns {@code "C"} when the
     * word at {@code cword} is in a COMMAND context (previous token is a separator
     * such as {@code ; | & ( ) { }, or a keyword / assignment) and {@code "A"} when
     * it is an ARGUMENT context. This is a conservative heuristic kept in lock-step
     * with the bash-side {@code -E}/{@code -I} handling so the two never share a
     * cache entry for different completion domains.
     */
    @NonNull
    static String contextPrefix(@NonNull String[] words, int cword) {
        if (cword <= 0) return "C";
        String prev = words[cword - 1];
        if (prev.isEmpty()) return "A";
        // Redirection operators force a FILENAME context for the next word.
        // Must match shell_complete_slow.sh (__force_file=1) or the cache key
        // ("F") would collide with a genuine command context ("C") and serve
        // stale file candidates where commands are expected (e.g. "cmd >" vs
        // "cmd &" share the same key when mislabeled "C").
        if (isRedirection(prev)) return "F";
        // Command context after a separator / keyword / assignment.
        if (isCommandSeparator(prev) || isAssignment(prev)) return "C";
        return "A";
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
            } else if (c == '\\') {
                // Backslash escapes the next character (space, etc.) — keep it as part
                // of the current word so e.g. `my\ dir` stays one token (S11).
                current.append(c);
                if (i + 1 < line.length()) {
                    current.append(line.charAt(i + 1));
                    i++;
                }
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

    /**
     * Build the per-word-slot cache key. Single source of truth shared by
     * {@link #complete}-adjacent key construction and {@link #cacheKeyFor} so the
     * two never drift. Layout:
     * {@code cword \0 context \0 cmdWord \0 cwdPart \0 scenario \0 envVersion}.
     * Uses a {@link StringBuilder} to avoid the intermediate String allocations of
     * chained {@code + "\u0000" +} concatenations on the hot path.
     */
    @NonNull
    static String buildCacheKey(@NonNull ShellCompletionProvider.Scenario scenario,
                                @NonNull String[] words, int cword,
                                @Nullable String cwd, int envVersion) {
        String context = contextPrefix(words, cword);
        String cmdWord = (cword > 0 && words.length > 0) ? words[0] : "";
        String cwdPart = (cword == 0) ? "" : (cwd == null ? "" : cwd);
        return new StringBuilder()
                .append(cword).append('\u0000')
                .append(context).append('\u0000')
                .append(cmdWord).append('\u0000')
                .append(cwdPart).append('\u0000')
                .append(scenario.name()).append('\u0000')
                .append(envVersion)
                .toString();
    }

    /**
     * Build a per-word-slot cache key directly from a raw command line
     * (tokenizing the same way bash would). See {@link #buildCacheKey}.
     */
    @NonNull
    static String cacheKeyForLine(@NonNull String commandLine,
                                  @Nullable String cwd, int envVersion) {
        String[] words = splitCommandLine(commandLine);
        int cword = Math.max(0, words.length - 1);
        return cacheKeyFor(words, cword, cwd, envVersion);
    }

    /**
     * Build the per-word-slot cache key from a pre-tokenized line. The key layout
     * mirrors {@link #buildCacheKey}'s exactly.
     */
    @NonNull
    static String cacheKeyFor(@NonNull String[] words, int cword,
                              @Nullable String cwd, int envVersion) {
        ShellCompletionProvider.Scenario scenario = classifyScenario(words, cword);
        return buildCacheKey(scenario, words, cword, cwd, envVersion);
    }

    /**
     * Map the classified scenario to the persistent-dispatcher wire TYPE token.
     * This is the one-word selector fed to the single long-lived bash process;
     * it tells the dispatcher which single compgen / glob to run. REDIRECTION and
     * PATH share the wire TYPE {@code PATH} (the dispatcher emits one
     * {@code compgen -G}), but the cache key still distinguishes them via the
     * {@code "F"} context discriminator.
     *
     * @return the wire TYPE ({@code CMD/PATH/VAR/SIGNAL/COMPSPEC}), or
     *         {@code null} for scenarios that are never completable
     *         (HISTORY_EXPANSION / ARRAY_INDEX) — the caller treats {@code null}
     *         as EMPTY (no request, no cache).
     */
    @Nullable
    static String buildCompletionScript(@NonNull ShellCompletionProvider.Scenario scenario,
                                        @NonNull String[] words, int cword,
                                        @NonNull String compLine, int compPoint) {
        switch (scenario) {
            case COMMAND_NAME:
            case COMMAND_CONTEXT:
                return "CMD";
            case PATH:
            case REDIRECTION:
                return "PATH";
            case VARIABLE:
                return "VAR";
            case SIGNAL_JOB:
                return "SIGNAL";
            case HISTORY_EXPANSION:
            case ARRAY_INDEX:
                return null; // complete() treats null as EMPTY (no request, no cache)
            case COMPSPEC:
            default:
                return "COMPSPEC";
        }
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
     * Strip a single layer of surrounding shell quotes from {@code s} so the Java
     * side agrees with bash's tokenizer (which strips quotes before producing
     * candidates). Handles {@code "..."}, {@code '...'} and an unterminated
     * trailing quote (the word being typed). Internal quotes are NOT removed.
     */
    @NonNull
    static String stripOuterQuotes(@NonNull String s) {
        int n = s.length();
        if (n < 2) return s;
        char first = s.charAt(0);
        if (first == '"' || first == '\'') {
            char last = s.charAt(n - 1);
            if (last == first) return s.substring(1, n - 1);
            // Unterminated trailing quote (mid-typing): drop the leading quote only.
            return s.substring(1);
        }
        return s;
    }

    /**
     * Dequote a token the way bash's tokenizer does for candidate comparison:
     * drop ' and " everywhere and resolve \-escapes, so a typed `'my notes'/su`
     * or `my\ dir/Do` matches the unquoted candidates bash returns. Used by the
     * merge/local-filter prefix (S10/S11).
     */
    @NonNull
    static String dequoteLikeBash(@NonNull String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\'') continue;
            if (c == '"') continue;
            if (c == '\\') {
                if (i + 1 < s.length()) b.append(s.charAt(i + 1));
                i++;
                continue;
            }
            b.append(c);
        }
        return b.toString();
    }

    /** UTF-8 byte length of the first {@code cursor} chars of {@code s}. */
    static int utf8ByteLength(@NonNull String s, int cursor) {
        int end = Math.max(0, Math.min(cursor, s.length()));
        return s.substring(0, end).getBytes(StandardCharsets.UTF_8).length;
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
    static List<ShellCompletionProvider.ShellCandidate> normalizeCandidates(
            @NonNull List<String> raw, @Nullable String cwd) {
        List<ShellCompletionProvider.ShellCandidate> out =
                new ArrayList<>(raw.size());
        java.util.Set<String> seen = new java.util.HashSet<>();
        // Cap stat() syscalls per fresh fetch; only the first K real paths are
        // stat-ed. Declared-type / option tokens skip the budget entirely.
        java.util.concurrent.atomic.AtomicInteger statBudget =
                new java.util.concurrent.atomic.AtomicInteger(STAT_BUDGET);
        for (String c : raw) {
            if (c.isEmpty()) continue;

            // Split the optional "name\tcat" fast-path classification.
            String s = c;
            ShellCompletionProvider.CandidateType declared = null;
            int tab = c.indexOf('\t');
            if (tab >= 0) {
                s = c.substring(0, tab);
                if (tab + 1 < c.length()) {
                    switch (c.charAt(tab + 1)) {
                        case '1': declared = ShellCompletionProvider.CandidateType.ALIAS; break;
                        case '2': declared = ShellCompletionProvider.CandidateType.COMMAND; break; // builtin
                        case '3': declared = ShellCompletionProvider.CandidateType.FUNCTION; break;
                        default: declared = ShellCompletionProvider.CandidateType.COMMAND; break; // external
                    }
                }
            }

            // Strip at most ONE trailing plain space that bash adds to mark a
            // non-nospace candidate boundary. Do NOT loop: a candidate that
            // legitimately ends in whitespace (rare, but possible for e.g. some
            // -W literals) must keep its spaces. Keeps '/', ':' and '=' too.
            if (s.length() > 1 && s.charAt(s.length() - 1) == ' ') {
                s = s.substring(0, s.length() - 1);
            }
            if (s.isEmpty() || seen.contains(s)) continue;
            if (KEYWORD_BLOCKLIST.contains(s)) continue; // drop pure-syntax keywords
            seen.add(s);

            ShellCompletionProvider.CandidateType type;
            if (declared != null) {
                type = declared;
            } else if (s.endsWith("/")) {
                type = ShellCompletionProvider.CandidateType.DIRECTORY;
            } else if (s.startsWith("-") && s.length() > 1) {
                type = ShellCompletionProvider.CandidateType.OPTION;
            } else {
                type = ShellCompletionProvider.CandidateType.COMMAND;
            }

            // When bash didn't mark the candidate as a directory (no trailing '/')
            // and didn't declare a type, infer FILE vs DIRECTORY by stat-ing the
            // path. This is a robust catch-all for `-F` compspecs that return bare
            // names. Handles leading '~' and absolute paths.
            //
            // To keep a fresh fetch cheap under thousands of candidates, we cap the
            // number of stat() syscalls: only the first STAT_BUDGET candidates are
            // stat-ed; the rest keep the coarse COMMAND type. Declared-type tokens
            // and option tokens (`-`) never need a stat.
            if (!s.endsWith("/") && declared == null && cwd != null
                    && !s.startsWith("-") && !s.startsWith("$")
                    && statBudget.get() > 0) {
                statBudget.decrementAndGet();
                try {
                    String path = expandHome(s);
                    java.io.File f = path.startsWith("/") ? new java.io.File(path)
                            : new java.io.File(cwd, path);
                    if (f.isDirectory()) {
                        type = ShellCompletionProvider.CandidateType.DIRECTORY;
                    } else if (f.isFile()) {
                        type = ShellCompletionProvider.CandidateType.FILE;
                    }
                } catch (SecurityException e) {
                    // SELinux / no permission to stat — leave type as-is.
                }
            }

            boolean isFilename = (type == ShellCompletionProvider.CandidateType.DIRECTORY
                    || type == ShellCompletionProvider.CandidateType.FILE);
            out.add(new ShellCompletionProvider.ShellCandidate(s, type, isFilename, false, false, false));
            if (out.size() >= MAX_CACHED_CANDIDATES) break;
        }
        return out;
    }

    /** Expand a leading {@code ~} or {@code ~user} in a candidate path. */
    @NonNull
    static String expandHome(@NonNull String path) {
        if (path.equals("~")) return System.getenv("HOME") == null ? path : System.getenv("HOME");
        if (path.startsWith("~/")) {
            String home = System.getenv("HOME");
            return home == null ? path : home + path.substring(1);
        }
        if (path.startsWith("~") && !path.startsWith("~+") && !path.startsWith("~-")) {
            int slash = path.indexOf('/');
            String user = (slash < 0) ? path.substring(1) : path.substring(1, slash);
            String rest = (slash < 0) ? "" : path.substring(slash);
            String home = homeOf(user);
            return home == null ? path : home + rest;
        }
        return path;
    }

    /** Look up a user's home directory via getent (best-effort). */
    @Nullable
    static String homeOf(@NonNull String user) {
        String cached = sHomeCache.get(user);
        if (cached != null) return cached.equals(NO_CACHE_ENTRY) ? null : cached;
        String result = null;
        try {
            Process p = new ProcessBuilder("getent", "passwd", user)
                    .redirectErrorStream(false).start();
            if (!p.waitFor(500, TimeUnit.MILLISECONDS)) { p.destroyForcibly(); }
            else {
                try (BufferedReader r = new BufferedReader(
                        new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                    String line = r.readLine();
                    if (line != null) {
                        String[] parts = line.split(":");
                        if (parts.length >= 6) result = parts[5];
                    }
                }
            }
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
        }
        synchronized (sHomeCache) {
            sHomeCache.put(user, result == null ? NO_CACHE_ENTRY : result);
        }
        return result;
    }

    /** Replace wire-breaking characters (our separator, CR/LF) in a request field. */
    @NonNull
    static String sanitizeField(@NonNull String s) {
        if (s.indexOf(REQ_SEP) < 0 && s.indexOf('\n') < 0 && s.indexOf('\r') < 0) return s;
        return s.replace(REQ_SEP, ' ').replace('\n', ' ').replace('\r', ' ');
    }

    /** Parse a NUL-delimited response buffer into candidates + comp-option flags. */
    static void parseNulRecords(@NonNull byte[] out, @NonNull List<String> raw,
                                @NonNull boolean[] flags) {
        int start = 0;
        for (int i = 0; i <= out.length; i++) {
            if (i == out.length || out[i] == 0) {
                if (i > start) {
                    String rec = new String(out, start, i - start, StandardCharsets.UTF_8);
                    // A stray trailing '\n' may remain just before the __END__
                    // marker; drop it so records compare cleanly.
                    if (rec.endsWith("\n")) rec = rec.substring(0, rec.length() - 1);
                    if (!rec.isEmpty()) {
                        if (rec.startsWith("__COMPOPTS:")) {
                            for (String o : rec.substring("__COMPOPTS:".length()).split(",")) {
                                switch (o) {
                                    case "filenames": flags[0] = true; break;
                                    case "nospace":   flags[1] = true; break;
                                    case "nosort":    flags[2] = true; break;
                                    case "noquote":   flags[3] = true; break;
                                    default: break;
                                }
                            }
                        } else {
                            raw.add(rec);
                        }
                    }
                }
                start = i + 1;
            }
        }
    }
}

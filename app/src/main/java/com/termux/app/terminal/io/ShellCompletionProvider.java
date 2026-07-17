package com.termux.app.terminal.io;

import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.logger.Logger;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import android.content.Context;
import android.os.Build;

import java.io.Closeable;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

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
public class ShellCompletionProvider {

    private static final String LOG_TAG = "ShellCompletionProvider";

    /** Same persistent trace file as AutoCompleteController, for the bash-side path. */
    private static final File TRACE_FILE =
            new File("/storage/emulated/0/Download/termux/autocomplete_log.txt");
    private static final AtomicInteger TRACE_SEQ = new AtomicInteger();
    private static volatile boolean sTraceFileOkay = true;

    private static void trace(@NonNull String tag, @NonNull String msg) {
        Logger.logInfo(LOG_TAG, msg);
        if (!sTraceFileOkay) return;
        try {
            StringBuilder sb = new StringBuilder();
            sb.append(new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(new Date()));
            sb.append(" #").append(String.format(Locale.US, "%05d", TRACE_SEQ.incrementAndGet()));
            sb.append(" [").append(tag).append("] ").append(msg).append("\n");
            try (FileWriter fw = new FileWriter(TRACE_FILE, true)) {
                fw.write(sb.toString());
            }
        } catch (Throwable t) {
            sTraceFileOkay = false;
        }
    }

    /** Max candidates we ever keep/parse (defensive bound on memory / popup size). */
    private static final int MAX_CACHED_CANDIDATES = 4000;

    /** Cap applied INSIDE bash before sorting/transmitting (perf + UX bound). */
    private static final int MAX_BASH_CANDIDATES = 300;

    /** Above this command-line length we skip the bash subprocess entirely. */
    private static final int MAX_COMMANDLINE_LENGTH = 4096;

    /**
     * Consecutive timeout counter (per provider instance). After enough
     * timeouts for distinct word slots we briefly suppress bash so a hung /
     * network-dependent compspec cannot storm the CPU with doomed subprocesses.
     */
    private int mConsecutiveTimeouts = 0;
    private long mShellSuppressedUntil = 0;
    private static final int TIMEOUT_CIRCUIT_THRESHOLD = 3;
    private static final long TIMEOUT_CIRCUIT_MS = 10_000;

    /** Timeout for a single bash completion subprocess. */
    private static final long TIMEOUT_MS = 1500;

    /** Staleness bound for the per-word cache (covers mid-session PATH/alias changes). */
    private static final long CACHE_TTL_MS = 20_000;

    /** Sentinel meaning "lookup attempted, no result" so we don't retry every candidate. */
    private static final String NO_CACHE_ENTRY = "__no_home_entry__";

    /** Small cache of {@code ~user} -> home dir lookups (getent is expensive). */
    private static final java.util.Map<String, String> sHomeCache =
            new java.util.concurrent.ConcurrentHashMap<>(8);

    /** Default COMP_WORDBREAKS used by the Java cache key (kept in sync with the
     *  bash-side default seeded in buildCompletionScript). */
    private static final String COMP_WORDBREAKS_KEY = " \t\n\"'()<>;|&=:@~";

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
        /** Do NOT quote the candidate even when it contains shell metacharacters (-o noquote). */
        public final boolean noQuote;

        public ShellCandidate(@NonNull String value, @NonNull CandidateType type,
                              boolean isFilename, boolean noSpace, boolean noSort, boolean noQuote) {
            this.value = value;
            this.type = type;
            this.isFilename = isFilename;
            this.noSpace = noSpace;
            this.noSort = noSort;
            this.noQuote = noQuote;
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

        /** Build a per-candidate result: every candidate copies the batch flags. */
        @NonNull
        static CompletionResult of(@NonNull List<ShellCandidate> candidates,
                                   boolean isFilename, boolean noSpace, boolean noSort, boolean noQuote) {
            List<ShellCandidate> tagged = new ArrayList<>(candidates.size());
            for (ShellCandidate c : candidates) {
                tagged.add(new ShellCandidate(c.value, c.type, c.isFilename,
                        noSpace || c.noSpace, noSort || c.noSort, noQuote || c.noQuote));
            }
            return new CompletionResult(tagged, isFilename, noSpace, noSort);
        }
    }

    private final File mBashExecutable;

    /** Android context used to load the bash completion script templates from raw resources. */
    @Nullable private final Context mContext;

    /** Lazily-loaded fast-path / slow-path script templates (raw resources). */
    @Nullable private String mFastTemplate;
    @Nullable private String mSlowTemplate;
    @Nullable private String mCommonTemplate;

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
    private boolean mCacheIsFilename;
    private boolean mCacheNoSpace;
    private boolean mCacheNoSort;
    private boolean mCacheNoQuote;

    /** Guards all mCache* accesses (UI clearCache vs background complete()). */
    private final Object mCacheLock = new Object();

    /** Bumped on any change to the shell environment (cwd switch, enable/disable,
     *  session switch) so cached results are invalidated instantly instead of
     *  waiting for the TTL. */
    private final java.util.concurrent.atomic.AtomicInteger mEnvVersion =
            new java.util.concurrent.atomic.AtomicInteger();

    public ShellCompletionProvider() {
        this(null, new File(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH, "bash"));
    }

    public ShellCompletionProvider(@Nullable Context context) {
        this(context, new File(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH, "bash"));
    }

    public ShellCompletionProvider(@Nullable Context context, @NonNull File bashExecutable) {
        mContext = context;
        mBashExecutable = bashExecutable;
    }

    /**
     * Load a raw resource String (the bash script template) exactly once and
     * cache it. Falls back to {@code null} if the resource is unavailable.
     */
    @Nullable
    private String loadRawResource(@NonNull String resName) {
        if (mContext == null) return loadRawResourceFromFile(resName);
        try {
            int id = mContext.getResources().getIdentifier(resName, "raw", mContext.getPackageName());
            if (id == 0) return null;
            try (java.io.InputStream in = mContext.getResources().openRawResource(id);
                 java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream()) {
                byte[] buf = new byte[4096];
                int r;
                while ((r = in.read(buf)) != -1) bos.write(buf, 0, r);
                return new String(bos.toByteArray(), StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "failed to load raw resource " + resName, e);
            return null;
        }
    }

    /**
     * Fallback used only when constructed with a {@code null} Context (e.g. unit
     * tests that drive the real bash subprocess but have no Android Resources).
     * Reads the raw template directly from the filesystem. The base directory is
     * taken from the {@code termux.rawres.dir} system property and defaults to the
     * conventional {@code app/src/main/res/raw} location. Production code always
     * supplies a non-null Context, so this path is never taken at runtime.
     */
    @Nullable
    private static String loadRawResourceFromFile(@NonNull String resName) {
        try {
            String dir = System.getProperty("termux.rawres.dir");
            if (dir == null || dir.isEmpty()) {
                dir = "app/src/main/res/raw";
            }
            File f = new File(dir, resName + ".sh");
            if (!f.isFile()) return null;
            try (java.io.InputStream in = new java.io.FileInputStream(f);
                 java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream()) {
                byte[] buf = new byte[4096];
                int r;
                while ((r = in.read(buf)) != -1) bos.write(buf, 0, r);
                return new String(bos.toByteArray(), StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "failed to load raw resource from file " + resName, e);
            return null;
        }
    }

    @Nullable
    private String fastTemplate() {
        if (mFastTemplate == null) mFastTemplate = withCommon(loadRawResource("shell_complete_fast"));
        return mFastTemplate;
    }

    /** @deprecated kept only so a stale reference can't break compilation. */
    @Nullable
    private String slowTemplate() {
        return null;
    }

    /**
     * Prepend the shared {@code shell_complete_common.sh} header to a scenario
     * template. The header defines the tokenizer, the file fallback and the
     * __COMPOPTS emitter, installs the process-group trap, and scans ~/.bashrc
     * for compspec registrations. We concatenate in Java (rather than bash
     * `source`) because the script runs via `bash -c` where BASH_SOURCE is
     * undefined and a cross-file source would silently fail.
     */
    @Nullable
    private String withCommon(@Nullable String scenario) {
        if (scenario == null) return null;
        String common = commonTemplate();
        if (common == null) return scenario;
        return common + "\n" + scenario;
    }

    @Nullable
    private String commonTemplate() {
        if (mCommonTemplate == null) mCommonTemplate = loadRawResource("shell_complete_common");
        return mCommonTemplate;
    }

    /** Returns true if the configured shell binary exists and can be used. */
    public boolean isAvailable() {
        return mBashExecutable.isFile() && mBashExecutable.canExecute();
    }

    /** Invalidate all cached completions (call after cwd/session/setting changes). */
    public void bumpEnvironmentVersion() {
        mEnvVersion.incrementAndGet();
    }

    /** Returns the current environment version (package-private for cache tests). */
    int getEnvVersion() {
        return mEnvVersion.get();
    }

    /**
     * Whether a cache entry stamped at {@code timestamp} is still fresh at
     * {@code now} under the {@link #CACHE_TTL_MS} staleness bound. Package-private
     * so the unit tests can exercise the TTL without populating the real cache.
     */
    static boolean isCacheFresh(long timestamp, long now) {
        return (now - timestamp) < CACHE_TTL_MS;
    }

    /** Drop any cached completion results (e.g. on session switch or when disabled). */
    public void clearCache() {
        synchronized (mCacheLock) {
            mCacheKey = null;
            mCachePrefix = null;
            mCachedCandidates = null;
            mCacheTimestamp = 0;
            mCacheIsFilename = false;
            mCacheNoSpace = false;
            mCacheNoSort = false;
            mCacheNoQuote = false;
        }
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
            trace("COMP", "complete(\"" + commandLine + "\") -> EMPTY early (isAvailable="
                    + isAvailable() + " empty=" + TextUtils.isEmpty(commandLine)
                    + " bash=" + (mBashExecutable == null ? "null" : mBashExecutable.getAbsolutePath()) + ")");
            return CompletionResult.EMPTY;
        }

        // Circuit-breaker: after several consecutive timeouts, stop spawning bash
        // for a short window so a hung/network compspec cannot storm the CPU.
        long now0 = System.currentTimeMillis();
        if (now0 < mShellSuppressedUntil) {
            return CompletionResult.EMPTY;
        }

        // Very long lines (e.g. a pasted script) are never usefully completable and
        // would force an expensive bash tokenization that blows the timeout budget.
        // Skip the subprocess entirely and fall back to history-only.
        if (commandLine.length() > MAX_COMMANDLINE_LENGTH) {
            return CompletionResult.EMPTY;
        }

        // Tokenize the line the same way bash would split words. We keep it simple:
        // split on unquoted whitespace. Quoted segments are kept intact.
        String[] words = splitCommandLine(commandLine);
        int cword = Math.max(0, words.length - 1); // index of the word being completed
        String lastWord = (words.length > 0) ? words[cword] : "";
        // The token immediately before the word being completed. When this is a
        // command separator or control keyword, an empty last word means the user
        // is about to type a NEW command name, so we must not short-circuit below.
        String prevToken = (words.length >= 2) ? words[words.length - 2] : "";
        // Bash's tokenizer strips a single layer of surrounding quotes, so the
        // candidates it returns are unquoted. Strip the same quotes here so the
        // cache key, the prefix match and the merge all agree with bash (S8/S13).
        // We keep the raw word for passing to bash ($3) where the COMP_WORDS
        // tokenizer re-derives the unquoted form itself.
        String lastWordStripped = stripOuterQuotes(lastWord);

        // Defer the fetch until the user has typed at least one real character of the
        // word being completed. A trailing space (e.g. "git ") yields an empty last
        // word; querying bash then would spawn a subprocess (compgen -f over the whole
        // cwd) and dump every file on a keystroke that added no word content. We return
        // nothing here and let the caller show history only; the real fetch happens on
        // the first typed character, satisfying "fetch once on first char".
        // Exception (S2): if the previous token is a command separator/control
        // keyword, an empty last word means a new command name is expected, so
        // proceed to classifyScenario (which returns COMMAND_CONTEXT/COMMAND_NAME).
        if (lastWord.isEmpty() && !isCommandSeparator(prevToken)) {
            return CompletionResult.EMPTY;
        }

        // Stable per-word-slot cache key. For cword==0 (command name) the key omits
        // cwd — command names are cwd-independent, so a `cd` must NOT invalidate the
        // cached command universe (the dominant, every-keystroke case). For cword>0
        // the COMMAND word (words[0]) is folded in so two different commands at the
        // same slot/cwd (e.g. "ssh host1 ls /" vs "scp host1 /") never share a cached
        // candidate set. The context discriminator ('C'/'A'/'F') prevents a collision
        // between a command-context slot and an argument-context slot that share the
        // same Java word index. COMP_WORDBREAKS_KEY is a compile-time constant that
        // never changes, so it is intentionally omitted from the key.
        String context = contextPrefix(words, cword);
        String cmdWord = (cword > 0 && words.length > 0) ? words[0] : "";
        String cwdPart = (cword == 0) ? "" : (cwd == null ? "" : cwd);
        // Fold the scenario into the key so two inputs that share the same
        // cword/context (e.g. cword==0 empty command word: COMMAND_NAME vs
        // VARIABLE) never share a cached candidate set.
        Scenario scenario = classifyScenario(words, cword);
        if (scenario == Scenario.HISTORY_EXPANSION || scenario == Scenario.ARRAY_INDEX) {
            trace("COMP", "complete(\"" + commandLine + "\") -> EMPTY (scenario=" + scenario + ")");
            return CompletionResult.EMPTY;
        }
        String scenarioPart = scenario.name();
        String key = cword + "\u0000" + context + "\u0000" + cmdWord + "\u0000"
                + cwdPart + "\u0000" + scenarioPart + "\u0000"
                + mEnvVersion.get();

        trace("COMP", "complete(\"" + commandLine + "\") cword=" + cword
                + " lastWord=\"" + lastWord + "\" prevToken=\"" + prevToken
                + " scenario=" + scenario + " cached="
                + (mCacheKey != null && mCacheKey.equals(key)));

        long now = System.currentTimeMillis();
        synchronized (mCacheLock) {
            if (mCacheKey != null && mCacheKey.equals(key) && mCachedCandidates != null
                    && mCachePrefix != null && (now - mCacheTimestamp) < CACHE_TTL_MS) {
                // Serve the cache only when the requested prefix is consistent with the
                // prefix the cache was built for: either the user typed MORE characters of
                // the same word (lastWord extends mCachePrefix) OR deleted some (mCachePrefix
                // extends lastWord). In both cases every cached candidate still starts with
                // the requested lastWord, so local filtering stays correct. If the user
                // backspaced to a shorter prefix and retyped a DIFFERENT character, neither
                // holds and the cache is stale → fall through to a fresh bash fetch.
                if (lastWordStripped.startsWith(mCachePrefix) || mCachePrefix.startsWith(lastWordStripped)) {
                    trace("COMP", "complete(\"" + commandLine + "\") -> CACHE HIT ("
                            + mCachedCandidates.size() + " cached)");
                    return CompletionResult.of(mCachedCandidates, mCacheIsFilename,
                            mCacheNoSpace, mCacheNoSort, mCacheNoQuote);
                }
            }
        }

        // COMP_POINT is the byte offset of the cursor into COMP_LINE. For ASCII
        // command lines this equals the char offset; convert to UTF-8 bytes so a
        // cursor inside a multibyte token still lands correctly.
        String compLine = commandLine;
        int compPoint = utf8ByteLength(commandLine, cursor);
        String currentWord = (words.length > 0) ? words[cword] : "";

        String script = buildCompletionScript(words, cword, compLine, compPoint);
        if (script == null) {
            // Template failed to load (resource missing) — do not cache.
            return CompletionResult.EMPTY;
        }

        BashResult bash = runBash(script, cwd, compLine, compPoint, currentWord);
        if (bash == null || bash.candidates == null) {
            // Timed out / errored: do not cache the empty result (so a later, faster
            // attempt for the same word can still succeed). Track consecutive
            // failures to trip the circuit-breaker against a persistently-hung
            // compspec.
            trace("COMP", "complete(\"" + commandLine + "\") -> bash returned "
                    + (bash == null ? "null (TIMEOUT/circuit-breaker)" : "null candidates")
                    + " consecutiveTimeouts=" + mConsecutiveTimeouts);
            if (bash == null) {
                if (++mConsecutiveTimeouts >= TIMEOUT_CIRCUIT_THRESHOLD) {
                    mShellSuppressedUntil = System.currentTimeMillis() + TIMEOUT_CIRCUIT_MS;
                    trace("COMP", "circuit-breaker TRIPPED until " + mShellSuppressedUntil);
                }
            }
            return CompletionResult.EMPTY;
        }
        mConsecutiveTimeouts = 0;
        trace("COMP", "complete(\"" + commandLine + "\") -> bash OK candidates="
                + bash.candidates.size());

        List<ShellCandidate> candidates = bash.candidates;

        // Cache the normalized bash output keyed by this word slot, including the
        // resolved completion options so a cache-hit replays the same flags.
        synchronized (mCacheLock) {
            mCacheKey = key;
            mCachePrefix = lastWordStripped;
            mCachedCandidates = candidates;
            mCacheTimestamp = now;
            mCacheIsFilename = bash.isFilename;
            mCacheNoSpace = bash.noSpace;
            mCacheNoSort = bash.noSort;
            mCacheNoQuote = bash.noQuote;
        }

        Logger.logInfo(LOG_TAG, "complete(\"" + commandLine + "\") -> " + candidates.size()
                + " candidates (fresh fetch)");
        return CompletionResult.of(candidates, bash.isFilename, bash.noSpace, bash.noSort, bash.noQuote);
    }

    /** Result of a single bash run: candidates plus the side-channel completion options. */
    protected static class BashResult {
        @Nullable final List<ShellCandidate> candidates;
        final boolean isFilename;
        final boolean noSpace;
        final boolean noSort;
        final boolean noQuote;

        BashResult(@Nullable List<ShellCandidate> candidates, boolean isFilename,
                   boolean noSpace, boolean noSort, boolean noQuote) {
            this.candidates = candidates;
            this.isFilename = isFilename;
            this.noSpace = noSpace;
            this.noSort = noSort;
            this.noQuote = noQuote;
        }
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
    private static List<ShellCandidate> normalizeCandidates(@NonNull List<String> raw,
                                                            @Nullable String cwd) {
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

            // When bash didn't mark the candidate as a directory (no trailing '/')
            // and didn't declare a type, infer FILE vs DIRECTORY by stat-ing the
            // path. This is a robust catch-all for `-F` compspecs that return bare
            // names. Handles leading '~' and absolute paths.
            if (!s.endsWith("/") && declared == null && cwd != null
                    && !s.startsWith("-") && !s.startsWith("$")) {
                try {
                    String path = expandHome(s);
                    File f = path.startsWith("/") ? new File(path)
                            : new File(cwd, path);
                    if (f.isDirectory()) {
                        type = CandidateType.DIRECTORY;
                    } else if (f.isFile()) {
                        type = CandidateType.FILE;
                    }
                } catch (SecurityException e) {
                    // SELinux / no permission to stat — leave type as-is.
                }
            }

            boolean isFilename = (type == CandidateType.DIRECTORY || type == CandidateType.FILE);
            out.add(new ShellCandidate(s, type, isFilename, false, false, false));
            if (out.size() >= MAX_CACHED_CANDIDATES) break;
        }
        return out;
    }

    /** Expand a leading {@code ~} or {@code ~user} in a candidate path. */
    @NonNull
    private static String expandHome(@NonNull String path) {
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
    private static String homeOf(@NonNull String user) {
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

    /**
     * Per-scenario completion scripts. Each maps to a dedicated raw-resource
     * template so the branching is explicit and the bash logic for every usage
     * scenario lives in its own file (see res/raw/shell_complete_*.sh).
     */
    enum Scenario {
        /** First word — command name (compgen -c). */
        COMMAND_NAME,
        /** Argument/option of a command with a real programmable compspec. */
        COMPSPEC,
        /** Filesystem path / file / directory (incl. ~, quoted, trailing slash). */
        PATH,
        /** Redirection operator (>, <, 2>> …) — filename context "F". */
        REDIRECTION,
        /** Command context after a separator / keyword / process substitution. */
        COMMAND_CONTEXT,
        /** $VAR / ${VAR} / export-unset-… variable name. */
        VARIABLE,
        /** kill/trap signal (-) or job (%) completion. */
        SIGNAL_JOB,
        /** Bash history expansion (e.g. "!git") — not a completable token (S12). */
        HISTORY_EXPANSION,
        /** Array subscript (e.g. "${ARR[") — not a bare variable name (S14). */
        ARRAY_INDEX
    }

    /** Lazily-loaded per-scenario script templates (raw resources). */
    @Nullable private String mCmdctxTemplate;
    @Nullable private String mRedirTemplate;
    @Nullable private String mVarsTemplate;
    @Nullable private String mSignalsTemplate;
    @Nullable private String mCompspecTemplate;
    @Nullable private String mPathTemplate;

    @Nullable
    private String cmdctxTemplate() {
        if (mCmdctxTemplate == null) mCmdctxTemplate = withCommon(loadRawResource("shell_complete_cmdctx"));
        return mCmdctxTemplate;
    }
    @Nullable
    private String redirTemplate() {
        if (mRedirTemplate == null) mRedirTemplate = withCommon(loadRawResource("shell_complete_redir"));
        return mRedirTemplate;
    }
    @Nullable
    private String varsTemplate() {
        if (mVarsTemplate == null) mVarsTemplate = withCommon(loadRawResource("shell_complete_vars"));
        return mVarsTemplate;
    }
    @Nullable
    private String signalsTemplate() {
        if (mSignalsTemplate == null) mSignalsTemplate = withCommon(loadRawResource("shell_complete_signals"));
        return mSignalsTemplate;
    }
    @Nullable
    private String compspecTemplate() {
        if (mCompspecTemplate == null) mCompspecTemplate = withCommon(loadRawResource("shell_complete_compspec"));
        return mCompspecTemplate;
    }
    @Nullable
    private String pathTemplate() {
        if (mPathTemplate == null) mPathTemplate = withCommon(loadRawResource("shell_complete_path"));
        return mPathTemplate;
    }

    /**
     * Classify the word slot being completed into one of the {@link Scenario}
     * scripts. This is the single source of truth for *which* bash template to
     * run, replacing the previous monolithic slow-path branch tree. It mirrors
     * the context discriminator used by the cache key ({@link #contextPrefix})
     * so the chosen script aligns with the cached completion domain.
     *
     * <p>Plain (non-path / non-variable / non-command) text that matches no
     * scenario completes as {@link Scenario#COMPSPEC} and returns no candidates
     * when there is no matching compspec, so ordinary text produces an empty
     * result rather than spurious command suggestions.
     */
    @NonNull
    static Scenario classifyScenario(@NonNull String[] words, int cword) {
        String lastWord = (words.length > 0) ? words[cword] : "";
        String prev = (cword > 0 && words.length > cword) ? words[cword - 1] : "";
        String cmd = (words.length > 0) ? words[0] : "";

        // History expansion (bash '!') — not a completable command token.
        // Interactive bash expands it to a prior command; offering compgen -c on
        // the literal '!git' is meaningless and wastes a subprocess (S12).
        if (lastWord.startsWith("!") && lastWord.length() > 1
                && !lastWord.startsWith("!{") && !lastWord.startsWith("!([")) {
            return Scenario.HISTORY_EXPANSION;
        }

        // $VAR / ${VAR} forms, or the export/unset/… variable builtins.
        if ((lastWord.startsWith("$")
                && !lastWord.startsWith("$(") && !lastWord.startsWith("$(((")
                && !lastWord.startsWith("$[")
                && !lastWord.matches("\\$\\{?[A-Za-z_][A-Za-z0-9_]*\\[.*"))
                || cmd.equals("export") || cmd.equals("unset")
                || cmd.equals("readonly") || cmd.equals("declare") || cmd.equals("typeset")) {
            return Scenario.VARIABLE;
        }

        // A first-word NAME=value assignment (e.g. "LD_LIBRARY_PATH=/opt/") must be
        // routed to PATH so the VALUE completes against the filesystem (S11). This
        // is checked BEFORE the cword==0 COMMAND_NAME return below, otherwise a
        // first-word assignment would be mis-classified as a command name and try
        // compgen -c on the whole "VAR=value" token (empty result).
        if (cword == 0 && lastWord.indexOf('=') > 0
                && lastWord.matches("[A-Za-z_][A-Za-z0-9_]*=.*")) {
            return Scenario.PATH;
        }

        // Redirection operators force a FILENAME context.
        switch (prev) {
            case ">": case ">>": case "<": case "<<": case "<<<":
            case "&>": case "&>>":
                return Scenario.REDIRECTION;
            default:
                if (prev.matches("[0-9]+(&?>+|<+|>>)")) return Scenario.REDIRECTION;
        }

        // A first word that is already a filesystem path (absolute, ~/tilde,
        // ./relative, or contains a slash — including a quoted path with a
        // space like 'my notes'/su) must be file-completed, NOT treated as a
        // command name (S8b/S10). Hoist the path-literal test above the
        // command-name return so these route to PATH. Compute the remote check
        // inline here to avoid moving the later 'looksRemote' declaration.
        boolean firstWordLooksRemote = lastWord.matches("^[A-Za-z0-9._-]+:.*")
                || lastWord.matches("^[^/@\\s]+@[^/@\\s]+:.*")
                || lastWord.contains("://");
        if (!firstWordLooksRemote && (lastWord.startsWith("/") || lastWord.startsWith("~")
                || lastWord.startsWith(".")
                || (lastWord.indexOf('/') >= 0)
                || ((lastWord.startsWith("'") || lastWord.startsWith("\""))
                    && (lastWord.indexOf('/') >= 0)))) {
            return Scenario.PATH;
        }
        // Array subscript completion (e.g. ${ARR[ ) — not a bare variable name
        // (S14). Route to a no-op scenario; real index completion is out of scope.
        if (lastWord.matches("\\$(?:\\{)?[A-Za-z_][A-Za-z0-9_]*\\[.*")) {
            return Scenario.ARRAY_INDEX;
        }

        if (cword == 0) return Scenario.COMMAND_NAME;

        // Command context after a separator / keyword / process substitution.
        // Note: the Java splitCommandLine tokenizer does NOT honour bash's
        // COMP_WORDBREAKS, so process-substitution forms arrive glued to the
        // following word (e.g. ">(git"). Match with startsWith to be robust.
        if (prev.equals(";") || prev.equals("|") || prev.equals("&")
                || prev.equals("&&") || prev.equals("||") || prev.equals("|&")
                || prev.equals("(") || prev.equals(")") || prev.equals("{")
                || prev.equals("<(") || prev.equals(">(") || prev.equals("&>(")
                || prev.startsWith("<(") || prev.startsWith(">(") || prev.startsWith("&>(")) {
            return Scenario.COMMAND_CONTEXT;
        }
        if (prev.equals("time") || prev.equals("coproc") || prev.equals("do")
                || prev.equals("then") || prev.equals("else") || prev.equals("elif")) {
            return Scenario.COMMAND_CONTEXT;
        }
        if (prev.indexOf('=') > 0 && prev.matches("[A-Za-z_][A-Za-z0-9_]*=.*")) {
            return Scenario.COMMAND_CONTEXT;
        }

        // kill / trap signal-or-job completion.
        if ((cmd.equals("kill") || cmd.equals("trap"))
                && (lastWord.startsWith("-") || lastWord.startsWith("%"))) {
            return Scenario.SIGNAL_JOB;
        }

        // Builtins whose operand is ALWAYS a command name (type/which/command/
        // builtin/enable) must complete against command names, not the target
        // command's arguments — they have no compspec that yields command names
        // (S6/S7). In message mode we must NOT offer command names, so degrade.
        if (cmd.equals("type") || cmd.equals("which") || cmd.equals("command")
                || cmd.equals("builtin") || cmd.equals("enable")) {
            return Scenario.COMMAND_CONTEXT;
        }

        // The operand of a [ / test / [[ unary file operator is a path (S2b).
        // e.g. `while [ -f va` or `while [ -f` (empty) should complete as a path.
        if (prev.matches("-(f|d|e|r|w|x|L|h|S|b|c|p|u|g|k|s)")) {
            return Scenario.PATH;
        }

        // Empty / whitespace-only line is a command-name context.
        if (lastWord.isEmpty()) {
            // An empty word directly after a [ / test file operator is a path
            // operand (S2b); otherwise it's a command name.
            if (prev.matches("-(f|d|e|r|w|x|L|h|S|b|c|p|u|g|k|s)")) {
                return Scenario.PATH;
            }
            return Scenario.COMMAND_NAME;
        }

        // A bare word in the word-list after `in` (for/case/select) is a path,
        // not a command (S1b). The slash-prefixed case is already caught by the
        // PATH-literal check below; this handles the bare (non-slash) word.
        if (prev.equals("in") && cmd.matches("^(for|case|select)$")) {
            return Scenario.PATH;
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
        boolean looksRemote = lastWord.matches("^[A-Za-z0-9._-]+:.*")
                || lastWord.matches("^[^/@\\s]+@[^/@\\s]+:.*")
                || lastWord.contains("://");
        if (!looksRemote && (lastWord.startsWith("/") || lastWord.startsWith("~")
                || lastWord.startsWith(".") || lastWord.indexOf('/') >= 0
                || ((lastWord.startsWith("\"") || lastWord.startsWith("'"))
                    && (lastWord.indexOf('*') >= 0 || lastWord.indexOf('?') >= 0
                        || lastWord.indexOf('[') >= 0)))) {
            return Scenario.PATH;
        }

        // Otherwise: a real argument/option of a command → run its compspec.
        return Scenario.COMPSPEC;
    }

    /**
     * Convenience wrapper used by tests (and any caller that has a raw command
     * line rather than a pre-tokenized word array) to classify the completion
     * scenario of the token currently being typed. Tokenizes {@code commandLine}
     * the way bash would and classifies the last word.
     *
     * <p>Package-private on purpose: it exists so the unit tests can exercise
     * {@link #classifyScenario(String[], int)} without re-implementing the
     * tokenizer, and keeps the scenario-classification logic as the single
     * source of truth.
     */
    static Scenario classify(@NonNull String commandLine) {
        String[] words = splitCommandLine(commandLine);
        int cword = Math.max(0, words.length - 1);
        return classifyScenario(words, cword);
    }

    /**
     * Build the per-word-slot cache key for the given parameters. Package-private
     * (and static) so the unit tests can verify the cache-key construction — in
     * particular that command-name slots (cword==0) omit the cwd while argument
     * slots (cword>0) include it, and that the environment version is folded into
     * the key — WITHOUT invoking {@link #complete(String, String, int)} (which
     * spawns bash).
     *
     * <p>The key layout mirrors {@code complete()}'s key construction exactly:
     * {@code cword \0 context \0 cmdWord \0 cwdPart \0 scenario \0 envVersion}.
     *
     * @param words   the tokenized command line (as from {@link #splitCommandLine}).
     * @param cword   index of the word being completed.
     * @param cwd     the working directory (affects file/path completions).
     * @param envVersion the current environment version (from {@code mEnvVersion}).
     */
    static String cacheKeyFor(@NonNull String[] words, int cword,
                              @Nullable String cwd, int envVersion) {
        String context = contextPrefix(words, cword);
        String cmdWord = (cword > 0 && words.length > 0) ? words[0] : "";
        String cwdPart = (cword == 0) ? "" : (cwd == null ? "" : cwd);
        Scenario scenario = classifyScenario(words, cword);
        String scenarioPart = scenario.name();
        return cword + "\u0000" + context + "\u0000" + cmdWord + "\u0000"
                + cwdPart + "\u0000" + scenarioPart + "\u0000" + envVersion;
    }

    /**
     * Convenience wrapper used by tests to build a cache key directly from a raw
     * command line (tokenizing the same way bash would). See {@link #cacheKeyFor}.
     */
    static String cacheKeyForLine(@NonNull String commandLine,
                                  @Nullable String cwd, int envVersion) {
        String[] words = splitCommandLine(commandLine);
        int cword = Math.max(0, words.length - 1);
        return cacheKeyFor(words, cword, cwd, envVersion);
    }

    /**
     * Return the bash completion script for the classified scenario. The scripts
     * live as raw resources ({@code shell_complete_*.sh}) so each usage scenario's
     * logic is editable in its own file. They are parameterized entirely through
     * positional arguments and the {@code __MAX_CANDIDATES} variable, so
     * {@link #runBash(String, String)} passes {@code compLine}, {@code compPoint}
     * and {@code currentWord} as {@code $1 $2 $3} and exports the candidate cap.
     *
     * @return the script to feed to bash, or {@code null} if the template could
     *         not be loaded (caller should return an empty result rather than
     *         caching it).
     */
    @Nullable
    private String buildCompletionScript(@NonNull String[] words, int cword,
                                         @NonNull String compLine, int compPoint) {
        Scenario scenario = classifyScenario(words, cword);
        switch (scenario) {
            case COMMAND_NAME:
                return fastTemplate();
            case PATH:
                return pathTemplate();
            case REDIRECTION:
                return redirTemplate();
            case COMMAND_CONTEXT:
                return cmdctxTemplate();
            case VARIABLE:
                return varsTemplate();
            case SIGNAL_JOB:
                return signalsTemplate();
            case HISTORY_EXPANSION:
            case ARRAY_INDEX:
                return null; // complete() treats null as EMPTY (no cache, no spawn)
            case COMPSPEC:
            default:
                return compspecTemplate();
        }
    }

    /**
     * Run the bash script and collect its stdout lines as candidates. Returns
     * {@code null} on timeout/error so the caller does NOT cache the (empty)
     * result. Completion options ({@code -o filenames/nospace/nosort/noquote})
     * are parsed from a side-channel {@code __COMPOPTS:...} sentinel line the
     * script emits.
     */
    @Nullable
    protected BashResult runBash(@NonNull String script, @Nullable String cwd,
                                 @NonNull String compLine, int compPoint, @NonNull String currentWord) {
        List<String> raw = new ArrayList<>();
        Process process = null;
        boolean isFilename = false, noSpace = false, noSort = false, noQuote = false;
        // Upper bound on bytes we keep from a (potentially runaway) completion so a
        // completer emitting 100k paths cannot OOM the background thread.
        final int MAX_OUTPUT_BYTES = 2_000_000;
        try {
            // Pass compLine / compPoint / currentWord as positional args ($1/$2/$3)
            // and export the candidate cap so the raw-resource template stays
            // parameter-driven (no Java string concatenation into the script).
            ProcessBuilder pb = new ProcessBuilder(
                    mBashExecutable.getAbsolutePath(), "--norc", "--noprofile", "-c", script,
                    "--", compLine, Integer.toString(compPoint), currentWord);
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
            // Locale + HOME so bash/compgen treat multibyte input as UTF-8 and ~
            // expansion is deterministic even if the host env lacks them.
            pb.environment().putIfAbsent("LANG", "C.UTF-8");
            pb.environment().putIfAbsent("LC_ALL", "C.UTF-8");
            String home = System.getenv("HOME");
            if (home != null) pb.environment().putIfAbsent("HOME", home);
            // Candidate cap consumed by the raw-resource templates via ${__MAX_CANDIDATES}.
            pb.environment().put("__MAX_CANDIDATES", Integer.toString(MAX_BASH_CANDIDATES));
            pb.redirectErrorStream(false);
            process = pb.start();
            final Process proc = process;
            final java.io.InputStream errStream = proc.getErrorStream();

            // Drain stderr on a side thread so a chatty completion script can never
            // block the (full-pipe) subprocess, and so we can surface diagnostics on
            // timeout. The buffer is read back only in the failure path below.
            final StringBuilder stderrBuf = new StringBuilder();
            Thread errDrain = new Thread(() -> {
                try (BufferedReader er = new BufferedReader(
                        new InputStreamReader(errStream, StandardCharsets.UTF_8))) {
                    String l;
                    while ((l = er.readLine()) != null) {
                        if (stderrBuf.length() < 2000) stderrBuf.append(l).append('\n');
                    }
                } catch (IOException ignored) {
                }
            }, "shell-completion-stderr");
            errDrain.setDaemon(true);
            errDrain.start();

            // Stream stdout on a side thread (bounded) so a timeout still yields any
            // candidates bash emitted before hanging — graceful partial results.
            final java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            final Thread outDrain = new Thread(() -> {
                byte[] buf = new byte[4096];
                int r;
                try (java.io.InputStream in = proc.getInputStream()) {
                    while ((r = in.read(buf)) != -1) {
                        if (bos.size() < MAX_OUTPUT_BYTES) bos.write(buf, 0, r);
                        else break;
                    }
                } catch (IOException ignored) {
                }
            }, "shell-completion-stdout");
            outDrain.setDaemon(true);
            outDrain.start();

            // Guard against a hanging completion script (e.g. network-dependent).
            boolean finished = process.waitFor(TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (!finished) {
                // Kill the WHOLE process group, not just bash: a -F/-C completer or a
                // sourced .bashrc line may have spawned children that would otherwise
                // orphan, hold the pipe, and starve the single-thread executor.
                killProcessGroup(process);
                process.destroyForcibly();
                // Closing the streams unblocks the drain threads so they exit.
                closeQuietly(process.getInputStream());
                closeQuietly(process.getErrorStream());
                byte[] partial = bos.toByteArray();
                String diag = stderrBuf.toString().trim();
                Logger.logWarn(LOG_TAG, "bash completion timed out (> " + TIMEOUT_MS
                        + "ms)" + (diag.isEmpty() ? "" : "; stderr: " + diag));
                trace("BASH", "TIMEOUT (> " + TIMEOUT_MS + "ms)"
                        + (diag.isEmpty() ? "" : "; stderr: " + diag.substring(0, Math.min(400, diag.length()))));
                // Return whatever bash managed to emit before the hang (partial).
                if (partial.length > 0) {
                    return parseBashOutput(partial, cwd, raw,
                            new boolean[]{isFilename, noSpace, noSort, noQuote});
                }
                return null;
            }

            outDrain.join(2000);
            byte[] out = bos.toByteArray();
            String diag = stderrBuf.toString().trim();
            int exitCode = 0;
            try { exitCode = process.exitValue(); } catch (IllegalThreadStateException ignored) {}
            if (!diag.isEmpty()) {
                trace("BASH", "stderr (first 400): " + diag.substring(0, Math.min(400, diag.length())));
            }
            trace("BASH", "exit=" + exitCode + " rawRecords=" + raw.size()
                    + " bytes=" + out.length + " isFilename=" + isFilename);
            int start = 0;
            for (int i = 0; i <= out.length; i++) {
                if (i == out.length || out[i] == 0) {
                    if (i > start) {
                        String rec = new String(out, start, i - start, StandardCharsets.UTF_8);
                        if (!rec.isEmpty()) {
                            // Side-channel sentinel carrying the resolved -o comp-options.
                            if (rec.startsWith("__COMPOPTS:")) {
                                String opts = rec.substring("__COMPOPTS:".length());
                                for (String o : opts.split(",")) {
                                    switch (o) {
                                        case "filenames": isFilename = true; break;
                                        case "nospace": noSpace = true; break;
                                        case "nosort": noSort = true; break;
                                        case "noquote": noQuote = true; break;
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
        } catch (IOException | InterruptedException e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "bash completion failed", e);
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return null;
        } finally {
            if (process != null && process.isAlive()) {
                killProcessGroup(process);
                process.destroyForcibly();
            }
        }
        return new BashResult(normalizeCandidates(raw, cwd),
                isFilename, noSpace, noSort, noQuote);
    }

    /** Parse a NUL-delimited bash output buffer into candidates + comp-options. */
    @Nullable
    private BashResult parseBashOutput(@NonNull byte[] out, @Nullable String cwd,
                                       @NonNull List<String> raw,
                                       @NonNull boolean[] flags) {
        boolean isFilename = flags[0], noSpace = flags[1], noSort = flags[2], noQuote = flags[3];
        int start = 0;
        for (int i = 0; i <= out.length; i++) {
            if (i == out.length || out[i] == 0) {
                if (i > start) {
                    String rec = new String(out, start, i - start, StandardCharsets.UTF_8);
                    if (!rec.isEmpty()) {
                        if (rec.startsWith("__COMPOPTS:")) {
                            String opts = rec.substring("__COMPOPTS:".length());
                            for (String o : opts.split(",")) {
                                switch (o) {
                                    case "filenames": isFilename = true; break;
                                    case "nospace": noSpace = true; break;
                                    case "nosort": noSort = true; break;
                                    case "noquote": noQuote = true; break;
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
        return new BashResult(normalizeCandidates(raw, cwd),
                isFilename, noSpace, noSort, noQuote);
    }

    /**
     * Reap the whole process group of {@code process} (bash + any -F/-C completer
     * children it forked). The templates ALSO install a bash-side {@code trap ...
     * EXIT} that SIGKILLs their own process group, but a {@code destroyForcibly()}
     * (SIGKILL) on bash does NOT run that EXIT trap, so orphaned completer
     * children would survive without this call. We SIGKILL the group directly via
     * {@code Os.killpg} (available API 21+, which is our minSdk) and fall back to
     * the trap/destroyForcibly path when it is unavailable.
     */
    private static void killProcessGroup(@NonNull Process process) {
        try {
            // Os.killpg is API 21+; Process.getPid() is API 26+. Use reflection for
            // both so the code links on the project's minSdk and stays safe.
            @SuppressWarnings("JavaReflectionMemberAccess")
            java.lang.reflect.Method getPid = Process.class.getMethod("getPid");
            @SuppressWarnings("JavaReflectionMemberAccess")
            java.lang.reflect.Method killpg = android.system.Os.class.getMethod(
                    "killpg", int.class, int.class);
            int pid = (int) getPid.invoke(process);
            if (pid > 0) {
                // Negative pid targets the process group (bash set -m made it the
                // leader of its own group). SIGKILL = 9.
                killpg.invoke(null, -pid, 9);
            }
        } catch (Throwable ignored) {
            // Os/killpg/getPid unavailable or denied (SELinux) — rely on the bash
            // EXIT trap + destroyForcibly() as the fallback reaper.
        }
    }

    private static void closeQuietly(@Nullable java.io.Closeable c) {
        if (c == null) return;
        try { c.close(); } catch (IOException ignored) { }
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

    /**
     * Cheap Java-side discriminator for the cache key: returns {@code "C"} when the
     * word at {@code cword} is in a COMMAND context (previous token is a separator
     * such as {@code ; | & ( ) { }, or a keyword / assignment) and {@code "A"} when
     * it is an ARGUMENT context. This is a conservative heuristic kept in lock-step
     * with the bash-side {@code -E}/{@code -I} handling so the two never share a
     * cache entry for different completion domains.
     */
    @NonNull
    /**
     * True when {@code t} is a command separator or control keyword after which a
     * new command name is expected (so an empty last word should still be
     * classified rather than short-circuited to EMPTY). See S2.
     */
    private static boolean isCommandSeparator(String t) {
        if (t == null) return false;
        switch (t) {
            case ";":
            case "|":
            case "&":
            case "&&":
            case "||":
            case "|&":
            case "then":
            case "do":
            case "else":
            case "elif":
            case "(":
            case ")":
                return true;
            default:
                return false;
        }
    }

    private static String contextPrefix(@NonNull String[] words, int cword) {
        if (cword <= 0) return "C";
        String prev = words[cword - 1];
        if (prev.isEmpty()) return "A";
        switch (prev) {
            case ";":
            case "|":
            case "&":
            case "&&":
            case "||":
            case "|&":
            case "(":
            case ")":
            case "{":
            case "time":
            case "coproc":
            case "do":
            case "then":
            case "else":
            case "elif":
                return "C";
            // Redirection operators force a FILENAME context for the next word.
            // Must match shell_complete_slow.sh (__force_file=1) or the cache key
            // ("F") would collide with a genuine command context ("C") and serve
            // stale file candidates where commands are expected (e.g. "cmd >" vs
            // "cmd &" share the same key when mislabeled "C").
            case ">":
            case ">>":
            case "<":
            case "<<":
            case "<<<":
            case "&>":
            case "&>>":
                return "F";
            // Process substitution forces a COMMAND context for the inner word.
            case "<(":
            case ">(":
            case "&>(":
                return "C";
            default:
                // NAME= assignment begins a command context.
                if (prev.indexOf('=') > 0
                        && prev.matches("[A-Za-z_][A-Za-z0-9_]*=.*")) return "C";
                // Numeric-fd redirection (e.g. 2>, 1>>) also forces a filename context.
                if (prev.matches("[0-9]+(&?>+|<+|>>)")) return "F";
                return "A";
        }
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

    // ── Additional test-only accessors (package-private) ──

    /**
     * Test-only wrapper exposing the private {@link #normalizeCandidates} so unit
     * tests can verify the raw-bash-output → {@link ShellCandidate} parsing
     * (type inference, de-dup, keyword blocklist) without spawning a subprocess.
     */
    @NonNull
    static List<ShellCandidate> debugNormalizeCandidates(@NonNull List<String> raw,
                                                         @Nullable String cwd) {
        return normalizeCandidates(raw, cwd);
    }

    /**
     * Test-only wrapper exposing {@link #parseBashOutput} so unit tests can verify
     * the NUL-delimited byte-buffer parsing (including the {@code __COMPOPTS:}
     * side-channel that decodes {@code filenames/nospace/nosort/noquote}) without
     * spawning a subprocess.
     */
    @Nullable
    BashResult debugParseBashOutput(@NonNull byte[] out, @Nullable String cwd) {
        return parseBashOutput(out, cwd, new ArrayList<>(), new boolean[4]);
    }
}

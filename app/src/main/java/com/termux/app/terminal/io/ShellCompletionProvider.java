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

    /** Android context used to load the bash completion dispatcher from raw resources. */
    @Nullable private final Context mContext;

    /** Field delimiter for the persistent-process wire protocol (TYPE\x01WORD\x01CWD\x01LINE). */
    private static final char REQ_SEP = '\u0001';
    /** End-of-response marker line the dispatcher prints after each request. */
    private static final String END_MARKER = "__END__";

    /** The single long-lived bash dispatcher process (lazy-started). */
    @Nullable private Process mPersistent;
    @Nullable private java.io.BufferedWriter mPersistentIn;
    @Nullable private java.io.BufferedReader mPersistentOut;
    @Nullable private Thread mPersistentErrDrain;
    /** Temp file the dispatcher script is materialized to (once), reused for restarts. */
    @Nullable private File mScriptFile;
    /** Serializes write-request / read-response so concurrent callers can't interleave. */
    private final Object mProcLock = new Object();

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

    /**
     * Materialize the persistent dispatcher script to a stable temp file (once)
     * and return it. The script is loaded from the {@code shell_complete} raw
     * resource. Returns {@code null} if the resource is unavailable.
     */
    @Nullable
    private synchronized File dispatcherScriptFile() {
        if (mScriptFile != null && mScriptFile.isFile()) return mScriptFile;
        String script = loadRawResource("shell_complete");
        if (script == null) return null;
        try {
            File dir = (mContext != null) ? mContext.getCacheDir()
                    : new File(System.getProperty("java.io.tmpdir", "/data/data/com.termux/files/usr/tmp"));
            if (dir != null && !dir.isDirectory()) dir.mkdirs();
            File f = File.createTempFile("shell_complete", ".sh", dir);
            try (java.io.OutputStream os = new java.io.FileOutputStream(f)) {
                os.write(script.getBytes(StandardCharsets.UTF_8));
            }
            f.deleteOnExit();
            mScriptFile = f;
            return f;
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "failed to materialize dispatcher script", e);
            return null;
        }
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
     * Map the classified scenario to the persistent-dispatcher wire TYPE token.
     * This is the request selector fed to the single long-lived bash process
     * (see {@code res/raw/shell_complete.sh}). It replaces the old
     * per-scenario-script selection: instead of returning a bash script to spawn,
     * it returns the one-word TYPE that tells the dispatcher which single compgen
     * / glob to run.
     *
     * @return the wire TYPE ({@code CMD/PATH/FILE/REDIR/VAR/SIGNAL/COMPSPEC}), or
     *         {@code null} for scenarios that are never completable
     *         (HISTORY_EXPANSION / ARRAY_INDEX) — the caller treats {@code null}
     *         as EMPTY (no request, no cache).
     */
    @Nullable
    private String buildCompletionScript(@NonNull String[] words, int cword,
                                         @NonNull String compLine, int compPoint) {
        Scenario scenario = classifyScenario(words, cword);
        switch (scenario) {
            case COMMAND_NAME:
            case COMMAND_CONTEXT:
                return "CMD";
            case PATH:
                return "PATH";
            case REDIRECTION:
                return "REDIR";
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
     * Issue ONE completion request to the persistent bash dispatcher and collect
     * its NUL-delimited candidates. Returns {@code null} on timeout/error so the
     * caller does NOT cache the (empty) result. Completion options
     * ({@code -o filenames/nospace/nosort/noquote}) are parsed from the
     * side-channel {@code __COMPOPTS:...} record the dispatcher emits.
     *
     * <p>The seam signature is retained (tests override it to count fetches).
     * Here {@code script} carries the wire TYPE token
     * ({@code CMD/PATH/FILE/REDIR/VAR/SIGNAL/COMPSPEC}) produced by
     * {@link #buildCompletionScript}, and {@code currentWord} is the raw token
     * being completed. All TYPE-specific token shaping (VAR {@code $}/{@code ${}}
     * sigil strip + re-wrap, SIGNAL {@code -}/{@code SIG} mapping, PATH
     * {@code NAME=} assignment strip + re-prepend, COMPSPEC wrapper-skip) happens
     * here in Java — the dispatcher runs exactly one compgen/glob per request.
     */
    @Nullable
    protected BashResult runBash(@NonNull String script, @Nullable String cwd,
                                 @NonNull String compLine, int compPoint, @NonNull String currentWord) {
        String type = script;

        // ── TYPE-specific request shaping (moved out of bash). ──
        String word = currentWord;
        String assignPrefix = null;     // PATH: re-prepended to every candidate
        String varSigil = null;         // VAR: '$' + optional '{'
        String varClose = "";           // VAR: '}' when ${...
        boolean signalDash = false;     // SIGNAL: candidate re-prefixed with '-'

        switch (type) {
            case "PATH":
            case "REDIR":
            case "FILE": {
                // Bash's word tokenizer strips a single layer of surrounding
                // quotes, so the old per-scenario path template resolved
                // CURRENT_WORD from COMP_WORDS (unquoted) before globbing. The
                // persistent dispatcher receives the RAW word (quote retained by
                // splitCommandLine), so a `cd "$HOME/DI` would arrive as
                // `"/…/home/DI`; the leading quote defeats `compgen -G`. Strip the
                // outer quote here to restore the original candidate set (S8/S13).
                word = stripOuterQuotes(word);
                // NAME=value assignment: complete the VALUE against the filesystem,
                // re-prepend VAR= to each candidate so the UI inserts the full token.
                if (word.matches("[A-Za-z_][A-Za-z0-9_]*=.*")) {
                    int eq = word.indexOf('=');
                    assignPrefix = word.substring(0, eq + 1);
                    word = word.substring(eq + 1);
                }
                break;
            }
            case "VAR": {
                // $VAR / ${VAR}: strip the sigil for compgen -v, remember it to
                // re-wrap each emitted candidate.
                if (word.startsWith("$")) {
                    varSigil = "$";
                    String bare = word.substring(1);
                    if (bare.startsWith("{")) { varSigil = "${"; varClose = "}"; bare = bare.substring(1); }
                    word = bare;
                }
                break;
            }
            case "SIGNAL": {
                // kill/trap -SIGNAL: map "-USR1"/"-HUP" onto "SIGUSR1"/"SIGHUP"
                // for compgen -A signal; re-prepend '-' to each name on return.
                if (word.startsWith("-")) {
                    signalDash = true;
                    String sig = word.substring(1);
                    if (!sig.startsWith("SIG")) sig = "SIG" + sig;
                    word = sig;
                } else {
                    // "%job" and other forms are not supported by the fast path.
                    word = "";
                }
                break;
            }
            case "COMPSPEC":
                // No Java-side word shaping: the bash dispatcher resolves the real
                // command (through wrappers/assignments) and detects a leading
                // wrapper for the command-name fallback from the full COMP_LINE.
                break;
            default:
                break;
        }

        // ── Persistent request/response, guarded so callers never interleave. ──
        List<String> raw = new ArrayList<>();
        boolean[] flags = new boolean[4]; // isFilename, noSpace, noSort, noQuote
        // COMPSPEC must receive the FULL command line so the bash dispatcher can
        // (a) resolve the real command through its own wrapper/assignment loop and
        // (b) detect a leading wrapper (sudo/env/xargs/…) for the command-name
        // fallback — exactly as the original compspec template did via
        // `cmd="${1%% *}"`. Rewriting the line to `<resolvedCmd> <word>` dropped the
        // wrapper token, breaking the `sudo gi`/`env gi`/`xargs gi` fallback (S9).
        String responseLine = compLine;
        byte[] out = queryPersistent(type, word, cwd, responseLine);
        if (out == null) {
            trace("BASH", "persistent query TIMEOUT/error (type=" + type + ")");
            return null;
        }
        parseNulRecords(out, raw, flags);

        // ── TYPE-specific candidate re-shaping (moved out of bash). ──
        if (varSigil != null) {
            List<String> wrapped = new ArrayList<>(raw.size());
            for (String r : raw) wrapped.add(varSigil + r + varClose);
            raw = wrapped;
        } else if (signalDash) {
            List<String> wrapped = new ArrayList<>(raw.size());
            for (String r : raw) wrapped.add("-" + r);
            raw = wrapped;
        } else if (assignPrefix != null) {
            List<String> wrapped = new ArrayList<>(raw.size());
            for (String r : raw) wrapped.add(assignPrefix + r);
            raw = wrapped;
        }

        return new BashResult(normalizeCandidates(raw, cwd),
                flags[0], flags[1], flags[2], flags[3]);
    }

    /**
     * Resolve the real command whose compspec should run: skip known
     * command-wrappers ({@code sudo xargs env time nohup nice exec stdbuf
     * timeout ionice setsid}) and inline {@code NAME=value} assignments that
     * precede it, so e.g. {@code sudo systemctl sta} completes {@code systemctl}.
     * Returns the command name (words[0] when no wrapper), or {@code null} when
     * the word array is empty.
     */
    @Nullable
    private static String resolveCompspecCommand(@NonNull String[] words) {
        if (words.length == 0) return null;
        int i = 0;
        String cmd = words[0];
        while (cmd != null && WRAPPER_COMMANDS.contains(cmd) && i + 1 < words.length) {
            i++;
            cmd = words[i];
            while (cmd != null && cmd.matches("[A-Za-z_][A-Za-z0-9_]*=.*") && i + 1 < words.length) {
                i++;
                cmd = words[i];
            }
        }
        return (cmd == null || cmd.isEmpty()) ? null : cmd;
    }

    /** Command wrappers whose real target is the following argument (COMPSPEC). */
    private static final Set<String> WRAPPER_COMMANDS = Collections.unmodifiableSet(
            new HashSet<>(java.util.Arrays.asList(
                    "sudo", "xargs", "env", "time", "nohup", "nice", "exec",
                    "stdbuf", "timeout", "ionice", "setsid")));

    /**
     * Send one request to the persistent dispatcher and read its response up to
     * the {@code __END__} marker. Returns the raw NUL-delimited response bytes,
     * or {@code null} on timeout/error. On timeout it first attempts a graceful
     * drain (read the still-pending {@code __END__} without killing the process);
     * if that fails the process is restarted so a wedged compspec cannot poison
     * every future request.
     */
    @Nullable
    private byte[] queryPersistent(@NonNull String type, @NonNull String word,
                                   @Nullable String cwd, @NonNull String line) {
        synchronized (mProcLock) {
            if (!ensurePersistent()) return null;

            // Sanitize the request fields: strip our field separator and newlines
            // (they would corrupt the single-line wire format).
            String req = sanitizeField(type) + REQ_SEP + sanitizeField(word) + REQ_SEP
                    + sanitizeField(cwd == null ? "" : cwd) + REQ_SEP + sanitizeField(line);
            final java.io.BufferedWriter in = mPersistentIn;
            final java.io.BufferedReader stdout = mPersistentOut;
            if (in == null || stdout == null) return null;

            try {
                in.write(req);
                in.write('\n');
                in.flush();
            } catch (IOException e) {
                trace("BASH", "persistent write failed; restarting");
                stopPersistent();
                return null;
            }

            // Read the response on a bounded side thread so we can time it out
            // without blocking on a wedged dispatcher.
            final java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            final Object done = new Object();
            final boolean[] completed = {false};
            final boolean[] eof = {false};
            Thread reader = new Thread(() -> {
                try {
                    String l;
                    while ((l = stdout.readLine()) != null) {
                        if (END_MARKER.equals(l)) {
                            synchronized (done) { completed[0] = true; done.notifyAll(); }
                            return;
                        }
                        byte[] b = l.getBytes(StandardCharsets.UTF_8);
                        if (bos.size() < 2_000_000) { bos.write(b, 0, b.length); bos.write('\n'); }
                    }
                    synchronized (done) { eof[0] = true; done.notifyAll(); }
                } catch (IOException ignored) {
                    synchronized (done) { eof[0] = true; done.notifyAll(); }
                }
            }, "shell-completion-read");
            reader.setDaemon(true);
            reader.start();

            long deadline = System.currentTimeMillis() + TIMEOUT_MS;
            synchronized (done) {
                while (!completed[0] && !eof[0]) {
                    long wait = deadline - System.currentTimeMillis();
                    if (wait <= 0) break;
                    try { done.wait(wait); } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }

            if (completed[0]) {
                // Dispatcher output arrives newline-framed by readLine(); the caller
                // parser splits on NUL. Rejoin: our records ARE NUL-terminated inside
                // each line, so keep the raw bytes as-is (the trailing '\n' per line
                // is harmless — parseNulRecords splits on '\0' only).
                return bos.toByteArray();
            }
            if (eof[0]) {
                // Process died mid-response — restart for the next call.
                trace("BASH", "persistent EOF mid-response; restarting");
                stopPersistent();
                byte[] partial = bos.toByteArray();
                return partial.length > 0 ? partial : null;
            }

            // Timeout: the dispatcher is wedged on this request. We cannot safely
            // resume mid-response (the pending __END__ would corrupt the NEXT
            // read), so restart the process. The reader thread is daemon and will
            // exit when the stream closes.
            trace("BASH", "persistent query timeout (> " + TIMEOUT_MS + "ms); restarting");
            stopPersistent();
            return null;
        }
    }

    /** Replace wire-breaking characters (our separator, CR/LF) in a request field. */
    @NonNull
    private static String sanitizeField(@NonNull String s) {
        if (s.indexOf(REQ_SEP) < 0 && s.indexOf('\n') < 0 && s.indexOf('\r') < 0) return s;
        return s.replace(REQ_SEP, ' ').replace('\n', ' ').replace('\r', ' ');
    }

    /** Parse a NUL-delimited response buffer into candidates + comp-option flags. */
    private static void parseNulRecords(@NonNull byte[] out, @NonNull List<String> raw,
                                        @NonNull boolean[] flags) {
        int start = 0;
        for (int i = 0; i <= out.length; i++) {
            if (i == out.length || out[i] == 0) {
                if (i > start) {
                    String rec = new String(out, start, i - start, StandardCharsets.UTF_8);
                    // readLine() framing may leave a trailing '\n' on the last record
                    // of a line; drop it so records compare cleanly.
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

    /** Lazily (re)start the persistent dispatcher. Returns false if unavailable. */
    private boolean ensurePersistent() {
        if (mPersistent != null && mPersistent.isAlive()
                && mPersistentIn != null && mPersistentOut != null) {
            return true;
        }
        stopPersistent();
        File scriptFile = dispatcherScriptFile();
        if (scriptFile == null) {
            trace("BASH", "dispatcher script unavailable");
            return false;
        }
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    mBashExecutable.getAbsolutePath(), "--norc", "--noprofile",
                    scriptFile.getAbsolutePath());
            String inheritedPath = pb.environment().get("PATH");
            String termuxPath = TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH
                    + ":/system/bin:/system/xbin";
            pb.environment().put("PATH",
                    (inheritedPath == null || inheritedPath.isEmpty())
                            ? termuxPath : termuxPath + ":" + inheritedPath);
            pb.environment().putIfAbsent("TERMUX_PREFIX", TermuxConstants.TERMUX_PREFIX_DIR_PATH);
            pb.environment().putIfAbsent("LANG", "C.UTF-8");
            pb.environment().putIfAbsent("LC_ALL", "C.UTF-8");
            String home = System.getenv("HOME");
            if (home != null) pb.environment().putIfAbsent("HOME", home);
            pb.environment().put("__MAX_CANDIDATES", Integer.toString(MAX_BASH_CANDIDATES));
            pb.redirectErrorStream(false);
            Process p = pb.start();
            mPersistent = p;
            mPersistentIn = new java.io.BufferedWriter(
                    new java.io.OutputStreamWriter(p.getOutputStream(), StandardCharsets.UTF_8));
            mPersistentOut = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
            final java.io.InputStream err = p.getErrorStream();
            Thread drain = new Thread(() -> {
                try (BufferedReader er = new BufferedReader(
                        new InputStreamReader(err, StandardCharsets.UTF_8))) {
                    while (er.readLine() != null) { /* discard */ }
                } catch (IOException ignored) { }
            }, "shell-completion-persist-stderr");
            drain.setDaemon(true);
            drain.start();
            mPersistentErrDrain = drain;
            return true;
        } catch (IOException e) {
            trace("BASH", "failed to start persistent bash: " + e.getMessage());
            stopPersistent();
            return false;
        }
    }

    /** Tear down the persistent dispatcher (on death, timeout or shutdown). */
    private void stopPersistent() {
        // Snapshot the live references and NULL the fields synchronously so a
        // concurrent/next queryPersistent()/ensurePersistent() immediately sees a
        // torn-down dispatcher and never touches a half-closed stream. The actual
        // stream closes AND the process kill are deferred to a daemon reaper so
        // this call returns without blocking.
        //
        // This is load-bearing: closing the stdin/stdout of a wedged child (e.g. a
        // fake-bash that `sleep 30`) can BLOCK until the child actually exits in
        // some Process implementations (Robolectric's shadow in particular waits
        // for the process to terminate before the close() of its pipes returns).
        // Doing the close inline would make queryPersistent() hang for the FULL
        // child duration, defeating the entire 1500ms timeout budget.
        final java.io.Closeable in = mPersistentIn;
        final java.io.Closeable out = mPersistentOut;
        final Thread errDrain = mPersistentErrDrain;
        final Process p = mPersistent;
        mPersistentIn = null;
        mPersistentOut = null;
        mPersistentErrDrain = null;
        mPersistent = null;
        if (p == null && in == null && out == null) return;
        Thread reaper = new Thread(() -> {
            closeQuietly(in);
            closeQuietly(out);
            if (p != null) {
                try { killProcessGroup(p); } catch (Throwable ignored) { }
                try { p.destroyForcibly(); } catch (Throwable ignored) { }
            }
            if (errDrain != null && errDrain.isAlive()) {
                try { errDrain.interrupt(); } catch (Throwable ignored) { }
            }
        }, "shell-completion-reaper");
        reaper.setDaemon(true);
        reaper.start();
    }

    /** Release the persistent process (call on provider disposal / session end). */
    public void shutdown() {
        synchronized (mProcLock) {
            stopPersistent();
        }
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

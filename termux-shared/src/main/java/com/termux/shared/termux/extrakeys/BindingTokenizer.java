package com.termux.shared.termux.extrakeys;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Tokenizer for macro bindings that may contain delay tokens ({@code DELAY_100}, {@code SLEEP_500}).
 * <p>
 * Bindings are whitespace-separated sequences of tokens. A token is a delay if it starts with
 * {@code DELAY_} or {@code SLEEP_} (case-insensitive) followed by a positive integer. Legacy
 * {@code SLEEP_} tokens are normalized to {@code DELAY_}.
 */
public class BindingTokenizer {

    /** Minimum allowed delay value in milliseconds. */
    public static final int MIN_DELAY_MS = 1;

    /** Maximum allowed delay value in milliseconds. */
    public static final int MAX_DELAY_MS = 1000;

    /** Canonical delay prefix. */
    public static final String DELAY_PREFIX = "DELAY_";

    /** Legacy sleep prefix, normalized to {@link #DELAY_PREFIX} during tokenization. */
    public static final String LEGACY_SLEEP_PREFIX = "SLEEP_";

    private static final int PARSE_BASE = 10;

    private BindingTokenizer() {
        // utility class
    }

    /**
     * Tokenize a binding string by splitting on whitespace, normalizing delay tokens.
     * <p>
     * Legacy {@code SLEEP_} tokens are converted to the canonical {@code DELAY_} form and the
     * value is clamped to [{@link #MIN_DELAY_MS}, {@link #MAX_DELAY_MS}]. Empty strings resulting
     * from the split are discarded.
     *
     * @param binding the raw binding string (may be {@code null}).
     * @return a non-null list of normalized tokens.
     */
    @NonNull
    public static List<String> tokenize(String binding) {
        List<String> result = new ArrayList<>();
        if (binding == null || binding.isEmpty()) return result;

        String[] parts = binding.split("\\s+");
        for (String part : parts) {
            if (part.isEmpty()) continue;
            result.add(normalize(part));
        }
        return result;
    }

    /**
     * Returns {@code true} if {@code token} is a valid delay token: starts with {@link #DELAY_PREFIX}
     * followed by a positive integer in [{@link #MIN_DELAY_MS}, {@link #MAX_DELAY_MS}].
     *
     * @param token the token to check.
     * @return {@code true} if the token is a valid delay.
     */
    public static boolean isDelay(String token) {
        if (token == null || !token.startsWith(DELAY_PREFIX)) return false;
        int ms = parseUnsignedIntSuffix(token, DELAY_PREFIX.length());
        return ms >= MIN_DELAY_MS && ms <= MAX_DELAY_MS;
    }

    /**
     * Returns {@code true} if {@code token} starts with {@link #DELAY_PREFIX} or
     * {@link #LEGACY_SLEEP_PREFIX} (case-insensitive), regardless of whether a valid number follows.
     *
     * @param token the token to check.
     * @return {@code true} if the token has a delay prefix.
     */
    public static boolean hasDelayPrefix(String token) {
        if (token == null) return false;
        String upper = token.toUpperCase(Locale.US);
        return upper.startsWith(DELAY_PREFIX) || upper.startsWith(LEGACY_SLEEP_PREFIX);
    }

    /**
     * Parse the delay value (in milliseconds) from a delay token. The value is clamped to
     * [{@link #MIN_DELAY_MS}, {@link #MAX_DELAY_MS}]. Returns {@code 0} if the token is not a
     * valid delay.
     *
     * @param token the delay token.
     * @return the clamped delay value, or {@code 0} if invalid.
     */
    public static int parseDelayMs(String token) {
        if (token == null) return 0;
        String upper = token.toUpperCase(Locale.US);
        int prefixLen;
        if (upper.startsWith(DELAY_PREFIX)) {
            prefixLen = DELAY_PREFIX.length();
        } else if (upper.startsWith(LEGACY_SLEEP_PREFIX)) {
            prefixLen = LEGACY_SLEEP_PREFIX.length();
        } else {
            return 0;
        }
        int ms = parseUnsignedIntSuffix(upper, prefixLen);
        if (ms < 0) return 0;
        return clamp(ms);
    }

    /**
     * Generate the canonical delay token string for the given value.
     *
     * @param ms the delay value in milliseconds.
     * @return {@code "DELAY_" + }{@link #clamp(int) clamp(ms)}.
     */
    @NonNull
    public static String delayToken(int ms) {
        return DELAY_PREFIX + clamp(ms);
    }

    /**
     * Returns {@code true} if any token in the list is a valid delay.
     *
     * @param tokens the list of tokens.
     * @return {@code true} if a delay is present.
     */
    public static boolean containsDelay(@NonNull List<String> tokens) {
        for (String token : tokens) {
            if (isDelay(token)) return true;
        }
        return false;
    }

    /**
     * Compute the sum of all delay values from individual tokens.
     *
     * @param tokens the list of tokens.
     * @return the total delay in milliseconds.
     */
    public static int totalDelayMs(@NonNull List<String> tokens) {
        int total = 0;
        for (String token : tokens) {
            total += parseDelayMs(token);
        }
        return total;
    }

    /**
     * Collapse consecutive delay tokens into a single delay whose value is the sum of the
     * individual delays (clamped to [{@link #MIN_DELAY_MS}, {@link #MAX_DELAY_MS}]).
     * <p>
     * Non-delay tokens are preserved in their original order.
     *
     * @param tokens the input list of tokens.
     * @return a new list with consecutive delays merged.
     */
    @NonNull
    public static List<String> collapseConsecutiveDelays(@NonNull List<String> tokens) {
        List<String> result = new ArrayList<>();
        int pendingDelay = 0;

        for (String token : tokens) {
            int ms = parseDelayMs(token);
            if (ms > 0) {
                pendingDelay += ms;
            } else {
                if (pendingDelay > 0) {
                    result.add(delayToken(pendingDelay));
                    pendingDelay = 0;
                }
                result.add(token);
            }
        }
        if (pendingDelay > 0) {
            result.add(delayToken(pendingDelay));
        }

        return result;
    }

    /**
     * Clamp a value to [{@link #MIN_DELAY_MS}, {@link #MAX_DELAY_MS}].
     *
     * @param ms the value to clamp.
     * @return the clamped value.
     */
    public static int clamp(int ms) {
        if (ms < MIN_DELAY_MS) return MIN_DELAY_MS;
        if (ms > MAX_DELAY_MS) return MAX_DELAY_MS;
        return ms;
    }

    // ---- internal helpers ----

    /**
     * Normalize a single token: convert {@code SLEEP_} to {@code DELAY_}, uppercase prefix, and
     * clamp the value if it is a valid delay.
     */
    @NonNull
    private static String normalize(@NonNull String token) {
        String upper = token.toUpperCase(Locale.US);
        if (upper.startsWith(LEGACY_SLEEP_PREFIX)) {
            String suffix = upper.substring(LEGACY_SLEEP_PREFIX.length());
            int ms = parseUnsignedIntSuffix(suffix, 0);
            return delayToken(ms);
        }
        if (upper.startsWith(DELAY_PREFIX)) {
            String suffix = upper.substring(DELAY_PREFIX.length());
            int ms = parseUnsignedIntSuffix(suffix, 0);
            return delayToken(ms);
        }
        return token;
    }

    /**
     * Parse an unsigned integer from the suffix of an already-uppercased string starting at the
     * given offset. Returns -1 if the suffix is not a valid non-negative integer.
     */
    private static int parseUnsignedIntSuffix(@NonNull String s, int offset) {
        if (offset >= s.length()) return -1;
        int value = 0;
        for (int i = offset; i < s.length(); i++) {
            char c = s.charAt(i);
            int digit = Character.digit(c, PARSE_BASE);
            if (digit < 0) return -1;
            value = value * PARSE_BASE + digit;
        }
        return value;
    }
}

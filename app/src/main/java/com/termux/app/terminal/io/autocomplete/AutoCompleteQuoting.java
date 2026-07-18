package com.termux.app.terminal.io.autocomplete;

import androidx.annotation.NonNull;

/**
 * Shell-aware quoting helpers used when inserting a chosen completion candidate
 * into the command line. All methods are static and stateless.
 */
final class AutoCompleteQuoting {

    private AutoCompleteQuoting() {}

    /** Quote {@code s} only if it contains a shell metacharacter; leave existing quotes intact. */
    @NonNull
    static String quoteIfNeeded(@NonNull String s) {
        if (s.isEmpty()) return s;
        // Already a fully-quoted token -> leave untouched.
        if (isAlreadyQuoted(s)) return s;
        // A leading '~' (or '~user') must keep its tilde UNQUOTED so bash performs
        // tilde expansion; only the remainder (which may contain spaces) is quoted.
        // e.g. ~/My Documents -> ~/My\ Documents, ~bob/My Docs -> ~bob/My\ Docs.
        if (s.charAt(0) == '~') {
            int slash = s.indexOf('/');
            String tildePart = (slash < 0) ? s : s.substring(0, slash);
            String rest = (slash < 0) ? "" : s.substring(slash);
            if (needsQuoting(rest)) {
                return tildePart + quoteHard(rest);
            }
            return s;
        }
        if (needsQuoting(s)) {
            return quoteHard(s);
        }
        return s;
    }

    /** True if {@code s} contains a shell metacharacter that warrants quoting. */
    static boolean needsQuoting(@NonNull String s) {
        for (int i = 0; i < s.length(); i++) {
            if (" \t()&;|<>*?[]{}'\"$`\\".indexOf(s.charAt(i)) >= 0) return true;
        }
        return false;
    }

    /** Wrap {@code s} in single quotes, escaping embedded single quotes POSIX-style. */
    @NonNull
    static String quoteHard(@NonNull String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    /**
     * True if {@code s} is already a complete, balanced, shell-valid quoted token
     * and must NOT be re-quoted. We only accept a token that is fully surrounded
     * by a matching open+close quote; we do NOT treat a token that merely
     * *contains* a stray quote (e.g. a real filename {@code it's}) as "done",
     * because that would leave an unescaped metacharacter in the command line.
     */
    static boolean isAlreadyQuoted(@NonNull String s) {
        int n = s.length();
        if (n < 2) return false;
        char first = s.charAt(0);
        char last = s.charAt(n - 1);
        return (first == '\'' && last == '\'') || (first == '"' && last == '"');
    }
}

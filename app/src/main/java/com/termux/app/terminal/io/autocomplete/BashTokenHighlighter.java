package com.termux.app.terminal.io.autocomplete;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Lightweight, stateless bash command-line tokenizer used purely for
 * syntax highlighting (NOT for completion semantics). It walks a command line
 * and produces a list of {@link Token}s, each tagged with a {@link Role} so the
 * caller can colour recognised elements differently from the main text.
 *
 * <p>Everything here is a pure function of the input string — no colour, no
 * scheme, no Android dependencies — so it is trivially unit-testable. The actual
 * colours are resolved by the caller from {@code TermuxColorSchemeManager},
 * keeping light/dark values and caching in one place.
 *
 * <p>The classifier is intentionally heuristic (good-enough for highlighting),
 * not a full POSIX-shell parser: it recognises commands, paths, variables,
 * options, quoted strings and operators/redirections, and leaves everything
 * else as {@link Role#TEXT} (drawn in the normal input colour).
 */
final class BashTokenHighlighter {

    private BashTokenHighlighter() {}

    /** Semantic role of a highlighted token. */
    enum Role {
        /** Command name (first word of a simple command / after a separator). */
        COMMAND,
        /** Path or directory token (contains '/', starts with '~' or '.' or '/'). */
        PATH,
        /** Variable expansion: {@code $VAR}, {@code ${VAR}}, {@code $VAR[..]}. */
        VARIABLE,
        /** Option / flag: {@code -x}, {@code --long}. */
        OPTION,
        /** Single- or double-quoted string. */
        QUOTED,
        /** Operator / redirection / separator: {@code |}, {@code &&}, {@code >}, {@code ;}. */
        OPERATOR,
        /** Anything not otherwise recognised — drawn in the normal text colour. */
        TEXT
    }

    /** A contiguous, classified slice of the input line. */
    static final class Token {
        @NonNull final Role role;
        final int start;
        final int end;

        Token(@NonNull Role role, int start, int end) {
            this.role = role;
            this.start = start;
            this.end = end;
        }

        int length() { return end - start; }
    }

    /** Characters that separate "words" for highlighting purposes. */
    private static boolean isBreak(char c) {
        switch (c) {
            case ' ': case '\t': case '\n': case '\r':
            case '|': case '&': case ';': case '<': case '>':
            case '(': case ')': case '{': case '}':
                return true;
            default:
                return false;
        }
    }

    /** Classify a single bare (unquoted) word given its position. */
    @NonNull
    private static Role classifyWord(@NonNull String word, boolean isCommandPosition) {
        if (word.isEmpty()) return Role.TEXT;

        // Variable expansion ($VAR, ${VAR}, $VAR[..]).
        if (word.charAt(0) == '$') return Role.VARIABLE;

        // Assignment NAME=value: highlight the NAME as VARIABLE, value as TEXT.
        int eq = word.indexOf('=');
        if (eq > 0 && isName(word.substring(0, eq))) return Role.VARIABLE;

        // Option / flag.
        if (word.charAt(0) == '-' && word.length() > 1) return Role.OPTION;

        // Path-like token.
        if (word.charAt(0) == '/' || word.charAt(0) == '~'
                || word.charAt(0) == '.' || word.indexOf('/') >= 0) {
            return Role.PATH;
        }

        // First word of a command (or after a separator) is the command name,
        // but only if it actually looks like a command name — a bare glob/wildcard
        // such as '*' or '?' in command position is not a command (e.g. the
        // iterator in "for f in *"). Anything that isn't a plausible command name
        // is left as TEXT so it isn't mis-coloured.
        if (isCommandPosition && looksLikeCommandName(word)) return Role.COMMAND;

        return Role.TEXT;
    }

    /** True for a valid POSIX shell NAME ({@code [A-Za-z_][A-Za-z0-9_]*}) or its prefix. */
    private static boolean isName(@NonNull String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean ok = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                    || c == '_' || (i > 0 && c >= '0' && c <= '9');
            if (!ok) return false;
        }
        return !s.isEmpty();
    }

    /** Single-name character (letter / underscore / digit). */
    private static boolean isNameChar(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                || c == '_' || (c >= '0' && c <= '9');
    }

    /** A word is a plausible command name if it is a valid NAME with no glob chars. */
    private static boolean looksLikeCommandName(@NonNull String w) {
        if (w.isEmpty()) return false;
        for (int i = 0; i < w.length(); i++) {
            char c = w.charAt(i);
            if (!isNameChar(c) && c != '.' && c != '-') return false;
        }
        return isName(w); // also requires a letter/underscore as first char
    }

    /** Shell keywords after which the following word is a command name. */
    private static boolean isCommandResetter(@NonNull String w) {
        switch (w) {
            case "if": case "then": case "else": case "elif":
            case "do": case "while": case "for": case "function":
            case "until": case "case": case "in": case "select":
                return true;
            default:
                return false;
        }
    }

    /** True for a multi-char operator token we paint as OPERATOR. */
    private static boolean isOperatorWord(@NonNull String w) {
        switch (w) {
            case "|": case "||": case "&": case "&&": case ";":
            case ";;": case "(" : case ")": case "{": case "}":
            case "<": case ">": case "<<": case ">>": case "<<<":
            case "&>": case "&>>": case "|&": case ";;&": case ";*":
                return true;
            default:
                // Numeric-fd redirection like 2>, 1>&2 handled via '=' and '>' breaks;
                // single leading digit followed by '>' is split, so accept a bare
                // '>'/'<' family already covered. Keep simple.
                return false;
        }
    }

    /**
     * Tokenize {@code line} into classified, non-overlapping, contiguous spans
     * covering the whole string (so the caller can colour each and leave
     * {@link Role#TEXT} tokens in the default colour). Returns an empty list for
     * {@code null}/empty input.
     */
    @NonNull
    static List<Token> tokenize(@Nullable String line) {
        List<Token> out = new ArrayList<>();
        if (line == null || line.isEmpty()) return out;

        int n = line.length();
        int i = 0;
        boolean commandPosition = true; // first word is a command

        while (i < n) {
            char c = line.charAt(i);

            // Whitespace run -> TEXT (invisible span, keeps offsets contiguous).
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                int start = i;
                while (i < n && (line.charAt(i) == ' ' || line.charAt(i) == '\t'
                        || line.charAt(i) == '\n' || line.charAt(i) == '\r')) i++;
                out.add(new Token(Role.TEXT, start, i));
                continue;
            }

            // Quoted string (single or double) -> QUOTED.
            if (c == '\'' || c == '"') {
                char q = c;
                int start = i;
                i++; // skip opening quote
                while (i < n) {
                    if (line.charAt(i) == '\\' && q == '"' && i + 1 < n) {
                        i += 2; // escaped char inside double quotes
                        continue;
                    }
                    if (line.charAt(i) == q) { i++; break; }
                    i++;
                }
                out.add(new Token(Role.QUOTED, start, i));
                commandPosition = false;
                continue;
            }

            // Variable expansion: $VAR, ${VAR}, ${VAR[0]}, $1, $@, $?.
            if (c == '$') {
                int start = i;
                i++; // consume '$'
                if (i < n && line.charAt(i) == '{') {
                    i++; // consume '{'
                    while (i < n && line.charAt(i) != '}') {
                        if (line.charAt(i) == '[') {
                            while (i < n && line.charAt(i) != ']') i++;
                            if (i < n) i++; // consume ']'
                        } else {
                            i++;
                        }
                    }
                    if (i < n) i++; // consume '}'
                } else if (i < n && (line.charAt(i) == '@' || line.charAt(i) == '?'
                        || line.charAt(i) == '#' || line.charAt(i) == '!' || line.charAt(i) == '*')) {
                    i++; // special parameter
                } else {
                    while (i < n && (isNameChar(line.charAt(i))
                            || (line.charAt(i) >= '0' && line.charAt(i) <= '9'))) i++;
                }
                out.add(new Token(Role.VARIABLE, start, i));
                commandPosition = false;
                continue;
            }

            // Break character (operator / redirection / bracket) -> OPERATOR.
            if (isBreak(c) && c != '=') {
                int start = i;
                // Process substitution: <( >( &>( reset command position and group.
                if ((c == '<' || c == '>') && i + 1 < n && line.charAt(i + 1) == '('
                        || (c == '&' && i + 1 < n && i + 2 < n && line.charAt(i + 1) == '>'
                            && line.charAt(i + 2) == '(')) {
                    if (c == '&') i += 3; // &>(
                    else i += 2;          // <( or >(
                    out.add(new Token(Role.OPERATOR, start, i));
                    commandPosition = true;
                    continue;
                }
                // consume a compound operator run for nicer grouping
                while (i < n && isBreak(line.charAt(i)) && line.charAt(i) != '='
                        && isOperatorWord(line.substring(start, Math.min(i + 1, n)))) {
                    i++;
                    String run = line.substring(start, i);
                    if (!isOperatorWord(run) && run.length() > 1) { i--; break; }
                }
                if (i == start) i++; // always consume at least one
                out.add(new Token(Role.OPERATOR, start, i));
                // After a command separator / control operator a new command can start.
                if (isCommandSeparatorRun(line, start, i)) commandPosition = true;
                continue;
            }

            // Bare word.
            int start = i;
            while (i < n && !isBreak(line.charAt(i)) && line.charAt(i) != '\''
                    && line.charAt(i) != '"') i++;
            String word = line.substring(start, i);
            Role role = classifyWord(word, commandPosition);
            out.add(new Token(role, start, i));
            // A control keyword resets command position for the following word.
            if (role != Role.VARIABLE && role != Role.PATH && role != Role.OPTION
                    && isCommandResetter(word)) {
                commandPosition = true;
            } else {
                commandPosition = false;
            }
        }

        return out;
    }

    /** Heuristic: does this OPERATOR run end a command (so the next word is a command)? */
    private static boolean isCommandSeparatorRun(@NonNull String line, int start, int end) {
        String run = line.substring(start, end);
        switch (run) {
            case ";": case "|": case "&": case "&&": case "||": case "|&":
            case "(": case "{": case "<(": case ">(": case "&>(":
                return true;
            default:
                return run.equals("then") || run.equals("else") || run.equals("elif")
                        || run.equals("do") || run.startsWith("<(") || run.startsWith(">(")
                        || run.startsWith("&>(");
        }
    }
}

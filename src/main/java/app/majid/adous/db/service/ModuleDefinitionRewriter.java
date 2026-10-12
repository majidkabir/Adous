package app.majid.adous.db.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Rewrites the leading {@code CREATE} of a T-SQL module definition (procedure, function,
 * view, trigger) without touching comments, string literals or quoted identifiers.
 * <p>
 * Applying modules with {@code CREATE OR ALTER} instead of {@code DROP} + {@code CREATE}
 * keeps the object's id, so permissions (GRANT/DENY), extended properties and
 * dependency metadata survive a sync.
 */
public final class ModuleDefinitionRewriter {

    /** Kind of user-defined function, which {@code CREATE OR ALTER} cannot change. */
    public enum FunctionKind { SCALAR, INLINE_TABLE, MULTI_STATEMENT_TABLE }

    private static final Set<String> MODULE_KEYWORDS = Set.of("PROC", "PROCEDURE", "FUNCTION", "VIEW", "TRIGGER");

    private ModuleDefinitionRewriter() {
    }

    /**
     * Turns the module's {@code CREATE} into {@code CREATE OR ALTER}.
     *
     * @return the rewritten definition (unchanged if it already uses {@code CREATE OR ALTER}),
     *         or empty if no {@code CREATE PROCEDURE/FUNCTION/VIEW/TRIGGER} statement was found
     */
    public static Optional<String> toCreateOrAlter(String sql) {
        List<Token> tokens = tokenize(sql);
        int create = findModuleCreate(tokens);
        if (create < 0) {
            return Optional.empty();
        }
        if (isOrAlter(tokens, create)) {
            return Optional.of(sql);
        }
        Token token = tokens.get(create);
        return Optional.of(sql.substring(0, token.end) + " OR ALTER" + sql.substring(token.end));
    }

    /**
     * Returns the canonical {@code CREATE} form of a module definition, as kept in the repository.
     * <p>
     * SQL Server stores a module created with {@code CREATE OR ALTER} as {@code CREATE} followed
     * by the leftover blanks ({@code "CREATE   PROCEDURE"}). That run of spaces is collapsed to
     * one, and a literal {@code OR ALTER} (should a server keep it) is removed. Anything else is
     * returned unchanged.
     */
    public static String toCanonicalCreate(String sql) {
        if (sql == null) {
            return null;
        }
        List<Token> tokens = tokenize(sql);
        int create = findModuleCreate(tokens);
        if (create < 0) {
            return sql;
        }
        Token createToken = tokens.get(create);
        if (isOrAlter(tokens, create)) {
            Token alterToken = tokens.get(create + 2);
            return sql.substring(0, createToken.end) + sql.substring(alterToken.end);
        }
        Token keyword = tokens.get(create + 1);
        String gap = sql.substring(createToken.end, keyword.start);
        if (gap.length() > 1 && gap.chars().allMatch(c -> c == ' ')) {
            return sql.substring(0, createToken.end) + " " + sql.substring(keyword.start);
        }
        return sql;
    }

    /**
     * Determines the kind of function a {@code CREATE FUNCTION} definition declares,
     * from what follows its first {@code RETURNS}.
     */
    public static Optional<FunctionKind> functionKind(String sql) {
        List<Token> tokens = tokenize(sql);
        for (int i = 0; i < tokens.size() - 1; i++) {
            if (tokens.get(i).is("RETURNS")) {
                Token next = tokens.get(i + 1);
                if (next.is("TABLE")) {
                    return Optional.of(FunctionKind.INLINE_TABLE);
                }
                if (next.text.startsWith("@") && i + 2 < tokens.size() && tokens.get(i + 2).is("TABLE")) {
                    return Optional.of(FunctionKind.MULTI_STATEMENT_TABLE);
                }
                return Optional.of(FunctionKind.SCALAR);
            }
        }
        return Optional.empty();
    }

    private static int findModuleCreate(List<Token> tokens) {
        for (int i = 0; i < tokens.size(); i++) {
            if (!tokens.get(i).is("CREATE")) {
                continue;
            }
            int next = isOrAlter(tokens, i) ? i + 3 : i + 1;
            if (next < tokens.size() && MODULE_KEYWORDS.contains(tokens.get(next).upper())) {
                return i;
            }
        }
        return -1;
    }

    private static boolean isOrAlter(List<Token> tokens, int create) {
        return create + 2 < tokens.size()
                && tokens.get(create + 1).is("OR")
                && tokens.get(create + 2).is("ALTER");
    }

    private record Token(String text, int start, int end) {
        boolean is(String keyword) {
            return text.equalsIgnoreCase(keyword);
        }

        String upper() {
            return text.toUpperCase(Locale.ROOT);
        }
    }

    /**
     * Splits SQL into bare words, skipping comments (nested block comments included),
     * string literals and bracket/double-quoted identifiers.
     */
    private static List<Token> tokenize(String sql) {
        List<Token> tokens = new ArrayList<>();
        int i = 0;
        int n = sql.length();
        while (i < n) {
            char c = sql.charAt(i);
            char next = i + 1 < n ? sql.charAt(i + 1) : '\0';
            if (c == '-' && next == '-') {
                int eol = sql.indexOf('\n', i);
                i = eol < 0 ? n : eol + 1;
            } else if (c == '/' && next == '*') {
                i = skipBlockComment(sql, i);
            } else if (c == '\'') {
                i = skipQuoted(sql, i, '\'');
            } else if (c == '[') {
                i = skipQuoted(sql, i, ']');
            } else if (c == '"') {
                i = skipQuoted(sql, i, '"');
            } else if (isWordStart(c)) {
                int start = i;
                while (i < n && isWordPart(sql.charAt(i))) {
                    i++;
                }
                tokens.add(new Token(sql.substring(start, i), start, i));
            } else {
                i++;
            }
        }
        return tokens;
    }

    private static int skipBlockComment(String sql, int i) {
        int depth = 0;
        int n = sql.length();
        while (i < n) {
            if (sql.startsWith("/*", i)) {
                depth++;
                i += 2;
            } else if (sql.startsWith("*/", i)) {
                depth--;
                i += 2;
                if (depth == 0) {
                    return i;
                }
            } else {
                i++;
            }
        }
        return n;
    }

    /** Skips a quoted run starting at {@code i}; a doubled closing quote is an escape. */
    private static int skipQuoted(String sql, int i, char close) {
        int n = sql.length();
        i++;
        while (i < n) {
            if (sql.charAt(i) == close) {
                if (i + 1 < n && sql.charAt(i + 1) == close) {
                    i += 2;
                    continue;
                }
                return i + 1;
            }
            i++;
        }
        return n;
    }

    private static boolean isWordStart(char c) {
        return Character.isLetter(c) || c == '_' || c == '@' || c == '#';
    }

    private static boolean isWordPart(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '@' || c == '#' || c == '$';
    }
}

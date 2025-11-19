package app.majid.adous.synchronizer.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Objects;

/**
 * Service for checking SQL statement equivalence.
 * Normalizes SQL statements by removing comments, whitespace, and case differences
 * to determine if two SQL statements are functionally equivalent.
 */
@Service
public class SqlEquivalenceCheckerService {

    private static final Logger logger = LoggerFactory.getLogger(SqlEquivalenceCheckerService.class);

    @Value("${spring.application.database.default-schema}")
    private String defaultSchema;

    /**
     * Checks if two SQL statements are semantically equivalent.
     *
     * @param sqlA First SQL statement
     * @param sqlB Second SQL statement
     * @return true if statements are equivalent after normalization
     */
    public boolean equals(String sqlA, String sqlB) {
        String normalizedA = normalizeSql(sqlA);
        String normalizedB = normalizeSql(sqlB);

        boolean result = Objects.equals(normalizedA, normalizedB);

        if (!result && logger.isTraceEnabled()) {
            logger.trace("SQL statements are not equivalent. A: {}, B: {}", normalizedA, normalizedB);
        }

        return result;
    }

    /**
     * Normalizes SQL by:
     * 1. Removing comments
     * 2. Converting to lowercase
     * 3. Normalizing whitespace
     * 4. Removing semicolons
     * 5. Extracting only CREATE statement
     * 6. Converting CREATE OR ALTER to CREATE
     * 7. Removing schema prefixes
     */
    private String normalizeSql(String sql) {
        if (sql == null) {
            return null;
        }

        String withoutComments = removeCommentsAndNormalizeBasics(sql);
        // Remove square brackets from identifiers: [ident] -> ident to make quoted/unquoted equal
        String unquotedIdentifiers = withoutComments.replaceAll("\\[(\\w+)]", "$1");
        String createStatement = extractCreateStatement(unquotedIdentifiers);
        String withoutAlter = replaceCreateOrAlterWithCreate(createStatement);
        return removeSchemaPrefixes(withoutAlter);
    }

    /**
     * Removes comments and performs basic normalization (case, whitespace, semicolons).
     */
    private String removeCommentsAndNormalizeBasics(String sql) {
        StringBuilder normalized = new StringBuilder();
        boolean inSingleLineComment = false;
        boolean inMultiLineComment = false;
        boolean inWhitespace = false;

        int length = sql.length();
        for (int i = 0; i < length; i++) {
            char current = sql.charAt(i);
            char next = (i < length - 1) ? sql.charAt(i + 1) : '\0';

            // Handle single-line comments
            if (!inMultiLineComment && current == '-' && next == '-') {
                inSingleLineComment = true;
            }

            if (inSingleLineComment && current == '\n') {
                inSingleLineComment = false;
                continue;
            }

            // Handle multi-line comments
            if (!inSingleLineComment && current == '/' && next == '*') {
                inMultiLineComment = true;
            }

            if (inMultiLineComment && i > 0 && current == '/' && sql.charAt(i - 1) == '*') {
                inMultiLineComment = false;
                continue;
            }

            // Skip if in any comment
            if (inSingleLineComment || inMultiLineComment) {
                continue;
            }

            // Normalize character
            char processed = Character.toLowerCase(current);

            // Skip semicolons
            if (processed == ';') {
                continue;
            }

            // Normalize whitespace
            if (Character.isWhitespace(processed)) {
                if (!inWhitespace) {
                    normalized.append(' ');
                    inWhitespace = true;
                }
            } else {
                normalized.append(processed);
                inWhitespace = false;
            }
        }

        return normalized.toString().trim();
    }

    /**
     * Extracts the CREATE statement from SQL that may contain multiple statements separated by GO.
     */
    private String extractCreateStatement(String sql) {
        String[] sqlParts = sql.split("(?i)\\bGO\\b\\s*");

        for (String part : sqlParts) {
            if (part.toLowerCase().contains("create")) {
                return part.trim();
            }
        }

        return "";
    }

    /**
     * Replaces "CREATE OR ALTER" with "CREATE" for equivalence checking.
     * This ensures that CREATE and CREATE OR ALTER are treated as equivalent.
     */
    private String replaceCreateOrAlterWithCreate(String sql) {
        final String createKeyword = "create";
        final String orAlterKeyword = "or alter";

        int createIndex = sql.indexOf(createKeyword);

        if (createIndex != -1) {
            int orAlterIndex = createIndex + createKeyword.length();

            if (sql.regionMatches(true, orAlterIndex + 1, orAlterKeyword, 0, orAlterKeyword.length())) {
                return sql.substring(0, createIndex) + createKeyword +
                       sql.substring(orAlterIndex + 1 + orAlterKeyword.length());
            }
        }

        return sql;
    }

    /**
     * Removes default schema prefixes from object names.
     * For example, "CREATE PROCEDURE dbo.MyProc" becomes "CREATE PROCEDURE MyProc"
     * Also removes default schema from table references like "FROM dbo.TableName"
     * This ensures that objects with and without explicit schema prefixes are treated as equivalent.
     */
    private String removeSchemaPrefixes(String sql) {
        // Remove schema from CREATE/ALTER object definitions
        String result = sql.replaceAll(
                "(create|alter)\\s+(procedure|function|view|trigger)\\s+(?:\\[?%s+\\]?\\.)?".formatted(defaultSchema),
                "$1 $2 "
        );

        // Remove default schema from table references (FROM, JOIN, etc.)
        // Pattern: dbo.tablename or [dbo].tablename or dbo.[tablename] or [dbo].[tablename]
        result = result.replaceAll(
                "\\b" + defaultSchema + "\\.",
                ""
        );

        return result;
    }
}

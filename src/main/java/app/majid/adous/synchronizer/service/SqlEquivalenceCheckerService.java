package app.majid.adous.synchronizer.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Service for checking SQL statement equivalence.
 * Normalizes SQL statements by removing comments, whitespace, and case differences
 * to determine if two SQL statements are functionally equivalent.
 */
@Service
public class SqlEquivalenceCheckerService {

    private static final Logger logger = LoggerFactory.getLogger(SqlEquivalenceCheckerService.class);

    private final SqlEquivalenceCheckerService self;

    @Value("${spring.application.database.default-schema}")
    private String defaultSchema;

    public SqlEquivalenceCheckerService(@Lazy SqlEquivalenceCheckerService self) {
        this.self = self;
    }

    /**
     * Checks if two SQL statements are semantically equivalent.
     *
     * @param sqlA First SQL statement
     * @param sqlB Second SQL statement
     * @return true if statements are equivalent after normalization
     */
    public boolean equals(String sqlA, String sqlB) {
        // Quick check for null and reference equality
        // Note: == is intentional here for fast path optimization
        //noinspection StringEquality
        if (sqlA == sqlB) {
            return true;
        }
        if (sqlA == null || sqlB == null) {
            return false;
        }

        // Quick check for exact string equality before normalization
        if (sqlA.equals(sqlB)) {
            return true;
        }

        String normalizedA = self.normalizeSql(sqlA);
        String normalizedB = self.normalizeSql(sqlB);

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
     * 5. Extracting CREATE statements
     * 6. Converting CREATE OR ALTER to CREATE
     * 7. Removing schema prefixes
     * 8. Normalizing parentheses around numeric literals: ((0)) -> (0)
     * 9. Normalizing operators spacing: >= vs  >=
     * 10. Normalizing DEFAULT value formatting
     */

    @Cacheable("sqlNormalizationCache")
    public String normalizeSql(String sql) {
        String withoutComments = removeCommentsAndNormalizeBasics(sql);
        String unquotedIdentifiers = withoutComments.replaceAll("\\[(\\w+)]", "$1");
        String createStatement = extractCreateStatement(unquotedIdentifiers);
        String withoutAlter = replaceCreateOrAlterWithCreate(createStatement);
        String withoutSchema = removeSchemaPrefixes(withoutAlter);
        String normalizedDefaults = normalizeDefaultValues(withoutSchema);
        String normalizedParens = normalizeParentheses(normalizedDefaults);
        String normalizedOps = normalizeOperators(normalizedParens);
        String normalizedIndexKeywords = normalizeIndexKeywords(normalizedOps);
        String normalizedDataTypes = normalizeDataTypes(normalizedIndexKeywords);
        String sortedColumns = sortTableColumns(normalizedDataTypes);
        String sortedIndexes = sortIndexStatements(sortedColumns);

        return sortedIndexes;
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
        StringBuilder result = new StringBuilder();

        for (String part : sqlParts) {
            if (part.toLowerCase().contains("create")) {
                if (!result.isEmpty()) {
                    result.append(" ");
                }
                result.append(part.trim());
            }
        }

        return result.toString();
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

    /**
     * Normalizes DEFAULT value formatting.
     * Converts DEFAULT ((0)) to DEFAULT 0 and DEFAULT (0) to DEFAULT 0
     * Converts DEFAULT (getdate()) to DEFAULT getdate()
     * This handles differences in how SQL Server formats DEFAULT constraints.
     */
    private String normalizeDefaultValues(String sql) {
        // Remove all parentheses around numeric DEFAULT values
        // First handle double parentheses: DEFAULT ((0)) -> DEFAULT 0
        String result = sql.replaceAll("(?i)(\\bdefault\\s+)\\(\\((\\d+)\\)\\)", "$1$2");

        // Then handle single parentheses: DEFAULT (0) -> DEFAULT 0
        result = result.replaceAll("(?i)(\\bdefault\\s+)\\((\\d+)\\)", "$1$2");

        // Remove parentheses around function calls: DEFAULT (getdate()) -> DEFAULT getdate()
        result = result.replaceAll("(?i)(\\bdefault\\s+)\\((getdate|sysdatetime|current_timestamp)\\(\\)\\)", "$1$2()");

        return result;
    }

    /**
     * Normalizes parentheses in WHERE clauses and CHECK constraints.
     * Converts multiple nested parentheses to single ones where appropriate.
     * Removes parentheses around numeric values in comparisons.
     * Example: >= (0) -> >= 0
     */
    private String normalizeParentheses(String sql) {
        String result = sql.replaceAll("\\((\\w+)\\)\\s*([=><]+)\\s*\\((\\d+)\\)", "$1 $2 $3");

        result = result.replaceAll("([=><]+)\\s*\\((\\d+)\\)", "$1 $2");

        result = result.replaceAll("where\\s+\\(\\(([^()]*[=><]+[^()]*)\\)\\)", "where ($1)");

        result = result.replaceAll("where\\s+([\\w]+)\\s*=\\s*\\(([0-9]+)\\)", "where $1 = $2");

        // Remove outer parentheses from WHERE conditions: WHERE ([condition]) -> WHERE condition
        result = result.replaceAll("where\\s+\\(([^()]+)\\)", "where $1");

        return result;
    }

    /**
     * Normalizes operator spacing.
     * Converts >= to > = and vice versa to have consistent spacing around operators.
     * Also handles other comparison operators.
     */
    private String normalizeOperators(String sql) {
        // Normalize spacing around operators: >= becomes >=, <=  becomes <=, etc.
        String result = sql.replaceAll("\\s*([><=!]+)\\s*", " $1 ");

        // Remove duplicate spaces
        result = result.replaceAll("\\s+", " ");

        return result;
    }

    private String normalizeIndexKeywords(String sql) {
        return sql.replaceAll("(?i)create\\s+(clustered|nonclustered|unique|unique\\s+clustered|unique\\s+nonclustered)\\s+index", "create index");
    }

    /**
     * Normalizes data type declarations by removing spaces inside parentheses.
     * Examples:
     * - DECIMAL(5, 2) -> DECIMAL(5,2)
     * - VARCHAR(200) -> VARCHAR(200) (no change, already correct)
     * - INT IDENTITY(1, 1) -> INT IDENTITY(1,1)
     */
    private String normalizeDataTypes(String sql) {
        // Remove spaces before opening parenthesis: VARCHAR (200) -> VARCHAR(200)
        String result = sql.replaceAll("(\\w)\\s+\\(", "$1(");

        // Remove spaces inside parentheses for data types
        // Matches patterns like (5, 2) and converts to (5,2)
        result = result.replaceAll("\\(\\s*(\\d+)\\s*,\\s*(\\d+)\\s*\\)", "($1,$2)");

        // Also handle single parameter types: (200) stays (200)
        result = result.replaceAll("\\(\\s*(\\d+)\\s*\\)", "($1)");

        return result;
    }

    /**
     * Sorts columns in CREATE TABLE statements alphabetically to ignore column order.
     * This ensures that tables with the same columns in different orders are considered equivalent.
     * For example:
     * CREATE TABLE t (c int, b int, a int)
     * becomes:
     * CREATE TABLE t (a int, b int, c int)
     *
     * @param sql Normalized SQL statement
     * @return SQL with columns sorted alphabetically
     */
    private String sortTableColumns(String sql) {
        if (!sql.contains("create table")) {
            return sql;
        }

        try {
            int tableStart = sql.indexOf("create table");
            int openParen = sql.indexOf('(', tableStart);
            if (openParen == -1) return sql;

            int constraintStart = sql.indexOf("constraint", openParen);

            int tableCloseParen = findMatchingCloseParen(sql, openParen);
            if (tableCloseParen == -1) return sql;

            String beforeColumns = sql.substring(0, openParen + 1).trim();
            String afterTable;
            String columnsSection;

            if (constraintStart != -1 && constraintStart < tableCloseParen) {
                columnsSection = sql.substring(openParen + 1, constraintStart).trim();
                afterTable = sql.substring(constraintStart, tableCloseParen).trim();
            } else {
                columnsSection = sql.substring(openParen + 1, tableCloseParen).trim();
                afterTable = "";
            }

            java.util.List<String> columns = splitColumns(columnsSection);

            columns.sort((a, b) -> {
                String nameA = extractColumnName(a);
                String nameB = extractColumnName(b);
                return nameA.compareTo(nameB);
            });

            String sortedColumns = String.join(", ", columns);

            String result = beforeColumns + " " + sortedColumns;
            if (!afterTable.isEmpty()) {
                result += ", " + afterTable;
            }
            result += sql.substring(tableCloseParen);

            return result;

        } catch (Exception e) {
            logger.debug("Failed to sort table columns, returning original: {}", e.getMessage());
            return sql;
        }
    }

    private int findMatchingCloseParen(String sql, int openParenIndex) {
        int depth = 0;
        for (int i = openParenIndex; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    /**
     * Splits column definitions by comma, handling nested parentheses.
     */
    private java.util.List<String> splitColumns(String columnsSection) {
        java.util.List<String> columns = new java.util.ArrayList<>();
        int parenDepth = 0;
        int lastSplit = 0;

        for (int i = 0; i < columnsSection.length(); i++) {
            char c = columnsSection.charAt(i);
            if (c == '(') {
                parenDepth++;
            } else if (c == ')') {
                parenDepth--;
            } else if (c == ',' && parenDepth == 0) {
                String column = columnsSection.substring(lastSplit, i).trim();
                if (!column.isEmpty()) {
                    columns.add(column);
                }
                lastSplit = i + 1;
            }
        }

        // Add last column
        String lastColumn = columnsSection.substring(lastSplit).trim();
        if (!lastColumn.isEmpty() && !lastColumn.startsWith("constraint")) {
            columns.add(lastColumn);
        }

        return columns;
    }

    /**
     * Extracts the column name (first word) from a column definition.
     * For example: "productid int identity(1,1) not null" -> "productid"
     */
    private String extractColumnName(String columnDef) {
        String trimmed = columnDef.trim();
        int spaceIndex = trimmed.indexOf(' ');
        if (spaceIndex == -1) {
            return trimmed;
        }
        return trimmed.substring(0, spaceIndex);
    }

    /**
     * Sorts CREATE INDEX statements alphabetically by index name to ignore index order.
     * This ensures that tables with the same indexes in different orders are considered equivalent.
     * For example:
     * CREATE INDEX idx_b ON t(b); CREATE INDEX idx_a ON t(a);
     * becomes:
     * CREATE INDEX idx_a ON t(a); CREATE INDEX idx_b ON t(b);
     *
     * @param sql Normalized SQL statement (may contain multiple statements)
     * @return SQL with index statements sorted alphabetically
     */
    private String sortIndexStatements(String sql) {
        if (!sql.contains("create index")) {
            return sql; // No indexes, return as-is
        }

        try {
            // Split SQL into parts: CREATE TABLE part + CREATE INDEX parts
            String[] parts = sql.split("(?=create index)|(?=create unique index)");

            if (parts.length <= 1) {
                return sql; // Only one part or no indexes
            }

            // First part is usually the CREATE TABLE statement
            StringBuilder result = new StringBuilder(parts[0].trim());

            // Collect all CREATE INDEX statements
            List<String> indexStatements = new ArrayList<>();
            for (int i = 1; i < parts.length; i++) {
                String part = parts[i].trim();
                if (part.startsWith("create index") || part.startsWith("create unique index")) {
                    indexStatements.add(part);
                }
            }

            // Sort index statements by index name
            indexStatements.sort((a, b) -> {
                String nameA = extractIndexName(a);
                String nameB = extractIndexName(b);
                return nameA.compareTo(nameB);
            });

            // Rebuild SQL with sorted indexes
            for (String indexStmt : indexStatements) {
                result.append(" ").append(indexStmt);
            }

            return result.toString().trim();

        } catch (Exception e) {
            // If parsing fails, return original SQL
            logger.debug("Failed to sort index statements, returning original: {}", e.getMessage());
            return sql;
        }
    }

    /**
     * Extracts the index name from a CREATE INDEX statement.
     * For example: "create index idx_products_category on products(category)" -> "idx_products_category"
     */
    private String extractIndexName(String indexStatement) {
        // Pattern: CREATE [UNIQUE] INDEX <name> ON ...
        String normalized = indexStatement.toLowerCase().trim();

        // Remove "create unique index" or "create index"
        String afterCreate = normalized.replace("create unique index", "").replace("create index", "").trim();

        // Index name is the first word
        int spaceIndex = afterCreate.indexOf(' ');
        if (spaceIndex == -1) {
            return afterCreate;
        }
        return afterCreate.substring(0, spaceIndex);
    }
}

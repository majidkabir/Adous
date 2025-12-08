package app.majid.adous.db.service;

import app.majid.adous.synchronizer.model.DbObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static app.majid.adous.db.service.SqlServerTypeFormatter.formatDataType;

/**
 * Generates ALTER TABLE scripts by comparing Git CREATE TABLE definition
 * with current database table structure.
 * This preserves existing data while aligning table structure with Git.
 */
@Service
public class TableAlterScriptGenerator {

    private static final Logger logger = LoggerFactory.getLogger(TableAlterScriptGenerator.class);

    private final JdbcTemplate jdbcTemplate;

    public TableAlterScriptGenerator(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Generates ALTER TABLE script to sync database with Git definition.
     * If table doesn't exist in database, returns the CREATE TABLE script as-is.
     *
     * @param tableObject DbObject containing schema, name, and CREATE TABLE definition from Git
     * @return SQL script (CREATE or ALTER statements) to apply to database
     */
    public String generateAlterScript(DbObject tableObject) {
        logger.debug("Generating alter script for table {}.{}", tableObject.schema(), tableObject.name());

        // Check if table exists in database
        if (!tableExists(tableObject.schema(), tableObject.name())) {
            logger.debug("Table {}.{} doesn't exist, using CREATE TABLE script",
                    tableObject.schema(), tableObject.name());
            return tableObject.definition();
        }

        // Parse Git definition to extract table structure
        TableDefinition gitDefinition = parseCreateTableDefinition(tableObject.definition());

        // Get current database table structure
        TableDefinition dbDefinition = getCurrentTableDefinition(tableObject.schema(), tableObject.name());

        // Generate ALTER statements
        List<String> alterStatements = generateAlterStatements(tableObject.schema(), tableObject.name(),
                dbDefinition, gitDefinition, tableObject.definition());

        if (alterStatements.isEmpty()) {
            logger.debug("No changes needed for table {}.{}", tableObject.schema(), tableObject.name());
            return ""; // No changes needed
        }

        StringBuilder script = new StringBuilder();
        alterStatements.forEach(stmt -> script.append(stmt).append("\nGO\n"));

        // Extract and append CREATE INDEX statements from Git definition
        // These will recreate any indexes that were dropped during column alterations
        List<String> indexStatements = extractIndexStatements(tableObject.definition());
        if (!indexStatements.isEmpty()) {
            logger.debug("Adding {} index recreation statements for table {}.{}",
                    indexStatements.size(), tableObject.schema(), tableObject.name());
            indexStatements.forEach(stmt -> script.append(stmt).append("\nGO\n"));
        }

        logger.debug("Generated {} ALTER statements and {} index statements for table {}.{}",
                alterStatements.size(), indexStatements.size(), tableObject.schema(), tableObject.name());

        return script.toString();
    }

    /**
     * Checks if a table exists in the database.
     */
    private boolean tableExists(String schema, String name) {
        String query = """
                SELECT COUNT(*)
                FROM sys.tables t
                JOIN sys.schemas s ON t.schema_id = s.schema_id
                WHERE s.name = ? AND t.name = ?
                """;

        Integer count = jdbcTemplate.queryForObject(query, Integer.class, schema, name);
        return count != null && count > 0;
    }

    /**
     * Parses CREATE TABLE definition from Git to extract table structure.
     */
    private TableDefinition parseCreateTableDefinition(String createTableSql) {
        TableDefinition def = new TableDefinition();

        // Remove SQL comments before parsing to avoid interference
        String cleanedSql = removeSqlComments(createTableSql);

        // Extract columns from CREATE TABLE statement

        // Find the columns section between the first ( and CONSTRAINT or closing )
        int openParen = cleanedSql.indexOf('(');
        if (openParen == -1) return def;

        int constraintStart = cleanedSql.indexOf("CONSTRAINT", openParen);
        int closeParen = cleanedSql.lastIndexOf(')');

        String columnsSection;
        if (constraintStart != -1) {
            columnsSection = cleanedSql.substring(openParen + 1, constraintStart).trim();
        } else {
            columnsSection = cleanedSql.substring(openParen + 1, closeParen).trim();
        }

        // Split by comma and parse each column
        // Use smart comma splitting that handles commas inside parentheses
        String[] columnLines = splitColumnsSmartly(columnsSection);
        for (String columnLine : columnLines) {
            columnLine = columnLine.trim();
            if (columnLine.isEmpty() || columnLine.toUpperCase().contains("CONSTRAINT")) {
                continue;
            }

            // Parse individual column line
            ColumnDefinition column = parseColumnDefinition(columnLine);
            if (column != null) {
                def.columns.put(column.name, column);
            }
        }

        // Extract PRIMARY KEY
        Pattern pkPattern = Pattern.compile(
                "CONSTRAINT\\s+\\[([^\\]]+)\\]\\s+PRIMARY\\s+KEY\\s*(?:CLUSTERED|NONCLUSTERED)?\\s*\\(([^)]+)\\)",
                Pattern.CASE_INSENSITIVE | Pattern.DOTALL
        );
        Matcher pkMatcher = pkPattern.matcher(cleanedSql);
        if (pkMatcher.find()) {
            def.primaryKeyName = pkMatcher.group(1).toLowerCase();
            String[] columns = pkMatcher.group(2).split(",");
            for (String col : columns) {
                def.primaryKeyColumns.add(col.replaceAll("[\\[\\]\\s]", "").toLowerCase());
            }
        }

        return def;
    }

    /**
     * Splits column definitions by commas, but ignores commas inside parentheses.
     * This prevents splitting DECIMAL(10,2) at the comma inside the parentheses.
     *
     * @param columnsSection The columns section from CREATE TABLE
     * @return Array of individual column definition strings
     */
    private String[] splitColumnsSmartly(String columnsSection) {
        List<String> columns = new ArrayList<>();
        StringBuilder currentColumn = new StringBuilder();
        int parenDepth = 0;

        for (int i = 0; i < columnsSection.length(); i++) {
            char c = columnsSection.charAt(i);

            if (c == '(') {
                parenDepth++;
                currentColumn.append(c);
            } else if (c == ')') {
                parenDepth--;
                currentColumn.append(c);
            } else if (c == ',' && parenDepth == 0) {
                // Only split on commas when we're not inside parentheses
                columns.add(currentColumn.toString().trim());
                currentColumn = new StringBuilder();
            } else {
                currentColumn.append(c);
            }
        }

        // Add the last column
        if (!currentColumn.isEmpty()) {
            columns.add(currentColumn.toString().trim());
        }

        return columns.toArray(new String[0]);
    }

    /**
     * Removes SQL line comments (-- comments) from the SQL text.
     * This prevents comments from interfering with column name parsing.
     *
     * @param sql The SQL text that may contain comments
     * @return SQL text with line comments removed
     */
    private String removeSqlComments(String sql) {
        StringBuilder result = new StringBuilder();
        String[] lines = sql.split("\n");

        for (String line : lines) {
            // Find the position of -- comment marker
            int commentPos = line.indexOf("--");
            if (commentPos != -1) {
                // Keep only the part before the comment
                String beforeComment = line.substring(0, commentPos).trim();
                if (!beforeComment.isEmpty()) {
                    result.append(beforeComment).append("\n");
                }
            } else {
                // No comment, keep the entire line
                result.append(line).append("\n");
            }
        }

        return result.toString();
    }

    /**
     * Parses a single column definition line.
     * Example: "[product_id] INT IDENTITY(1,1) NOT NULL"
     * Example: "[discount_percent] DECIMAL(5,2) NULL DEFAULT 0"
     */
    private ColumnDefinition parseColumnDefinition(String columnLine) {
        try {
            columnLine = columnLine.trim();

            // Extract column name first
            if (!columnLine.startsWith("[")) {
                return null;
            }

            int nameEnd = columnLine.indexOf("]");
            if (nameEnd == -1) {
                return null;
            }

            String originalColumnName = columnLine.substring(1, nameEnd);
            String columnName = originalColumnName.toLowerCase();
            String remainder = columnLine.substring(nameEnd + 1).trim();

            ColumnDefinition column = new ColumnDefinition();
            column.name = columnName;
            column.originalName = originalColumnName;

            // Parse data type (everything up to IDENTITY, NULL, or DEFAULT)
            // Use a more robust approach that handles parentheses correctly
            String dataType = extractDataType(remainder);
            column.dataType = normalizeDataType(dataType);

            // Parse the rest of the attributes after the data type
            String attributesSection = remainder.substring(dataType.length()).trim();
            parseColumnAttributes(column, attributesSection);


            return column;
        } catch (Exception e) {
            logger.debug("Failed to parse column definition: {}", columnLine, e);
            return null;
        }
    }

    /**
     * Extracts the data type from a column definition remainder.
     * Handles parentheses correctly by tracking open/close pairs.
     *
     * @param remainder The column definition after the column name
     * @return The data type portion (e.g., "DECIMAL(10,2)")
     */
    private String extractDataType(String remainder) {
        // Use a simpler regex-based approach that's more reliable
        // Pattern: DataType optionally followed by parentheses with content
        Pattern dataTypePattern = Pattern.compile(
            "^([A-Z_]+)(?:\\s*\\([^)]+\\))?",
            Pattern.CASE_INSENSITIVE
        );

        Matcher matcher = dataTypePattern.matcher(remainder.trim());
        if (matcher.find()) {
            return matcher.group().trim();
        }

        // Fallback: extract until we hit a keyword
        String[] parts = remainder.trim().split("\\s+");
        StringBuilder dataType = new StringBuilder();

        for (String part : parts) {
            String upperPart = part.toUpperCase();
            if (upperPart.equals("IDENTITY") ||
                upperPart.equals("NOT") ||
                upperPart.equals("NULL") ||
                upperPart.equals("DEFAULT")) {
                break;
            }

            if (!dataType.isEmpty()) {
                dataType.append(" ");
            }
            dataType.append(part);

            // If this part contains a closing parenthesis, the data type is complete
            if (part.contains(")") && !part.contains("(")) {
                break;
            }
        }

        return dataType.toString().trim();
    }

    /**
     * Parses column attributes (IDENTITY, NULL, DEFAULT) from the attributes section.
     *
     * @param column The column definition to populate
     * @param attributesSection The attributes part of the column definition
     */
    private void parseColumnAttributes(ColumnDefinition column, String attributesSection) {
        if (attributesSection.isEmpty()) {
            return;
        }

        String[] parts = attributesSection.split("\\s+");
        boolean foundNull = false;

        for (int partIndex = 0; partIndex < parts.length; partIndex++) {
            String part = parts[partIndex];
            String upperPart = part.toUpperCase();

            if (upperPart.equals("IDENTITY")) {
                column.isIdentity = true;
                // Look for IDENTITY(1,1) pattern
                if (partIndex + 1 < parts.length && parts[partIndex + 1].startsWith("(")) {
                    String identitySpec = parts[partIndex + 1];
                    if (identitySpec.endsWith(")")) {
                        identitySpec = identitySpec.substring(1, identitySpec.length() - 1);
                        String[] identityParts = identitySpec.split(",");
                        if (identityParts.length == 2) {
                            column.identitySeed = Integer.parseInt(identityParts[0].trim());
                            column.identityIncrement = Integer.parseInt(identityParts[1].trim());
                        }
                    }
                    partIndex++; // Skip the (1,1) part
                } else {
                    column.identitySeed = 1;
                    column.identityIncrement = 1;
                }
            } else if (upperPart.equals("NOT")) {
                if (partIndex + 1 < parts.length && parts[partIndex + 1].equalsIgnoreCase("NULL")) {
                    column.isNullable = false;
                    foundNull = true;
                    partIndex++; // Skip the NULL part
                }
            } else if (upperPart.equals("NULL") && !foundNull) {
                column.isNullable = true;
                foundNull = true;
            } else if (upperPart.equals("DEFAULT")) {
                // Everything after DEFAULT is the default value
                StringBuilder defaultBuilder = new StringBuilder();
                partIndex++;
                while (partIndex < parts.length) {
                    if (!defaultBuilder.isEmpty()) {
                        defaultBuilder.append(" ");
                    }
                    defaultBuilder.append(parts[partIndex]);
                    partIndex++;
                }
                column.defaultValue = defaultBuilder.toString();
                break;
            }
        }
    }

    /**
     * Gets current table definition from database.
     */
    private TableDefinition getCurrentTableDefinition(String schema, String name) {
        TableDefinition def = new TableDefinition();

        // Get columns
        String columnQuery = """
                SELECT
                    c.column_id,
                    c.name AS column_name,
                    TYPE_NAME(c.user_type_id) AS data_type,
                    c.max_length,
                    c.precision,
                    c.scale,
                    c.is_nullable,
                    c.is_identity,
                    ISNULL(ic.seed_value, 0) AS identity_seed,
                    ISNULL(ic.increment_value, 0) AS identity_increment
                FROM sys.columns c
                LEFT JOIN sys.identity_columns ic ON c.object_id = ic.object_id AND c.column_id = ic.column_id
                JOIN sys.tables t ON c.object_id = t.object_id
                JOIN sys.schemas s ON t.schema_id = s.schema_id
                WHERE s.name = ? AND t.name = ?
                ORDER BY c.column_id
                """;

        jdbcTemplate.query(columnQuery, rs -> {
            ColumnDefinition column = new ColumnDefinition();
            String originalName = rs.getString("column_name");
            column.name = originalName.toLowerCase();  // lowercase for comparison
            column.originalName = originalName;  // original case from database
            column.dataType = formatDataType(
                    rs.getString("data_type"),
                    rs.getInt("max_length"),
                    rs.getInt("precision"),
                    rs.getInt("scale")
            );
            column.isNullable = rs.getBoolean("is_nullable");
            column.isIdentity = rs.getBoolean("is_identity");
            column.identitySeed = rs.getInt("identity_seed");
            column.identityIncrement = rs.getInt("identity_increment");
            column.columnId = rs.getInt("column_id");

            def.columns.put(column.name, column);
        }, schema, name);

        // Get primary key
        String pkQuery = """
                SELECT
                    kc.name AS constraint_name,
                    c.name AS column_name
                FROM sys.key_constraints kc
                JOIN sys.index_columns ic ON kc.parent_object_id = ic.object_id
                    AND kc.unique_index_id = ic.index_id
                JOIN sys.columns c ON ic.object_id = c.object_id
                    AND ic.column_id = c.column_id
                JOIN sys.tables t ON kc.parent_object_id = t.object_id
                JOIN sys.schemas s ON t.schema_id = s.schema_id
                WHERE s.name = ? AND t.name = ? AND kc.type = 'PK'
                ORDER BY ic.key_ordinal
                """;

        jdbcTemplate.query(pkQuery, rs -> {
            if (def.primaryKeyName == null) {
                def.primaryKeyName = rs.getString("constraint_name").toLowerCase();
            }
            def.primaryKeyColumns.add(rs.getString("column_name").toLowerCase());
        }, schema, name);

        return def;
    }

    /**
     * Generates ALTER statements by comparing database and Git definitions.
     */
    private List<String> generateAlterStatements(String schema, String name,
                                                  TableDefinition dbDef, TableDefinition gitDef, String gitTableDefinition) {
        List<String> statements = new ArrayList<>();

        // Drop primary key if it exists and needs modification
        if (dbDef.primaryKeyName != null &&
                !dbDef.primaryKeyColumns.equals(gitDef.primaryKeyColumns)) {
            statements.add(String.format(
                    "ALTER TABLE [%s].[%s] DROP CONSTRAINT [%s];",
                    schema, name, dbDef.primaryKeyName
            ));
        }

        // Drop columns that exist in DB but not in Git
        for (String columnName : dbDef.columns.keySet()) {
            if (!gitDef.columns.containsKey(columnName)) {
                // Before dropping column, drop all constraints that depend on it
                ColumnDefinition dbColumn = dbDef.columns.get(columnName);
                String originalColumnName = dbColumn.originalName;

                List<String> dependentConstraints = getConstraintsDependentOnColumn(schema, name, columnName);
                for (String constraintName : dependentConstraints) {
                    statements.add(String.format(
                            "ALTER TABLE [%s].[%s] DROP CONSTRAINT [%s];",
                            schema, name, constraintName
                    ));
                    logger.debug("Dropping constraint [{}] before dropping column [{}]", constraintName, originalColumnName);
                }

                statements.add(String.format(
                        "ALTER TABLE [%s].[%s] DROP COLUMN [%s];",
                        schema, name, originalColumnName
                ));
            }
        }

        // Add new columns or alter existing ones
        for (Map.Entry<String, ColumnDefinition> entry : gitDef.columns.entrySet()) {
            String columnName = entry.getKey();
            ColumnDefinition gitColumn = entry.getValue();
            ColumnDefinition dbColumn = dbDef.columns.get(columnName);

            if (dbColumn == null) {
                // Add new column - use original case from Git
                String nullability = gitColumn.isNullable ? "NULL" : "NOT NULL";
                String identity = gitColumn.isIdentity ? " IDENTITY(1,1)" : "";
                String defaultClause = gitColumn.defaultValue != null ? " DEFAULT " + gitColumn.defaultValue : "";

                statements.add(String.format(
                        "ALTER TABLE [%s].[%s] ADD [%s] %s%s %s%s;",
                        schema, name, gitColumn.originalName, gitColumn.dataType, identity, nullability, defaultClause
                ));
            } else {
                // Check if column needs alteration
                if (!dbColumn.dataType.equalsIgnoreCase(gitColumn.dataType) ||
                        dbColumn.isNullable != gitColumn.isNullable) {

                    // Cannot alter identity columns easily, skip those
                    if (!dbColumn.isIdentity && !gitColumn.isIdentity) {
                        // Before altering column, drop indexes that depend on it
                        List<String> dependentIndexes = getIndexesDependentOnColumn(schema, name, columnName);
                        for (String indexName : dependentIndexes) {
                            statements.add(String.format(
                                    "DROP INDEX IF EXISTS [%s] ON [%s].[%s];",
                                    indexName, schema, name
                            ));
                            logger.debug("Dropping index [{}] before altering column [{}]", indexName, gitColumn.originalName);
                        }

                        String nullability = gitColumn.isNullable ? "NULL" : "NOT NULL";
                        // Use original case from Git definition
                        statements.add(String.format(
                                "ALTER TABLE [%s].[%s] ALTER COLUMN [%s] %s %s;",
                                schema, name, gitColumn.originalName, gitColumn.dataType, nullability
                        ));

                        // Note: Indexes will be recreated from Git definition at the end
                        // The Git definition contains the CREATE INDEX statements which will be
                        // applied after all column alterations are complete
                    } else if (dbColumn.isIdentity != gitColumn.isIdentity) {
                        logger.warn("Cannot alter identity property for column {}.{}.{} - manual intervention required",
                                schema, name, columnName);
                    }
                }
            }
        }

        // Add primary key if needed
        if (!gitDef.primaryKeyColumns.isEmpty() &&
                !dbDef.primaryKeyColumns.equals(gitDef.primaryKeyColumns)) {
            String pkName = gitDef.primaryKeyName != null ? gitDef.primaryKeyName :
                    String.format("PK_%s_%s", schema, name);
            String columns = gitDef.primaryKeyColumns.stream()
                    .map(col -> "[" + col + "]")
                    .reduce((a, b) -> a + ", " + b)
                    .orElse("");

            statements.add(String.format(
                    "ALTER TABLE [%s].[%s] ADD CONSTRAINT [%s] PRIMARY KEY (%s);",
                    schema, name, pkName, columns
            ));
        }

        // Extract and add CHECK constraints from Git that don't exist in database
        List<String> gitCheckConstraints = extractCheckConstraints(gitTableDefinition);
        for (String checkConstraint : gitCheckConstraints) {
            // Check if this constraint already exists in the database
            if (!checkConstraintExistsInDatabase(checkConstraint)) {
                statements.add(String.format(
                        "ALTER TABLE [%s].[%s] ADD %s;",
                        schema, name, checkConstraint
                ));
                logger.debug("Adding CHECK constraint: {}", checkConstraint);
            }
        }

        return statements;
    }

    /**
     * Normalizes data type string for comparison.
     */
    private String normalizeDataType(String dataType) {
        if (dataType == null || dataType.trim().isEmpty()) {
            return dataType;
        }

        // Convert to lowercase and normalize basic spacing
        String normalized = dataType.trim().toLowerCase().replaceAll("\\s+", " ");

        // Fix parentheses spacing: "decimal( 10 , 2 )" -> "decimal(10,2)"
        normalized = normalized.replaceAll("\\s*\\(\\s*", "(");  // Remove spaces around opening paren
        normalized = normalized.replaceAll("\\s*\\)\\s*", ")");  // Remove spaces around closing paren
        normalized = normalized.replaceAll("\\s*,\\s*", ",");    // Remove spaces around commas

        return normalized;
    }

    /**
     * Gets all constraints (CHECK, DEFAULT, FOREIGN KEY) that depend on a specific column.
     * These must be dropped before the column can be dropped.
     *
     * @param schema Schema name
     * @param tableName Table name
     * @param columnName Column name
     * @return List of constraint names that depend on the column
     */
    private List<String> getConstraintsDependentOnColumn(String schema, String tableName, String columnName) {
        List<String> constraints = new ArrayList<>();

        // Query for CHECK constraints on this column
        String checkConstraintQuery = """
                SELECT cc.name AS constraint_name, cc.definition
                FROM sys.check_constraints cc
                JOIN sys.tables t ON cc.parent_object_id = t.object_id
                JOIN sys.schemas s ON t.schema_id = s.schema_id
                WHERE s.name = ? AND t.name = ?
                """;

        jdbcTemplate.query(checkConstraintQuery, rs -> {
            String constraintName = rs.getString("constraint_name");
            String definition = rs.getString("definition");

            // Check if column is referenced in the constraint definition
            if (definition != null) {
                String lowerDef = definition.toLowerCase();
                String lowerCol = columnName.toLowerCase();

                // Check for [columnName] pattern or columnName as a word
                if (lowerDef.contains("[" + lowerCol + "]") ||
                    lowerDef.matches(".*\\b" + Pattern.quote(lowerCol) + "\\b.*")) {
                    constraints.add(constraintName);
                    logger.debug("Found CHECK constraint [{}] on column [{}]: {}",
                        constraintName, columnName, definition);
                }
            }
        }, schema, tableName);

        // Query for DEFAULT constraints on this column
        String defaultConstraintQuery = """
                SELECT dc.name AS constraint_name
                FROM sys.default_constraints dc
                JOIN sys.columns c ON dc.parent_object_id = c.object_id
                    AND dc.parent_column_id = c.column_id
                JOIN sys.tables t ON dc.parent_object_id = t.object_id
                JOIN sys.schemas s ON t.schema_id = s.schema_id
                WHERE s.name = ? AND t.name = ? AND c.name = ?
                """;

        jdbcTemplate.query(defaultConstraintQuery, rs -> {
            constraints.add(rs.getString("constraint_name"));
        }, schema, tableName, columnName);

        // Query for FOREIGN KEY constraints where this column is part of the FK
        String foreignKeyConstraintQuery = """
                SELECT fk.name AS constraint_name
                FROM sys.foreign_keys fk
                JOIN sys.foreign_key_columns fkc ON fk.object_id = fkc.constraint_object_id
                JOIN sys.columns c ON fkc.parent_object_id = c.object_id
                    AND fkc.parent_column_id = c.column_id
                JOIN sys.tables t ON fk.parent_object_id = t.object_id
                JOIN sys.schemas s ON t.schema_id = s.schema_id
                WHERE s.name = ? AND t.name = ? AND c.name = ?
                """;

        jdbcTemplate.query(foreignKeyConstraintQuery, rs -> {
            constraints.add(rs.getString("constraint_name"));
        }, schema, tableName, columnName);

        if (!constraints.isEmpty()) {
            logger.debug("Found {} constraints dependent on column [{}].[{}].[{}]: {}",
                    constraints.size(), schema, tableName, columnName, constraints);
        }

        return constraints;
    }

    /**
     * Extracts CREATE INDEX statements from the table definition.
     * These appear after the CREATE TABLE statement in the Git definition.
     * Prepends DROP INDEX IF EXISTS for idempotency.
     *
     * @param createTableSql Full CREATE TABLE definition from Git
     * @return List of CREATE INDEX statements (with DROP statements prepended)
     */
    private List<String> extractIndexStatements(String createTableSql) {
        List<String> indexStatements = new ArrayList<>();

        // Pattern to match CREATE INDEX statements with complete capture
        // Matches: CREATE [UNIQUE] [CLUSTERED|NONCLUSTERED] INDEX [name] ON ... WHERE ... ;
        // We capture the entire statement to preserve exact formatting and WHERE clauses
        Pattern indexPattern = Pattern.compile(
                "(CREATE\\s+(?:UNIQUE\\s+)?(?:(?:CLUSTERED|NONCLUSTERED)\\s+)?INDEX\\s+\\[([^\\]]+)\\][^;]*;)",
                Pattern.CASE_INSENSITIVE | Pattern.DOTALL
        );

        Matcher matcher = indexPattern.matcher(createTableSql);
        while (matcher.find()) {
            String fullIndexStatement = matcher.group(1).trim();
            String indexName = matcher.group(2);

            // Extract the ON clause to build proper DROP statement
            // Pattern: ON [schema].[table] or ON schema.table
            Pattern onPattern = Pattern.compile(
                    "ON\\s+(\\[[^\\]]+\\]\\.\\[[^\\]]+\\]|[^\\s\\(]+\\.[^\\s\\(]+)",
                    Pattern.CASE_INSENSITIVE
            );
            Matcher onMatcher = onPattern.matcher(fullIndexStatement);

            String dropStatement = "";
            if (onMatcher.find()) {
                String onClause = onMatcher.group(1);
                // Prepend DROP INDEX IF EXISTS for idempotency
                dropStatement = String.format("DROP INDEX IF EXISTS [%s] ON %s;", indexName, onClause);
            }

            if (!dropStatement.isEmpty()) {
                indexStatements.add(dropStatement);
            }
            indexStatements.add(fullIndexStatement);

            logger.debug("Extracted index: DROP + CREATE for [{}]", indexName);
        }

        return indexStatements;
    }

    /**
     * Gets all indexes that depend on a specific column.
     * This includes indexes where the column is a key column or part of a filtered WHERE clause.
     * These indexes must be dropped before the column can be altered.
     *
     * @param schema Schema name
     * @param tableName Table name
     * @param columnName Column name
     * @return List of index names that depend on the column
     */
    private List<String> getIndexesDependentOnColumn(String schema, String tableName, String columnName) {
        List<String> indexes = new ArrayList<>();

        // Query for indexes where this column is a key column or included column
        String indexQuery = """
                SELECT DISTINCT i.name AS index_name
                FROM sys.indexes i
                JOIN sys.index_columns ic ON i.object_id = ic.object_id AND i.index_id = ic.index_id
                JOIN sys.columns c ON ic.object_id = c.object_id AND ic.column_id = c.column_id
                JOIN sys.tables t ON i.object_id = t.object_id
                JOIN sys.schemas s ON t.schema_id = s.schema_id
                WHERE s.name = ? AND t.name = ? AND c.name = ?
                    AND i.is_primary_key = 0
                    AND i.is_unique_constraint = 0
                """;

        jdbcTemplate.query(indexQuery, rs -> {
            indexes.add(rs.getString("index_name"));
        }, schema, tableName, columnName);

        // Also check for filtered indexes where the column appears in the WHERE clause
        String filteredIndexQuery = """
                SELECT i.name AS index_name, i.filter_definition
                FROM sys.indexes i
                JOIN sys.tables t ON i.object_id = t.object_id
                JOIN sys.schemas s ON t.schema_id = s.schema_id
                WHERE s.name = ? AND t.name = ?
                    AND i.has_filter = 1
                    AND i.is_primary_key = 0
                    AND i.is_unique_constraint = 0
                """;

        jdbcTemplate.query(filteredIndexQuery, rs -> {
            String indexName = rs.getString("index_name");
            String filterDefinition = rs.getString("filter_definition");

            if (filterDefinition != null && !indexes.contains(indexName)) {
                String lowerFilter = filterDefinition.toLowerCase();
                String lowerCol = columnName.toLowerCase();

                // Check if column is referenced in the filter
                if (lowerFilter.contains("[" + lowerCol + "]") ||
                    lowerFilter.matches(".*\\b" + Pattern.quote(lowerCol) + "\\b.*")) {
                    indexes.add(indexName);
                    logger.debug("Found filtered index [{}] on column [{}]: {}",
                        indexName, columnName, filterDefinition);
                }
            }
        }, schema, tableName);

        if (!indexes.isEmpty()) {
            logger.debug("Found {} indexes dependent on column [{}].[{}].[{}]: {}",
                    indexes.size(), schema, tableName, columnName, indexes);
        }

        return indexes;
    }

    /**
     * Extracts CHECK constraints from the table definition.
     * These are defined in the CREATE TABLE statement as CONSTRAINT clauses.
     *
     * @param createTableSql Full CREATE TABLE definition from Git
     * @return List of CHECK constraint definitions (CONSTRAINT [name] CHECK (...))
     */
    private List<String> extractCheckConstraints(String createTableSql) {
        List<String> constraints = new ArrayList<>();

        // Pattern to match CHECK constraints
        // Matches: CONSTRAINT [name] CHECK (...)
        Pattern checkPattern = Pattern.compile(
                "CONSTRAINT\\s+\\[([^\\]]+)\\]\\s+CHECK\\s*\\(([^)]+)\\)",
                Pattern.CASE_INSENSITIVE | Pattern.DOTALL
        );

        Matcher matcher = checkPattern.matcher(createTableSql);
        while (matcher.find()) {
            String constraintName = matcher.group(1);
            String checkCondition = matcher.group(2).trim();

            // Build the full constraint definition
            String constraintDef = String.format("CONSTRAINT [%s] CHECK (%s)", constraintName, checkCondition);
            constraints.add(constraintDef);

            logger.debug("Extracted CHECK constraint [{}]: {}", constraintName, constraintDef);
        }

        return constraints;
    }

    /**
     * Checks if a CHECK constraint already exists in the database.
     *
     * @param constraintDef Constraint definition (CONSTRAINT [name] CHECK (...))
     * @return true if constraint exists, false otherwise
     */
    private boolean checkConstraintExistsInDatabase(String constraintDef) {
        // Extract constraint name from the definition
        Pattern namePattern = Pattern.compile("CONSTRAINT\\s+\\[([^\\]]+)\\]", Pattern.CASE_INSENSITIVE);
        Matcher matcher = namePattern.matcher(constraintDef);

        if (!matcher.find()) {
            return false;
        }

        String constraintName = matcher.group(1).toLowerCase();

        // Query to check if constraint exists
        String query = """
                SELECT COUNT(*)
                FROM sys.check_constraints
                WHERE name = ?
                """;

        Integer count = jdbcTemplate.queryForObject(query, Integer.class, constraintName);
        return count != null && count > 0;
    }

    /**
     * Internal class to represent table definition.
     */
    private static class TableDefinition {
        Map<String, ColumnDefinition> columns = new LinkedHashMap<>();
        String primaryKeyName;
        List<String> primaryKeyColumns = new ArrayList<>();
    }

    /**
     * Internal class to represent column definition.
     */
    private static class ColumnDefinition {
        String name;              // lowercase for comparison
        String originalName;      // original case from Git for SQL generation
        String dataType;
        boolean isNullable;
        boolean isIdentity;
        int identitySeed;
        int identityIncrement;
        int columnId;
        String defaultValue;      // DEFAULT constraint value (e.g., "0", "getdate()", etc.)
    }
}


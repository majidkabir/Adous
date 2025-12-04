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
                dbDefinition, gitDefinition);

        if (alterStatements.isEmpty()) {
            logger.debug("No changes needed for table {}.{}", tableObject.schema(), tableObject.name());
            return ""; // No changes needed
        }

        StringBuilder script = new StringBuilder();
        alterStatements.forEach(stmt -> script.append(stmt).append("\nGO\n"));

        logger.debug("Generated {} ALTER statements for table {}.{}",
                alterStatements.size(), tableObject.schema(), tableObject.name());

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

        // Extract columns from CREATE TABLE statement
        Pattern columnPattern = Pattern.compile(
                "\\[([^\\]]+)\\]\\s+([^,\\n]+?)(?:\\s+IDENTITY\\s*\\(([^)]+)\\))?\\s+(NOT\\s+NULL|NULL)",
                Pattern.CASE_INSENSITIVE | Pattern.MULTILINE
        );

        Matcher matcher = columnPattern.matcher(createTableSql);
        while (matcher.find()) {
            String columnName = matcher.group(1).toLowerCase();
            String dataType = matcher.group(2).trim();
            String identity = matcher.group(3);
            boolean isNullable = matcher.group(4).trim().equalsIgnoreCase("NULL");

            ColumnDefinition column = new ColumnDefinition();
            column.name = columnName;
            column.dataType = normalizeDataType(dataType);
            column.isNullable = isNullable;
            if (matcher.group(0).toUpperCase().contains("IDENTITY")) {
                column.isIdentity = true;
                if (identity != null) {
                    String[] parts = identity.split(",");
                    column.identitySeed = Integer.parseInt(parts[0].trim());
                    column.identityIncrement = Integer.parseInt(parts[1].trim());
                } else {
                    column.identitySeed = 1;
                    column.identityIncrement = 1;
                }
            }

            def.columns.put(columnName, column);
        }

        // Extract PRIMARY KEY
        Pattern pkPattern = Pattern.compile(
                "CONSTRAINT\\s+\\[([^\\]]+)\\]\\s+PRIMARY\\s+KEY\\s*(?:CLUSTERED|NONCLUSTERED)?\\s*\\(([^)]+)\\)",
                Pattern.CASE_INSENSITIVE | Pattern.DOTALL
        );
        matcher = pkPattern.matcher(createTableSql);
        if (matcher.find()) {
            def.primaryKeyName = matcher.group(1).toLowerCase();
            String[] columns = matcher.group(2).split(",");
            for (String col : columns) {
                def.primaryKeyColumns.add(col.replaceAll("[\\[\\]\\s]", "").toLowerCase());
            }
        }

        return def;
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
            column.name = rs.getString("column_name").toLowerCase();
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
                                                  TableDefinition dbDef, TableDefinition gitDef) {
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
                List<String> dependentConstraints = getConstraintsDependentOnColumn(schema, name, columnName);
                for (String constraintName : dependentConstraints) {
                    statements.add(String.format(
                            "ALTER TABLE [%s].[%s] DROP CONSTRAINT [%s];",
                            schema, name, constraintName
                    ));
                    logger.debug("Dropping constraint [{}] before dropping column [{}]", constraintName, columnName);
                }

                statements.add(String.format(
                        "ALTER TABLE [%s].[%s] DROP COLUMN [%s];",
                        schema, name, columnName
                ));
            }
        }

        // Add new columns or alter existing ones
        for (Map.Entry<String, ColumnDefinition> entry : gitDef.columns.entrySet()) {
            String columnName = entry.getKey();
            ColumnDefinition gitColumn = entry.getValue();
            ColumnDefinition dbColumn = dbDef.columns.get(columnName);

            if (dbColumn == null) {
                // Add new column
                String nullability = gitColumn.isNullable ? "NULL" : "NOT NULL";
                String identity = gitColumn.isIdentity ? " IDENTITY(1,1)" : "";

                statements.add(String.format(
                        "ALTER TABLE [%s].[%s] ADD [%s] %s%s %s;",
                        schema, name, columnName, gitColumn.dataType, identity, nullability
                ));
            } else {
                // Check if column needs alteration
                if (!dbColumn.dataType.equalsIgnoreCase(gitColumn.dataType) ||
                        dbColumn.isNullable != gitColumn.isNullable) {

                    // Cannot alter identity columns easily, skip those
                    if (!dbColumn.isIdentity && !gitColumn.isIdentity) {
                        String nullability = gitColumn.isNullable ? "NULL" : "NOT NULL";
                        statements.add(String.format(
                                "ALTER TABLE [%s].[%s] ALTER COLUMN [%s] %s %s;",
                                schema, name, columnName, gitColumn.dataType, nullability
                        ));
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

        return statements;
    }

    /**
     * Normalizes data type string for comparison.
     */
    private String normalizeDataType(String dataType) {
        return dataType.trim().toLowerCase()
                .replaceAll("\\s+", " ");
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
        String name;
        String dataType;
        boolean isNullable;
        boolean isIdentity;
        int identitySeed;
        int identityIncrement;
        int columnId;
    }
}


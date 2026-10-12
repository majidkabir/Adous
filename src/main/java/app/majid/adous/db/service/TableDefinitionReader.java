package app.majid.adous.db.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import static app.majid.adous.db.service.SqlServerTypeFormatter.formatDataType;

/**
 * Reads a table's structure from the system catalogs into a {@link TableModel}.
 * <p>
 * The model renders the canonical {@code CREATE TABLE} definition kept in the repository
 * ({@link TableModel#toDefinition()}). It also keeps what the definition text hides but
 * {@link TableAlterScriptGenerator} needs to change the table: the real constraint and
 * default-constraint names (definitions use normalized names).
 */
public class TableDefinitionReader {

    private static final Logger logger = LoggerFactory.getLogger(TableDefinitionReader.class);

    public enum ConstraintKind { PRIMARY_KEY, UNIQUE, FOREIGN_KEY, CHECK }

    /**
     * @param definition    column definition as written in CREATE TABLE
     * @param defaultValue  default expression as SQL Server stores it, or null
     * @param defaultName   name of the default constraint in the database, or null
     */
    public record Column(String name, String definition, String defaultValue, String defaultName) {
    }

    /**
     * @param name   name used in the definition (system-generated names are normalized)
     * @param dbName name of the constraint in the database
     * @param clause {@code CONSTRAINT [name] ...} as written in CREATE TABLE
     */
    public record Constraint(ConstraintKind kind, String name, String dbName, String clause) {
    }

    public record Index(String name, String statement) {
    }

    public record TableModel(String schema, String name, List<Column> columns,
                             List<Constraint> constraints, List<Index> indexes) {

        /** The canonical CREATE TABLE definition, followed by its indexes. */
        public String toDefinition() {
            String columnsBlock = columns.stream().map(Column::definition).collect(Collectors.joining(",\n    "));
            String constraintsBlock = constraints.isEmpty()
                    ? ""
                    : ",\n    " + constraints.stream().map(Constraint::clause).collect(Collectors.joining(",\n    "));

            String createTable = """
                CREATE TABLE [%s].[%s]
                (
                    %s%s
                );
                GO
                """.formatted(schema, name, columnsBlock, constraintsBlock);

            return createTable + indexes.stream()
                    .map(idx -> idx.statement() + "\nGO\n")
                    .collect(Collectors.joining());
        }
    }

    private final JdbcTemplate jdbcTemplate;

    public TableDefinitionReader(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** Reads a table by name; empty when it doesn't exist. */
    public Optional<TableModel> read(String schemaName, String tableName) {
        List<Integer> ids = jdbcTemplate.queryForList("""
                SELECT t.object_id FROM sys.tables t
                WHERE t.schema_id = SCHEMA_ID(?) AND t.name = ?
                """, Integer.class, schemaName, tableName);
        return ids.isEmpty() ? Optional.empty() : Optional.of(read(schemaName, tableName, ids.getFirst()));
    }

    /**
     * Reads a table. {@code schemaName} and {@code tableName} are used as written in the definition.
     */
    public TableModel read(String schemaName, String tableName, int objectId) {
        return new TableModel(schemaName, tableName,
                readColumns(objectId),
                readConstraints(tableName, objectId),
                readIndexes(schemaName, tableName, objectId));
    }

    private List<Column> readColumns(int objectId) {
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
                    ISNULL(ic.increment_value, 0) AS identity_increment,
                    dc.definition AS default_value,
                    dc.name AS default_name
                FROM sys.columns c
                LEFT JOIN sys.identity_columns ic ON c.object_id = ic.object_id AND c.column_id = ic.column_id
                LEFT JOIN sys.default_constraints dc ON c.object_id = dc.parent_object_id AND c.column_id = dc.parent_column_id
                WHERE c.object_id = ?
                ORDER BY c.column_id
                """;

        return jdbcTemplate.query(columnQuery, (rs, _) -> {
            String column = rs.getString("column_name");
            boolean identity = rs.getBoolean("is_identity");
            String defaultValue = identity ? null : rs.getString("default_value");

            String definition = "[" + column + "] " +
                    formatDataType(rs.getString("data_type"), rs.getInt("max_length"),
                            rs.getInt("precision"), rs.getInt("scale")) +
                    (identity
                            ? " IDENTITY(" + rs.getInt("identity_seed") + "," + rs.getInt("identity_increment") + ")"
                            : "") +
                    (rs.getBoolean("is_nullable") ? " NULL" : " NOT NULL") +
                    (defaultValue != null ? " DEFAULT " + defaultValue : "");

            return new Column(column, definition, defaultValue, defaultValue != null ? rs.getString("default_name") : null);
        }, objectId);
    }

    private List<Constraint> readConstraints(String tableName, int objectId) {
        List<Constraint> constraints = new ArrayList<>();

        // Primary key
        jdbcTemplate.query("""
                SELECT
                    kc.name AS constraint_name,
                    MAX(i.type) AS index_type,
                    STRING_AGG(c.name, ', ') WITHIN GROUP (ORDER BY ic.key_ordinal) AS columns
                FROM sys.key_constraints kc
                JOIN sys.indexes i ON i.object_id = kc.parent_object_id
                    AND i.index_id = kc.unique_index_id
                JOIN sys.index_columns ic ON kc.parent_object_id = ic.object_id
                    AND kc.unique_index_id = ic.index_id
                JOIN sys.columns c ON ic.object_id = c.object_id
                    AND ic.column_id = c.column_id
                WHERE kc.parent_object_id = ? AND kc.type = 'PK'
                GROUP BY kc.name
                """, rs -> {
            String dbName = rs.getString("constraint_name");
            String name = dbName.startsWith("PK__") ? "PK_" + tableName : dbName;
            // Clustered is the default for a primary key; record NONCLUSTERED so a separate
            // clustered index on the table can be recreated
            String nonclustered = rs.getInt("index_type") == 2 ? " NONCLUSTERED" : "";
            constraints.add(new Constraint(ConstraintKind.PRIMARY_KEY, name, dbName,
                    "CONSTRAINT [%s] PRIMARY KEY%s (%s)".formatted(name, nonclustered, bracketed(rs.getString("columns")))));
        }, objectId);

        // Unique constraints
        jdbcTemplate.query("""
                SELECT
                    kc.name AS constraint_name,
                    STRING_AGG(c.name, ', ') WITHIN GROUP (ORDER BY ic.key_ordinal) AS columns
                FROM sys.key_constraints kc
                JOIN sys.index_columns ic ON kc.parent_object_id = ic.object_id
                    AND kc.unique_index_id = ic.index_id
                JOIN sys.columns c ON ic.object_id = c.object_id
                    AND ic.column_id = c.column_id
                WHERE kc.parent_object_id = ? AND kc.type = 'UQ'
                GROUP BY kc.name
                """, rs -> {
            String dbName = rs.getString("constraint_name");
            String columns = rs.getString("columns");
            String name = dbName.startsWith("UQ__")
                    ? "UQ_" + tableName + "_" + String.join("_", columns.split(", "))
                    : dbName;
            constraints.add(new Constraint(ConstraintKind.UNIQUE, name, dbName,
                    "CONSTRAINT [%s] UNIQUE (%s)".formatted(name, bracketed(columns))));
        }, objectId);

        // Foreign keys
        jdbcTemplate.query("""
                SELECT
                    fk.name AS constraint_name,
                    STRING_AGG(c.name, ', ') WITHIN GROUP (ORDER BY fkc.constraint_column_id) AS columns,
                    SCHEMA_NAME(ref_t.schema_id) AS ref_schema,
                    ref_t.name AS ref_table,
                    STRING_AGG(ref_c.name, ', ') WITHIN GROUP (ORDER BY fkc.constraint_column_id) AS ref_columns,
                    fk.delete_referential_action_desc AS on_delete,
                    fk.update_referential_action_desc AS on_update
                FROM sys.foreign_keys fk
                JOIN sys.foreign_key_columns fkc ON fk.object_id = fkc.constraint_object_id
                JOIN sys.columns c ON fkc.parent_object_id = c.object_id AND fkc.parent_column_id = c.column_id
                JOIN sys.tables ref_t ON fk.referenced_object_id = ref_t.object_id
                JOIN sys.columns ref_c ON fkc.referenced_object_id = ref_c.object_id AND fkc.referenced_column_id = ref_c.column_id
                WHERE fk.parent_object_id = ?
                GROUP BY fk.name, ref_t.schema_id, ref_t.name, fk.delete_referential_action_desc, fk.update_referential_action_desc
                """, rs -> {
            String dbName = rs.getString("constraint_name");
            String refTable = rs.getString("ref_table");
            String name = dbName.startsWith("FK__") ? "FK_" + tableName + "_" + refTable : dbName;
            String clause = "CONSTRAINT [%s] FOREIGN KEY (%s) REFERENCES [%s].[%s] (%s)".formatted(
                    name, bracketed(rs.getString("columns")), rs.getString("ref_schema"), refTable,
                    bracketed(rs.getString("ref_columns")))
                    + referentialAction("DELETE", rs.getString("on_delete"))
                    + referentialAction("UPDATE", rs.getString("on_update"));
            constraints.add(new Constraint(ConstraintKind.FOREIGN_KEY, name, dbName, clause));
        }, objectId);

        // Check constraints
        jdbcTemplate.query("""
                SELECT cc.name AS constraint_name, cc.definition
                FROM sys.check_constraints cc
                WHERE cc.parent_object_id = ?
                """, rs -> {
            String dbName = rs.getString("constraint_name");
            String definition = rs.getString("definition");
            // A hash of the expression keeps generated names deterministic
            String name = dbName.startsWith("CK__")
                    ? "CK_" + tableName + "_" + Math.abs(definition.hashCode() % 10000)
                    : dbName;
            constraints.add(new Constraint(ConstraintKind.CHECK, name, dbName,
                    "CONSTRAINT [%s] CHECK %s".formatted(name, definition)));
        }, objectId);

        return constraints;
    }

    /** {@code " ON DELETE CASCADE"} etc.; empty for the default NO ACTION. */
    private static String referentialAction(String event, String actionDesc) {
        if (actionDesc == null || actionDesc.equals("NO_ACTION")) {
            return "";
        }
        return " ON " + event + " " + actionDesc.replace('_', ' ');
    }

    private static String bracketed(String commaSeparatedColumns) {
        return Arrays.stream(commaSeparatedColumns.split(", "))
                .map(col -> "[" + col + "]")
                .collect(Collectors.joining(", "));
    }

    /**
     * Indexes that don't back a PK or UNIQUE constraint.
     * <ul>
     *   <li>Rowstore: {@code CREATE [UNIQUE] [CLUSTERED] INDEX ... (key [DESC], ...) [INCLUDE (...)] [WHERE ...]}.
     *       Nonclustered is the default, so it carries no keyword.</li>
     *   <li>Columnstore: {@code CREATE CLUSTERED COLUMNSTORE INDEX ...} or
     *       {@code CREATE NONCLUSTERED COLUMNSTORE INDEX ... (columns) [WHERE ...]}.</li>
     *   <li>XML, spatial and other index types are not managed and are skipped with a warning.</li>
     * </ul>
     */
    private List<Index> readIndexes(String schemaName, String tableName, int objectId) {
        String indexQuery = """
                SELECT
                    i.name AS index_name,
                    i.index_id,
                    i.is_unique,
                    i.type AS index_type,
                    i.filter_definition,
                    k.key_columns,
                    inc.included_columns,
                    a.all_columns
                FROM sys.indexes i
                -- One aggregate per APPLY: SQL Server rejects differently ordered STRING_AGGs in one scope
                OUTER APPLY (
                    SELECT STRING_AGG(QUOTENAME(c.name) + CASE WHEN ic.is_descending_key = 1 THEN ' DESC' ELSE '' END, ', ')
                               WITHIN GROUP (ORDER BY ic.key_ordinal) AS key_columns
                    FROM sys.index_columns ic
                    JOIN sys.columns c ON ic.object_id = c.object_id AND ic.column_id = c.column_id
                    WHERE ic.object_id = i.object_id AND ic.index_id = i.index_id
                        AND ic.is_included_column = 0 AND ic.key_ordinal > 0
                ) k
                OUTER APPLY (
                    SELECT STRING_AGG(QUOTENAME(c.name), ', ') WITHIN GROUP (ORDER BY ic.index_column_id) AS included_columns
                    FROM sys.index_columns ic
                    JOIN sys.columns c ON ic.object_id = c.object_id AND ic.column_id = c.column_id
                    WHERE ic.object_id = i.object_id AND ic.index_id = i.index_id AND ic.is_included_column = 1
                ) inc
                OUTER APPLY (
                    SELECT STRING_AGG(QUOTENAME(c.name), ', ') WITHIN GROUP (ORDER BY ic.index_column_id) AS all_columns
                    FROM sys.index_columns ic
                    JOIN sys.columns c ON ic.object_id = c.object_id AND ic.column_id = c.column_id
                    WHERE ic.object_id = i.object_id AND ic.index_id = i.index_id
                ) a
                WHERE i.object_id = ?
                    AND i.is_primary_key = 0
                    AND i.is_unique_constraint = 0
                    AND i.type > 0
                """;

        List<Index> indexes = new ArrayList<>();

        jdbcTemplate.query(indexQuery, rs -> {
            String indexName = rs.getString("index_name");
            int indexId = rs.getInt("index_id");
            int indexType = rs.getInt("index_type");
            String unique = rs.getBoolean("is_unique") ? "UNIQUE " : "";
            String filter = rs.getString("filter_definition");
            String target = "[%s] ON [%s].[%s]".formatted(indexName, schemaName, tableName);

            String statement;
            switch (indexType) {
                case 1, 2 -> {
                    // Rowstore indexes: key columns must have indexable types
                    if (hasInvalidIndexColumns(objectId, indexId)) {
                        logger.warn("Skipping index [{}] on [{}].[{}] - contains columns with types invalid for index keys (VARCHAR(MAX), NVARCHAR(MAX), TEXT, etc.)",
                                indexName, schemaName, tableName);
                        return;
                    }
                    String clustered = indexType == 1 ? "CLUSTERED " : "";
                    statement = "CREATE %s%sINDEX %s (%s)".formatted(
                            unique, clustered, target, rs.getString("key_columns"));
                    String included = rs.getString("included_columns");
                    if (included != null && !included.isBlank()) {
                        statement += " INCLUDE (" + included + ")";
                    }
                }
                case 5 -> statement = "CREATE CLUSTERED COLUMNSTORE INDEX " + target;
                case 6 -> statement = "CREATE NONCLUSTERED COLUMNSTORE INDEX %s (%s)".formatted(
                        target, rs.getString("all_columns"));
                default -> {
                    logger.warn("Skipping index [{}] on [{}].[{}] - index type {} (XML, spatial, hash, ...) is not managed",
                            indexName, schemaName, tableName, indexType);
                    return;
                }
            }

            // filter_definition does not include the WHERE keyword
            if (filter != null && !filter.trim().isEmpty()) {
                statement += " WHERE " + normalizeFilterDefinition(filter);
            }

            indexes.add(new Index(indexName, statement + ";"));
        }, objectId);

        return indexes;
    }

    /**
     * Checks if an index has any key columns with data types invalid for use in indexes.
     * Invalid types include: TEXT, NTEXT, IMAGE, VARCHAR(MAX), NVARCHAR(MAX), VARBINARY(MAX), XML
     */
    private boolean hasInvalidIndexColumns(int objectId, int indexId) {
        Integer invalidCount = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) as invalid_count
                FROM sys.index_columns ic
                JOIN sys.columns c ON ic.object_id = c.object_id AND ic.column_id = c.column_id
                JOIN sys.types t ON c.user_type_id = t.user_type_id
                WHERE ic.object_id = ?
                    AND ic.index_id = ?
                    AND ic.is_included_column = 0
                    AND (
                        t.name IN ('text', 'ntext', 'image', 'xml')
                        OR (t.name IN ('varchar', 'nvarchar', 'varbinary') AND c.max_length = -1)
                    )
                """, Integer.class, objectId, indexId);
        return invalidCount != null && invalidCount > 0;
    }

    /**
     * Normalizes filter definitions from SQL Server to standard SQL format.
     * SQL Server returns filter definitions like: ([IsActive]=(1)); we write [IsActive] = 1.
     */
    static String normalizeFilterDefinition(String filterDef) {
        if (filterDef == null || filterDef.trim().isEmpty()) {
            return filterDef;
        }

        String normalized = filterDef.trim();

        // Remove parentheses that wrap the whole expression: ([IsActive]=(1)) -> [IsActive]=(1)
        while (normalized.startsWith("(") && normalized.endsWith(")")) {
            String inner = normalized.substring(1, normalized.length() - 1);
            int openCount = 0;
            boolean canRemove = true;
            for (int i = 0; i < inner.length(); i++) {
                if (inner.charAt(i) == '(') openCount++;
                else if (inner.charAt(i) == ')') {
                    openCount--;
                    if (openCount < 0) {
                        canRemove = false;
                        break;
                    }
                }
            }
            if (canRemove && openCount == 0) {
                normalized = inner;
            } else {
                break;
            }
        }

        // Remove spaces inside brackets: [ IsActive ] -> [IsActive]
        normalized = normalized.replaceAll("\\[\\s+", "[").replaceAll("\\s+\\]", "]");

        // Spaces around comparison operators: =(1) -> = (1)
        normalized = normalized.replaceAll("([=><])\\(", "$1 (");
        normalized = normalized.replaceAll("\\)([=><])", ") $1");
        normalized = normalized.replaceAll("\\s*([=><]+)\\s*", " $1 ");

        // Parentheses around numeric literals: = (1) -> = 1
        normalized = normalized.replaceAll("([=><]+)\\s*\\(\\s*(\\d+)\\s*\\)", "$1 $2");

        return normalized.replaceAll("\\s+", " ").trim();
    }
}

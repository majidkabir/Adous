package app.majid.adous.db.service;

import app.majid.adous.synchronizer.db.DatabaseService;
import app.majid.adous.synchronizer.model.DbObject;
import app.majid.adous.synchronizer.model.DbObjectType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static app.majid.adous.db.service.SqlServerTypeFormatter.formatDataType;

/**
 * Microsoft SQL Server implementation of DatabaseService.
 * Provides database object retrieval and modification operations for MS SQL Server.
 */
@Service
public class MSSQLDatabaseService implements DatabaseService {

    private static final Logger logger = LoggerFactory.getLogger(MSSQLDatabaseService.class);

    @Value("${spring.application.database.default-schema:dbo}")
    private String defaultSchema;

    private final JdbcTemplate jdbcTemplate;
    private final TableAlterScriptGenerator tableAlterScriptGenerator;

    public MSSQLDatabaseService(JdbcTemplate jdbcTemplate, TableAlterScriptGenerator tableAlterScriptGenerator) {
        this.jdbcTemplate = jdbcTemplate;
        this.tableAlterScriptGenerator = tableAlterScriptGenerator;
    }

    @Override
    public List<DbObject> getDbObjects() {
        logger.debug("Fetching database objects from SQL Server");

        // Get objects from sys.sql_modules (procedures, functions, views, triggers)
        List<DbObject> objects = jdbcTemplate.query(buildGetObjectsQuery(), (rs, _) ->
                new DbObject(
                        rs.getString("schema_name").toLowerCase(),
                        rs.getString("name").toLowerCase(),
                        DbObjectType.valueOf(rs.getString("object_type")),
                        rs.getString("definition")
                )
        );

        objects.addAll(getFullTextCatalogs());
        objects.addAll(getSynonyms());
        objects.addAll(getTableTypes());
        objects.addAll(getSequences());
        objects.addAll(getScalarTypes());
        objects.addAll(getTables());

        logger.debug("Retrieved {} database objects", objects.size());
        return objects;
    }

    @Transactional
    @Override
    public void applyChangesToDatabase(List<DbObject> dbChanges) {
        if (dbChanges == null || dbChanges.isEmpty()) {
            logger.debug("No database changes to apply");
            return;
        }

        logger.info("Applying {} database changes", dbChanges.size());

        createRequiredSchemas(dbChanges);
        applyObjectChanges(dbChanges);

        logger.info("Successfully applied database changes");
    }

    /**
     * Builds the SQL query to retrieve database objects with their complete definitions.
     * Includes SET options (ANSI_NULLS, QUOTED_IDENTIFIER) required for SQL Server objects.
     */
    private String buildGetObjectsQuery() {
        return """
                SELECT
                    s.name AS schema_name,
                    o.name AS name,
                    CASE
                        WHEN o.type = 'V' THEN 'VIEW'
                        WHEN o.type IN ('FN','IF','TF','FS','FT') THEN 'FUNCTION'
                        WHEN o.type = 'P' THEN 'PROCEDURE'
                        WHEN o.type = 'TR' THEN 'TRIGGER'
                    END AS object_type,
                    (
                        IIF(m.uses_ansi_nulls = 1, 'SET ANSI_NULLS ON;' + CHAR(13) + CHAR(10), 'SET ANSI_NULLS OFF;' + CHAR(13) + CHAR(10)) +
                        'GO' + CHAR(13) + CHAR(10) +
                        IIF(m.uses_quoted_identifier = 1, 'SET QUOTED_IDENTIFIER ON;' + CHAR(13) + CHAR(10), 'SET QUOTED_IDENTIFIER OFF;' + CHAR(13) + CHAR(10)) +
                        'GO' + CHAR(13) + CHAR(10) +
                        m.definition + CHAR(13) + CHAR(10) +
                        'GO'
                    ) AS definition
                FROM sys.objects o
                JOIN sys.schemas s ON o.schema_id = s.schema_id
                JOIN sys.sql_modules m ON o.object_id = m.object_id
                WHERE o.type IN ('P', 'FN', 'IF', 'TF', 'FS', 'FT', 'V', 'TR')
                    AND o.is_ms_shipped = 0
                    AND s.name != 'sys'
                ORDER BY s.name, o.type, o.name
                """;
    }

    /**
     * Retrieves all synonyms from the database.
     *
     * @return List of synonym database objects
     */
    private List<DbObject> getSynonyms() {
        logger.debug("Fetching synonyms from SQL Server");

        String synonymQuery = """
                SELECT
                    SCHEMA_NAME(schema_id) AS schema_name,
                    name AS name,
                    base_object_name AS target_object
                FROM sys.synonyms
                WHERE is_ms_shipped = 0
                ORDER BY schema_name, name
                """;

        List<DbObject> synonyms = jdbcTemplate.query(synonymQuery, (rs, _) -> {
            String schema = rs.getString("schema_name").toLowerCase();
            String name = rs.getString("name").toLowerCase();
            String targetObject = rs.getString("target_object");

            String definition = "CREATE SYNONYM [%s].[%s] FOR %s;%nGO".formatted(schema, name, targetObject);

            return new DbObject(schema, name, DbObjectType.SYNONYM, definition);
        });

        logger.debug("Retrieved {} synonyms", synonyms.size());
        return synonyms;
    }

    /**
     * Retrieves all user-defined table types from the database.
     *
     * @return List of table type database objects
     */
    private List<DbObject> getTableTypes() {
        logger.debug("Fetching table types from SQL Server");

        String tableTypeQuery = """
                SELECT
                    SCHEMA_NAME(tt.schema_id) AS schema_name,
                    tt.name AS name,
                    tt.type_table_object_id AS object_id
                FROM sys.table_types tt
                WHERE tt.is_user_defined = 1
                ORDER BY schema_name, name
                """;

        List<DbObject> tableTypes = jdbcTemplate.query(tableTypeQuery, (rs, _) -> {
            String schema = rs.getString("schema_name").toLowerCase();
            String name = rs.getString("name").toLowerCase();
            int objectId = rs.getInt("object_id");

            String definition = buildTableTypeDefinition(schema, name, objectId);

            return new DbObject(schema, name, DbObjectType.TABLE_TYPE, definition);
        });

        logger.debug("Retrieved {} table types", tableTypes.size());
        return tableTypes;
    }

    /**
     * Builds the CREATE TYPE definition for a table type by retrieving its columns.
     *
     * @param schemaName The schema name
     * @param typeName The type name
     * @param objectId The object ID of the table type
     * @return Complete CREATE TYPE statement
     */
    private String buildTableTypeDefinition(String schemaName, String typeName, int objectId) {
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
                WHERE c.object_id = ?
                ORDER BY c.column_id
                """;

        List<String> columns = jdbcTemplate.query(columnQuery, (rs, _) -> {
            String column = rs.getString("column_name");
            String dataType = rs.getString("data_type");
            int maxLength = rs.getInt("max_length");
            int precision = rs.getInt("precision");
            int scale = rs.getInt("scale");
            boolean nullable = rs.getBoolean("is_nullable");
            boolean identity = rs.getBoolean("is_identity");
            int identitySeed = rs.getInt("identity_seed");
            int identityIncrement = rs.getInt("identity_increment");

            return "[" + column + "] " +
                    formatDataType(dataType, maxLength, precision, scale) +
                    (identity ? " IDENTITY(" + identitySeed + "," + identityIncrement + ")" : "") +
                    (nullable ? " NULL" : " NOT NULL");
        }, objectId);

        return "CREATE TYPE [" + schemaName + "].[" + typeName + "] AS TABLE\n(\n" +
                "    " + String.join(",\n    ", columns) +
                "\n);\nGO";
    }

    /**
     * Retrieves all sequences from the database.
     *
     * @return List of sequence database objects
     */
    private List<DbObject> getSequences() {
        logger.debug("Fetching sequences from SQL Server");

        String sequenceQuery = """
                SELECT
                    SCHEMA_NAME(schema_id) AS schema_name,
                    name AS name,
                    CAST(start_value AS BIGINT) AS start_value,
                    CAST(increment AS BIGINT) AS increment,
                    CAST(minimum_value AS BIGINT) AS minimum_value,
                    CAST(maximum_value AS BIGINT) AS maximum_value,
                    is_cycling,
                    is_cached,
                    CAST(cache_size AS INT) AS cache_size,
                    TYPE_NAME(system_type_id) AS data_type
                FROM sys.sequences
                WHERE is_ms_shipped = 0
                ORDER BY schema_name, name
                """;

        List<DbObject> sequences = jdbcTemplate.query(sequenceQuery, (rs, _) -> {
            String schema = rs.getString("schema_name").toLowerCase();
            String name = rs.getString("name").toLowerCase();
            long startValue = rs.getLong("start_value");
            long increment = rs.getLong("increment");
            long minValue = rs.getLong("minimum_value");
            long maxValue = rs.getLong("maximum_value");
            boolean cycling = rs.getBoolean("is_cycling");
            boolean cached = rs.getBoolean("is_cached");
            int cacheSize = rs.getInt("cache_size");
            String dataType = rs.getString("data_type");

            String definition = """
                CREATE SEQUENCE [%s].[%s]
                    AS %s
                    START WITH %d
                    INCREMENT BY %d
                    MINVALUE %d
                    MAXVALUE %d
                    %s
                    %s;
                GO
            """.formatted(
                    schema, name, dataType, startValue, increment, minValue, maxValue,
                    cycling ? "CYCLE" : "NO CYCLE",
                    (cached && cacheSize > 0) ? "CACHE " + cacheSize : "NO CACHE"
            );

            return new DbObject(schema, name, DbObjectType.SEQUENCE, definition);
        });

        logger.debug("Retrieved {} sequences", sequences.size());
        return sequences;
    }

    /**
     * Retrieves all user-defined scalar types from the database.
     *
     * @return List of scalar type database objects
     */
    private List<DbObject> getScalarTypes() {
        logger.debug("Fetching scalar types from SQL Server");

        String scalarTypeQuery = """
                SELECT
                    SCHEMA_NAME(t.schema_id) AS schema_name,
                    t.name AS name,
                    TYPE_NAME(t.system_type_id) AS base_type,
                    t.max_length,
                    t.precision,
                    t.scale,
                    t.is_nullable,
                    dc.definition AS default_value,
                    r.name AS rule_name
                FROM sys.types t
                LEFT JOIN sys.default_constraints dc ON t.default_object_id = dc.object_id
                LEFT JOIN sys.objects r ON t.rule_object_id = r.object_id
                WHERE t.is_user_defined = 1
                    AND t.is_table_type = 0
                    AND t.is_assembly_type = 0
                ORDER BY schema_name, name
                """;

        List<DbObject> scalarTypes = jdbcTemplate.query(scalarTypeQuery, (rs, _) -> {
            String schema = rs.getString("schema_name").toLowerCase();
            String name = rs.getString("name").toLowerCase();
            String base = rs.getString("base_type");
            int maxLen = rs.getInt("max_length");
            int precision = rs.getInt("precision");
            int scale = rs.getInt("scale");
            boolean nullable = rs.getBoolean("is_nullable");
            String defaultValue = rs.getString("default_value");
            String ruleName = rs.getString("rule_name");

            String createType = """
            CREATE TYPE [%s].[%s]
                FROM %s%s;
            GO

            """.formatted(
                    schema, name,
                    formatDataType(base, maxLen, precision, scale),
                    nullable ? "" : " NOT NULL"
            );

            String defaultBinding =
                    defaultValue == null ? "" :
                            "EXEC sp_bindefault '%s', '[%s].[%s]';\nGO\n\n"
                                    .formatted(defaultValue, schema, name);

            String ruleBinding =
                    ruleName == null ? "" :
                            "EXEC sp_bindrule '[%s].[%s]', '[%s].[%s]';\nGO\n\n"
                                    .formatted(schema, ruleName, schema, name);

            String definition = createType + defaultBinding + ruleBinding;

            return new DbObject(schema, name, DbObjectType.SCALAR_TYPE, definition);
        });

        logger.debug("Retrieved {} scalar types", scalarTypes.size());
        return scalarTypes;
    }

    /**
     * Retrieves all user-defined tables from the database.
     *
     * @return List of table database objects
     */
    private List<DbObject> getTables() {
        logger.debug("Fetching tables from SQL Server");

        String tableQuery = """
                SELECT
                    SCHEMA_NAME(t.schema_id) AS schema_name,
                    t.name AS table_name,
                    t.object_id
                FROM sys.tables t
                WHERE t.is_ms_shipped = 0
                    AND t.type = 'U'
                ORDER BY schema_name, table_name
                """;

        List<DbObject> tables = jdbcTemplate.query(tableQuery, (rs, _) -> {
            String schema = rs.getString("schema_name").toLowerCase();
            String table = rs.getString("table_name").toLowerCase();
            int objectId = rs.getInt("object_id");

            String definition = buildCreateTableDefinition(schema, table, objectId);

            return new DbObject(schema, table, DbObjectType.TABLE, definition);
        });

        logger.debug("Retrieved {} tables", tables.size());
        return tables;
    }

    /**
     * Builds the CREATE TABLE definition for a table by retrieving its structure.
     *
     * @param schemaName The schema name
     * @param tableName The table name
     * @param objectId The object ID of the table
     * @return Complete CREATE TABLE statement
     */
    private String buildCreateTableDefinition(String schemaName, String tableName, int objectId) {
        List<String> columnDefs = getTableColumns(objectId);
        String columnsBlock = String.join(",\n    ", columnDefs);

        List<String> constraintDefs = getTableConstraints(tableName, objectId);
        String constraintsBlock = constraintDefs.isEmpty()
                ? ""
                : ",\n    " + String.join(",\n    ", constraintDefs);

        String createTable = """
            CREATE TABLE [%s].[%s]
            (
                %s%s
            );
            GO

            """.formatted(schemaName, tableName, columnsBlock, constraintsBlock);

        // Indexes
        List<String> indexDefs = getTableIndexes(schemaName, tableName, objectId);
        String indexes = indexDefs.stream()
                .map(idx -> idx + "\nGO\n")
                .collect(Collectors.joining());

        return createTable + indexes;
    }

    /**
     * Gets column definitions for a table.
     */
    private List<String> getTableColumns(int objectId) {
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
                    dc.definition AS default_value
                FROM sys.columns c
                LEFT JOIN sys.identity_columns ic ON c.object_id = ic.object_id AND c.column_id = ic.column_id
                LEFT JOIN sys.default_constraints dc ON c.object_id = dc.parent_object_id AND c.column_id = dc.parent_column_id
                WHERE c.object_id = ?
                ORDER BY c.column_id
                """;

        return jdbcTemplate.query(columnQuery, (rs, _) -> {
            String column = rs.getString("column_name");
            String dataType = rs.getString("data_type");
            int maxLength = rs.getInt("max_length");
            int precision = rs.getInt("precision");
            int scale = rs.getInt("scale");
            boolean nullable = rs.getBoolean("is_nullable");
            boolean identity = rs.getBoolean("is_identity");
            int identitySeed = rs.getInt("identity_seed");
            int identityIncrement = rs.getInt("identity_increment");
            String defaultValue = rs.getString("default_value");

            return "[" + column + "] " +
                    formatDataType(dataType, maxLength, precision, scale) +
                    (identity
                            ? " IDENTITY(" + identitySeed + "," + identityIncrement + ")"
                            : "") +
                    (nullable ? " NULL" : " NOT NULL") +
                    ((defaultValue != null && !identity)
                            ? " DEFAULT " + defaultValue
                            : "");
        }, objectId);
    }

    /**
     * Gets constraint definitions for a table (PK, FK, UNIQUE, CHECK).
     */
    private List<String> getTableConstraints(String tableName, int objectId) {
        List<String> constraints = new ArrayList<>();

        // Primary Key
        String pkQuery = """
                SELECT
                    kc.name AS constraint_name,
                    STRING_AGG(c.name, ', ') WITHIN GROUP (ORDER BY ic.key_ordinal) AS columns
                FROM sys.key_constraints kc
                JOIN sys.index_columns ic ON kc.parent_object_id = ic.object_id
                    AND kc.unique_index_id = ic.index_id
                JOIN sys.columns c ON ic.object_id = c.object_id
                    AND ic.column_id = c.column_id
                WHERE kc.parent_object_id = ? AND kc.type = 'PK'
                GROUP BY kc.name
                """;

        jdbcTemplate.query(pkQuery, rs -> {
            String constraintName = rs.getString("constraint_name");
            String columns = rs.getString("columns");
            String[] columnArray = columns.split(", ");
            String formattedColumns = Arrays.stream(columnArray)
                    .map(col -> "[" + col + "]")
                    .reduce((a, b) -> a + ", " + b)
                    .orElse("");

            // Normalize auto-generated constraint names (PK__tablename__*)
            String normalizedName = constraintName;
            if (constraintName.startsWith("PK__")) {
                normalizedName = "PK_" + tableName;
            }

            constraints.add(String.format("CONSTRAINT [%s] PRIMARY KEY (%s)",
                    normalizedName, formattedColumns));
        }, objectId);

        // Unique Constraints
        String uniqueQuery = """
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
                """;

        jdbcTemplate.query(uniqueQuery, rs -> {
            String constraintName = rs.getString("constraint_name");
            String columns = rs.getString("columns");
            String[] columnArray = columns.split(", ");
            String formattedColumns = Arrays.stream(columnArray)
                    .map(col -> "[" + col + "]")
                    .reduce((a, b) -> a + ", " + b)
                    .orElse("");

            // Normalize auto-generated constraint names (UQ__tablename__*)
            String normalizedName = constraintName;
            if (constraintName.startsWith("UQ__")) {
                String columnsForName = String.join("_", columnArray);
                normalizedName = "UQ_" + tableName + "_" + columnsForName;
            }

            constraints.add(String.format("CONSTRAINT [%s] UNIQUE (%s)",
                    normalizedName, formattedColumns));
        }, objectId);

        // Foreign Keys
        String fkQuery = """
                SELECT
                    fk.name AS constraint_name,
                    STRING_AGG(c.name, ', ') WITHIN GROUP (ORDER BY fkc.constraint_column_id) AS columns,
                    SCHEMA_NAME(ref_t.schema_id) AS ref_schema,
                    ref_t.name AS ref_table,
                    STRING_AGG(ref_c.name, ', ') WITHIN GROUP (ORDER BY fkc.constraint_column_id) AS ref_columns
                FROM sys.foreign_keys fk
                JOIN sys.foreign_key_columns fkc ON fk.object_id = fkc.constraint_object_id
                JOIN sys.columns c ON fkc.parent_object_id = c.object_id AND fkc.parent_column_id = c.column_id
                JOIN sys.tables ref_t ON fk.referenced_object_id = ref_t.object_id
                JOIN sys.columns ref_c ON fkc.referenced_object_id = ref_c.object_id AND fkc.referenced_column_id = ref_c.column_id
                WHERE fk.parent_object_id = ?
                GROUP BY fk.name, ref_t.schema_id, ref_t.name
                """;

        jdbcTemplate.query(fkQuery, rs -> {
            String constraintName = rs.getString("constraint_name");
            String columns = rs.getString("columns");
            String refSchema = rs.getString("ref_schema");
            String refTable = rs.getString("ref_table");
            String refColumns = rs.getString("ref_columns");

            String[] columnArray = columns.split(", ");
            String formattedColumns = Arrays.stream(columnArray)
                    .map(col -> "[" + col + "]")
                    .reduce((a, b) -> a + ", " + b)
                    .orElse("");

            String[] refColumnArray = refColumns.split(", ");
            String formattedRefColumns = Arrays.stream(refColumnArray)
                    .map(col -> "[" + col + "]")
                    .reduce((a, b) -> a + ", " + b)
                    .orElse("");

            // Normalize auto-generated constraint names (FK__tablename__*)
            String normalizedName = constraintName;
            if (constraintName.startsWith("FK__")) {
                normalizedName = "FK_" + tableName + "_" + refTable;
            }

            constraints.add(String.format("CONSTRAINT [%s] FOREIGN KEY (%s) REFERENCES [%s].[%s] (%s)",
                    normalizedName, formattedColumns, refSchema, refTable, formattedRefColumns));
        }, objectId);

        // Check Constraints
        String checkQuery = """
                SELECT
                    cc.name AS constraint_name,
                    cc.definition
                FROM sys.check_constraints cc
                WHERE cc.parent_object_id = ?
                """;

        jdbcTemplate.query(checkQuery, rs -> {
            String constraintName = rs.getString("constraint_name");
            String definition = rs.getString("definition");

            // Normalize auto-generated constraint names (CK__tablename__*)
            String normalizedName = constraintName;
            if (constraintName.startsWith("CK__")) {
                // Use a simple hash of the definition to make the name deterministic
                int hash = Math.abs(definition.hashCode() % 10000);
                normalizedName = "CK_" + tableName + "_" + hash;
            }

            constraints.add(String.format("CONSTRAINT [%s] CHECK %s",
                    normalizedName, definition));
        }, objectId);

        return constraints;
    }

    /**
     * Gets index definitions for a table (non-constraint indexes).
     * Includes all index types: Clustered (1), Non-Clustered (2), XML (3), Spatial (4), Clustered Columnstore (5), etc.
     * Validates and filters out only indexes with invalid column types that cannot be used as key columns.
     */
    private List<String> getTableIndexes(String schemaName, String tableName, int objectId) {
        String indexQuery = """
                SELECT
                    i.name AS index_name,
                    i.index_id,
                    i.is_unique,
                    i.type AS index_type,
                    i.filter_definition,
                    STRING_AGG(c.name, ', ') WITHIN GROUP (ORDER BY ic.key_ordinal) AS columns
                FROM sys.indexes i
                LEFT JOIN sys.index_columns ic ON i.object_id = ic.object_id AND i.index_id = ic.index_id AND ic.is_included_column = 0
                LEFT JOIN sys.columns c ON ic.object_id = c.object_id AND ic.column_id = c.column_id
                WHERE i.object_id = ?
                    AND i.is_primary_key = 0
                    AND i.is_unique_constraint = 0
                    AND i.type > 0
                GROUP BY i.name, i.index_id, i.is_unique, i.type, i.filter_definition
                """;

        List<String> indexes = new ArrayList<>();

        jdbcTemplate.query(indexQuery, rs -> {
            String indexName = rs.getString("index_name");
            int indexId = rs.getInt("index_id");
            boolean isUnique = rs.getBoolean("is_unique");
            String filterDefinition = rs.getString("filter_definition");
            String columns = rs.getString("columns");

            // Validate that all key columns have valid data types for indexing
            if (hasInvalidIndexColumns(objectId, indexId)) {
                logger.warn("Skipping index [{}] on [{}].[{}] - contains columns with types invalid for index keys (VARCHAR(MAX), NVARCHAR(MAX), TEXT, etc.)",
                        indexName, schemaName, tableName);
                return;
            }

            String[] columnArray = columns.split(", ");
            String formattedColumns = Arrays.stream(columnArray)
                    .map(col -> "[" + col + "]")
                    .collect(Collectors.joining(", "));

            String uniqueKeyword = isUnique ? "UNIQUE " : "";

            // Note: We don't include CLUSTERED/NONCLUSTERED keywords to match Git convention
            // The index type is implicit from the database structure

            String indexDefinition = String.format("CREATE %sINDEX [%s] ON [%s].[%s] (%s)",
                    uniqueKeyword, indexName, schemaName, tableName, formattedColumns);

            // Add WHERE clause if it exists (filtered index)
            if (filterDefinition != null && !filterDefinition.trim().isEmpty()) {
                indexDefinition += " " + filterDefinition;
                logger.debug("Index [{}] has filter: {}", indexName, filterDefinition);
            }

            indexDefinition += ";";
            indexes.add(indexDefinition);

            logger.debug("Generated index: {}", indexDefinition);
        }, objectId);

        logger.debug("Retrieved {} indexes for table {}.{}", indexes.size(), schemaName, tableName);
        return indexes;
    }

    /**
     * Checks if an index has any key columns with data types invalid for use in indexes.
     * Invalid types include: TEXT, NTEXT, IMAGE, VARCHAR(MAX), NVARCHAR(MAX), VARBINARY(MAX), XML
     */
    private boolean hasInvalidIndexColumns(int objectId, int indexId) {
        String validationQuery = """
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
                """;

        Integer invalidCount = jdbcTemplate.queryForObject(validationQuery, Integer.class, objectId, indexId);
        return invalidCount != null && invalidCount > 0;
    }

    /**
     * Creates any schemas required for the database objects that don't already exist.
     */
    private void createRequiredSchemas(List<DbObject> dbChanges) {
        List<String> requiredSchemas = dbChanges.stream()
                .map(DbObject::schema)
                .distinct()
                .filter(schema -> !schema.equalsIgnoreCase(defaultSchema))
                .toList();

        if (!requiredSchemas.isEmpty()) {
            logger.debug("Creating {} required schemas", requiredSchemas.size());
            requiredSchemas.forEach(this::createSchemaIfNotExists);
        }
    }

    /**
     * Creates a schema if it doesn't already exist.
     */
    private void createSchemaIfNotExists(String schema) {
        String createSchemaQuery =
                ("IF NOT EXISTS (SELECT * FROM sys.schemas WHERE name = '%s') " +
                "EXEC('CREATE SCHEMA [%1$s]')").formatted(schema);

        logger.debug("Creating schema if not exists: {}", schema);
        jdbcTemplate.execute(createSchemaQuery);
    }

    /**
     * Applies the actual object changes.
     * For TABLEs: generates ALTER statements to preserve data
     * For other objects: uses DROP and CREATE pattern
     * Objects are processed in dependency order to ensure referenced objects exist:
     * 1. Tables (base objects)
     * 2. Scalar Types and Table Types
     * 3. Sequences
     * 4. Synonyms
     * 5. Functions and Procedures (depend on tables)
     * 6. Views (depend on tables and functions)
     * 7. Triggers (depend on tables)
     */
    private void applyObjectChanges(List<DbObject> dbChanges) {
        // Order changes by dependency to avoid "object does not exist" errors
        List<DbObject> orderedChanges = orderByDependency(dbChanges);

        orderedChanges.forEach(change -> {
            String sql;
            if (change.type() == DbObjectType.TABLE) {
                // For tables, use ALTER script generator to preserve data
                if (change.definition() != null) {
                    sql = tableAlterScriptGenerator.generateAlterScript(change);
                    if (!sql.isEmpty()) {
                        executeWithGoBatchSeparator(sql);
                    }
                } else {
                    // Table deletion - drop the table
                    sql = buildDropQuery(change);
                    executeWithGoBatchSeparator(sql);
                }
            } else {
                // For non-table objects, use traditional DROP/CREATE pattern
                StringBuilder sqlBuilder = new StringBuilder();
                sqlBuilder.append(buildDropQuery(change));

                if (change.definition() != null) {
                    sqlBuilder.append(change.definition());
                }

                executeWithGoBatchSeparator(sqlBuilder.toString());
            }
        });
    }

    /**
     * Extracts view dependencies by parsing SQL definitions.
     * This is used during repo-to-db sync where we only have view definitions, not database metadata.
     *
     * @param views List of view objects
     * @return Map of view name to set of views it depends on
     */
    private Map<String, Set<String>> extractViewDependenciesFromSQL(List<DbObject> views) {
        Map<String, Set<String>> dependencies = new HashMap<>();

        // Pre-lowercase names once
        record ViewKey(DbObject v, String schemaName, String viewName) {}

        List<ViewKey> viewKeys = views.stream()
                .map(v -> new ViewKey(v, v.schema().toLowerCase(), v.name().toLowerCase()))
                .toList();

        for (ViewKey v : viewKeys) {
            String definition = v.v.definition();
            if (definition == null) {
                continue;
            }

            String sql = definition.toLowerCase();
            Set<String> deps = new HashSet<>();

            for (ViewKey other : viewKeys) {
                // skip self
                if (v.schemaName.equals(other.schemaName) && v.viewName.equals(other.viewName)) {
                    continue;
                }

                // patterns: [schema].[view] / schema.view
                String pattern1 = "\\[?" + Pattern.quote(other.schemaName) + "\\]?\\.\\[?" +
                        Pattern.quote(other.viewName) + "\\]?";
                // [view] / view (word boundary-ish)
                String pattern2 = "\\[?" + Pattern.quote(other.viewName) + "\\]?";

                String fromJoinPattern1 = "(?:from|join)\\s+" + pattern1;
                String fromJoinPattern2 = "(?:from|join)\\s+" + pattern2;

                Pattern p1 = Pattern.compile(fromJoinPattern1, Pattern.CASE_INSENSITIVE);
                Pattern p2 = Pattern.compile(fromJoinPattern2, Pattern.CASE_INSENSITIVE);

                if (p1.matcher(sql).find() || p2.matcher(sql).find()) {
                    String depKey = other.schemaName + "." + other.viewName;
                    deps.add(depKey);

                    logger.debug("View {}.{} depends on {}.{}",
                            v.v.schema(), v.v.name(), other.v.schema(), other.v.name());
                }
            }

            if (!deps.isEmpty()) {
                String viewKey = v.schemaName + "." + v.viewName;
                dependencies.put(viewKey, deps);
            }
        }

        logger.info("Extracted dependencies for {} views", dependencies.size());
        return dependencies;
    }

    /**
     * Sorts views by dependency using topological sort.
     * Views that are dependencies come before views that depend on them.
     *
     * @param views List of views to sort
     * @param dependencies Map of view dependencies (using schema.name as key)
     * @return List of views sorted by dependency order
     */
    private List<DbObject> sortViewsByDependency(List<DbObject> views,
                                                 Map<String, Set<String>> dependencies) {
        List<DbObject> sorted = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        Set<String> visiting = new HashSet<>();

        // Create a map for quick lookup by schema.name
        Map<String, DbObject> viewMap = new HashMap<>();
        for (DbObject view : views) {
            String key = view.schema().toLowerCase() + "." + view.name().toLowerCase();
            viewMap.put(key, view);
        }

        for (DbObject view : views) {
            String viewKey = view.schema().toLowerCase() + "." + view.name().toLowerCase();
            if (!visited.contains(viewKey)) {
                topologicalSortViews(view, viewMap, dependencies, visited, visiting, sorted);
            }
        }

        logger.info("Sorted {} views by dependency order", sorted.size());
        return sorted;
    }

    /**
     * Performs topological sort for views using DFS with cycle detection.
     */
    private void topologicalSortViews(DbObject view,
                                      Map<String, DbObject> viewMap,
                                      Map<String, Set<String>> dependencies,
                                      Set<String> visited,
                                      Set<String> visiting,
                                      List<DbObject> sorted) {
        String viewKey = view.schema().toLowerCase() + "." + view.name().toLowerCase();

        if (visiting.contains(viewKey)) {
            // Circular dependency detected - log warning and continue
            logger.warn("Circular view dependency detected involving view: {}", viewKey);
            return;
        }

        if (visited.contains(viewKey)) {
            return;
        }

        visiting.add(viewKey);

        // Visit dependencies first (views this view depends on)
        Set<String> deps = dependencies.get(viewKey);
        if (deps != null) {
            for (String depKey : deps) {
                // Find the view object for this dependency
                DbObject depView = viewMap.get(depKey);

                if (depView != null) {
                    topologicalSortViews(depView, viewMap, dependencies, visited, visiting, sorted);
                } else {
                    logger.debug("Dependency {} not found in current view set (might be a table or external view)", depKey);
                }
            }
        }

        visiting.remove(viewKey);
        visited.add(viewKey);

        // Add this view to the sorted list
        sorted.add(view);
        logger.debug("Added view {} to sorted list (position {})", viewKey, sorted.size());
    }

    /**
     * Orders database objects by dependency to ensure objects are created before they are referenced.
     * Dependency order:
     * 1. Tables without foreign keys (base tables)
     * 2. Tables with foreign keys (ordered by their dependencies)
     * 3. Scalar Types, Table Types - can be used in table columns
     * 4. Sequences - standalone objects
     * 5. Synonyms - aliases to other objects
     * 6. Functions, Procedures - depend on tables
     * 7. Views - depend on tables and functions (sorted by inter-view dependencies)
     * 8. Triggers - depend on tables
     */
    private List<DbObject> orderByDependency(List<DbObject> dbChanges) {
        List<DbObject> orderedList = new ArrayList<>();

        // Separate tables, views, and other objects
        List<DbObject> tables = dbChanges.stream()
                .filter(obj -> obj.type() == DbObjectType.TABLE)
                .toList();

        List<DbObject> views = dbChanges.stream()
                .filter(obj -> obj.type() == DbObjectType.VIEW)
                .toList();

        List<DbObject> nonTablesAndViews = dbChanges.stream()
                .filter(obj -> obj.type() != DbObjectType.TABLE && obj.type() != DbObjectType.VIEW)
                .sorted((a, b) -> {
                    int priorityA = getDependencyPriority(a.type());
                    int priorityB = getDependencyPriority(b.type());
                    return Integer.compare(priorityA, priorityB);
                })
                .toList();

        logger.info("Ordering dependencies: {} tables, {} views, {} other objects",
                   tables.size(), views.size(), nonTablesAndViews.size());

        // Order tables by foreign key dependencies
        List<DbObject> orderedTables = orderTablesByForeignKeys(tables);

        // Order views by their inter-view dependencies
        if (!views.isEmpty()) {
            logger.info("Analyzing view dependencies for {} views...", views.size());
            Map<String, Set<String>> viewDependencies = extractViewDependenciesFromSQL(views);

            if (!viewDependencies.isEmpty()) {
                logger.info("Found dependencies for {} views", viewDependencies.size());
                viewDependencies.forEach((view, deps) ->
                    logger.debug("View {} depends on: {}", view, deps)
                );
            } else {
                logger.info("No inter-view dependencies detected");
            }

            List<DbObject> orderedViews = sortViewsByDependency(views, viewDependencies);

            logger.info("View creation order:");
            for (int i = 0; i < orderedViews.size(); i++) {
                DbObject v = orderedViews.get(i);
                logger.info("  {}. {}.{}", i + 1, v.schema(), v.name());
            }

            // Combine: tables first, then other objects (by priority), then views (sorted by dependency)
            orderedList.addAll(orderedTables);
            orderedList.addAll(nonTablesAndViews);
            orderedList.addAll(orderedViews);
        } else {
            // No views, just combine tables and other objects
            orderedList.addAll(orderedTables);
            orderedList.addAll(nonTablesAndViews);
        }

        return orderedList;
    }

    /**
     * Orders tables by foreign key dependencies using topological sort.
     * Tables without foreign keys come first, then tables that reference them.
     */
    private List<DbObject> orderTablesByForeignKeys(List<DbObject> tables) {
        // Build dependency map: table -> list of tables it depends on (via FK)
        Map<String, List<String>> dependencies = new HashMap<>();
        Map<String, DbObject> tableMap = new HashMap<>();

        for (DbObject table : tables) {
            String tableKey = table.schema() + "." + table.name();
            tableMap.put(tableKey, table);
            dependencies.put(tableKey, extractForeignKeyDependencies(table));
        }

        // Topological sort
        List<DbObject> ordered = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        Set<String> visiting = new HashSet<>();

        for (String tableKey : tableMap.keySet()) {
            if (!visited.contains(tableKey)) {
                topologicalSort(tableKey, dependencies, tableMap, visited, visiting, ordered);
            }
        }

        return ordered;
    }

    /**
     * Extracts foreign key dependencies from a table definition.
     * Returns list of referenced tables in format "schema.table".
     */
    private List<String> extractForeignKeyDependencies(DbObject table) {
        if (table.definition() == null) {
            return List.of();
        }

        List<String> dependencies = new ArrayList<>();
        String definition = table.definition().toUpperCase();

        // Match: FOREIGN KEY (...) REFERENCES [schema].[table] or [table]
        // Pattern: REFERENCES followed by schema.table or just table
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
            "REFERENCES\\s+(?:\\[(\\w+)\\]\\.)?\\[(\\w+)\\]",
            java.util.regex.Pattern.CASE_INSENSITIVE
        );

        java.util.regex.Matcher matcher = pattern.matcher(definition);
        while (matcher.find()) {
            String schema = matcher.group(1);
            String tableName = matcher.group(2);

            // If schema not specified, assume dbo
            if (schema == null) {
                schema = "dbo";
            }

            String referencedTable = schema.toLowerCase() + "." + tableName.toLowerCase();
            dependencies.add(referencedTable);
        }

        return dependencies;
    }

    /**
     * Performs topological sort to order tables by dependencies.
     * Uses DFS with cycle detection.
     */
    private void topologicalSort(
            String tableKey,
            Map<String, List<String>> dependencies,
            Map<String, DbObject> tableMap,
            Set<String> visited,
            Set<String> visiting,
            List<DbObject> ordered) {

        if (visited.contains(tableKey)) {
            return;
        }

        if (visiting.contains(tableKey)) {
            // Circular dependency detected - log warning and continue
            logger.warn("Circular foreign key dependency detected involving table: {}", tableKey);
            return;
        }

        visiting.add(tableKey);

        // Visit dependencies first (referenced tables)
        List<String> deps = dependencies.get(tableKey);
        if (deps != null) {
            for (String dep : deps) {
                // Only process if the referenced table is in our change set
                if (tableMap.containsKey(dep)) {
                    topologicalSort(dep, dependencies, tableMap, visited, visiting, ordered);
                }
            }
        }

        visiting.remove(tableKey);
        visited.add(tableKey);

        // Add this table to the ordered list
        DbObject table = tableMap.get(tableKey);
        if (table != null) {
            ordered.add(table);
        }
    }

    /**
     * Returns the dependency priority for a database object type.
     * Lower numbers are processed first.
     */
    private int getDependencyPriority(DbObjectType type) {
        return switch (type) {
            case FULLTEXT_CATALOG -> 0;             // Full-Text catalogs must be created before tables/indexes
            case TABLE -> 1;                        // Tables first (handled separately with FK ordering)
            case SCALAR_TYPE, TABLE_TYPE -> 2;      // Types before functions that might use them
            case SEQUENCE -> 3;                     // Sequences are standalone
            case SYNONYM -> 4;                      // Synonyms reference other objects
            case FUNCTION, PROCEDURE -> 5;          // Functions & Procedures depend on tables
            case VIEW -> 6;                         // Views depend on tables and functions
            case TRIGGER -> 7;                      // Triggers depend on tables
        };
    }

    /**
     * Executes SQL script by splitting on GO batch separators.
     * This is necessary because SQL Server requires GO statements to be executed in separate batches,
     * but Spring's ResourceDatabasePopulator doesn't handle GO as a proper batch separator.
     */
    private void executeWithGoBatchSeparator(String sql) {
        if (sql == null || sql.trim().isEmpty()) {
            return;
        }

        // Parse SQL line by line to find GO batch separators
        // GO must be on its own line (with only whitespace) to be treated as a batch separator
        List<String> batches = new ArrayList<>();
        StringBuilder currentBatch = new StringBuilder();

        String[] lines = sql.split("\\r?\\n");
        for (String line : lines) {
            String trimmedLine = line.trim();

            // Check if this line is a GO statement (case-insensitive, standalone)
            if (trimmedLine.equalsIgnoreCase("GO")) {
                // Save current batch if not empty
                String batch = currentBatch.toString().trim();
                if (!batch.isEmpty()) {
                    batches.add(batch);
                }
                currentBatch = new StringBuilder();
            } else {
                // Add line to current batch
                currentBatch.append(line).append("\n");
            }
        }

        // Don't forget the last batch
        String lastBatch = currentBatch.toString().trim();
        if (!lastBatch.isEmpty()) {
            batches.add(lastBatch);
        }

        // Execute each batch separately using direct JDBC execution
        // This avoids Spring's ResourceDatabasePopulator which tries to parse statements
        // on semicolons, breaking function/procedure bodies that contain semicolons
        for (String batch : batches) {
            logger.debug("Executing SQL batch ({} chars): {}",
                batch.length(),
                batch.substring(0, Math.min(100, batch.length())).replace("\n", " ") + "...");

            try {
                // Execute the batch directly using JdbcTemplate
                // This sends the entire batch to SQL Server as-is, without parsing
                jdbcTemplate.execute(batch);

                logger.debug("Successfully executed SQL batch");
            } catch (Exception e) {
                logger.error("Failed to execute SQL batch ({} chars): {}",
                    batch.length(),
                    batch.length() <= 500 ? batch : batch.substring(0, 500) + "...");
                throw new RuntimeException("Failed to execute SQL batch: " + e.getMessage(), e);
            }
        }
    }


    /**
     * Retrieves Full-Text catalogs from the database.
     * Full-Text catalogs must be created before Full-Text indexes.
     */
    private List<DbObject> getFullTextCatalogs() {
        String catalogQuery = """
                SELECT
                    name,
                    is_default,
                    is_accent_sensitivity_on
                FROM sys.fulltext_catalogs
                WHERE name NOT IN ('', 'default')
                ORDER BY name
                """;

        return jdbcTemplate.query(catalogQuery, (rs, _) -> {
            String name = rs.getString("name");
            boolean isDefault = rs.getBoolean("is_default");
            boolean isAccentSensitive = rs.getBoolean("is_accent_sensitivity_on");

            StringBuilder definition = new StringBuilder();
            definition.append("CREATE FULLTEXT CATALOG [").append(name).append("]");

            if (isAccentSensitive) {
                definition.append(" WITH ACCENT_SENSITIVITY = ON");
            } else {
                definition.append(" WITH ACCENT_SENSITIVITY = OFF");
            }

            if (isDefault) {
                definition.append(" AS DEFAULT");
            }

            definition.append(";");

            return new DbObject(
                    "dbo",  // Full-Text catalogs are database-level objects
                    name.toLowerCase(),
                    DbObjectType.FULLTEXT_CATALOG,
                    definition.toString()
            );
        });
    }

    /**
     * Builds a DROP statement for a database object.
     */
    private String buildDropQuery(DbObject obj) {
        // TABLE_TYPE and SCALAR_TYPE need "DROP TYPE" syntax
        // SEQUENCE needs "DROP SEQUENCE" syntax
        // TABLE needs "DROP TABLE" syntax
        // FULLTEXT_CATALOG needs "DROP FULLTEXT CATALOG" syntax
        String dropKeyword = switch (obj.type()) {
            case TABLE_TYPE, SCALAR_TYPE -> "TYPE";
            case SEQUENCE -> "SEQUENCE";
            case TABLE -> "TABLE";
            case FULLTEXT_CATALOG -> "FULLTEXT CATALOG";
            default -> obj.type().toString();
        };

        return """
            DROP %s IF EXISTS [%s].[%s];
            GO
            """.formatted(dropKeyword, obj.schema(), obj.name());
    }
}

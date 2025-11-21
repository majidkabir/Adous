package app.majid.adous.db.service;

import app.majid.adous.synchronizer.db.DatabaseService;
import app.majid.adous.synchronizer.model.DbObject;
import app.majid.adous.synchronizer.model.DbObjectType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

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
        List<DbObject> objects = jdbcTemplate.query(buildGetObjectsQuery(), (rs, rowNum) ->
                new DbObject(
                        rs.getString("schema_name").toLowerCase(),
                        rs.getString("name").toLowerCase(),
                        DbObjectType.valueOf(rs.getString("object_type")),
                        rs.getString("definition")
                )
        );

        // Get synonyms
        objects.addAll(getSynonyms());

        // Get table types
        objects.addAll(getTableTypes());

        // Get sequences
        objects.addAll(getSequences());

        // Get scalar types
        objects.addAll(getScalarTypes());

        // Get tables
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

        List<DbObject> synonyms = jdbcTemplate.query(synonymQuery, (rs, rowNum) -> {
            String schemaName = rs.getString("schema_name").toLowerCase();
            String name = rs.getString("name").toLowerCase();
            String targetObject = rs.getString("target_object");

            String definition = String.format(
                    "CREATE SYNONYM [%s].[%s] FOR %s;%nGO",
                    schemaName, name, targetObject
            );

            return new DbObject(schemaName, name, DbObjectType.SYNONYM, definition);
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

        List<DbObject> tableTypes = jdbcTemplate.query(tableTypeQuery, (rs, rowNum) -> {
            String schemaName = rs.getString("schema_name").toLowerCase();
            String name = rs.getString("name").toLowerCase();
            int objectId = rs.getInt("object_id");

            String definition = buildTableTypeDefinition(schemaName, name, objectId);

            return new DbObject(schemaName, name, DbObjectType.TABLE_TYPE, definition);
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

        List<String> columns = jdbcTemplate.query(columnQuery, (rs, rowNum) -> {
            String columnName = rs.getString("column_name");
            String dataType = rs.getString("data_type");
            int maxLength = rs.getInt("max_length");
            int precision = rs.getInt("precision");
            int scale = rs.getInt("scale");
            boolean isNullable = rs.getBoolean("is_nullable");
            boolean isIdentity = rs.getBoolean("is_identity");
            int identitySeed = rs.getInt("identity_seed");
            int identityIncrement = rs.getInt("identity_increment");

            StringBuilder columnDef = new StringBuilder();
            columnDef.append("[").append(columnName).append("] ");
            columnDef.append(formatDataType(dataType, maxLength, precision, scale));

            if (isIdentity) {
                columnDef.append(" IDENTITY(").append(identitySeed).append(",")
                        .append(identityIncrement).append(")");
            }

            if (!isNullable) {
                columnDef.append(" NOT NULL");
            } else {
                columnDef.append(" NULL");
            }

            return columnDef.toString();
        }, objectId);

        StringBuilder definition = new StringBuilder();
        definition.append("CREATE TYPE [").append(schemaName).append("].[").append(typeName).append("] AS TABLE\n(\n");
        definition.append("    ").append(String.join(",\n    ", columns));
        definition.append("\n);\nGO");

        return definition.toString();
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

        List<DbObject> sequences = jdbcTemplate.query(sequenceQuery, (rs, rowNum) -> {
            String schemaName = rs.getString("schema_name").toLowerCase();
            String name = rs.getString("name").toLowerCase();
            long startValue = rs.getLong("start_value");
            long increment = rs.getLong("increment");
            long minimumValue = rs.getLong("minimum_value");
            long maximumValue = rs.getLong("maximum_value");
            boolean isCycling = rs.getBoolean("is_cycling");
            boolean isCached = rs.getBoolean("is_cached");
            int cacheSize = rs.getInt("cache_size");
            String dataType = rs.getString("data_type");

            StringBuilder definition = new StringBuilder();
            definition.append("CREATE SEQUENCE [").append(schemaName).append("].[").append(name).append("]\n");
            definition.append("    AS ").append(dataType).append("\n");
            definition.append("    START WITH ").append(startValue).append("\n");
            definition.append("    INCREMENT BY ").append(increment).append("\n");
            definition.append("    MINVALUE ").append(minimumValue).append("\n");
            definition.append("    MAXVALUE ").append(maximumValue).append("\n");

            if (isCycling) {
                definition.append("    CYCLE\n");
            } else {
                definition.append("    NO CYCLE\n");
            }

            if (isCached && cacheSize > 0) {
                definition.append("    CACHE ").append(cacheSize);
            } else {
                definition.append("    NO CACHE");
            }

            definition.append(";\nGO");

            return new DbObject(schemaName, name, DbObjectType.SEQUENCE, definition.toString());
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

        List<DbObject> scalarTypes = jdbcTemplate.query(scalarTypeQuery, (rs, rowNum) -> {
            String schemaName = rs.getString("schema_name").toLowerCase();
            String name = rs.getString("name").toLowerCase();
            String baseType = rs.getString("base_type");
            int maxLength = rs.getInt("max_length");
            int precision = rs.getInt("precision");
            int scale = rs.getInt("scale");
            boolean isNullable = rs.getBoolean("is_nullable");
            String defaultValue = rs.getString("default_value");
            String ruleName = rs.getString("rule_name");

            StringBuilder definition = new StringBuilder();
            definition.append("CREATE TYPE [").append(schemaName).append("].[").append(name).append("]\n");
            definition.append("    FROM ").append(formatDataType(baseType, maxLength, precision, scale));

            if (!isNullable) {
                definition.append(" NOT NULL");
            }

            definition.append(";\nGO");

            return new DbObject(schemaName, name, DbObjectType.SCALAR_TYPE, definition.toString());
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

        List<DbObject> tables = jdbcTemplate.query(tableQuery, (rs, rowNum) -> {
            String schemaName = rs.getString("schema_name").toLowerCase();
            String tableName = rs.getString("table_name").toLowerCase();
            int objectId = rs.getInt("object_id");

            String definition = buildCreateTableDefinition(schemaName, tableName, objectId);

            return new DbObject(schemaName, tableName, DbObjectType.TABLE, definition);
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
        StringBuilder definition = new StringBuilder();
        definition.append("CREATE TABLE [").append(schemaName).append("].[").append(tableName).append("]\n(\n");

        // Get columns
        List<String> columnDefinitions = getTableColumns(objectId);
        definition.append("    ").append(String.join(",\n    ", columnDefinitions));

        // Get constraints (PK, FK, UNIQUE, CHECK)
        List<String> constraints = getTableConstraints(schemaName, tableName, objectId);
        if (!constraints.isEmpty()) {
            definition.append(",\n    ").append(String.join(",\n    ", constraints));
        }

        definition.append("\n);\nGO\n");

        // Get indexes (non-constraint indexes)
        List<String> indexes = getTableIndexes(schemaName, tableName, objectId);
        for (String index : indexes) {
            definition.append("\n").append(index).append("\nGO");
        }

        return definition.toString();
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

        return jdbcTemplate.query(columnQuery, (rs, rowNum) -> {
            String columnName = rs.getString("column_name");
            String dataType = rs.getString("data_type");
            int maxLength = rs.getInt("max_length");
            int precision = rs.getInt("precision");
            int scale = rs.getInt("scale");
            boolean isNullable = rs.getBoolean("is_nullable");
            boolean isIdentity = rs.getBoolean("is_identity");
            int identitySeed = rs.getInt("identity_seed");
            int identityIncrement = rs.getInt("identity_increment");
            String defaultValue = rs.getString("default_value");

            StringBuilder columnDef = new StringBuilder();
            columnDef.append("[").append(columnName).append("] ");
            columnDef.append(formatDataType(dataType, maxLength, precision, scale));

            if (isIdentity) {
                columnDef.append(" IDENTITY(").append(identitySeed).append(",")
                        .append(identityIncrement).append(")");
            }

            if (!isNullable) {
                columnDef.append(" NOT NULL");
            } else {
                columnDef.append(" NULL");
            }

            if (defaultValue != null && !isIdentity) {
                columnDef.append(" DEFAULT ").append(defaultValue);
            }

            return columnDef.toString();
        }, objectId);
    }

    /**
     * Gets constraint definitions for a table (PK, FK, UNIQUE, CHECK).
     */
    private List<String> getTableConstraints(String schemaName, String tableName, int objectId) {
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
     */
    private List<String> getTableIndexes(String schemaName, String tableName, int objectId) {
        String indexQuery = """
                SELECT
                    i.name AS index_name,
                    i.is_unique,
                    STRING_AGG(c.name, ', ') WITHIN GROUP (ORDER BY ic.key_ordinal) AS columns
                FROM sys.indexes i
                JOIN sys.index_columns ic ON i.object_id = ic.object_id AND i.index_id = ic.index_id
                JOIN sys.columns c ON ic.object_id = c.object_id AND ic.column_id = c.column_id
                WHERE i.object_id = ?
                    AND i.is_primary_key = 0
                    AND i.is_unique_constraint = 0
                    AND i.type > 0
                GROUP BY i.name, i.is_unique
                """;

        return jdbcTemplate.query(indexQuery, (rs, rowNum) -> {
            String indexName = rs.getString("index_name");
            boolean isUnique = rs.getBoolean("is_unique");
            String columns = rs.getString("columns");

            String[] columnArray = columns.split(", ");
            String formattedColumns = Arrays.stream(columnArray)
                    .map(col -> "[" + col + "]")
                    .reduce((a, b) -> a + ", " + b)
                    .orElse("");

            String uniqueKeyword = isUnique ? "UNIQUE " : "";
            return String.format("CREATE %sINDEX [%s] ON [%s].[%s] (%s);",
                    uniqueKeyword, indexName, schemaName, tableName, formattedColumns);
        }, objectId);
    }

    /**
     * Formats a data type with its length, precision, and scale.
     *
     * @param dataType The base data type
     * @param maxLength The maximum length (for string types)
     * @param precision The precision (for numeric types)
     * @param scale The scale (for numeric types)
     * @return Formatted data type string
     */
    private String formatDataType(String dataType, int maxLength, int precision, int scale) {
        return switch (dataType.toLowerCase()) {
            case "varchar", "char", "varbinary", "binary" -> {
                int actualLength = maxLength == -1 ? -1 : maxLength;
                yield dataType + "(" + (actualLength == -1 ? "MAX" : String.valueOf(actualLength)) + ")";
            }
            case "nvarchar", "nchar" -> {
                int actualLength = maxLength == -1 ? -1 : maxLength / 2;
                yield dataType + "(" + (actualLength == -1 ? "MAX" : String.valueOf(actualLength)) + ")";
            }
            case "decimal", "numeric" ->
                    dataType + "(" + precision + ", " + scale + ")";
            case "datetime2", "time", "datetimeoffset" ->
                    scale > 0 ? dataType + "(" + scale + ")" : dataType;
            default -> dataType;
        };
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
     */
    private void applyObjectChanges(List<DbObject> dbChanges) {
        ResourceDatabasePopulator populator = new ResourceDatabasePopulator();
        populator.setSeparator("GO");

        dbChanges.forEach(change -> {
            logger.debug("Processing change for {}.{} ({})",
                    change.schema(), change.name(), change.type());

            if (change.type() == DbObjectType.TABLE) {
                // For tables, use ALTER script generator to preserve data
                if (change.definition() != null) {
                    String alterScript = tableAlterScriptGenerator.generateAlterScript(change);
                    if (!alterScript.isEmpty()) {
                        populator.addScript(toResource(alterScript));
                    }
                } else {
                    // Table deletion - drop the table
                    populator.addScript(toResource(buildDropQuery(change)));
                }
            } else {
                // For non-table objects, use traditional DROP/CREATE pattern
                populator.addScript(toResource(buildDropQuery(change)));

                if (change.definition() != null) {
                    populator.addScript(toResource(change.definition()));
                }
            }
        });

        populator.execute(Objects.requireNonNull(jdbcTemplate.getDataSource(),
                "DataSource must not be null"));
    }

    /**
     * Builds a DROP statement for a database object.
     */
    private String buildDropQuery(DbObject obj) {
        // TABLE_TYPE and SCALAR_TYPE need "DROP TYPE" syntax
        // SEQUENCE needs "DROP SEQUENCE" syntax
        // TABLE needs "DROP TABLE" syntax
        String dropKeyword = switch (obj.type()) {
            case TABLE_TYPE, SCALAR_TYPE -> "TYPE";
            case SEQUENCE -> "SEQUENCE";
            case TABLE -> "TABLE";
            default -> obj.type().toString();
        };

        return """
            DROP %s IF EXISTS [%s].[%s];
            GO
            """.formatted(dropKeyword, obj.schema(), obj.name());
    }

    /**
     * Converts a string to a Spring Resource for use with ResourceDatabasePopulator.
     */
    private Resource toResource(String content) {
        return new InputStreamResource(
                new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)));
    }
}

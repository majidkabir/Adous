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
import java.util.EnumSet;
import java.util.Comparator;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;

import java.util.Map;
import java.util.Optional;
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
    private final SequenceAlterScriptGenerator sequenceAlterScriptGenerator;
    private final TableDefinitionReader tableDefinitionReader;

    /** Module types that support CREATE OR ALTER. */
    private static final Set<DbObjectType> MODULE_TYPES = EnumSet.of(
            DbObjectType.PROCEDURE, DbObjectType.FUNCTION, DbObjectType.VIEW, DbObjectType.TRIGGER);

    public MSSQLDatabaseService(JdbcTemplate jdbcTemplate, TableAlterScriptGenerator tableAlterScriptGenerator,
                                SequenceAlterScriptGenerator sequenceAlterScriptGenerator) {
        this.jdbcTemplate = jdbcTemplate;
        this.tableAlterScriptGenerator = tableAlterScriptGenerator;
        this.sequenceAlterScriptGenerator = sequenceAlterScriptGenerator;
        this.tableDefinitionReader = new TableDefinitionReader(jdbcTemplate);
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
                        // Objects applied with CREATE OR ALTER store that text; keep the
                        // canonical CREATE form so the repository does not churn
                        ModuleDefinitionRewriter.toCanonicalCreate(rs.getString("definition"))
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

    @Override
    public List<String> findDataLossOperations(List<DbObject> dbChanges) {
        if (dbChanges == null) {
            return List.of();
        }
        return dbChanges.stream()
                .filter(change -> change.type() == DbObjectType.TABLE)
                .flatMap(change -> tableAlterScriptGenerator.findDataLossStatements(change).stream())
                .toList();
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
            // cache_size is NULL when the sequence uses the default cache size
            Integer cacheSize = rs.getObject("cache_size", Integer.class);
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
                    cacheClause(cached, cacheSize)
            );

            return new DbObject(schema, name, DbObjectType.SEQUENCE, definition);
        });

        logger.debug("Retrieved {} sequences", sequences.size());
        return sequences;
    }

    /**
     * {@code CACHE n} for an explicit size, {@code CACHE} for the server default size,
     * {@code NO CACHE} when caching is off.
     */
    static String cacheClause(boolean cached, Integer cacheSize) {
        if (!cached) {
            return "NO CACHE";
        }
        return cacheSize != null && cacheSize > 0 ? "CACHE " + cacheSize : "CACHE";
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

            String definition = tableDefinitionReader.read(schema, table, objectId).toDefinition();

            return new DbObject(schema, table, DbObjectType.TABLE, definition);
        });

        logger.debug("Retrieved {} tables", tables.size());
        return tables;
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
     * For procedures, functions, views and triggers: uses CREATE OR ALTER so the object keeps
     * its id and therefore its permissions (falls back to DROP + CREATE when that is impossible)
     * For existing SEQUENCEs: uses ALTER SEQUENCE so the current value is kept
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
        // Foreign keys of other tables dropped by a table script, by lower-case "schema.table"
        Map<String, List<TableAlterScriptGenerator.ReferencingForeignKey>> pendingForeignKeys = new LinkedHashMap<>();

        orderedChanges.forEach(change -> {
            if (change.type() == DbObjectType.TABLE) {
                // A referencing table changed after the drop is synced from its own definition,
                // which re-adds the foreign key if it still has it
                pendingForeignKeys.remove(tableKey(change.schema(), change.name()));
            }
            String sql;
            Optional<String> inPlaceScript = change.definition() == null || change.type() == DbObjectType.TABLE
                    ? Optional.empty()
                    : change.type() == DbObjectType.SEQUENCE
                        ? sequenceAlterScriptGenerator.generateAlterScript(change)
                        : createOrAlterScript(change);
            if (change.type() == DbObjectType.TABLE) {
                // For tables, use ALTER script generator to preserve data
                if (change.definition() != null) {
                    TableAlterScriptGenerator.TableScript tableScript = tableAlterScriptGenerator.generate(change);
                    executeWithGoBatchSeparator(tableScript.script());
                    tableScript.droppedReferencingForeignKeys().forEach(fk -> pendingForeignKeys
                            .computeIfAbsent(tableKey(fk.schema(), fk.table()), k -> new ArrayList<>()).add(fk));
                } else {
                    // Table deletion - drop the table
                    sql = buildDropQuery(change);
                    executeWithGoBatchSeparator(sql);
                }
            } else if (inPlaceScript.isPresent()) {
                executeWithGoBatchSeparator(inPlaceScript.get());
            } else {
                // For other non-table objects, use traditional DROP/CREATE pattern
                if (change.definition() != null && MODULE_TYPES.contains(change.type())) {
                    logger.warn("Recreating {} [{}].[{}] with DROP + CREATE; existing permissions on it will be lost",
                            change.type(), change.schema(), change.name());
                }
                StringBuilder sqlBuilder = new StringBuilder();
                sqlBuilder.append(buildDropQuery(change));

                if (change.definition() != null) {
                    sqlBuilder.append(change.definition());
                }

                executeWithGoBatchSeparator(sqlBuilder.toString());
            }
        });

        // Foreign keys of tables that are not part of this sync
        pendingForeignKeys.values().stream().flatMap(List::stream).forEach(fk -> {
            try {
                executeWithGoBatchSeparator(fk.addStatement());
            } catch (RuntimeException e) {
                throw new IllegalStateException(("Could not re-create foreign key [%s] on [%s].[%s] after changing the "
                        + "columns it references; if its columns must change too, change [%s].[%s] in the same commit: %s")
                        .formatted(fk.name(), fk.schema(), fk.table(), fk.schema(), fk.table(), e.getMessage()), e);
            }
        });
    }

    private static String tableKey(String schema, String name) {
        return (schema + "." + name).toLowerCase();
    }

    /**
     * Builds a {@code CREATE OR ALTER} script for procedures, functions, views and triggers.
     * Empty when the object must be dropped and recreated instead: other object types, a
     * definition without a recognizable CREATE statement, or a function whose kind
     * (scalar / inline table / multi-statement table) differs from the existing one,
     * which SQL Server cannot ALTER.
     */
    private Optional<String> createOrAlterScript(DbObject change) {
        if (!MODULE_TYPES.contains(change.type())) {
            return Optional.empty();
        }
        if (change.type() == DbObjectType.FUNCTION && !canAlterFunctionInPlace(change)) {
            return Optional.empty();
        }
        return ModuleDefinitionRewriter.toCreateOrAlter(change.definition());
    }

    private boolean canAlterFunctionInPlace(DbObject function) {
        List<String> existingTypes = jdbcTemplate.queryForList("""
                SELECT RTRIM(o.type)
                FROM sys.objects o
                JOIN sys.schemas s ON o.schema_id = s.schema_id
                WHERE s.name = ? AND o.name = ?
                """, String.class, function.schema(), function.name());
        if (existingTypes.isEmpty()) {
            return true; // new function: CREATE OR ALTER simply creates it
        }
        Optional<ModuleDefinitionRewriter.FunctionKind> existingKind = switch (existingTypes.getFirst()) {
            case "FN" -> Optional.of(ModuleDefinitionRewriter.FunctionKind.SCALAR);
            case "IF" -> Optional.of(ModuleDefinitionRewriter.FunctionKind.INLINE_TABLE);
            case "TF" -> Optional.of(ModuleDefinitionRewriter.FunctionKind.MULTI_STATEMENT_TABLE);
            default -> Optional.empty(); // CLR or a different object type with the same name
        };
        Optional<ModuleDefinitionRewriter.FunctionKind> newKind =
                ModuleDefinitionRewriter.functionKind(function.definition());
        return existingKind.isPresent() && existingKind.equals(newKind);
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
     * Orders changes so every object exists before something that needs it:
     * creates and updates first, in dependency order, then drops in the reverse order
     * (so dependent objects are dropped before what they depend on).
     */
    private List<DbObject> orderByDependency(List<DbObject> dbChanges) {
        List<DbObject> upserts = dbChanges.stream().filter(o -> o.definition() != null).toList();
        List<DbObject> drops = dbChanges.stream().filter(o -> o.definition() == null).toList();

        List<DbObject> ordered = new ArrayList<>(orderForCreation(upserts, this::extractForeignKeyDependencies));

        if (!drops.isEmpty()) {
            // Dropped tables have no definition; take their foreign keys from the database
            Map<String, List<String>> dbForeignKeys = currentForeignKeys();
            List<DbObject> dropOrder = new ArrayList<>(orderForCreation(drops,
                    t -> dbForeignKeys.getOrDefault(t.schema() + "." + t.name(), List.of())));
            Collections.reverse(dropOrder);
            ordered.addAll(dropOrder);
        }

        logger.info("Apply order: {}", ordered);
        return ordered;
    }

    /**
     * Sorts objects into creation order: by type priority (see {@link #getDependencyPriority}),
     * tables by foreign keys and views by references between them.
     */
    private List<DbObject> orderForCreation(List<DbObject> objects,
                                            java.util.function.Function<DbObject, List<String>> tableDependencies) {
        List<DbObject> ordered = new ArrayList<>();
        objects.stream()
                .map(DbObject::type)
                .distinct()
                .sorted(Comparator.comparingInt(this::getDependencyPriority))
                .forEach(type -> {
                    List<DbObject> ofType = objects.stream().filter(o -> o.type() == type).toList();
                    if (type == DbObjectType.TABLE) {
                        ordered.addAll(orderTablesByForeignKeys(ofType, tableDependencies));
                    } else if (type == DbObjectType.VIEW && ofType.stream().allMatch(v -> v.definition() != null)) {
                        ordered.addAll(sortViewsByDependency(ofType, extractViewDependenciesFromSQL(ofType)));
                    } else {
                        ordered.addAll(ofType);
                    }
                });
        return ordered;
    }

    /**
     * Foreign keys between user tables in the current database, as
     * "schema.table" -> referenced "schema.table" (lower case).
     */
    private Map<String, List<String>> currentForeignKeys() {
        Map<String, List<String>> result = new HashMap<>();
        jdbcTemplate.query("""
                SELECT LOWER(SCHEMA_NAME(p.schema_id) + '.' + p.name) AS parent_table,
                       LOWER(SCHEMA_NAME(r.schema_id) + '.' + r.name) AS referenced_table
                FROM sys.foreign_keys fk
                JOIN sys.tables p ON fk.parent_object_id = p.object_id
                JOIN sys.tables r ON fk.referenced_object_id = r.object_id
                WHERE fk.parent_object_id <> fk.referenced_object_id
                """, rs -> {
            result.computeIfAbsent(rs.getString("parent_table"), k -> new ArrayList<>())
                    .add(rs.getString("referenced_table"));
        });
        return result;
    }

    /**
     * Orders tables by foreign key dependencies using topological sort.
     * Tables without foreign keys come first, then tables that reference them.
     */
    private List<DbObject> orderTablesByForeignKeys(List<DbObject> tables,
                                                    java.util.function.Function<DbObject, List<String>> tableDependencies) {
        // Build dependency map: table -> list of tables it depends on (via FK)
        Map<String, List<String>> dependencies = new HashMap<>();
        Map<String, DbObject> tableMap = new java.util.LinkedHashMap<>();

        for (DbObject table : tables) {
            String tableKey = table.schema() + "." + table.name();
            tableMap.put(tableKey, table);
            dependencies.put(tableKey, tableDependencies.apply(table));
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
    int getDependencyPriority(DbObjectType type) {
        return switch (type) {
            case FULLTEXT_CATALOG -> 0;  // Full-text catalogs before the tables whose indexes use them
            case SCALAR_TYPE -> 1;       // Scalar types before table types, tables and modules that use them
            case TABLE_TYPE -> 2;        // Table types may use scalar types
            case SEQUENCE -> 3;          // Sequences before tables whose defaults use NEXT VALUE FOR
            case TABLE -> 4;             // Tables (sorted by foreign keys)
            case SYNONYM -> 5;           // Synonyms usually point at tables
            case FUNCTION -> 6;          // Functions depend on tables; views often use them
            case VIEW -> 7;              // Views depend on tables, functions and other views (sorted)
            case PROCEDURE -> 8;         // Procedures may use all of the above
            case TRIGGER -> 9;           // Triggers last: on tables or views (INSTEAD OF)
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
                    defaultSchema,  // Full-Text catalogs are database-level objects
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

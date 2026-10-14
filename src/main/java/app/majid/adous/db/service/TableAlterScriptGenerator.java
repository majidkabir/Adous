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
    private final TableDefinitionReader tableDefinitionReader;

    public TableAlterScriptGenerator(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.tableDefinitionReader = new TableDefinitionReader(jdbcTemplate);
    }

    /** What the column/PK statements dropped, so it can be re-created afterwards. */
    private static final class AlterContext {
        final Set<String> droppedIndexes = new HashSet<>();                  // lower-case names
        final List<String[]> droppedReferencingForeignKeys = new ArrayList<>(); // [schema, table, fk name]
        Set<String> alteredColumns = Set.of();                               // lower-case; type or nullability changes
        boolean recreated;                                                   // whole table dropped and recreated
        boolean primaryKeyClauseChanged;                                     // e.g. renamed or clustered -> nonclustered
        boolean primaryKeyColumnAltered;                                     // ALTER COLUMN on a primary key column

        /** Records a dropped foreign key once; returns false if it was already recorded. */
        boolean addDroppedReferencingForeignKey(String[] fk) {
            boolean known = droppedReferencingForeignKeys.stream().anyMatch(existing ->
                    existing[0].equalsIgnoreCase(fk[0]) && existing[1].equalsIgnoreCase(fk[1])
                            && existing[2].equalsIgnoreCase(fk[2]));
            if (!known) {
                droppedReferencingForeignKeys.add(fk);
            }
            return !known;
        }
    }

    /**
     * A foreign key of another table that the script for this table drops (because the
     * referenced key changes), with the statement that re-creates it. The statement does
     * nothing if the foreign key or its table no longer exists.
     */
    public record ReferencingForeignKey(String schema, String table, String name, String addStatement) {
    }

    /**
     * Script for one table, without re-creating the foreign keys of other tables that it drops.
     * The caller re-adds those once the referencing tables are in their final shape.
     */
    public record TableScript(String script, List<ReferencingForeignKey> droppedReferencingForeignKeys) {
    }

    /**
     * Generates ALTER TABLE script to sync database with Git definition.
     * If table doesn't exist in database, returns the CREATE TABLE script as-is.
     *
     * @param tableObject DbObject containing schema, name, and CREATE TABLE definition from Git
     * @return SQL script (CREATE or ALTER statements) to apply to database
     */
    public String generateAlterScript(DbObject tableObject) {
        TableScript tableScript = generate(tableObject);
        StringBuilder script = new StringBuilder(tableScript.script());
        tableScript.droppedReferencingForeignKeys()
                .forEach(fk -> script.append(fk.addStatement()).append("\nGO\n"));
        return script.toString();
    }

    /**
     * Like {@link #generateAlterScript(DbObject)}, but foreign keys of other tables dropped by
     * the script are returned instead of re-added. When the referencing columns change too
     * (e.g. both sides widened from int to bigint), the foreign key can only be re-added after
     * the referencing table has been altered.
     */
    public TableScript generate(DbObject tableObject) {
        String schema = tableObject.schema();
        String name = tableObject.name();
        logger.debug("Generating alter script for table {}.{}", schema, name);

        Optional<TableDefinitionReader.TableModel> current = tableDefinitionReader.read(schema, name);
        if (current.isEmpty()) {
            logger.debug("Table {}.{} doesn't exist, using CREATE TABLE script", schema, name);
            return new TableScript(tableObject.definition(), List.of());
        }

        String gitText = tableObject.definition();
        TableDefinition gitDefinition = parseCreateTableDefinition(gitText);
        TableDefinition dbDefinition = getCurrentTableDefinition(schema, name);

        AlterContext ctx = new AlterContext();
        String dbPkClause = current.get().constraints().stream()
                .filter(c -> c.kind() == TableDefinitionReader.ConstraintKind.PRIMARY_KEY)
                .map(TableDefinitionReader.Constraint::clause).findFirst().orElse(null);
        String gitPkClause = parseNamedConstraints(gitText).values().stream()
                .filter(clause -> PRIMARY_KEY_CLAUSE.matcher(clause).find()).findFirst().orElse(null);
        ctx.primaryKeyClauseChanged = dbPkClause != null && gitPkClause != null && !sameSql(dbPkClause, gitPkClause);
        ctx.alteredColumns = alteredColumns(dbDefinition, gitDefinition);
        ctx.primaryKeyColumnAltered = dbDefinition.primaryKeyColumns.stream().anyMatch(ctx.alteredColumns::contains);

        // Foreign keys (of any table) that reference an altered column block the ALTER COLUMN
        // and the drop of the key they reference, so they go first
        List<String> statements = new ArrayList<>();
        for (String[] fk : getForeignKeysReferencingColumns(schema, name, ctx.alteredColumns)) {
            if (ctx.addDroppedReferencingForeignKey(fk)) {
                statements.add("ALTER TABLE [%s].[%s] DROP CONSTRAINT IF EXISTS [%s];".formatted(fk[0], fk[1], fk[2]));
            }
        }

        List<String> alterStatements = generateAlterStatements(schema, name, dbDefinition, gitDefinition, gitText, ctx);

        if (ctx.recreated) {
            // DROP TABLE + the full Git definition (columns, constraints, indexes)
            statements.addAll(alterStatements);
        } else {
            ObjectChanges changes = diffConstraintsDefaultsAndIndexes(
                    schema, name, current.get(), gitText, dbDefinition, gitDefinition, ctx);
            statements.addAll(changes.before());
            statements.addAll(alterStatements);
            statements.addAll(changes.after());
        }
        List<ReferencingForeignKey> referencingForeignKeys = referencingForeignKeysToReadd(schema, name, ctx);

        if (statements.isEmpty()) {
            logger.debug("No changes needed for table {}.{}", schema, name);
        }

        StringBuilder script = new StringBuilder();
        statements.forEach(stmt -> script.append(stmt).append("\nGO\n"));
        return new TableScript(script.toString(), referencingForeignKeys);
    }

    /** Lower-case names of columns whose type or nullability changes (identity columns are not altered). */
    private static Set<String> alteredColumns(TableDefinition dbDef, TableDefinition gitDef) {
        Set<String> altered = new HashSet<>();
        for (Map.Entry<String, ColumnDefinition> entry : gitDef.columns.entrySet()) {
            ColumnDefinition dbColumn = dbDef.columns.get(entry.getKey());
            ColumnDefinition gitColumn = entry.getValue();
            if (dbColumn != null && !dbColumn.isIdentity && !gitColumn.isIdentity
                    && (!dbColumn.dataType.equalsIgnoreCase(gitColumn.dataType) || dbColumn.isNullable != gitColumn.isNullable)) {
                altered.add(entry.getKey());
            }
        }
        return altered;
    }

    private record ObjectChanges(List<String> before, List<String> after) {
    }

    /**
     * Compares the named constraints (UNIQUE, FOREIGN KEY, CHECK), column defaults and indexes
     * of the database table with the Git definition.
     * <ul>
     *   <li>{@code before}: drop what was removed or changed, and what blocks an ALTER COLUMN
     *       (constraints and defaults on altered columns); runs before the column statements.</li>
     *   <li>{@code after}: add what is new or changed, and re-create what was dropped above
     *       or by the column statements (indexes on altered columns).</li>
     * </ul>
     */
    private ObjectChanges diffConstraintsDefaultsAndIndexes(String schema, String name,
                                                            TableDefinitionReader.TableModel current, String gitText,
                                                            TableDefinition dbDef, TableDefinition gitDef,
                                                            AlterContext ctx) {
        List<String> before = new ArrayList<>();
        List<String> after = new ArrayList<>();
        String table = "[%s].[%s]".formatted(schema, name);

        Set<String> alteredColumns = ctx.alteredColumns;
        Set<String> selfReferencingDropped = new HashSet<>();
        for (String[] fk : ctx.droppedReferencingForeignKeys) {
            if (fk[0].equalsIgnoreCase(schema) && fk[1].equalsIgnoreCase(name)) {
                selfReferencingDropped.add(fk[2].toLowerCase());
            }
        }

        // Named UNIQUE / FOREIGN KEY / CHECK constraints (the primary key is handled by the column statements)
        Map<String, String> gitConstraints = parseNamedConstraints(gitText);
        Map<String, TableDefinitionReader.Constraint> dbConstraints = new LinkedHashMap<>();
        for (TableDefinitionReader.Constraint constraint : current.constraints()) {
            if (constraint.kind() != TableDefinitionReader.ConstraintKind.PRIMARY_KEY) {
                dbConstraints.put(constraint.name().toLowerCase(), constraint);
            }
        }
        gitConstraints.keySet().removeIf(key -> PRIMARY_KEY_CLAUSE.matcher(gitConstraints.get(key)).find());

        List<String> addConstraints = new ArrayList<>();
        List<String> addForeignKeys = new ArrayList<>();
        dbConstraints.forEach((key, constraint) -> {
            String gitClause = gitConstraints.get(key);
            boolean blocksAlter = referencesAny(constraint.clause(), alteredColumns);
            boolean droppedForPrimaryKey = selfReferencingDropped.contains(constraint.dbName().toLowerCase());
            boolean changed = gitClause == null || !sameSql(gitClause, constraint.clause());
            if (changed || blocksAlter) {
                before.add("ALTER TABLE %s DROP CONSTRAINT IF EXISTS [%s];".formatted(table, constraint.dbName()));
            }
            if (gitClause != null && (changed || blocksAlter || droppedForPrimaryKey)) {
                (isForeignKey(gitClause) ? addForeignKeys : addConstraints)
                        .add("ALTER TABLE %s ADD %s;".formatted(table, gitClause));
            }
        });
        gitConstraints.forEach((key, clause) -> {
            if (!dbConstraints.containsKey(key)) {
                (isForeignKey(clause) ? addForeignKeys : addConstraints)
                        .add("ALTER TABLE %s ADD %s;".formatted(table, clause));
            }
        });

        // Column defaults (columns added or dropped get theirs with the column statement)
        Map<String, TableDefinitionReader.Column> dbColumns = new HashMap<>();
        current.columns().forEach(c -> dbColumns.put(c.name().toLowerCase(), c));
        for (Map.Entry<String, ColumnDefinition> entry : gitDef.columns.entrySet()) {
            TableDefinitionReader.Column dbColumn = dbColumns.get(entry.getKey());
            ColumnDefinition gitColumn = entry.getValue();
            if (dbColumn == null || gitColumn.isIdentity) {
                continue;
            }
            String gitDefault = gitColumn.defaultValue;
            String dbDefault = dbColumn.defaultValue();
            boolean altered = alteredColumns.contains(entry.getKey());
            boolean same = gitDefault != null && dbDefault != null && sameSql(gitDefault, dbDefault);
            if (dbDefault != null && (!same || altered)) {
                before.add("ALTER TABLE %s DROP CONSTRAINT IF EXISTS [%s];".formatted(table, dbColumn.defaultName()));
            }
            if (gitDefault != null && (!same || altered)) {
                addConstraints.add("ALTER TABLE %s ADD DEFAULT %s FOR [%s];".formatted(table, gitDefault, gitColumn.originalName));
            }
        }

        after.addAll(addConstraints);
        after.addAll(addForeignKeys);

        // Indexes
        Map<String, String> gitIndexes = parseIndexStatements(gitText);
        Map<String, String> dbIndexes = new LinkedHashMap<>();
        current.indexes().forEach(i -> dbIndexes.put(i.name().toLowerCase(), i.statement()));
        dbIndexes.forEach((key, statement) -> {
            if (!gitIndexes.containsKey(key)) {
                before.add("DROP INDEX IF EXISTS [%s] ON %s;".formatted(dbIndexName(current, key), table));
            }
        });
        gitIndexes.forEach((key, statement) -> {
            String dbStatement = dbIndexes.get(key);
            if (dbStatement == null || !sameSql(dbStatement, statement) || ctx.droppedIndexes.contains(key)) {
                after.add("DROP INDEX IF EXISTS [%s] ON %s;".formatted(indexNameIn(statement), table));
                after.add(statement);
            }
        });

        return new ObjectChanges(before, after);
    }

    /**
     * Foreign keys of other tables that the script drops because a column or key of this table
     * they reference changes, or the table is recreated. Their definitions are read before the
     * script runs.
     */
    private List<ReferencingForeignKey> referencingForeignKeysToReadd(String schema, String name, AlterContext ctx) {
        List<ReferencingForeignKey> foreignKeys = new ArrayList<>();
        Map<String, Optional<TableDefinitionReader.TableModel>> parents = new HashMap<>();
        for (String[] fk : ctx.droppedReferencingForeignKeys) {
            if (fk[0].equalsIgnoreCase(schema) && fk[1].equalsIgnoreCase(name)) {
                continue; // self-reference: re-created from this table's own definition
            }
            Optional<TableDefinitionReader.TableModel> parent = parents.computeIfAbsent(
                    fk[0] + "." + fk[1], k -> tableDefinitionReader.read(fk[0], fk[1]));
            parent.flatMap(model -> model.constraints().stream()
                            .filter(c -> c.dbName().equalsIgnoreCase(fk[2]))
                            .findFirst())
                    .ifPresent(c -> foreignKeys.add(new ReferencingForeignKey(fk[0], fk[1], c.dbName(),
                            readdStatement(fk[0], fk[1], c.dbName(), c.clause()))));
        }
        return foreignKeys;
    }

    private static String readdStatement(String schema, String table, String fkName, String clause) {
        String objectName = sqlLiteral("[%s].[%s]".formatted(schema.replace("]", "]]"), table.replace("]", "]]")));
        return """
                IF OBJECT_ID(%s, N'U') IS NOT NULL
                    AND NOT EXISTS (SELECT 1 FROM sys.foreign_keys WHERE name = %s AND parent_object_id = OBJECT_ID(%s))
                    ALTER TABLE [%s].[%s] ADD %s;""".formatted(objectName, sqlLiteral(fkName), objectName, schema, table, clause);
    }

    private static String sqlLiteral(String value) {
        return "N'" + value.replace("'", "''") + "'";
    }

    private static final Pattern PRIMARY_KEY_CLAUSE = Pattern.compile("^CONSTRAINT\\s+\\S+\\s+PRIMARY\\s+KEY\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern NAMED_CONSTRAINT = Pattern.compile(
            "^CONSTRAINT\\s+\\[?([^\\]\\s]+)\\]?\\s+(?:PRIMARY\\s+KEY|UNIQUE|FOREIGN\\s+KEY|CHECK)\\b",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern INDEX_STATEMENT = Pattern.compile(
            "(CREATE\\s+(?:UNIQUE\\s+)?(?:(?:CLUSTERED|NONCLUSTERED)\\s+)?(?:COLUMNSTORE\\s+)?INDEX\\s+\\[([^\\]]+)\\][^;]*;)",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private static boolean isForeignKey(String clause) {
        return clause.toUpperCase().matches("(?s).*\\bFOREIGN\\s+KEY\\b.*");
    }

    /** Named table-level constraints in a CREATE TABLE definition: lower-case name -> clause. */
    Map<String, String> parseNamedConstraints(String createTableSql) {
        Map<String, String> constraints = new LinkedHashMap<>();
        String sql = removeSqlComments(createTableSql);
        int start = sql.indexOf('(', Math.max(0, sql.toUpperCase().indexOf("CREATE TABLE")));
        if (start < 0) {
            return constraints;
        }
        int depth = 0;
        int end = -1;
        for (int i = start; i < sql.length() && end < 0; i++) {
            char c = sql.charAt(i);
            if (c == '\'') {
                i = sql.indexOf('\'', i + 1);
                if (i < 0) break;
            } else if (c == '(') {
                depth++;
            } else if (c == ')' && --depth == 0) {
                end = i;
            }
        }
        if (end < 0) {
            return constraints;
        }
        for (String item : splitColumnsSmartly(sql.substring(start + 1, end))) {
            String clause = item.trim();
            Matcher matcher = NAMED_CONSTRAINT.matcher(clause);
            if (matcher.find()) {
                constraints.put(matcher.group(1).toLowerCase(), clause);
            }
        }
        return constraints;
    }

    /** Index statements in a definition: lower-case name -> statement. */
    private static Map<String, String> parseIndexStatements(String definition) {
        Map<String, String> indexes = new LinkedHashMap<>();
        Matcher matcher = INDEX_STATEMENT.matcher(definition);
        while (matcher.find()) {
            indexes.put(matcher.group(2).toLowerCase(), matcher.group(1).trim());
        }
        return indexes;
    }

    private static String indexNameIn(String statement) {
        Matcher matcher = INDEX_STATEMENT.matcher(statement);
        return matcher.find() ? matcher.group(2) : statement;
    }

    private static String dbIndexName(TableDefinitionReader.TableModel model, String lowerName) {
        return model.indexes().stream()
                .map(TableDefinitionReader.Index::name)
                .filter(n -> n.equalsIgnoreCase(lowerName))
                .findFirst().orElse(lowerName);
    }

    private static boolean referencesAny(String clause, Set<String> columns) {
        for (String column : columns) {
            if (Pattern.compile("(?i)(\\[" + Pattern.quote(column) + "\\]|\\b" + Pattern.quote(column) + "\\b)")
                    .matcher(clause).find()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Compares two SQL fragments ignoring case, brackets, whitespace, a trailing semicolon,
     * and the parentheses SQL Server adds around literals and expressions.
     */
    static boolean sameSql(String a, String b) {
        return normalizeSql(a).equals(normalizeSql(b));
    }

    private static String normalizeSql(String sql) {
        String s = sql.toLowerCase().replaceAll("[\\[\\]]", "").replaceAll("\\s+", "").replaceAll(";$", "");
        String previous;
        do {
            previous = s;
            s = s.replaceAll("\\(\\(([^()]*)\\)\\)", "($1)")              // ((x)) -> (x)
                 .replaceAll("\\((-?\\d+(?:\\.\\d+)?|'[^']*'|n'[^']*')\\)", "$1"); // (1) -> 1, ('a') -> 'a'
        } while (!s.equals(previous));
        // a whole default/check expression wrapped in parentheses
        while (s.startsWith("(") && s.endsWith(")") && balanced(s.substring(1, s.length() - 1))) {
            s = s.substring(1, s.length() - 1);
        }
        return s;
    }

    private static boolean balanced(String s) {
        int depth = 0;
        for (char c : s.toCharArray()) {
            if (c == '(') depth++;
            else if (c == ')' && --depth < 0) return false;
        }
        return depth == 0;
    }

    /**
     * Statements in a generated table script that destroy data: dropping the whole
     * table (also used when a table is recreated) or dropping a column.
     */
    private static final Pattern DATA_LOSS_STATEMENT = Pattern.compile(
            "^\\s*(DROP\\s+TABLE\\b.*|ALTER\\s+TABLE\\s+.+?\\s+DROP\\s+COLUMN\\b.*)$",
            Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);

    /**
     * Returns the statements that syncing this table would run and that can destroy
     * existing data: {@code DROP TABLE} (table removed from the repo, or recreated
     * because all data columns change) and {@code DROP COLUMN}.
     * Returns an empty list when the change only adds or alters structure.
     *
     * @param tableObject the table as it should look after sync; a {@code null}
     *                    definition means the table is being removed
     */
    public List<String> findDataLossStatements(DbObject tableObject) {
        if (tableObject.definition() == null) {
            return tableExists(tableObject.schema(), tableObject.name())
                    ? List.of(String.format("DROP TABLE [%s].[%s];", tableObject.schema(), tableObject.name()))
                    : List.of();
        }

        if (!tableExists(tableObject.schema(), tableObject.name())) {
            // New tables are created from scratch; nothing to lose
            return List.of();
        }

        String script = generateAlterScript(tableObject);

        List<String> statements = new ArrayList<>();
        Matcher matcher = DATA_LOSS_STATEMENT.matcher(script);
        while (matcher.find()) {
            statements.add(matcher.group(1).trim());
        }
        return statements;
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

    private static final Pattern IDENTITY_ATTRIBUTE = Pattern.compile(
            "\\bIDENTITY\\b(?:\\s*\\(\\s*([-+]?\\d+)\\s*,\\s*([-+]?\\d+)\\s*\\))?", Pattern.CASE_INSENSITIVE);

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

        // IDENTITY, IDENTITY(1,1), IDENTITY (1000, -1): taken out before splitting on whitespace
        Matcher identity = IDENTITY_ATTRIBUTE.matcher(attributesSection);
        if (identity.find()) {
            column.isIdentity = true;
            column.identitySeed = identity.group(1) != null ? new java.math.BigInteger(identity.group(1)).toString() : "1";
            column.identityIncrement = identity.group(2) != null ? new java.math.BigInteger(identity.group(2)).toString() : "1";
            attributesSection = (attributesSection.substring(0, identity.start()) + " "
                    + attributesSection.substring(identity.end())).trim();
            if (attributesSection.isEmpty()) {
                return;
            }
        }

        String[] parts = attributesSection.split("\\s+");
        boolean foundNull = false;

        for (int partIndex = 0; partIndex < parts.length; partIndex++) {
            String part = parts[partIndex];
            String upperPart = part.toUpperCase();

            if (upperPart.equals("NOT")) {
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
                    CAST(ic.seed_value AS varchar(40)) AS identity_seed,
                    CAST(ic.increment_value AS varchar(40)) AS identity_increment
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
            column.identitySeed = rs.getString("identity_seed");
            column.identityIncrement = rs.getString("identity_increment");
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
                                                  TableDefinition dbDef, TableDefinition gitDef, String gitTableDefinition,
                                                  AlterContext ctx) {
        List<String> statements = new ArrayList<>();

        // Check if we're dropping all data columns - if so, drop the entire table and recreate it
        List<String> columnsToRemove = dbDef.columns.keySet().stream()
                .filter(col -> !gitDef.columns.containsKey(col))
                .toList();

        // Count how many data columns would remain after drops
        long remainingDataColumns = dbDef.columns.keySet().stream()
                .filter(col -> gitDef.columns.containsKey(col))
                .filter(col -> {
                    ColumnDefinition colDef = dbDef.columns.get(col);
                    return !colDef.isIdentity; // Identity columns don't count as data columns
                })
                .count();

        // If we're about to drop all data columns, drop and recreate the entire table instead
        if (remainingDataColumns == 0 && !columnsToRemove.isEmpty()) {
            logger.info("Dropping entire table [{}].[{}] and recreating because all data columns would be removed",
                    schema, name);

            // Before dropping the table, drop all foreign key constraints that reference it from other tables
            List<String[]> referencingForeignKeys = getForeignKeysReferencingPrimaryKey(schema, name);
            for (String[] fkInfo : referencingForeignKeys) {
                String parentSchema = fkInfo[0];
                String parentTable = fkInfo[1];
                String fkName = fkInfo[2];
                if (!ctx.addDroppedReferencingForeignKey(fkInfo)) {
                    continue;
                }

                statements.add(String.format(
                        "ALTER TABLE [%s].[%s] DROP CONSTRAINT IF EXISTS [%s];",
                        parentSchema, parentTable, fkName
                ));
                logger.debug("Dropping foreign key [{}].[{}] before dropping table [{}].[{}]",
                        parentSchema, fkName, schema, name);
            }

            statements.add(String.format("DROP TABLE IF EXISTS [%s].[%s];", schema, name));
            statements.add(gitTableDefinition);
            ctx.recreated = true;
            return statements;
        }

        // Drop primary key if it exists and needs modification
        boolean primaryKeyChanged = !dbDef.primaryKeyColumns.equals(gitDef.primaryKeyColumns)
                || ctx.primaryKeyClauseChanged
                || ctx.primaryKeyColumnAltered; // SQL Server can't alter a column of a primary key in place
        if (dbDef.primaryKeyName != null && primaryKeyChanged) {
            // Before dropping primary key, drop all foreign key constraints that reference it
            List<String[]> referencingForeignKeys = getForeignKeysReferencingPrimaryKey(schema, name);
            for (String[] fkInfo : referencingForeignKeys) {
                String parentSchema = fkInfo[0];
                String parentTable = fkInfo[1];
                String fkName = fkInfo[2];
                if (!ctx.addDroppedReferencingForeignKey(fkInfo)) {
                    continue;
                }

                statements.add(String.format(
                        "ALTER TABLE [%s].[%s] DROP CONSTRAINT IF EXISTS [%s];",
                        parentSchema, parentTable, fkName
                ));
                logger.debug("Dropping foreign key [{}].[{}] before dropping primary key [{}]",
                        parentSchema, fkName, dbDef.primaryKeyName);
            }

            statements.add(String.format(
                    "ALTER TABLE [%s].[%s] DROP CONSTRAINT IF EXISTS [%s];",
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
                            "ALTER TABLE [%s].[%s] DROP CONSTRAINT IF EXISTS [%s];",
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
                String identity = gitColumn.isIdentity
                        ? " IDENTITY(%s,%s)".formatted(gitColumn.identitySeed, gitColumn.identityIncrement)
                        : "";
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
                            ctx.droppedIndexes.add(indexName.toLowerCase());
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
                    }
                }
                if (dbColumn.isIdentity != gitColumn.isIdentity
                        || (gitColumn.isIdentity && (!Objects.equals(dbColumn.identitySeed, gitColumn.identitySeed)
                                || !Objects.equals(dbColumn.identityIncrement, gitColumn.identityIncrement)))) {
                    // SQL Server can't add, remove or change IDENTITY on an existing column
                    logger.warn("Cannot change IDENTITY of column [{}].[{}].[{}] (database: {}, Git: {}); not applied, change it by hand",
                            schema, name, gitColumn.originalName, identitySpec(dbColumn), identitySpec(gitColumn));
                }
            }
        }

        // Add primary key if needed
        if (!gitDef.primaryKeyColumns.isEmpty() && primaryKeyChanged) {
            String pkName = gitDef.primaryKeyName != null ? gitDef.primaryKeyName :
                    String.format("PK_%s_%s", schema, name);
            String columns = gitDef.primaryKeyColumns.stream()
                    .map(col -> "[" + col + "]")
                    .reduce((a, b) -> a + ", " + b)
                    .orElse("");

            String gitPkClause = parseNamedConstraints(gitTableDefinition).values().stream()
                    .filter(clause -> PRIMARY_KEY_CLAUSE.matcher(clause).find())
                    .findFirst().orElse(null);
            statements.add(gitPkClause != null
                    ? String.format("ALTER TABLE [%s].[%s] ADD %s;", schema, name, gitPkClause)
                    : String.format("ALTER TABLE [%s].[%s] ADD CONSTRAINT [%s] PRIMARY KEY (%s);",
                            schema, name, pkName, columns));
        }

        return statements;
    }

    private static String identitySpec(ColumnDefinition column) {
        return column.isIdentity ? "IDENTITY(%s,%s)".formatted(column.identitySeed, column.identityIncrement) : "no IDENTITY";
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
     * Gets all foreign key constraints from other tables that reference this table's primary key.
     * These must be dropped before the primary key can be modified.
     *
     * @param schema Schema name of the table
     * @param tableName Table name whose primary key is referenced
     * @return List of foreign key constraint info in format [parentSchema, parentTable, fkName]
     */
    private List<String[]> getForeignKeysReferencingPrimaryKey(String schema, String tableName) {
        List<String[]> foreignKeys = new ArrayList<>();

        // Query for foreign keys that reference this table
        String query = """
                SELECT 
                    SCHEMA_NAME(pt.schema_id) AS parent_schema,
                    pt.name AS parent_table,
                    fk.name AS fk_name
                FROM sys.foreign_keys fk
                JOIN sys.tables pt ON fk.parent_object_id = pt.object_id
                JOIN sys.tables rt ON fk.referenced_object_id = rt.object_id
                JOIN sys.schemas rs ON rt.schema_id = rs.schema_id
                WHERE rs.name = ? AND rt.name = ?
                """;

        jdbcTemplate.query(query, rs -> {
            String parentSchema = rs.getString("parent_schema");
            String parentTable = rs.getString("parent_table");
            String fkName = rs.getString("fk_name");

            foreignKeys.add(new String[]{parentSchema, parentTable, fkName});
            logger.debug("Found foreign key [{}].[{}] referencing {}.{}",
                    parentSchema, fkName, schema, tableName);
        }, schema, tableName);

        return foreignKeys;
    }

    /**
     * Foreign keys (of any table, including this one) that reference one of the given columns
     * of this table, e.g. through its primary key or a unique constraint.
     *
     * @return [parentSchema, parentTable, fkName] per foreign key
     */
    private List<String[]> getForeignKeysReferencingColumns(String schema, String tableName, Set<String> columns) {
        if (columns.isEmpty()) {
            return List.of();
        }
        String query = """
                SELECT SCHEMA_NAME(pt.schema_id) AS parent_schema, pt.name AS parent_table,
                       fk.name AS fk_name, rc.name AS referenced_column
                FROM sys.foreign_keys fk
                JOIN sys.foreign_key_columns fkc ON fkc.constraint_object_id = fk.object_id
                JOIN sys.columns rc ON rc.object_id = fkc.referenced_object_id AND rc.column_id = fkc.referenced_column_id
                JOIN sys.tables pt ON fk.parent_object_id = pt.object_id
                JOIN sys.tables rt ON fk.referenced_object_id = rt.object_id
                JOIN sys.schemas rs ON rt.schema_id = rs.schema_id
                WHERE rs.name = ? AND rt.name = ?
                ORDER BY parent_schema, parent_table, fk_name
                """;
        Map<String, String[]> foreignKeys = new LinkedHashMap<>();
        jdbcTemplate.query(query, rs -> {
            if (columns.contains(rs.getString("referenced_column").toLowerCase())) {
                String[] fk = {rs.getString("parent_schema"), rs.getString("parent_table"), rs.getString("fk_name")};
                foreignKeys.putIfAbsent(String.join(".", fk).toLowerCase(), fk);
            }
        }, schema, tableName);
        return new ArrayList<>(foreignKeys.values());
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
        String identitySeed;      // as written, e.g. "1" or "1000000000000"; null if not an identity
        String identityIncrement;
        int columnId;
        String defaultValue;      // DEFAULT constraint value (e.g., "0", "getdate()", etc.)
    }
}


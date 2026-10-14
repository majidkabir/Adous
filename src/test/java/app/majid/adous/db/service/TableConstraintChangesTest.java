package app.majid.adous.db.service;

import app.majid.adous.synchronizer.model.DbObject;
import app.majid.adous.synchronizer.model.DbObjectType;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.MSSQLServerContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Changes to constraints, defaults and indexes of existing tables: each test edits the
 * extracted definition, applies it, and checks the database and the re-extracted definition.
 */
@Testcontainers
class TableConstraintChangesTest {

    @Container
    static MSSQLServerContainer<?> mssqlServer =
            new MSSQLServerContainer<>("mcr.microsoft.com/mssql/server:2022-latest")
                    .acceptLicense();

    private static HikariDataSource dataSource;
    private static JdbcTemplate jdbcTemplate;
    private static MSSQLDatabaseService databaseService;
    private static TableAlterScriptGenerator generator;

    @BeforeAll
    static void initAll() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(mssqlServer.getJdbcUrl());
        config.setUsername(mssqlServer.getUsername());
        config.setPassword(mssqlServer.getPassword());
        config.setDriverClassName("com.microsoft.sqlserver.jdbc.SQLServerDriver");
        config.setMaximumPoolSize(5);
        dataSource = new HikariDataSource(config);
        jdbcTemplate = new JdbcTemplate(dataSource);
        generator = new TableAlterScriptGenerator(jdbcTemplate);
        databaseService = new MSSQLDatabaseService(jdbcTemplate, generator, new SequenceAlterScriptGenerator(jdbcTemplate));
        ReflectionTestUtils.setField(databaseService, "defaultSchema", "dbo");
    }

    @AfterAll
    static void tearDownAll() {
        dataSource.close();
    }

    @BeforeEach
    void createTables() {
        jdbcTemplate.execute("""
                CREATE TABLE dbo.tc_customer (
                    id int NOT NULL CONSTRAINT PK_tc_customer PRIMARY KEY,
                    email varchar(100) NULL)
                """);
        jdbcTemplate.execute("""
                CREATE TABLE dbo.tc_order (
                    id int NOT NULL CONSTRAINT PK_tc_order PRIMARY KEY,
                    customer_id int NULL,
                    qty int NOT NULL CONSTRAINT DF_tc_order_qty DEFAULT (1),
                    note varchar(20) NULL,
                    CONSTRAINT CK_tc_order_qty CHECK (qty > 0))
                """);
        jdbcTemplate.execute("INSERT INTO dbo.tc_customer (id, email) VALUES (1, 'a@x'), (2, 'b@x')");
        jdbcTemplate.execute("INSERT INTO dbo.tc_order (id, customer_id, qty) VALUES (10, 1, 3)");
    }

    @AfterEach
    void cleanup() {
        jdbcTemplate.execute("DROP TABLE IF EXISTS dbo.tc_order");
        jdbcTemplate.execute("DROP TABLE IF EXISTS dbo.tc_customer");
    }

    @Test
    void shouldGenerateNothingForUnchangedTable() {
        assertEquals("", generator.generateAlterScript(table("tc_order", definition("tc_order"))));
    }

    @Test
    void shouldAddAndRemoveForeignKeyWithReferentialAction() {
        String withFk = addClause(definition("tc_order"),
                "CONSTRAINT [FK_tc_order_customer] FOREIGN KEY ([customer_id]) REFERENCES [dbo].[tc_customer] ([id]) ON DELETE CASCADE");

        apply("tc_order", withFk);
        assertEquals("CASCADE", jdbcTemplate.queryForObject(
                "SELECT delete_referential_action_desc FROM sys.foreign_keys WHERE name = 'FK_tc_order_customer'", String.class));
        assertTrue(definition("tc_order").contains("REFERENCES [dbo].[tc_customer] ([id]) ON DELETE CASCADE"),
                "extraction must include ON DELETE CASCADE");
        assertEquals("", generator.generateAlterScript(table("tc_order", withFk)), "nothing left to change");

        apply("tc_order", withFk.replace(",\n    CONSTRAINT [FK_tc_order_customer] FOREIGN KEY ([customer_id]) REFERENCES [dbo].[tc_customer] ([id]) ON DELETE CASCADE", ""));
        assertEquals(0, count("SELECT COUNT(*) FROM sys.foreign_keys WHERE name = 'FK_tc_order_customer'"));
    }

    @Test
    void shouldAddAndRemoveUniqueConstraint() {
        String withUnique = addClause(definition("tc_customer"), "CONSTRAINT [UQ_tc_customer_email] UNIQUE ([email])");

        apply("tc_customer", withUnique);
        assertEquals(1, count("SELECT COUNT(*) FROM sys.key_constraints WHERE name = 'UQ_tc_customer_email'"));

        apply("tc_customer", withUnique.replace(",\n    CONSTRAINT [UQ_tc_customer_email] UNIQUE ([email])", ""));
        assertEquals(0, count("SELECT COUNT(*) FROM sys.key_constraints WHERE name = 'UQ_tc_customer_email'"));
    }

    @Test
    void shouldChangeAndRemoveCheckConstraint() {
        String original = definition("tc_order");
        String changed = original.replace("CHECK ([qty]>(0))", "CHECK ([qty]>(0) AND [qty]<(100))");
        assertNotEquals(original, changed, "test setup: CHECK clause format");

        apply("tc_order", changed);
        assertTrue(jdbcTemplate.queryForObject(
                "SELECT definition FROM sys.check_constraints WHERE name = 'CK_tc_order_qty'", String.class).contains("100"));

        apply("tc_order", changed.replace(",\n    CONSTRAINT [CK_tc_order_qty] CHECK ([qty]>(0) AND [qty]<(100))", ""));
        assertEquals(0, count("SELECT COUNT(*) FROM sys.check_constraints WHERE name = 'CK_tc_order_qty'"));
    }

    @Test
    void shouldChangeAddAndRemoveDefaults() {
        String original = definition("tc_order");
        String changed = original
                .replace("[qty] int NOT NULL DEFAULT ((1))", "[qty] int NOT NULL DEFAULT ((5))")
                .replace("[note] varchar(20) NULL", "[note] varchar(20) NULL DEFAULT ('n/a')");
        assertNotEquals(original, changed, "test setup: column format");

        apply("tc_order", changed);
        jdbcTemplate.execute("INSERT INTO dbo.tc_order (id) VALUES (11)");
        assertEquals(5, jdbcTemplate.queryForObject("SELECT qty FROM dbo.tc_order WHERE id = 11", Integer.class));
        assertEquals("n/a", jdbcTemplate.queryForObject("SELECT note FROM dbo.tc_order WHERE id = 11", String.class));
        assertEquals("", generator.generateAlterScript(table("tc_order", changed)), "nothing left to change");

        apply("tc_order", changed.replace(" DEFAULT ('n/a')", ""));
        assertEquals(0, count("""
                SELECT COUNT(*) FROM sys.default_constraints
                WHERE parent_object_id = OBJECT_ID('dbo.tc_order') AND COL_NAME(parent_object_id, parent_column_id) = 'note'
                """));
    }

    @Test
    void shouldAddAndDropIndexWithoutOtherChanges() {
        String withIndex = definition("tc_order") + "CREATE INDEX [ix_tc_order_note] ON [dbo].[tc_order] ([note]);\nGO\n";

        apply("tc_order", withIndex);
        assertEquals(1, count("SELECT COUNT(*) FROM sys.indexes WHERE name = 'ix_tc_order_note'"));
        assertEquals(withIndex, definition("tc_order"));

        apply("tc_order", withIndex.replace("CREATE INDEX [ix_tc_order_note] ON [dbo].[tc_order] ([note]);\nGO\n", ""));
        assertEquals(0, count("SELECT COUNT(*) FROM sys.indexes WHERE name = 'ix_tc_order_note'"));
    }

    @Test
    void shouldAlterColumnThatHasCheckDefaultAndIndex() {
        String original = definition("tc_order") + "CREATE INDEX [ix_tc_order_qty] ON [dbo].[tc_order] ([qty]);\nGO\n";
        apply("tc_order", original);

        String widened = original.replace("[qty] int NOT NULL", "[qty] bigint NOT NULL");
        apply("tc_order", widened);

        assertEquals("bigint", jdbcTemplate.queryForObject(
                "SELECT TYPE_NAME(user_type_id) FROM sys.columns WHERE object_id = OBJECT_ID('dbo.tc_order') AND name = 'qty'", String.class));
        assertEquals(widened, definition("tc_order"), "check, default and index must be back");
    }

    @Test
    void shouldReaddForeignKeysOfOtherTablesWhenPrimaryKeyIsRecreated() {
        jdbcTemplate.execute("""
                ALTER TABLE dbo.tc_order ADD CONSTRAINT FK_tc_order_customer
                    FOREIGN KEY (customer_id) REFERENCES dbo.tc_customer (id)
                """);
        String orderBefore = definition("tc_order");

        // Same key column, but the primary key becomes nonclustered: dropped and re-added
        apply("tc_customer", definition("tc_customer").replace("PRIMARY KEY ([id])", "PRIMARY KEY NONCLUSTERED ([id])")
                .replace("CONSTRAINT [PK_tc_customer]", "CONSTRAINT [PK_tc_customer2]"));

        assertEquals(1, count("SELECT COUNT(*) FROM sys.foreign_keys WHERE name = 'FK_tc_order_customer'"));
        assertEquals(orderBefore, definition("tc_order"));
    }

    @Test
    void shouldWidenReferencedPrimaryKeyTogetherWithReferencingColumn() {
        addOrderCustomerForeignKey();
        String customer = definition("tc_customer").replace("[id] int NOT NULL", "[id] bigint NOT NULL");
        String order = definition("tc_order").replace("[customer_id] int NULL", "[customer_id] bigint NULL");
        assertNotEquals(definition("tc_order"), order, "test setup: column format");

        // Given in the order a sync would list them; the service orders them by foreign key
        databaseService.applyChangesToDatabase(List.of(table("tc_order", order), table("tc_customer", customer)));

        assertEquals("bigint", columnType("tc_customer", "id"));
        assertEquals("bigint", columnType("tc_order", "customer_id"));
        assertEquals(1, count("SELECT COUNT(*) FROM sys.foreign_keys WHERE name = 'FK_tc_order_customer'"));
        assertEquals(customer, definition("tc_customer"));
        assertEquals(order, definition("tc_order"));
        assertEquals(2, count("SELECT COUNT(*) FROM dbo.tc_customer"), "rows are kept");
    }

    @Test
    void shouldAlterUniqueColumnReferencedByUnchangedTable() {
        String withUnique = addClause(definition("tc_customer"), "CONSTRAINT [UQ_tc_customer_email] UNIQUE ([email])");
        apply("tc_customer", withUnique);
        jdbcTemplate.execute("""
                CREATE TABLE dbo.tc_contact (
                    id int NOT NULL CONSTRAINT PK_tc_contact PRIMARY KEY,
                    email varchar(100) NULL CONSTRAINT FK_tc_contact_customer REFERENCES dbo.tc_customer (email))
                """);
        try {
            String notNull = withUnique.replace("[email] varchar(100) NULL", "[email] varchar(100) NOT NULL");
            assertNotEquals(withUnique, notNull, "test setup: column format");

            apply("tc_customer", notNull);

            assertEquals(0, count("""
                    SELECT COUNT(*) FROM sys.columns
                    WHERE object_id = OBJECT_ID('dbo.tc_customer') AND name = 'email' AND is_nullable = 1
                    """));
            assertEquals(1, count("SELECT COUNT(*) FROM sys.key_constraints WHERE name = 'UQ_tc_customer_email'"));
            assertEquals(1, count("SELECT COUNT(*) FROM sys.foreign_keys WHERE name = 'FK_tc_contact_customer'"));
        } finally {
            jdbcTemplate.execute("DROP TABLE IF EXISTS dbo.tc_contact");
        }
    }

    @Test
    void shouldNameForeignKeyWhenReferencingColumnIsNotChangedToo() {
        addOrderCustomerForeignKey();
        String customer = definition("tc_customer").replace("[id] int NOT NULL", "[id] bigint NOT NULL");

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> apply("tc_customer", customer));

        assertTrue(error.getMessage().contains("[FK_tc_order_customer] on [dbo].[tc_order]"), error.getMessage());
    }

    @Test
    void shouldAddComputedColumnAndKeepItInExtraction() {
        String withComputed = withComputedColumn(definition("tc_order"), "([qty]*(2)) PERSISTED");

        apply("tc_order", withComputed);

        assertEquals("([qty]*(2))", jdbcTemplate.queryForObject(
                "SELECT definition FROM sys.computed_columns WHERE object_id = OBJECT_ID('dbo.tc_order') AND name = 'qty_x2'",
                String.class));
        assertEquals(withComputed, definition("tc_order"));
        assertEquals(6, jdbcTemplate.queryForObject("SELECT qty_x2 FROM dbo.tc_order WHERE id = 10", Integer.class));
        assertEquals("", generator.generateAlterScript(table("tc_order", withComputed)), "nothing left to change");
    }

    @Test
    void shouldRecreateComputedColumnWhenExpressionChangesKeepingItsIndex() {
        String index = "CREATE INDEX [ix_tc_order_qty_x2] ON [dbo].[tc_order] ([qty_x2]);\nGO\n";
        apply("tc_order", withComputedColumn(definition("tc_order"), "([qty]*(2)) PERSISTED") + index);

        String changed = withComputedColumn(definitionWithoutComputed(), "([qty]*(3)) PERSISTED"); // index included
        assertEquals(List.of(), databaseService.findDataLossOperations(List.of(table("tc_order", changed))),
                "dropping a computed column loses no data");
        apply("tc_order", changed);

        assertEquals(9, jdbcTemplate.queryForObject("SELECT qty_x2 FROM dbo.tc_order WHERE id = 10", Integer.class));
        assertEquals(changed, definition("tc_order"));
    }

    @Test
    void shouldAlterColumnUsedByComputedColumn() {
        String withComputed = withComputedColumn(definition("tc_order"), "([qty]*(2))");
        apply("tc_order", withComputed);

        String widened = withComputed.replace("[qty] int NOT NULL", "[qty] bigint NOT NULL");
        apply("tc_order", widened);

        assertEquals("bigint", columnType("tc_order", "qty"));
        assertEquals(widened, definition("tc_order"));
    }

    @Test
    void shouldReportDataLossWhenRegularColumnBecomesComputed() {
        String computedNote = definition("tc_order").replace("[note] varchar(20) NULL", "[note] AS (CONVERT([varchar](20),[qty]))");

        assertEquals(List.of("ALTER TABLE [dbo].[tc_order] DROP COLUMN [note];"),
                databaseService.findDataLossOperations(List.of(table("tc_order", computedNote))));
    }

    @Test
    void shouldReportDataLossWhenComputedColumnBecomesRegular() {
        apply("tc_order", withComputedColumn(definition("tc_order"), "([qty]*(2))"));
        // How 0.2.0 extracted computed columns
        String regular = definition("tc_order").replace("[qty_x2] AS ([qty]*(2))", "[qty_x2] int NULL");

        assertEquals(List.of("ALTER TABLE [dbo].[tc_order] DROP COLUMN [qty_x2];"),
                databaseService.findDataLossOperations(List.of(table("tc_order", regular))));
    }

    /** Adds [qty_x2] AS ... as the last column of a tc_order definition. */
    private static String withComputedColumn(String definition, String spec) {
        String result = definition.replace("[note] varchar(20) NULL", "[note] varchar(20) NULL,\n    [qty_x2] AS " + spec);
        assertNotEquals(definition, result, "test setup: column format");
        return result;
    }

    private static String definitionWithoutComputed() {
        String definition = definition("tc_order");
        return definition.substring(0, definition.indexOf(",\n    [qty_x2]"))
                + definition.substring(definition.indexOf(",\n", definition.indexOf("[qty_x2]")));
    }

    private static void addOrderCustomerForeignKey() {
        jdbcTemplate.execute("""
                ALTER TABLE dbo.tc_order ADD CONSTRAINT FK_tc_order_customer
                    FOREIGN KEY (customer_id) REFERENCES dbo.tc_customer (id)
                """);
    }

    private static String columnType(String table, String column) {
        return jdbcTemplate.queryForObject(
                "SELECT TYPE_NAME(user_type_id) FROM sys.columns WHERE object_id = OBJECT_ID(?) AND name = ?",
                String.class, "dbo." + table, column);
    }

    private static String addClause(String definition, String clause) {
        int end = definition.indexOf("\n);");
        return definition.substring(0, end) + ",\n    " + clause + definition.substring(end);
    }

    private static void apply(String name, String definition) {
        databaseService.applyChangesToDatabase(List.of(table(name, definition)));
    }

    private static DbObject table(String name, String definition) {
        return new DbObject("dbo", name, DbObjectType.TABLE, definition);
    }

    private static String definition(String name) {
        return databaseService.getDbObjects().stream()
                .filter(o -> o.type() == DbObjectType.TABLE && o.name().equals(name))
                .findFirst().orElseThrow().definition();
    }

    private static int count(String sql) {
        return jdbcTemplate.queryForObject(sql, Integer.class);
    }
}

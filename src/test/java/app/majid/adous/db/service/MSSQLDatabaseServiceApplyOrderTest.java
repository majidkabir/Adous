package app.majid.adous.db.service;

import app.majid.adous.synchronizer.model.DbObject;
import app.majid.adous.synchronizer.model.DbObjectType;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.MSSQLServerContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Applies dependent objects in the same sync, passed in an unhelpful order, and checks
 * they are created (and dropped) in an order SQL Server accepts.
 */
@Testcontainers
class MSSQLDatabaseServiceApplyOrderTest {

    @Container
    static MSSQLServerContainer<?> mssqlServer =
            new MSSQLServerContainer<>("mcr.microsoft.com/mssql/server:2022-latest")
                    .acceptLicense();

    private static HikariDataSource dataSource;
    private static JdbcTemplate jdbcTemplate;
    private static MSSQLDatabaseService databaseService;

    @BeforeAll
    static void initAll() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(mssqlServer.getJdbcUrl());
        config.setUsername(mssqlServer.getUsername());
        config.setPassword(mssqlServer.getPassword());
        config.setDriverClassName("com.microsoft.sqlserver.jdbc.SQLServerDriver");
        config.setMaximumPoolSize(2);
        dataSource = new HikariDataSource(config);
        jdbcTemplate = new JdbcTemplate(dataSource);
        databaseService = new MSSQLDatabaseService(jdbcTemplate, new TableAlterScriptGenerator(jdbcTemplate),
                new SequenceAlterScriptGenerator(jdbcTemplate));
        ReflectionTestUtils.setField(databaseService, "defaultSchema", "dbo");
    }

    @AfterAll
    static void tearDownAll() {
        dataSource.close();
    }

    @AfterEach
    void cleanup() {
        for (String sql : List.of(
                "DROP TRIGGER IF EXISTS dbo.tr_v_orders_insert",
                "DROP VIEW IF EXISTS dbo.v_orders",
                "DROP VIEW IF EXISTS dbo.v_bound",
                "DROP FUNCTION IF EXISTS dbo.fn_double",
                "DROP TABLE IF EXISTS dbo.order_line",
                "DROP TABLE IF EXISTS dbo.orders",
                "DROP SEQUENCE IF EXISTS dbo.seq_orders",
                "DROP TYPE IF EXISTS dbo.amount")) {
            jdbcTemplate.execute(sql);
        }
    }

    @Test
    void shouldCreateTypeAndSequenceBeforeTableThatUsesThem() {
        databaseService.applyChangesToDatabase(List.of(
                table("orders", """
                        CREATE TABLE [dbo].[orders]
                        (
                            [id] int NOT NULL DEFAULT (NEXT VALUE FOR [dbo].[seq_orders]),
                            [total] [dbo].[amount] NULL,
                            CONSTRAINT [PK_orders] PRIMARY KEY ([id])
                        );
                        GO
                        """),
                new DbObject("dbo", "seq_orders", DbObjectType.SEQUENCE,
                        "CREATE SEQUENCE [dbo].[seq_orders] AS int START WITH 1 INCREMENT BY 1 NO CACHE;\nGO\n"),
                new DbObject("dbo", "amount", DbObjectType.SCALAR_TYPE,
                        "CREATE TYPE [dbo].[amount] FROM decimal(18, 2) NULL;\nGO\n")));

        jdbcTemplate.execute("INSERT INTO dbo.orders (total) VALUES (9.50)");
        assertEquals(1, jdbcTemplate.queryForObject("SELECT id FROM dbo.orders", Integer.class));
    }

    @Test
    void shouldCreateFunctionAndViewBeforeTriggerOnTheView() {
        createOrdersTable();

        databaseService.applyChangesToDatabase(List.of(
                new DbObject("dbo", "tr_v_orders_insert", DbObjectType.TRIGGER, """
                        CREATE TRIGGER dbo.tr_v_orders_insert ON dbo.v_orders INSTEAD OF INSERT
                        AS BEGIN SET NOCOUNT ON; INSERT INTO dbo.orders (id) SELECT id FROM inserted; END
                        GO
                        """),
                new DbObject("dbo", "v_orders", DbObjectType.VIEW,
                        "CREATE VIEW dbo.v_orders AS SELECT id, dbo.fn_double(id) AS twice FROM dbo.orders\nGO\n"),
                new DbObject("dbo", "fn_double", DbObjectType.FUNCTION,
                        "CREATE FUNCTION dbo.fn_double(@v int) RETURNS int AS BEGIN RETURN @v * 2 END\nGO\n")));

        jdbcTemplate.execute("INSERT INTO dbo.v_orders (id) VALUES (21)");
        assertEquals(42, jdbcTemplate.queryForObject("SELECT twice FROM dbo.v_orders WHERE id = 21", Integer.class));
    }

    @Test
    void shouldCreateReferencedTableBeforeReferencingTable() {
        databaseService.applyChangesToDatabase(List.of(
                table("order_line", """
                        CREATE TABLE [dbo].[order_line]
                        (
                            [id] int NOT NULL,
                            [order_id] int NOT NULL,
                            CONSTRAINT [PK_order_line] PRIMARY KEY ([id]),
                            CONSTRAINT [FK_order_line_orders] FOREIGN KEY ([order_id]) REFERENCES [dbo].[orders] ([id])
                        );
                        GO
                        """),
                table("orders", """
                        CREATE TABLE [dbo].[orders]
                        (
                            [id] int NOT NULL,
                            CONSTRAINT [PK_orders] PRIMARY KEY ([id])
                        );
                        GO
                        """)));

        assertNotNull(jdbcTemplate.queryForObject("SELECT OBJECT_ID('dbo.order_line')", Integer.class));
    }

    @Test
    void shouldDropDependentObjectsBeforeWhatTheyDependOn() {
        createOrdersTable();
        jdbcTemplate.execute("""
                CREATE TABLE dbo.order_line (id int NOT NULL PRIMARY KEY,
                    order_id int NOT NULL CONSTRAINT FK_order_line_orders REFERENCES dbo.orders (id))
                """);
        jdbcTemplate.execute("CREATE VIEW dbo.v_bound WITH SCHEMABINDING AS SELECT id FROM dbo.orders");

        // Listed in the worst order: referenced table first, schema-bound view last
        databaseService.applyChangesToDatabase(List.of(
                new DbObject("dbo", "orders", DbObjectType.TABLE, null),
                new DbObject("dbo", "order_line", DbObjectType.TABLE, null),
                new DbObject("dbo", "v_bound", DbObjectType.VIEW, null)));

        assertNull(jdbcTemplate.queryForObject("SELECT OBJECT_ID('dbo.orders')", Integer.class));
        assertNull(jdbcTemplate.queryForObject("SELECT OBJECT_ID('dbo.order_line')", Integer.class));
        assertNull(jdbcTemplate.queryForObject("SELECT OBJECT_ID('dbo.v_bound')", Integer.class));
    }

    @Test
    void shouldUpdateTableBeforeDroppingTypeItNoLongerUses() {
        jdbcTemplate.execute("CREATE TYPE dbo.amount FROM decimal(18, 2) NULL");
        jdbcTemplate.execute("CREATE TABLE dbo.orders (id int NOT NULL PRIMARY KEY, total dbo.amount NULL)");

        databaseService.applyChangesToDatabase(List.of(
                new DbObject("dbo", "amount", DbObjectType.SCALAR_TYPE, null),
                table("orders", """
                        CREATE TABLE [dbo].[orders]
                        (
                            [id] int NOT NULL,
                            [total] decimal(18, 2) NULL,
                            CONSTRAINT [PK_orders] PRIMARY KEY ([id])
                        );
                        GO
                        """)));

        assertNull(jdbcTemplate.queryForObject("SELECT TYPE_ID('dbo.amount')", Integer.class));
    }

    private static void createOrdersTable() {
        jdbcTemplate.execute("CREATE TABLE dbo.orders (id int NOT NULL CONSTRAINT PK_orders PRIMARY KEY)");
    }

    private static DbObject table(String name, String definition) {
        return new DbObject("dbo", name, DbObjectType.TABLE, definition);
    }
}

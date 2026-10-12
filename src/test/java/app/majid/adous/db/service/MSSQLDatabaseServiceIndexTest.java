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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Index kinds survive extraction and re-creation: clustered, nonclustered with INCLUDE and
 * DESC keys, filtered, columnstore, and nonclustered primary keys.
 */
@Testcontainers
class MSSQLDatabaseServiceIndexTest {

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
        config.setMaximumPoolSize(5);
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
        jdbcTemplate.execute("DROP TABLE IF EXISTS dbo.ix_orders");
        jdbcTemplate.execute("DROP TABLE IF EXISTS dbo.ix_facts");
        jdbcTemplate.execute("DROP TABLE IF EXISTS dbo.ix_places");
    }

    @Test
    void shouldExtractIndexKinds() {
        createOrders();

        String definition = tableDefinition("ix_orders");

        assertContains(definition, "CONSTRAINT [PK_ix_orders] PRIMARY KEY NONCLUSTERED ([id])");
        assertContains(definition, "CREATE CLUSTERED INDEX [cx_orders_created] ON [dbo].[ix_orders] ([created])");
        assertContains(definition, "CREATE INDEX [ix_orders_customer] ON [dbo].[ix_orders] ([customer_id], [created] DESC) INCLUDE ([total], [status])");
        assertContains(definition, "CREATE UNIQUE INDEX [ux_orders_ref] ON [dbo].[ix_orders] ([ref]) WHERE [ref] IS NOT NULL");
        assertContains(definition, "CREATE NONCLUSTERED COLUMNSTORE INDEX [ncci_orders] ON [dbo].[ix_orders] ([customer_id], [total])");
    }

    @Test
    void shouldExtractClusteredColumnstoreIndex() {
        jdbcTemplate.execute("CREATE TABLE dbo.ix_facts (id int NOT NULL, amount decimal(10, 2) NULL)");
        jdbcTemplate.execute("CREATE CLUSTERED COLUMNSTORE INDEX cci_facts ON dbo.ix_facts");

        assertContains(tableDefinition("ix_facts"), "CREATE CLUSTERED COLUMNSTORE INDEX [cci_facts] ON [dbo].[ix_facts];");
    }

    @Test
    void shouldSkipSpatialIndexInsteadOfWritingInvalidDdl() {
        jdbcTemplate.execute("CREATE TABLE dbo.ix_places (id int NOT NULL CONSTRAINT PK_ix_places PRIMARY KEY, location geography NULL)");
        jdbcTemplate.execute("CREATE SPATIAL INDEX sx_places_location ON dbo.ix_places (location)");

        assertFalse(tableDefinition("ix_places").contains("sx_places_location"));
    }

    @Test
    void shouldRecreateTablesWithTheSameIndexes() {
        createOrders();
        jdbcTemplate.execute("CREATE TABLE dbo.ix_facts (id int NOT NULL, amount decimal(10, 2) NULL)");
        jdbcTemplate.execute("CREATE CLUSTERED COLUMNSTORE INDEX cci_facts ON dbo.ix_facts");
        String orders = tableDefinition("ix_orders");
        String facts = tableDefinition("ix_facts");
        Map<String, String> indexKindsBefore = indexKinds();

        jdbcTemplate.execute("DROP TABLE dbo.ix_orders");
        jdbcTemplate.execute("DROP TABLE dbo.ix_facts");
        databaseService.applyChangesToDatabase(List.of(
                new DbObject("dbo", "ix_orders", DbObjectType.TABLE, orders),
                new DbObject("dbo", "ix_facts", DbObjectType.TABLE, facts)));

        assertEquals(orders, tableDefinition("ix_orders"));
        assertEquals(facts, tableDefinition("ix_facts"));
        assertEquals(indexKindsBefore, indexKinds());
    }

    private static void createOrders() {
        jdbcTemplate.execute("""
                CREATE TABLE dbo.ix_orders (
                    id int NOT NULL CONSTRAINT PK_ix_orders PRIMARY KEY NONCLUSTERED,
                    customer_id int NOT NULL,
                    created datetime2 NOT NULL,
                    total decimal(10, 2) NULL,
                    status varchar(20) NULL,
                    ref varchar(30) NULL)
                """);
        jdbcTemplate.execute("CREATE CLUSTERED INDEX cx_orders_created ON dbo.ix_orders (created)");
        jdbcTemplate.execute("CREATE INDEX ix_orders_customer ON dbo.ix_orders (customer_id, created DESC) INCLUDE (total, status)");
        jdbcTemplate.execute("CREATE UNIQUE INDEX ux_orders_ref ON dbo.ix_orders (ref) WHERE ref IS NOT NULL");
        jdbcTemplate.execute("CREATE NONCLUSTERED COLUMNSTORE INDEX ncci_orders ON dbo.ix_orders (customer_id, total)");
    }

    private static Map<String, String> indexKinds() {
        Map<String, String> kinds = new java.util.TreeMap<>();
        jdbcTemplate.query("""
                SELECT OBJECT_NAME(object_id) + '.' + name AS idx, type_desc + CASE WHEN is_unique = 1 THEN ' UNIQUE' ELSE '' END AS kind
                FROM sys.indexes
                WHERE OBJECT_NAME(object_id) IN ('ix_orders', 'ix_facts') AND type > 0
                """, rs -> {
            kinds.put(rs.getString("idx"), rs.getString("kind"));
        });
        return kinds;
    }

    private static String tableDefinition(String name) {
        return databaseService.getDbObjects().stream()
                .filter(o -> o.type() == DbObjectType.TABLE && o.name().equals(name))
                .findFirst().orElseThrow().definition();
    }

    private static void assertContains(String definition, String expected) {
        assertTrue(definition.contains(expected), () -> "Missing:\n  " + expected + "\nin:\n" + definition);
    }
}

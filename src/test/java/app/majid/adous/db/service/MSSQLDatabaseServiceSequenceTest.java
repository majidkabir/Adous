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
 * Verifies that syncing a changed sequence keeps its current value (ALTER SEQUENCE
 * instead of DROP + CREATE).
 */
@Testcontainers
class MSSQLDatabaseServiceSequenceTest {

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
        jdbcTemplate.execute("DROP TABLE IF EXISTS dbo.seq_orders");
        jdbcTemplate.execute("DROP SEQUENCE IF EXISTS dbo.seq_test");
    }

    @Test
    void shouldKeepCurrentValueWhenIncrementChanges() {
        jdbcTemplate.execute("CREATE SEQUENCE dbo.seq_test AS INT START WITH 1 INCREMENT BY 1 NO CACHE");
        nextValue(); nextValue(); nextValue(); // 1, 2, 3

        apply("""
                CREATE SEQUENCE [dbo].[seq_test]
                    AS int
                    START WITH 1
                    INCREMENT BY 10
                    MINVALUE 1
                    MAXVALUE 100000
                    NO CYCLE
                    NO CACHE;
                GO
                """);

        assertEquals(13L, nextValue(), "numbering must continue from 3, not restart at 1");
        Map<String, Object> props = properties();
        assertEquals(10L, ((Number) props.get("increment")).longValue());
        assertEquals(100000L, ((Number) props.get("maximum_value")).longValue());
    }

    @Test
    void shouldAlterSequenceUsedByColumnDefault() {
        jdbcTemplate.execute("CREATE SEQUENCE dbo.seq_test AS INT START WITH 1 INCREMENT BY 1 NO CACHE");
        jdbcTemplate.execute("""
                CREATE TABLE dbo.seq_orders (
                    id INT NOT NULL CONSTRAINT DF_seq_orders_id DEFAULT (NEXT VALUE FOR dbo.seq_test),
                    note NVARCHAR(10) NULL)
                """);
        jdbcTemplate.execute("INSERT INTO dbo.seq_orders (note) VALUES ('a'), ('b')");

        apply("CREATE SEQUENCE [dbo].[seq_test] AS int START WITH 1 INCREMENT BY 5 NO CYCLE NO CACHE;\nGO\n");

        jdbcTemplate.execute("INSERT INTO dbo.seq_orders (note) VALUES ('c')");
        assertEquals(7, jdbcTemplate.queryForObject("SELECT id FROM dbo.seq_orders WHERE note = 'c'", Integer.class));
    }

    @Test
    void shouldRestartUnusedSequenceWhenStartWithChanges() {
        jdbcTemplate.execute("CREATE SEQUENCE dbo.seq_test AS BIGINT START WITH 1 INCREMENT BY 1 NO CACHE");

        apply("CREATE SEQUENCE [dbo].[seq_test] AS bigint START WITH 500 INCREMENT BY 1 NO CYCLE NO CACHE;\nGO\n");

        assertEquals(500L, ((Number) properties().get("start_value")).longValue(),
                "start_value must match the repository so the database is not reported as out of sync");
        assertEquals(500L, nextValue());
    }

    @Test
    void shouldKeepValueOfUsedSequenceWhenStartWithChanges() {
        jdbcTemplate.execute("CREATE SEQUENCE dbo.seq_test AS BIGINT START WITH 1 INCREMENT BY 1 NO CACHE");
        nextValue(); nextValue(); // 1, 2

        apply("CREATE SEQUENCE [dbo].[seq_test] AS bigint START WITH 500 INCREMENT BY 1 NO CYCLE NO CACHE;\nGO\n");

        assertEquals(3L, nextValue());
    }

    @Test
    void shouldRecreateSequenceWhenDataTypeChanges() {
        jdbcTemplate.execute("CREATE SEQUENCE dbo.seq_test AS INT START WITH 1 INCREMENT BY 1 NO CACHE");

        apply("CREATE SEQUENCE [dbo].[seq_test] AS bigint START WITH 1 INCREMENT BY 1 NO CYCLE NO CACHE;\nGO\n");

        assertEquals("bigint", jdbcTemplate.queryForObject(
                "SELECT TYPE_NAME(system_type_id) FROM sys.sequences WHERE name = 'seq_test'", String.class));
    }

    @Test
    void shouldCreateNewSequence() {
        apply("CREATE SEQUENCE [dbo].[seq_test] AS int START WITH 7 INCREMENT BY 1 NO CYCLE NO CACHE;\nGO\n");

        assertEquals(7L, nextValue());
    }

    @Test
    void shouldApplyCycleAndCacheOptions() {
        jdbcTemplate.execute("CREATE SEQUENCE dbo.seq_test AS INT START WITH 1 INCREMENT BY 1 NO CYCLE NO CACHE");
        nextValue();

        apply("CREATE SEQUENCE [dbo].[seq_test] AS int START WITH 1 INCREMENT BY 1 MINVALUE 1 MAXVALUE 10 CYCLE CACHE 20;\nGO\n");

        Map<String, Object> props = properties();
        assertEquals(true, props.get("is_cycling"));
        assertEquals(true, props.get("is_cached"));
        assertEquals(20, ((Number) props.get("cache_size")).intValue());
        assertEquals(2L, nextValue());
    }

    @Test
    void shouldReadBackSameDefinitionAfterAlter() {
        jdbcTemplate.execute("CREATE SEQUENCE dbo.seq_test AS INT START WITH 1 INCREMENT BY 1 MINVALUE 1 MAXVALUE 1000 NO CYCLE NO CACHE");
        nextValue();
        String target = databaseService.getDbObjects().stream()
                .filter(o -> o.type() == DbObjectType.SEQUENCE && o.name().equals("seq_test"))
                .findFirst().orElseThrow().definition().replace("INCREMENT BY 1", "INCREMENT BY 3");

        apply(target);

        String readBack = databaseService.getDbObjects().stream()
                .filter(o -> o.type() == DbObjectType.SEQUENCE && o.name().equals("seq_test"))
                .findFirst().orElseThrow().definition();
        assertEquals(target, readBack, "db definition must match the applied one so it is not reported as drift");
    }

    @Test
    void shouldReadDefaultCacheAsCache() {
        jdbcTemplate.execute("CREATE SEQUENCE dbo.seq_test AS INT START WITH 1 INCREMENT BY 1 CACHE");

        assertTrue(definition().contains("CACHE;"), definition());
        assertFalse(definition().contains("NO CACHE"), definition());
    }

    @Test
    void shouldReadExplicitCacheSizeAndNoCache() {
        jdbcTemplate.execute("CREATE SEQUENCE dbo.seq_test AS INT START WITH 1 INCREMENT BY 1 CACHE 50");
        assertTrue(definition().contains("CACHE 50;"), definition());

        jdbcTemplate.execute("ALTER SEQUENCE dbo.seq_test NO CACHE");
        assertTrue(definition().contains("NO CACHE;"), definition());
    }

    @Test
    void shouldKeepDefaultCacheThroughRoundTrip() {
        jdbcTemplate.execute("CREATE SEQUENCE dbo.seq_test AS INT START WITH 1 INCREMENT BY 1 CACHE");
        String original = definition();

        apply(original.replace("INCREMENT BY 1", "INCREMENT BY 2"));

        Map<String, Object> props = properties();
        assertEquals(true, props.get("is_cached"));
        assertNull(props.get("cache_size"), "default cache size must stay default");
        assertEquals(original.replace("INCREMENT BY 1", "INCREMENT BY 2"), definition());
    }

    private static String definition() {
        return databaseService.getDbObjects().stream()
                .filter(o -> o.type() == DbObjectType.SEQUENCE && o.name().equals("seq_test"))
                .findFirst().orElseThrow().definition();
    }

    private void apply(String definition) {
        databaseService.applyChangesToDatabase(List.of(
                new DbObject("dbo", "seq_test", DbObjectType.SEQUENCE, definition)));
    }

    private static long nextValue() {
        return jdbcTemplate.queryForObject("SELECT CAST(NEXT VALUE FOR dbo.seq_test AS BIGINT)", Long.class);
    }

    private static Map<String, Object> properties() {
        return jdbcTemplate.queryForMap("""
                SELECT CAST(start_value AS BIGINT) AS start_value, CAST(increment AS BIGINT) AS increment,
                       CAST(maximum_value AS BIGINT) AS maximum_value, is_cycling, is_cached, cache_size
                FROM sys.sequences WHERE name = 'seq_test'
                """);
    }
}

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
 * Verifies that applying procedures, functions and views keeps the permissions granted on
 * them (they are applied with CREATE OR ALTER rather than DROP + CREATE).
 */
@Testcontainers
class MSSQLDatabaseServicePermissionsTest {

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
        jdbcTemplate.execute("CREATE ROLE adous_perm_role");
    }

    @AfterAll
    static void tearDownAll() {
        dataSource.close();
    }

    @AfterEach
    void cleanup() {
        jdbcTemplate.execute("DROP PROCEDURE IF EXISTS dbo.perm_proc");
        jdbcTemplate.execute("DROP VIEW IF EXISTS dbo.perm_view");
        jdbcTemplate.execute("DROP FUNCTION IF EXISTS dbo.perm_func");
    }

    @Test
    void shouldKeepExecutePermissionWhenProcedureIsUpdated() {
        jdbcTemplate.execute("CREATE PROCEDURE dbo.perm_proc AS SELECT 1 AS v");
        jdbcTemplate.execute("GRANT EXECUTE ON dbo.perm_proc TO adous_perm_role");

        databaseService.applyChangesToDatabase(List.of(new DbObject("dbo", "perm_proc", DbObjectType.PROCEDURE,
                "CREATE PROCEDURE dbo.perm_proc AS SELECT 2 AS v\nGO")));

        assertEquals(List.of("EXECUTE"), permissionsOn("perm_proc"));
        assertTrue(definitionOf("perm_proc").contains("SELECT 2"), "procedure body must be updated");
    }

    @Test
    void shouldKeepSelectPermissionWhenViewIsUpdated() {
        jdbcTemplate.execute("CREATE VIEW dbo.perm_view AS SELECT 1 AS v");
        jdbcTemplate.execute("GRANT SELECT ON dbo.perm_view TO adous_perm_role");

        databaseService.applyChangesToDatabase(List.of(new DbObject("dbo", "perm_view", DbObjectType.VIEW,
                "CREATE VIEW dbo.perm_view AS SELECT 2 AS v\nGO")));

        assertEquals(List.of("SELECT"), permissionsOn("perm_view"));
        assertTrue(definitionOf("perm_view").contains("SELECT 2"));
    }

    @Test
    void shouldKeepPermissionWhenFunctionKindIsUnchanged() {
        jdbcTemplate.execute("CREATE FUNCTION dbo.perm_func() RETURNS int AS BEGIN RETURN 1 END");
        jdbcTemplate.execute("GRANT EXECUTE ON dbo.perm_func TO adous_perm_role");

        databaseService.applyChangesToDatabase(List.of(new DbObject("dbo", "perm_func", DbObjectType.FUNCTION,
                "CREATE FUNCTION dbo.perm_func() RETURNS int AS BEGIN RETURN 2 END\nGO")));

        assertEquals(List.of("EXECUTE"), permissionsOn("perm_func"));
        assertTrue(definitionOf("perm_func").contains("RETURN 2"));
    }

    @Test
    void shouldRecreateFunctionWhenKindChanges() {
        jdbcTemplate.execute("CREATE FUNCTION dbo.perm_func() RETURNS int AS BEGIN RETURN 1 END");

        databaseService.applyChangesToDatabase(List.of(new DbObject("dbo", "perm_func", DbObjectType.FUNCTION,
                "CREATE FUNCTION dbo.perm_func() RETURNS TABLE AS RETURN SELECT 1 AS v\nGO")));

        String type = jdbcTemplate.queryForObject(
                "SELECT RTRIM(type) FROM sys.objects WHERE object_id = OBJECT_ID('dbo.perm_func')", String.class);
        assertEquals("IF", type);
    }

    @Test
    void shouldReadBackCanonicalCreateDefinition() {
        jdbcTemplate.execute("CREATE PROCEDURE dbo.perm_proc AS SELECT 1 AS v");

        databaseService.applyChangesToDatabase(List.of(new DbObject("dbo", "perm_proc", DbObjectType.PROCEDURE,
                "CREATE PROCEDURE dbo.perm_proc AS SELECT 2 AS v\nGO")));

        DbObject proc = databaseService.getDbObjects().stream()
                .filter(o -> o.name().equals("perm_proc"))
                .findFirst().orElseThrow();
        assertTrue(proc.definition().contains("\r\nCREATE PROCEDURE dbo.perm_proc AS SELECT 2 AS v"),
                () -> "unexpected read-back: " + proc.definition().replace("\r", "\\r").replace("\n", "\\n"));
    }

    @Test
    void shouldCreateProcedureThatDoesNotExistYet() {
        databaseService.applyChangesToDatabase(List.of(new DbObject("dbo", "perm_proc", DbObjectType.PROCEDURE,
                "CREATE PROCEDURE dbo.perm_proc AS SELECT 3 AS v\nGO")));

        assertTrue(definitionOf("perm_proc").contains("SELECT 3"));
    }

    @Test
    void shouldDropProcedureWhenRemovedFromRepo() {
        jdbcTemplate.execute("CREATE PROCEDURE dbo.perm_proc AS SELECT 1 AS v");

        databaseService.applyChangesToDatabase(List.of(
                new DbObject("dbo", "perm_proc", DbObjectType.PROCEDURE, null)));

        assertNull(jdbcTemplate.queryForObject("SELECT OBJECT_ID('dbo.perm_proc')", Integer.class));
    }

    private static List<String> permissionsOn(String objectName) {
        return jdbcTemplate.queryForList("""
                SELECT p.permission_name
                FROM sys.database_permissions p
                JOIN sys.database_principals r ON p.grantee_principal_id = r.principal_id
                WHERE p.major_id = OBJECT_ID('dbo.' + ?) AND r.name = 'adous_perm_role' AND p.state = 'G'
                """, String.class, objectName);
    }

    private static String definitionOf(String objectName) {
        return jdbcTemplate.queryForObject("SELECT OBJECT_DEFINITION(OBJECT_ID('dbo.' + ?))", String.class, objectName);
    }
}

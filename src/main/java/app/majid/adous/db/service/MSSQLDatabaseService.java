package app.majid.adous.db.service;

import app.majid.adous.common.constants.DatabaseConstants;
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

    public MSSQLDatabaseService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Retrieves all database objects (procedures, functions, views, triggers) from the database.
     *
     * @return List of database objects with their definitions
     */
    @Override
    public List<DbObject> getDbObjects() {
        logger.debug("Fetching database objects from SQL Server");

        List<DbObject> objects = jdbcTemplate.query(buildGetObjectsQuery(), (rs, rowNum) ->
                new DbObject(
                        rs.getString("schema_name"),
                        rs.getString("name"),
                        DbObjectType.valueOf(rs.getString("object_type")),
                        rs.getString("definition")
                )
        );

        logger.debug("Retrieved {} database objects", objects.size());
        return objects;
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
     * Applies database changes transactionally.
     * Creates necessary schemas, drops existing objects, and creates new/updated objects.
     *
     * @param dbChanges List of database objects to apply
     * @throws org.springframework.dao.DataAccessException if database operation fails
     */
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
     * Applies the actual object changes (drop and recreate).
     */
    private void applyObjectChanges(List<DbObject> dbChanges) {
        ResourceDatabasePopulator populator = new ResourceDatabasePopulator();
        populator.setSeparator("GO");

        dbChanges.forEach(change -> {
            logger.debug("Processing change for {}.{} ({})",
                    change.schema(), change.name(), change.type());

            populator.addScript(toResource(buildDropQuery(change)));

            if (change.definition() != null) {
                populator.addScript(toResource(change.definition()));
            }
        });

        populator.execute(Objects.requireNonNull(jdbcTemplate.getDataSource(),
                "DataSource must not be null"));
    }

    /**
     * Builds a DROP statement for a database object.
     */
    private String buildDropQuery(DbObject obj) {
        return """
            DROP %s IF EXISTS [%s].[%s];
            GO
            """.formatted(obj.type(), obj.schema(), obj.name());
    }

    /**
     * Converts a string to a Spring Resource for use with ResourceDatabasePopulator.
     */
    private Resource toResource(String content) {
        return new InputStreamResource(
                new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)));
    }
}

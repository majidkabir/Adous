package app.majid.adous.db.service;

import app.majid.adous.db.config.DatabaseContextHolder;
import app.majid.adous.synchronizer.model.DbObject;
import app.majid.adous.synchronizer.model.DbObjectType;
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

@Service
public class DatabaseService {

    private final JdbcTemplate jdbcTemplate;

    public DatabaseService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<DbObject> getDbObjects(String dbName) {
        DatabaseContextHolder.setCurrentDb(dbName);
        return jdbcTemplate.query("""
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
                """, (rs, rowNum) ->
                new DbObject(
                        rs.getString("schema_name"),
                        rs.getString("name"),
                        DbObjectType.valueOf(rs.getString("object_type")),
                        rs.getString("definition")
                )
        );
    }

    @Transactional
    public void applyChangesToDatabase(String dbName, List<DbObject> dbChanges) {
        DatabaseContextHolder.setCurrentDb(dbName);

        ResourceDatabasePopulator populator = new ResourceDatabasePopulator();
        populator.setSeparator("GO");

        dbChanges.stream()
                .map(DbObject::schema)
                .distinct()
                .forEach(schema -> {
                    jdbcTemplate.execute(
                            "IF NOT EXISTS (SELECT * FROM sys.schemas WHERE name = '%s') " +
                                    "EXEC('CREATE SCHEMA [%s]')".formatted(schema, schema)
                    );
                });

        dbChanges.forEach(change -> {
            populator.addScript(toResource(getDropQuery(change)));
            if (change.definition() != null) {
                populator.addScript(toResource(change.definition()));
            }
        });

        populator.execute(Objects.requireNonNull(jdbcTemplate.getDataSource()));
    }

    private String getDropQuery(DbObject obj) {
        return """
            DROP %s IF EXISTS [%s].[%s];
            GO
            """.formatted(obj.type(), obj.schema(), obj.name());
    }

    private Resource toResource(String content) {
        return new InputStreamResource(new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)));
    }
}

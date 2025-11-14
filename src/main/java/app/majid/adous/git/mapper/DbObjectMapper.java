package app.majid.adous.git.mapper;

import app.majid.adous.common.constants.GitConstants;
import app.majid.adous.synchronizer.model.DbObject;
import app.majid.adous.synchronizer.model.DbObjectType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Mapper to convert Git file paths to database objects.
 * Expected path format: {rootPath}/{type}/{schema}/{name}.sql
 */
@Component
public class DbObjectMapper {

    private static final Logger logger = LoggerFactory.getLogger(DbObjectMapper.class);
    private static final int SQL_EXTENSION_LENGTH = 4; // ".sql"

    /**
     * Creates a DbObject from a Git repository file path.
     *
     * @param path The file path in format: {rootPath}/{type}/{schema}/{name}.sql
     * @param definition The SQL definition of the object (null for deletions)
     * @return DbObject parsed from the path
     * @throws IllegalArgumentException if path format is invalid
     */
    public DbObject fromPath(String path, String definition) {
        validatePath(path);

        PathComponents components = extractPathComponents(path);

        try {
            DbObjectType type = DbObjectType.valueOf(components.type().toUpperCase());

            logger.debug("Mapped path '{}' to DbObject: {}.{} ({})",
                    path, components.schema(), components.name(), type);

            return new DbObject(components.schema(), components.name(), type, definition);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Invalid database object type '" + components.type() + "' in path: " + path, e);
        }
    }

    /**
     * Validates that the path has the correct format and file extension.
     */
    private void validatePath(String path) {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("Path cannot be null or empty");
        }

        if (!path.endsWith(GitConstants.SQL_FILE_EXTENSION)) {
            throw new IllegalArgumentException("Invalid file type: " + path);
        }
    }

    /**
     * Extracts type, schema, and name components from the path.
     */
    private PathComponents extractPathComponents(String path) {
        int lastSlash = path.lastIndexOf('/');
        int secondSlash = path.lastIndexOf('/', lastSlash - 1);
        int thirdSlash = path.lastIndexOf('/', secondSlash - 1);

        if (thirdSlash < 0 || lastSlash < 0 || secondSlash < 0) {
            throw new IllegalArgumentException("Invalid path: " + path);
        }

        String type = path.substring(thirdSlash + 1, secondSlash);
        String schema = path.substring(secondSlash + 1, lastSlash);
        String name = path.substring(lastSlash + 1, path.length() - SQL_EXTENSION_LENGTH);

        if (type.isBlank() || schema.isBlank() || name.isBlank()) {
            throw new IllegalArgumentException(
                    "Path components cannot be empty. Type: '" + type +
                    "', Schema: '" + schema + "', Name: '" + name + "'");
        }

        return new PathComponents(type, schema, name);
    }

    /**
     * Record to hold extracted path components.
     */
    private record PathComponents(String type, String schema, String name) {}
}

package app.majid.adous.synchronizer.model;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * A repository file that a db-to-repo sync wrote (or, in a dry run, would write).
 */
@Schema(description = "Repository file changed by a db-to-repo sync")
public record RepoFileChange(
        @Schema(description = "Path of the file in the repository",
                example = "diff/prod/db2/PROCEDURE/dbo/usp_get_orders.sql")
        String path,

        @Schema(description = "Object type, from the path", example = "PROCEDURE")
        DbObjectType type,

        @Schema(description = "Schema name, from the path", example = "dbo")
        String schema,

        @Schema(description = "Object name, from the path", example = "usp_get_orders")
        String name,

        @Schema(description = """
                WRITE: file written with the object's definition. \
                DELETE: database-specific override removed (the database matches base again). \
                MARK_ABSENT: empty override written, recording that the object does not exist in this database.""",
                example = "WRITE")
        Action action
) {

    public enum Action { WRITE, DELETE, MARK_ABSENT }

    public static RepoFileChange from(RepoObject object) {
        Action action = object.definition() == null ? Action.DELETE
                : object.definition().isEmpty() ? Action.MARK_ABSENT
                : Action.WRITE;

        // Paths end with {TYPE}/{schema}/{name}.sql
        String[] parts = object.path().split("/");
        DbObjectType type = null;
        String schema = null;
        String name = null;
        if (parts.length >= 3 && parts[parts.length - 1].endsWith(".sql")) {
            name = parts[parts.length - 1].substring(0, parts[parts.length - 1].length() - 4);
            schema = parts[parts.length - 2];
            try {
                type = DbObjectType.valueOf(parts[parts.length - 3]);
            } catch (IllegalArgumentException e) {
                type = null;
            }
        }
        return new RepoFileChange(object.path(), type, schema, name, action);
    }
}

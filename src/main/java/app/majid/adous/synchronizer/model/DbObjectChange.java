package app.majid.adous.synchronizer.model;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * A database object that a repo-to-db sync applied (or, in a dry run, would apply).
 */
@Schema(description = "Database object applied by a repo-to-db sync")
public record DbObjectChange(
        @Schema(description = "Object type", example = "PROCEDURE")
        DbObjectType type,

        @Schema(description = "Schema name", example = "dbo")
        String schema,

        @Schema(description = "Object name", example = "usp_get_orders")
        String name,

        @Schema(description = "What happens to the object in the database", example = "CREATE_OR_UPDATE")
        Action action
) {

    public enum Action {
        /** The object is created, or updated to the repository definition. */
        CREATE_OR_UPDATE,
        /** The object is dropped from the database. */
        DROP
    }

    public static DbObjectChange from(DbObject object) {
        return new DbObjectChange(object.type(), object.schema(), object.name(),
                object.definition() == null ? Action.DROP : Action.CREATE_OR_UPDATE);
    }
}

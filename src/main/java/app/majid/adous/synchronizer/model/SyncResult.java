package app.majid.adous.synchronizer.model;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "Result of synchronizing a database")
public record SyncResult(
        @Schema(description = "Name of the database", example = "MyDatabase")
        String dbName,

        @Schema(description = "Synchronization status", example = "SYNCED")
        Status status,

        @Schema(description = "Error or skip reason. For successful syncs it is a text list of the changes, "
                + "kept for compatibility; use `changes` instead.",
                example = "[DbObject[schema=dbo, name=usp_get_orders, type=PROCEDURE]]")
        String message,

        @Schema(description = "Objects applied (or, in a dry run, that would be applied); empty when skipped or failed")
        List<DbObjectChange> changes
) {

    public SyncResult {
        changes = changes == null ? List.of() : List.copyOf(changes);
    }

    /** A result without object changes (skipped or failed). */
    public SyncResult(String dbName, Status status, String message) {
        this(dbName, status, message, List.of());
    }

    @Schema(description = "Synchronization status values")
    public enum Status {
        @Schema(description = "Successfully synchronized")
        SYNCED,

        @Schema(description = "Dry run completed successfully")
        SUCCESS_DRY_RUN,

        @Schema(description = "Skipped - database not onboarded to repository")
        SKIPPED_NOT_ONBOARDED,

        @Schema(description = "Skipped - database is out of sync (use force to override)")
        SKIPPED_OUT_OF_SYNC,

        @Schema(description = "Skipped - changes would drop tables or columns (use allowDataLoss to override)")
        SKIPPED_DATA_LOSS,

        @Schema(description = "Synchronization failed")
        FAILED
    }
}

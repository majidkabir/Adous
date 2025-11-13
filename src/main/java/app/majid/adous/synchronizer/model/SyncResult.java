package app.majid.adous.synchronizer.model;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Result of synchronizing a database")
public record SyncResult(
        @Schema(description = "Name of the database", example = "MyDatabase")
        String dbName,

        @Schema(description = "Synchronization status", example = "SYNCED")
        Status status,

        @Schema(description = "Details or error message", example = "[DbObject(name=dbo.Users, type=TABLE)]")
        String message
) {
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

        @Schema(description = "Synchronization failed")
        FAILED
    }
}

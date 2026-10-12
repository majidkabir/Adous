package app.majid.adous.synchronizer.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "Result of synchronizing a database")
public record SyncResult(
        @Schema(description = "Name of the database", example = "MyDatabase")
        String dbName,

        @Schema(description = "Synchronization status", example = "SYNCED")
        Status status,

        @Schema(description = "Why the database was skipped or failed; omitted on success",
                example = "The database 'db2' has not been onboarded to the repository yet")
        @JsonInclude(JsonInclude.Include.NON_NULL)
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

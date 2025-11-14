package app.majid.adous.synchronizer.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

@Schema(description = "Request to sync database to repository")
public record SyncDbToRepoRequest(
        @Schema(description = "Name of the database to sync", example = "MyDatabase", required = true)
        @NotBlank(message = "Database name is required")
        String dbName,

        @Schema(description = "Perform dry run without committing changes", example = "false", defaultValue = "false")
        boolean dryRun
) {
}

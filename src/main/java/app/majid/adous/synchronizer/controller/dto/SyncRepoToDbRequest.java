package app.majid.adous.synchronizer.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

@Schema(description = "Request to sync repository to databases")
public record SyncRepoToDbRequest(
        @Schema(description = "Git commit, branch, or tag reference to sync from", example = "main", required = true)
        @NotBlank(message = "Commitish is required")
        String commitish,

        @Schema(description = "List of database names to sync", example = "[\"Database1\", \"Database2\"]", required = true)
        @NotEmpty(message = "At least one database is required")
        List<String> dbs,

        @Schema(description = "Perform dry run without applying changes", example = "false", defaultValue = "false")
        boolean dryRun,

        @Schema(description = "Force sync even if database is out of sync", example = "false", defaultValue = "false")
        boolean force
) {
}

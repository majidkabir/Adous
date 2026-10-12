package app.majid.adous.synchronizer.controller.dto;

import app.majid.adous.synchronizer.model.RepoFileChange;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "Response from syncing database to repository")
public record SyncDbToRepoResponse(
        @Schema(description = "Name of the database that was synced", example = "MyDatabase")
        String dbName,

        @Schema(description = "Whether this was a dry run", example = "false")
        boolean dryRun,

        @Schema(description = "Text list of the repository files changed. Deprecated: use `changes`.",
                example = "[RepoObject[path=base/TABLE/dbo/users.sql]]", deprecated = true)
        String result,

        @Schema(description = "Success or error message", example = "Sync completed successfully")
        String message,

        @Schema(description = "Repository files written (or, in a dry run, that would be written)")
        List<RepoFileChange> changes
) {
}

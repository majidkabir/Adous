package app.majid.adous.synchronizer.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Response from syncing database to repository")
public record SyncDbToRepoResponse(
        @Schema(description = "Name of the database that was synced", example = "MyDatabase")
        String dbName,

        @Schema(description = "Whether this was a dry run", example = "false")
        boolean dryRun,

        @Schema(description = "Details of repository objects that were synced", example = "[RepoObject(path=base/table/dbo/Users.sql)]")
        String result,

        @Schema(description = "Success or error message", example = "Sync completed successfully")
        String message
) {
}

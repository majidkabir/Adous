package app.majid.adous.synchronizer.controller;

import app.majid.adous.synchronizer.controller.dto.SyncDbToRepoResponse;
import app.majid.adous.synchronizer.controller.dto.SyncRepoToDbRequest;
import app.majid.adous.synchronizer.model.SyncResult;
import app.majid.adous.synchronizer.service.DatabaseRepositorySynchronizerService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.List;

@RestController
@RequestMapping("/api/synchronizer")
@Validated
@Tag(name = "Database Synchronizer", description = "APIs for synchronizing databases with Git repository")
public class SynchronizerController {

    private final DatabaseRepositorySynchronizerService synchronizerService;

    public SynchronizerController(DatabaseRepositorySynchronizerService synchronizerService) {
        this.synchronizerService = synchronizerService;
    }

    @PostMapping("/db-to-repo/{dbName}")
    @Operation(
            summary = "Sync database to repository",
            description = "Synchronizes a database schema to the Git repository. Can be run in dry-run mode to preview changes without committing."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Successfully synced database to repository",
                    content = @Content(schema = @Schema(implementation = SyncDbToRepoResponse.class))),
            @ApiResponse(responseCode = "400", description = "Invalid request or database in invalid state",
                    content = @Content(schema = @Schema(implementation = GlobalExceptionHandler.ErrorResponse.class))),
            @ApiResponse(responseCode = "500", description = "Internal server error or Git operation failed",
                    content = @Content(schema = @Schema(implementation = GlobalExceptionHandler.ErrorResponse.class)))
    })
    public SyncDbToRepoResponse syncDbToRepo(
            @Parameter(description = "Database name to sync", required = true)
            @PathVariable String dbName,
            @Parameter(description = "Perform dry run without committing changes", example = "false")
            @RequestParam(defaultValue = "false") boolean dryRun)
            throws IOException, GitAPIException {
        String result = synchronizerService.syncDbToRepo(dbName, dryRun);
        return new SyncDbToRepoResponse(
                dbName,
                dryRun,
                result,
                "Sync completed successfully"
        );
    }

    @PostMapping("/repo-to-db")
    @Operation(
            summary = "Sync repository to databases",
            description = "Synchronizes a Git repository commit to one or more databases. Supports batch processing and can force sync even when databases are out of sync."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Successfully processed sync requests",
                    content = @Content(schema = @Schema(implementation = SyncResult.class))),
            @ApiResponse(responseCode = "400", description = "Invalid request or validation error",
                    content = @Content(schema = @Schema(implementation = GlobalExceptionHandler.ValidationErrorResponse.class))),
            @ApiResponse(responseCode = "500", description = "Internal server error",
                    content = @Content(schema = @Schema(implementation = GlobalExceptionHandler.ErrorResponse.class)))
    })
    public List<SyncResult> syncRepoToDb(@Valid @RequestBody SyncRepoToDbRequest request)
            throws IOException {
        return synchronizerService.syncRepoToDb(
                request.commitish(),
                request.dbs(),
                request.dryRun(),
                request.force()
        );
    }
}


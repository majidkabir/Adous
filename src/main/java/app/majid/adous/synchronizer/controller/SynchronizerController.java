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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.List;

/**
 * REST controller for database and repository synchronization operations.
 * Provides endpoints for bidirectional sync between databases and Git repository.
 */
@RestController
@RequestMapping("/api/synchronizer")
@Validated
@Tag(name = "Database Synchronizer", description = "APIs for synchronizing databases with Git repository")
public class SynchronizerController {

    private static final Logger logger = LoggerFactory.getLogger(SynchronizerController.class);

    private final DatabaseRepositorySynchronizerService synchronizerService;

    public SynchronizerController(DatabaseRepositorySynchronizerService synchronizerService) {
        this.synchronizerService = synchronizerService;
    }

    @PostMapping("/init-repo/{dbName}")
    @Operation(
            summary = "Initialize repository with database",
            description = "Initializes an empty Git repository with all database objects from the specified database. This operation can only be performed on an empty repository."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Successfully initialized repository",
                    content = @Content(schema = @Schema(implementation = SyncDbToRepoResponse.class))),
            @ApiResponse(responseCode = "400", description = "Repository is not empty or database has no objects",
                    content = @Content(schema = @Schema(implementation = GlobalExceptionHandler.ErrorResponse.class))),
            @ApiResponse(responseCode = "500", description = "Internal server error or Git operation failed",
                    content = @Content(schema = @Schema(implementation = GlobalExceptionHandler.ErrorResponse.class)))
    })
    public SyncDbToRepoResponse initRepo(
            @Parameter(description = "Database name to initialize repository from", required = true)
            @PathVariable String dbName)
            throws IOException, GitAPIException {
        logger.info("Received request to initialize repository with database: {}", dbName);

        String result = synchronizerService.initRepo(dbName);

        logger.info("Successfully initialized repository with database: {}", dbName);
        return new SyncDbToRepoResponse(
                dbName,
                false,
                result,
                "Repository initialized successfully"
        );
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
        logger.info("Received request to sync repository '{}' to {} database(s) (dryRun: {}, force: {})",
                request.commitish(), request.dbs().size(), request.dryRun(), request.force());

        List<SyncResult> results = synchronizerService.syncRepoToDb(
                request.commitish(),
                request.dbs(),
                request.dryRun(),
                request.force()
        );

        logger.info("Completed repository to database sync request with {} results", results.size());

        return results;
    }
}


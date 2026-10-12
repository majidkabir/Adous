package app.majid.adous.synchronizer.service;

import app.majid.adous.synchronizer.model.RepoObject;
import app.majid.adous.synchronizer.model.SyncResult;
import org.eclipse.jgit.api.errors.GitAPIException;

import java.io.IOException;
import java.util.List;

/**
 * Interface for database and repository synchronization operations.
 * Defines the contract for bidirectional synchronization between databases and Git repository.
 */
public interface SynchronizerService {

    /**
     * Initializes a Git repository with database objects from the specified database.
     * This should only be called on an empty repository.
     *
     * @param dbName The name of the database to initialize from
     * @return The repository files written
     * @throws IOException if Git operations fail
     * @throws GitAPIException if Git API operations fail
     * @throws IllegalStateException if repository is not empty or database has no objects
     */
    List<RepoObject> initRepo(String dbName) throws IOException, GitAPIException;

    /**
     * Synchronizes database changes to the repository.
     *
     * @param dbName The name of the database to sync
     * @param dryRun If true, only detect changes without committing
     * @return The repository files changed (or, in a dry run, that would be changed)
     * @throws IOException if Git operations fail
     * @throws GitAPIException if Git API operations fail
     */
    List<RepoObject> syncDbToRepo(String dbName, boolean dryRun) throws IOException, GitAPIException;

    /**
     * Synchronizes repository changes to multiple databases in parallel.
     *
     * @param commitish Git reference (commit, branch, tag) to sync from
     * @param dbs List of database names to sync to
     * @param dryRun If true, only detect changes without applying
     * @param force If true, sync even if databases are out of sync
     * @param allowDataLoss If true, apply changes that drop tables or columns;
     *                      otherwise such databases are skipped with SKIPPED_DATA_LOSS
     * @return List of sync results for each database
     * @throws IOException if Git operations fail
     */
    List<SyncResult> syncRepoToDb(String commitish, List<String> dbs, boolean dryRun, boolean force,
                                  boolean allowDataLoss)
            throws IOException, GitAPIException;

    /**
     * Same as {@link #syncRepoToDb(String, List, boolean, boolean, boolean)} with
     * data-loss changes disallowed.
     */
    default List<SyncResult> syncRepoToDb(String commitish, List<String> dbs, boolean dryRun, boolean force)
            throws IOException, GitAPIException {
        return syncRepoToDb(commitish, dbs, dryRun, force, false);
    }
}


package app.majid.adous.synchronizer.service;

import app.majid.adous.synchronizer.exception.DbNotOnboardedException;
import app.majid.adous.synchronizer.exception.DbOutOfSyncException;
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
     * @return String representation of the changes made
     * @throws IOException if Git operations fail
     * @throws GitAPIException if Git API operations fail
     * @throws IllegalStateException if repository is not empty or database has no objects
     */
    String initRepo(String dbName) throws IOException, GitAPIException;

    /**
     * Synchronizes database changes to the repository.
     *
     * @param dbName The name of the database to sync
     * @param dryRun If true, only detect changes without committing
     * @return String representation of the changes detected/made
     * @throws IOException if Git operations fail
     * @throws GitAPIException if Git API operations fail
     */
    String syncDbToRepo(String dbName, boolean dryRun) throws IOException, GitAPIException;

    /**
     * Synchronizes repository changes to multiple databases in parallel.
     *
     * @param commitish Git reference (commit, branch, tag) to sync from
     * @param dbs List of database names to sync to
     * @param dryRun If true, only detect changes without applying
     * @param force If true, sync even if databases are out of sync
     * @return List of sync results for each database
     * @throws IOException if Git operations fail
     */
    List<SyncResult> syncRepoToDb(String commitish, List<String> dbs, boolean dryRun, boolean force)
            throws IOException, GitAPIException;
}


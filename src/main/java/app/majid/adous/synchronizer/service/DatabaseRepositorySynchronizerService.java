package app.majid.adous.synchronizer.service;

import app.majid.adous.db.aspect.UseDatabase;
import app.majid.adous.db.config.DatabaseContextHolder;
import app.majid.adous.git.service.GitService;
import app.majid.adous.synchronizer.db.DatabaseService;
import app.majid.adous.synchronizer.exception.DbNotOnboardedException;
import app.majid.adous.synchronizer.exception.DbOutOfSyncException;
import app.majid.adous.synchronizer.model.DbObject;
import app.majid.adous.synchronizer.model.FullObject;
import app.majid.adous.synchronizer.model.RepoObject;
import app.majid.adous.synchronizer.model.SyncResult;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Constants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

/**
 * Core service for synchronizing databases with Git repositories.
 * Handles bidirectional synchronization between database objects and repository files.
 */
@Service
public class DatabaseRepositorySynchronizerService implements SynchronizerService {

    private static final Logger logger = LoggerFactory.getLogger(DatabaseRepositorySynchronizerService.class);

    private final GitService gitService;
    private final DatabaseService databaseService;
    private final SqlEquivalenceCheckerService sqlEquivalenceCheckerService;
    private final SynchronizerIgnoreService ignoreService;
    private final TransactionTemplate transactionTemplate;

    public DatabaseRepositorySynchronizerService(
            GitService gitService,
            DatabaseService databaseService,
            SqlEquivalenceCheckerService sqlEquivalenceCheckerService,
            SynchronizerIgnoreService ignoreService,
            TransactionTemplate transactionTemplate) {
        this.gitService = gitService;
        this.databaseService = databaseService;
        this.sqlEquivalenceCheckerService = sqlEquivalenceCheckerService;
        this.ignoreService = ignoreService;
        this.transactionTemplate = transactionTemplate;
    }

    @UseDatabase("dbName")
    public String initRepo(String dbName) throws IOException, GitAPIException {
        dbName = dbName.toLowerCase();
        logger.info("Initializing repository with database: {}", dbName);

        if (!gitService.isEmptyRepo()) {
            throw new IllegalStateException("Cannot initialize non-empty repository");
        }

        List<DbObject> dbObjects = databaseService.getDbObjects();

        if (dbObjects.isEmpty()) {
            throw new IllegalStateException("No database objects found in database: " + dbName);
        }

        logger.debug("Found {} database objects to initialize repository", dbObjects.size());

        List<RepoObject> repoChanges = dbObjects.stream()
                .map(o -> dbObjectToRepoObject(o, gitService.getBasePath()))
                .filter(o -> ignoreService.shouldProcess(o.path()))
                .toList();

        logger.debug("After filtering, {} objects will be committed", repoChanges.size());

        gitService.applyChangesAndPush(repoChanges, "Repo initialized with DB: " + dbName,
                List.of(dbName));

        logger.info("Successfully initialized repository with database: {}", dbName);
        return repoChanges.toString();
    }

    @UseDatabase("dbName")
    public String syncDbToRepo(String dbName, boolean dryRun) throws IOException, GitAPIException {
        dbName = dbName.toLowerCase();
        logger.info("Syncing database '{}' to repository (dryRun: {})", dbName, dryRun);

        // Ensure local repo is up to date
        gitService.syncRemote();

        List<RepoObject> outOfSyncObjects = detectOutOfSyncDbObjects(dbName);

        logger.debug("Found {} out-of-sync objects", outOfSyncObjects.size());

        if (!dryRun && !outOfSyncObjects.isEmpty()) {
            List<String> tags = isDbOnboardedInRepo(dbName) ? Collections.emptyList() : List.of(dbName);
            gitService.applyChangesAndPush(outOfSyncObjects, "Repo synced with DB: " + dbName, tags);
            logger.info("Successfully synced {} objects from database '{}' to repository",
                    outOfSyncObjects.size(), dbName);
        } else if (dryRun && !outOfSyncObjects.isEmpty()) {
            logger.info("Dry run detected {} changes for database '{}'",
                    outOfSyncObjects.size(), dbName);
        } else {
            logger.info("No changes detected for database '{}'", dbName);
        }

        return outOfSyncObjects.toString();
    }

    public List<SyncResult> syncRepoToDb(String commitish, List<String> dbs, boolean dryRun, boolean force)
            throws IOException, GitAPIException {
        if (dbs == null || dbs.isEmpty()) {
            logger.debug("No databases specified for sync");
            return Collections.emptyList();
        }

        logger.info("Syncing repository commit '{}' to {} databases (dryRun: {}, force: {})",
                commitish, dbs.size(), dryRun, force);

        // Ensure local repo is up to date
        gitService.syncRemote();

        // Force dry run if not syncing to HEAD
        if (!gitService.isHeadCommit(commitish) && !force) {
            logger.info("Commit '{}' is not HEAD, forcing dry run", commitish);
            dryRun = true;
        }

        boolean finalDryRun = dryRun;

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<CompletableFuture<SyncResult>> futures = dbs.stream()
                    .map(dbName -> CompletableFuture.supplyAsync(() ->
                            syncDatabaseWithExceptionHandling(commitish, dbName, finalDryRun, force),
                            executor))
                    .toList();

            List<SyncResult> results = futures.stream()
                    .map(CompletableFuture::join)
                    .toList();

            logSyncSummary(results);
            return results;
        }
    }

    /**
     * Syncs a single database with proper exception handling.
     */
    private SyncResult syncDatabaseWithExceptionHandling(String commitish, String dbName,
                                                         boolean dryRun, boolean force) {
        dbName = dbName.toLowerCase();
        try {
            String result = syncRepoToDb(commitish, dbName, dryRun, force);
            SyncResult.Status status = dryRun ? SyncResult.Status.SUCCESS_DRY_RUN : SyncResult.Status.SYNCED;
            logger.info("Successfully synced database '{}' (status: {})", dbName, status);
            return new SyncResult(dbName, status, result);
        } catch (DbNotOnboardedException e) {
            logger.warn("Database '{}' is not onboarded, skipping", dbName);
            return new SyncResult(dbName, SyncResult.Status.SKIPPED_NOT_ONBOARDED, "");
        } catch (DbOutOfSyncException e) {
            logger.warn("Database '{}' is out of sync, skipping", dbName);
            return new SyncResult(dbName, SyncResult.Status.SKIPPED_OUT_OF_SYNC, "");
        } catch (IOException | GitAPIException e) {
            logger.error("Failed to sync database '{}'", dbName, e);
            return new SyncResult(dbName, SyncResult.Status.FAILED, e.getMessage());
        }
    }

    /**
     * Logs a summary of synchronization results.
     */
    private void logSyncSummary(List<SyncResult> results) {
        long synced = results.stream().filter(r -> r.status() == SyncResult.Status.SYNCED).count();
        long skipped = results.stream().filter(r -> r.status().name().startsWith("SKIPPED")).count();
        long failed = results.stream().filter(r -> r.status() == SyncResult.Status.FAILED).count();

        logger.info("Sync summary - Total: {}, Synced: {}, Skipped: {}, Failed: {}",
                results.size(), synced, skipped, failed);
    }

    /**
     * Synchronizes repository changes to a single database.
     *
     * @param commitish Git reference to sync from
     * @param dbName Database name to sync to
     * @param dryRun If true, only detect changes without applying
     * @param force If true, sync even if database is out of sync
     * @return String representation of changes applied
     * @throws IOException if Git operations fail
     * @throws GitAPIException if Git API operations fail
     * @throws DbNotOnboardedException if database is not onboarded
     * @throws DbOutOfSyncException if database is out of sync and force is false
     */
    private String syncRepoToDb(String commitish, String dbName, boolean dryRun, boolean force)
            throws IOException, GitAPIException, DbNotOnboardedException, DbOutOfSyncException {

        try {
            DatabaseContextHolder.setCurrentDb(dbName);

            logger.debug("Starting sync for database '{}' from commit '{}'", dbName, commitish);

            validateDatabaseOnboarded(dbName);

            if (!force) {
                validateDatabaseInSync(dbName);
            }

            List<DbObject> dbChanges = gitService.getRepoChangesToApplyToDb(commitish, dbName);
            logger.debug("Found {} changes to apply to database '{}'", dbChanges.size(), dbName);

            if (!dryRun) {
                if (!dbChanges.isEmpty()) {
                    applyChangesTransactionally(dbChanges, dbName, commitish);
                } else {
                    gitService.addTags(List.of(dbName), commitish);
                    logger.info("Database '{}' already is already in sync with repo, tagged commit", dbName);
                }
            }

            return dbChanges.toString();
        } finally {
            DatabaseContextHolder.clear();
        }
    }

    /**
     * Validates that a database has been onboarded to the repository.
     */
    private void validateDatabaseOnboarded(String dbName) throws IOException, DbNotOnboardedException {
        if (!isDbOnboardedInRepo(dbName)) {
            throw new DbNotOnboardedException(dbName);
        }
    }

    /**
     * Validates that a database is in sync with the repository.
     */
    private void validateDatabaseInSync(String dbName) throws IOException, DbOutOfSyncException {
        List<RepoObject> outOfSyncObjects = detectOutOfSyncDbObjects(dbName);
        if (!outOfSyncObjects.isEmpty()) {
            throw new DbOutOfSyncException(dbName, outOfSyncObjects);
        }
    }

    /**
     * Applies database changes within a transaction.
     */
    private void applyChangesTransactionally(List<DbObject> dbChanges, String dbName, String commitish) {
        transactionTemplate.execute(status -> {
            try {
                databaseService.applyChangesToDatabase(dbChanges);
                gitService.addTags(List.of(dbName), commitish);
                logger.info("Applied {} changes to database '{}' and tagged commit",
                        dbChanges.size(), dbName);
                return null;
            } catch (GitAPIException | IOException e) {
                logger.error("Failed to apply changes or tag commit for database '{}'", dbName, e);
                status.setRollbackOnly();
                throw new RuntimeException("Failed to apply database changes", e);
            }
        });
    }

    private boolean isDbOnboardedInRepo(String dbName) throws IOException {
        return gitService.tagExists(dbName);
    }

    private List<RepoObject> detectOutOfSyncDbObjects(String dbName) throws IOException {
        var outOfSyncObjects = isDbOnboardedInRepo(dbName)
            ? computeDbDiffForCommit(gitService.getTag(dbName), dbName)
            : computeDbDiffForCommit(Constants.HEAD, dbName);

        return outOfSyncObjects.stream()
                .filter(o -> notExistsInRepoHead(o, dbName))
                .toList();
    }

    private boolean notExistsInRepoHead(RepoObject repoObject, String dbName) {
        var repoDiffDef = gitService.getFileContentAtRef(Constants.HEAD, repoObject.path())
                .orElse(null);
        var basePath = repoObject.path().
                replace(gitService.getDiffPath() + "/" + dbName, gitService.getBasePath());
        var repoBaseDef = gitService.getFileContentAtRef(Constants.HEAD, basePath)
                .orElse(null);
        return !(sqlEquivalenceCheckerService.equals(repoObject.definition(), repoBaseDef) ||
                sqlEquivalenceCheckerService.equals(repoObject.definition(), repoDiffDef));
    }

    private List<RepoObject> computeDbDiffForCommit(String commitish, String dbName) throws IOException {
        var allObjects = getAllObjectsForDb(commitish, dbName);

        // Process in parallel and collect directly, avoiding synchronized ArrayList
        return allObjects.values()
                .parallelStream()
                .map(this::computeDiffForObject)
                .filter(Objects::nonNull)
                .filter(o -> ignoreService.shouldProcess(o.path()))
                .toList();
    }

    /**
     * Computes the diff for a single object. Returns null if no diff needed.
     */
    private RepoObject computeDiffForObject(FullObject o) {
        // Case 1: DB definition matches base definition
        if (sqlEquivalenceCheckerService.equals(o.getDbDefinition(), o.getBaseDefinition())) {
            // Remove diff file if it exists
            if (o.getDiffDefinition() != null) {
                return new RepoObject(o.getDiffPath(), null);
            }
            return null;
        }

        // Case 2: DB object was deleted
        if (o.getDbDefinition() == null) {
            // Add empty diff file if it doesn't exist or isn't empty
            if (!"".equals(o.getDiffDefinition())) {
                return new RepoObject(o.getDiffPath(), "");
            }
            return null;
        }

        // Case 3: DB definition differs from both base and diff
        if (!sqlEquivalenceCheckerService.equals(o.getDbDefinition(), o.getDiffDefinition())) {
            return new RepoObject(o.getDiffPath(), o.getDbDefinition());
        }

        return null;
    }

    private Map<String, FullObject> getAllObjectsForDb(String commitish, String dbName) throws IOException {
        var baseRootPath = gitService.getBasePath();
        var diffRootPath = gitService.getDiffPath() + "/" + dbName;

        var dbObjects = databaseService.getDbObjects();
        var baseObjects = gitService.getAllFilesAtRef(commitish, baseRootPath);
        var diffObjectsForDb = gitService.getAllFilesAtRef(commitish, diffRootPath);

        // Pre-size the map to reduce resizing overhead
        int estimatedSize = Math.max(dbObjects.size(), Math.max(baseObjects.size(), diffObjectsForDb.size()));
        var all = new ConcurrentHashMap<String, FullObject>(estimatedSize);

        // Process DB objects - this creates the initial entries
        dbObjects
                .parallelStream()
                .forEach(dbObject -> {
                    var key = dbObject.type() + "/" + dbObject.schema() + "/" + dbObject.name();
                    var o = initFullObjectFromKey(key, dbName);
                    o.setDbDefinition(dbObject.definition());
                    all.put(key, o);
                });

        // Process base objects - merge with existing entries
        baseObjects.entrySet()
                .parallelStream()
                .forEach(e -> {
                    var key = getKeyFromGitPath(e.getKey());
                    var o = all.computeIfAbsent(key, k -> initFullObjectFromKey(k, dbName));
                    o.setBaseDefinition(e.getValue());
                });

        // Process diff objects - merge with existing entries
        diffObjectsForDb.entrySet()
                .parallelStream()
                .forEach(e -> {
                    var key = getKeyFromGitPath(e.getKey());
                    var o = all.computeIfAbsent(key, k -> initFullObjectFromKey(k, dbName));
                    o.setDiffDefinition(e.getValue());
                });

        return all;
    }

    private FullObject initFullObjectFromKey(String key, String dbName) {
        return new FullObject(
                key,
                gitService.getBasePath(),
                gitService.getDiffPath() + "/" + dbName
        );
    }

    private String getKeyFromGitPath(String path) {
        if (!path.endsWith(".sql")) {
            throw new IllegalArgumentException("Invalid file type: " + path);
        }

        int lastSlash = path.lastIndexOf('/');
        int secondSlash = path.lastIndexOf('/', lastSlash - 1);
        int thirdSlash = path.lastIndexOf('/', secondSlash - 1);

        if (thirdSlash < 0) {
            throw new IllegalArgumentException("Invalid path: " + path);
        }

        return path.substring(thirdSlash + 1, path.length() - 4); // type/schema/file
    }

    private RepoObject dbObjectToRepoObject(DbObject dbObject, String rootPath) {
        return new RepoObject(
                rootPath + "/" + dbObject.type() + "/" + dbObject.schema() + "/" + dbObject.name() + ".sql",
                dbObject.definition()
        );
    }
}

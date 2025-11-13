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
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

@Service
public class DatabaseRepositorySynchronizerService {

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
        if (!gitService.isEmptyRepo()) {
            throw new IllegalStateException("Cannot initialize non-empty repository");
        }

        List<DbObject> dbObjects = databaseService.getDbObjects();

        if (dbObjects.isEmpty()) {
            throw new IllegalStateException("No database objects found in database: " + dbName);
        }

        List<RepoObject> repoChanges = dbObjects.stream()
                .map(o -> dbObjectToRepoObject(o, gitService.getBasePath()))
                .filter(o -> !ignoreService.shouldIgnore(o.path()))
                .toList();

        gitService.applyChangesAndPush(repoChanges, "Repo initialized with DB: " + dbName,
                List.of(dbName));

        return repoChanges.toString();
    }

    @UseDatabase("dbName")
    public String syncDbToRepo(String dbName, boolean dryRun) throws IOException, GitAPIException {
        List<RepoObject> outOfSyncObjects = detectOutOfSyncDbObjects(dbName);

        if (!dryRun && !outOfSyncObjects.isEmpty()) {
            List<String> tags = isDbOnboardedInRepo(dbName) ? Collections.emptyList() : List.of(dbName);
            gitService.applyChangesAndPush(outOfSyncObjects, "Repo synced with DB: " + dbName, tags);
        }

        return outOfSyncObjects.toString();
    }

    public List<SyncResult> syncRepoToDb(String commitish, List<String> dbs, boolean dryRun, boolean force)
            throws IOException {
        if (dbs.isEmpty()) return Collections.emptyList();

        if (!gitService.isHeadCommit(commitish)) {
            dryRun = true;
        }

        boolean finalDryRun = dryRun;

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<CompletableFuture<SyncResult>> futures = dbs.stream()
                    .map(dbName -> CompletableFuture.supplyAsync(() -> {
                        try {
                            var result = syncRepoToDb(commitish, dbName, finalDryRun, force);
                            var status = finalDryRun ? SyncResult.Status.SUCCESS_DRY_RUN : SyncResult.Status.SYNCED;
                            return new SyncResult(dbName, status, result);
                        } catch (DbNotOnboardedException e) {
                            return new SyncResult(dbName, SyncResult.Status.SKIPPED_NOT_ONBOARDED, "");
                        } catch (DbOutOfSyncException e) {
                            return new SyncResult(dbName, SyncResult.Status.SKIPPED_OUT_OF_SYNC, "");
                        } catch (IOException | GitAPIException e) {
                            return new SyncResult(dbName, SyncResult.Status.FAILED, e.getMessage());
                        }
                    }, executor))
                    .toList();

            return futures.stream()
                    .map(CompletableFuture::join)
                    .toList();
        }
    }

    private String syncRepoToDb(String commitish, String dbName, boolean dryRun, boolean force)
            throws IOException, GitAPIException, DbNotOnboardedException, DbOutOfSyncException {

        try {
            DatabaseContextHolder.setCurrentDb(dbName);

            if (!isDbOnboardedInRepo(dbName)) {
                throw new DbNotOnboardedException(dbName);
            }

            if (!force) {
                var outOfSyncObjects = detectOutOfSyncDbObjects(dbName);
                if (!outOfSyncObjects.isEmpty()) {
                    throw new DbOutOfSyncException(dbName, outOfSyncObjects);
                }
            }

            List<DbObject> dbChanges = gitService.getRepoChangesToApplyToDb(commitish, dbName);

            if (!dryRun) {
                transactionTemplate.execute(status -> {
                    try {
                        databaseService.applyChangesToDatabase(dbChanges);
                        gitService.addTags(List.of(dbName), commitish);
                        return null;
                    } catch (GitAPIException | IOException e) {
                        status.setRollbackOnly();
                        throw new RuntimeException(e);
                    }
                });
            }
            return dbChanges.toString();
        } finally {
            DatabaseContextHolder.clear();
        }
    }

    private boolean notExistsInRepoHead(RepoObject repoObject, String dbName) {
        var repoDiffDef = gitService.getFileContentAtRef(Constants.HEAD, repoObject.path())
                .orElse(null);
        var basePath = repoObject.path().
                replace(gitService.getDiffPath() + "/" + dbName, gitService.getBasePath());
        var repoBaseDef = gitService.getFileContentAtRef(Constants.HEAD, basePath)
                .orElse(null);
        return !(Objects.equals(repoObject.definition(), repoBaseDef) ||
                Objects.equals(repoObject.definition(), repoDiffDef));
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

    private List<RepoObject> computeDbDiffForCommit(String commitish, String dbName) throws IOException {
        var diffs = new ArrayList<RepoObject>();

        var allObjects = getAllObjectsForDb(commitish, dbName);

        allObjects.values()
                .parallelStream()
                .forEach(o -> {
                    if (sqlEquivalenceCheckerService.equals(o.getDbDefinition(), o.getBaseDefinition())) {
                        if (o.getDiffDefinition() != null) {
                            diffs.add(new RepoObject(o.getDiffPath(), null));
                        }
                    } else if (o.getDbDefinition() == null) {
                        if (!"".equals(o.getDiffDefinition())) {
                            diffs.add(new RepoObject(o.getDiffPath(), ""));
                        }
                    } else if (!sqlEquivalenceCheckerService.equals(o.getDbDefinition(), o.getDiffDefinition())) {
                        diffs.add(new RepoObject(o.getDiffPath(), o.getDbDefinition()));
                    }
                });

        return diffs.stream()
                .filter(o -> !ignoreService.shouldIgnore(o.path()))
                .toList();
    }

    private Map<String, FullObject> getAllObjectsForDb(String commitish, String dbName) throws IOException {
        var all = new ConcurrentHashMap<String, FullObject>();

        var baseRootPath = gitService.getBasePath();
        var diffRootPath = gitService.getDiffPath() + "/" + dbName;

        var dbObjects = databaseService.getDbObjects();
        var baseObjects = gitService.getAllFilesAtRef(commitish, baseRootPath);
        var diffObjectsForDb = gitService.getAllFilesAtRef(commitish, diffRootPath);

        dbObjects
                .parallelStream()
                .forEach(dbObject -> {
                    var key = dbObject.type() + "/" + dbObject.schema() + "/" + dbObject.name();
                    var o = initFullObjectFromKey(key, dbName);
                    o.setDbDefinition(dbObject.definition());
                    all.put(o.getKey(), o);
                });

        baseObjects.entrySet()
                .parallelStream()
                .forEach(e -> {
                    var key = getKeyFromGitPath(e.getKey());
                    var o = all.computeIfAbsent(key, k -> initFullObjectFromKey(key, dbName));
                    o.setBaseDefinition(e.getValue());
                });

        diffObjectsForDb.entrySet()
                .parallelStream()
                .forEach(e -> {
                    var key = getKeyFromGitPath(e.getKey());
                    var o = all.computeIfAbsent(key, k -> initFullObjectFromKey(key, dbName));
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

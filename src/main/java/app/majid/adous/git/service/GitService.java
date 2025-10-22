package app.majid.adous.git.service;

import app.majid.adous.synchronizer.model.DbObject;
import app.majid.adous.synchronizer.model.DbObjectType;
import app.majid.adous.git.config.GitProperties;
import app.majid.adous.synchronizer.model.RepoObject;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.dircache.DirCacheBuilder;
import org.eclipse.jgit.dircache.DirCacheEntry;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.filter.PathFilter;
import org.eclipse.jgit.util.io.DisabledOutputStream;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static java.nio.file.Files.createTempDirectory;
import static org.eclipse.jgit.diff.DiffEntry.ChangeType.*;

@Service
public class GitService {

    private static final Set<DiffEntry.ChangeType> ADDITION_OR_MODIFICATION = Set.of(ADD, MODIFY, RENAME, COPY);
    private static final Set<DiffEntry.ChangeType> RENAME_OR_DELETION = Set.of(RENAME, COPY, DELETE);

    private final boolean localMode;
    private final Repository repo;
    private final CredentialsProvider creds;
    private final String baseRootPath;
    private final String diffRootPath;

    public GitService(GitProperties gitProperties) throws IOException, GitAPIException {
        this.localMode = gitProperties.localModeForTest();
        this.baseRootPath = gitProperties.baseRootPath();
        this.diffRootPath = gitProperties.diffRootPath() + "/" + gitProperties.prefixPath();

        this.creds = new UsernamePasswordCredentialsProvider("token", gitProperties.token());

        var workDir = createTempDirectory("adous-repo").toFile();

        if (!localMode) {
            Git.cloneRepository()
                    .setBare(true)
                    .setURI(gitProperties.remoteUri())
                    .setGitDir(workDir)
                    .setCredentialsProvider(creds)
                    .call()
                    .close();
        }

        this.repo =  new FileRepositoryBuilder()
                .setGitDir(workDir)
                .setBare()
                .build();

        if (localMode) {
            repo.create(true);
        }
    }

    public Optional<String> getFileContent(ObjectId id) {
        try {
            byte[] bytes = repo.open(id, Constants.OBJ_BLOB).getBytes();
            return Optional.of(new String(bytes, StandardCharsets.UTF_8));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    public Optional<String> getFileContentAtRef(String commitish, String path) {
        try {
            ObjectId id = repo.resolve(commitish);
            if (id == null) return Optional.empty();

            try (RevWalk rw = new RevWalk(repo)) {
                RevCommit commit = rw.parseCommit(id);
                try (TreeWalk tw = new TreeWalk(repo)) {
                    tw.addTree(commit.getTree());
                    tw.setRecursive(true);
                    tw.setFilter(PathFilter.create(path));

                    if (!tw.next()) return Optional.empty();

                    if (tw.getFileMode(0).getObjectType() != Constants.OBJ_BLOB) {
                        return Optional.empty();
                    }

                    byte[] bytes = repo.open(tw.getObjectId(0), Constants.OBJ_BLOB).getBytes();
                    return Optional.of(new String(bytes, StandardCharsets.UTF_8));
                }
            }
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /* Get all files (blobs) under a folder (prefix) at a given commit-ish (branch, tag, commit)
       If folder is empty or root ("" or "/"), get all files in the repo
       Returns a map of file paths to their content */
    public Map<String, String> getAllFilesAtRef(String commitish, String folder) throws IOException {
        ObjectId id = repo.resolve(commitish);
        if (id == null) return Map.of(); // unknown ref → empty

        // normalize folder (no leading './', no trailing '/')
        String norm = folder.replace('\\', '/');
        while (norm.startsWith("./")) norm = norm.substring(2);
        if (norm.endsWith("/")) norm = norm.substring(0, norm.length() - 1);
        final String prefix = norm.isEmpty() ? "" : norm + "/";

        Map<String, String> result = new LinkedHashMap<>();

        try (RevWalk rw = new RevWalk(repo)) {
            RevCommit commit = rw.parseCommit(id);
            try (TreeWalk tw = new TreeWalk(repo)) {
                tw.addTree(commit.getTree());
                tw.setRecursive(true);
                if (!norm.isEmpty()) {
                    // PathFilter matches the subtree prefix (e.g., "src" → everything under src/)
                    tw.setFilter(PathFilter.create(norm));
                }

                while (tw.next()) {
                    // If a folder was provided, ensure we only keep entries under it (belt & suspenders)
                    if (!prefix.isEmpty() && !tw.getPathString().startsWith(prefix)) continue;

                    // Only files (blobs)
                    if (tw.getFileMode(0).getObjectType() != Constants.OBJ_BLOB) continue;

                    byte[] bytes = repo.open(tw.getObjectId(0), Constants.OBJ_BLOB).getBytes();
                    String content = new String(bytes, StandardCharsets.UTF_8);
                    result.put(tw.getPathString(), content);
                }
            }
        }
        return result;
    }

    public List<DiffEntry> getDiff(String commitId, String ancestorId) throws IOException {
        try (RevWalk walk = new RevWalk(repo);
             ObjectReader reader = repo.newObjectReader();
             DiffFormatter df = new DiffFormatter(DisabledOutputStream.INSTANCE)) {

            df.setRepository(repo);
            df.setDetectRenames(true);

            RevCommit commit = walk.parseCommit(repo.resolve(commitId));
            RevCommit ancestor = walk.parseCommit(repo.resolve(ancestorId));

            CanonicalTreeParser oldTree = new CanonicalTreeParser();
            CanonicalTreeParser newTree = new CanonicalTreeParser();
            oldTree.reset(reader, ancestor.getTree());
            newTree.reset(reader, commit.getTree());

            return df.scan(oldTree, newTree);
        }
    }

    public List<DbObject> getRepoChangesToApplyToDb(String commitish, String dbName) throws IOException {
        List<DbObject> dbObjects = new ArrayList<>();

        List<DiffEntry> diff = getDiff(commitish, getTag(dbName));
        diff.forEach(e -> {
            if (newOrModifiedInBaseAndNotExistInDiff(e, commitish)) {
                dbObjects.add(newDbObject(e.getNewPath(), getFileContent(e.getNewId().toObjectId()).orElse(null)));
            }
            if (removedFromBaseAndNotExistInDiff(e, commitish)) {
                dbObjects.add(newDbObject(e.getOldPath(), null));
            }
            if (newOrModifiedInDiff(e)) {
                dbObjects.add(newDbObject(e.getNewPath(), getFileContent(e.getNewId().toObjectId()).orElse(null)));
            }
            if (removedFromDiff(e)) {
                Optional<String> c = getFileContentAtRef(commitish, e.getOldPath().replace(diffRootPath, baseRootPath));
                dbObjects.add(newDbObject(e.getOldPath(), c.orElse(null)));
            }
        });

        return dbObjects;
    }

    public ObjectId applyChanges(List<RepoObject> changes, String commitMessage, String branchRef)
            throws IOException {

        try (ObjectInserter inserter = repo.newObjectInserter();
             RevWalk revWalk = new RevWalk(repo)) {

            // Get the current HEAD commit (if exists)
            ObjectId headId = repo.resolve(branchRef);
            DirCache index = DirCache.newInCore();
            DirCacheBuilder builder = index.builder();

            // If there's an existing commit, copy its tree entries first
            if (headId != null) {
                RevCommit headCommit = revWalk.parseCommit(headId);
                try (TreeWalk treeWalk = new TreeWalk(repo)) {
                    treeWalk.addTree(headCommit.getTree());
                    treeWalk.setRecursive(true);

                    while (treeWalk.next()) {
                        String path = treeWalk.getPathString();

                        // Check if this path is being modified or deleted
                        boolean isModified = changes.stream()
                                .anyMatch(c -> c.path().equals(path));

                        if (!isModified) {
                            // Keep existing file
                            DirCacheEntry entry = new DirCacheEntry(path);
                            entry.setFileMode(treeWalk.getFileMode(0));
                            entry.setObjectId(treeWalk.getObjectId(0));
                            builder.add(entry);
                        }
                    }
                }
            }

            // Add or remove files based on changes
            for (RepoObject change : changes) {
                if (change.definition() != null) {
                    // Add or update file
                    byte[] content = change.definition().getBytes(StandardCharsets.UTF_8);
                    ObjectId blobId = inserter.insert(Constants.OBJ_BLOB, content);

                    DirCacheEntry entry = new DirCacheEntry(change.path());
                    entry.setFileMode(FileMode.REGULAR_FILE);
                    entry.setObjectId(blobId);
                    builder.add(entry);
                }
                // If definition is null, file is simply not added (deleted)
            }

            builder.finish();
            ObjectId treeId = index.writeTree(inserter);

            // Create commit
            CommitBuilder commitBuilder = new CommitBuilder();
            commitBuilder.setTreeId(treeId);
            commitBuilder.setMessage(commitMessage);

            PersonIdent ident = new PersonIdent("Your Name", "your.email@example.com");
            commitBuilder.setAuthor(ident);
            commitBuilder.setCommitter(ident);

            // Set parent commit if exists
            if (headId != null) {
                commitBuilder.setParentId(headId);
            }

            ObjectId commitId = inserter.insert(commitBuilder);
            inserter.flush();

            // Update the branch reference
            RefUpdate refUpdate = repo.updateRef(branchRef);
            refUpdate.setNewObjectId(commitId);
            if (headId != null) {
                refUpdate.setExpectedOldObjectId(headId);
            }
            refUpdate.update();

            return commitId;
        }
    }

    public ObjectId applyChangesAndPush(List<RepoObject> changes, String commitMessage)
            throws IOException, GitAPIException {
        String branchRef = Constants.R_HEADS + "main";
        ObjectId commitId = applyChanges(changes, commitMessage, branchRef);

        if (localMode) return  commitId;

        try (Git git = new Git(repo)) {
            git.push()
                    .setRemote("origin")
                    .add(branchRef)
                    .setCredentialsProvider(creds)
                    .call();
        }

        return commitId;
    }

    public boolean isEmptyRepo() throws IOException {
        return repo.resolve(Constants.HEAD) == null;
    }

    public boolean isHeadCommit(String commitish) throws IOException {
        return repo.resolve(commitish) == repo.resolve(Constants.HEAD);
    }

    public boolean tagExists(String tagName) throws IOException {
        return repo.findRef(getTag(tagName)) != null;
    }

    public String getTag(String tagName) {
        return Constants.R_TAGS + tagName;
    }

    public String getDiffPath() {
        return diffRootPath;
    }

    public String getBasePath() {
        return baseRootPath;
    }

    private boolean removedFromDiff(DiffEntry e) {
        return RENAME_OR_DELETION.contains(e.getChangeType()) && e.getOldPath().startsWith(diffRootPath);
    }

    private boolean newOrModifiedInDiff(DiffEntry e) {
        return ADDITION_OR_MODIFICATION.contains(e.getChangeType()) && e.getNewPath().startsWith(diffRootPath);
    }

    private boolean removedFromBaseAndNotExistInDiff(DiffEntry e, String commitish) {
        if (RENAME_OR_DELETION.contains(e.getChangeType()) && e.getOldPath().startsWith(baseRootPath)) {
            Optional<String> c = getFileContentAtRef(commitish, e.getNewPath().replace(baseRootPath, diffRootPath));
            return c.isEmpty();
        }
        return false;
    }

    private boolean newOrModifiedInBaseAndNotExistInDiff(DiffEntry e, String commitish) {
        if (ADDITION_OR_MODIFICATION.contains(e.getChangeType()) && e.getNewPath().startsWith(baseRootPath)) {
            Optional<String> c = getFileContentAtRef(commitish, e.getNewPath().replace(baseRootPath, diffRootPath));
            return c.isEmpty();
        }
        return false;
    }

    private DbObject newDbObject(String path, String definition) {
        if (!path.endsWith(".sql")) {
            throw new IllegalArgumentException("Invalid file type: " + path);
        }

        int lastSlash = path.lastIndexOf('/');
        int secondSlash = path.lastIndexOf('/', lastSlash - 1);
        int thirdSlash = path.lastIndexOf('/', secondSlash - 1);

        if (thirdSlash < 0) {
            throw new IllegalArgumentException("Invalid path: " + path);
        }

        String type = path.substring(thirdSlash + 1, secondSlash);
        String schema = path.substring(secondSlash + 1, lastSlash);
        String name = path.substring(lastSlash + 1, path.length() - 4);

        return new DbObject(schema, name, DbObjectType.valueOf(type.toUpperCase()), definition);
    }
}

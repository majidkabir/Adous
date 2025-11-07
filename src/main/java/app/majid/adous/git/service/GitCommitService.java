package app.majid.adous.git.service;

import app.majid.adous.synchronizer.model.RepoObject;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.dircache.DirCacheBuilder;
import org.eclipse.jgit.dircache.DirCacheEntry;
import org.eclipse.jgit.lib.*;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

@Service
public class GitCommitService {

    private final Repository repo;
    private final PersonIdent committerIdent;

    public GitCommitService(Repository repo, PersonIdent committerIdent) {
        this.repo = repo;
        this.committerIdent = committerIdent;
    }

    public ObjectId applyChanges(List<RepoObject> changes, String commitMessage, String branchRef)
            throws IOException {

        try (ObjectInserter inserter = repo.newObjectInserter();
             RevWalk revWalk = new RevWalk(repo)) {

            ObjectId headId = repo.resolve(branchRef);
            DirCache index = buildDirCache(headId, changes, revWalk, inserter);
            ObjectId treeId = index.writeTree(inserter);
            ObjectId commitId = createCommit(treeId, headId, commitMessage, inserter);
            updateBranchRef(branchRef, headId, commitId);

            return commitId;
        }
    }

    private DirCache buildDirCache(ObjectId headId, List<RepoObject> changes, RevWalk revWalk, ObjectInserter inserter)
            throws IOException {
        DirCache index = DirCache.newInCore();
        DirCacheBuilder builder = index.builder();

        if (headId != null) {
            copyExistingEntries(headId, changes, revWalk, builder);
        }

        addOrUpdateFiles(changes, inserter, builder);
        builder.finish();

        return index;
    }

    private void copyExistingEntries(ObjectId headId, List<RepoObject> changes, RevWalk revWalk, DirCacheBuilder builder)
            throws IOException {
        RevCommit headCommit = revWalk.parseCommit(headId);
        try (TreeWalk treeWalk = new TreeWalk(repo)) {
            treeWalk.addTree(headCommit.getTree());
            treeWalk.setRecursive(true);

            while (treeWalk.next()) {
                String path = treeWalk.getPathString();

                boolean isModified = changes.stream()
                        .anyMatch(c -> c.path().equals(path));

                if (!isModified) {
                    DirCacheEntry entry = new DirCacheEntry(path);
                    entry.setFileMode(treeWalk.getFileMode(0));
                    entry.setObjectId(treeWalk.getObjectId(0));
                    builder.add(entry);
                }
            }
        }
    }

    private void addOrUpdateFiles(List<RepoObject> changes, ObjectInserter inserter, DirCacheBuilder builder)
            throws IOException {
        for (RepoObject change : changes) {
            if (change.definition() != null) {
                byte[] content = change.definition().getBytes(StandardCharsets.UTF_8);
                ObjectId blobId = inserter.insert(Constants.OBJ_BLOB, content);

                DirCacheEntry entry = new DirCacheEntry(change.path());
                entry.setFileMode(FileMode.REGULAR_FILE);
                entry.setObjectId(blobId);
                builder.add(entry);
            }
        }
    }

    private ObjectId createCommit(ObjectId treeId, ObjectId parentId, String commitMessage, ObjectInserter inserter)
            throws IOException {
        CommitBuilder commitBuilder = new CommitBuilder();
        commitBuilder.setTreeId(treeId);
        commitBuilder.setMessage(commitMessage);
        commitBuilder.setAuthor(committerIdent);
        commitBuilder.setCommitter(committerIdent);

        if (parentId != null) {
            commitBuilder.setParentId(parentId);
        }

        ObjectId commitId = inserter.insert(commitBuilder);
        inserter.flush();

        return commitId;
    }

    private void updateBranchRef(String branchRef, ObjectId oldId, ObjectId newId) throws IOException {
        RefUpdate refUpdate = repo.updateRef(branchRef);
        refUpdate.setNewObjectId(newId);
        if (oldId != null) {
            refUpdate.setExpectedOldObjectId(oldId);
        }
        refUpdate.update();

        RefUpdate headUpdate = repo.updateRef(Constants.HEAD, true);
        headUpdate.link(branchRef);
    }
}

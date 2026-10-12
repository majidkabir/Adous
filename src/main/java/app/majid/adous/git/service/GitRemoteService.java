package app.majid.adous.git.service;

import app.majid.adous.git.config.GitProperties;
import app.majid.adous.git.exception.GitPushRejectedException;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.RemoteRefUpdate;
import org.eclipse.jgit.transport.TagOpt;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

@Service
public class GitRemoteService {

    private static final Set<RemoteRefUpdate.Status> SUCCESSFUL_PUSH_STATUSES =
            EnumSet.of(RemoteRefUpdate.Status.OK, RemoteRefUpdate.Status.UP_TO_DATE);

    private static final Set<RefUpdate.Result> SUCCESSFUL_LOCAL_UPDATES = EnumSet.of(
            RefUpdate.Result.NEW,
            RefUpdate.Result.FORCED,
            RefUpdate.Result.FAST_FORWARD,
            RefUpdate.Result.NO_CHANGE);

    private final Repository repo;
    private final CredentialsProvider creds;
    private final boolean localMode;

    public GitRemoteService(Repository repo, CredentialsProvider creds, GitProperties gitProperties) {
        this.repo = repo;
        this.creds = creds;
        this.localMode = gitProperties.localModeForTest();
    }

    /**
     * Pushes a branch to origin (non-forced).
     *
     * @throws GitPushRejectedException if the remote rejects the update, e.g. because
     *                                  the remote branch advanced since the last fetch
     */
    public void push(String branchRef) throws GitAPIException {
        if (localMode) return;

        try (Git git = new Git(repo)) {
            Iterable<PushResult> results = git.push()
                    .setRemote("origin")
                    .add(branchRef)
                    .setCredentialsProvider(creds)
                    .call();
            verifyPushResults(results);
        }
    }

    /**
     * Pushes the given tag ref specs to origin.
     *
     * @throws GitPushRejectedException if the remote rejects any of the tag updates
     */
    public void pushTags(List<RefSpec> refSpecs) throws GitAPIException {
        if (localMode || refSpecs.isEmpty()) return;

        try (Git git = new Git(repo)) {
            Iterable<PushResult> results = git.push()
                    .setRemote("origin")
                    .setRefSpecs(refSpecs)
                    .setCredentialsProvider(creds)
                    .call();
            verifyPushResults(results);
        }
    }

    /**
     * Sync bare repository with remote: fetch from origin and update all local heads
     * and tags to exactly match the remote (force update to handle rewrites and moved
     * sync tags written by other instances).
     */
    public void sync() throws GitAPIException {
        if (localMode) {
            return;
        }

        try (Git git = new Git(repo)) {
            // Fetch all remote branches and force-update tags: sync tags move on every
            // repo-to-db sync, so a stale local tag would yield a wrong diff base.
            git.fetch()
               .setRemote("origin")
               .setRefSpecs(
                       new RefSpec("+refs/heads/*:refs/remotes/origin/*"),
                       new RefSpec("+refs/tags/*:refs/tags/*"))
               .setTagOpt(TagOpt.NO_TAGS)
               .setRemoveDeletedRefs(true)
               .setCredentialsProvider(creds)
               .call();

            // Mirror all remote tracking branches to local heads
            for (Ref remoteRef : repo.getRefDatabase().getRefsByPrefix("refs/remotes/origin/")) {
                String branchName = remoteRef.getName().substring("refs/remotes/origin/".length());
                String localRef = "refs/heads/" + branchName;
                ObjectId newId = remoteRef.getObjectId();

                RefUpdate update = repo.updateRef(localRef);
                update.setNewObjectId(newId);
                update.setForceUpdate(true);
                RefUpdate.Result result = update.update();
                if (!SUCCESSFUL_LOCAL_UPDATES.contains(result)) {
                    throw new IllegalStateException(
                            "Failed to update local ref " + localRef + ": " + result);
                }
            }
        } catch (Exception e) {
            throw new GitAPIException("Sync failed: " + e.getMessage(), e) {};
        }
    }

    /**
     * Throws if any ref update in the push results was not accepted by the remote.
     */
    static void verifyPushResults(Iterable<PushResult> results) throws GitPushRejectedException {
        List<String> rejected = new ArrayList<>();
        for (PushResult result : results) {
            for (RemoteRefUpdate update : result.getRemoteUpdates()) {
                if (!SUCCESSFUL_PUSH_STATUSES.contains(update.getStatus())) {
                    String detail = update.getMessage() == null ? "" : " (" + update.getMessage() + ")";
                    rejected.add(update.getRemoteName() + ": " + update.getStatus() + detail);
                }
            }
        }
        if (!rejected.isEmpty()) {
            throw new GitPushRejectedException(rejected);
        }
    }
}

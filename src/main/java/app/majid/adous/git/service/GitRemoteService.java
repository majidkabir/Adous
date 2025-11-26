package app.majid.adous.git.service;

import app.majid.adous.git.config.GitProperties;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.TagOpt;
import org.springframework.stereotype.Service;

@Service
public class GitRemoteService {

    private final Repository repo;
    private final CredentialsProvider creds;
    private final boolean localMode;

    public GitRemoteService(Repository repo, CredentialsProvider creds, GitProperties gitProperties) {
        this.repo = repo;
        this.creds = creds;
        this.localMode = gitProperties.localModeForTest();
    }

    public void push(String branchRef) throws GitAPIException {
        if (localMode) return;

        try (Git git = new Git(repo)) {
            git.push()
                    .setRemote("origin")
                    .setPushTags()
                    .add(branchRef)
                    .setCredentialsProvider(creds)
                    .call();
        }
    }

    /**
     * Sync bare repository with remote: fetch from origin and update all local heads
     * to exactly match remote branches (force update to handle rewrites).
     */
    public void sync() throws GitAPIException {
        if (localMode) {
            return;
        }

        try (Git git = new Git(repo)) {
            // Fetch all remote branches and tags
            git.fetch()
               .setRemote("origin")
               .setTagOpt(TagOpt.FETCH_TAGS)
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
                update.update();
            }
        } catch (Exception e) {
            throw new GitAPIException("Sync failed", e) {};
        }
    }
}

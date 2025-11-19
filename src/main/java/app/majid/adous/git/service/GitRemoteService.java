package app.majid.adous.git.service;

import app.majid.adous.git.config.GitProperties;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.CredentialsProvider;
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
}

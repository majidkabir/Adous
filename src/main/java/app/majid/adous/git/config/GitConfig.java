package app.majid.adous.git.config;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;

import static java.nio.file.Files.createTempDirectory;

@Configuration
public class GitConfig {

    @Bean()
    public CredentialsProvider credentialsProvider(GitProperties gitProperties) {
        return new UsernamePasswordCredentialsProvider("token", gitProperties.token());
    }

    @Bean
    public Repository repo(GitProperties gitProperties, CredentialsProvider creds) throws IOException, GitAPIException {
        var workDir = createTempDirectory("adous-repo-").toFile();

        if (!gitProperties.localModeForTest()) {
            Git.cloneRepository()
                    .setBare(true)
                    .setURI(gitProperties.remoteUri())
                    .setGitDir(workDir)
                    .setCredentialsProvider(creds)
                    .call()
                    .close();
        }

        var repo = new FileRepositoryBuilder()
                .setGitDir(workDir)
                .setBare()
                .build();

        if (gitProperties.localModeForTest()) {
            repo.create(true);
        }

        return repo;
    }

    @Bean
    public PersonIdent committerIdent(GitProperties gitProperties) {
        return new PersonIdent(gitProperties.commitUsername(), gitProperties.commitEmail());
    }
}

package app.majid.adous.git.service;

import app.majid.adous.git.config.GitProperties;
import app.majid.adous.git.exception.GitPushRejectedException;
import app.majid.adous.git.mapper.DbObjectMapper;
import app.majid.adous.synchronizer.model.RepoObject;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.URIish;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercises push/fetch against a real (file-based) remote, so that rejected pushes
 * and moved tags behave exactly as they would against GitHub.
 */
class GitRemoteServiceTest {

    private static final String MAIN = Constants.R_HEADS + "main";

    @TempDir
    Path tmp;

    private Repository remote;
    private Git other;
    private Repository local;
    private GitRemoteService remoteService;
    private GitCommitService commitService;
    private GitService gitService;

    @BeforeEach
    void setUp() throws Exception {
        File remoteDir = tmp.resolve("remote.git").toFile();
        remote = Git.init().setBare(true).setInitialBranch("main").setDirectory(remoteDir).call().getRepository();

        // A second, independent writer (another Adous instance or a developer)
        other = Git.init().setInitialBranch("main").setDirectory(tmp.resolve("other").toFile()).call();
        other.remoteAdd().setName("origin").setUri(new URIish(remoteDir.getAbsolutePath())).call();
        commitInOther("base/VIEW/dbo/v1.sql", "CREATE VIEW v1 AS SELECT 1");
        other.push().setRemote("origin").add("main").call();

        // The Adous repository, cloned the same way GitConfig does it
        File localDir = tmp.resolve("local.git").toFile();
        Git.cloneRepository().setBare(true).setURI(remoteDir.getAbsolutePath()).setGitDir(localDir).call().close();
        local = new FileRepositoryBuilder().setGitDir(localDir).setBare().build();

        CredentialsProvider creds = new UsernamePasswordCredentialsProvider("token", "");
        GitProperties props = new GitProperties(false, remoteDir.getAbsolutePath(), "", "base", "diff",
                "", "Adous", "adous@example.com", "main");
        remoteService = new GitRemoteService(local, creds, props);
        commitService = new GitCommitService(local, new PersonIdent("Adous", "adous@example.com"));
        gitService = new GitService(new GitRepository(local), new GitDiffService(local), commitService,
                remoteService, new DbObjectMapper(), props);
    }

    @AfterEach
    void tearDown() {
        other.close();
        local.close();
        remote.close();
    }

    @Test
    void shouldPushWhenRemoteUnchanged() throws Exception {
        remoteService.sync();
        ObjectId commit = commitService.applyChanges(
                List.of(new RepoObject("base/VIEW/dbo/v2.sql", "CREATE VIEW v2 AS SELECT 2")), "add v2", MAIN);

        remoteService.push(MAIN);

        assertEquals(commit, remote.resolve(MAIN));
    }

    @Test
    void shouldThrowWhenPushIsRejectedBecauseRemoteAdvanced() throws Exception {
        remoteService.sync();
        commitInOther("base/VIEW/dbo/v3.sql", "CREATE VIEW v3 AS SELECT 3");
        other.push().setRemote("origin").add("main").call();
        ObjectId remoteHead = remote.resolve(MAIN);

        commitService.applyChanges(
                List.of(new RepoObject("base/VIEW/dbo/v2.sql", "CREATE VIEW v2 AS SELECT 2")), "add v2", MAIN);

        GitPushRejectedException e = assertThrows(GitPushRejectedException.class, () -> remoteService.push(MAIN));
        assertTrue(e.getMessage().contains(MAIN));
        assertEquals(remoteHead, remote.resolve(MAIN), "remote must not be modified");
    }

    @Test
    void shouldRollBackLocalCommitAndSkipTagsWhenPushIsRejected() throws Exception {
        remoteService.sync();
        ObjectId localHeadBefore = local.resolve(MAIN);
        commitInOther("base/VIEW/dbo/v3.sql", "CREATE VIEW v3 AS SELECT 3");
        other.push().setRemote("origin").add("main").call();

        assertThrows(GitPushRejectedException.class, () -> gitService.applyChangesAndPush(
                List.of(new RepoObject("base/VIEW/dbo/v2.sql", "CREATE VIEW v2 AS SELECT 2")),
                "add v2", List.of("db1")));

        assertEquals(localHeadBefore, local.resolve(MAIN), "local branch must be reset to its previous head");
        assertNull(local.findRef(Constants.R_TAGS + "db1"), "tag must not be created locally");
        assertNull(remote.findRef(Constants.R_TAGS + "db1"), "tag must not be pushed");
    }

    @Test
    void shouldPushBranchAndTagsTogether() throws Exception {
        gitService.syncRemote();

        ObjectId commit = gitService.applyChangesAndPush(
                List.of(new RepoObject("base/VIEW/dbo/v2.sql", "CREATE VIEW v2 AS SELECT 2")),
                "add v2", List.of("db1"));

        assertEquals(commit, remote.resolve(MAIN));
        assertEquals(commit, remote.resolve(Constants.R_TAGS + "db1"));
    }

    @Test
    void shouldForceUpdateLocalTagWhenItMovedOnRemote() throws Exception {
        // db1 tag created by this instance
        remoteService.sync();
        gitService.addTags(List.of("db1"), MAIN);

        // Another instance syncs db1 to a newer commit and moves the tag
        other.fetch().setRemote("origin").setRefSpecs(new RefSpec("+refs/tags/*:refs/tags/*")).call();
        ObjectId newer = commitInOther("base/VIEW/dbo/v3.sql", "CREATE VIEW v3 AS SELECT 3");
        other.push().setRemote("origin").add("main").call();
        other.tag().setName("db1").setObjectId(other.getRepository().parseCommit(newer))
                .setAnnotated(false).setForceUpdate(true).call();
        other.push().setRemote("origin").setRefSpecs(new RefSpec("+refs/tags/db1:refs/tags/db1")).call();

        remoteService.sync();

        assertEquals(newer, local.resolve(Constants.R_TAGS + "db1"));
        assertEquals(newer, local.resolve(MAIN));
    }

    private ObjectId commitInOther(String path, String content) throws Exception {
        Path file = other.getRepository().getWorkTree().toPath().resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
        other.add().addFilepattern(path).call();
        return other.commit().setMessage("change " + path)
                .setAuthor("dev", "dev@example.com").setCommitter("dev", "dev@example.com")
                .setSign(false).call();
    }
}

package app.majid.adous.synchronizer.service;

import app.majid.adous.db.config.DatabaseContextHolder;
import app.majid.adous.git.config.GitProperties;
import app.majid.adous.git.mapper.DbObjectMapper;
import app.majid.adous.git.service.GitCommitService;
import app.majid.adous.git.service.GitDiffService;
import app.majid.adous.git.service.GitRemoteService;
import app.majid.adous.git.service.GitRepository;
import app.majid.adous.git.service.GitService;
import app.majid.adous.synchronizer.db.DatabaseService;
import app.majid.adous.synchronizer.model.DbObject;
import app.majid.adous.synchronizer.model.DbObjectType;
import app.majid.adous.synchronizer.model.RepoObject;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Runs init-repo and db-to-repo against a real (local) Git repository with and without
 * a prefix-path, checking the override paths and sync tags that result.
 */
class PrefixNamespaceTest {

    private static final String V1 = "CREATE VIEW dbo.v1 AS SELECT 1 AS x";
    private static final String V1_CHANGED = "CREATE VIEW dbo.v1 AS SELECT 2 AS x";

    @TempDir
    Path tmp;

    private Repository repo;
    private GitService gitService;
    private DatabaseService databaseService;
    private DatabaseRepositorySynchronizerService service;

    @AfterEach
    void tearDown() {
        DatabaseContextHolder.clear();
        repo.close();
    }

    @Test
    void shouldWriteOverridesAndTagWithoutPrefix() throws Exception {
        setUp("");

        service.initRepo("db1");
        assertEquals("diff", gitService.getDiffPath());
        assertNotNull(repo.findRef(Constants.R_TAGS + "db1"));

        dbHasView(V1_CHANGED);
        List<RepoObject> changes = service.syncDbToRepo("db1", false);

        assertEquals(List.of("diff/db1/VIEW/dbo/v1.sql"), changes.stream().map(RepoObject::path).toList());
        assertEquals(V1_CHANGED, gitService.getFileContentAtRef(Constants.HEAD, "diff/db1/VIEW/dbo/v1.sql").orElseThrow());
    }

    @Test
    void shouldNamespaceOverridesAndTagsWithPrefix() throws Exception {
        setUp("/prod/");

        service.initRepo("db1");
        assertEquals("diff/prod", gitService.getDiffPath());
        assertEquals("prod/db1", gitService.tagName("db1"));
        assertNotNull(repo.findRef(Constants.R_TAGS + "prod/db1"));
        assertNull(repo.findRef(Constants.R_TAGS + "db1"), "no unprefixed tag");

        dbHasView(V1_CHANGED);
        service.syncDbToRepo("db1", false);

        assertTrue(gitService.getFileContentAtRef(Constants.HEAD, "diff/prod/db1/VIEW/dbo/v1.sql").isPresent());
    }

    @Test
    void shouldUseLegacyUnprefixedTagUntilDatabaseIsTaggedAgain() throws Exception {
        // A repository whose db1 tag was written before tags were prefixed
        setUp("");
        service.initRepo("db1");
        ObjectId legacyCommit = repo.resolve(Constants.R_TAGS + "db1");

        GitService prefixed = gitService(repo, "prod");
        assertTrue(prefixed.tagExists("db1"), "legacy tag counts as onboarded");
        assertEquals(Constants.R_TAGS + "db1", prefixed.getTag("db1"));

        prefixed.addTags(List.of("db1"), Constants.HEAD);

        assertEquals(Constants.R_TAGS + "prod/db1", prefixed.getTag("db1"));
        assertEquals(legacyCommit, repo.resolve(Constants.R_TAGS + "db1"), "legacy tag is left untouched");
    }

    private void setUp(String prefix) throws Exception {
        repo = Git.init().setBare(true).setInitialBranch("main")
                .setGitDir(tmp.resolve("repo-" + prefix.replace("/", "_") + ".git").toFile())
                .call().getRepository();
        gitService = gitService(repo, prefix);

        databaseService = mock(DatabaseService.class);
        dbHasView(V1);

        SqlEquivalenceCheckerService inner = new SqlEquivalenceCheckerService(null);
        ReflectionTestUtils.setField(inner, "defaultSchema", "dbo");
        SqlEquivalenceCheckerService equivalence = new SqlEquivalenceCheckerService(inner);
        ReflectionTestUtils.setField(equivalence, "defaultSchema", "dbo");

        service = new DatabaseRepositorySynchronizerService(gitService, databaseService, equivalence,
                new SynchronizerIgnoreService(""), mock(TransactionTemplate.class));
        DatabaseContextHolder.setAvailableDbs(Set.of("db1"));
    }

    private void dbHasView(String definition) {
        when(databaseService.getDbObjects())
                .thenReturn(new ArrayList<>(List.of(new DbObject("dbo", "v1", DbObjectType.VIEW, definition))));
    }

    private static GitService gitService(Repository repo, String prefix) {
        GitProperties props = new GitProperties(true, "", "", "base", "diff", prefix,
                "Adous", "adous@example.com", "main");
        GitRemoteService remote = new GitRemoteService(repo, new UsernamePasswordCredentialsProvider("t", ""), props);
        return new GitService(new GitRepository(repo), new GitDiffService(repo),
                new GitCommitService(repo, new PersonIdent("Adous", "adous@example.com")),
                remote, new DbObjectMapper(), props);
    }
}

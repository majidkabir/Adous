package app.majid.adous.it;

import app.majid.adous.db.config.DatabaseContextHolder;
import app.majid.adous.git.service.GitService;
import app.majid.adous.synchronizer.service.DatabaseRepositorySynchronizerService;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.graalvm.collections.Pair;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.io.IOException;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class SynchronizationTestsIT {

    @Autowired
    DatabaseRepositorySynchronizerService synchronizerService;

    @Autowired
    GitService gitService;

    @Autowired
    Repository repo;

    @Test
    void testSynchronizerServiceNotNull() throws GitAPIException, IOException {
        synchronizerService.initRepo("db1");
        assertInitializedRepoState();

        synchronizerService.syncDbToRepo("db2", false);
        assertDb2SyncedRepoState();

        assertNotEquals(getTagObjectId("db1"), getTagObjectId("db2"));

        synchronizerService.syncRepoToDb(Constants.HEAD, "db1", false, false);

        assertEquals(getTagObjectId("db1"), getTagObjectId("db2"));
    }

    private void assertInitializedRepoState() throws IOException {
        assertEquals(getHeadObjectId(), getTagObjectId("db1"));
        assertEquals(getHeadObjectId(), getTagObjectId("sync-from-db-db1"));

        assertAll("Checking existence of all non-ignored database objects in git repo",
                Stream.of(
                        "base/PROCEDURE/dbo/proc1.sql",
                        "base/PROCEDURE/dbo/proc2.sql",
                        "base/PROCEDURE/dbo/prefix2_proc1.sql",
                        "base/PROCEDURE/dbo/prefix3_proc1.sql",
                        "base/PROCEDURE/dbo/prefix4_proc1.sql",
                        "base/TRIGGER/dbo/trigger1.sql",
                        "base/TRIGGER/dbo/trigger2.sql",
                        "base/TRIGGER/dbo/prefix1_trigger1.sql",
                        "base/TRIGGER/dbo/prefix3_trigger1.sql",
                        "base/TRIGGER/dbo/prefix4_trigger1.sql",
                        "base/VIEW/dbo/view1.sql",
                        "base/VIEW/dbo/view2.sql",
                        "base/VIEW/dbo/prefix1_view1.sql",
                        "base/VIEW/dbo/prefix2_view1.sql",
                        "base/VIEW/dbo/prefix4_view1.sql",
                        "base/FUNCTION/dbo/func1.sql",
                        "base/FUNCTION/dbo/func2.sql",
                        "base/FUNCTION/dbo/prefix1_func1.sql",
                        "base/FUNCTION/dbo/prefix2_func1.sql",
                        "base/FUNCTION/dbo/prefix3_func1.sql"
                ).map(this::assertFileExists).toArray(Executable[]::new)
        );

        assertAll("Checking non-existence of all ignored database objects in git repo",
                Stream.of(
                        "base/PROCEDURE/dbo/prefix1_proc1.sql",
                        "base/VIEW/dbo/prefix3_view1.sql",
                        "base/TRIGGER/dbo/prefix2_trigger1.sql",
                        "base/FUNCTION/dbo/prefix4_func1.sql"
                ).map(this::assertFileNotExists).toArray(Executable[]::new)
        );

        assertAll("Checking definitions of selected database objects in git repo",
                Stream.of(
                        Pair.create("base/PROCEDURE/dbo/proc1.sql", """
                                SET ANSI_NULLS ON;
                                GO
                                SET QUOTED_IDENTIFIER ON;
                                GO
                                CREATE PROCEDURE proc1 AS BEGIN SELECT 'Procedure 1 executed' AS Message; END
                                GO"""),
                        Pair.create("base/TRIGGER/dbo/trigger1.sql", """
                                SET ANSI_NULLS ON;
                                GO
                                SET QUOTED_IDENTIFIER ON;
                                GO
                                CREATE TRIGGER trigger1 ON table1 AFTER INSERT AS BEGIN INSERT INTO table1 (name) VALUES ('test name 1'); END
                                GO"""),
                        Pair.create("base/VIEW/dbo/view1.sql", """
                                SET ANSI_NULLS ON;
                                GO
                                SET QUOTED_IDENTIFIER ON;
                                GO
                                CREATE VIEW view1 AS SELECT id FROM table1
                                GO"""),
                        Pair.create("base/FUNCTION/dbo/func1.sql", """
                                SET ANSI_NULLS ON;
                                GO
                                SET QUOTED_IDENTIFIER ON;
                                GO
                                CREATE FUNCTION func1() RETURNS INT BEGIN RETURN 1 END
                                GO""")
                ).map(this::assertFileContent).toArray(Executable[]::new)
        );
    }

    private void assertDb2SyncedRepoState() throws IOException {
        assertEquals(getHeadObjectId(), getTagObjectId("db2"));
        assertEquals(getHeadObjectId(), getTagObjectId("sync-from-db-db2"));

        assertAll("Checking existence of all non-ignored database objects in git repo",
                Stream.of(
                        "diff/db2/PROCEDURE/dbo/proc1.sql",
                        "diff/db2/PROCEDURE/dbo/prefix4_proc1.sql",
                        "diff/db2/TRIGGER/dbo/trigger1.sql",
                        "diff/db2/VIEW/dbo/view1.sql",
                        "diff/db2/FUNCTION/dbo/func1.sql"
                ).map(this::assertFileExists).toArray(Executable[]::new)
        );

        assertAll("Checking non-existence of all ignored database objects in git repo",
                Stream.of(
                        "diff/db2/PROCEDURE/dbo/prefix1_proc2.sql",
                        "diff/db2/VIEW/dbo/prefix3_view2.sql",
                        "diff/db2/TRIGGER/dbo/prefix2_trigger1.sql",
                        "diff/db2/FUNCTION/dbo/prefix4_func2.sql"
                ).map(this::assertFileNotExists).toArray(Executable[]::new)
        );

        assertAll("Checking definitions of selected database objects in git repo",
                Stream.of(
                        Pair.create("diff/db2/PROCEDURE/dbo/proc1.sql", """
                                SET ANSI_NULLS ON;
                                GO
                                SET QUOTED_IDENTIFIER ON;
                                GO
                                CREATE PROCEDURE proc1 AS BEGIN SELECT 'Procedure 11 executed' AS Message; END
                                GO"""),
                        Pair.create("diff/db2/PROCEDURE/dbo/prefix4_proc1.sql", ""),
                        Pair.create("diff/db2/TRIGGER/dbo/trigger1.sql", """
                                SET ANSI_NULLS ON;
                                GO
                                SET QUOTED_IDENTIFIER ON;
                                GO
                                CREATE TRIGGER trigger1 ON table1 AFTER INSERT AS BEGIN INSERT INTO table1 (name) VALUES ('test name 11'); END
                                GO"""),
                        Pair.create("diff/db2/VIEW/dbo/view1.sql", """
                                SET ANSI_NULLS ON;
                                GO
                                SET QUOTED_IDENTIFIER ON;
                                GO
                                CREATE VIEW view1 AS SELECT id, 'p1' AS prefix FROM table1
                                GO"""),
                        Pair.create("diff/db2/FUNCTION/dbo/func1.sql", """
                                SET ANSI_NULLS ON;
                                GO
                                SET QUOTED_IDENTIFIER ON;
                                GO
                                CREATE FUNCTION func1() RETURNS INT BEGIN RETURN 11 END
                                GO""")
                ).map(this::assertFileContent).toArray(Executable[]::new)
        );
    }

    private ObjectId getTagObjectId(String tag) throws IOException {
        return repo.findRef(Constants.R_TAGS + tag).getObjectId();
    }

    private ObjectId getHeadObjectId() throws IOException {
        return repo.findRef(Constants.HEAD).getObjectId();
    }

    private Executable assertFileExists(String path) {
        return () -> assertTrue(
                gitService.getFileContentAtRef("HEAD", path).isPresent(),
                "Expected file to exist: " + path
        );
    }

    private Executable assertFileNotExists(String path) {
        return () -> assertTrue(
                gitService.getFileContentAtRef("HEAD", path).isEmpty(),
                "Expected file not to exist: " + path
        );
    }

    private Executable assertFileContent(Pair<String, String> fileContent) {
        String expected = fileContent.getRight() != null ? normalizeLineEndings(fileContent.getRight()) : null;
        String actual = gitService.getFileContentAtRef("HEAD", fileContent.getLeft())
                .map(this::normalizeLineEndings).orElse(null);

        return () -> assertEquals(expected, actual,
                "Content mismatch for file: %s, actual: %s".formatted(fileContent.getLeft(), actual));
    }

    private String normalizeLineEndings(String input) {
        return input.replace("\r\n", "\n").replace("\r", "\n");
    }
}

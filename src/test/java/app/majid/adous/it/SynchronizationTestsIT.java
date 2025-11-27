package app.majid.adous.it;

import app.majid.adous.db.config.DatabaseContextHolder;
import app.majid.adous.git.service.GitCommitService;
import app.majid.adous.git.service.GitService;
import app.majid.adous.synchronizer.db.DatabaseService;
import app.majid.adous.synchronizer.model.DbObject;
import app.majid.adous.synchronizer.model.DbObjectType;
import app.majid.adous.synchronizer.model.RepoObject;
import app.majid.adous.synchronizer.model.SyncResult;
import app.majid.adous.synchronizer.service.DatabaseRepositorySynchronizerService;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.util.Pair;

import java.util.List;
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

    @Autowired
    GitCommitService gitCommitService;

    @Autowired
    DatabaseService databaseService;

    @Test
    void testSynchronizerService() throws Exception {
        synchronizerService.initRepo("db1");
        assertInitializedRepoState();

        synchronizerService.syncDbToRepo("db2", false);
        assertDb2SyncedRepoState();
        assertNotEquals(getTagObjectId("db1"), getTagObjectId("db2"));

        synchronizerService.syncRepoToDb(Constants.HEAD, List.of("db1"), false, false);
        assertEquals(getTagObjectId("db1"), getTagObjectId("db2"));

        // Update object in base folder of repo and sync to dbs should update all dbs when there is no diff for that object
        assertUpdateBaseObjectWithoutDiffAndSyncToDbs();

        // Update proc1 in repo and sync to db1 and db2, then verify proc1 is not updated in db2 due to diff override
        assertUpdateBaseObjectWithDiffAndSyncToDbs();

        // Remove proc1 from diff folder of db2 and sync to db2, then verify proc1 updated to base definition
        assertRemoveDiffObjectAndSyncToDb2();

        // Sync changed repo to a db that is not sync with repo, has some manual changes
        assertSyncRepoChangeToUnsyncedDb();
    }

    private void assertInitializedRepoState() throws Exception {
        assertEquals(getHeadObjectId(), getTagObjectId("db1"));

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
                        "base/FUNCTION/dbo/prefix3_func1.sql",
                        // New: Synonyms
                        "base/SYNONYM/dbo/syn_table1.sql",
                        "base/SYNONYM/dbo/syn_remotetable.sql",
                        "base/SYNONYM/dbo/prefix1_syn_table.sql",
                        // New: Table Types
                        "base/TABLE_TYPE/dbo/tt_userlist.sql",
                        "base/TABLE_TYPE/dbo/tt_orderdetails.sql",
                        "base/TABLE_TYPE/dbo/prefix1_tt_list.sql",
                        // New: Sequences
                        "base/SEQUENCE/dbo/seq_orderid.sql",
                        "base/SEQUENCE/dbo/prefix1_seq_temp.sql",
                        // New: Scalar types
                        "base/SCALAR_TYPE/dbo/phonenumber.sql",
                        "base/SCALAR_TYPE/dbo/countrycode.sql",
                        // New: Tables
                        "base/TABLE/dbo/table1.sql",
                        "base/TABLE/dbo/users.sql",
                        "base/TABLE/dbo/orders.sql"
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
                        Pair.of("base/PROCEDURE/dbo/proc1.sql", """
                                SET ANSI_NULLS ON;
                                GO
                                SET QUOTED_IDENTIFIER ON;
                                GO
                                CREATE PROCEDURE proc1 AS BEGIN SELECT 'Procedure 1 executed' AS Message; END
                                GO"""),
                        Pair.of("base/TRIGGER/dbo/trigger1.sql", """
                                SET ANSI_NULLS ON;
                                GO
                                SET QUOTED_IDENTIFIER ON;
                                GO
                                CREATE TRIGGER trigger1 ON table1 AFTER INSERT AS BEGIN INSERT INTO table1 (name) VALUES ('test name 1'); END
                                GO"""),
                        Pair.of("base/VIEW/dbo/view1.sql", """
                                SET ANSI_NULLS ON;
                                GO
                                SET QUOTED_IDENTIFIER ON;
                                GO
                                CREATE VIEW view1 AS SELECT id FROM table1
                                GO"""),
                        Pair.of("base/FUNCTION/dbo/func1.sql", """
                                SET ANSI_NULLS ON;
                                GO
                                SET QUOTED_IDENTIFIER ON;
                                GO
                                CREATE FUNCTION func1() RETURNS INT BEGIN RETURN 1 END
                                GO"""),
                        Pair.of("base/SYNONYM/dbo/syn_table1.sql", """
                                CREATE SYNONYM [dbo].[syn_table1] FOR [dbo].[table1];
                                GO"""),
                        Pair.of("base/TABLE_TYPE/dbo/tt_userlist.sql", """
                                CREATE TYPE [dbo].[tt_userlist] AS TABLE
                                (
                                    [UserId] int NOT NULL,
                                    [UserName] nvarchar(100) NOT NULL
                                );
                                GO"""),
                        // New sequence definition
                        Pair.of("base/SEQUENCE/dbo/seq_orderid.sql", """
                                CREATE SEQUENCE [dbo].[seq_orderid]
                                    AS int
                                    START WITH 1
                                    INCREMENT BY 1
                                    MINVALUE 1
                                    MAXVALUE 1000
                                    NO CYCLE
                                    NO CACHE;
                                GO"""),
                        Pair.of("base/SEQUENCE/dbo/prefix1_seq_temp.sql", """
                                CREATE SEQUENCE [dbo].[prefix1_seq_temp]
                                    AS bigint
                                    START WITH 100
                                    INCREMENT BY 10
                                    MINVALUE 0
                                    MAXVALUE 100000
                                    NO CYCLE
                                    CACHE 20;
                                GO"""),
                        // New scalar types
                        Pair.of("base/SCALAR_TYPE/dbo/phonenumber.sql", """
                                CREATE TYPE [dbo].[phonenumber]
                                    FROM varchar(20) NOT NULL;
                                GO"""),
                        Pair.of("base/SCALAR_TYPE/dbo/countrycode.sql", """
                                CREATE TYPE [dbo].[countrycode]
                                    FROM char(2) NOT NULL;
                                GO""")
                ).map(this::assertFileContent).toArray(Executable[]::new)
        );
    }

    private void assertDb2SyncedRepoState() throws Exception {
        assertEquals(getHeadObjectId(), getTagObjectId("db2"));

        assertAll("Checking existence of all non-ignored database objects in git repo",
                Stream.of(
                        "diff/test-prefix/db2/PROCEDURE/dbo/proc1.sql",
                        "diff/test-prefix/db2/PROCEDURE/dbo/prefix4_proc1.sql",
                        "diff/test-prefix/db2/TRIGGER/dbo/trigger1.sql",
                        "diff/test-prefix/db2/VIEW/dbo/view1.sql",
                        "diff/test-prefix/db2/FUNCTION/dbo/func1.sql",
                        // New diffs: sequence and scalar type with differences
                        "diff/test-prefix/db2/SEQUENCE/dbo/seq_orderid.sql",
                        "diff/test-prefix/db2/SCALAR_TYPE/dbo/phonenumber.sql",
                        // New diffs: tables with differences
                        "diff/test-prefix/db2/TABLE/dbo/users.sql",
                        "diff/test-prefix/db2/TABLE/dbo/orders.sql"
                ).map(this::assertFileExists).toArray(Executable[]::new)
        );

        assertAll("Checking non-existence of all ignored or unchanged database objects in git repo",
                Stream.of(
                        "diff/test-prefix/db2/PROCEDURE/dbo/prefix1_proc2.sql",
                        "diff/test-prefix/db2/VIEW/dbo/prefix3_view2.sql",
                        "diff/test-prefix/db2/TRIGGER/dbo/prefix2_trigger1.sql",
                        "diff/test-prefix/db2/FUNCTION/dbo/prefix4_func2.sql",
                        "diff/test-prefix/db2/SYNONYM/dbo/syn_table1.sql",
                        "diff/test-prefix/db2/SYNONYM/dbo/syn_remotetable.sql",
                        "diff/test-prefix/db2/TABLE_TYPE/dbo/tt_userlist.sql",
                        "diff/test-prefix/db2/TABLE_TYPE/dbo/tt_orderdetails.sql",
                        // Sequences identical should not appear
                        "diff/test-prefix/db2/SEQUENCE/dbo/prefix1_seq_temp.sql",
                        // Scalar types identical should not appear
                        "diff/test-prefix/db2/SCALAR_TYPE/dbo/countrycode.sql",
                        // Tables identical should not appear
                        "diff/test-prefix/db2/TABLE/dbo/table1.sql"
                ).map(this::assertFileNotExists).toArray(Executable[]::new)
        );

        assertAll("Checking definitions of selected database objects in git repo",
                Stream.of(
                        Pair.of("diff/test-prefix/db2/PROCEDURE/dbo/proc1.sql", """
                                SET ANSI_NULLS ON;
                                GO
                                SET QUOTED_IDENTIFIER ON;
                                GO
                                CREATE PROCEDURE proc1 AS BEGIN SELECT 'Procedure 11 executed' AS Message; END
                                GO"""),
                        Pair.of("diff/test-prefix/db2/PROCEDURE/dbo/prefix4_proc1.sql", ""),
                        Pair.of("diff/test-prefix/db2/TRIGGER/dbo/trigger1.sql", """
                                SET ANSI_NULLS ON;
                                GO
                                SET QUOTED_IDENTIFIER ON;
                                GO
                                CREATE TRIGGER trigger1 ON table1 AFTER INSERT AS BEGIN INSERT INTO table1 (name) VALUES ('test name 11'); END
                                GO"""),
                        Pair.of("diff/test-prefix/db2/VIEW/dbo/view1.sql", """
                                SET ANSI_NULLS ON;
                                GO
                                SET QUOTED_IDENTIFIER ON;
                                GO
                                CREATE VIEW view1 AS SELECT id, 'p1' AS prefix FROM table1
                                GO"""),
                        Pair.of("diff/test-prefix/db2/FUNCTION/dbo/func1.sql", """
                                SET ANSI_NULLS ON;
                                GO
                                SET QUOTED_IDENTIFIER ON;
                                GO
                                CREATE FUNCTION func1() RETURNS INT BEGIN RETURN 11 END
                                GO"""),
                        // Sequence diff
                        Pair.of("diff/test-prefix/db2/SEQUENCE/dbo/seq_orderid.sql", """
                                CREATE SEQUENCE [dbo].[seq_orderid]
                                    AS int
                                    START WITH 1
                                    INCREMENT BY 2
                                    MINVALUE 1
                                    MAXVALUE 1000
                                    NO CYCLE
                                    NO CACHE;
                                GO"""),
                        // Scalar type diff
                        Pair.of("diff/test-prefix/db2/SCALAR_TYPE/dbo/phonenumber.sql", """
                                CREATE TYPE [dbo].[phonenumber]
                                    FROM varchar(25) NOT NULL;
                                GO""")
                ).map(this::assertFileContent).toArray(Executable[]::new)
        );
    }

    private void assertUpdateBaseObjectWithoutDiffAndSyncToDbs() throws Exception {
        String proc2Def= """
                        SET ANSI_NULLS ON;
                        GO
                        SET QUOTED_IDENTIFIER ON;
                        GO
                        CREATE PROCEDURE proc2 AS BEGIN SELECT 'Procedure 2 UPDATED executed' AS Message; END
                        GO""";
        updateRepo(List.of(new RepoObject("base/PROCEDURE/dbo/proc2.sql", proc2Def)));
        synchronizerService.syncRepoToDb(Constants.HEAD, List.of("db1", "db2"), false, false);
        var expectedProc2DbObject = new DbObject("dbo", "proc2", DbObjectType.PROCEDURE, proc2Def);
        assertObjectExistInDb("db1", expectedProc2DbObject);
        assertObjectExistInDb("db2", expectedProc2DbObject);
    }

    private void assertUpdateBaseObjectWithDiffAndSyncToDbs() throws Exception {
        String proc1Def= """
                        SET ANSI_NULLS ON;
                        GO
                        SET QUOTED_IDENTIFIER ON;
                        GO
                        CREATE PROCEDURE proc1 AS BEGIN SELECT 'Procedure 1 UPDATED executed' AS Message; END
                        GO""";
        updateRepo(List.of(new RepoObject("base/PROCEDURE/dbo/proc1.sql", proc1Def)));
        synchronizerService.syncRepoToDb(Constants.HEAD, List.of("db1", "db2"), false, false);
        var expectedProc1DbObjectDb1 = new DbObject("dbo", "proc1", DbObjectType.PROCEDURE, proc1Def);
        var expectedProc1DbObjectDb2 = new DbObject("dbo", "proc1", DbObjectType.PROCEDURE, """
                                SET ANSI_NULLS ON;
                                GO
                                SET QUOTED_IDENTIFIER ON;
                                GO
                                CREATE PROCEDURE proc1 AS BEGIN SELECT 'Procedure 11 executed' AS Message; END
                                GO""");
        assertObjectExistInDb("db1", expectedProc1DbObjectDb1);
        assertObjectExistInDb("db2", expectedProc1DbObjectDb2);
    }

    private void assertRemoveDiffObjectAndSyncToDb2() throws Exception {
        DatabaseContextHolder.setCurrentDb("db2");
        String proc1OldDef = databaseService.getDbObjects().stream()
                .filter(o -> o.name().equals("proc1"))
                .findFirst()
                .map(DbObject::definition)
                .orElseThrow(() -> new AssertionError("proc1 not found in db2 before update"));
        String proc1NewDef = """
                        SET ANSI_NULLS ON;
                        GO
                        SET QUOTED_IDENTIFIER ON;
                        GO
                        CREATE PROCEDURE proc1 AS BEGIN SELECT 'Procedure 1 UPDATED executed' AS Message; END
                        GO""";
        updateRepo(List.of(new RepoObject("diff/test-prefix/db2/PROCEDURE/dbo/proc1.sql", null)));
        String expectedResponse = "[DbObject[schema=dbo, name=proc1, type=PROCEDURE]]";

        // Dry run true
        List<SyncResult> dryRunResponse = synchronizerService.syncRepoToDb(Constants.HEAD, List.of("db2"), true, false);

        assertEquals(SyncResult.Status.SUCCESS_DRY_RUN, dryRunResponse.getFirst().status());
        assertEquals(expectedResponse, dryRunResponse.getFirst().message());
        var expectedOldProc1 = new DbObject("dbo", "proc1", DbObjectType.PROCEDURE, proc1OldDef);
        assertObjectExistInDb("db2", expectedOldProc1);

        // Dry run false
        List<SyncResult> response = synchronizerService.syncRepoToDb(Constants.HEAD, List.of("db2"), false, false);

        assertEquals(SyncResult.Status.SYNCED, response.getFirst().status());
        assertEquals(expectedResponse, response.getFirst().message());
        var expectedNewProc1 = new DbObject("dbo", "proc1", DbObjectType.PROCEDURE, proc1NewDef);
        assertObjectExistInDb("db2", expectedNewProc1);
    }

    private void assertSyncRepoChangeToUnsyncedDb() throws Exception {
        String proc1Def = """
                        SET ANSI_NULLS ON;
                        GO
                        SET QUOTED_IDENTIFIER ON;
                        GO
                        CREATE PROCEDURE proc1 AS BEGIN SELECT 'Procedure 1 Update for unsyncedDb' AS Message; END
                        GO""";
        updateRepo(List.of(new RepoObject("base/PROCEDURE/dbo/proc1.sql", proc1Def)));
        String proc2Def = """
                        SET ANSI_NULLS ON;
                        GO
                        SET QUOTED_IDENTIFIER ON;
                        GO
                        CREATE PROCEDURE proc2 AS BEGIN SELECT 'Procedure 2 Update for unsyncedDb' AS Message; END
                        GO""";
        var dbObject = new DbObject("dbo", "proc2", DbObjectType.PROCEDURE, proc2Def);
        DatabaseContextHolder.setCurrentDb("db2");
        databaseService.applyChangesToDatabase(List.of(dbObject));

        var actualOutOfSyncResponse =
                synchronizerService.syncRepoToDb(Constants.HEAD, List.of("db2"), false, false);
        assertEquals(SyncResult.Status.SKIPPED_OUT_OF_SYNC, actualOutOfSyncResponse.getFirst().status());

        // Dryrun set to true
        var actualDryRunResponse = synchronizerService.syncDbToRepo("db2", true);

        var expectedResponse = "[RepoObject[path=diff/test-prefix/db2/PROCEDURE/dbo/proc2.sql]]";
        assertEquals(normalizeLineEndings(expectedResponse), normalizeLineEndings(actualDryRunResponse));
        assertTrue(gitService.getFileContentAtRef(Constants.HEAD, "diff/test-prefix/db2/PROCEDURE/dbo/proc2.sql").isEmpty());

        // Dryrun set to false
        var actualResponse = synchronizerService.syncDbToRepo("db2", false);
        assertEquals(normalizeLineEndings(expectedResponse), normalizeLineEndings(actualResponse));
        var proc2InDb2Diff = gitService.getFileContentAtRef(Constants.HEAD, "diff/test-prefix/db2/PROCEDURE/dbo/proc2.sql").get();
        assertEquals(normalizeLineEndings(proc2Def), normalizeLineEndings(proc2InDb2Diff));

        // Already synced, so no changes
        var noChangeResponse = synchronizerService.syncDbToRepo("db2", false);
        assertEquals("[]", noChangeResponse);
    }

    private void updateRepo(List<RepoObject> changes) throws Exception {
        String headRef = repo.exactRef(Constants.HEAD).getTarget().getName();
        gitCommitService.applyChanges(changes, "Updated proc2 definition in repo", headRef);
    }

    private void assertObjectExistInDb(String dbName, DbObject expectedObject) {
        DatabaseContextHolder.setCurrentDb(dbName);
        var dbObjects = databaseService.getDbObjects();
        assertTrue(dbObjects.stream().anyMatch (o ->
                        o.name().equals(expectedObject.name()) &&
                                o.schema().equals(expectedObject.schema()) &&
                                o.type() == expectedObject.type() &&
                                normalizeLineEndings(o.definition()).equals(normalizeLineEndings(expectedObject.definition()))),
                "Expected object not found in database %s: %s".formatted(dbName, expectedObject));
    }

    private ObjectId getTagObjectId(String tag) throws Exception {
        return repo.findRef(Constants.R_TAGS + tag).getObjectId();
    }

    private ObjectId getHeadObjectId() throws Exception {
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
        String expected = normalizeLineEndings(fileContent.getSecond());
        String actual = gitService.getFileContentAtRef("HEAD", fileContent.getFirst())
                .map(this::normalizeLineEndings).orElse(null);

        return () -> assertEquals(expected, actual,
                "Content mismatch for file: %s, actual: %s".formatted(fileContent.getFirst(), actual));
    }

    private String normalizeLineEndings(String input) {
        return input.replace("\r\n", "\n").replace("\r", "\n");
    }
}

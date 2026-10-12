package app.majid.adous.synchronizer.service;

import app.majid.adous.db.config.DatabaseContextHolder;
import app.majid.adous.git.exception.GitPushRejectedException;
import app.majid.adous.git.service.GitService;
import app.majid.adous.synchronizer.db.DatabaseService;
import app.majid.adous.synchronizer.model.DbObject;
import app.majid.adous.synchronizer.model.DbObjectType;
import app.majid.adous.synchronizer.model.SyncResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DatabaseRepositorySynchronizerServiceTest {

    private GitService gitService;
    private DatabaseService databaseService;
    private DatabaseRepositorySynchronizerService service;

    @BeforeEach
    void setUp() throws Exception {
        DatabaseContextHolder.setAvailableDbs(Set.of("db1", "DB2", "db3"));

        gitService = mock(GitService.class);
        databaseService = mock(DatabaseService.class);
        TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
        when(transactionTemplate.execute(any())).thenAnswer(inv ->
                inv.<TransactionCallback<?>>getArgument(0).doInTransaction(mock(TransactionStatus.class)));

        when(gitService.isHeadCommit("HEAD")).thenReturn(true);
        when(gitService.getRepoChangesToApplyToDb(eq("HEAD"), anyString()))
                .thenReturn(List.of(new DbObject("dbo", "v1", DbObjectType.VIEW, "CREATE VIEW v1 AS SELECT 1")));

        service = new DatabaseRepositorySynchronizerService(gitService, databaseService,
                mock(SqlEquivalenceCheckerService.class), new SynchronizerIgnoreService(""), transactionTemplate);
    }

    @Test
    void shouldReportFailedDatabaseWithoutFailingTheBatchWhenSqlFails() throws Exception {
        doAnswer(inv -> {
            if ("db2".equals(DatabaseContextHolder.getCurrentDb())) {
                throw new DataIntegrityViolationException("boom on db2");
            }
            return null;
        }).when(databaseService).applyChangesToDatabase(anyList());

        Map<String, SyncResult> results = byDb(service.syncRepoToDb("HEAD", List.of("db1", "db2", "db3"), false, true));

        assertEquals(SyncResult.Status.SYNCED, results.get("db1").status());
        assertEquals(SyncResult.Status.FAILED, results.get("db2").status());
        assertTrue(results.get("db2").message().contains("boom on db2"));
        assertEquals(SyncResult.Status.SYNCED, results.get("db3").status());
        verify(gitService, never()).addTags(eq(List.of("db2")), anyString());
    }

    @Test
    void shouldReportFailedDatabaseWhenTagPushIsRejected() throws Exception {
        doThrow(new GitPushRejectedException(List.of("refs/tags/db1: REJECTED_OTHER_REASON")))
                .when(gitService).addTags(eq(List.of("db1")), anyString());

        Map<String, SyncResult> results = byDb(service.syncRepoToDb("HEAD", List.of("db1", "db3"), false, true));

        assertEquals(SyncResult.Status.FAILED, results.get("db1").status());
        assertTrue(results.get("db1").message().contains("Push rejected"));
        assertEquals(SyncResult.Status.SYNCED, results.get("db3").status());
    }

    @Test
    void shouldReportUnknownDatabaseAsFailed() throws Exception {
        Map<String, SyncResult> results = byDb(service.syncRepoToDb("HEAD", List.of("db1", "nope"), false, true));

        assertEquals(SyncResult.Status.SYNCED, results.get("db1").status());
        assertEquals(SyncResult.Status.FAILED, results.get("nope").status());
    }

    @Test
    void shouldRouteToDatabaseConfiguredWithDifferentCase() throws Exception {
        Map<String, SyncResult> results = byDb(service.syncRepoToDb("HEAD", List.of("Db2"), false, true));

        assertEquals(SyncResult.Status.SYNCED, results.get("db2").status());
        verify(gitService).addTags(List.of("db2"), "HEAD");
    }

    private static Map<String, SyncResult> byDb(List<SyncResult> results) {
        return results.stream().collect(Collectors.toMap(SyncResult::dbName, Function.identity()));
    }
}

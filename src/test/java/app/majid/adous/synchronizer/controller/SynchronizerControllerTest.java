package app.majid.adous.synchronizer.controller;

import app.majid.adous.synchronizer.controller.dto.SyncDbToRepoRequest;
import app.majid.adous.synchronizer.controller.dto.SyncRepoToDbRequest;
import app.majid.adous.synchronizer.model.DbObjectChange;
import app.majid.adous.synchronizer.model.DbObjectType;
import app.majid.adous.synchronizer.model.RepoObject;
import app.majid.adous.synchronizer.model.SyncResult;
import app.majid.adous.synchronizer.service.DatabaseRepositorySynchronizerService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(SynchronizerController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler.class)
class SynchronizerControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private DatabaseRepositorySynchronizerService synchronizerService;

    @Test
    void syncDbToRepo_shouldReturnSuccessResponse() throws Exception {
        // Given
        String dbName = "testdb";
        List<RepoObject> changed = List.of(
                new RepoObject("diff/p/testdb/PROCEDURE/dbo/proc1.sql", "CREATE PROCEDURE dbo.proc1 AS SELECT 1"),
                new RepoObject("diff/p/testdb/VIEW/dbo/v1.sql", null),
                new RepoObject("diff/p/testdb/FUNCTION/dbo/f1.sql", ""));
        when(synchronizerService.syncDbToRepo(eq(dbName), eq(true)))
                .thenReturn(changed);

        // When & Then
        mockMvc.perform(post("/api/synchronizer/db-to-repo/{dbName}", dbName)
                        .param("dryRun", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dbName").value(dbName))
                .andExpect(jsonPath("$.dryRun").value(true))
                .andExpect(jsonPath("$.result").doesNotExist())
                .andExpect(jsonPath("$.changes.length()").value(3))
                .andExpect(jsonPath("$.changes[0].path").value("diff/p/testdb/PROCEDURE/dbo/proc1.sql"))
                .andExpect(jsonPath("$.changes[0].type").value("PROCEDURE"))
                .andExpect(jsonPath("$.changes[0].schema").value("dbo"))
                .andExpect(jsonPath("$.changes[0].name").value("proc1"))
                .andExpect(jsonPath("$.changes[0].action").value("WRITE"))
                .andExpect(jsonPath("$.changes[1].action").value("DELETE"))
                .andExpect(jsonPath("$.changes[2].action").value("MARK_ABSENT"))
                .andExpect(jsonPath("$.changes[0].definition").doesNotExist());
    }

    @Test
    void initRepo_shouldReturnWrittenFiles() throws Exception {
        when(synchronizerService.initRepo("testdb")).thenReturn(List.of(
                new RepoObject("base/TABLE/dbo/users.sql", "CREATE TABLE dbo.users (id int)")));

        mockMvc.perform(post("/api/synchronizer/init-repo/{dbName}", "testdb"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.changes[0].type").value("TABLE"))
                .andExpect(jsonPath("$.changes[0].name").value("users"))
                .andExpect(jsonPath("$.changes[0].action").value("WRITE"));
    }

    @Test
    void syncRepoToDb_shouldReturnSyncResults() throws Exception {
        // Given
        List<String> dbs = List.of("db1", "db2");
        List<SyncResult> expectedResults = List.of(
                new SyncResult("db1", SyncResult.Status.SYNCED, null, List.of(
                        new DbObjectChange(DbObjectType.PROCEDURE, "dbo", "proc1", DbObjectChange.Action.CREATE_OR_UPDATE),
                        new DbObjectChange(DbObjectType.VIEW, "dbo", "v1", DbObjectChange.Action.DROP))),
                new SyncResult("db2", SyncResult.Status.SKIPPED_NOT_ONBOARDED, "")
        );
        when(synchronizerService.syncRepoToDb(eq("main"), eq(dbs), eq(false), eq(false), eq(false)))
                .thenReturn(expectedResults);

        SyncRepoToDbRequest request = new SyncRepoToDbRequest("main", dbs, false, false, false);

        // When & Then
        mockMvc.perform(post("/api/synchronizer/repo-to-db")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].dbName").value("db1"))
                .andExpect(jsonPath("$[0].status").value("SYNCED"))
                .andExpect(jsonPath("$[0].message").doesNotExist())
                .andExpect(jsonPath("$[0].changes[0].type").value("PROCEDURE"))
                .andExpect(jsonPath("$[0].changes[0].action").value("CREATE_OR_UPDATE"))
                .andExpect(jsonPath("$[0].changes[1].action").value("DROP"))
                .andExpect(jsonPath("$[1].changes.length()").value(0))
                .andExpect(jsonPath("$[1].dbName").value("db2"))
                .andExpect(jsonPath("$[1].status").value("SKIPPED_NOT_ONBOARDED"));
    }

    @Test
    void syncRepoToDb_shouldPassAllowDataLossThrough() throws Exception {
        List<String> dbs = List.of("db1");
        when(synchronizerService.syncRepoToDb(eq("main"), eq(dbs), eq(false), eq(false), eq(true)))
                .thenReturn(List.of(new SyncResult("db1", SyncResult.Status.SYNCED, "")));

        mockMvc.perform(post("/api/synchronizer/repo-to-db")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new SyncRepoToDbRequest("main", dbs, false, false, true))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("SYNCED"));
    }

    @Test
    void syncRepoToDb_shouldDefaultAllowDataLossToFalseWhenOmitted() throws Exception {
        when(synchronizerService.syncRepoToDb(eq("main"), eq(List.of("db1")), eq(false), eq(false), eq(false)))
                .thenReturn(List.of(new SyncResult("db1", SyncResult.Status.SKIPPED_DATA_LOSS, "blocked")));

        mockMvc.perform(post("/api/synchronizer/repo-to-db")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commitish\":\"main\",\"dbs\":[\"db1\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("SKIPPED_DATA_LOSS"));
    }

    @Test
    void syncRepoToDb_withIOError_shouldReturnInternalServerError() throws Exception {
        // Given
        List<String> dbs = List.of("db1");
        when(synchronizerService.syncRepoToDb(anyString(), anyList(), anyBoolean(), anyBoolean(), anyBoolean()))
                .thenThrow(new IOException("IO error"));

        SyncRepoToDbRequest request = new SyncRepoToDbRequest("main", dbs, false, false, false);

        // When & Then
        mockMvc.perform(post("/api/synchronizer/repo-to-db")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isInternalServerError());
    }

    @Test
    void syncRepoToDb_withEmptyDbs_shouldReturnBadRequest() throws Exception {
        // Given
        SyncRepoToDbRequest request = new SyncRepoToDbRequest("main", List.of(), false, false, false);

        // When & Then
        mockMvc.perform(post("/api/synchronizer/repo-to-db")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void syncRepoToDb_withBlankCommitish_shouldReturnBadRequest() throws Exception {
        // Given
        SyncRepoToDbRequest request = new SyncRepoToDbRequest("", List.of("db1"), false, false, false);

        // When & Then
        mockMvc.perform(post("/api/synchronizer/repo-to-db")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }
}


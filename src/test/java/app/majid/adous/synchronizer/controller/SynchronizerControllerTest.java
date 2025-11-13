package app.majid.adous.synchronizer.controller;

import app.majid.adous.synchronizer.controller.dto.SyncDbToRepoRequest;
import app.majid.adous.synchronizer.controller.dto.SyncRepoToDbRequest;
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
        String syncResult = "[RepoObject(path=base/table/dbo/test.sql)]";
        when(synchronizerService.syncDbToRepo(eq(dbName), eq(true)))
                .thenReturn(syncResult);

        // When & Then
        mockMvc.perform(post("/api/synchronizer/db-to-repo/{dbName}", dbName)
                        .param("dryRun", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dbName").value(dbName))
                .andExpect(jsonPath("$.dryRun").value(true))
                .andExpect(jsonPath("$.result").value(syncResult));
    }

    @Test
    void syncDbToRepo_withGitError_shouldReturnInternalServerError() throws Exception {
        // Given
        String dbName = "testdb";
        when(synchronizerService.syncDbToRepo(eq(dbName), eq(false)))
                .thenThrow(new GitAPIException("Git error") {});

        SyncDbToRepoRequest request = new SyncDbToRepoRequest(dbName, false);

        // When & Then
        mockMvc.perform(post("/api/synchronizer/db-to-repo")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error").value("Git API Error"))
                .andExpect(jsonPath("$.message").value("Error syncing with Git repository: Git error"));
    }

    @Test
    void syncDbToRepo_withIllegalState_shouldReturnBadRequest() throws Exception {
        // Given
        String dbName = "testdb";
        when(synchronizerService.syncDbToRepo(eq(dbName), eq(false)))
                .thenThrow(new IllegalStateException("Database not initialized"));

        SyncDbToRepoRequest request = new SyncDbToRepoRequest(dbName, false);

        // When & Then
        mockMvc.perform(post("/api/synchronizer/db-to-repo")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Invalid State"))
                .andExpect(jsonPath("$.message").value("Database not initialized"));
    }

    @Test
    void syncDbToRepo_withBlankDbName_shouldReturnBadRequest() throws Exception {
        // Given
        SyncDbToRepoRequest request = new SyncDbToRepoRequest("", false);

        // When & Then
        mockMvc.perform(post("/api/synchronizer/db-to-repo")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void syncRepoToDb_shouldReturnSyncResults() throws Exception {
        // Given
        List<String> dbs = List.of("db1", "db2");
        List<SyncResult> expectedResults = List.of(
                new SyncResult("db1", SyncResult.Status.SYNCED, "Changes applied"),
                new SyncResult("db2", SyncResult.Status.SKIPPED_NOT_ONBOARDED, "")
        );
        when(synchronizerService.syncRepoToDb(eq("main"), eq(dbs), eq(false), eq(false)))
                .thenReturn(expectedResults);

        SyncRepoToDbRequest request = new SyncRepoToDbRequest("main", dbs, false, false);

        // When & Then
        mockMvc.perform(post("/api/synchronizer/repo-to-db")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].dbName").value("db1"))
                .andExpect(jsonPath("$[0].status").value("SYNCED"))
                .andExpect(jsonPath("$[1].dbName").value("db2"))
                .andExpect(jsonPath("$[1].status").value("SKIPPED_NOT_ONBOARDED"));
    }

    @Test
    void syncRepoToDb_withIOError_shouldReturnInternalServerError() throws Exception {
        // Given
        List<String> dbs = List.of("db1");
        when(synchronizerService.syncRepoToDb(anyString(), anyList(), anyBoolean(), anyBoolean()))
                .thenThrow(new IOException("IO error"));

        SyncRepoToDbRequest request = new SyncRepoToDbRequest("main", dbs, false, false);

        // When & Then
        mockMvc.perform(post("/api/synchronizer/repo-to-db")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isInternalServerError());
    }

    @Test
    void syncRepoToDb_withEmptyDbs_shouldReturnBadRequest() throws Exception {
        // Given
        SyncRepoToDbRequest request = new SyncRepoToDbRequest("main", List.of(), false, false);

        // When & Then
        mockMvc.perform(post("/api/synchronizer/repo-to-db")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void syncRepoToDb_withBlankCommitish_shouldReturnBadRequest() throws Exception {
        // Given
        SyncRepoToDbRequest request = new SyncRepoToDbRequest("", List.of("db1"), false, false);

        // When & Then
        mockMvc.perform(post("/api/synchronizer/repo-to-db")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }
}


package com.agentwork.productspecagent.api

import com.agentwork.productspecagent.domain.Project
import com.agentwork.productspecagent.domain.ProjectStatus
import com.agentwork.productspecagent.domain.okf.OkfChangeset
import com.agentwork.productspecagent.domain.okf.OkfChangesetKind
import com.agentwork.productspecagent.domain.okf.OkfConflict
import com.agentwork.productspecagent.domain.okf.OkfConflictResolution
import com.agentwork.productspecagent.domain.okf.OkfFileOperation
import com.agentwork.productspecagent.domain.okf.OkfGitConnection
import com.agentwork.productspecagent.domain.okf.OkfMigrationPreview
import com.agentwork.productspecagent.domain.okf.OkfMode
import com.agentwork.productspecagent.domain.okf.OkfProjectState
import com.agentwork.productspecagent.domain.okf.OkfSyncStatus
import com.agentwork.productspecagent.service.okf.OkfChangesetConflictException
import com.agentwork.productspecagent.service.okf.OkfGitAuthenticationException
import com.agentwork.productspecagent.service.okf.OkfMigrationService
import com.agentwork.productspecagent.service.okf.OkfPendingChangesException
import com.agentwork.productspecagent.service.okf.OkfStaleRemoteException
import com.agentwork.productspecagent.service.okf.OkfSyncService
import com.agentwork.productspecagent.service.OkfChangesetPendingException
import com.agentwork.productspecagent.storage.OkfStateStorage
import com.agentwork.productspecagent.storage.ProjectStorage
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argThat
import org.mockito.kotlin.eq
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.security.test.context.support.WithAnonymousUser
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@SpringBootTest(properties = ["okf.git.allow-file-remotes=true"])
@AutoConfigureMockMvc
@WithMockUser
class OkfControllerTest {

    @Autowired lateinit var mockMvc: MockMvc
    @MockitoBean lateinit var syncService: OkfSyncService
    @MockitoBean lateinit var migrationService: OkfMigrationService
    @MockitoBean lateinit var stateStorage: OkfStateStorage
    @MockitoBean lateinit var projectStorage: ProjectStorage

    @BeforeEach
    fun projectExists() {
        whenever(projectStorage.loadProject(PROJECT_ID)).thenReturn(PROJECT)
    }

    @Test
    fun `connection validates and persists only URL and branch names`() {
        whenever(stateStorage.loadState(PROJECT_ID)).thenReturn(STATE)
        whenever(stateStorage.loadPendingChangeset(PROJECT_ID)).thenReturn(null)
        whenever(syncService.status(PROJECT_ID)).thenReturn(
            STATE.copy(connection = CONNECTION),
        )
        whenever(syncService.pending(PROJECT_ID)).thenReturn(null)
        whenever(stateStorage.loadConflicts(PROJECT_ID)).thenReturn(emptyList())

        mockMvc.perform(
            put("/api/v1/projects/$PROJECT_ID/okf/connection")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"remoteUrl":"https://git.example/project.git","baseBranch":"main"}"""),
        )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.state.connection.remoteUrl").value("https://git.example/project.git"))
            .andExpect(jsonPath("$.state.connection.canonicalBranch").value("productspec/okf"))

        verify(stateStorage).saveState(argThat {
            connection == CONNECTION && !connection.remoteUrl.contains("@")
        })
    }

    @Test
    fun `credential bearing connection URL is rejected before persistence`() {
        mockMvc.perform(
            put("/api/v1/projects/$PROJECT_ID/okf/connection")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"remoteUrl":"https://user:secret@git.example/project.git"}"""),
        )
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("OKF_VALIDATION_ERROR"))

        verify(stateStorage, never()).saveState(any())
    }

    @Test
    fun `file remote is accepted only in the configured test mode`() {
        whenever(stateStorage.loadState(PROJECT_ID)).thenReturn(STATE)
        whenever(stateStorage.loadPendingChangeset(PROJECT_ID)).thenReturn(null)
        whenever(syncService.status(PROJECT_ID)).thenReturn(
            STATE.copy(connection = CONNECTION.copy(remoteUrl = "file:///tmp/okf.git")),
        )
        whenever(syncService.pending(PROJECT_ID)).thenReturn(null)
        whenever(stateStorage.loadConflicts(PROJECT_ID)).thenReturn(emptyList())

        mockMvc.perform(
            put("/api/v1/projects/$PROJECT_ID/okf/connection")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"remoteUrl":"file:///tmp/okf.git","baseBranch":"main"}"""),
        ).andExpect(status().isOk())

        verify(stateStorage).saveState(argThat {
            connection?.remoteUrl == "file:///tmp/okf.git"
        })
    }

    @Test
    fun `connection rejects pending changes and unsafe refs`() {
        whenever(stateStorage.loadPendingChangeset(PROJECT_ID)).thenReturn(CHANGESET)

        mockMvc.perform(
            put("/api/v1/projects/$PROJECT_ID/okf/connection")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"remoteUrl":"https://git.example/project.git"}"""),
        ).andExpect(status().isConflict())

        mockMvc.perform(
            put("/api/v1/projects/$PROJECT_ID/okf/connection")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"remoteUrl":"https://git.example/project.git?token=secret","baseBranch":"../bad"}"""),
        ).andExpect(status().isBadRequest())
    }

    @Test
    fun `status includes pending changes and conflicts`() {
        whenever(syncService.status(PROJECT_ID)).thenReturn(STATE.copy(syncStatus = OkfSyncStatus.CONFLICT))
        whenever(syncService.pending(PROJECT_ID)).thenReturn(CHANGESET)
        whenever(stateStorage.loadConflicts(PROJECT_ID)).thenReturn(listOf(CONFLICT))

        mockMvc.perform(get("/api/v1/projects/$PROJECT_ID/okf/status"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.pending.id").value(CHANGESET_ID))
            .andExpect(jsonPath("$.conflicts[0].path").value("features/login.md"))
    }

    @Test
    fun `migration preview and prepare expose service results`() {
        whenever(migrationService.dryRun(PROJECT_ID)).thenReturn(PREVIEW)
        whenever(syncService.pending(PROJECT_ID)).thenReturn(null)
        whenever(migrationService.prepare(PROJECT_ID)).thenReturn(CHANGESET)

        mockMvc.perform(post("/api/v1/projects/$PROJECT_ID/okf/migration/dry-run"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.baseCommit").value("c1"))
        mockMvc.perform(post("/api/v1/projects/$PROJECT_ID/okf/migration/prepare"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.kind").value("MIGRATION"))
    }

    @Test
    fun `divergent pending migration maps to 409`() {
        whenever(syncService.pending(PROJECT_ID)).thenReturn(CHANGESET)
        whenever(migrationService.prepare(PROJECT_ID)).thenThrow(OkfPendingChangesException(PROJECT_ID))

        mockMvc.perform(post("/api/v1/projects/$PROJECT_ID/okf/migration/prepare"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error").value("OKF_CONFLICT"))
    }

    @Test
    fun `pending approve and resolve routes preserve workflow payloads`() {
        whenever(syncService.pending(PROJECT_ID)).thenReturn(CHANGESET)
        whenever(syncService.approve(PROJECT_ID, CHANGESET_ID)).thenReturn(CHANGESET)
        whenever(syncService.resolve(PROJECT_ID, CHANGESET_ID, OkfConflictResolution.PROPOSED))
            .thenReturn(CHANGESET)

        mockMvc.perform(get("/api/v1/projects/$PROJECT_ID/okf/changesets/pending"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(CHANGESET_ID))
        mockMvc.perform(post("/api/v1/projects/$PROJECT_ID/okf/changesets/$CHANGESET_ID/approve"))
            .andExpect(status().isOk())
        mockMvc.perform(
            post("/api/v1/projects/$PROJECT_ID/okf/changesets/$CHANGESET_ID/resolve")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"resolution":"PROPOSED"}"""),
        ).andExpect(status().isOk())

        verify(syncService).resolve(PROJECT_ID, CHANGESET_ID, OkfConflictResolution.PROPOSED)
    }

    @Test
    fun `sync returns refreshed status and invalid remote maps to 422`() {
        whenever(syncService.sync(PROJECT_ID))
            .thenReturn(STATE.copy(syncStatus = OkfSyncStatus.CLEAN, baseCommit = "c2"))
            .thenReturn(STATE.copy(syncStatus = OkfSyncStatus.INVALID_REMOTE, lastError = "bad profile"))
            .thenReturn(STATE.copy(syncStatus = OkfSyncStatus.CONFLICT))
        whenever(syncService.status(PROJECT_ID)).thenReturn(STATE.copy(baseCommit = "c2"))
        whenever(syncService.pending(PROJECT_ID)).thenReturn(null)
        whenever(stateStorage.loadConflicts(PROJECT_ID)).thenReturn(emptyList())

        mockMvc.perform(post("/api/v1/projects/$PROJECT_ID/okf/sync"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.state.baseCommit").value("c2"))
        mockMvc.perform(post("/api/v1/projects/$PROJECT_ID/okf/sync"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.error").value("OKF_INVALID_REMOTE"))
        mockMvc.perform(post("/api/v1/projects/$PROJECT_ID/okf/sync"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error").value("OKF_CONFLICT"))
    }

    @Test
    fun `changeset conflict maps to 409`() {
        whenever(syncService.approve(PROJECT_ID, CHANGESET_ID))
            .thenThrow(OkfChangesetConflictException(CHANGESET_ID))

        mockMvc.perform(post("/api/v1/projects/$PROJECT_ID/okf/changesets/$CHANGESET_ID/approve"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error").value("OKF_CONFLICT"))
    }

    @Test
    fun `pending feature approval has a dedicated error code`() {
        whenever(migrationService.dryRun(PROJECT_ID))
            .thenThrow(OkfChangesetPendingException(PROJECT_ID))

        mockMvc.perform(post("/api/v1/projects/$PROJECT_ID/okf/migration/dry-run"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error").value("OKF_CHANGESET_PENDING"))
    }

    @Test
    fun `stale remote maps to 409 and authentication failure maps to 502`() {
        whenever(syncService.approve(PROJECT_ID, "stale"))
            .thenThrow(OkfStaleRemoteException("remote advanced"))
        whenever(syncService.approve(PROJECT_ID, "auth"))
            .thenThrow(OkfGitAuthenticationException("authentication failed"))

        mockMvc.perform(post("/api/v1/projects/$PROJECT_ID/okf/changesets/stale/approve"))
            .andExpect(status().isConflict())
        mockMvc.perform(post("/api/v1/projects/$PROJECT_ID/okf/changesets/auth/approve"))
            .andExpect(status().isBadGateway())
    }

    @Test
    fun `missing project returns 404 without creating OKF state`() {
        mockMvc.perform(get("/api/v1/projects/missing/okf/status"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").value("NOT_FOUND"))

        verify(stateStorage, never()).saveState(any())
    }

    @Test
    @WithAnonymousUser
    fun `anonymous access to OKF status is rejected`() {
        mockMvc.perform(get("/api/v1/projects/$PROJECT_ID/okf/status"))
            .andExpect(status().isUnauthorized())
    }

    private companion object {
        const val PROJECT_ID = "project-1"
        const val CHANGESET_ID = "changeset-1"
        val CONNECTION = OkfGitConnection("https://git.example/project.git")
        val PROJECT = Project(
            PROJECT_ID,
            "Project",
            "user-1",
            ProjectStatus.DRAFT,
            "2026-07-20T00:00:00Z",
            "2026-07-20T00:00:00Z",
        )
        val STATE = OkfProjectState(PROJECT_ID, mode = OkfMode.LEGACY)
        val CHANGESET = OkfChangeset(
            id = CHANGESET_ID,
            projectId = PROJECT_ID,
            kind = OkfChangesetKind.MIGRATION,
            baseCommit = "c1",
            operations = emptyList<OkfFileOperation>(),
            issues = emptyList(),
            createdAt = "2026-07-20T12:00:00Z",
            updatedAt = "2026-07-20T12:00:00Z",
        )
        val PREVIEW = OkfMigrationPreview(PROJECT_ID, "c1", emptyList(), emptyList(), emptyList())
        val CONFLICT = OkfConflict(CHANGESET_ID, null, "features/login.md", null, null, null)
    }
}

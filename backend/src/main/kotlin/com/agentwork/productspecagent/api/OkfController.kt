package com.agentwork.productspecagent.api

import com.agentwork.productspecagent.domain.okf.ConnectOkfRequest
import com.agentwork.productspecagent.domain.okf.OkfChangeset
import com.agentwork.productspecagent.domain.okf.OkfChangesetKind
import com.agentwork.productspecagent.domain.okf.OkfGitConnection
import com.agentwork.productspecagent.domain.okf.OkfMigrationPreview
import com.agentwork.productspecagent.domain.okf.OkfStatusResponse
import com.agentwork.productspecagent.domain.okf.OkfSyncStatus
import com.agentwork.productspecagent.domain.okf.ResolveOkfConflictRequest
import com.agentwork.productspecagent.service.okf.OkfInvalidRemoteBundleException
import com.agentwork.productspecagent.service.okf.OkfMigrationService
import com.agentwork.productspecagent.service.okf.OkfPendingChangesException
import com.agentwork.productspecagent.service.okf.OkfProjectLocks
import com.agentwork.productspecagent.service.okf.OkfSyncService
import com.agentwork.productspecagent.service.okf.OkfValidationException
import com.agentwork.productspecagent.storage.OkfStateStorage
import com.agentwork.productspecagent.storage.ProjectStorage
import com.agentwork.productspecagent.service.ProjectNotFoundException
import java.net.URI
import java.time.Instant
import org.eclipse.jgit.lib.Repository
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/projects/{projectId}/okf")
class OkfController(
    private val syncService: OkfSyncService,
    private val migrationService: OkfMigrationService,
    private val stateStorage: OkfStateStorage,
    private val projectStorage: ProjectStorage,
) {

    @PutMapping("/connection")
    fun connect(
        @PathVariable projectId: String,
        @RequestBody request: ConnectOkfRequest,
    ): OkfStatusResponse {
        requireProject(projectId)
        val connection = validateConnection(request)
        OkfProjectLocks.withLock(projectId) {
            if (stateStorage.loadPendingChangeset(projectId) != null) {
                throw OkfPendingChangesException(projectId)
            }
            val state = stateStorage.loadState(projectId)
            stateStorage.saveState(
                state.copy(connection = connection, updatedAt = Instant.now().toString()),
            )
        }
        return status(projectId)
    }

    @GetMapping("/status")
    fun status(@PathVariable projectId: String): OkfStatusResponse {
        requireProject(projectId)
        return OkfProjectLocks.withLock(projectId) {
            OkfStatusResponse(
                state = syncService.status(projectId),
                pending = syncService.pending(projectId),
                conflicts = stateStorage.loadConflicts(projectId),
            )
        }
    }

    @PostMapping("/sync")
    fun sync(@PathVariable projectId: String): OkfStatusResponse {
        requireProject(projectId)
        val state = syncService.sync(projectId)
        when (state.syncStatus) {
            OkfSyncStatus.INVALID_REMOTE ->
                throw OkfInvalidRemoteBundleException(state.lastError ?: "remote OKF bundle is invalid")
            OkfSyncStatus.CONFLICT -> throw OkfPendingChangesException(projectId)
            else -> Unit
        }
        return status(projectId)
    }

    @PostMapping("/migration/dry-run")
    fun dryRun(@PathVariable projectId: String): OkfMigrationPreview {
        requireProject(projectId)
        return migrationService.dryRun(projectId)
    }

    @PostMapping("/migration/prepare")
    fun prepare(@PathVariable projectId: String): OkfChangeset {
        requireProject(projectId)
        val pending = syncService.pending(projectId)
        if (pending != null && pending.kind != OkfChangesetKind.MIGRATION) {
            throw OkfPendingChangesException(projectId)
        }
        return migrationService.prepare(projectId)
    }

    @GetMapping("/changesets/pending")
    fun pending(@PathVariable projectId: String): OkfChangeset? {
        requireProject(projectId)
        return syncService.pending(projectId)
    }

    @PostMapping("/changesets/{changesetId}/approve")
    fun approve(
        @PathVariable projectId: String,
        @PathVariable changesetId: String,
    ): OkfChangeset {
        requireProject(projectId)
        return syncService.approve(projectId, changesetId)
    }

    @PostMapping("/changesets/{changesetId}/resolve")
    fun resolve(
        @PathVariable projectId: String,
        @PathVariable changesetId: String,
        @RequestBody request: ResolveOkfConflictRequest,
    ): OkfChangeset? {
        requireProject(projectId)
        return syncService.resolve(projectId, changesetId, request.resolution)
    }

    private fun validateConnection(request: ConnectOkfRequest): OkfGitConnection {
        val uri = try {
            URI(request.remoteUrl)
        } catch (exception: Exception) {
            throw OkfValidationException("invalid Git remote URL", exception)
        }
        validate(uri.rawUserInfo == null, "Git remote URL must not contain credentials")
        validate(
            !uri.isOpaque && uri.rawQuery == null && uri.rawFragment == null,
            "Git remote URL must not contain opaque, query, or fragment components",
        )
        validate(
            uri.scheme?.lowercase() == "https" && !uri.host.isNullOrBlank(),
            "Git remote URL must use HTTPS and contain a host",
        )
        validate(Repository.isValidRefName("refs/heads/${request.baseBranch}"), "invalid base branch")
        return OkfGitConnection(remoteUrl = request.remoteUrl, baseBranch = request.baseBranch)
    }

    private fun validate(condition: Boolean, message: String) {
        if (!condition) throw OkfValidationException(message)
    }

    private fun requireProject(projectId: String) {
        if (projectStorage.loadProject(projectId) == null) throw ProjectNotFoundException(projectId)
    }
}

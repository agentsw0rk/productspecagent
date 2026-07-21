package com.agentwork.productspecagent.service.okf

import com.agentwork.productspecagent.domain.okf.OkfBundle
import com.agentwork.productspecagent.domain.okf.OkfChangeset
import com.agentwork.productspecagent.domain.okf.OkfChangesetKind
import com.agentwork.productspecagent.domain.okf.OkfChangesetStatus
import com.agentwork.productspecagent.domain.okf.OkfConflict
import com.agentwork.productspecagent.domain.okf.OkfFileOperation
import com.agentwork.productspecagent.domain.okf.OkfMode
import com.agentwork.productspecagent.domain.okf.OkfOperationType
import com.agentwork.productspecagent.domain.okf.OkfProjectState
import com.agentwork.productspecagent.domain.okf.OkfSyncStatus
import com.agentwork.productspecagent.storage.OkfStateStorage
import java.time.Instant
import kotlinx.serialization.Serializable
import org.springframework.stereotype.Service

@Serializable
enum class OkfConflictResolution { REMOTE, PROPOSED }

class OkfPushException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

class OkfNotConnectedException(projectId: String) :
    RuntimeException("project is not connected to an OKF Git repository: $projectId")

class OkfChangesetNotFoundException(changesetId: String) :
    RuntimeException("OKF changeset does not exist: $changesetId")

class OkfChangesetConflictException(changesetId: String) :
    RuntimeException("OKF changeset requires conflict resolution: $changesetId")

@Service
class OkfSyncService(
    private val gitClient: OkfGitClient,
    private val stateStorage: OkfStateStorage,
    private val projectionService: OkfProjectionService,
    private val planner: OkfThreeWayPlanner,
    private val parser: OkfParser,
) {

    fun status(projectId: String): OkfProjectState = stateStorage.loadState(projectId)

    fun pending(projectId: String): OkfChangeset? = stateStorage.loadPendingChangeset(projectId)

    fun sync(projectId: String): OkfProjectState = locked(projectId) {
        val current = stateStorage.loadState(projectId)
        val connection = current.connection ?: throw OkfNotConnectedException(projectId)
        val pending = stateStorage.loadPendingChangeset(projectId)
        if (pending?.approvedCommit != null) {
            val base = gitClient.readAt(projectId, connection, requireNotNull(pending.baseCommit))
            val pushed = gitClient.readAt(projectId, connection, pending.approvedCommit)
            val targetFiles = applyOperations(base.files, pending.operations)
            if (pushed.files != targetFiles) {
                return@locked saveState(
                    current.copy(
                        syncStatus = OkfSyncStatus.PUSH_FAILED,
                        pendingChangesetId = pending.id,
                        lastError = "pushed OKF changeset does not match pending content",
                        updatedAt = now(),
                    ),
                )
            }
            val pushedProjection = projectionService.project(projectId, pushed.files)
            if (!pushedProjection.valid) {
                return@locked saveInvalidState(
                    current,
                    pushedProjection.issues.joinToString("; ") { it.message },
                )
            }
            val approved = pending.copy(status = OkfChangesetStatus.APPROVED)
            stateStorage.saveApprovedChangeset(approved)
            finalizeApprovedReceipt(projectId, approved)
            return@locked sync(projectId)
        }
        val snapshot = gitClient.fetch(projectId, connection)
        val projected = projectionService.project(projectId, snapshot.files)
        if (!projected.valid) {
            return@locked saveInvalidState(current, projected.issues.joinToString("; ") { it.message })
        }

        if (pending == null) {
            stateStorage.deleteConflict(projectId)
            return@locked saveState(
                current.copy(
                    syncStatus = OkfSyncStatus.CLEAN,
                    baseCommit = snapshot.commit,
                    pendingChangesetId = null,
                    lastError = null,
                    updatedAt = now(),
                ),
            )
        }

        if (pending.baseCommit == snapshot.commit) {
            val hasConflicts = stateStorage.loadConflicts(projectId).isNotEmpty()
            return@locked saveState(
                current.copy(
                    syncStatus = if (hasConflicts) OkfSyncStatus.CONFLICT else OkfSyncStatus.PROPOSAL_PENDING,
                    baseCommit = snapshot.commit,
                    pendingChangesetId = pending.id,
                    lastError = null,
                    updatedAt = now(),
                ),
            )
        }

        val baseFiles = gitClient.readAt(projectId, connection, requireNotNull(pending.baseCommit)).files
        val proposedFiles = applyOperations(baseFiles, pending.operations)
        val merge = planner.plan(
            parseBundle(baseFiles),
            requireNotNull(projected.bundle),
            parseBundle(proposedFiles),
        )
        if (merge.conflicts.isNotEmpty()) {
            stateStorage.saveConflicts(
                projectId,
                merge.conflicts.map { it.copy(changesetId = pending.id) },
            )
            return@locked saveState(
                current.copy(
                    syncStatus = OkfSyncStatus.CONFLICT,
                    baseCommit = snapshot.commit,
                    pendingChangesetId = pending.id,
                    lastError = null,
                    updatedAt = now(),
                ),
            )
        }

        val mergedFiles = renderBundle(requireNotNull(merge.merged))
        val rebasedOperations = diffOperations(snapshot.files, mergedFiles)
        if (rebasedOperations.isEmpty()) {
            stateStorage.deletePendingChangeset(projectId)
            stateStorage.deleteConflict(projectId)
            return@locked saveState(
                current.copy(
                    syncStatus = OkfSyncStatus.CLEAN,
                    baseCommit = snapshot.commit,
                    pendingChangesetId = null,
                    lastError = null,
                    updatedAt = now(),
                ),
            )
        }
        val rebased = pending.copy(
            baseCommit = snapshot.commit,
            operations = rebasedOperations,
            updatedAt = now(),
        )
        stateStorage.savePendingChangeset(rebased)
        stateStorage.deleteConflict(projectId)
        saveState(
            current.copy(
                syncStatus = OkfSyncStatus.PROPOSAL_PENDING,
                baseCommit = snapshot.commit,
                pendingChangesetId = pending.id,
                lastError = null,
                updatedAt = now(),
            ),
        )
    }

    fun approve(projectId: String, changesetId: String): OkfChangeset = locked(projectId) {
        stateStorage.loadApprovedChangeset(projectId, changesetId)?.let {
            return@locked finalizeApprovedReceipt(projectId, it)
        }
        var pending = stateStorage.loadPendingChangeset(projectId)
            ?.takeIf { it.id == changesetId }
            ?: throw OkfChangesetNotFoundException(changesetId)
        var current = stateStorage.loadState(projectId)
        val connection = current.connection ?: throw OkfNotConnectedException(projectId)

        if (pending.approvedCommit == null) {
            val synchronized = try {
                sync(projectId)
            } catch (exception: OkfGitException) {
                savePushFailedState(current, pending, exception)
                throw OkfPushException("failed to synchronize OKF changeset ${pending.id}", exception)
            }
            when (synchronized.syncStatus) {
                OkfSyncStatus.CONFLICT -> throw OkfChangesetConflictException(changesetId)
                OkfSyncStatus.INVALID_REMOTE -> throw OkfPushException("remote OKF bundle is invalid")
                else -> Unit
            }
            val refreshed = stateStorage.loadPendingChangeset(projectId)
            if (refreshed == null) {
                val approved = pending.copy(
                    status = OkfChangesetStatus.APPROVED,
                    approvedCommit = requireNotNull(synchronized.baseCommit),
                )
                stateStorage.saveApprovedChangeset(approved)
                return@locked finalizeApprovedReceipt(projectId, approved, force = true)
            }
            pending = refreshed
            current = stateStorage.loadState(projectId)
        }

        val targetFiles = try {
            val base = gitClient.readAt(projectId, connection, requireNotNull(pending.baseCommit))
            applyOperations(base.files, pending.operations)
        } catch (exception: OkfGitException) {
            savePushFailedState(current, pending, exception)
            throw OkfPushException("failed to read base for OKF changeset ${pending.id}", exception)
        }
        val commitResult = pending.approvedCommit?.let {
            OkfGitCommitResult(it, connection.canonicalBranch)
        } ?: try {
            gitClient.commitAndPush(
                projectId = projectId,
                connection = connection,
                expectedRemoteCommit = pending.baseCommit,
                files = targetFiles,
                message = "docs(okf): approve changeset ${pending.id}",
            ).also { pushed ->
                stateStorage.savePendingChangeset(
                    pending.copy(approvedCommit = pushed.commit),
                )
            }
        } catch (exception: OkfGitException) {
            if (exception is OkfGitRemoteAdvancedException) {
                val advanced = try {
                    gitClient.fetch(projectId, connection)
                } catch (fetchException: OkfGitException) {
                    savePushFailedState(current, pending, fetchException)
                    throw OkfPushException("failed to verify concurrent OKF push", fetchException)
                }
                if (advanced.files == targetFiles) {
                    stateStorage.savePendingChangeset(
                        pending.copy(approvedCommit = advanced.commit),
                    )
                    OkfGitCommitResult(advanced.commit, advanced.branch)
                } else {
                    savePushFailedState(current, pending, exception)
                    throw OkfPushException("remote advanced with different OKF content", exception)
                }
            } else {
                savePushFailedState(current, pending, exception)
                throw OkfPushException("failed to push OKF changeset ${pending.id}", exception)
            }
        }

        val readback = try {
            gitClient.readAt(projectId, connection, commitResult.commit)
        } catch (exception: OkfGitException) {
            savePushFailedState(current, pending, exception)
            throw OkfPushException("failed to verify pushed OKF changeset ${pending.id}", exception)
        }
        if (readback.commit != commitResult.commit || readback.files != targetFiles) {
            val message = "pushed OKF changeset readback does not match approved snapshot"
            saveState(
                current.copy(
                    syncStatus = OkfSyncStatus.PUSH_FAILED,
                    pendingChangesetId = pending.id,
                    lastError = message,
                    updatedAt = now(),
                ),
            )
            throw OkfPushException(message)
        }
        val projected = projectionService.project(projectId, readback.files)
        if (!projected.valid) {
            saveInvalidState(current, projected.issues.joinToString("; ") { it.message })
            throw OkfPushException("pushed OKF changeset failed readback projection")
        }

        val approved = pending.copy(
            status = OkfChangesetStatus.APPROVED,
            approvedCommit = commitResult.commit,
        )
        stateStorage.saveApprovedChangeset(approved)
        finalizeApprovedReceipt(projectId, approved)
    }

    fun resolve(
        projectId: String,
        changesetId: String,
        resolution: OkfConflictResolution,
    ): OkfChangeset? = locked(projectId) {
        val pending = stateStorage.loadPendingChangeset(projectId)
            ?.takeIf { it.id == changesetId }
            ?: throw OkfChangesetNotFoundException(changesetId)
        val conflicts = stateStorage.loadConflicts(projectId)
            .takeIf { stored -> stored.isNotEmpty() && stored.all { it.changesetId == changesetId } }
            ?: throw OkfChangesetNotFoundException(changesetId)
        val current = stateStorage.loadState(projectId)
        val connection = current.connection ?: throw OkfNotConnectedException(projectId)
        val remote = gitClient.fetch(projectId, connection)
        val projected = projectionService.project(projectId, remote.files)
        if (!projected.valid) {
            saveInvalidState(current, projected.issues.joinToString("; ") { it.message })
            return@locked pending
        }

        val baseFiles = gitClient.readAt(projectId, connection, requireNotNull(pending.baseCommit)).files
        val refreshedPlan = planner.plan(
            parseBundle(baseFiles),
            requireNotNull(projected.bundle),
            parseBundle(applyOperations(baseFiles, pending.operations)),
        )
        val newlyDetected = refreshedPlan.conflicts
            .map { it.copy(changesetId = changesetId) }
            .filter { detected -> conflicts.none { sameConflict(it, detected) } }
        val conflict = conflicts.first()
        val remainingConflicts = conflicts.drop(1) + newlyDetected

        val proposedFiles = remote.files.toMutableMap()
        pending.operations
            .filterNot(::isGeneratedIndexOperation)
            .forEach { operation ->
                when {
                    matches(operation, conflict) && resolution == OkfConflictResolution.REMOTE -> Unit
                    matches(operation, conflict) -> {
                        conflict.documentId?.let { id ->
                            findPathByDocumentId(proposedFiles, id)?.let(proposedFiles::remove)
                        }
                        applyOperation(proposedFiles, operation)
                    }
                    remainingConflicts.any { matches(operation, it) } -> Unit
                    else -> applyOperation(proposedFiles, operation)
                }
            }

        val merge = planner.plan(
            requireNotNull(projected.bundle),
            requireNotNull(projected.bundle),
            parseBundle(proposedFiles),
        )
        if (merge.conflicts.isNotEmpty()) {
            stateStorage.saveConflicts(
                projectId,
                merge.conflicts.map { it.copy(changesetId = changesetId) } + remainingConflicts,
            )
            saveState(
                current.copy(
                    syncStatus = OkfSyncStatus.CONFLICT,
                    baseCommit = remote.commit,
                    pendingChangesetId = changesetId,
                    updatedAt = now(),
                ),
            )
            return@locked pending
        }

        val appliedOperations = diffOperations(remote.files, renderBundle(requireNotNull(merge.merged)))
        val unresolvedOperations = remainingConflicts.mapNotNull { remaining ->
            pending.operations.firstOrNull { matches(it, remaining) }
                ?.let { rebaseOperation(remote.files, it) }
        }
        val operations = (appliedOperations + unresolvedOperations)
            .distinctBy { it.documentId ?: "path:${it.path}" }
            .sortedBy { it.path }
        if (remainingConflicts.isNotEmpty()) {
            val unresolved = remainingConflicts.mapNotNull { remaining ->
                operations.firstOrNull { matches(it, remaining) }
                    ?.let { refreshConflict(changesetId, remaining, it) }
            }
            if (unresolved.isEmpty()) {
                stateStorage.deleteConflict(projectId)
            } else {
                stateStorage.saveConflicts(projectId, unresolved)
            }
            val updated = pending.copy(baseCommit = remote.commit, operations = operations, updatedAt = now())
            stateStorage.savePendingChangeset(updated)
            if (unresolved.isEmpty()) {
                return@locked finishResolvedChangeset(projectId, current, updated, remote.commit)
            }
            saveState(
                current.copy(
                    syncStatus = OkfSyncStatus.CONFLICT,
                    baseCommit = remote.commit,
                    pendingChangesetId = changesetId,
                    lastError = null,
                    updatedAt = now(),
                ),
            )
            return@locked updated
        }

        stateStorage.deleteConflict(projectId)
        if (operations.isEmpty()) {
            stateStorage.deletePendingChangeset(projectId)
            saveState(
                current.copy(
                    syncStatus = OkfSyncStatus.CLEAN,
                    baseCommit = remote.commit,
                    pendingChangesetId = null,
                    lastError = null,
                    updatedAt = now(),
                ),
            )
            return@locked null
        }

        val resolved = pending.copy(baseCommit = remote.commit, operations = operations, updatedAt = now())
        stateStorage.savePendingChangeset(resolved)
        saveState(
            current.copy(
                syncStatus = OkfSyncStatus.PROPOSAL_PENDING,
                baseCommit = remote.commit,
                pendingChangesetId = changesetId,
                lastError = null,
                updatedAt = now(),
            ),
        )
        resolved
    }

    private fun parseBundle(files: Map<String, String>): OkfBundle = OkfBundle(
        files.toSortedMap().map { (path, content) -> parser.parse(path, content) },
    )

    private fun renderBundle(bundle: OkfBundle): Map<String, String> =
        bundle.documents.associate { it.path to parser.render(it) }

    private fun applyOperations(
        base: Map<String, String>,
        operations: List<OkfFileOperation>,
    ): Map<String, String> = base.toMutableMap().also { files ->
        operations.forEach { applyOperation(files, it) }
    }

    private fun applyOperation(files: MutableMap<String, String>, operation: OkfFileOperation) {
        when (operation.type) {
            OkfOperationType.ADD, OkfOperationType.UPDATE -> files[operation.path] = requireNotNull(operation.content)
            OkfOperationType.RENAME -> {
                operation.previousPath?.let(files::remove)
                files[operation.path] = requireNotNull(operation.content)
            }
            OkfOperationType.DELETE -> files.remove(operation.previousPath ?: operation.path)
        }
    }

    private fun diffOperations(base: Map<String, String>, proposed: Map<String, String>): List<OkfFileOperation> {
        val baseDocuments = rawDocuments(base)
        val proposedDocuments = rawDocuments(proposed)
        val operations = mutableListOf<OkfFileOperation>()
        val handledBasePaths = mutableSetOf<String>()
        val handledProposedPaths = mutableSetOf<String>()
        val baseById = baseDocuments.filter { it.id != null }.associateBy { it.id!! }
        val proposedById = proposedDocuments.filter { it.id != null }.associateBy { it.id!! }

        (baseById.keys + proposedById.keys).sorted().forEach { id ->
            val before = baseById[id]
            val after = proposedById[id]
            operation(before, after, id)?.let(operations::add)
            before?.path?.let(handledBasePaths::add)
            after?.path?.let(handledProposedPaths::add)
        }
        val remainingPaths =
            (base.keys - handledBasePaths) + (proposed.keys - handledProposedPaths)
        remainingPaths.sorted().distinct().forEach { path ->
            operation(
                baseDocuments.firstOrNull { it.path == path },
                proposedDocuments.firstOrNull { it.path == path },
                null,
            )?.let(operations::add)
        }
        return operations.sortedBy { it.path }
    }

    private fun operation(before: RawDocument?, after: RawDocument?, id: String?): OkfFileOperation? {
        if (before?.path == after?.path && before?.content == after?.content) return null
        return OkfFileOperation(
            type = when {
                before == null -> OkfOperationType.ADD
                after == null -> OkfOperationType.DELETE
                before.path != after.path -> OkfOperationType.RENAME
                else -> OkfOperationType.UPDATE
            },
            path = after?.path ?: requireNotNull(before).path,
            previousPath = before?.path?.takeIf { after != null && it != after.path },
            baseContent = before?.content,
            content = after?.content,
            documentId = id,
        )
    }

    private fun rebaseOperation(
        remoteFiles: Map<String, String>,
        local: OkfFileOperation,
    ): OkfFileOperation? {
        val remote = local.documentId?.let { id ->
            rawDocuments(remoteFiles).firstOrNull { it.id == id }
        } ?: listOfNotNull(local.previousPath, local.path)
            .firstNotNullOfOrNull { path -> remoteFiles[path]?.let { RawDocument(path, it, local.documentId) } }
        val proposed = local.content?.let { RawDocument(local.path, it, local.documentId) }
        return operation(remote, proposed, local.documentId)
    }

    private fun refreshConflict(
        changesetId: String,
        previous: OkfConflict,
        operation: OkfFileOperation,
    ) = OkfConflict(
        changesetId = changesetId,
        documentId = operation.documentId ?: previous.documentId,
        path = operation.path,
        baseContent = operation.baseContent,
        remoteContent = operation.baseContent,
        proposedContent = operation.content,
    )

    private fun rawDocuments(files: Map<String, String>): List<RawDocument> = files.map { (path, content) ->
        val id = if (path.substringAfterLast('/') == "index.md") null else parser.parse(path, content).frontmatter["id"] as? String
        RawDocument(path, content, id)
    }

    private fun findPathByDocumentId(files: Map<String, String>, documentId: String): String? =
        rawDocuments(files).firstOrNull { it.id == documentId }?.path

    private fun isGeneratedIndexOperation(operation: OkfFileOperation): Boolean =
        operation.path.substringAfterLast('/') == "index.md"

    private fun matches(operation: OkfFileOperation, conflict: OkfConflict): Boolean =
        conflict.documentId?.let { it == operation.documentId }
            ?: (operation.path == conflict.path || operation.previousPath == conflict.path)

    private fun sameConflict(first: OkfConflict, second: OkfConflict): Boolean =
        if (first.documentId != null || second.documentId != null) {
            first.documentId == second.documentId
        } else {
            first.path == second.path
        }

    private fun finishResolvedChangeset(
        projectId: String,
        current: OkfProjectState,
        pending: OkfChangeset,
        remoteCommit: String,
    ): OkfChangeset? {
        if (pending.operations.isEmpty()) {
            stateStorage.deletePendingChangeset(projectId)
            saveState(
                current.copy(
                    syncStatus = OkfSyncStatus.CLEAN,
                    baseCommit = remoteCommit,
                    pendingChangesetId = null,
                    lastError = null,
                    updatedAt = now(),
                ),
            )
            return null
        }
        stateStorage.savePendingChangeset(pending)
        saveState(
            current.copy(
                syncStatus = OkfSyncStatus.PROPOSAL_PENDING,
                baseCommit = remoteCommit,
                pendingChangesetId = pending.id,
                lastError = null,
                updatedAt = now(),
            ),
        )
        return pending
    }

    private fun saveInvalidState(state: OkfProjectState, message: String): OkfProjectState = saveState(
        state.copy(syncStatus = OkfSyncStatus.INVALID_REMOTE, lastError = message, updatedAt = now()),
    )

    private fun savePushFailedState(
        state: OkfProjectState,
        pending: OkfChangeset,
        exception: OkfGitException,
    ) {
        saveState(
            state.copy(
                syncStatus = OkfSyncStatus.PUSH_FAILED,
                pendingChangesetId = pending.id,
                lastError = exception.message,
                updatedAt = now(),
            ),
        )
    }

    private fun finalizeApprovedReceipt(
        projectId: String,
        approved: OkfChangeset,
        force: Boolean = false,
    ): OkfChangeset {
        val pending = stateStorage.loadPendingChangeset(projectId)
        val current = stateStorage.loadState(projectId)
        if (!force && pending?.id != approved.id && current.pendingChangesetId != approved.id) {
            return approved
        }
        if (pending?.id == approved.id) {
            stateStorage.deletePendingChangeset(projectId)
        }
        val remainingConflicts = stateStorage.loadConflicts(projectId)
            .filterNot { it.changesetId == approved.id }
        stateStorage.saveConflicts(projectId, remainingConflicts)

        val remainingPending = stateStorage.loadPendingChangeset(projectId)
        saveState(
            current.copy(
                mode = if (approved.kind == OkfChangesetKind.MIGRATION) OkfMode.OKF_ACTIVE else current.mode,
                syncStatus = if (remainingPending == null) OkfSyncStatus.CLEAN else OkfSyncStatus.PROPOSAL_PENDING,
                baseCommit = approved.approvedCommit ?: current.baseCommit,
                pendingChangesetId = remainingPending?.id,
                lastError = null,
                updatedAt = now(),
            ),
        )
        return approved
    }

    private fun saveState(state: OkfProjectState): OkfProjectState {
        stateStorage.saveState(state)
        return state
    }

    private fun <T> locked(projectId: String, block: () -> T): T =
        OkfProjectLocks.withLock(projectId, block)

    private fun now(): String = Instant.now().toString()

    private data class RawDocument(val path: String, val content: String, val id: String?)
}

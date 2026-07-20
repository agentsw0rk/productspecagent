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
import com.agentwork.productspecagent.storage.OkfProjectionStorage
import com.agentwork.productspecagent.storage.OkfStateStorage
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.serialization.Serializable
import org.springframework.stereotype.Service

@Serializable
enum class OkfConflictResolution { REMOTE, PROPOSED }

class OkfPushException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

class OkfNotConnectedException(projectId: String) :
    RuntimeException("project is not connected to an OKF Git repository: $projectId")

class OkfChangesetNotFoundException(changesetId: String) :
    RuntimeException("OKF changeset does not exist: $changesetId")

@Service
class OkfSyncService(
    private val gitClient: OkfGitClient,
    private val stateStorage: OkfStateStorage,
    private val projectionStorage: OkfProjectionStorage,
    private val projectionService: OkfProjectionService,
    private val planner: OkfThreeWayPlanner,
    private val parser: OkfParser,
) {

    private val projectLocks = ConcurrentHashMap<String, ReentrantLock>()

    fun status(projectId: String): OkfProjectState = stateStorage.loadState(projectId)

    fun pending(projectId: String): OkfChangeset? = stateStorage.loadPendingChangeset(projectId)

    fun sync(projectId: String): OkfProjectState = locked(projectId) {
        val current = stateStorage.loadState(projectId)
        val connection = current.connection ?: throw OkfNotConnectedException(projectId)
        val pending = stateStorage.loadPendingChangeset(projectId)
        val previousRawFiles = projectionStorage.loadRawFiles(projectId)
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
            return@locked saveState(
                current.copy(
                    syncStatus = OkfSyncStatus.PROPOSAL_PENDING,
                    baseCommit = snapshot.commit,
                    pendingChangesetId = pending.id,
                    lastError = null,
                    updatedAt = now(),
                ),
            )
        }

        val baseFiles = previousRawFiles.takeIf { current.baseCommit == pending.baseCommit && it.isNotEmpty() }
            ?: gitClient.readAt(projectId, connection, requireNotNull(pending.baseCommit)).files
        val proposedFiles = applyOperations(baseFiles, pending.operations)
        val merge = planner.plan(
            parseBundle(baseFiles),
            requireNotNull(projected.bundle),
            parseBundle(proposedFiles),
        )
        if (merge.conflicts.isNotEmpty()) {
            val conflict = merge.conflicts.first().copy(changesetId = pending.id)
            stateStorage.saveConflict(projectId, conflict)
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
        stateStorage.loadApprovedChangeset(projectId, changesetId)?.let { return@locked it }
        val pending = stateStorage.loadPendingChangeset(projectId)
            ?.takeIf { it.id == changesetId }
            ?: throw OkfChangesetNotFoundException(changesetId)
        val current = stateStorage.loadState(projectId)
        val connection = current.connection ?: throw OkfNotConnectedException(projectId)

        val commitResult = pending.approvedCommit?.let {
            OkfGitCommitResult(it, connection.canonicalBranch)
        } ?: try {
            val base = gitClient.readAt(projectId, connection, requireNotNull(pending.baseCommit))
            gitClient.commitAndPush(
                projectId = projectId,
                connection = connection,
                expectedRemoteCommit = pending.baseCommit,
                files = applyOperations(base.files, pending.operations),
                message = "docs(okf): approve changeset ${pending.id}",
            ).also { pushed ->
                stateStorage.savePendingChangeset(
                    pending.copy(approvedCommit = pushed.commit, updatedAt = now()),
                )
            }
        } catch (exception: OkfGitException) {
            savePushFailedState(current, pending, exception)
            throw OkfPushException("failed to push OKF changeset ${pending.id}", exception)
        }

        val readback = try {
            gitClient.fetch(projectId, connection)
        } catch (exception: OkfGitException) {
            savePushFailedState(current, pending, exception)
            throw OkfPushException("failed to verify pushed OKF changeset ${pending.id}", exception)
        }
        val projected = projectionService.project(projectId, readback.files)
        if (!projected.valid) {
            saveInvalidState(current, projected.issues.joinToString("; ") { it.message })
            throw OkfPushException("pushed OKF changeset failed readback projection")
        }

        val approved = pending.copy(
            status = OkfChangesetStatus.APPROVED,
            approvedCommit = commitResult.commit,
            updatedAt = now(),
        )
        stateStorage.saveApprovedChangeset(approved)
        stateStorage.deletePendingChangeset(projectId)
        stateStorage.deleteConflict(projectId)
        saveState(
            current.copy(
                mode = if (pending.kind == OkfChangesetKind.MIGRATION) OkfMode.OKF_ACTIVE else current.mode,
                syncStatus = OkfSyncStatus.CLEAN,
                baseCommit = readback.commit,
                pendingChangesetId = null,
                lastError = null,
                updatedAt = now(),
            ),
        )
        approved
    }

    fun resolve(
        projectId: String,
        changesetId: String,
        resolution: OkfConflictResolution,
    ): OkfChangeset? = locked(projectId) {
        val pending = stateStorage.loadPendingChangeset(projectId)
            ?.takeIf { it.id == changesetId }
            ?: throw OkfChangesetNotFoundException(changesetId)
        val conflict = stateStorage.loadConflict(projectId)
            ?.takeIf { it.changesetId == changesetId }
            ?: throw OkfChangesetNotFoundException(changesetId)
        val current = stateStorage.loadState(projectId)
        val connection = current.connection ?: throw OkfNotConnectedException(projectId)
        val remote = gitClient.fetch(projectId, connection)
        val projected = projectionService.project(projectId, remote.files)
        if (!projected.valid) {
            saveInvalidState(current, projected.issues.joinToString("; ") { it.message })
            return@locked pending
        }

        val proposedFiles = remote.files.toMutableMap()
        pending.operations
            .filterNot(::isGeneratedIndexOperation)
            .filter { operation -> resolution == OkfConflictResolution.PROPOSED || !matches(operation, conflict) }
            .forEach { operation ->
                if (resolution == OkfConflictResolution.PROPOSED && matches(operation, conflict)) {
                    conflict.documentId?.let { id -> findPathByDocumentId(proposedFiles, id)?.let(proposedFiles::remove) }
                }
                applyOperation(proposedFiles, operation)
            }

        val merge = planner.plan(
            requireNotNull(projected.bundle),
            requireNotNull(projected.bundle),
            parseBundle(proposedFiles),
        )
        if (merge.conflicts.isNotEmpty()) {
            stateStorage.saveConflict(projectId, merge.conflicts.first().copy(changesetId = changesetId))
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

        val operations = diffOperations(remote.files, renderBundle(requireNotNull(merge.merged)))
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

    private fun saveState(state: OkfProjectState): OkfProjectState {
        stateStorage.saveState(state)
        return state
    }

    private fun <T> locked(projectId: String, block: () -> T): T =
        projectLocks.computeIfAbsent(projectId) { ReentrantLock() }.withLock(block)

    private fun now(): String = Instant.now().toString()

    private data class RawDocument(val path: String, val content: String, val id: String?)
}

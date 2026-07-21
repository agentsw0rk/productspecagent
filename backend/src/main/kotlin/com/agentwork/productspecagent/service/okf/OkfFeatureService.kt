package com.agentwork.productspecagent.service.okf

import com.agentwork.productspecagent.domain.GraphPosition
import com.agentwork.productspecagent.domain.WizardFeature
import com.agentwork.productspecagent.domain.WizardFeatureGraph
import com.agentwork.productspecagent.domain.okf.OkfBundle
import com.agentwork.productspecagent.domain.okf.OkfChangeset
import com.agentwork.productspecagent.domain.okf.OkfChangesetKind
import com.agentwork.productspecagent.domain.okf.OkfFileOperation
import com.agentwork.productspecagent.domain.okf.OkfIssueSeverity
import com.agentwork.productspecagent.domain.okf.OkfMode
import com.agentwork.productspecagent.domain.okf.OkfOperationType
import com.agentwork.productspecagent.domain.okf.OkfSyncStatus
import com.agentwork.productspecagent.storage.OkfPresentationStorage
import com.agentwork.productspecagent.storage.OkfProjectionStorage
import com.agentwork.productspecagent.storage.OkfStateStorage
import java.time.Clock
import java.time.OffsetDateTime
import java.util.UUID
import org.springframework.stereotype.Service

class OkfFeatureProposalException(message: String) : RuntimeException(message)

@Service
class OkfFeatureService(
    private val stateStorage: OkfStateStorage,
    private val projectionStorage: OkfProjectionStorage,
    private val presentationStorage: OkfPresentationStorage,
    private val parser: OkfParser,
    private val codec: OkfFeatureCodec,
    private val validator: OkfProfileValidator,
    private val clock: Clock = Clock.systemUTC(),
) {

    fun isActive(projectId: String): Boolean = stateStorage.loadState(projectId).mode == OkfMode.OKF_ACTIVE

    fun hasPending(projectId: String): Boolean = stateStorage.loadPendingChangeset(projectId) != null

    fun graph(projectId: String): WizardFeatureGraph = OkfProjectLocks.withLock(projectId) {
        requireActive(projectId)
        val confirmedFiles = projectionStorage.loadRawFiles(projectId)
        val pending = stateStorage.loadPendingChangeset(projectId)
        val files = if (pending == null) confirmedFiles else applyOperations(confirmedFiles, pending.operations)
        val bundle = parseBundle(files)
        val decoded = codec.decode(bundle, presentationStorage.loadPositions(projectId))
        val errors = decoded.issues.filter { it.severity == OkfIssueSeverity.ERROR }
        if (errors.isNotEmpty()) {
            throw OkfFeatureProposalException(errors.joinToString("; ") { it.message })
        }
        decoded.graph
    }

    fun propose(projectId: String, graph: WizardFeatureGraph): OkfChangeset? =
        OkfProjectLocks.withLock(projectId) {
            val state = requireActive(projectId)
            val positions = canonicalPositions(graph.features)
            val current = graph(projectId)
            if (semanticGraph(current) == semanticGraph(graph)) {
                presentationStorage.savePositions(projectId, positions)
                return@withLock null
            }

            val confirmedFiles = projectionStorage.loadRawFiles(projectId)
            val confirmedBundle = parseBundle(confirmedFiles)
            val timestamp = OffsetDateTime.now(clock)
            val proposedBundle = codec.encode(semanticGraph(graph), confirmedBundle, timestamp)
            val issues = validator.validate(proposedBundle)
            val errors = issues.filter { it.severity == OkfIssueSeverity.ERROR }
            if (errors.isNotEmpty()) {
                throw OkfFeatureProposalException(errors.joinToString("; ") { it.message })
            }
            presentationStorage.savePositions(projectId, positions)
            val operations = diff(confirmedBundle, proposedBundle)
            val existing = stateStorage.loadPendingChangeset(projectId)
            val now = clock.instant().toString()
            if (operations.isEmpty()) {
                stateStorage.deletePendingChangeset(projectId)
                val remainingConflicts = existing?.let { pending ->
                    stateStorage.loadConflicts(projectId).filterNot { it.changesetId == pending.id }
                }.orEmpty()
                stateStorage.saveConflicts(projectId, remainingConflicts)
                stateStorage.saveState(
                    state.copy(
                        syncStatus = if (remainingConflicts.isEmpty()) OkfSyncStatus.CLEAN else OkfSyncStatus.CONFLICT,
                        pendingChangesetId = null,
                        lastError = null,
                        updatedAt = now,
                    ),
                )
                return@withLock null
            }
            val changeset = OkfChangeset(
                id = existing?.takeIf { it.kind == OkfChangesetKind.EDIT }?.id ?: UUID.randomUUID().toString(),
                projectId = projectId,
                kind = OkfChangesetKind.EDIT,
                baseCommit = state.baseCommit
                    ?: throw OkfFeatureProposalException("active OKF project has no confirmed base commit: $projectId"),
                operations = operations,
                issues = issues,
                createdAt = existing?.takeIf { it.kind == OkfChangesetKind.EDIT }?.createdAt ?: now,
                updatedAt = now,
            )
            stateStorage.savePendingChangeset(changeset)
            stateStorage.saveState(
                state.copy(
                    syncStatus = OkfSyncStatus.PROPOSAL_PENDING,
                    pendingChangesetId = changeset.id,
                    lastError = null,
                    updatedAt = now,
                ),
            )
            changeset
        }

    private fun requireActive(projectId: String) = stateStorage.loadState(projectId).also { state ->
        if (state.mode != OkfMode.OKF_ACTIVE) {
            throw OkfFeatureProposalException("project is not using OKF: $projectId")
        }
    }

    private fun semanticGraph(graph: WizardFeatureGraph): WizardFeatureGraph = graph.copy(
        features = graph.features.map { feature ->
            feature.copy(
                id = canonicalUuid(feature.id) ?: feature.id,
                acceptanceCriteria = feature.acceptanceCriteria.map { criterion ->
                    criterion.copy(id = canonicalUuid(criterion.id) ?: criterion.id)
                },
                position = GraphPosition(),
            )
        },
        edges = graph.edges.map { edge ->
            edge.copy(
                id = canonicalUuid(edge.id) ?: edge.id,
                from = canonicalUuid(edge.from) ?: edge.from,
                to = canonicalUuid(edge.to) ?: edge.to,
            )
        },
    )

    private fun canonicalPositions(features: List<WizardFeature>): Map<String, GraphPosition> {
        val positions = linkedMapOf<String, GraphPosition>()
        features.forEach { feature ->
            val id = canonicalUuid(feature.id)
                ?: throw OkfFeatureProposalException("feature id must be a canonical UUID: ${feature.id}")
            if (positions.put(id, feature.position) != null) {
                throw OkfFeatureProposalException("duplicate feature id: ${feature.id}")
            }
        }
        return positions
    }

    private fun canonicalUuid(value: String): String? = try {
        UUID.fromString(value).toString().takeIf { it.equals(value, ignoreCase = true) }
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun parseBundle(files: Map<String, String>): OkfBundle = try {
        OkfBundle(files.toSortedMap().map { (path, content) -> parser.parse(path, content) })
    } catch (exception: Exception) {
        throw OkfFeatureProposalException(exception.message ?: "invalid confirmed OKF bundle")
    }

    private fun applyOperations(
        base: Map<String, String>,
        operations: List<OkfFileOperation>,
    ): Map<String, String> = base.toMutableMap().apply {
        operations.forEach { operation ->
            operation.previousPath?.let(::remove)
            when (operation.type) {
                OkfOperationType.DELETE -> remove(operation.path)
                OkfOperationType.ADD,
                OkfOperationType.UPDATE,
                OkfOperationType.RENAME,
                -> this[operation.path] = operation.content
                    ?: throw OkfFeatureProposalException("${operation.type} operation has no content: ${operation.path}")
            }
        }
    }

    private fun diff(base: OkfBundle, target: OkfBundle): List<OkfFileOperation> {
        val baseByIdentity = base.documents.associateBy(::identity)
        val targetByIdentity = target.documents.associateBy(::identity)
        return (baseByIdentity.keys + targetByIdentity.keys).sorted().mapNotNull { identity ->
            val before = baseByIdentity[identity]
            val after = targetByIdentity[identity]
            val beforeContent = before?.let(parser::render)
            val afterContent = after?.let(parser::render)
            if (before?.path == after?.path && beforeContent == afterContent) return@mapNotNull null
            OkfFileOperation(
                type = when {
                    before == null -> OkfOperationType.ADD
                    after == null -> OkfOperationType.DELETE
                    before.path != after.path -> OkfOperationType.RENAME
                    else -> OkfOperationType.UPDATE
                },
                path = after?.path ?: requireNotNull(before).path,
                previousPath = before?.path?.takeIf { after != null && it != after.path },
                baseContent = beforeContent,
                content = afterContent,
                documentId = (after ?: before)?.frontmatter?.get("id") as? String,
            )
        }
    }

    private fun identity(document: com.agentwork.productspecagent.domain.okf.OkfDocument): String =
        (document.frontmatter["id"] as? String)?.let { "id:$it" } ?: "path:${document.path}"
}

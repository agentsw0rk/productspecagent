package com.agentwork.productspecagent.service.okf

import com.agentwork.productspecagent.domain.WizardFeature
import com.agentwork.productspecagent.domain.WizardFeatureEdge
import com.agentwork.productspecagent.domain.WizardFeatureGraph
import com.agentwork.productspecagent.domain.WizardData
import com.agentwork.productspecagent.domain.okf.OkfBundle
import com.agentwork.productspecagent.domain.okf.OkfChangeset
import com.agentwork.productspecagent.domain.okf.OkfChangesetKind
import com.agentwork.productspecagent.domain.okf.OkfFileOperation
import com.agentwork.productspecagent.domain.okf.OkfIssue
import com.agentwork.productspecagent.domain.okf.OkfIssueSeverity
import com.agentwork.productspecagent.domain.okf.OkfMigrationDocument
import com.agentwork.productspecagent.domain.okf.OkfMigrationPreview
import com.agentwork.productspecagent.domain.okf.OkfMode
import com.agentwork.productspecagent.domain.okf.OkfOperationType
import com.agentwork.productspecagent.domain.okf.OkfSyncStatus
import com.agentwork.productspecagent.service.WizardService
import com.agentwork.productspecagent.storage.OkfStateStorage
import com.agentwork.productspecagent.storage.ProjectStorage
import java.nio.charset.StandardCharsets
import java.text.Normalizer
import java.time.Clock
import java.time.OffsetDateTime
import java.util.Locale
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import org.springframework.stereotype.Service

class OkfMigrationException(message: String) : RuntimeException(message)

@Service
class OkfMigrationService(
    private val wizardService: WizardService,
    private val gitClient: OkfGitClient,
    private val stateStorage: OkfStateStorage,
    private val projectStorage: ProjectStorage,
    private val featureCodec: OkfFeatureCodec,
    private val parser: OkfParser,
    private val profileValidator: OkfProfileValidator,
    private val clock: Clock = Clock.systemUTC(),
) {

    private val json = Json { ignoreUnknownKeys = true }

    fun dryRun(projectId: String): OkfMigrationPreview {
        val state = stateStorage.loadState(projectId)
        if (state.mode == OkfMode.OKF_ACTIVE) {
            throw OkfMigrationException("project is already using OKF: $projectId")
        }
        val connection = state.connection ?: throw OkfNotConnectedException(projectId)
        val snapshot = gitClient.fetch(projectId, connection)
        val issues = mutableListOf<OkfIssue>()
        val existing = parseBundle(snapshot.files, issues)
        val wizardData = wizardService.getWizardData(projectId)
        val sourceGraph = readFeatureGraph(wizardData, issues)
        val migrated = migrateGraph(projectId, sourceGraph, issues)
        val encoded = encodeBundle(
            migrated.graph,
            existing,
            migrated.legacyFeatureIds,
            migrationTimestamp(projectId, wizardData, issues),
            issues,
        )
        val operations = encoded?.let { diff(existing, it) }.orEmpty()
        val documentsById = encoded?.documents
            ?.mapNotNull { document -> (document.frontmatter["id"] as? String)?.let { it to document.path } }
            ?.toMap()
            .orEmpty()
        val documents = migrated.features.mapNotNull { feature ->
            documentsById[feature.documentId]?.let { path -> feature.copy(path = path) }
        }.sortedBy { it.path }

        return OkfMigrationPreview(
            projectId = projectId,
            baseCommit = snapshot.commit,
            documents = documents,
            operations = operations,
            issues = issues.distinct().sortedWith(compareBy({ it.path }, { it.field ?: "" }, { it.message })),
        )
    }

    fun prepare(projectId: String): OkfChangeset = OkfProjectLocks.withLock(projectId) {
        val preview = dryRun(projectId)
        val errors = preview.issues.filter { it.severity == OkfIssueSeverity.ERROR }
        if (errors.isNotEmpty()) {
            throw OkfMigrationException(errors.joinToString("; ") { it.message })
        }

        val state = stateStorage.loadState(projectId)
        val existing = stateStorage.loadPendingChangeset(projectId)
        if (existing != null) {
            if (
                existing.kind == OkfChangesetKind.MIGRATION &&
                existing.baseCommit == preview.baseCommit &&
                existing.operations == preview.operations &&
                existing.issues == preview.issues
            ) {
                stateStorage.saveState(
                    state.copy(
                        mode = OkfMode.MIGRATION_READY,
                        syncStatus = OkfSyncStatus.PROPOSAL_PENDING,
                        baseCommit = preview.baseCommit,
                        pendingChangesetId = existing.id,
                        lastError = null,
                        updatedAt = clock.instant().toString(),
                    ),
                )
                return@withLock existing
            }
            throw OkfPendingChangesException(projectId)
        }

        val timestamp = clock.instant().toString()
        val changeset = OkfChangeset(
            id = migrationChangesetId(projectId, preview),
            projectId = projectId,
            kind = OkfChangesetKind.MIGRATION,
            baseCommit = preview.baseCommit,
            operations = preview.operations,
            issues = preview.issues,
            createdAt = timestamp,
            updatedAt = timestamp,
        )
        stateStorage.savePendingChangeset(changeset)
        stateStorage.saveState(
            state.copy(
                mode = OkfMode.MIGRATION_READY,
                syncStatus = OkfSyncStatus.PROPOSAL_PENDING,
                baseCommit = preview.baseCommit,
                pendingChangesetId = changeset.id,
                lastError = null,
                updatedAt = timestamp,
            ),
        )
        changeset
    }

    private fun readFeatureGraph(wizardData: WizardData, issues: MutableList<OkfIssue>): WizardFeatureGraph {
        val fields = wizardData.steps[FEATURES_STEP]?.fields.orEmpty()
        return try {
            json.decodeFromJsonElement(
                JsonObject(
                    mapOf(
                        "features" to (fields["features"] ?: JsonArray(emptyList())),
                        "edges" to (fields["edges"] ?: JsonArray(emptyList())),
                    ),
                ),
            )
        } catch (exception: Exception) {
            issues += error("wizard/FEATURES", "features", exception.message ?: "invalid feature graph")
            WizardFeatureGraph()
        }
    }

    private fun migrationTimestamp(
        projectId: String,
        wizardData: WizardData,
        issues: MutableList<OkfIssue>,
    ): OffsetDateTime {
        val candidates = listOfNotNull(
            wizardData.steps[FEATURES_STEP]?.completedAt,
            projectStorage.loadProject(projectId)?.updatedAt,
        )
        candidates.forEach { candidate ->
            try {
                return OffsetDateTime.parse(candidate)
            } catch (_: Exception) {
                issues += OkfIssue(
                    OkfIssueSeverity.WARNING,
                    "wizard/FEATURES",
                    "timestamp",
                    "ignored invalid migration source timestamp '$candidate'",
                )
            }
        }
        throw OkfMigrationException("project has no valid timestamp for OKF migration: $projectId")
    }

    private fun migrateGraph(
        projectId: String,
        source: WizardFeatureGraph,
        issues: MutableList<OkfIssue>,
    ): MigratedGraph {
        val featureIds = linkedMapOf<String, String>()
        val legacyFeatureIds = linkedMapOf<String, String>()
        val documents = mutableListOf<OkfMigrationDocument>()
        val features = source.features.map { feature ->
            val migratedId = migratedId(projectId, "feature", feature.id)
            if (featureIds.put(referenceKey(feature.id), migratedId) != null) {
                issues += error("wizard/FEATURES", "id", "duplicate feature id '${feature.id}'")
            }
            val normalizations = mutableListOf<String>()
            if (migratedId != canonicalUuid(feature.id)) {
                normalizations += "id"
                legacyFeatureIds[migratedId] = feature.id
            }
            if (feature.description.isBlank()) {
                issues += error("wizard/FEATURES/${feature.id}", "description", "feature description must not be empty")
            }
            val criteria = feature.acceptanceCriteria.map { criterion ->
                val criterionId = migratedId(projectId, "criterion", criterion.id)
                if (criterionId != canonicalUuid(criterion.id)) normalizations += "acceptanceCriteria"
                criterion.copy(id = criterionId)
            }
            documents += OkfMigrationDocument(feature.id, migratedId, "", normalizations.distinct())
            feature.copy(id = migratedId, acceptanceCriteria = criteria)
        }

        reportSlugCollisions(features, issues)
        val edges = source.edges.map { edge ->
            val from = featureIds[referenceKey(edge.from)]
            val to = featureIds[referenceKey(edge.to)]
            if (from == null) issues += error("wizard/FEATURES", "edges", "edge '${edge.id}' source does not exist: ${edge.from}")
            if (to == null) issues += error("wizard/FEATURES", "edges", "edge '${edge.id}' target does not exist: ${edge.to}")
            WizardFeatureEdge(
                id = migratedId(projectId, "edge", edge.id),
                from = from ?: migratedId(projectId, "feature", edge.from),
                to = to ?: migratedId(projectId, "feature", edge.to),
            )
        }
        return MigratedGraph(WizardFeatureGraph(features, edges), documents, legacyFeatureIds)
    }

    private fun parseBundle(files: Map<String, String>, issues: MutableList<OkfIssue>): OkfBundle {
        val documents = files.toSortedMap().mapNotNull { (path, content) ->
            try {
                parser.parse(path, content)
            } catch (exception: Exception) {
                issues += error(path, null, exception.message ?: "invalid OKF document")
                null
            }
        }
        return OkfBundle(documents)
    }

    private fun encodeBundle(
        graph: WizardFeatureGraph,
        existing: OkfBundle,
        legacyFeatureIds: Map<String, String>,
        timestamp: OffsetDateTime,
        issues: MutableList<OkfIssue>,
    ): OkfBundle? = try {
        val encoded = featureCodec.encode(graph, existing, timestamp)
        val withLegacyIds = OkfBundle(encoded.documents.map { document ->
            val documentId = document.frontmatter["id"] as? String
            val legacyId = documentId?.let(legacyFeatureIds::get)
            if (legacyId == null) document else document.copy(
                frontmatter = LinkedHashMap(document.frontmatter).apply {
                    val existingIds = (this["legacy_ids"] as? List<*>)?.filterIsInstance<String>().orEmpty()
                    this["legacy_ids"] = (existingIds + legacyId).distinct()
                },
            )
        })
        issues += profileValidator.validate(withLegacyIds)
        withLegacyIds
    } catch (exception: Exception) {
        issues += error("wizard/FEATURES", null, exception.message ?: "feature graph cannot be encoded")
        null
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

    private fun reportSlugCollisions(features: List<WizardFeature>, issues: MutableList<OkfIssue>) {
        features.groupBy { slug(it.title) }.filterValues { it.size > 1 }.forEach { (slug, matches) ->
            issues += OkfIssue(
                OkfIssueSeverity.WARNING,
                "features/$slug.md",
                "path",
                "feature titles share slug '$slug': ${matches.map { it.title }.sorted().joinToString()}",
            )
        }
    }

    private fun identity(document: com.agentwork.productspecagent.domain.okf.OkfDocument): String =
        (document.frontmatter["id"] as? String)?.let { "id:$it" } ?: "path:${document.path}"

    private fun migratedId(projectId: String, kind: String, sourceId: String): String =
        canonicalUuid(sourceId) ?: UUID.nameUUIDFromBytes(
            "productspecagent:$projectId:$kind:$sourceId".toByteArray(StandardCharsets.UTF_8),
        ).toString()

    private fun canonicalUuid(value: String): String? = try {
        UUID.fromString(value).toString().takeIf { it.equals(value, ignoreCase = true) }
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun referenceKey(value: String): String = canonicalUuid(value) ?: value

    private fun migrationChangesetId(projectId: String, preview: OkfMigrationPreview): String =
        UUID.nameUUIDFromBytes(
            "productspecagent:$projectId:migration:${preview.baseCommit}:${preview.operations}".toByteArray(StandardCharsets.UTF_8),
        ).toString()

    private fun slug(title: String): String {
        val normalized = Normalizer.normalize(
            title.lowercase(Locale.ROOT).replace("ß", "ss"),
            Normalizer.Form.NFD,
        ).replace(Regex("\\p{M}+"), "")
        return normalized.replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { "feature" }
    }

    private fun error(path: String, field: String?, message: String) =
        OkfIssue(OkfIssueSeverity.ERROR, path, field, message)

    private data class MigratedGraph(
        val graph: WizardFeatureGraph,
        val features: List<OkfMigrationDocument>,
        val legacyFeatureIds: Map<String, String>,
    )

    private companion object {
        const val FEATURES_STEP = "FEATURES"
    }
}

package com.agentwork.productspecagent.service

import com.agentwork.productspecagent.domain.*
import com.agentwork.productspecagent.domain.WizardData
import com.agentwork.productspecagent.domain.WizardStepData
import com.agentwork.productspecagent.domain.okf.OkfMode
import com.agentwork.productspecagent.export.DocsScaffoldGenerator
import com.agentwork.productspecagent.export.ScaffoldContext
import com.agentwork.productspecagent.export.ScaffoldContextBuilder
import com.agentwork.productspecagent.infrastructure.graphmesh.GraphMeshConfig
import com.agentwork.productspecagent.storage.ProjectStorage
import com.agentwork.productspecagent.storage.OkfStateStorage
import com.agentwork.productspecagent.service.okf.OkfProjectLocks
import kotlinx.serialization.json.JsonPrimitive
import org.springframework.context.annotation.Lazy
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.UUID

class ProjectNotFoundException(id: String) : RuntimeException("Project not found: $id")
class GraphMeshDisabledException : RuntimeException("GraphMesh is disabled in backend config")

private val deprecatedScaffoldDocs = setOf(
    "docs/architecture/overview.md",
    "docs/backend/api.md",
    "docs/frontend/design.md",
)

@Service
class ProjectService(
    private val storage: ProjectStorage,
    private val scaffoldGenerator: DocsScaffoldGenerator? = null,
    @Lazy private val scaffoldContextBuilder: ScaffoldContextBuilder? = null,
    private val graphMeshConfig: GraphMeshConfig = GraphMeshConfig(enabled = false, url = "", requestTimeout = java.time.Duration.ofSeconds(30)),
    private val okfStateStorage: OkfStateStorage? = null,
) {

    fun createProject(name: String): ProjectResponse {
        val now = Instant.now().toString()
        val project = Project(
            id = UUID.randomUUID().toString(),
            name = name,
            ownerId = "anonymous",
            status = ProjectStatus.DRAFT,
            createdAt = now,
            updatedAt = now
        )
        val flowState = createInitialFlowState(project.id)

        storage.saveProject(project)
        storage.saveFlowState(flowState)

        // Prefill wizard IDEA.productName so the user doesn't re-type the name.
        val initialWizard = WizardData(
            projectId = project.id,
            steps = mapOf(
                "IDEA" to WizardStepData(
                    fields = mapOf("productName" to JsonPrimitive(name))
                )
            )
        )
        storage.saveWizardData(project.id, initialWizard)

        // Generate initial docs scaffold
        generateDocsScaffold(project.id, name)

        return ProjectResponse(project = project, flowState = flowState)
    }

    fun regenerateDocsScaffold(projectId: String) {
        val project = storage.loadProject(projectId) ?: throw ProjectNotFoundException(projectId)
        generateDocsScaffold(projectId, project.name)
    }

    private fun generateDocsScaffold(projectId: String, projectName: String) {
        OkfProjectLocks.withLock(projectId) {
            if (okfStateStorage?.loadState(projectId)?.mode == OkfMode.OKF_ACTIVE) {
                storage.deleteLegacyFeatureDocs(projectId)
                return@withLock
            }
            val generator = scaffoldGenerator ?: return@withLock
            val context = if (scaffoldContextBuilder != null) {
                scaffoldContextBuilder.build(projectId)
            } else {
                ScaffoldContext(
                    projectName = projectName,
                    features = emptyList(),
                    techStack = "See SPEC.md for tech stack details.",
                )
            }
            val entries = generator.generate(context)

            // Diff-sync: drop ORPHANED files only in subdirectories the generator actually
            // manages. Critical: docs/clarifications/, docs/decisions/, docs/tasks/, and
            // docs/uploads/ also live under projects/{id}/docs/ but are owned by other
            // storages — they must never be touched here.
            val desired = entries.keys
            val managedDirs = desired.map { it.substringBeforeLast('/') + "/" }.toSet()
            val onDisk = storage.listDocsFiles(projectId).map { it.first }
            for (path in onDisk) {
                if (path in deprecatedScaffoldDocs) {
                    storage.deleteDocsFile(projectId, path)
                    continue
                }
                if (path in desired) continue
                if (managedDirs.any { path.startsWith(it) }) {
                    storage.deleteDocsFile(projectId, path)
                }
            }

            for ((path, content) in entries) {
                storage.saveDocsFile(projectId, path, content)
            }
        }
    }

    fun getProject(id: String): ProjectResponse {
        val project = storage.loadProject(id) ?: throw ProjectNotFoundException(id)
        val flowState = storage.loadFlowState(id) ?: throw ProjectNotFoundException(id)
        return ProjectResponse(project = project, flowState = flowState)
    }

    fun deleteProject(id: String) {
        storage.loadProject(id) ?: throw ProjectNotFoundException(id)
        storage.deleteProject(id)
    }

    fun listProjects(): List<Project> = storage.listProjects()

    fun getFlowState(id: String): FlowState {
        storage.loadProject(id) ?: throw ProjectNotFoundException(id)
        return storage.loadFlowState(id) ?: throw ProjectNotFoundException(id)
    }

    fun updateFlowState(id: String, flowState: FlowState) {
        storage.loadProject(id) ?: throw ProjectNotFoundException(id)
        storage.saveFlowState(flowState)
    }

    /**
     * Marks [step] as COMPLETED, advances currentStep to the next one, and returns the next
     * FlowStepType (or null if [step] is the last). Requires a pre-existing FlowState —
     * throws [ProjectNotFoundException] if the project does not exist.
     */
    fun advanceStep(projectId: String, step: FlowStepType): FlowStepType? {
        val flowState = storage.loadFlowState(projectId) ?: throw ProjectNotFoundException(projectId)
        val stepOrder = FlowStepType.entries.toList()
        val now = Instant.now().toString()
        val currentIndex = stepOrder.indexOf(step)
        val nextStepType = if (currentIndex + 1 < stepOrder.size) stepOrder[currentIndex + 1] else null
        val updatedSteps = flowState.steps.map { s ->
            when (s.stepType) {
                step -> s.copy(status = FlowStepStatus.COMPLETED, updatedAt = now)
                nextStepType -> s.copy(status = FlowStepStatus.IN_PROGRESS, updatedAt = now)
                else -> s
            }
        }
        storage.saveFlowState(flowState.copy(steps = updatedSteps, currentStep = nextStepType ?: step))
        return nextStepType
    }

    fun saveSpecFile(projectId: String, fileName: String, content: String) {
        storage.loadProject(projectId) ?: throw ProjectNotFoundException(projectId)
        storage.saveSpecStep(projectId, fileName, content)
        if (fileName == "spec.md") {
            storage.listSpecFiles(projectId)
                .map { it.first.removePrefix("spec/") }
                .filter { it != "spec.md" }
                .forEach { storage.deleteSpecStep(projectId, it) }
        }
        regenerateDocsScaffold(projectId)
    }

    fun readSpecFile(projectId: String, fileName: String): String? {
        return storage.loadSpecStep(projectId, fileName)
    }

    fun listSpecFiles(projectId: String): List<Pair<String, ByteArray>> {
        storage.loadProject(projectId) ?: throw ProjectNotFoundException(projectId)
        return storage.listSpecFiles(projectId)
    }

    fun listDocsFiles(projectId: String): List<Pair<String, ByteArray>> {
        storage.loadProject(projectId) ?: throw ProjectNotFoundException(projectId)
        return storage.listDocsFiles(projectId)
    }

    fun setGraphMeshEnabled(projectId: String, enabled: Boolean): Project {
        // Validate against backend config first; flipping ON while backend is OFF is rejected
        // even for unknown projectIds (consistent 409 signal — client should not retry).
        if (enabled && !graphMeshConfig.enabled) {
            throw GraphMeshDisabledException()
        }
        val project = storage.loadProject(projectId) ?: throw ProjectNotFoundException(projectId)
        val updated = project.copy(graphmeshEnabled = enabled, updatedAt = Instant.now().toString())
        storage.saveProject(updated)
        return updated
    }
}

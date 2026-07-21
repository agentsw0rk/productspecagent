package com.agentwork.productspecagent.storage

import com.agentwork.productspecagent.domain.FlowState
import com.agentwork.productspecagent.domain.FlowStep
import com.agentwork.productspecagent.domain.FlowStepStatus
import com.agentwork.productspecagent.domain.FlowStepType
import com.agentwork.productspecagent.domain.Project
import com.agentwork.productspecagent.domain.WizardData
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.springframework.stereotype.Service

@Service
class ProjectStorage(private val objectStore: ObjectStore) {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    private fun projectPrefix(id: String) = "projects/$id/"
    private fun projectKey(id: String) = "projects/$id/project.json"
    private fun flowStateKey(id: String) = "projects/$id/flow-state.json"
    private fun wizardKey(id: String) = "projects/$id/wizard.json"
    private fun specKey(id: String, fileName: String) = "projects/$id/spec/$fileName"
    private fun specPrefix(id: String) = "projects/$id/spec/"
    private fun docsKey(id: String, relativePath: String) = "projects/$id/$relativePath"
    private fun docsPrefix(id: String) = "projects/$id/docs/"

    fun saveProject(project: Project) {
        objectStore.put(
            projectKey(project.id),
            json.encodeToString(project).toByteArray(),
            "application/json"
        )
    }

    fun loadProject(projectId: String): Project? =
        objectStore.get(projectKey(projectId))
            ?.toString(Charsets.UTF_8)
            ?.let { json.decodeFromString<Project>(it) }

    fun deleteProject(projectId: String) {
        objectStore.deletePrefix(projectPrefix(projectId))
    }

    fun listProjects(): List<Project> =
        objectStore.listCommonPrefixes("projects/", "/")
            .mapNotNull { id -> loadProject(id) }

    fun saveFlowState(flowState: FlowState) {
        objectStore.put(
            flowStateKey(flowState.projectId),
            json.encodeToString(flowState).toByteArray(),
            "application/json"
        )
    }

    fun loadFlowState(projectId: String): FlowState? =
        objectStore.get(flowStateKey(projectId))
            ?.toString(Charsets.UTF_8)
            ?.let { json.decodeFromString<FlowState>(it) }
            ?.let { fillMissingSteps(it) }

    private fun fillMissingSteps(raw: FlowState): FlowState {
        val knownTypes = raw.steps.map { it.stepType }.toSet()
        val missing = FlowStepType.entries
            .filter { it !in knownTypes }
        if (missing.isEmpty()) return raw
        // Reuse the first existing step's timestamp so repeated loads produce identical results.
        val migrationTimestamp = raw.steps.firstOrNull()?.updatedAt ?: java.time.Instant.EPOCH.toString()
        return raw.copy(steps = raw.steps + missing.map {
            FlowStep(stepType = it, status = FlowStepStatus.OPEN, updatedAt = migrationTimestamp)
        })
    }

    fun saveSpecStep(projectId: String, fileName: String, content: String) {
        objectStore.put(specKey(projectId, fileName), content.toByteArray(), "text/markdown")
    }

    fun deleteSpecStep(projectId: String, fileName: String) {
        objectStore.delete(specKey(projectId, fileName))
    }

    fun loadSpecStep(projectId: String, fileName: String): String? =
        objectStore.get(specKey(projectId, fileName))?.toString(Charsets.UTF_8)

    fun listSpecFiles(projectId: String): List<Pair<String, ByteArray>> {
        val specPrefix = specPrefix(projectId)
        val projectPrefix = projectPrefix(projectId)
        return objectStore.listKeys(specPrefix)
            .map { key ->
                val rel = key.removePrefix(projectPrefix)
                val bytes = objectStore.get(key) ?: ByteArray(0)
                rel to bytes
            }
    }

    fun saveDocsFile(projectId: String, relativePath: String, content: String) {
        objectStore.put(docsKey(projectId, relativePath), content.toByteArray())
    }

    fun deleteDocsFile(projectId: String, relativePath: String) {
        objectStore.delete(docsKey(projectId, relativePath))
    }

    fun deleteLegacyFeatureDocs(projectId: String) {
        objectStore.deletePrefix("${projectPrefix(projectId)}docs/features/")
    }

    /** Returns every doc file as `(relativePath, bytes)` pairs. Excludes `.index.json` (UploadStorage internals). */
    fun listDocsFiles(projectId: String): List<Pair<String, ByteArray>> {
        val docsPrefix = docsPrefix(projectId)
        val projectPrefix = projectPrefix(projectId)
        return objectStore.listKeys(docsPrefix)
            .filter { !it.endsWith(".index.json") }
            .map { key ->
                val rel = key.removePrefix(projectPrefix)
                val bytes = objectStore.get(key) ?: ByteArray(0)
                rel to bytes
            }
    }

    fun saveWizardData(projectId: String, data: WizardData) {
        objectStore.put(
            wizardKey(projectId),
            json.encodeToString(data).toByteArray(),
            "application/json"
        )
    }

    fun loadWizardData(projectId: String): WizardData? =
        objectStore.get(wizardKey(projectId))
            ?.toString(Charsets.UTF_8)
            ?.let { json.decodeFromString<WizardData>(it) }
}

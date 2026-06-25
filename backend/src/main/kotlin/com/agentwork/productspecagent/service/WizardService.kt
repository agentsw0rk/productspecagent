package com.agentwork.productspecagent.service

import com.agentwork.productspecagent.domain.*
import com.agentwork.productspecagent.storage.ProjectStorage
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.springframework.stereotype.Service

@Service
class WizardService(private val storage: ProjectStorage) {

    fun getWizardData(projectId: String): WizardData {
        storage.loadProject(projectId) ?: throw ProjectNotFoundException(projectId)
        return normalizeWizardData(storage.loadWizardData(projectId) ?: WizardData(projectId = projectId))
    }

    fun saveWizardData(projectId: String, data: WizardData): WizardData {
        storage.loadProject(projectId) ?: throw ProjectNotFoundException(projectId)
        val saved = normalizeWizardData(data.copy(projectId = projectId))
        storage.saveWizardData(projectId, saved)
        return saved
    }

    fun saveStepData(projectId: String, stepName: String, stepData: WizardStepData): WizardData {
        storage.loadProject(projectId) ?: throw ProjectNotFoundException(projectId)
        val existing = storage.loadWizardData(projectId) ?: WizardData(projectId = projectId)
        val updated = normalizeWizardData(
            existing.copy(steps = existing.steps + (stepName to normalizeStepData(stepName, stepData)))
        )
        storage.saveWizardData(projectId, updated)
        return updated
    }

    private fun normalizeWizardData(data: WizardData): WizardData =
        data.copy(steps = data.steps.mapValues { (stepName, stepData) -> normalizeStepData(stepName, stepData) })

    private fun normalizeStepData(stepName: String, stepData: WizardStepData): WizardStepData {
        if (stepName != "FEATURES") return stepData
        val features = stepData.fields["features"] as? JsonArray ?: return stepData
        val normalizedFeatures = JsonArray(features.map { feature ->
            if (feature is JsonObject) normalizeFeature(feature) else feature
        })
        return stepData.copy(fields = stepData.fields + ("features" to normalizedFeatures))
    }

    private fun normalizeFeature(feature: JsonObject): JsonObject =
        JsonObject(feature + ("scopes" to normalizeScopes(feature["scopes"])))

    private fun normalizeScopes(raw: JsonElement?): JsonArray {
        val scopes = when (raw) {
            is JsonArray -> raw.mapNotNull(::scopeName)
            is JsonPrimitive -> listOfNotNull(scopeName(raw))
            is JsonObject -> raw.entries.mapNotNull { (key, value) ->
                val enabled = (value as? JsonPrimitive)?.contentOrNull?.lowercase() != "false"
                key.takeIf { enabled }?.uppercase()?.takeIf { it in scopeNames }
            }
            else -> emptyList()
        }
        return JsonArray(scopes.distinct().map(::JsonPrimitive))
    }

    private fun scopeName(element: JsonElement): String? =
        (element as? JsonPrimitive)
            ?.contentOrNull
            ?.trim()
            ?.uppercase()
            ?.takeIf { it in scopeNames }

    private companion object {
        val scopeNames = FeatureScope.entries.map { it.name }.toSet()
    }
}

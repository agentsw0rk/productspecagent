package com.agentwork.productspecagent.service

import com.agentwork.productspecagent.domain.*
import com.agentwork.productspecagent.service.okf.OkfProjectLocks
import com.agentwork.productspecagent.storage.ProjectStorage
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.springframework.stereotype.Service

@Service
class WizardService(private val storage: ProjectStorage) {

    fun getWizardData(projectId: String): WizardData = OkfProjectLocks.withLock(projectId) {
        storage.loadProject(projectId) ?: throw ProjectNotFoundException(projectId)
        normalizeWizardData(storage.loadWizardData(projectId) ?: WizardData(projectId = projectId))
    }

    fun saveWizardData(projectId: String, data: WizardData): WizardData = OkfProjectLocks.withLock(projectId) {
        storage.loadProject(projectId) ?: throw ProjectNotFoundException(projectId)
        val saved = normalizeWizardData(data.copy(projectId = projectId))
        storage.saveWizardData(projectId, saved)
        saved
    }

    fun saveStepData(projectId: String, stepName: String, stepData: WizardStepData): WizardData =
        OkfProjectLocks.withLock(projectId) {
            storage.loadProject(projectId) ?: throw ProjectNotFoundException(projectId)
            val existing = storage.loadWizardData(projectId) ?: WizardData(projectId = projectId)
            val updated = normalizeWizardData(
                existing.copy(steps = existing.steps + (stepName to normalizeStepData(stepName, stepData)))
            )
            storage.saveWizardData(projectId, updated)
            updated
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

    private fun normalizeFeature(feature: JsonObject): JsonObject {
        val featureId = stringContent(feature["id"]) ?: "feature"
        return JsonObject(
            feature + mapOf(
                "scopes" to normalizeScopes(feature["scopes"]),
                "acceptanceCriteria" to normalizeAcceptanceCriteria(feature["acceptanceCriteria"], featureId),
            )
        )
    }

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
        stringContent(element)
            ?.trim()
            ?.uppercase()
            ?.takeIf { it in scopeNames }

    private fun normalizeAcceptanceCriteria(raw: JsonElement?, featureId: String): JsonArray {
        val criteria = when (raw) {
            is JsonArray -> raw
            is JsonObject, is JsonPrimitive -> listOf(raw)
            else -> emptyList()
        }
        return JsonArray(criteria.mapIndexedNotNull { index, criterion ->
            normalizeAcceptanceCriterion(criterion, featureId, index)
        })
    }

    private fun normalizeAcceptanceCriterion(
        raw: JsonElement,
        featureId: String,
        index: Int,
    ): JsonObject? {
        val fallbackId = "$featureId-acceptance-criterion-${index + 1}"
        if (raw is JsonPrimitive) {
            return stringContent(raw)?.let { text ->
                JsonObject(mapOf("id" to JsonPrimitive(fallbackId), "text" to JsonPrimitive(text)))
            }
        }
        if (raw !is JsonObject) return null

        val id = stringContent(raw["id"])?.takeIf { it.isNotBlank() } ?: fallbackId
        val text = stringContent(raw["text"]) ?: listOfNotNull(
            stringContent(raw["title"]),
            stringContent(raw["description"]),
        ).filter { it.isNotBlank() }.joinToString(": ").takeIf { it.isNotBlank() }
        return text?.let {
            JsonObject(mapOf("id" to JsonPrimitive(id), "text" to JsonPrimitive(it)))
        }
    }

    private fun stringContent(element: JsonElement?): String? =
        (element as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private companion object {
        val scopeNames = FeatureScope.entries.map { it.name }.toSet()
    }
}

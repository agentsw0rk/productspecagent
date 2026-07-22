package com.agentwork.productspecagent.service

import com.agentwork.productspecagent.domain.*
import com.agentwork.productspecagent.service.okf.OkfFeatureService
import com.agentwork.productspecagent.service.okf.OkfProjectLocks
import com.agentwork.productspecagent.storage.ProjectStorage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import org.springframework.context.annotation.Lazy
import org.springframework.stereotype.Service

@Service
class WizardService(
    private val storage: ProjectStorage,
    @Lazy private val okfFeatures: OkfFeatureService? = null,
) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun getWizardData(projectId: String): WizardData = OkfProjectLocks.withLock(projectId) {
        storage.loadProject(projectId) ?: throw ProjectNotFoundException(projectId)
        val legacy = normalizeWizardData(storage.loadWizardData(projectId) ?: WizardData(projectId = projectId))
        overlayActiveFeatures(projectId, legacy)
    }

    fun saveWizardData(projectId: String, data: WizardData): WizardData = OkfProjectLocks.withLock(projectId) {
        storage.loadProject(projectId) ?: throw ProjectNotFoundException(projectId)
        val saved = normalizeWizardData(data.copy(projectId = projectId))
        if (okfFeatures?.isActive(projectId) == true) {
            saved.steps["FEATURES"]?.let { okfFeatures.propose(projectId, graph(it)) }
        }
        val persisted = withoutActiveFeatureGraph(projectId, saved)
        storage.saveWizardData(projectId, persisted)
        overlayActiveFeatures(projectId, persisted)
    }

    fun saveStepData(projectId: String, stepName: String, stepData: WizardStepData): WizardData =
        OkfProjectLocks.withLock(projectId) {
            storage.loadProject(projectId) ?: throw ProjectNotFoundException(projectId)
            val existing = storage.loadWizardData(projectId) ?: WizardData(projectId = projectId)
            val updated = normalizeWizardData(
                existing.copy(steps = existing.steps + (stepName to normalizeStepData(stepName, stepData)))
            )
            if (stepName == "FEATURES" && okfFeatures?.isActive(projectId) == true) {
                okfFeatures.propose(projectId, graph(updated.steps.getValue(stepName)))
            }
            val persisted = withoutActiveFeatureGraph(projectId, updated)
            storage.saveWizardData(projectId, persisted)
            overlayActiveFeatures(projectId, persisted)
        }

    private fun overlayActiveFeatures(projectId: String, data: WizardData): WizardData {
        val service = okfFeatures?.takeIf { it.isActive(projectId) } ?: return data
        val encoded = json.encodeToJsonElement(service.graph(projectId)) as JsonObject
        val existing = data.steps["FEATURES"] ?: WizardStepData()
        val step = existing.copy(
            fields = existing.fields + mapOf(
                "features" to (encoded["features"] ?: JsonArray(emptyList())),
                "edges" to (encoded["edges"] ?: JsonArray(emptyList())),
            ),
        )
        return data.copy(steps = data.steps + ("FEATURES" to step))
    }

    private fun withoutActiveFeatureGraph(projectId: String, data: WizardData): WizardData {
        if (okfFeatures?.isActive(projectId) != true) return data
        val legacyFeatureStep = storage.loadWizardData(projectId)?.steps?.get("FEATURES")
        val featureStep = data.steps["FEATURES"] ?: legacyFeatureStep ?: return data
        val legacyGraphFields = legacyFeatureStep?.fields.orEmpty()
            .filterKeys { it == "features" || it == "edges" }
        return data.copy(
            steps = data.steps + (
                "FEATURES" to featureStep.copy(
                    fields = legacyGraphFields + (featureStep.fields - "features" - "edges"),
                )
            ),
        )
    }

    private fun graph(stepData: WizardStepData): WizardFeatureGraph = json.decodeFromJsonElement(
        JsonObject(
            mapOf(
                "features" to (stepData.fields["features"] ?: JsonArray(emptyList())),
                "edges" to (stepData.fields["edges"] ?: JsonArray(emptyList())),
            ),
        ),
    )

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

package com.agentwork.productspecagent.service

import com.agentwork.productspecagent.agent.DecisionAgent
import com.agentwork.productspecagent.domain.*
import com.agentwork.productspecagent.storage.DecisionStorage
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.springframework.stereotype.Service
import java.time.Instant

@Service
class DecisionService(
    private val storage: DecisionStorage,
    private val agent: DecisionAgent
) {
    private val legacyJson = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun createDecision(projectId: String, title: String, stepType: FlowStepType): Decision {
        val decision = agent.generateDecision(projectId, title, stepType)
        storage.saveDecision(decision)
        return decision
    }

    fun getDecision(projectId: String, decisionId: String): Decision {
        return normalizeAndPersist(storage.loadDecision(projectId, decisionId))
    }

    fun listDecisions(projectId: String): List<Decision> {
        return storage.listDecisions(projectId).map(::normalizeAndPersist)
    }

    fun resolveDecision(projectId: String, decisionId: String, chosenOptionId: String, rationale: String): Decision {
        val decision = normalizeAndPersist(storage.loadDecision(projectId, decisionId))
        if (decision.status == DecisionStatus.RESOLVED) {
            throw IllegalStateException("Decision already resolved")
        }
        if (decision.options.none { it.id == chosenOptionId }) {
            throw IllegalArgumentException("Invalid option: $chosenOptionId")
        }
        val resolved = decision.copy(
            status = DecisionStatus.RESOLVED,
            chosenOptionId = chosenOptionId,
            rationale = rationale,
            resolvedAt = Instant.now().toString()
        )
        storage.saveDecision(resolved)
        return resolved
    }

    fun markApplied(projectId: String, decisionId: String, appliedFields: List<String>): Decision {
        val decision = normalizeAndPersist(storage.loadDecision(projectId, decisionId))
        val applied = decision.copy(
            appliedAt = Instant.now().toString(),
            appliedFields = appliedFields
        )
        storage.saveDecision(applied)
        return applied
    }

    private fun normalizeAndPersist(decision: Decision): Decision {
        val normalized = normalizeLegacyDecision(decision)
        if (normalized != decision) {
            storage.saveDecision(normalized)
        }
        return normalized
    }

    private fun normalizeLegacyDecision(decision: Decision): Decision {
        val rawRecommendation = decision.recommendation.trim()
        if (!decision.hasFallbackOptions() || !rawRecommendation.startsWith("{") || !rawRecommendation.contains("\"options\"")) {
            return decision
        }

        parseLegacyDecisionResponse(rawRecommendation)?.let { parsed ->
            if (parsed.options.isNotEmpty()) {
                return decision.copy(
                    options = parsed.options.toDecisionOptions(),
                    recommendation = parsed.recommendation.toReadableRecommendation(parsed.options)
                )
            }
        }

        val labels = extractLabels(rawRecommendation)
        if (labels.isEmpty()) {
            return decision.copy(recommendation = "Agenten-Empfehlung konnte nicht vollstaendig rekonstruiert werden.")
        }

        val options = labels.mapIndexed { idx, label ->
            DecisionOption(
                id = "opt-${idx + 1}",
                label = label,
                pros = emptyList(),
                cons = emptyList(),
                recommended = idx == 0
            )
        }
        return decision.copy(
            options = options,
            recommendation = "Empfohlen: ${options.first().label}"
        )
    }

    private fun Decision.hasFallbackOptions(): Boolean =
        options.size == 2 &&
            options[0].label == "Yes" &&
            options[1].label == "No" &&
            options[0].pros == listOf("Recommended by agent") &&
            options[1].cons == listOf("Not recommended")

    private fun parseLegacyDecisionResponse(raw: String): LegacyDecisionResponse? =
        runCatching {
            legacyJson.decodeFromString<LegacyDecisionResponse>(raw)
        }.getOrNull()

    private fun List<LegacyDecisionOption>.toDecisionOptions(): List<DecisionOption> {
        val recommendedIndex = indexOfFirst { it.recommended }.takeIf { it >= 0 } ?: 0
        return mapIndexed { idx, option ->
            DecisionOption(
                id = "opt-${idx + 1}",
                label = option.label,
                pros = option.pros,
                cons = option.cons,
                recommended = idx == recommendedIndex
            )
        }
    }

    private fun String?.toReadableRecommendation(options: List<LegacyDecisionOption>): String {
        val explicitRecommendation = this?.takeIf { it.isNotBlank() }
        if (explicitRecommendation != null) return explicitRecommendation

        val recommendedOption = options.firstOrNull { it.recommended } ?: options.first()
        return "Empfohlen: ${recommendedOption.label}"
    }

    private fun extractLabels(raw: String): List<String> =
        """"label"\s*:\s*"((?:\\.|[^"\\])*)"""".toRegex()
            .findAll(raw)
            .map { it.groupValues[1].decodeJsonString() }
            .distinct()
            .toList()

    private fun String.decodeJsonString(): String =
        runCatching { legacyJson.decodeFromString<String>("\"$this\"") }
            .getOrDefault(this)
}

@Serializable
private data class LegacyDecisionResponse(
    val options: List<LegacyDecisionOption> = emptyList(),
    val recommendation: String? = null
)

@Serializable
private data class LegacyDecisionOption(
    val label: String,
    val pros: List<String> = emptyList(),
    val cons: List<String> = emptyList(),
    val recommended: Boolean = false
)

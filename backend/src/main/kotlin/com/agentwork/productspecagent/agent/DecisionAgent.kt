package com.agentwork.productspecagent.agent

import com.agentwork.productspecagent.domain.*
import com.agentwork.productspecagent.service.PromptService
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.UUID

@Service
open class DecisionAgent(
    private val contextBuilder: SpecContextBuilder,
    private val promptService: PromptService,
    private val koogRunner: KoogAgentRunner? = null
) {
    companion object {
        const val AGENT_ID = "decision"
    }

    suspend fun generateDecision(projectId: String, title: String, stepType: FlowStepType): Decision {
        val context = contextBuilder.buildContext(projectId)
        val prompt = buildString {
            appendLine("Based on this project context, generate a structured decision for: $title")
            appendLine()
            appendLine(context)
            appendLine()
            appendLine("Respond with EXACTLY this JSON format (no markdown, no explanation outside JSON):")
            appendLine("""{"options":[{"label":"Option name","pros":["pro1"],"cons":["con1"],"recommended":true}],"recommendation":"Why this option"}""")
            appendLine("Generate 2-3 options. Exactly one should have recommended=true.")
        }

        val rawResponse = runAgent(prompt)
        return parseDecisionResponse(rawResponse, projectId, title, stepType)
    }

    protected open suspend fun runAgent(prompt: String): String {
        return koogRunner?.run(AGENT_ID, promptService.get("decision-system"), prompt)
            ?: throw UnsupportedOperationException("KoogAgentRunner not configured.")
    }

    private fun parseDecisionResponse(
        raw: String, projectId: String, title: String, stepType: FlowStepType
    ): Decision {
        // Extract JSON from response (may be wrapped in markdown code blocks)
        val jsonStr = raw.replace("```json", "").replace("```", "").trim()

        return try {
            val parsed = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
                .decodeFromString<DecisionAgentResponse>(jsonStr)

            val recommendedIndex = parsed.options.indexOfFirst { it.recommended }.takeIf { it >= 0 } ?: 0
            val options = parsed.options.mapIndexed { idx, opt ->
                DecisionOption(
                    id = "opt-${idx + 1}",
                    label = opt.label,
                    pros = opt.pros,
                    cons = opt.cons,
                    recommended = idx == recommendedIndex
                )
            }
            val recommendation = parsed.recommendation?.takeIf { it.isNotBlank() }
                ?: options.getOrNull(recommendedIndex)?.let { "Empfohlen: ${it.label}" }
                ?: "Keine Empfehlung vom Agenten geliefert."

            Decision(
                id = UUID.randomUUID().toString(),
                projectId = projectId,
                stepType = stepType,
                title = title,
                options = options,
                recommendation = recommendation,
                createdAt = Instant.now().toString()
            )
        } catch (e: Exception) {
            // Fallback: create a simple decision with the raw response as recommendation
            Decision(
                id = UUID.randomUUID().toString(),
                projectId = projectId,
                stepType = stepType,
                title = title,
                options = listOf(
                    DecisionOption("opt-1", "Yes", listOf("Recommended by agent"), emptyList(), true),
                    DecisionOption("opt-2", "No", emptyList(), listOf("Not recommended"), false)
                ),
                recommendation = raw.take(500),
                createdAt = Instant.now().toString()
            )
        }
    }
}

@kotlinx.serialization.Serializable
private data class DecisionAgentResponse(
    val options: List<AgentOptionResponse>,
    val recommendation: String? = null
)

@kotlinx.serialization.Serializable
private data class AgentOptionResponse(
    val label: String,
    val pros: List<String>,
    val cons: List<String>,
    val recommended: Boolean = false
)

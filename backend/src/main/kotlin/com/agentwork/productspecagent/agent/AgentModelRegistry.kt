package com.agentwork.productspecagent.agent

import ai.koog.prompt.llm.LLModel
import org.springframework.stereotype.Component

@Component
class AgentModelRegistry(private val properties: AgentModelsProperties) {

    companion object {
        val KNOWN_AGENT_IDS: Set<String> = setOf(
            "idea-to-spec",
            "decision",
            "wizard-blocker-apply",
            "acceptance-criteria-proposal",
            "feature-proposal",
            "feature-done-import",
            "plan-generator",
            "design-summary",
            "design-variant",
            "design-image-analysis",
        )
    }

    private val tierMapping: Map<AgentModelTier, LLModel>
    private val tierIdMapping: Map<AgentModelTier, String>
    private val agentDefaults: Map<String, AgentModelTier>

    init {
        AgentModelTier.entries.forEach { tier ->
            check(properties.tiers.containsKey(tier)) {
                "Tier mapping incomplete: missing $tier"
            }
        }
        tierIdMapping = properties.tiers.toMap()
        tierMapping = properties.tiers.mapValues { (_, name) ->
            when (properties.resolver) {
                AgentModelResolverType.OPENAI -> resolveOpenAiModel(name)
                AgentModelResolverType.CLAUDE -> resolveClaudeModel(name)
            }
        }

        KNOWN_AGENT_IDS.forEach { id ->
            check(properties.defaults.containsKey(id)) {
                "Missing default tier for agent: $id"
            }
        }
        properties.defaults.keys.forEach { id ->
            check(id in KNOWN_AGENT_IDS) {
                "Unknown agent id in defaults: $id"
            }
        }
        agentDefaults = properties.defaults.toMap()
    }

    fun agentIds(): Set<String> = KNOWN_AGENT_IDS

    fun defaultTier(agentId: String): AgentModelTier =
        agentDefaults[agentId] ?: throw IllegalArgumentException("Unknown agent id: $agentId")

    fun modelFor(tier: AgentModelTier): LLModel =
        tierMapping.getValue(tier)

    fun modelIdFor(tier: AgentModelTier): String =
        tierIdMapping.getValue(tier)

    fun resolverType(): AgentModelResolverType = properties.resolver

    fun tierMappingView(): Map<AgentModelTier, String> = tierIdMapping
}

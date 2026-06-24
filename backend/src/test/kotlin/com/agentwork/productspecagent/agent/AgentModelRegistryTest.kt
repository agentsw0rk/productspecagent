package com.agentwork.productspecagent.agent

import ai.koog.prompt.executor.clients.anthropic.AnthropicModels
import ai.koog.prompt.executor.clients.openai.OpenAIModels
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class AgentModelRegistryTest {

    private val validDefaults = mapOf(
        "idea-to-spec" to AgentModelTier.LARGE,
        "decision" to AgentModelTier.MEDIUM,
        "wizard-blocker-apply" to AgentModelTier.MEDIUM,
        "acceptance-criteria-proposal" to AgentModelTier.MEDIUM,
        "feature-proposal" to AgentModelTier.MEDIUM,
        "feature-done-import" to AgentModelTier.MEDIUM,
        "plan-generator" to AgentModelTier.LARGE,
        "design-summary" to AgentModelTier.MEDIUM,
        "design-variant" to AgentModelTier.MEDIUM,
        "design-image-analysis" to AgentModelTier.MEDIUM,
    )

    private val validProps = AgentModelsProperties(
        tiers = mapOf(
            AgentModelTier.SMALL to "gpt-5-nano",
            AgentModelTier.MEDIUM to "gpt-5-mini",
            AgentModelTier.LARGE to "gpt-5-2",
        ),
        defaults = validDefaults,
    )

    @Test
    fun `valid properties produce working registry`() {
        val reg = AgentModelRegistry(validProps)
        assertThat(reg.agentIds()).containsExactlyInAnyOrder(
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
        assertThat(reg.defaultTier("idea-to-spec")).isEqualTo(AgentModelTier.LARGE)
        assertThat(reg.modelFor(AgentModelTier.SMALL)).isEqualTo(OpenAIModels.Chat.GPT5Nano)
        assertThat(reg.modelIdFor(AgentModelTier.LARGE)).isEqualTo("gpt-5-2")
    }

    @Test
    fun `missing tier mapping fails fast`() {
        val props = validProps.copy(tiers = validProps.tiers - AgentModelTier.SMALL)
        assertThatThrownBy { AgentModelRegistry(props) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("Tier mapping incomplete: missing SMALL")
    }

    @Test
    fun `unknown model id fails fast`() {
        val props = validProps.copy(
            tiers = validProps.tiers + (AgentModelTier.LARGE to "gpt-99-turbo")
        )
        assertThatThrownBy { AgentModelRegistry(props) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("Unknown OpenAI model id: gpt-99-turbo")
    }

    @Test
    fun `claude resolver maps configured tiers`() {
        val props = AgentModelsProperties(
            resolver = AgentModelResolverType.CLAUDE,
            tiers = mapOf(
                AgentModelTier.SMALL to "claude-haiku-4-5",
                AgentModelTier.MEDIUM to "claude-sonnet-4-5",
                AgentModelTier.LARGE to "claude-sonnet-4-6",
            ),
            defaults = validDefaults,
        )

        val reg = AgentModelRegistry(props)

        assertThat(reg.modelFor(AgentModelTier.SMALL)).isEqualTo(AnthropicModels.Haiku_4_5)
        assertThat(reg.modelFor(AgentModelTier.MEDIUM)).isEqualTo(AnthropicModels.Sonnet_4_5)
        assertThat(reg.modelFor(AgentModelTier.LARGE)).isEqualTo(AnthropicModels.Sonnet_4_6)
        assertThat(reg.modelIdFor(AgentModelTier.LARGE)).isEqualTo("claude-sonnet-4-6")
    }

    @Test
    fun `claude resolver fails fast on unknown model id`() {
        val props = AgentModelsProperties(
            resolver = AgentModelResolverType.CLAUDE,
            tiers = mapOf(
                AgentModelTier.SMALL to "claude-haiku-4-5",
                AgentModelTier.MEDIUM to "claude-sonnet-4-5",
                AgentModelTier.LARGE to "claude-unknown",
            ),
            defaults = validDefaults,
        )

        assertThatThrownBy { AgentModelRegistry(props) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("Unknown Claude model id: claude-unknown")
    }

    @Test
    fun `missing default for known agent fails fast`() {
        val props = validProps.copy(defaults = validProps.defaults - "decision")
        assertThatThrownBy { AgentModelRegistry(props) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("Missing default tier for agent: decision")
    }

    @Test
    fun `unknown agent id in defaults fails fast`() {
        val props = validProps.copy(
            defaults = validProps.defaults + ("ghost-agent" to AgentModelTier.SMALL)
        )
        assertThatThrownBy { AgentModelRegistry(props) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("Unknown agent id in defaults: ghost-agent")
    }

    @Test
    fun `defaultTier throws for unknown agentId at runtime`() {
        val reg = AgentModelRegistry(validProps)
        assertThatThrownBy { reg.defaultTier("ghost") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("ghost")
    }

    @Test
    fun `known agents include wizard blocker apply`() {
        assertThat(AgentModelRegistry.KNOWN_AGENT_IDS).contains("wizard-blocker-apply")
    }

    @Test
    fun `known agents include acceptance criteria proposal`() {
        assertThat(AgentModelRegistry.KNOWN_AGENT_IDS).contains("acceptance-criteria-proposal")
    }

    @Test
    fun `known agents include feature done import`() {
        val reg = AgentModelRegistry(validProps)

        assertThat(AgentModelRegistry.KNOWN_AGENT_IDS).contains("feature-done-import")
        assertThat(reg.defaultTier("feature-done-import")).isEqualTo(AgentModelTier.MEDIUM)
    }
}

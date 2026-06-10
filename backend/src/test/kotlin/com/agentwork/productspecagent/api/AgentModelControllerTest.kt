package com.agentwork.productspecagent.api

import com.agentwork.productspecagent.agent.AgentModelRegistry
import com.agentwork.productspecagent.service.AgentModelService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*

@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser(roles = ["ADMIN"])
class AgentModelControllerTest {

    @Autowired private lateinit var mvc: MockMvc
    @Autowired private lateinit var service: AgentModelService
    @Autowired private lateinit var registry: AgentModelRegistry

    @AfterEach
    fun cleanup() {
        registry.agentIds().forEach { service.reset(it) }
    }

    @Test
    fun `GET lists all agents with default tiers`() {
        mvc.perform(get("/api/v1/agent-models"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(9))
            .andExpect(jsonPath("$[?(@.agentId == 'decision')].defaultTier").value("MEDIUM"))
            .andExpect(jsonPath("$[?(@.agentId == 'decision')].currentTier").value("MEDIUM"))
            .andExpect(jsonPath("$[?(@.agentId == 'decision')].isOverridden").value(false))
            .andExpect(jsonPath("$[?(@.agentId == 'wizard-blocker-apply')].defaultTier").value("MEDIUM"))
            .andExpect(jsonPath("$[?(@.agentId == 'acceptance-criteria-proposal')].defaultTier").value("MEDIUM"))
            .andExpect(jsonPath("$[?(@.agentId == 'design-variant')].defaultTier").value("MEDIUM"))
            .andExpect(jsonPath("$[?(@.agentId == 'design-image-analysis')].defaultTier").value("MEDIUM"))
            .andExpect(jsonPath("$[?(@.agentId == 'decision')].tierMapping.MEDIUM").exists())
    }

    @Test
    fun `PUT updates tier and GET reflects override`() {
        mvc.perform(
            put("/api/v1/agent-models/decision")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"tier":"SMALL"}""")
        ).andExpect(status().isNoContent)

        assertThat(service.getTier("decision")).isEqualTo(com.agentwork.productspecagent.agent.AgentModelTier.SMALL)

        mvc.perform(get("/api/v1/agent-models"))
            .andExpect(jsonPath("$[?(@.agentId == 'decision')].currentTier").value("SMALL"))
            .andExpect(jsonPath("$[?(@.agentId == 'decision')].isOverridden").value(true))
    }

    @Test
    fun `PUT rejects invalid tier with 400`() {
        mvc.perform(
            put("/api/v1/agent-models/decision")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"tier":"XL"}""")
        ).andExpect(status().isBadRequest)
    }

    @Test
    fun `PUT for unknown agent returns 404`() {
        mvc.perform(
            put("/api/v1/agent-models/ghost")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"tier":"SMALL"}""")
        ).andExpect(status().isNotFound)
    }

    @Test
    fun `DELETE removes override and returns 204`() {
        service.setTier("decision", com.agentwork.productspecagent.agent.AgentModelTier.SMALL)
        mvc.perform(delete("/api/v1/agent-models/decision"))
            .andExpect(status().isNoContent)
        assertThat(service.getTier("decision")).isEqualTo(com.agentwork.productspecagent.agent.AgentModelTier.MEDIUM)
    }

    @Test
    fun `DELETE for unknown agent returns 404`() {
        mvc.perform(delete("/api/v1/agent-models/ghost"))
            .andExpect(status().isNotFound)
    }
}

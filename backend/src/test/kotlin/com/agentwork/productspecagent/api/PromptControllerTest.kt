package com.agentwork.productspecagent.api

import com.agentwork.productspecagent.service.PromptRegistry
import com.agentwork.productspecagent.service.PromptService
import com.agentwork.productspecagent.storage.ObjectStore
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*

@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser(roles = ["ADMIN"])
class PromptControllerTest {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var objectStore: ObjectStore
    @Autowired lateinit var promptService: PromptService
    @Autowired lateinit var promptRegistry: PromptRegistry

    @AfterEach
    fun cleanup() {
        // Reset evicts the in-memory cache and the S3 override per id, so
        // overrides set in one test don't leak into the next via the singleton
        // PromptService cache.
        promptRegistry.definitions.forEach { promptService.reset(it.id) }
        objectStore.deletePrefix("prompts/")
    }

    @Test
    fun `GET prompts returns all items with isOverridden flags`() {
        mockMvc.perform(get("/api/v1/prompts"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(promptRegistry.definitions.size))
            .andExpect(jsonPath("$[?(@.id == 'idea-base')]").exists())
            .andExpect(jsonPath("$[?(@.id == 'wizard-blocker-apply-system')]").exists())
            .andExpect(jsonPath("$[?(@.id == 'design-variant-system')]").exists())
            .andExpect(jsonPath("$[?(@.id == 'design-image-analysis-system')]").exists())
            .andExpect(jsonPath("$[?(@.id == 'idea-base')].isOverridden").value(false))
            .andExpect(jsonPath("$[?(@.id == 'decision-system')].agent").value("Decision"))
    }

    @Test
    fun `GET prompts id returns default content when no override`() {
        mockMvc.perform(get("/api/v1/prompts/decision-system"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.id").value("decision-system"))
            .andExpect(jsonPath("$.isOverridden").value(false))
            .andExpect(jsonPath("$.content").value(org.hamcrest.Matchers.containsString("Produkt-Entscheidungs-Berater")))
    }

    @Test
    fun `GET prompts id returns 404 for unknown id`() {
        mockMvc.perform(get("/api/v1/prompts/nonexistent"))
            .andExpect(status().isNotFound)
    }

    @Test
    fun `PUT prompts id stores override and subsequent GET returns it`() {
        val newContent = "Neuer Decision-System-Prompt für Tests."

        mockMvc.perform(
            put("/api/v1/prompts/decision-system")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"content":"$newContent"}""")
        ).andExpect(status().isOk)

        mockMvc.perform(get("/api/v1/prompts/decision-system"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.content").value(newContent))
            .andExpect(jsonPath("$.isOverridden").value(true))
    }

    @Test
    fun `PUT prompts id rejects empty content with 400 and error list`() {
        mockMvc.perform(
            put("/api/v1/prompts/decision-system")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"content":""}""")
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errors").isArray)
            .andExpect(jsonPath("$.errors[0]").value(org.hamcrest.Matchers.containsString("nicht leer")))
    }

    @Test
    fun `PUT prompts id rejects missing markers in idea-marker-reminder with 400`() {
        val incomplete = "Nur ein Marker [DECISION_NEEDED] erwähnt, der Rest fehlt."

        mockMvc.perform(
            put("/api/v1/prompts/idea-marker-reminder")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"content":"$incomplete"}""")
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errors[0]").value(org.hamcrest.Matchers.containsString("[STEP_COMPLETE]")))
    }

    @Test
    fun `DELETE prompts id removes override and GET returns default again`() {
        // Prime an override
        mockMvc.perform(
            put("/api/v1/prompts/decision-system")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"content":"Override für Reset-Test."}""")
        ).andExpect(status().isOk)

        mockMvc.perform(delete("/api/v1/prompts/decision-system"))
            .andExpect(status().isOk)

        mockMvc.perform(get("/api/v1/prompts/decision-system"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.isOverridden").value(false))
            .andExpect(jsonPath("$.content").value(org.hamcrest.Matchers.containsString("Produkt-Entscheidungs-Berater")))
    }
}

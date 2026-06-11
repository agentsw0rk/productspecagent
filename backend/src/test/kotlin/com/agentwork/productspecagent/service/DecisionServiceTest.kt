package com.agentwork.productspecagent.service

import com.agentwork.productspecagent.agent.DecisionAgent
import com.agentwork.productspecagent.agent.SpecContextBuilder
import com.agentwork.productspecagent.domain.Decision
import com.agentwork.productspecagent.domain.DecisionOption
import com.agentwork.productspecagent.domain.FlowStepType
import com.agentwork.productspecagent.storage.DecisionStorage
import com.agentwork.productspecagent.storage.InMemoryObjectStore
import com.agentwork.productspecagent.storage.ProjectStorage
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DecisionServiceTest {

    private val objectStore = InMemoryObjectStore()
    private val storage = DecisionStorage(objectStore)
    private val projectService = ProjectService(ProjectStorage(InMemoryObjectStore()))
    private val contextBuilder = SpecContextBuilder(projectService)
    private val promptService = PromptService(PromptRegistry(), InMemoryObjectStore())
    private val agent = object : DecisionAgent(contextBuilder, promptService) {
        override suspend fun runAgent(prompt: String): String = error("Agent should not be called")
    }
    private val service = DecisionService(storage, agent)

    @Test
    fun `listDecisions repairs legacy fallback decision with full JSON recommendation`() {
        storage.saveDecision(
            legacyDecision(
                recommendation = """
                    {"options":[
                        {"label":"Small SaaS teams","pros":["Shared workflows"],"cons":["Needs integrations"],"recommended":true},
                        {"label":"Freelancers","pros":["Simple MVP"],"cons":["Lower willingness to pay"],"recommended":false}
                    ]}
                """.trimIndent()
            )
        )

        val repaired = service.listDecisions("project-1").single()

        assertEquals("Small SaaS teams", repaired.options[0].label)
        assertEquals(listOf("Shared workflows"), repaired.options[0].pros)
        assertTrue(repaired.options[0].recommended)
        assertEquals("Freelancers", repaired.options[1].label)
        assertFalse(repaired.recommendation.trim().startsWith("{"))
        assertEquals(repaired, storage.loadDecision("project-1", "decision-1"))
    }

    @Test
    fun `listDecisions repairs legacy fallback decision with truncated JSON recommendation`() {
        storage.saveDecision(
            legacyDecision(
                recommendation = """{"options":[{"label":"Small SaaS teams","pros":["Shared workflows"]},{"label":"Freelancers","pros":["Simple"""
            )
        )

        val repaired = service.listDecisions("project-1").single()

        assertEquals(2, repaired.options.size)
        assertEquals("Small SaaS teams", repaired.options[0].label)
        assertEquals("Freelancers", repaired.options[1].label)
        assertTrue(repaired.options[0].recommended)
        assertFalse(repaired.recommendation.trim().startsWith("{"))
        assertEquals("Empfohlen: Small SaaS teams", repaired.recommendation)
    }

    private fun legacyDecision(recommendation: String) = Decision(
        id = "decision-1",
        projectId = "project-1",
        stepType = FlowStepType.PROBLEM,
        title = "Target audience?",
        options = listOf(
            DecisionOption("opt-1", "Yes", listOf("Recommended by agent"), emptyList(), true),
            DecisionOption("opt-2", "No", emptyList(), listOf("Not recommended"), false)
        ),
        recommendation = recommendation,
        createdAt = "2026-06-10T14:12:21Z"
    )
}

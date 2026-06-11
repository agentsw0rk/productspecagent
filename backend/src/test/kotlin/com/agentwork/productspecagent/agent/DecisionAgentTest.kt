package com.agentwork.productspecagent.agent

import com.agentwork.productspecagent.domain.*
import com.agentwork.productspecagent.service.ProjectService
import com.agentwork.productspecagent.service.PromptRegistry
import com.agentwork.productspecagent.service.PromptService
import com.agentwork.productspecagent.storage.InMemoryObjectStore
import com.agentwork.productspecagent.storage.ProjectStorage
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.*

class DecisionAgentTest {

    private lateinit var projectService: ProjectService
    private lateinit var contextBuilder: SpecContextBuilder
    private lateinit var promptService: PromptService

    @BeforeEach
    fun setup() {
        val storage = ProjectStorage(InMemoryObjectStore())
        projectService = ProjectService(storage)
        contextBuilder = SpecContextBuilder(projectService)
        promptService = PromptService(PromptRegistry(), InMemoryObjectStore())
    }

    private fun createFakeAgent(jsonResponse: String): DecisionAgent {
        return object : DecisionAgent(contextBuilder, promptService) {
            override suspend fun runAgent(prompt: String): String = jsonResponse
        }
    }

    @Test
    fun `generateDecision parses valid JSON response`() = runBlocking {
        val project = projectService.createProject("Test")
        val agent = createFakeAgent("""
            {"options":[
                {"label":"Include in MVP","pros":["Users need it"],"cons":["More time"],"recommended":true},
                {"label":"Defer to v2","pros":["Faster launch"],"cons":["Users may not adopt"],"recommended":false}
            ],"recommendation":"Include because users need it early."}
        """.trimIndent())

        val decision = agent.generateDecision(project.project.id, "MVP scope?", FlowStepType.FEATURES)

        assertEquals("MVP scope?", decision.title)
        assertEquals(FlowStepType.FEATURES, decision.stepType)
        assertEquals(DecisionStatus.PENDING, decision.status)
        assertEquals(2, decision.options.size)
        assertTrue(decision.options[0].recommended)
        assertFalse(decision.options[1].recommended)
        assertContains(decision.recommendation, "Include")
    }

    @Test
    fun `generateDecision parses option JSON without recommendation`() = runBlocking {
        val project = projectService.createProject("Test")
        val agent = createFakeAgent("""
            {"options":[
                {"label":"Kleine SaaS-Teams","pros":["Klarer Bedarf an gemeinsamer Aufgabenkoordination"],"cons":["Erwarten oft Integrationen"],"recommended":true},
                {"label":"Freelancer","pros":["Einfachster Start"],"cons":["Geringere Zahlungsbereitschaft"],"recommended":false}
            ]}
        """.trimIndent())

        val decision = agent.generateDecision(project.project.id, "Primaere Zielgruppe?", FlowStepType.PROBLEM)

        assertEquals("Primaere Zielgruppe?", decision.title)
        assertEquals(2, decision.options.size)
        assertEquals("Kleine SaaS-Teams", decision.options[0].label)
        assertTrue(decision.options[0].recommended)
        assertContains(decision.recommendation, "Kleine SaaS-Teams")
        assertFalse(decision.recommendation.trim().startsWith("{"), "Recommendation must not render raw JSON")
    }

    @Test
    fun `generateDecision defaults first option when recommendation markers are missing`() = runBlocking {
        val project = projectService.createProject("Test")
        val agent = createFakeAgent("""
            {"options":[
                {"label":"Kleine SaaS-Teams","pros":["Klarer Bedarf an gemeinsamer Aufgabenkoordination"],"cons":["Erwarten oft Integrationen"]},
                {"label":"Freelancer","pros":["Einfachster Start"],"cons":["Geringere Zahlungsbereitschaft"]}
            ]}
        """.trimIndent())

        val decision = agent.generateDecision(project.project.id, "Primaere Zielgruppe?", FlowStepType.PROBLEM)

        assertEquals(2, decision.options.size)
        assertTrue(decision.options[0].recommended)
        assertFalse(decision.options[1].recommended)
        assertContains(decision.recommendation, "Kleine SaaS-Teams")
        assertFalse(decision.recommendation.trim().startsWith("{"), "Recommendation must not render raw JSON")
    }

    @Test
    fun `generateDecision handles malformed JSON gracefully`() = runBlocking {
        val project = projectService.createProject("Test")
        val agent = createFakeAgent("This is not JSON at all")

        val decision = agent.generateDecision(project.project.id, "Fallback test", FlowStepType.MVP)

        assertEquals("Fallback test", decision.title)
        assertEquals(2, decision.options.size) // fallback Yes/No
        assertEquals(DecisionStatus.PENDING, decision.status)
    }
}

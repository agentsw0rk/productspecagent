package com.agentwork.productspecagent.api

import com.agentwork.productspecagent.service.ProjectService
import com.agentwork.productspecagent.storage.S3TestSupport
import com.jayway.jsonpath.JsonPath
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.junit.jupiter.Testcontainers

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class SecurityIntegrationTest : S3TestSupport() {

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var projectService: ProjectService

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun props(registry: DynamicPropertyRegistry) {
            registry.add("auth.jwt.secret") { "0123456789abcdef0123456789abcdef0123456789abcdef" }
            registry.add("app.storage.bucket") { BUCKET }
            registry.add("app.storage.endpoint") { minio.s3URL }
            registry.add("app.storage.access-key") { minio.userName }
            registry.add("app.storage.secret-key") { minio.password }
            registry.add("app.storage.path-style-access") { "true" }
        }
    }

    private fun registerBody(email: String, pw: String) =
        """{"email":"$email","password":"$pw"}"""

    @Test
    fun `health endpoint accessible without auth`() {
        mvc.perform(get("/api/health")).andExpect(status().isOk)
    }

    @Test
    fun `projects endpoint without bearer token returns 401`() {
        mvc.perform(get("/api/v1/projects")).andExpect(status().isUnauthorized)
    }

    @Test
    fun `features config endpoint is public`() {
        mvc.perform(get("/api/v1/config/features"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.hackathonLock").exists())
    }

    @Test
    fun `handoff zip endpoint accessible without auth`() {
        val projectId = projectService.createProject("Public Handoff").project.id

        val response = mvc.perform(get("/api/v1/projects/$projectId/handoff/handoff.zip"))
            .andExpect(status().isOk)
            .andExpect(content().contentType(MediaType.parseMediaType("application/zip")))
            .andReturn()
            .response

        assert(response.contentAsByteArray.isNotEmpty()) {
            "Expected handoff zip content"
        }
    }

    @Test
    fun `mcp endpoint accessible without auth`() {
        mvc.perform(
            post("/mcp")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"jsonrpc":"2.0","id":1,"method":"tools/list"}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.result.tools[0].name").value("get_project_sync_context"))
    }

    @Test
    fun `living sync reporting endpoint accepts handoff agent payload without auth`() {
        val projectId = projectService.createProject("Living Sync Report").project.id

        mvc.perform(
            post("/api/v1/projects/$projectId/living-sync/mcp/report-feature-progress")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "feature": "01-user-accounts-workspace-setup",
                      "status": "started",
                      "message": "Feature 01 implementation started."
                    }
                    """.trimIndent(),
                ),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.type").value("FEATURE_PROGRESS"))
            .andExpect(jsonPath("$.featureId").value("01-user-accounts-workspace-setup"))
            .andExpect(jsonPath("$.status").value("IN_PROGRESS"))
            .andExpect(jsonPath("$.summary").value("Feature 01 implementation started."))
    }

    @Test
    fun `living sync code changes endpoint accepts reporter payload without auth`() {
        val projectId = projectService.createProject("Living Sync Code Changes").project.id

        mvc.perform(
            post("/api/v1/projects/$projectId/living-sync/mcp/report-code-changes")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "summary": "Implemented spec consolidation.",
                      "files": ["backend/src/main/kotlin/App.kt"],
                      "commits": ["abc123"],
                      "agentName": "claude-code"
                    }
                    """.trimIndent(),
                ),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.type").value("CODE_CHANGES"))
            .andExpect(jsonPath("$.summary").value("Implemented spec consolidation."))
    }

    @Test
    fun `projects endpoint with valid bearer token does not return 401`() {
        val body = mvc.perform(post("/api/v1/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .content(registerBody("user@example.com", "password123")))
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.token").isString)
            .andReturn()
            .response
            .contentAsString
        val token: String = JsonPath.read(body, "$.token")

        // Don't expect a specific success code — auth gate just shouldn't reject with 401.
        val result = mvc.perform(get("/api/v1/projects").header("Authorization", "Bearer $token")).andReturn()
        assertNotEquals(401, result.response.status, "Expected request through auth gate, got 401")
    }

    @Test
    @WithMockUser
    fun `configuration endpoints reject authenticated non-admin users`() {
        listOf(
            "/api/v1/prompts",
            "/api/v1/agent-models",
            "/api/v1/asset-bundles",
        ).forEach { path ->
            mvc.perform(get(path)).andExpect(status().isForbidden)
        }
    }

    @Test
    @WithMockUser(roles = ["ADMIN"])
    fun `configuration endpoints allow admin users`() {
        listOf(
            "/api/v1/prompts",
            "/api/v1/agent-models",
            "/api/v1/asset-bundles",
        ).forEach { path ->
            mvc.perform(get(path)).andExpect(status().isOk)
        }
    }
}

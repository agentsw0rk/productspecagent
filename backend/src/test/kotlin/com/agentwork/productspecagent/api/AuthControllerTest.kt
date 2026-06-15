package com.agentwork.productspecagent.api

import com.agentwork.productspecagent.storage.S3TestSupport
import com.jayway.jsonpath.JsonPath
import org.hamcrest.Matchers.emptyString
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.junit.jupiter.Testcontainers

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class AuthControllerTest : S3TestSupport() {

    @Autowired lateinit var mvc: MockMvc

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun props(registry: DynamicPropertyRegistry) {
            registry.add("auth.jwt.secret") { "0123456789abcdef0123456789abcdef0123456789abcdef" }
            registry.add("auth.cookie.secure") { "false" }
            registry.add("auth.admin-emails") { "admin@example.com" }
            registry.add("app.storage.bucket") { BUCKET }
            registry.add("app.storage.endpoint") { minio.s3URL }
            registry.add("app.storage.access-key") { minio.userName }
            registry.add("app.storage.secret-key") { minio.password }
            registry.add("app.storage.path-style-access") { "true" }
        }
    }

    private fun body(email: String, pw: String) =
        """{"email":"$email","password":"$pw"}"""

    private fun registerAndToken(email: String): String {
        val body = mvc.perform(post("/api/v1/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .content(body(email, "password123")))
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.token").isString)
            .andReturn()
            .response
            .contentAsString
        return JsonPath.read(body, "$.token")
    }

    @Test
    fun `register returns 201 with token and user info`() {
        mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content(body("alice@example.com", "password123")))
            .andExpect(status().isCreated)
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(jsonPath("$.token").isString)
            .andExpect(jsonPath("$.token").value(not(emptyString())))
            .andExpect(jsonPath("$.email").value("alice@example.com"))
            .andExpect(jsonPath("$.userId").exists())
            .andExpect(jsonPath("$.admin").value(false))
    }

    @Test
    fun `register duplicate email returns 409`() {
        mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content(body("dup@example.com", "password123")))
            .andExpect(status().isCreated)
        mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content(body("dup@example.com", "password123")))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.error").value("EMAIL_ALREADY_EXISTS"))
    }

    @Test
    fun `register weak password returns 400`() {
        mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content(body("a@b.com", "short")))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"))
    }

    @Test
    fun `login with valid credentials returns 200 with token`() {
        mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content(body("login@example.com", "password123")))
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content(body("login@example.com", "password123")))
            .andExpect(status().isOk)
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(jsonPath("$.token").isString)
            .andExpect(jsonPath("$.token").value(not(emptyString())))
            .andExpect(jsonPath("$.email").value("login@example.com"))
    }

    @Test
    fun `login with wrong password returns 401 with generic error`() {
        mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content(body("wp@example.com", "password123")))
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content(body("wp@example.com", "wrong-password")))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.error").value("INVALID_CREDENTIALS"))
    }

    @Test
    fun `login with unknown email returns 401`() {
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content(body("missing@example.com", "password123")))
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `me without bearer token returns 401`() {
        mvc.perform(get("/api/v1/auth/me"))
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `me with valid bearer token returns 200 with user info`() {
        val token = registerAndToken("me@example.com")
        mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer $token"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.email").value("me@example.com"))
            .andExpect(jsonPath("$.token").doesNotExist())
    }

    @Test
    fun `me returns admin true for configured admin email`() {
        val token = registerAndToken("admin@example.com")
        mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer $token"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.email").value("admin@example.com"))
            .andExpect(jsonPath("$.admin").value(true))
    }

    @Test
    fun `logout returns 204 without cookie mutation`() {
        val token = registerAndToken("lo@example.com")
        mvc.perform(post("/api/v1/auth/logout").header("Authorization", "Bearer $token"))
            .andExpect(status().isNoContent)
            .andExpect(header().doesNotExist("Set-Cookie"))
    }
}

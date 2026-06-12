package com.agentwork.productspecagent.api

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*

@SpringBootTest
@AutoConfigureMockMvc
class ConfigControllerTest {

    @Autowired lateinit var mockMvc: MockMvc

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun props(registry: DynamicPropertyRegistry) {
            registry.add("hackathon.lock.enabled") { "true" }
            registry.add("hackathon.lock.title") { "CVDT Launch Control" }
            registry.add("hackathon.lock.event-date") { "2026-06-26" }
            registry.add("hackathon.lock.target-at") { "2026-06-26T09:00:00+02:00" }
            registry.add("hackathon.lock.admin-bypass-enabled") { "false" }
        }
    }

    @Test
    fun `GET features returns graphmeshEnabled true and hackathon lock config`() {
        mockMvc.perform(get("/api/v1/config/features"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.graphmeshEnabled").value(true))
            .andExpect(jsonPath("$.hackathonLock.enabled").value(true))
            .andExpect(jsonPath("$.hackathonLock.title").value("CVDT Launch Control"))
            .andExpect(jsonPath("$.hackathonLock.eventDate").value("2026-06-26"))
            .andExpect(jsonPath("$.hackathonLock.targetAt").value("2026-06-26T09:00:00+02:00"))
            .andExpect(jsonPath("$.hackathonLock.adminBypassEnabled").value(false))
    }
}

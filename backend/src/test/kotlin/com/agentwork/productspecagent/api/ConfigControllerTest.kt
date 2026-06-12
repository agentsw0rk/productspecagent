package com.agentwork.productspecagent.api

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*

@SpringBootTest
@AutoConfigureMockMvc
class ConfigControllerTest {

    @Autowired lateinit var mockMvc: MockMvc

    @Test
    fun `GET features returns graphmeshEnabled true and hackathon lock config`() {
        mockMvc.perform(get("/api/v1/config/features"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.graphmeshEnabled").value(true))
            .andExpect(jsonPath("$.hackathonLock.enabled").value(false))
            .andExpect(jsonPath("$.hackathonLock.title").value("Hackathon CVDT"))
            .andExpect(jsonPath("$.hackathonLock.eventDate").value("2026-06-26"))
            .andExpect(jsonPath("$.hackathonLock.targetAt").value("2026-06-26T09:00+02:00"))
            .andExpect(jsonPath("$.hackathonLock.adminBypassEnabled").value(true))
    }
}

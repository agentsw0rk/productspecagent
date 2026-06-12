package com.agentwork.productspecagent.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.LocalDate
import java.time.OffsetDateTime

@ConfigurationProperties(prefix = "hackathon")
data class HackathonProperties(
    val lock: Lock = Lock(),
) {
    data class Lock(
        val enabled: Boolean = false,
        val title: String = "Hackathon CVDT",
        val eventDate: LocalDate = LocalDate.parse("2026-06-26"),
        val targetAt: OffsetDateTime = OffsetDateTime.parse("2026-06-26T09:00:00+02:00"),
        val adminBypassEnabled: Boolean = true,
    )
}

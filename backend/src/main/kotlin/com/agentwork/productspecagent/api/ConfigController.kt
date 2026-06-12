package com.agentwork.productspecagent.api

import com.agentwork.productspecagent.config.HackathonProperties
import com.agentwork.productspecagent.infrastructure.graphmesh.GraphMeshConfig
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.format.DateTimeFormatter

@RestController
@RequestMapping("/api/v1/config")
class ConfigController(
    private val graphMeshConfig: GraphMeshConfig,
    private val hackathon: HackathonProperties,
) {

    @GetMapping("/features")
    fun features(): Map<String, Any> = mapOf(
        "graphmeshEnabled" to graphMeshConfig.enabled,
        "hackathonLock" to mapOf(
            "enabled" to hackathon.lock.enabled,
            "title" to hackathon.lock.title,
            "eventDate" to hackathon.lock.eventDate.toString(),
            "targetAt" to hackathon.lock.targetAt.format(TARGET_AT_FORMATTER),
            "adminBypassEnabled" to hackathon.lock.adminBypassEnabled,
        ),
    )

    private companion object {
        val TARGET_AT_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX")
    }
}

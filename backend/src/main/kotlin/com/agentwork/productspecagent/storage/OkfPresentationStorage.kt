package com.agentwork.productspecagent.storage

import com.agentwork.productspecagent.domain.GraphPosition
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.springframework.stereotype.Service

@Service
class OkfPresentationStorage(private val objectStore: ObjectStore) {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    fun savePositions(projectId: String, positions: Map<String, GraphPosition>) {
        objectStore.put(
            presentationKey(projectId),
            json.encodeToString(positions).toByteArray(),
            "application/json",
        )
    }

    fun loadPositions(projectId: String): Map<String, GraphPosition> =
        objectStore.get(presentationKey(projectId))
            ?.toString(Charsets.UTF_8)
            ?.let(json::decodeFromString)
            ?: emptyMap()

    private fun presentationKey(projectId: String) = "projects/$projectId/okf/presentation.json"
}

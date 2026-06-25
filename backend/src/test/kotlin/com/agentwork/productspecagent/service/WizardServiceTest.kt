package com.agentwork.productspecagent.service

import com.agentwork.productspecagent.domain.Project
import com.agentwork.productspecagent.domain.ProjectStatus
import com.agentwork.productspecagent.domain.WizardData
import com.agentwork.productspecagent.domain.WizardStepData
import com.agentwork.productspecagent.storage.InMemoryObjectStore
import com.agentwork.productspecagent.storage.ProjectStorage
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class WizardServiceTest {

    private lateinit var storage: ProjectStorage
    private lateinit var service: WizardService

    @BeforeEach
    fun setUp() {
        storage = ProjectStorage(InMemoryObjectStore())
        service = WizardService(storage)
        storage.saveProject(
            Project(
                id = "project-1",
                name = "API Project",
                ownerId = "user-1",
                status = ProjectStatus.DRAFT,
                createdAt = "2026-06-25T00:00:00Z",
                updatedAt = "2026-06-25T00:00:00Z",
            )
        )
    }

    @Test
    fun `saveStepData normalizes feature scopes to arrays`() {
        val step = WizardStepData(
            fields = mapOf(
                "features" to JsonArray(
                    listOf(
                        JsonObject(
                            mapOf(
                                "id" to JsonPrimitive("feature-1"),
                                "title" to JsonPrimitive("Login"),
                                "scopes" to JsonPrimitive("BACKEND"),
                            )
                        )
                    )
                )
            )
        )

        val saved = service.saveStepData("project-1", "FEATURES", step)

        val feature = (saved.steps["FEATURES"]!!.fields["features"] as JsonArray).first() as JsonObject
        val scopes = feature["scopes"]
        assertTrue(scopes is JsonArray)
        assertEquals(listOf("BACKEND"), (scopes as JsonArray).map { (it as JsonPrimitive).content })
    }

    @Test
    fun `getWizardData returns feature scopes as arrays for legacy data`() {
        storage.saveWizardData(
            "project-1",
            WizardData(
                projectId = "project-1",
                steps = mapOf(
                    "FEATURES" to WizardStepData(
                        fields = mapOf(
                            "features" to JsonArray(
                                listOf(
                                    JsonObject(
                                        mapOf(
                                            "id" to JsonPrimitive("feature-1"),
                                            "title" to JsonPrimitive("Login"),
                                            "scopes" to JsonPrimitive("BACKEND"),
                                        )
                                    )
                                )
                            )
                        )
                    )
                ),
            )
        )

        val loaded = service.getWizardData("project-1")

        val feature = (loaded.steps["FEATURES"]!!.fields["features"] as JsonArray).first() as JsonObject
        val scopes = feature["scopes"]
        assertTrue(scopes is JsonArray)
        assertEquals(listOf("BACKEND"), (scopes as JsonArray).map { (it as JsonPrimitive).content })
    }
}

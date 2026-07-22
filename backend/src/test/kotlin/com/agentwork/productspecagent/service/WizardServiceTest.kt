package com.agentwork.productspecagent.service

import com.agentwork.productspecagent.domain.Project
import com.agentwork.productspecagent.domain.ProjectStatus
import com.agentwork.productspecagent.domain.WizardFeature
import com.agentwork.productspecagent.domain.WizardFeatureGraph
import com.agentwork.productspecagent.domain.WizardData
import com.agentwork.productspecagent.domain.WizardStepData
import com.agentwork.productspecagent.domain.okf.OkfMode
import com.agentwork.productspecagent.domain.okf.OkfProjectState
import com.agentwork.productspecagent.service.okf.OkfFeatureCodec
import com.agentwork.productspecagent.service.okf.OkfFeatureService
import com.agentwork.productspecagent.service.okf.OkfParser
import com.agentwork.productspecagent.service.okf.OkfProfileValidator
import com.agentwork.productspecagent.storage.InMemoryObjectStore
import com.agentwork.productspecagent.storage.OkfPresentationStorage
import com.agentwork.productspecagent.storage.OkfProjectionStorage
import com.agentwork.productspecagent.storage.OkfStateStorage
import com.agentwork.productspecagent.storage.ProjectStorage
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
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

    @Test
    fun `getWizardData returns string acceptance criteria as an array for legacy data`() {
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
                                            "acceptanceCriteria" to JsonPrimitive("Login succeeds"),
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
        val criteria = feature["acceptanceCriteria"] as JsonArray
        assertEquals(
            JsonObject(
                mapOf(
                    "id" to JsonPrimitive("feature-1-acceptance-criterion-1"),
                    "text" to JsonPrimitive("Login succeeds"),
                )
            ),
            criteria.single(),
        )
    }

    @Test
    fun `active feature writes create a pending changeset without replacing legacy graph fields`() {
        val store = InMemoryObjectStore()
        val projectStorage = ProjectStorage(store)
        projectStorage.saveProject(
            Project(
                id = "active-project",
                name = "Active project",
                ownerId = "user-1",
                status = ProjectStatus.DRAFT,
                createdAt = "2026-07-20T00:00:00Z",
                updatedAt = "2026-07-20T00:00:00Z",
            ),
        )
        val legacyFeature = featureJson("Legacy login")
        projectStorage.saveWizardData(
            "active-project",
            WizardData(
                "active-project",
                mapOf(
                    "FEATURES" to WizardStepData(
                        fields = mapOf(
                            "features" to JsonArray(listOf(legacyFeature)),
                            "edges" to JsonArray(emptyList()),
                            "note" to JsonPrimitive("legacy note"),
                        ),
                    ),
                    "IDEA" to WizardStepData(fields = mapOf("idea" to JsonPrimitive("Keep me"))),
                ),
            ),
        )
        val parser = OkfParser()
        val codec = OkfFeatureCodec(parser)
        val stateStorage = OkfStateStorage(store)
        val projectionStorage = OkfProjectionStorage(store, parser)
        val presentationStorage = OkfPresentationStorage(store)
        val confirmed = WizardFeatureGraph(
            listOf(WizardFeature(ACTIVE_FEATURE_ID, "Login", description = "Users can sign in.")),
        )
        val files = codec.encode(confirmed, null, OffsetDateTime.parse("2026-07-20T00:00:00Z"))
            .documents.associate { it.path to parser.render(it) }
        projectionStorage.replaceRawFiles("active-project", files)
        projectionStorage.saveFeatureProjection("active-project", confirmed)
        stateStorage.saveState(
            OkfProjectState("active-project", mode = OkfMode.OKF_ACTIVE, baseCommit = "c1"),
        )
        val okf = OkfFeatureService(
            stateStorage,
            projectionStorage,
            presentationStorage,
            parser,
            codec,
            OkfProfileValidator(),
            Clock.fixed(Instant.parse("2026-07-20T12:00:00Z"), ZoneOffset.UTC),
        )
        val activeService = WizardService(projectStorage, okf)

        val saved = activeService.saveStepData(
            "active-project",
            "FEATURES",
            WizardStepData(
                fields = mapOf(
                    "features" to JsonArray(listOf(featureJson("Sign in"))),
                    "edges" to JsonArray(emptyList()),
                    "note" to JsonPrimitive("updated note"),
                ),
                completedAt = "2026-07-20T12:00:00Z",
            ),
        )

        assertEquals("Sign in", featureTitle(saved))
        assertEquals("Keep me", (saved.steps["IDEA"]!!.fields["idea"] as JsonPrimitive).content)
        val persisted = projectStorage.loadWizardData("active-project")!!
        assertEquals("Legacy login", featureTitle(persisted))
        assertEquals("updated note", (persisted.steps["FEATURES"]!!.fields["note"] as JsonPrimitive).content)
        assertEquals("2026-07-20T12:00:00Z", persisted.steps["FEATURES"]!!.completedAt)
        assertTrue(stateStorage.loadPendingChangeset("active-project") != null)
        val projected = WizardService(projectStorage, okf).getWizardData("active-project")
        assertEquals("Sign in", featureTitle(projected))
        val projectedFeature = (projected.steps["FEATURES"]!!.fields["features"] as JsonArray).single() as JsonObject
        assertEquals(
            JsonObject(mapOf("x" to JsonPrimitive(0.0), "y" to JsonPrimitive(0.0))),
            projectedFeature["position"],
        )

        activeService.saveWizardData(
            "active-project",
            WizardData(
                "active-project",
                mapOf("IDEA" to WizardStepData(fields = mapOf("idea" to JsonPrimitive("Updated idea")))),
            ),
        )
        assertEquals("Legacy login", featureTitle(projectStorage.loadWizardData("active-project")!!))
    }

    private fun featureJson(title: String) = JsonObject(
        mapOf(
            "id" to JsonPrimitive(ACTIVE_FEATURE_ID),
            "title" to JsonPrimitive(title),
            "description" to JsonPrimitive("Users can sign in."),
            "scopes" to JsonArray(emptyList()),
            "acceptanceCriteria" to JsonArray(emptyList()),
        ),
    )

    private fun featureTitle(data: WizardData): String {
        val feature = (data.steps["FEATURES"]!!.fields["features"] as JsonArray).single() as JsonObject
        return (feature["title"] as JsonPrimitive).content
    }

    private companion object {
        const val ACTIVE_FEATURE_ID = "11111111-1111-4111-8111-111111111111"
    }
}

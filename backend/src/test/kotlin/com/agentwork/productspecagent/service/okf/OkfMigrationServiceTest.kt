package com.agentwork.productspecagent.service.okf

import com.agentwork.productspecagent.domain.Project
import com.agentwork.productspecagent.domain.ProjectStatus
import com.agentwork.productspecagent.domain.WizardData
import com.agentwork.productspecagent.domain.WizardStepData
import com.agentwork.productspecagent.domain.okf.OkfBundle
import com.agentwork.productspecagent.domain.okf.OkfChangesetKind
import com.agentwork.productspecagent.domain.okf.OkfGitConnection
import com.agentwork.productspecagent.domain.okf.OkfIssueSeverity
import com.agentwork.productspecagent.domain.okf.OkfMode
import com.agentwork.productspecagent.service.WizardService
import com.agentwork.productspecagent.storage.InMemoryObjectStore
import com.agentwork.productspecagent.storage.OkfStateStorage
import com.agentwork.productspecagent.storage.ProjectStorage
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class OkfMigrationServiceTest {

    @Test
    fun `legacy ids map deterministically and remain discoverable through a roundtrip`() {
        val fixture = fixture()
        fixture.seed(
            features = JsonArray(
                listOf(
                    feature("feature-a", "Login", "Users can sign in.", JsonPrimitive("Login succeeds")),
                    feature(VALID_FEATURE_ID, "Dashboard", "Users can view a dashboard."),
                ),
            ),
            edges = JsonArray(
                listOf(
                    JsonObject(
                        mapOf(
                            "id" to JsonPrimitive("edge-a"),
                            "from" to JsonPrimitive("feature-a"),
                            "to" to JsonPrimitive(VALID_FEATURE_ID),
                        ),
                    ),
                ),
            ),
        )

        val first = fixture.service.dryRun(PROJECT_ID)
        val second = fixture.service.dryRun(PROJECT_ID)

        assertEquals(first, second)
        val expectedFeatureId = migratedUuid("feature", "feature-a")
        val migratedDocument = first.documents.single { it.sourceId == "feature-a" }
        assertEquals(expectedFeatureId, migratedDocument.documentId)
        assertTrue("id" in migratedDocument.normalizations)
        assertTrue("acceptanceCriteria" in migratedDocument.normalizations)
        assertEquals(VALID_FEATURE_ID, first.documents.single { it.sourceId == VALID_FEATURE_ID }.documentId)

        val bundle = bundleFrom(first, fixture.parser)
        val document = bundle.documents.single { it.frontmatter["id"] == expectedFeatureId }
        assertEquals(listOf("feature-a"), document.frontmatter["legacy_ids"])
        assertEquals("2026-07-20T00:00Z", document.frontmatter["timestamp"])
        val graph = fixture.codec.decode(bundle, emptyMap()).graph
        val criterion = graph.features.single { it.id == expectedFeatureId }.acceptanceCriteria.single()
        assertEquals("Login succeeds", criterion.text)
        assertEquals(migratedUuid("criterion", "feature-a-acceptance-criterion-1"), criterion.id)
        val edge = graph.edges.single()
        assertEquals(migratedUuid("edge", "edge-a"), edge.id)
        assertEquals(expectedFeatureId, edge.from)
        assertEquals(VALID_FEATURE_ID, edge.to)
        assertNull(fixture.stateStorage.loadPendingChangeset(PROJECT_ID))
        assertEquals(OkfMode.LEGACY, fixture.stateStorage.loadState(PROJECT_ID).mode)
        verify(fixture.git, never()).commitAndPush(any(), any(), any(), any(), any())
    }

    @Test
    fun `empty descriptions block prepare without changing migration state`() {
        val fixture = fixture()
        fixture.seed(JsonArray(listOf(feature("feature-a", "Login", ""))))

        val preview = fixture.service.dryRun(PROJECT_ID)

        assertTrue(preview.issues.any {
            it.severity == OkfIssueSeverity.ERROR && it.field == "description"
        })
        assertThrows(OkfMigrationException::class.java) { fixture.service.prepare(PROJECT_ID) }
        assertNull(fixture.stateStorage.loadPendingChangeset(PROJECT_ID))
        assertEquals(OkfMode.LEGACY, fixture.stateStorage.loadState(PROJECT_ID).mode)
    }

    @Test
    fun `slug collisions are reported while paths remain unique`() {
        val fixture = fixture()
        val features = listOf(
            feature("feature-a", "Über View", "Users can view the first page."),
            feature("feature-b", "uber-view", "Users can view the second page."),
        )
        fixture.seed(JsonArray(features))

        val preview = fixture.service.dryRun(PROJECT_ID)
        fixture.seed(JsonArray(features.reversed()))
        val reordered = fixture.service.dryRun(PROJECT_ID)

        assertTrue(preview.issues.any {
            it.severity == OkfIssueSeverity.WARNING && it.field == "path" && "share slug" in it.message
        })
        assertEquals(2, preview.documents.map { it.path }.distinct().size)
        assertEquals(preview, reordered)
    }

    @Test
    fun `edge references use canonical UUID identity independent of case`() {
        val fixture = fixture()
        val uppercaseId = VALID_FEATURE_ID.uppercase()
        fixture.seed(
            features = JsonArray(
                listOf(
                    feature(uppercaseId, "Login", "Users can sign in."),
                    feature("feature-b", "Dashboard", "Users can view a dashboard."),
                ),
            ),
            edges = JsonArray(
                listOf(
                    JsonObject(
                        mapOf(
                            "id" to JsonPrimitive("edge-a"),
                            "from" to JsonPrimitive(VALID_FEATURE_ID),
                            "to" to JsonPrimitive("feature-b"),
                        ),
                    ),
                ),
            ),
        )

        val preview = fixture.service.dryRun(PROJECT_ID)

        assertTrue(preview.issues.none { it.severity == OkfIssueSeverity.ERROR })
        val graph = fixture.codec.decode(bundleFrom(preview, fixture.parser), emptyMap()).graph
        assertEquals(VALID_FEATURE_ID, graph.edges.single().from)
    }

    @Test
    fun `prepare stores one migration changeset and stops before cutover`() {
        val fixture = fixture()
        fixture.seed(JsonArray(listOf(feature("feature-a", "Login", "Users can sign in."))))
        val preview = fixture.service.dryRun(PROJECT_ID)

        val prepared = fixture.service.prepare(PROJECT_ID)
        val repeated = fixture.service.prepare(PROJECT_ID)
        val afterRestart = fixture.newService(Instant.parse("2026-07-21T12:00:00Z")).prepare(PROJECT_ID)

        assertEquals(prepared, repeated)
        assertEquals(prepared, afterRestart)
        assertEquals(OkfChangesetKind.MIGRATION, prepared.kind)
        assertEquals(preview.baseCommit, prepared.baseCommit)
        assertEquals(preview.operations, prepared.operations)
        assertEquals(preview.issues, prepared.issues)
        assertEquals(prepared, fixture.stateStorage.loadPendingChangeset(PROJECT_ID))
        val state = fixture.stateStorage.loadState(PROJECT_ID)
        assertEquals(OkfMode.MIGRATION_READY, state.mode)
        assertEquals(prepared.id, state.pendingChangesetId)
    }

    @Test
    fun `dry run uses canonical or base snapshot without pushing`() {
        val fixture = fixture(baseCommit = null)
        fixture.seed(JsonArray(listOf(feature("feature-a", "Login", "Users can sign in."))))

        val preview = fixture.service.dryRun(PROJECT_ID)

        assertEquals("c1", preview.baseCommit)
        assertTrue(preview.operations.isNotEmpty())
        verify(fixture.git, never()).commitAndPush(any(), any(), any(), any(), any())
    }

    private fun fixture(baseCommit: String? = null): Fixture {
        val objectStore = InMemoryObjectStore()
        val projectStorage = ProjectStorage(objectStore)
        projectStorage.saveProject(
            Project(
                id = PROJECT_ID,
                name = "Migration project",
                ownerId = "user-1",
                status = ProjectStatus.DRAFT,
                createdAt = "2026-07-20T00:00:00Z",
                updatedAt = "2026-07-20T00:00:00Z",
            ),
        )
        val wizardService = WizardService(projectStorage)
        val stateStorage = OkfStateStorage(objectStore)
        stateStorage.saveState(
            com.agentwork.productspecagent.domain.okf.OkfProjectState(
                projectId = PROJECT_ID,
                mode = OkfMode.LEGACY,
                connection = CONNECTION,
                baseCommit = baseCommit,
            ),
        )
        val git = mock<OkfGitClient>()
        whenever(git.fetch(PROJECT_ID, CONNECTION)).thenReturn(
            OkfGitSnapshot("c1", "main", emptyMap()),
        )
        val parser = OkfParser()
        val codec = OkfFeatureCodec(parser)
        val service = OkfMigrationService(
            wizardService,
            git,
            stateStorage,
            projectStorage,
            codec,
            parser,
            OkfProfileValidator(),
            Clock.fixed(Instant.parse("2026-07-20T12:00:00Z"), ZoneOffset.UTC),
        )
        return Fixture(projectStorage, git, stateStorage, parser, codec, service)
    }

    private fun Fixture.newService(now: Instant): OkfMigrationService = OkfMigrationService(
        WizardService(projectStorage),
        git,
        stateStorage,
        projectStorage,
        codec,
        parser,
        OkfProfileValidator(),
        Clock.fixed(now, ZoneOffset.UTC),
    )

    private fun Fixture.seed(features: JsonArray, edges: JsonArray = JsonArray(emptyList())) {
        projectStorage.saveWizardData(
            PROJECT_ID,
            WizardData(
                projectId = PROJECT_ID,
                steps = mapOf(
                    "FEATURES" to WizardStepData(
                        fields = mapOf("features" to features, "edges" to edges),
                    ),
                ),
            ),
        )
    }

    private fun feature(
        id: String,
        title: String,
        description: String,
        acceptanceCriteria: kotlinx.serialization.json.JsonElement = JsonArray(emptyList()),
    ) = JsonObject(
        mapOf(
            "id" to JsonPrimitive(id),
            "title" to JsonPrimitive(title),
            "description" to JsonPrimitive(description),
            "acceptanceCriteria" to acceptanceCriteria,
        ),
    )

    private fun bundleFrom(
        preview: com.agentwork.productspecagent.domain.okf.OkfMigrationPreview,
        parser: OkfParser,
    ): OkfBundle = OkfBundle(
        preview.operations.mapNotNull { operation ->
            operation.content?.let { parser.parse(operation.path, it) }
        },
    )

    private fun migratedUuid(kind: String, sourceId: String): String = UUID.nameUUIDFromBytes(
        "productspecagent:$PROJECT_ID:$kind:$sourceId".toByteArray(StandardCharsets.UTF_8),
    ).toString()

    private data class Fixture(
        val projectStorage: ProjectStorage,
        val git: OkfGitClient,
        val stateStorage: OkfStateStorage,
        val parser: OkfParser,
        val codec: OkfFeatureCodec,
        val service: OkfMigrationService,
    )

    private companion object {
        const val PROJECT_ID = "project-1"
        const val VALID_FEATURE_ID = "11111111-1111-4111-8111-111111111111"
        val CONNECTION = OkfGitConnection("https://git.example/repo.git")
    }
}

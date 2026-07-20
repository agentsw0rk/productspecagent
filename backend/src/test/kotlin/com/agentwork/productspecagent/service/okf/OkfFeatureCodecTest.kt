package com.agentwork.productspecagent.service.okf

import com.agentwork.productspecagent.domain.AcceptanceCriterion
import com.agentwork.productspecagent.domain.FeatureScope
import com.agentwork.productspecagent.domain.GraphPosition
import com.agentwork.productspecagent.domain.WizardFeature
import com.agentwork.productspecagent.domain.WizardFeatureEdge
import com.agentwork.productspecagent.domain.WizardFeatureGraph
import com.agentwork.productspecagent.domain.okf.OkfBundle
import com.agentwork.productspecagent.domain.okf.OkfDocument
import com.agentwork.productspecagent.domain.okf.OkfIssueSeverity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime

class OkfFeatureCodecTest {

    private val parser = OkfParser()
    private val codec = OkfFeatureCodec(parser)
    private val timestamp = OffsetDateTime.parse("2026-07-20T10:15:30+02:00")

    @Test
    fun `roundtrip preserves features criteria scopes fields edges ids and supplied positions`() {
        val graph = sampleGraph()

        val encoded = codec.encode(graph, existing = null, timestamp = timestamp)
        val decoded = codec.decode(
            encoded,
            positions = mapOf(
                LOGIN_ID to GraphPosition(40.0, 80.0),
                DASHBOARD_ID to GraphPosition(360.0, 80.0),
            ),
        )

        assertTrue(decoded.issues.isEmpty(), decoded.issues.toString())
        assertEquals(
            graph.features.map { feature ->
                feature.copy(position = decoded.graph.features.single { it.id == feature.id }.position)
            },
            decoded.graph.features,
        )
        assertEquals(graph.edges, decoded.graph.edges)
        assertEquals(GraphPosition(40.0, 80.0), decoded.graph.features.single { it.id == LOGIN_ID }.position)
        assertEquals(GraphPosition(360.0, 80.0), decoded.graph.features.single { it.id == DASHBOARD_ID }.position)

        val login = encoded.byPath.getValue("features/login.md")
        val dashboard = encoded.byPath.getValue("features/dashboard.md")
        assertEquals(LOGIN_ID, login.frontmatter["id"])
        assertEquals(listOf("FRONTEND", "BACKEND"), login.frontmatter["scopes"])
        assertEquals(linkedMapOf("api" to "/login", "screen" to "login"), login.frontmatter["scope_fields"])
        assertTrue(login.body.contains("<!-- okf:id=$LOGIN_CRITERION_ID -->"))
        assertTrue(dashboard.body.contains("[Login](login.md)"))
        assertTrue(dashboard.body.contains("<!-- okf:id=$EDGE_ID -->"))
    }

    @Test
    fun `encode creates deterministic frontmatter indexes empty sections and no log`() {
        val graph = WizardFeatureGraph(
            features = listOf(
                WizardFeature(id = DASHBOARD_ID, title = "Dashboard"),
                WizardFeature(id = LOGIN_ID, title = "Login"),
            ),
        )

        val first = codec.encode(graph, existing = null, timestamp = timestamp)
        val second = codec.encode(graph, existing = null, timestamp = timestamp)

        assertEquals(first, second)
        assertEquals(
            listOf("index.md", "features/index.md", "features/dashboard.md", "features/login.md"),
            first.documents.map { it.path },
        )
        assertTrue(first.byPath.getValue("index.md").frontmatter.isEmpty())
        assertTrue(first.byPath.getValue("features/index.md").frontmatter.isEmpty())
        assertEquals(
            listOf("type", "id", "title", "description", "tags", "timestamp", "scopes", "scope_fields", "legacy_ids"),
            first.byPath.getValue("features/login.md").frontmatter.keys.toList(),
        )
        assertEquals(
            "## Problem\n\n\n\n## Outcome\n\n\n\n## Acceptance Criteria\n\n\n\n## Relationships\n",
            first.byPath.getValue("features/login.md").body,
        )
        assertTrue(first.byPath.getValue("index.md").body.contains("[Dashboard](features/dashboard.md)"))
        assertTrue(first.byPath.getValue("features/index.md").body.contains("[Dashboard](dashboard.md)"))
        assertFalse(first.documents.any { it.path == "log.md" || it.path.endsWith("/log.md") })
    }

    @Test
    fun `encode preserves unknown frontmatter and list metadata while title rename changes only path`() {
        val existing = OkfBundle(
            listOf(
                OkfDocument(
                    path = "features/old-login.md",
                    frontmatter = linkedMapOf(
                        "type" to "feature",
                        "x-owner" to "identity-team",
                        "id" to LOGIN_ID,
                        "title" to "Old Login",
                        "description" to "Old description.",
                        "tags" to listOf("security"),
                        "timestamp" to "2025-01-01T00:00:00Z",
                        "legacy_ids" to listOf("LOGIN-7"),
                    ),
                    body = "Old body",
                ),
                OkfDocument(
                    path = "architecture/system.md",
                    frontmatter = linkedMapOf("type" to "architecture-overview"),
                    body = "Architecture",
                ),
            ),
        )
        val renamed = WizardFeatureGraph(
            features = listOf(
                WizardFeature(
                    id = LOGIN_ID,
                    title = "Sign In",
                    description = "Users authenticate securely.",
                ),
            ),
        )

        val encoded = codec.encode(renamed, existing, timestamp)
        val feature = encoded.byPath.getValue("features/sign-in.md")

        assertFalse(encoded.byPath.containsKey("features/old-login.md"))
        assertEquals(LOGIN_ID, feature.frontmatter["id"])
        assertEquals("identity-team", feature.frontmatter["x-owner"])
        assertEquals(listOf("security"), feature.frontmatter["tags"])
        assertEquals(listOf("LOGIN-7"), feature.frontmatter["legacy_ids"])
        assertTrue(feature.frontmatter.keys.indexOf("x-owner") < feature.frontmatter.keys.indexOf("id"))
        assertEquals("Architecture", encoded.byPath.getValue("architecture/system.md").body)
    }

    @Test
    fun `decode gives missing criterion and edge ids stable fallbacks with warnings`() {
        val login = featureDocument(
            path = "features/login.md",
            id = LOGIN_ID,
            title = "Login",
            body = """
                ## Problem

                Users need access.

                ## Outcome

                ## Acceptance Criteria

                - [ ] Valid credentials succeed

                ## Relationships
            """.trimIndent(),
        )
        val dashboard = featureDocument(
            path = "features/dashboard.md",
            id = DASHBOARD_ID,
            title = "Dashboard",
            body = """
                ## Problem

                ## Outcome

                ## Acceptance Criteria

                ## Relationships

                - depends-on: [Login](login.md)
            """.trimIndent(),
        )
        val bundle = OkfBundle(listOf(login, dashboard))

        val first = codec.decode(bundle, emptyMap())
        val second = codec.decode(bundle, emptyMap())

        assertEquals(first.graph, second.graph)
        assertEquals(2, first.issues.count { it.severity == OkfIssueSeverity.WARNING })
        assertTrue(first.issues.any { it.path == login.path && it.field == "acceptanceCriteria" })
        assertTrue(first.issues.any { it.path == dashboard.path && it.field == "relationships" })
        assertTrue(first.graph.features.single { it.id == LOGIN_ID }.acceptanceCriteria.single().id.isNotBlank())
        assertTrue(first.graph.edges.single().id.isNotBlank())
        assertEquals(GraphPosition(), first.graph.features.single { it.id == LOGIN_ID }.position)
    }

    @Test
    fun `slug collisions use the stable id suffix`() {
        val graph = WizardFeatureGraph(
            features = listOf(
                WizardFeature(id = LOGIN_ID, title = "Shared Title"),
                WizardFeature(id = DASHBOARD_ID, title = "shared title"),
            ),
        )

        val encoded = codec.encode(graph, existing = null, timestamp = timestamp)

        assertTrue(encoded.byPath.containsKey("features/shared-title.md"))
        assertTrue(encoded.byPath.containsKey("features/shared-title-${DASHBOARD_ID.take(8)}.md"))
    }

    @Test
    fun `decode ignores indexes and non-feature concepts and reports malformed feature documents`() {
        val malformed = featureDocument(
            path = "features/broken.md",
            id = "not-a-uuid",
            title = "Broken",
            body = "## Acceptance Criteria",
        )
        val bundle = OkfBundle(
            listOf(
                OkfDocument("index.md", linkedMapOf(), "# Index"),
                OkfDocument(
                    "concepts/note.md",
                    linkedMapOf("type" to "decision", "id" to LOGIN_ID, "title" to "Note"),
                    "Decision",
                ),
                malformed,
            ),
        )

        val decoded = codec.decode(bundle, emptyMap())

        assertTrue(decoded.graph.features.isEmpty())
        assertTrue(decoded.graph.edges.isEmpty())
        assertTrue(
            decoded.issues.any {
                it.severity == OkfIssueSeverity.ERROR &&
                    it.path == malformed.path &&
                    it.field == "id"
            },
        )
    }

    @Test
    fun `encode ignores graph positions`() {
        val firstGraph = WizardFeatureGraph(
            features = listOf(WizardFeature(id = LOGIN_ID, title = "Login", position = GraphPosition(10.0, 20.0))),
        )
        val movedGraph = WizardFeatureGraph(
            features = listOf(WizardFeature(id = LOGIN_ID, title = "Login", position = GraphPosition(900.0, 700.0))),
        )

        assertEquals(
            codec.encode(firstGraph, existing = null, timestamp = timestamp),
            codec.encode(movedGraph, existing = null, timestamp = timestamp),
        )
    }

    private fun sampleGraph() = WizardFeatureGraph(
        features = listOf(
            WizardFeature(
                id = LOGIN_ID,
                title = "Login",
                scopes = linkedSetOf(FeatureScope.FRONTEND, FeatureScope.BACKEND),
                description = "Users authenticate with valid credentials.",
                scopeFields = linkedMapOf("api" to "/login", "screen" to "login"),
                acceptanceCriteria = listOf(
                    AcceptanceCriterion(LOGIN_CRITERION_ID, "Valid credentials succeed"),
                ),
                position = GraphPosition(12.0, 24.0),
            ),
            WizardFeature(
                id = DASHBOARD_ID,
                title = "Dashboard",
                scopes = setOf(FeatureScope.FRONTEND),
                description = "Users see their account overview.",
                acceptanceCriteria = listOf(
                    AcceptanceCriterion(DASHBOARD_CRITERION_ID, "Account data is visible"),
                ),
                position = GraphPosition(420.0, 24.0),
            ),
        ),
        edges = listOf(WizardFeatureEdge(EDGE_ID, LOGIN_ID, DASHBOARD_ID)),
    )

    private fun featureDocument(
        path: String,
        id: String,
        title: String,
        body: String,
    ) = OkfDocument(
        path = path,
        frontmatter = linkedMapOf(
            "type" to "feature",
            "id" to id,
            "title" to title,
            "description" to "",
            "tags" to emptyList<String>(),
            "timestamp" to timestamp.toString(),
            "scopes" to emptyList<String>(),
            "scope_fields" to emptyMap<String, String>(),
            "legacy_ids" to emptyList<String>(),
        ),
        body = body,
    )

    private companion object {
        const val LOGIN_ID = "11111111-1111-4111-8111-111111111111"
        const val DASHBOARD_ID = "22222222-2222-4222-8222-222222222222"
        const val LOGIN_CRITERION_ID = "33333333-3333-4333-8333-333333333333"
        const val DASHBOARD_CRITERION_ID = "44444444-4444-4444-8444-444444444444"
        const val EDGE_ID = "55555555-5555-4555-8555-555555555555"
    }
}

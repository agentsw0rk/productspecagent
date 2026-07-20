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
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.assertThrows
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
            graph.features.associate { feature ->
                feature.id to feature.copy(position = decoded.graph.features.single { it.id == feature.id }.position)
            },
            decoded.graph.features.associateBy { it.id },
        )
        assertEquals(graph.edges, decoded.graph.edges)
        assertEquals(GraphPosition(40.0, 80.0), decoded.graph.features.single { it.id == LOGIN_ID }.position)
        assertEquals(GraphPosition(360.0, 80.0), decoded.graph.features.single { it.id == DASHBOARD_ID }.position)

        val login = encoded.byPath.getValue("features/login.md")
        val dashboard = encoded.byPath.getValue("features/dashboard.md")
        assertEquals(LOGIN_ID, login.frontmatter["id"])
        assertEquals(listOf("FRONTEND", "BACKEND"), login.frontmatter["scopes"])
        assertEquals(linkedMapOf("api" to "/login", "screen" to "login"), login.frontmatter["scope_fields"])
        assertEquals("Users authenticate with valid credentials.", login.frontmatter["description"])
        assertEquals("", sectionContent(login.body, "Problem"))
        assertEquals("", sectionContent(login.body, "Outcome"))
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
        assertEquals(
            linkedMapOf("okf_version" to "0.1", "profile" to "productspecagent/1"),
            first.byPath.getValue("index.md").frontmatter,
        )
        assertFalse(first.byPath.getValue("index.md").frontmatter.containsKey("type"))
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
    fun `bundle rejects duplicate document paths`() {
        val exception = assertThrows<IllegalArgumentException> {
            OkfBundle(
                listOf(
                    OkfDocument("features/foo.md", linkedMapOf(), "First"),
                    OkfDocument("features/foo.md", linkedMapOf(), "Second"),
                ),
            )
        }

        assertTrue(exception.message.orEmpty().contains("features/foo.md"))
    }

    @Test
    fun `encode reserves preserved document and generated index paths`() {
        val preserved = OkfDocument(
            path = "features/foo.md",
            frontmatter = linkedMapOf("type" to "decision"),
            body = "Preserved decision",
        )
        val encoded = codec.encode(
            WizardFeatureGraph(features = listOf(WizardFeature(LOGIN_ID, "Foo"))),
            existing = OkfBundle(listOf(preserved)),
            timestamp = timestamp,
        )

        val feature = encoded.documents.single { it.frontmatter["type"] == "feature" }
        assertEquals("Preserved decision", encoded.byPath.getValue("features/foo.md").body)
        assertEquals("features/foo-${LOGIN_ID.take(8)}.md", feature.path)
        assertEquals(encoded.documents.size, encoded.documents.map { it.path }.toSet().size)

        val indexFeature = codec.encode(
            WizardFeatureGraph(features = listOf(WizardFeature(DASHBOARD_ID, "Index"))),
            existing = null,
            timestamp = timestamp,
        ).documents.single { it.frontmatter["type"] == "feature" }
        assertEquals("features/index-${DASHBOARD_ID.take(8)}.md", indexFeature.path)
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
    fun `encode preserves authored and fenced sections while replacing only managed sections`() {
        val existingBody = """
            ## Problem

            Authored problem before fence.

            ```markdown
            ## Acceptance Criteria
            - [ ] Example inside fence
            ```

            Authored problem after fence.

            ## Outcome

            Authored outcome.

            ## Acceptance Criteria

            - [ ] Old criterion <!-- okf:id=$DASHBOARD_CRITERION_ID -->

            ## Citations

            - [Reference](https://example.com)

            ## Extension

            Extension text.

            ## Relationships

            - depends-on: [Old](old.md) <!-- okf:id=$EDGE_ID -->
        """.trimIndent()
        val existing = featureDocument("features/login.md", LOGIN_ID, "Login", existingBody)
        val graph = WizardFeatureGraph(
            features = listOf(
                WizardFeature(
                    id = LOGIN_ID,
                    title = "Login",
                    acceptanceCriteria = listOf(AcceptanceCriterion(LOGIN_CRITERION_ID, "New criterion")),
                ),
            ),
        )

        val encoded = codec.encode(graph, OkfBundle(listOf(existing)), timestamp)
        val body = encoded.documents.single { it.frontmatter["type"] == "feature" }.body

        assertTrue(body.contains("Authored problem before fence."))
        assertTrue(body.contains("## Acceptance Criteria\n- [ ] Example inside fence"))
        assertTrue(body.contains("Authored problem after fence."))
        assertTrue(body.contains("## Outcome\n\nAuthored outcome."))
        assertTrue(body.contains("## Citations\n\n- [Reference](https://example.com)"))
        assertTrue(body.contains("## Extension\n\nExtension text."))
        assertTrue(body.contains("New criterion <!-- okf:id=$LOGIN_CRITERION_ID -->"))
        assertFalse(body.contains("Old criterion"))
        assertFalse(body.contains("[Old](old.md)"))
    }

    @Test
    fun `encode reuses uppercase existing UUID identity and preserves authored data`() {
        val original = featureDocument(
            path = "features/login.md",
            id = LOGIN_ID.uppercase(),
            title = "Login",
            body = "## Problem\n\nAuthored.\n\n## Acceptance Criteria\n\n\n\n## Relationships\n",
        )
        val existing = original.copy(
            frontmatter = LinkedHashMap(original.frontmatter).apply { this["x-owner"] = "identity" },
        )

        val encoded = codec.encode(
            WizardFeatureGraph(features = listOf(WizardFeature(LOGIN_ID, "Login"))),
            OkfBundle(listOf(existing)),
            timestamp,
        )
        val feature = encoded.documents.single { it.frontmatter["type"] == "feature" }

        assertEquals("identity", feature.frontmatter["x-owner"])
        assertEquals(LOGIN_ID, feature.frontmatter["id"])
        assertTrue(feature.body.contains("## Problem\n\nAuthored."))
    }

    @Test
    fun `encode preserves existing log documents without generating new ones`() {
        val rootLog = OkfDocument("log.md", linkedMapOf(), "Root log")
        val nestedLog = OkfDocument("notes/log.md", linkedMapOf(), "Nested log")

        val encoded = codec.encode(
            WizardFeatureGraph(features = listOf(WizardFeature(LOGIN_ID, "Login"))),
            OkfBundle(listOf(nestedLog, rootLog)),
            timestamp,
        )

        assertEquals("Root log", encoded.byPath.getValue("log.md").body)
        assertEquals("Nested log", encoded.byPath.getValue("notes/log.md").body)
        assertEquals(2, encoded.documents.count { it.path == "log.md" || it.path.endsWith("/log.md") })
    }

    @Test
    fun `encode preserves timestamp on no-op and updates it for managed semantic changes`() {
        val originalTimestamp = OffsetDateTime.parse("2026-07-19T08:00:00Z")
        val originalGraph = WizardFeatureGraph(
            features = listOf(
                WizardFeature(
                    LOGIN_ID,
                    "Login",
                    acceptanceCriteria = listOf(AcceptanceCriterion(LOGIN_CRITERION_ID, "Original criterion")),
                ),
            ),
        )
        val original = codec.encode(originalGraph, null, originalTimestamp)

        val noOp = codec.encode(originalGraph, original, timestamp)
        assertEquals(originalTimestamp.toString(), noOp.byPath.values.single { it.frontmatter["type"] == "feature" }.frontmatter["timestamp"])

        val changedGraph = originalGraph.copy(
            features = listOf(
                originalGraph.features.single().copy(
                    acceptanceCriteria = listOf(AcceptanceCriterion(LOGIN_CRITERION_ID, "Changed criterion")),
                ),
            ),
        )
        val changed = codec.encode(changedGraph, noOp, timestamp)
        assertEquals(timestamp.toString(), changed.byPath.values.single { it.frontmatter["type"] == "feature" }.frontmatter["timestamp"])
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
    fun `decode assigns distinct stable ids to repeated missing criteria and edges`() {
        val login = featureDocument(
            "features/login.md",
            LOGIN_ID,
            "Login",
            "## Acceptance Criteria\n\n- [ ] Repeated\n- [ ] Repeated\n\n## Relationships\n",
        )
        val dashboard = featureDocument(
            "features/dashboard.md",
            DASHBOARD_ID,
            "Dashboard",
            "## Acceptance Criteria\n\n## Relationships\n\n- depends-on: [Login](login.md)\n- depends-on: [Login](login.md)\n",
        )
        val bundle = OkfBundle(listOf(login, dashboard))

        val first = codec.decode(bundle, emptyMap())
        val second = codec.decode(bundle, emptyMap())
        val criterionIds = first.graph.features.single { it.id == LOGIN_ID }.acceptanceCriteria.map { it.id }
        val edgeIds = first.graph.edges.map { it.id }

        assertEquals(first.graph, second.graph)
        assertEquals(2, criterionIds.toSet().size)
        assertEquals(2, edgeIds.toSet().size)
        assertEquals(4, first.issues.count { it.severity == OkfIssueSeverity.WARNING })
    }

    @Test
    fun `decode reports duplicate explicit criterion and edge ids`() {
        val login = featureDocument(
            "features/login.md",
            LOGIN_ID,
            "Login",
            """
                ## Acceptance Criteria

                - [ ] First <!-- okf:id=$LOGIN_CRITERION_ID -->
                - [ ] Second <!-- okf:id=$LOGIN_CRITERION_ID -->

                ## Relationships
            """.trimIndent(),
        )
        val dashboard = featureDocument(
            "features/dashboard.md",
            DASHBOARD_ID,
            "Dashboard",
            """
                ## Acceptance Criteria

                ## Relationships

                - depends-on: [Login](login.md) <!-- okf:id=$EDGE_ID -->
                - depends-on: [Login](login.md) <!-- okf:id=$EDGE_ID -->
            """.trimIndent(),
        )

        val decoded = codec.decode(OkfBundle(listOf(login, dashboard)), emptyMap())

        assertEquals(2, decoded.graph.features.single { it.id == LOGIN_ID }.acceptanceCriteria.size)
        assertEquals(2, decoded.graph.edges.size)
        assertEquals(
            2,
            decoded.issues.count {
                it.severity == OkfIssueSeverity.ERROR && it.message.contains("duplicate explicit okf:id")
            },
        )
    }

    @Test
    fun `encode output is independent of feature edge and existing document order`() {
        val reports = WizardFeature(REPORTS_ID, "Reports")
        val features = listOf(
            WizardFeature(LOGIN_ID, "Login"),
            WizardFeature(DASHBOARD_ID, "Dashboard"),
            reports,
        )
        val edges = listOf(
            WizardFeatureEdge(EDGE_ID, LOGIN_ID, REPORTS_ID),
            WizardFeatureEdge(SECOND_EDGE_ID, DASHBOARD_ID, REPORTS_ID),
        )
        val existingLogin = featureDocument(
            "features/login.md",
            LOGIN_ID.uppercase(),
            "Login",
            "## Problem\n\nAuthored.\n\n## Acceptance Criteria\n\n\n\n## Relationships\n",
        )
        val preserved = listOf(
            existingLogin,
            OkfDocument("z-last.md", linkedMapOf(), "Z"),
            OkfDocument("a-first.md", linkedMapOf(), "A"),
        )

        val first = codec.encode(WizardFeatureGraph(features, edges), OkfBundle(preserved), timestamp)
        val permuted = codec.encode(
            WizardFeatureGraph(features.reversed(), edges.reversed()),
            OkfBundle(preserved.reversed()),
            timestamp,
        )

        assertEquals(first.documents.map { it.path }, permuted.documents.map { it.path })
        assertEquals(
            first.documents.associate { it.path to parser.render(it) },
            permuted.documents.associate { it.path to parser.render(it) },
        )
        assertEquals(
            listOf(DASHBOARD_ID, LOGIN_ID),
            codec.decode(first, emptyMap()).graph.edges.filter { it.to == REPORTS_ID }.map { it.from },
        )
    }

    @Test
    fun `encode escapes multiline titles in Markdown labels without changing frontmatter`() {
        val title = "Line [One]\\\nTwo"
        val graph = WizardFeatureGraph(
            features = listOf(
                WizardFeature(LOGIN_ID, title),
                WizardFeature(DASHBOARD_ID, "Dashboard"),
            ),
            edges = listOf(WizardFeatureEdge(EDGE_ID, LOGIN_ID, DASHBOARD_ID)),
        )

        val encoded = codec.encode(graph, null, timestamp)
        val source = encoded.documents.single { it.frontmatter["id"] == LOGIN_ID }
        val dashboard = encoded.documents.single { it.frontmatter["id"] == DASHBOARD_ID }
        val escapedLabel = "Line \\[One\\]\\\\ Two"

        assertEquals(title, source.frontmatter["title"])
        assertTrue(encoded.byPath.getValue("index.md").body.contains("[$escapedLabel]"))
        assertTrue(encoded.byPath.getValue("features/index.md").body.contains("[$escapedLabel]"))
        assertTrue(dashboard.body.contains("[$escapedLabel]"))
    }

    @Test
    fun `encode rejects multiline acceptance criteria`() {
        val graph = WizardFeatureGraph(
            features = listOf(
                WizardFeature(
                    LOGIN_ID,
                    "Login",
                    acceptanceCriteria = listOf(AcceptanceCriterion(LOGIN_CRITERION_ID, "First line\nSecond line")),
                ),
            ),
        )

        val exception = assertThrows<IllegalArgumentException> { codec.encode(graph, null, timestamp) }

        assertTrue(exception.message.orEmpty().contains("single line"))
        assertTrue(exception.message.orEmpty().contains(LOGIN_CRITERION_ID))
    }

    @Test
    fun `decode ignores fenced fake headings and warns for malformed managed list items`() {
        val document = featureDocument(
            "features/login.md",
            LOGIN_ID,
            "Login",
            """
                ## Acceptance Criteria

                ```markdown
                ## Relationships
                - depends-on: [Fake](fake.md)
                ```

                - [ ] Valid criterion
                - [x] Unsupported checkbox
                - custom criterion item

                ## Relationships

                - depends-on broken
            """.trimIndent(),
        )

        val decoded = codec.decode(OkfBundle(listOf(document)), emptyMap())

        assertEquals(listOf("Valid criterion"), decoded.graph.features.single().acceptanceCriteria.map { it.text })
        assertTrue(decoded.graph.edges.isEmpty())
        assertEquals(3, decoded.issues.count { it.field == "acceptanceCriteria" })
        assertEquals(1, decoded.issues.count { it.field == "relationships" })
        assertTrue(decoded.issues.any { it.message.contains("unsupported list item") })
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
    fun `slug allocation remains unique when the id suffix candidate is already occupied`() {
        val suffixCollisionId = "12345678-3333-4333-8333-333333333333"
        val graph = WizardFeatureGraph(
            features = listOf(
                WizardFeature(id = DASHBOARD_ID, title = "Foo 12345678"),
                WizardFeature(id = LOGIN_ID, title = "Foo"),
                WizardFeature(id = suffixCollisionId, title = "Foo"),
            ),
        )

        val first = codec.encode(graph, existing = null, timestamp = timestamp)
        val second = codec.encode(graph, existing = null, timestamp = timestamp)
        val featurePaths = first.documents.filter { it.frontmatter["type"] == "feature" }.map { it.path }
        val rootLinks = markdownLinks(first.byPath.getValue("index.md").body)
        val featureIndexLinks = markdownLinks(first.byPath.getValue("features/index.md").body)

        assertEquals(first, second)
        assertEquals(3, featurePaths.size)
        assertEquals(3, featurePaths.toSet().size)
        assertEquals(3, rootLinks.toSet().size)
        assertEquals(3, featureIndexLinks.toSet().size)
        assertTrue(featurePaths.contains("features/foo.md"))
        assertTrue(featurePaths.contains("features/foo-12345678.md"))
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

    @Test
    fun `encode escapes link labels and rejects multiline criteria`() {
        val title = "Login [primary]\nflow"
        val encoded = codec.encode(
            WizardFeatureGraph(features = listOf(WizardFeature(LOGIN_ID, title))),
            existing = null,
            timestamp = timestamp,
        )

        assertEquals(title, encoded.documents.single { it.frontmatter["type"] == "feature" }.frontmatter["title"])
        assertTrue(encoded.byPath.getValue("index.md").body.contains("[Login \\[primary\\] flow]"))
        assertThrows<IllegalArgumentException> {
            codec.encode(
                WizardFeatureGraph(
                    features = listOf(
                        WizardFeature(
                            LOGIN_ID,
                            "Login",
                            acceptanceCriteria = listOf(AcceptanceCriterion(LOGIN_CRITERION_ID, "First line\nsecond line")),
                        ),
                    ),
                ),
                null,
                timestamp,
            )
        }
    }

    @Test
    fun `decode warns for malformed managed items and ignores fenced fake headings`() {
        val document = featureDocument(
            "features/login.md",
            LOGIN_ID,
            "Login",
            """
                ## Problem

                ```markdown
                ## Acceptance Criteria
                - [ ] Fake criterion
                ```

                ## Acceptance Criteria

                - malformed criterion
                - [ ] Real criterion <!-- okf:id=$LOGIN_CRITERION_ID -->

                ## Relationships

                - malformed relationship
            """.trimIndent(),
        )

        val decoded = codec.decode(OkfBundle(listOf(document)), emptyMap())

        assertEquals(listOf(AcceptanceCriterion(LOGIN_CRITERION_ID, "Real criterion")), decoded.graph.features.single().acceptanceCriteria)
        assertEquals(2, decoded.issues.count { it.severity == OkfIssueSeverity.WARNING })
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

    private fun sectionContent(body: String, heading: String): String {
        val lines = body.lines()
        val start = lines.indexOf("## $heading")
        val end = (start + 1 until lines.size).first { lines[it].startsWith("## ") }
        return lines.subList(start + 1, end).joinToString("\n").trim()
    }

    private fun markdownLinks(body: String): List<String> =
        Regex("\\[[^]]+]\\(([^)]+)\\)").findAll(body).map { it.groupValues[1] }.toList()

    private companion object {
        const val LOGIN_ID = "11111111-1111-4111-8111-111111111111"
        const val DASHBOARD_ID = "22222222-2222-4222-8222-222222222222"
        const val LOGIN_CRITERION_ID = "33333333-3333-4333-8333-333333333333"
        const val DASHBOARD_CRITERION_ID = "44444444-4444-4444-8444-444444444444"
        const val EDGE_ID = "55555555-5555-4555-8555-555555555555"
        const val REPORTS_ID = "66666666-6666-4666-8666-666666666666"
        const val SECOND_EDGE_ID = "77777777-7777-4777-8777-777777777777"
    }
}

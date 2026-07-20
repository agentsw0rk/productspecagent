package com.agentwork.productspecagent.service.okf

import com.agentwork.productspecagent.domain.okf.OkfBundle
import com.agentwork.productspecagent.domain.okf.OkfDocument
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OkfThreeWayPlannerTest {
    private val parser = OkfParser()
    private val planner = OkfThreeWayPlanner(OkfFeatureCodec(parser))

    @Test
    fun `same document id conflicts even when remote renamed its file`() {
        val result = planner.plan(
            base = bundle(feature("features/login.md", LOGIN_ID, "Login", "base")),
            remote = bundle(feature("features/sign-in.md", LOGIN_ID, "Login", "remote")),
            proposed = bundle(feature("features/login.md", LOGIN_ID, "Login", "local")),
        )

        assertNull(result.merged)
        assertEquals(listOf(LOGIN_ID), result.conflicts.mapNotNull { it.documentId })
    }

    @Test
    fun `different documents integrate and regenerate indexes`() {
        val base = bundle(
            feature("features/login.md", LOGIN_ID, "Login", "base login"),
            feature("features/dashboard.md", DASHBOARD_ID, "Dashboard", "base dashboard"),
        )
        val result = planner.plan(
            base,
            bundle(
                feature("features/login.md", LOGIN_ID, "Login", "remote login"),
                feature("features/dashboard.md", DASHBOARD_ID, "Dashboard", "base dashboard"),
            ),
            bundle(
                feature("features/login.md", LOGIN_ID, "Login", "base login"),
                feature("features/dashboard.md", DASHBOARD_ID, "Dashboard", "local dashboard"),
            ),
        )

        assertTrue(result.conflicts.isEmpty())
        val merged = requireNotNull(result.merged)
        assertEquals(
            setOf(LOGIN_ID, DASHBOARD_ID),
            merged.documents.mapNotNull { it.frontmatter["id"] as? String }.toSet(),
        )
        assertEquals("remote login", merged.byPath.getValue("features/login.md").body)
        assertEquals("local dashboard", merged.byPath.getValue("features/dashboard.md").body)
        assertTrue(merged.byPath.getValue("index.md").body.contains("features/login.md"))
        assertTrue(merged.byPath.getValue("features/index.md").body.contains("login.md"))
    }

    @Test
    fun `different ids colliding on one path conflict`() {
        val result = planner.plan(
            base = bundle(),
            remote = bundle(feature("features/shared.md", LOGIN_ID, "Login", "remote")),
            proposed = bundle(feature("features/shared.md", DASHBOARD_ID, "Dashboard", "local")),
        )

        assertNull(result.merged)
        assertEquals(setOf(LOGIN_ID, DASHBOARD_ID), result.conflicts.mapNotNull { it.documentId }.toSet())
    }

    @Test
    fun `generated index edits never conflict and are replaced`() {
        val baseFeature = feature("features/login.md", LOGIN_ID, "Login", "base")
        val result = planner.plan(
            base = bundle(baseFeature, rootIndex("base index")),
            remote = bundle(baseFeature, rootIndex("remote index")),
            proposed = bundle(baseFeature, rootIndex("local index")),
        )

        assertTrue(result.conflicts.isEmpty())
        val regenerated = requireNotNull(result.merged).byPath.getValue("index.md").body
        assertNotEquals("remote index", regenerated)
        assertNotEquals("local index", regenerated)
        assertTrue(regenerated.contains("features/login.md"))
    }

    @Test
    fun `remote deletion conflicts with local document update`() {
        val baseFeature = feature("features/login.md", LOGIN_ID, "Login", "base")
        val result = planner.plan(
            base = bundle(baseFeature),
            remote = bundle(),
            proposed = bundle(baseFeature.copy(body = "local")),
        )

        assertEquals(listOf(LOGIN_ID), result.conflicts.mapNotNull { it.documentId })
    }

    @Test
    fun `local deletion conflicts with remote document update`() {
        val baseFeature = feature("features/login.md", LOGIN_ID, "Login", "base")
        val result = planner.plan(
            base = bundle(baseFeature),
            remote = bundle(baseFeature.copy(body = "remote")),
            proposed = bundle(),
        )

        assertEquals(listOf(LOGIN_ID), result.conflicts.mapNotNull { it.documentId })
    }

    @Test
    fun `unopposed remote deletion is retained`() {
        val baseFeature = feature("features/login.md", LOGIN_ID, "Login", "base")
        val result = planner.plan(bundle(baseFeature), bundle(), bundle(baseFeature))

        assertTrue(result.conflicts.isEmpty())
        assertTrue(requireNotNull(result.merged).documents.none { it.frontmatter["id"] == LOGIN_ID })
    }

    @Test
    fun `unopposed local deletion is retained`() {
        val baseFeature = feature("features/login.md", LOGIN_ID, "Login", "base")
        val result = planner.plan(bundle(baseFeature), bundle(baseFeature), bundle())

        assertTrue(result.conflicts.isEmpty())
        assertTrue(requireNotNull(result.merged).documents.none { it.frontmatter["id"] == LOGIN_ID })
    }

    private fun bundle(vararg documents: OkfDocument) = OkfBundle(documents.toList())

    private fun feature(path: String, id: String, title: String, body: String) = OkfDocument(
        path = path,
        frontmatter = linkedMapOf(
            "type" to "feature",
            "id" to id,
            "title" to title,
            "description" to "$title works.",
            "tags" to emptyList<String>(),
            "timestamp" to "2026-07-20T12:00:00Z",
            "scopes" to emptyList<String>(),
            "scope_fields" to emptyMap<String, String>(),
            "legacy_ids" to emptyList<String>(),
        ),
        body = body,
    )

    private fun rootIndex(body: String) = OkfDocument(
        "index.md",
        linkedMapOf("okf_version" to "0.1", "profile" to "productspec"),
        body,
    )

    private companion object {
        const val LOGIN_ID = "11111111-1111-4111-8111-111111111111"
        const val DASHBOARD_ID = "22222222-2222-4222-8222-222222222222"
    }
}

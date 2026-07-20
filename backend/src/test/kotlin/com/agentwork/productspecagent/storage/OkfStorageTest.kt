package com.agentwork.productspecagent.storage

import com.agentwork.productspecagent.domain.GraphPosition
import com.agentwork.productspecagent.domain.WizardFeature
import com.agentwork.productspecagent.domain.WizardFeatureGraph
import com.agentwork.productspecagent.domain.okf.OkfBundle
import com.agentwork.productspecagent.domain.okf.OkfChangeset
import com.agentwork.productspecagent.domain.okf.OkfChangesetKind
import com.agentwork.productspecagent.domain.okf.OkfChangesetStatus
import com.agentwork.productspecagent.domain.okf.OkfConflict
import com.agentwork.productspecagent.domain.okf.OkfDocument
import com.agentwork.productspecagent.domain.okf.OkfGitConnection
import com.agentwork.productspecagent.domain.okf.OkfProjectState
import com.agentwork.productspecagent.service.okf.OkfParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OkfStorageTest {

    @Test
    fun `missing state defaults to legacy mode`() {
        val storage = OkfStateStorage(InMemoryObjectStore())

        val state = storage.loadState("p1")

        assertEquals(OkfProjectState(projectId = "p1"), state)
    }

    @Test
    fun `connection persistence never contains credentials`() {
        val objectStore = InMemoryObjectStore()
        val storage = OkfStateStorage(objectStore)
        storage.saveState(
            OkfProjectState(
                projectId = "p1",
                connection = OkfGitConnection("https://git.example/repo.git", "develop"),
            ),
        )

        val state = storage.loadState("p1")
        val storedJson = objectStore.get("projects/p1/okf/state.json")!!.toString(Charsets.UTF_8)

        assertEquals("https://git.example/repo.git", state.connection?.remoteUrl)
        assertEquals("develop", state.connection?.baseBranch)
        assertEquals("productspec/okf", state.connection?.canonicalBranch)
        assertFalse(storedJson.contains("credential", ignoreCase = true))
        assertFalse(storedJson.contains("token", ignoreCase = true))
        assertFalse(storedJson.contains("password", ignoreCase = true))
    }

    @Test
    fun `pending changeset is a single mutable record`() {
        val storage = OkfStateStorage(InMemoryObjectStore())
        val first = changeset(id = "first")
        val second = changeset(id = "second")

        storage.savePendingChangeset(first)
        storage.savePendingChangeset(second)

        assertEquals(second, storage.loadPendingChangeset("p1"))
        storage.deletePendingChangeset("p1")
        assertNull(storage.loadPendingChangeset("p1"))
    }

    @Test
    fun `approved changeset receipt is idempotent`() {
        val objectStore = InMemoryObjectStore()
        val storage = OkfStateStorage(objectStore)
        val approved = changeset(id = "approved", status = OkfChangesetStatus.APPROVED)

        storage.saveApprovedChangeset(approved)
        storage.saveApprovedChangeset(approved)

        assertEquals(approved, storage.loadApprovedChangeset("p1", "approved"))
        assertEquals(
            listOf("projects/p1/okf/changesets/approved/approved.json"),
            objectStore.listKeys("projects/p1/okf/changesets/approved/"),
        )
    }

    @Test
    fun `current conflict can be replaced and cleared`() {
        val storage = OkfStateStorage(InMemoryObjectStore())
        val first = OkfConflict("c1", null, "docs/okf/features/first.md", null, "remote", "proposed")
        val second = OkfConflict("c2", "feature-2", "docs/okf/features/second.md", "base", null, "proposed")

        storage.saveConflict("p1", first)
        storage.saveConflict("p1", second)

        assertEquals(second, storage.loadConflict("p1"))
        storage.deleteConflict("p1")
        assertNull(storage.loadConflict("p1"))
    }

    @Test
    fun `raw bundle replacement deletes only the OKF bundle prefix`() {
        val objectStore = InMemoryObjectStore()
        val storage = OkfProjectionStorage(objectStore, OkfParser())
        objectStore.put("projects/p1/docs/spec.md", "legacy".toByteArray())
        objectStore.put("projects/p1/docs/okf/obsolete.md", "obsolete".toByteArray())
        val bundle = OkfBundle(
            listOf(
                OkfDocument("docs/okf/index.md", linkedMapOf("type" to "index"), "# Index\n"),
                OkfDocument("docs/okf/features/feature-a.md", linkedMapOf("type" to "feature"), "# A\n"),
            ),
        )

        storage.replaceRawBundle("p1", bundle)

        assertEquals(bundle.byPath, storage.loadRawBundle("p1").byPath)
        assertFalse(objectStore.exists("projects/p1/docs/okf/obsolete.md"))
        assertEquals("legacy", objectStore.get("projects/p1/docs/spec.md")!!.toString(Charsets.UTF_8))
        assertEquals(
            listOf(
                "projects/p1/docs/okf/features/feature-a.md",
                "projects/p1/docs/okf/index.md",
            ),
            objectStore.listKeys("projects/p1/docs/okf/"),
        )
    }

    @Test
    fun `feature projection roundtrips`() {
        val storage = OkfProjectionStorage(InMemoryObjectStore(), OkfParser())
        val graph = WizardFeatureGraph(features = listOf(WizardFeature(id = "feature-1", title = "First")))

        storage.saveFeatureProjection("p1", graph)

        assertEquals(graph, storage.loadFeatureProjection("p1"))
    }

    @Test
    fun `presentation positions are replaceable and optional`() {
        val objectStore = InMemoryObjectStore()
        val storage = OkfPresentationStorage(objectStore)
        assertTrue(storage.loadPositions("p1").isEmpty())

        storage.savePositions("p1", mapOf("feature-1" to GraphPosition(10.0, 20.0)))
        storage.savePositions("p1", mapOf("feature-2" to GraphPosition(30.0, 40.0)))

        assertEquals(mapOf("feature-2" to GraphPosition(30.0, 40.0)), storage.loadPositions("p1"))
        val storedJson = objectStore.get("projects/p1/okf/presentation.json")!!.toString(Charsets.UTF_8)
        assertFalse(storedJson.contains("title"))
        assertFalse(storedJson.contains("acceptanceCriteria"))
    }

    private fun changeset(
        id: String,
        status: OkfChangesetStatus = OkfChangesetStatus.PENDING,
    ) = OkfChangeset(
        id = id,
        projectId = "p1",
        kind = OkfChangesetKind.EDIT,
        status = status,
        baseCommit = "abc123",
        operations = emptyList(),
        issues = emptyList(),
        createdAt = "2026-07-20T00:00:00Z",
        updatedAt = "2026-07-20T00:00:00Z",
        approvedCommit = if (status == OkfChangesetStatus.APPROVED) "def456" else null,
    )
}

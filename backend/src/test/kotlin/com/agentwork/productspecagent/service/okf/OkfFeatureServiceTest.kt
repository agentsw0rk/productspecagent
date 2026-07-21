package com.agentwork.productspecagent.service.okf

import com.agentwork.productspecagent.domain.GraphPosition
import com.agentwork.productspecagent.domain.WizardFeature
import com.agentwork.productspecagent.domain.WizardFeatureGraph
import com.agentwork.productspecagent.domain.okf.OkfMode
import com.agentwork.productspecagent.domain.okf.OkfConflict
import com.agentwork.productspecagent.domain.okf.OkfProjectState
import com.agentwork.productspecagent.storage.InMemoryObjectStore
import com.agentwork.productspecagent.storage.OkfPresentationStorage
import com.agentwork.productspecagent.storage.OkfProjectionStorage
import com.agentwork.productspecagent.storage.OkfStateStorage
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class OkfFeatureServiceTest {

    @Test
    fun `position only proposal updates presentation without a changeset`() {
        val fixture = fixture()
        val moved = fixture.service.graph(PROJECT_ID).copy(
            features = listOf(login(position = GraphPosition(120.0, 80.0))),
        )

        val changeset = fixture.service.propose(PROJECT_ID, moved)

        assertNull(changeset)
        assertNull(fixture.stateStorage.loadPendingChangeset(PROJECT_ID))
        assertEquals(GraphPosition(120.0, 80.0), fixture.presentationStorage.loadPositions(PROJECT_ID)[LOGIN_ID])
    }

    @Test
    fun `semantic proposal overlays confirmed projection and survives service reload`() {
        val fixture = fixture()
        val proposed = WizardFeatureGraph(listOf(login(title = "Sign in", position = GraphPosition(120.0, 80.0))))

        val first = fixture.service.propose(PROJECT_ID, proposed)
        val reloaded = fixture.newService().graph(PROJECT_ID)

        assertNotNull(first)
        assertEquals("Sign in", reloaded.features.single().title)
        assertEquals(GraphPosition(120.0, 80.0), reloaded.features.single().position)
        assertEquals("Login", fixture.projectionStorage.loadFeatureProjection(PROJECT_ID)!!.features.single().title)
        assertEquals("Login", fixture.codec.decode(bundle(fixture.projectionStorage.loadRawFiles(PROJECT_ID), fixture.parser), emptyMap()).graph.features.single().title)
    }

    @Test
    fun `later semantic proposal replaces the same pending changeset`() {
        val fixture = fixture()
        val first = fixture.service.propose(PROJECT_ID, WizardFeatureGraph(listOf(login(title = "Sign in"))))!!

        val second = fixture.service.propose(PROJECT_ID, WizardFeatureGraph(listOf(login(title = "Authenticate"))))!!

        assertEquals(first.id, second.id)
        assertEquals("Authenticate", fixture.service.graph(PROJECT_ID).features.single().title)
        assertEquals(second, fixture.stateStorage.loadPendingChangeset(PROJECT_ID))
    }

    @Test
    fun `reverting to confirmed graph removes an empty pending proposal`() {
        val fixture = fixture()
        val pending = fixture.service.propose(PROJECT_ID, WizardFeatureGraph(listOf(login(title = "Sign in"))))!!
        fixture.stateStorage.saveConflict(
            PROJECT_ID,
            OkfConflict(pending.id, LOGIN_ID, "features/login.md", null, null, null),
        )

        val reverted = fixture.service.propose(PROJECT_ID, WizardFeatureGraph(listOf(login())))

        assertNull(reverted)
        assertNull(fixture.stateStorage.loadPendingChangeset(PROJECT_ID))
        assertTrue(fixture.stateStorage.loadConflicts(PROJECT_ID).isEmpty())
        assertTrue(!fixture.service.hasPending(PROJECT_ID))
    }

    @Test
    fun `positions use canonical UUID keys and invalid proposals leave presentation unchanged`() {
        val fixture = fixture()
        val uppercase = WizardFeatureGraph(
            listOf(login(position = GraphPosition(20.0, 30.0)).copy(id = LOGIN_ID.uppercase())),
        )

        val changeset = fixture.service.propose(PROJECT_ID, uppercase)

        assertNull(changeset)
        assertNull(fixture.stateStorage.loadPendingChangeset(PROJECT_ID))
        assertEquals(GraphPosition(20.0, 30.0), fixture.presentationStorage.loadPositions(PROJECT_ID)[LOGIN_ID])
        assertThrows(OkfFeatureProposalException::class.java) {
            fixture.service.propose(
                PROJECT_ID,
                WizardFeatureGraph(listOf(login(position = GraphPosition(90.0, 90.0)).copy(id = "invalid"))),
            )
        }
        assertEquals(GraphPosition(20.0, 30.0), fixture.presentationStorage.loadPositions(PROJECT_ID)[LOGIN_ID])
    }

    private fun fixture(): Fixture {
        val store = InMemoryObjectStore()
        val parser = OkfParser()
        val codec = OkfFeatureCodec(parser)
        val stateStorage = OkfStateStorage(store)
        val projectionStorage = OkfProjectionStorage(store, parser)
        val presentationStorage = OkfPresentationStorage(store)
        stateStorage.saveState(
            OkfProjectState(PROJECT_ID, mode = OkfMode.OKF_ACTIVE, baseCommit = "c1"),
        )
        val graph = WizardFeatureGraph(listOf(login()))
        val files = codec.encode(graph, null, OffsetDateTime.parse("2026-07-20T00:00:00Z"))
            .documents.associate { it.path to parser.render(it) }
        projectionStorage.replaceRawFiles(PROJECT_ID, files)
        projectionStorage.saveFeatureProjection(PROJECT_ID, graph)
        val clock = Clock.fixed(Instant.parse("2026-07-20T12:00:00Z"), ZoneOffset.UTC)
        val service = OkfFeatureService(
            stateStorage,
            projectionStorage,
            presentationStorage,
            parser,
            codec,
            OkfProfileValidator(),
            clock,
        )
        return Fixture(stateStorage, projectionStorage, presentationStorage, parser, codec, clock, service)
    }

    private fun Fixture.newService() = OkfFeatureService(
        stateStorage,
        projectionStorage,
        presentationStorage,
        parser,
        codec,
        OkfProfileValidator(),
        clock,
    )

    private fun bundle(files: Map<String, String>, parser: OkfParser) =
        com.agentwork.productspecagent.domain.okf.OkfBundle(
            files.map { (path, content) -> parser.parse(path, content) },
        )

    private fun login(
        title: String = "Login",
        position: GraphPosition = GraphPosition(),
    ) = WizardFeature(LOGIN_ID, title, description = "Users can sign in.", position = position)

    private data class Fixture(
        val stateStorage: OkfStateStorage,
        val projectionStorage: OkfProjectionStorage,
        val presentationStorage: OkfPresentationStorage,
        val parser: OkfParser,
        val codec: OkfFeatureCodec,
        val clock: Clock,
        val service: OkfFeatureService,
    )

    private companion object {
        const val PROJECT_ID = "project-1"
        const val LOGIN_ID = "11111111-1111-4111-8111-111111111111"
    }
}

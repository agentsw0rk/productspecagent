package com.agentwork.productspecagent.service.okf

import com.agentwork.productspecagent.domain.WizardFeature
import com.agentwork.productspecagent.domain.WizardFeatureGraph
import com.agentwork.productspecagent.domain.okf.OkfChangeset
import com.agentwork.productspecagent.domain.okf.OkfChangesetKind
import com.agentwork.productspecagent.domain.okf.OkfFileOperation
import com.agentwork.productspecagent.domain.okf.OkfGitConnection
import com.agentwork.productspecagent.domain.okf.OkfMode
import com.agentwork.productspecagent.domain.okf.OkfOperationType
import com.agentwork.productspecagent.domain.okf.OkfProjectState
import com.agentwork.productspecagent.domain.okf.OkfSyncStatus
import com.agentwork.productspecagent.storage.InMemoryObjectStore
import com.agentwork.productspecagent.storage.OkfPresentationStorage
import com.agentwork.productspecagent.storage.OkfProjectionStorage
import com.agentwork.productspecagent.storage.OkfStateStorage
import java.time.OffsetDateTime
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class OkfSyncServiceTest {

    @Test
    fun `clean inbound sync stores confirmed projection and advances base`() {
        val fixture = fixture()
        val files = files(feature("Remote"))
        whenever(fixture.git.fetch(PROJECT_ID, CONNECTION)).thenReturn(OkfGitSnapshot("c1", "main", files))

        val state = fixture.service.sync(PROJECT_ID)

        assertEquals("c1", state.baseCommit)
        assertEquals(OkfSyncStatus.CLEAN, state.syncStatus)
        assertEquals("Remote", fixture.projectionStorage.loadFeatureProjection(PROJECT_ID)!!.features.single().title)
        assertEquals(files, fixture.projectionStorage.loadRawFiles(PROJECT_ID))
    }

    @Test
    fun `invalid remote remains inspectable without replacing confirmed projection or base`() {
        val fixture = fixture(baseCommit = "confirmed")
        fixture.projectionStorage.saveFeatureProjection(
            PROJECT_ID,
            WizardFeatureGraph(listOf(feature("Confirmed"))),
        )
        val invalid = mapOf("features/invalid.md" to "---\ntype: [broken\n---\n")
        whenever(fixture.git.fetch(PROJECT_ID, CONNECTION)).thenReturn(OkfGitSnapshot("bad", "productspec/okf", invalid))

        val state = fixture.service.sync(PROJECT_ID)

        assertEquals("confirmed", state.baseCommit)
        assertEquals(OkfSyncStatus.INVALID_REMOTE, state.syncStatus)
        assertEquals(invalid, fixture.projectionStorage.loadRawFiles(PROJECT_ID))
        assertEquals("Confirmed", fixture.projectionStorage.loadFeatureProjection(PROJECT_ID)!!.features.single().title)
    }

    @Test
    fun `recovery after invalid remote reads the confirmed base commit instead of the raw mirror`() {
        val fixture = fixture(baseCommit = "c1")
        val baseFiles = files(feature("Login"))
        val localFiles = files(feature("Local login"))
        fixture.stateStorage.savePendingChangeset(
            changeset(baseCommit = "c1", operations = operations(baseFiles, localFiles, LOGIN_ID)),
        )
        val invalid = mapOf("features/invalid.md" to "---\ntype: [broken\n---\n")
        whenever(fixture.git.fetch(PROJECT_ID, CONNECTION))
            .thenReturn(OkfGitSnapshot("bad", "productspec/okf", invalid))
            .thenReturn(OkfGitSnapshot("c2", "productspec/okf", baseFiles))
        whenever(fixture.git.readAt(PROJECT_ID, CONNECTION, "c1"))
            .thenReturn(OkfGitSnapshot("c1", "productspec/okf", baseFiles))

        assertEquals(OkfSyncStatus.INVALID_REMOTE, fixture.service.sync(PROJECT_ID).syncStatus)
        val recovered = fixture.service.sync(PROJECT_ID)

        assertEquals(OkfSyncStatus.PROPOSAL_PENDING, recovered.syncStatus)
        assertTrue(fixture.stateStorage.loadPendingChangeset(PROJECT_ID)!!.operations.any { it.documentId == LOGIN_ID })
        verify(fixture.git).readAt(PROJECT_ID, CONNECTION, "c1")
    }

    @Test
    fun `non overlapping pending changes rebase onto incoming remote`() {
        val fixture = fixture(baseCommit = "c1")
        val baseFiles = files(feature("Login"), feature("Dashboard", DASHBOARD_ID))
        fixture.projectionStorage.replaceRawFiles(PROJECT_ID, baseFiles)
        val localFiles = files(feature("Login"), feature("Local dashboard", DASHBOARD_ID))
        fixture.stateStorage.savePendingChangeset(
            changeset(
                baseCommit = "c1",
                operations = operations(baseFiles, localFiles, DASHBOARD_ID),
            ),
        )
        val remoteFiles = files(feature("Remote login"), feature("Dashboard", DASHBOARD_ID))
        whenever(fixture.git.fetch(PROJECT_ID, CONNECTION))
            .thenReturn(OkfGitSnapshot("c2", "productspec/okf", remoteFiles))
        whenever(fixture.git.readAt(PROJECT_ID, CONNECTION, "c1"))
            .thenReturn(OkfGitSnapshot("c1", "productspec/okf", baseFiles))

        val state = fixture.service.sync(PROJECT_ID)
        val rebased = fixture.stateStorage.loadPendingChangeset(PROJECT_ID)!!

        assertEquals(OkfSyncStatus.PROPOSAL_PENDING, state.syncStatus)
        assertEquals("c2", state.baseCommit)
        assertEquals("c2", rebased.baseCommit)
        assertTrue(rebased.operations.any { it.documentId == DASHBOARD_ID && it.content?.contains("Local dashboard") == true })
        assertTrue(rebased.operations.all { it.baseContent != it.content })
    }

    @Test
    fun `overlapping pending and remote changes persist a conflict`() {
        val fixture = fixture(baseCommit = "c1")
        val baseFiles = files(feature("Login"))
        fixture.projectionStorage.replaceRawFiles(PROJECT_ID, baseFiles)
        val localFiles = files(feature("Local login"))
        fixture.stateStorage.savePendingChangeset(
            changeset(baseCommit = "c1", operations = operations(baseFiles, localFiles, LOGIN_ID)),
        )
        val remoteFiles = files(feature("Remote login"))
        whenever(fixture.git.fetch(PROJECT_ID, CONNECTION))
            .thenReturn(OkfGitSnapshot("c2", "productspec/okf", remoteFiles))
        whenever(fixture.git.readAt(PROJECT_ID, CONNECTION, "c1"))
            .thenReturn(OkfGitSnapshot("c1", "productspec/okf", baseFiles))

        val state = fixture.service.sync(PROJECT_ID)

        assertEquals(OkfSyncStatus.CONFLICT, state.syncStatus)
        assertEquals(CHANGESET_ID, fixture.stateStorage.loadConflict(PROJECT_ID)?.changesetId)
        assertEquals(LOGIN_ID, fixture.stateStorage.loadConflict(PROJECT_ID)?.documentId)
        assertNotNull(fixture.stateStorage.loadPendingChangeset(PROJECT_ID))
    }

    @Test
    fun `multiple conflicts are resolved sequentially without overwriting remote choices`() {
        val fixture = fixture(baseCommit = "c1")
        val baseFiles = files(feature("Login"), feature("Dashboard", DASHBOARD_ID))
        val localFiles = files(feature("Local login"), feature("Local dashboard", DASHBOARD_ID))
        val remoteFiles = files(feature("Remote login"), feature("Remote dashboard", DASHBOARD_ID))
        val pendingOperations = (
            operations(baseFiles, localFiles, LOGIN_ID) +
                operations(baseFiles, localFiles, DASHBOARD_ID)
            ).distinctBy { it.documentId ?: "path:${it.path}" }
        fixture.stateStorage.savePendingChangeset(changeset(baseCommit = "c1", operations = pendingOperations))
        whenever(fixture.git.fetch(PROJECT_ID, CONNECTION))
            .thenReturn(OkfGitSnapshot("c2", "productspec/okf", remoteFiles))
        whenever(fixture.git.readAt(PROJECT_ID, CONNECTION, "c1"))
            .thenReturn(OkfGitSnapshot("c1", "productspec/okf", baseFiles))

        fixture.service.sync(PROJECT_ID)
        assertEquals(2, fixture.stateStorage.loadConflicts(PROJECT_ID).size)

        val afterFirst = fixture.service.resolve(PROJECT_ID, CHANGESET_ID, OkfConflictResolution.PROPOSED)!!
        assertEquals(OkfSyncStatus.CONFLICT, fixture.stateStorage.loadState(PROJECT_ID).syncStatus)
        assertEquals(1, fixture.stateStorage.loadConflicts(PROJECT_ID).size)
        assertTrue(afterFirst.operations.any { it.documentId == LOGIN_ID && it.content?.contains("Local login") == true })
        assertThrows(OkfChangesetConflictException::class.java) {
            fixture.service.approve(PROJECT_ID, CHANGESET_ID)
        }

        whenever(fixture.git.readAt(PROJECT_ID, CONNECTION, "c2"))
            .thenReturn(OkfGitSnapshot("c2", "productspec/okf", remoteFiles))

        val afterSecond = fixture.service.resolve(PROJECT_ID, CHANGESET_ID, OkfConflictResolution.REMOTE)!!
        assertEquals(OkfSyncStatus.PROPOSAL_PENDING, fixture.stateStorage.loadState(PROJECT_ID).syncStatus)
        assertTrue(afterSecond.operations.any { it.documentId == LOGIN_ID && it.content?.contains("Local login") == true })
        assertTrue(afterSecond.operations.none { it.documentId == DASHBOARD_ID })
    }

    @Test
    fun `new remote overlap during conflict resolution becomes another conflict`() {
        val fixture = fixture(baseCommit = "c1")
        val baseFiles = files(feature("Login"), feature("Dashboard", DASHBOARD_ID))
        val localFiles = files(feature("Local login"), feature("Local dashboard", DASHBOARD_ID))
        val firstRemote = files(feature("Remote login"), feature("Dashboard", DASHBOARD_ID))
        val latestRemote = files(feature("Remote login"), feature("New remote dashboard", DASHBOARD_ID))
        val pendingOperations = (
            operations(baseFiles, localFiles, LOGIN_ID) +
                operations(baseFiles, localFiles, DASHBOARD_ID)
            ).distinctBy { it.documentId ?: "path:${it.path}" }
        fixture.stateStorage.savePendingChangeset(changeset(baseCommit = "c1", operations = pendingOperations))
        whenever(fixture.git.fetch(PROJECT_ID, CONNECTION))
            .thenReturn(OkfGitSnapshot("c2", "productspec/okf", firstRemote))
            .thenReturn(OkfGitSnapshot("c3", "productspec/okf", latestRemote))
        whenever(fixture.git.readAt(PROJECT_ID, CONNECTION, "c1"))
            .thenReturn(OkfGitSnapshot("c1", "productspec/okf", baseFiles))

        fixture.service.sync(PROJECT_ID)
        fixture.service.resolve(PROJECT_ID, CHANGESET_ID, OkfConflictResolution.REMOTE)

        assertEquals(OkfSyncStatus.CONFLICT, fixture.stateStorage.loadState(PROJECT_ID).syncStatus)
        assertEquals(DASHBOARD_ID, fixture.stateStorage.loadConflict(PROJECT_ID)?.documentId)
        assertTrue(
            fixture.stateStorage.loadPendingChangeset(PROJECT_ID)!!.operations
                .any { it.documentId == DASHBOARD_ID && it.content?.contains("Local dashboard") == true },
        )
    }

    @Test
    fun `failed push keeps pending content retryable without advancing base`() {
        val fixture = fixture(baseCommit = "c1")
        val baseFiles = files(feature("Login"))
        val proposedFiles = files(feature("Local login"))
        fixture.stateStorage.savePendingChangeset(
            changeset(baseCommit = "c1", operations = operations(baseFiles, proposedFiles, LOGIN_ID)),
        )
        whenever(fixture.git.readAt(PROJECT_ID, CONNECTION, "c1"))
            .thenReturn(OkfGitSnapshot("c1", "productspec/okf", baseFiles))
        whenever(fixture.git.fetch(PROJECT_ID, CONNECTION))
            .thenReturn(OkfGitSnapshot("c1", "productspec/okf", baseFiles))
        whenever(
            fixture.git.commitAndPush(eq(PROJECT_ID), eq(CONNECTION), eq("c1"), any(), any()),
        ).thenThrow(OkfGitPushRejectedException("rejected"))

        assertThrows(OkfPushException::class.java) {
            fixture.service.approve(PROJECT_ID, CHANGESET_ID)
        }

        val state = fixture.stateStorage.loadState(PROJECT_ID)
        assertEquals("c1", state.baseCommit)
        assertEquals(OkfSyncStatus.PUSH_FAILED, state.syncStatus)
        assertNotNull(fixture.stateStorage.loadPendingChangeset(PROJECT_ID))
    }

    @Test
    fun `approval rebases non overlapping remote changes before pushing`() {
        val fixture = fixture(baseCommit = "c1")
        val baseFiles = files(feature("Login"), feature("Dashboard", DASHBOARD_ID))
        val localFiles = files(feature("Login"), feature("Local dashboard", DASHBOARD_ID))
        val remoteFiles = files(feature("Remote login"), feature("Dashboard", DASHBOARD_ID))
        val expectedFiles = files(feature("Remote login"), feature("Local dashboard", DASHBOARD_ID))
        fixture.stateStorage.savePendingChangeset(
            changeset(baseCommit = "c1", operations = operations(baseFiles, localFiles, DASHBOARD_ID)),
        )
        whenever(fixture.git.fetch(PROJECT_ID, CONNECTION))
            .thenReturn(OkfGitSnapshot("c2", "productspec/okf", remoteFiles))
            .thenReturn(OkfGitSnapshot("c3", "productspec/okf", expectedFiles))
        whenever(fixture.git.readAt(PROJECT_ID, CONNECTION, "c1"))
            .thenReturn(OkfGitSnapshot("c1", "productspec/okf", baseFiles))
        whenever(fixture.git.readAt(PROJECT_ID, CONNECTION, "c2"))
            .thenReturn(OkfGitSnapshot("c2", "productspec/okf", remoteFiles))
        whenever(fixture.git.readAt(PROJECT_ID, CONNECTION, "c3"))
            .thenReturn(OkfGitSnapshot("c3", "productspec/okf", expectedFiles))
        whenever(fixture.git.commitAndPush(eq(PROJECT_ID), eq(CONNECTION), eq("c2"), eq(expectedFiles), any()))
            .thenReturn(OkfGitCommitResult("c3", "productspec/okf"))

        val approved = fixture.service.approve(PROJECT_ID, CHANGESET_ID)

        assertEquals("c3", approved.approvedCommit)
        verify(fixture.git).commitAndPush(eq(PROJECT_ID), eq(CONNECTION), eq("c2"), eq(expectedFiles), any())
    }

    @Test
    fun `concurrent matching push is recovered as an idempotent approval`() {
        val fixture = fixture(baseCommit = "c1")
        val baseFiles = files(feature("Login"))
        val proposedFiles = files(feature("Local login"))
        fixture.stateStorage.savePendingChangeset(
            changeset(baseCommit = "c1", operations = operations(baseFiles, proposedFiles, LOGIN_ID)),
        )
        whenever(fixture.git.readAt(PROJECT_ID, CONNECTION, "c1"))
            .thenReturn(OkfGitSnapshot("c1", "productspec/okf", baseFiles))
        whenever(fixture.git.fetch(PROJECT_ID, CONNECTION))
            .thenReturn(OkfGitSnapshot("c1", "productspec/okf", baseFiles))
            .thenReturn(OkfGitSnapshot("c2", "productspec/okf", proposedFiles))
            .thenReturn(OkfGitSnapshot("c2", "productspec/okf", proposedFiles))
        whenever(fixture.git.readAt(PROJECT_ID, CONNECTION, "c2"))
            .thenReturn(OkfGitSnapshot("c2", "productspec/okf", proposedFiles))
        whenever(fixture.git.commitAndPush(eq(PROJECT_ID), eq(CONNECTION), eq("c1"), any(), any()))
            .thenThrow(OkfGitRemoteAdvancedException("advanced"))

        val approved = fixture.service.approve(PROJECT_ID, CHANGESET_ID)

        assertEquals("c2", approved.approvedCommit)
        assertEquals(OkfSyncStatus.CLEAN, fixture.stateStorage.loadState(PROJECT_ID).syncStatus)
    }

    @Test
    fun `successful migration approval activates only after readback projection and is idempotent`() {
        val fixture = fixture(baseCommit = "c1", mode = OkfMode.MIGRATION_READY)
        val baseFiles = files()
        val proposedFiles = files(feature("Login"))
        fixture.stateStorage.savePendingChangeset(
            changeset(
                kind = OkfChangesetKind.MIGRATION,
                baseCommit = "c1",
                operations = operations(baseFiles, proposedFiles, LOGIN_ID),
            ),
        )
        whenever(fixture.git.readAt(PROJECT_ID, CONNECTION, "c1"))
            .thenReturn(OkfGitSnapshot("c1", "main", baseFiles))
        whenever(fixture.git.commitAndPush(eq(PROJECT_ID), eq(CONNECTION), eq("c1"), any(), any()))
            .thenReturn(OkfGitCommitResult("c2", "productspec/okf"))
        whenever(fixture.git.fetch(PROJECT_ID, CONNECTION))
            .thenReturn(OkfGitSnapshot("c1", "main", baseFiles))
            .thenReturn(OkfGitSnapshot("c2", "productspec/okf", proposedFiles))
        whenever(fixture.git.readAt(PROJECT_ID, CONNECTION, "c2"))
            .thenReturn(OkfGitSnapshot("c2", "productspec/okf", proposedFiles))

        val approved = fixture.service.approve(PROJECT_ID, CHANGESET_ID)
        val retried = fixture.service.approve(PROJECT_ID, CHANGESET_ID)

        assertEquals(approved, retried)
        assertEquals("c2", approved.approvedCommit)
        assertEquals(OkfMode.OKF_ACTIVE, fixture.stateStorage.loadState(PROJECT_ID).mode)
        assertEquals("Login", fixture.projectionStorage.loadFeatureProjection(PROJECT_ID)!!.features.single().title)
        verify(fixture.git, times(1)).commitAndPush(eq(PROJECT_ID), eq(CONNECTION), eq("c1"), any(), any())

        fixture.stateStorage.saveState(fixture.stateStorage.loadState(PROJECT_ID).copy(baseCommit = "c3"))
        fixture.service.approve(PROJECT_ID, CHANGESET_ID)
        assertEquals("c3", fixture.stateStorage.loadState(PROJECT_ID).baseCommit)
        assertEquals("2026-07-20T12:00:00Z", approved.updatedAt)
    }

    @Test
    fun `migration remains inactive when readback differs from the approved snapshot`() {
        val fixture = fixture(baseCommit = "c1", mode = OkfMode.MIGRATION_READY)
        val baseFiles = files()
        val proposedFiles = files(feature("Login"))
        fixture.stateStorage.savePendingChangeset(
            changeset(
                kind = OkfChangesetKind.MIGRATION,
                baseCommit = "c1",
                operations = operations(baseFiles, proposedFiles, LOGIN_ID),
            ),
        )
        whenever(fixture.git.readAt(PROJECT_ID, CONNECTION, "c1"))
            .thenReturn(OkfGitSnapshot("c1", "main", baseFiles))
        whenever(fixture.git.commitAndPush(eq(PROJECT_ID), eq(CONNECTION), eq("c1"), any(), any()))
            .thenReturn(OkfGitCommitResult("c2", "productspec/okf"))
        whenever(fixture.git.fetch(PROJECT_ID, CONNECTION))
            .thenReturn(OkfGitSnapshot("c1", "main", baseFiles))
            .thenReturn(OkfGitSnapshot("c3", "productspec/okf", files(feature("Other"))))
        whenever(fixture.git.readAt(PROJECT_ID, CONNECTION, "c2"))
            .thenReturn(OkfGitSnapshot("c2", "productspec/okf", files(feature("Other"))))

        assertThrows(OkfPushException::class.java) {
            fixture.service.approve(PROJECT_ID, CHANGESET_ID)
        }

        assertEquals(OkfMode.MIGRATION_READY, fixture.stateStorage.loadState(PROJECT_ID).mode)
        assertEquals("c2", fixture.stateStorage.loadPendingChangeset(PROJECT_ID)?.approvedCommit)
    }

    @Test
    fun `approved receipt retry completes pending cleanup and migration cutover`() {
        val fixture = fixture(baseCommit = "c1", mode = OkfMode.MIGRATION_READY)
        val pending = changeset(
            kind = OkfChangesetKind.MIGRATION,
            baseCommit = "c1",
            operations = emptyList(),
        )
        fixture.stateStorage.savePendingChangeset(pending)
        fixture.stateStorage.saveApprovedChangeset(
            pending.copy(
                status = com.agentwork.productspecagent.domain.okf.OkfChangesetStatus.APPROVED,
                approvedCommit = "c2",
            ),
        )

        fixture.service.approve(PROJECT_ID, CHANGESET_ID)

        assertEquals(null, fixture.stateStorage.loadPendingChangeset(PROJECT_ID))
        assertEquals(OkfMode.OKF_ACTIVE, fixture.stateStorage.loadState(PROJECT_ID).mode)
        assertEquals("c2", fixture.stateStorage.loadState(PROJECT_ID).baseCommit)
    }

    @Test
    fun `retry after failed readback does not push the approved content twice`() {
        val fixture = fixture(baseCommit = "c1")
        val baseFiles = files(feature("Login"))
        val proposedFiles = files(feature("Local login"))
        fixture.stateStorage.savePendingChangeset(
            changeset(baseCommit = "c1", operations = operations(baseFiles, proposedFiles, LOGIN_ID)),
        )
        whenever(fixture.git.readAt(PROJECT_ID, CONNECTION, "c1"))
            .thenReturn(OkfGitSnapshot("c1", "productspec/okf", baseFiles))
        whenever(fixture.git.commitAndPush(eq(PROJECT_ID), eq(CONNECTION), eq("c1"), any(), any()))
            .thenReturn(OkfGitCommitResult("c2", "productspec/okf"))
        whenever(fixture.git.fetch(PROJECT_ID, CONNECTION))
            .thenReturn(OkfGitSnapshot("c1", "productspec/okf", baseFiles))
            .thenThrow(OkfGitException("temporary fetch failure"))
            .thenReturn(OkfGitSnapshot("c2", "productspec/okf", proposedFiles))
        whenever(fixture.git.readAt(PROJECT_ID, CONNECTION, "c2"))
            .thenThrow(OkfGitException("temporary readback failure"))
            .thenReturn(OkfGitSnapshot("c2", "productspec/okf", proposedFiles))

        assertThrows(OkfPushException::class.java) {
            fixture.service.approve(PROJECT_ID, CHANGESET_ID)
        }
        assertEquals("c2", fixture.stateStorage.loadPendingChangeset(PROJECT_ID)?.approvedCommit)
        assertEquals("2026-07-20T12:00:00Z", fixture.stateStorage.loadPendingChangeset(PROJECT_ID)?.updatedAt)

        val approved = fixture.service.approve(PROJECT_ID, CHANGESET_ID)

        assertEquals("c2", approved.approvedCommit)
        assertEquals("2026-07-20T12:00:00Z", approved.updatedAt)
        verify(fixture.git, times(1)).commitAndPush(eq(PROJECT_ID), eq(CONNECTION), eq("c1"), any(), any())
    }

    @Test
    fun `sync finalizes a pushed pending marker instead of deleting its receipt`() {
        val fixture = fixture(baseCommit = "c1")
        val baseFiles = files(feature("Login"))
        val proposedFiles = files(feature("Local login"))
        fixture.stateStorage.savePendingChangeset(
            changeset(baseCommit = "c1", operations = operations(baseFiles, proposedFiles, LOGIN_ID))
                .copy(approvedCommit = "c2"),
        )
        whenever(fixture.git.readAt(PROJECT_ID, CONNECTION, "c1"))
            .thenReturn(OkfGitSnapshot("c1", "productspec/okf", baseFiles))
        whenever(fixture.git.readAt(PROJECT_ID, CONNECTION, "c2"))
            .thenReturn(OkfGitSnapshot("c2", "productspec/okf", proposedFiles))
        whenever(fixture.git.fetch(PROJECT_ID, CONNECTION))
            .thenReturn(OkfGitSnapshot("c2", "productspec/okf", proposedFiles))

        val state = fixture.service.sync(PROJECT_ID)

        assertEquals(OkfSyncStatus.CLEAN, state.syncStatus)
        assertEquals(null, fixture.stateStorage.loadPendingChangeset(PROJECT_ID))
        assertEquals("c2", fixture.stateStorage.loadApprovedChangeset(PROJECT_ID, CHANGESET_ID)?.approvedCommit)
    }

    @Test
    fun `conflict resolution creates a newly reviewable diff`() {
        val fixture = fixture(baseCommit = "c1")
        val baseFiles = files(feature("Login"))
        val proposedFiles = files(feature("Local login"))
        val remoteFiles = files(feature("Remote login"))
        fixture.projectionStorage.replaceRawFiles(PROJECT_ID, remoteFiles)
        fixture.stateStorage.savePendingChangeset(
            changeset(baseCommit = "c1", operations = operations(baseFiles, proposedFiles, LOGIN_ID)),
        )
        fixture.stateStorage.saveConflict(
            PROJECT_ID,
            com.agentwork.productspecagent.domain.okf.OkfConflict(
                CHANGESET_ID,
                LOGIN_ID,
                "features/local-login.md",
                baseFiles.values.firstOrNull { it.contains(LOGIN_ID) },
                remoteFiles.values.firstOrNull { it.contains(LOGIN_ID) },
                proposedFiles.values.firstOrNull { it.contains(LOGIN_ID) },
            ),
        )
        whenever(fixture.git.fetch(PROJECT_ID, CONNECTION))
            .thenReturn(OkfGitSnapshot("c2", "productspec/okf", remoteFiles))
        whenever(fixture.git.readAt(PROJECT_ID, CONNECTION, "c1"))
            .thenReturn(OkfGitSnapshot("c1", "productspec/okf", baseFiles))

        val resolved = fixture.service.resolve(PROJECT_ID, CHANGESET_ID, OkfConflictResolution.PROPOSED)!!

        assertEquals("c2", resolved.baseCommit)
        assertTrue(resolved.operations.any { it.documentId == LOGIN_ID && it.content?.contains("Local login") == true })
        assertEquals(OkfSyncStatus.PROPOSAL_PENDING, fixture.stateStorage.loadState(PROJECT_ID).syncStatus)
        assertEquals(null, fixture.stateStorage.loadConflict(PROJECT_ID))
    }

    private fun fixture(
        baseCommit: String? = null,
        mode: OkfMode = OkfMode.OKF_ACTIVE,
    ): Fixture {
        val objectStore = InMemoryObjectStore()
        val parser = OkfParser()
        val projectionStorage = OkfProjectionStorage(objectStore, parser)
        val stateStorage = OkfStateStorage(objectStore)
        val presentationStorage = OkfPresentationStorage(objectStore)
        val codec = OkfFeatureCodec(parser)
        val projectionService = OkfProjectionService(
            projectionStorage,
            presentationStorage,
            parser,
            OkfProfileValidator(),
            codec,
        )
        val git = mock<OkfGitClient>()
        stateStorage.saveState(
            OkfProjectState(PROJECT_ID, mode = mode, connection = CONNECTION, baseCommit = baseCommit),
        )
        val service = OkfSyncService(
            git,
            stateStorage,
            projectionService,
            OkfThreeWayPlanner(codec),
            parser,
        )
        return Fixture(git, stateStorage, projectionStorage, service)
    }

    private fun feature(title: String, id: String = LOGIN_ID) = WizardFeature(id = id, title = title, description = "$title works.")

    private fun files(vararg features: WizardFeature): Map<String, String> {
        val parser = OkfParser()
        val bundle = OkfFeatureCodec(parser).encode(
            WizardFeatureGraph(features.toList()),
            null,
            OffsetDateTime.parse("2026-07-20T12:00:00Z"),
        )
        return bundle.documents.associate { it.path to parser.render(it) }
    }

    private fun operations(
        base: Map<String, String>,
        proposed: Map<String, String>,
        documentId: String,
    ): List<OkfFileOperation> {
        val basePath = base.keys.firstOrNull { base[it]?.contains(documentId) == true }
        val proposedPath = proposed.keys.firstOrNull { proposed[it]?.contains(documentId) == true }
        val featurePath = proposedPath ?: requireNotNull(basePath)
        val featureOperation = OkfFileOperation(
            type = when {
                basePath == null -> OkfOperationType.ADD
                proposedPath == null -> OkfOperationType.DELETE
                basePath != proposedPath -> OkfOperationType.RENAME
                else -> OkfOperationType.UPDATE
            },
            path = featurePath,
            previousPath = basePath?.takeIf { proposedPath != null && it != proposedPath },
            baseContent = basePath?.let(base::get),
            content = proposedPath?.let(proposed::get),
            documentId = documentId,
        )
        val indexOperations = (base.keys + proposed.keys)
            .filter { it.endsWith("index.md") && base[it] != proposed[it] }
            .distinct()
            .map { path ->
                OkfFileOperation(
                    type = when {
                        path !in base -> OkfOperationType.ADD
                        path !in proposed -> OkfOperationType.DELETE
                        else -> OkfOperationType.UPDATE
                    },
                    path = path,
                    baseContent = base[path],
                    content = proposed[path],
                )
            }
        return listOf(featureOperation) + indexOperations
    }

    private fun changeset(
        kind: OkfChangesetKind = OkfChangesetKind.EDIT,
        baseCommit: String,
        operations: List<OkfFileOperation>,
    ) = OkfChangeset(
        id = CHANGESET_ID,
        projectId = PROJECT_ID,
        kind = kind,
        baseCommit = baseCommit,
        operations = operations,
        issues = emptyList(),
        createdAt = "2026-07-20T12:00:00Z",
        updatedAt = "2026-07-20T12:00:00Z",
    )

    private data class Fixture(
        val git: OkfGitClient,
        val stateStorage: OkfStateStorage,
        val projectionStorage: OkfProjectionStorage,
        val service: OkfSyncService,
    )

    private companion object {
        const val PROJECT_ID = "project-1"
        const val CHANGESET_ID = "changeset-1"
        const val LOGIN_ID = "11111111-1111-4111-8111-111111111111"
        const val DASHBOARD_ID = "22222222-2222-4222-8222-222222222222"
        val CONNECTION = OkfGitConnection("https://git.example/repo.git")
    }
}

package com.agentwork.productspecagent.service

import com.agentwork.productspecagent.domain.*
import com.agentwork.productspecagent.export.DocsScaffoldGenerator
import com.agentwork.productspecagent.domain.okf.OkfMode
import com.agentwork.productspecagent.domain.okf.OkfProjectState
import com.agentwork.productspecagent.storage.InMemoryObjectStore
import com.agentwork.productspecagent.storage.OkfStateStorage
import com.agentwork.productspecagent.storage.ProjectStorage
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ProjectServiceTest {

    private lateinit var storage: ProjectStorage
    private lateinit var service: ProjectService
    private lateinit var wizardService: WizardService

    @BeforeEach
    fun setUp() {
        storage = ProjectStorage(InMemoryObjectStore())
        service = ProjectService(storage)
        wizardService = WizardService(storage)
    }

    private fun listDocsRelativePaths(projectId: String): Set<String> =
        storage.listDocsFiles(projectId).map { it.first }.toSet()

    @Test
    fun `createProject saves project and flowState`() {
        val response = service.createProject("My Project")

        assertEquals("My Project", response.project.name)
        assertEquals(ProjectStatus.DRAFT, response.project.status)
        assertEquals("anonymous", response.project.ownerId)
        assertEquals(FlowStepType.IDEA, response.flowState.currentStep)
        assertEquals(9, response.flowState.steps.size)
        assertTrue(response.flowState.steps.any { it.stepType == FlowStepType.REVIEW })

        // Verify persisted
        assertNotNull(storage.loadProject(response.project.id))
        assertNotNull(storage.loadFlowState(response.project.id))
    }

    @Test
    fun `getProject returns project and flowState`() {
        val created = service.createProject("Test")
        val response = service.getProject(created.project.id)

        assertEquals(created.project.id, response.project.id)
        assertEquals(created.flowState.currentStep, response.flowState.currentStep)
    }

    @Test
    fun `getProject throws ProjectNotFoundException when project not found`() {
        assertThrows(ProjectNotFoundException::class.java) {
            service.getProject("ghost")
        }
    }

    @Test
    fun `deleteProject removes project`() {
        val created = service.createProject("Delete Me")
        service.deleteProject(created.project.id)

        assertThrows(ProjectNotFoundException::class.java) {
            service.getProject(created.project.id)
        }
    }

    @Test
    fun `deleteProject throws ProjectNotFoundException when project not found`() {
        assertThrows(ProjectNotFoundException::class.java) {
            service.deleteProject("ghost")
        }
    }

    @Test
    fun `listProjects returns all projects`() {
        service.createProject("P1")
        service.createProject("P2")

        val result = service.listProjects()
        assertEquals(2, result.size)
    }

    @Test
    fun `listProjects returns empty list when no projects`() {
        assertEquals(0, service.listProjects().size)
    }

    @Test
    fun `getFlowState returns flowState for existing project`() {
        val created = service.createProject("Flow")
        val result = service.getFlowState(created.project.id)

        assertEquals(created.project.id, result.projectId)
        assertEquals(FlowStepType.IDEA, result.currentStep)
    }

    @Test
    fun `getFlowState throws ProjectNotFoundException when project not found`() {
        assertThrows(ProjectNotFoundException::class.java) {
            service.getFlowState("ghost")
        }
    }

    @Test
    fun `createProject initializes wizard IDEA productName with project name`() {
        val response = service.createProject("TaskFlow Pro")
        val wizard = wizardService.getWizardData(response.project.id)
        assertEquals(
            JsonPrimitive("TaskFlow Pro"),
            wizard.steps["IDEA"]?.fields?.get("productName")
        )
    }

    @Test
    fun `createProject does not create spec idea md`() {
        val response = service.createProject("TaskFlow Pro")
        assertNull(service.readSpecFile(response.project.id, "idea.md"))
    }

    @Test
    fun `regenerateDocsScaffold removes orphaned files no longer produced by generator`() {
        val syncingService = ProjectService(storage, DocsScaffoldGenerator())
        val response = syncingService.createProject("Sync Test")
        val projectId = response.project.id

        // Simulate orphans from prior generations: a deleted feature, a renamed feature,
        // non-target docs in legacy subfolders, and the deprecated scaffold outputs.
        storage.saveDocsFile(projectId, "docs/features/01-old-deleted-feature.md", "stale")
        storage.saveDocsFile(projectId, "docs/features/02-renamed-old-slug.md", "stale")
        storage.saveDocsFile(projectId, "docs/architecture/legacy-overview.md", "stale")
        storage.saveDocsFile(projectId, "docs/backend/legacy-api.md", "stale")
        storage.saveDocsFile(projectId, "docs/frontend/legacy-design.md", "stale")
        storage.saveDocsFile(projectId, "docs/architecture/overview.md", "stale")
        storage.saveDocsFile(projectId, "docs/backend/api.md", "stale")
        storage.saveDocsFile(projectId, "docs/frontend/design.md", "stale")

        syncingService.regenerateDocsScaffold(projectId)

        val paths = listDocsRelativePaths(projectId)
        assertFalse("docs/features/01-old-deleted-feature.md" in paths)
        assertFalse("docs/features/02-renamed-old-slug.md" in paths)
        assertFalse("docs/architecture/overview.md" in paths)
        assertFalse("docs/backend/api.md" in paths)
        assertFalse("docs/frontend/design.md" in paths)
        assertTrue("docs/architecture/legacy-overview.md" in paths)
        assertTrue("docs/backend/legacy-api.md" in paths)
        assertTrue("docs/frontend/legacy-design.md" in paths)

        // Generator's canonical files must remain
        assertTrue("docs/features/00-feature-set-overview.md" in paths)
    }

    @Test
    fun `regenerateDocsScaffold preserves clarifications decisions tasks and uploads under docs`() {
        // Regression: an earlier diff-sync wiped everything under docs/ that wasn't
        // generator output, destroying entities owned by other storages.
        val syncingService = ProjectService(storage, DocsScaffoldGenerator())
        val response = syncingService.createProject("Preserve Test")
        val projectId = response.project.id

        // Simulate entities written by other storages (paths match Clarification/Decision/
        // Task/UploadStorage layouts: all under projects/{id}/docs/{kind}/).
        storage.saveDocsFile(projectId, "docs/clarifications/clar-1.json", """{"id":"clar-1"}""")
        storage.saveDocsFile(projectId, "docs/decisions/dec-1.json", """{"id":"dec-1"}""")
        storage.saveDocsFile(projectId, "docs/tasks/task-1.json", """{"id":"task-1"}""")
        storage.saveDocsFile(projectId, "docs/uploads/file.pdf", "binary")

        syncingService.regenerateDocsScaffold(projectId)

        val paths = listDocsRelativePaths(projectId)
        assertTrue("docs/clarifications/clar-1.json" in paths, "clarification was deleted")
        assertTrue("docs/decisions/dec-1.json" in paths, "decision was deleted")
        assertTrue("docs/tasks/task-1.json" in paths, "task was deleted")
        assertTrue("docs/uploads/file.pdf" in paths, "upload was deleted")
    }

    @Test
    fun `regenerateDocsScaffold leaves non-docs project files untouched`() {
        val syncingService = ProjectService(storage, DocsScaffoldGenerator())
        val response = syncingService.createProject("Sync Test")
        val projectId = response.project.id

        // Spec files live under projects/{id}/spec/ — must not be affected by docs sync.
        storage.saveSpecStep(projectId, "idea.md", "# Idea")

        syncingService.regenerateDocsScaffold(projectId)

        assertEquals("# Idea", storage.loadSpecStep(projectId, "idea.md"))
    }

    @Test
    fun `regenerateDocsScaffold preserves docs design contents`() {
        // Convention: DocsScaffoldGenerator never emits under docs/design/, so the
        // diff-sync's managedDirs computation never includes that path, so user-uploaded
        // design-bundle files survive every spec-save / regen.
        val syncingService = ProjectService(storage, DocsScaffoldGenerator())
        val response = syncingService.createProject("Design Preserve Test")
        val projectId = response.project.id

        // Simulate a design bundle written by DesignBundleStorage.
        storage.saveDocsFile(projectId, "docs/design/manifest.json", """{"projectId":"x"}""")
        storage.saveDocsFile(projectId, "docs/design/Scheduler.html", "<html/>")
        storage.saveDocsFile(projectId, "docs/design/design-canvas.jsx", "// canvas")

        syncingService.regenerateDocsScaffold(projectId)

        val paths = listDocsRelativePaths(projectId)
        assertTrue("docs/design/manifest.json" in paths, "manifest was deleted")
        assertTrue("docs/design/Scheduler.html" in paths, "html was deleted")
        assertTrue("docs/design/design-canvas.jsx" in paths, "jsx was deleted")
    }

    @Test
    fun `active okf project suppresses legacy feature scaffold only`() {
        val objectStore = InMemoryObjectStore()
        val activeStorage = ProjectStorage(objectStore)
        val okfStateStorage = OkfStateStorage(objectStore)
        val activeService = ProjectService(
            activeStorage,
            DocsScaffoldGenerator(),
            okfStateStorage = okfStateStorage,
        )
        val projectId = activeService.createProject("OKF Project").project.id
        activeStorage.saveDocsFile(projectId, "docs/tasks/task-1.json", "{}")
        activeStorage.saveDocsFile(projectId, "docs/uploads/source.txt", "source")
        activeStorage.saveDocsFile(projectId, "docs/design/screen.html", "<html/>")
        activeStorage.saveDocsFile(projectId, "docs/okf/index.md", "# OKF")
        okfStateStorage.saveState(OkfProjectState(projectId, mode = OkfMode.OKF_ACTIVE))

        activeService.regenerateDocsScaffold(projectId)

        val paths = activeStorage.listDocsFiles(projectId).map { it.first }
        assertFalse(paths.any { it.startsWith("docs/features/") })
        assertTrue("docs/tasks/task-1.json" in paths)
        assertTrue("docs/uploads/source.txt" in paths)
        assertTrue("docs/design/screen.html" in paths)
        assertTrue("docs/okf/index.md" in paths)
    }
}

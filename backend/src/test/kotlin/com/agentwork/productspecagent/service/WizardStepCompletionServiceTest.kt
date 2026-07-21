package com.agentwork.productspecagent.service

import com.agentwork.productspecagent.agent.DecisionAgent
import com.agentwork.productspecagent.agent.PlanGeneratorAgent
import com.agentwork.productspecagent.agent.SpecContextBuilder
import com.agentwork.productspecagent.domain.FlowStepStatus
import com.agentwork.productspecagent.domain.FlowStepType
import com.agentwork.productspecagent.domain.ProductCategory
import com.agentwork.productspecagent.domain.SpecTask
import com.agentwork.productspecagent.domain.TaskType
import com.agentwork.productspecagent.domain.WizardStepData
import com.agentwork.productspecagent.service.okf.OkfFeatureService
import com.agentwork.productspecagent.storage.ClarificationStorage
import com.agentwork.productspecagent.storage.DecisionStorage
import com.agentwork.productspecagent.storage.DesignWorkbenchStorage
import com.agentwork.productspecagent.storage.InMemoryObjectStore
import com.agentwork.productspecagent.storage.ProjectStorage
import com.agentwork.productspecagent.storage.TaskStorage
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class WizardStepCompletionServiceTest {

    private lateinit var projectService: ProjectService
    private lateinit var contextBuilder: SpecContextBuilder
    private lateinit var promptService: PromptService
    private lateinit var decisionService: DecisionService
    private lateinit var clarificationService: ClarificationService
    private lateinit var wizardService: WizardService
    private lateinit var taskService: TaskService
    private lateinit var designWorkbenchStorage: DesignWorkbenchStorage
    private lateinit var applyAgent: CapturingApplyAgent

    @BeforeEach
    fun setup() {
        val projectStorage = ProjectStorage(InMemoryObjectStore())
        projectService = ProjectService(projectStorage)
        contextBuilder = SpecContextBuilder(projectService)
        promptService = PromptService(PromptRegistry(), InMemoryObjectStore())

        val decisionAgent = object : DecisionAgent(contextBuilder, promptService) {
            override suspend fun runAgent(prompt: String): String =
                """{"options":[{"label":"Yes","pros":["pro"],"cons":[],"recommended":true}],"recommendation":"Yes"}"""
        }
        decisionService = DecisionService(DecisionStorage(InMemoryObjectStore()), decisionAgent)
        clarificationService = ClarificationService(ClarificationStorage(InMemoryObjectStore()))
        wizardService = WizardService(projectStorage)

        val planAgent = object : PlanGeneratorAgent(contextBuilder, promptService) {
            override suspend fun generatePlanForFeature(
                projectId: String,
                input: WizardFeatureInput,
                startPriority: Int,
            ): List<SpecTask> {
                val now = java.time.Instant.now().toString()
                return listOf(
                    SpecTask(
                        id = "epic-${input.id}",
                        projectId = projectId,
                        type = TaskType.EPIC,
                        title = input.title,
                        estimate = "M",
                        priority = startPriority,
                        specSection = FlowStepType.FEATURES,
                        createdAt = now,
                        updatedAt = now,
                    )
                )
            }
        }
        taskService = TaskService(TaskStorage(InMemoryObjectStore()), planAgent)
        designWorkbenchStorage = DesignWorkbenchStorage(InMemoryObjectStore())
        applyAgent = CapturingApplyAgent(
            WizardBlockerApplyResult(
                message = "Antwort wurde eingearbeitet.",
                fieldUpdates = mapOf("primaryAudience" to JsonPrimitive("B2B SaaS teams")),
                appliedFields = listOf("primaryAudience"),
            )
        )
    }

    @Test
    fun `complete IDEA advances flow without writing step spec and captures prompts`() = runBlocking {
        val project = projectService.createProject("Test")
        val agent = CapturingWizardAgent("Looks good.")
        val completion = createCompletion(agent)

        val result = completion.complete(
            CompleteWizardStep(
                projectId = project.project.id,
                step = FlowStepType.IDEA,
                fields = mapOf(
                    "productName" to "MeinTool",
                    "vision" to "SaaS tool",
                    "category" to "SaaS",
                ),
                locale = "de",
            )
        )

        assertThat(result.message).isEqualTo("Looks good.")
        assertThat(result.nextStep).isEqualTo(FlowStepType.PROBLEM)
        assertThat(result.exportTriggered).isFalse()
        assertThat(result.progression.currentStep).isEqualTo(FlowStepType.PROBLEM.name)
        assertThat(result.progression.primaryAction.type).isEqualTo("COMPLETE_STEP")
        assertThat(result.progression.primaryAction.step).isEqualTo(FlowStepType.PROBLEM.name)
        assertThat(result.action.type).isEqualTo("SHOW_STEP")
        assertThat(result.action.step).isEqualTo(FlowStepType.PROBLEM.name)
        assertThat(result.artifacts.decisionIds).isEmpty()
        assertThat(result.artifacts.clarificationIds).isEmpty()

        val flowState = projectService.getFlowState(project.project.id)
        assertThat(flowState.currentStep).isEqualTo(FlowStepType.PROBLEM)
        assertThat(flowState.steps.first { it.stepType == FlowStepType.IDEA }.status)
            .isEqualTo(FlowStepStatus.COMPLETED)
        assertThat(flowState.steps.first { it.stepType == FlowStepType.PROBLEM }.status)
            .isEqualTo(FlowStepStatus.IN_PROGRESS)

        assertThat(projectService.readSpecFile(project.project.id, "idea.md")).isNull()
        assertThat(projectService.listSpecFiles(project.project.id)).isEmpty()

        assertThat(agent.calls).hasSize(1)
        assertThat(agent.calls.single().systemPrompt)
            .contains("IMPORTANT: Always respond in Deutsch (de).")
            .contains("=== WIZARD CONTEXT ===")
        assertThat(agent.calls.single().userPrompt)
            .contains("The user just completed the IDEA wizard step")
            .contains("productName: MeinTool")
        Unit
    }

    @Test
    fun `complete non-final step creates clarification from marker`() = runBlocking {
        val project = projectService.createProject("Test")
        setFlowProgress(project.project.id, FlowStepType.PROBLEM, setOf(FlowStepType.IDEA))
        val agent = CapturingWizardAgent(
            "Gut.\n[CLARIFICATION_NEEDED]: Wer ist die Zielgruppe? | Grundlage fuer alles weitere"
        )
        val completion = createCompletion(agent)

        val result = completion.complete(
            CompleteWizardStep(
                projectId = project.project.id,
                step = FlowStepType.PROBLEM,
                fields = mapOf("description" to "Kurz"),
            )
        )

        assertThat(result.message).isEqualTo("Gut.")
        assertThat(result.clarificationId).isNotNull()
        assertThat(result.nextStep).isNull()
        assertThat(result.exportTriggered).isFalse()
        assertThat(result.progression.currentStep).isEqualTo(FlowStepType.PROBLEM.name)
        assertThat(result.progression.primaryAction.type).isEqualTo("COMPLETE_STEP")
        assertThat(result.progression.primaryAction.step).isEqualTo(FlowStepType.PROBLEM.name)
        assertThat(result.action.type).isEqualTo("STAY")
        assertThat(clarificationService.listClarifications(project.project.id)).hasSize(1)

        val flowState = projectService.getFlowState(project.project.id)
        assertThat(flowState.currentStep).isEqualTo(FlowStepType.PROBLEM)
        assertThat(flowState.steps.first { it.stepType == FlowStepType.PROBLEM }.status)
            .isEqualTo(FlowStepStatus.IN_PROGRESS)
        assertThat(flowState.steps.first { it.stepType == FlowStepType.FEATURES }.status)
            .isEqualTo(FlowStepStatus.OPEN)
        Unit
    }

    @Test
    fun `answered clarification prevents repeated marker from blocking completion again`() = runBlocking {
        val project = projectService.createProject("Test")
        setFlowProgress(project.project.id, FlowStepType.PROBLEM, setOf(FlowStepType.IDEA))
        val clarification = clarificationService.createClarification(
            project.project.id,
            "Wer ist die Zielgruppe?",
            "Grundlage fuer alles weitere",
            FlowStepType.PROBLEM,
        )
        clarificationService.answerClarification(project.project.id, clarification.id, "Teams in KMU")
        val agent = CapturingWizardAgent(
            "Gut.\n[CLARIFICATION_NEEDED]: Wer ist die Zielgruppe? | Grundlage fuer alles weitere"
        )
        val completion = createCompletion(agent)

        val result = completion.complete(
            CompleteWizardStep(
                projectId = project.project.id,
                step = FlowStepType.PROBLEM,
                fields = mapOf("description" to "Kurz"),
            )
        )

        assertThat(result.nextStep).isEqualTo(FlowStepType.FEATURES)
        assertThat(result.clarificationId).isNull()
        assertThat(result.action.type).isEqualTo("SHOW_STEP")
        assertThat(result.action.step).isEqualTo(FlowStepType.FEATURES.name)
        assertThat(clarificationService.listClarifications(project.project.id)).hasSize(1)

        val flowState = projectService.getFlowState(project.project.id)
        assertThat(flowState.currentStep).isEqualTo(FlowStepType.FEATURES)
        assertThat(flowState.steps.first { it.stepType == FlowStepType.PROBLEM }.status)
            .isEqualTo(FlowStepStatus.COMPLETED)
        assertThat(flowState.steps.first { it.stepType == FlowStepType.FEATURES }.status)
            .isEqualTo(FlowStepStatus.IN_PROGRESS)
        Unit
    }

    @Test
    fun `answered unapplied clarification is applied to wizard data and advances without completion agent`() = runBlocking {
        val project = projectService.createProject("Test")
        setFlowProgress(project.project.id, FlowStepType.PROBLEM, setOf(FlowStepType.IDEA))
        wizardService.saveStepData(
            project.project.id,
            "PROBLEM",
            WizardStepData(fields = mapOf("painPoints" to JsonPrimitive("Existing pain"))),
        )
        val clarification = clarificationService.createClarification(
            project.project.id,
            "Wer ist die Zielgruppe?",
            "Grundlage fuer alles weitere",
            FlowStepType.PROBLEM,
        )
        clarificationService.answerClarification(project.project.id, clarification.id, "B2B SaaS teams")
        val completionAgent = CapturingWizardAgent("Should not be called.")
        val completion = createCompletion(completionAgent)

        val result = completion.complete(
            CompleteWizardStep(
                projectId = project.project.id,
                step = FlowStepType.PROBLEM,
                fields = mapOf("coreProblem" to "Zu unklar", "primaryAudience" to ""),
            )
        )

        assertThat(completionAgent.calls).isEmpty()
        assertThat(applyAgent.calls).hasSize(1)
        assertThat(applyAgent.calls.single().fields["painPoints"]).isEqualTo(JsonPrimitive("Existing pain"))
        assertThat(applyAgent.calls.single().fields["coreProblem"]).isEqualTo(JsonPrimitive("Zu unklar"))
        assertThat(result.message).isEqualTo("Antwort wurde eingearbeitet.")
        assertThat(result.nextStep).isEqualTo(FlowStepType.FEATURES)
        assertThat(result.action.type).isEqualTo("SHOW_STEP")
        assertThat(result.wizardDataChanged).isTrue()
        assertThat(result.appliedClarificationIds).containsExactly(clarification.id)

        val wizardData = wizardService.getWizardData(project.project.id)
        assertThat(wizardData.steps["PROBLEM"]?.fields?.get("primaryAudience"))
            .isEqualTo(JsonPrimitive("B2B SaaS teams"))
        assertThat(clarificationService.getClarification(project.project.id, clarification.id).appliedAt).isNotNull()
        Unit
    }

    @Test
    fun `resolved unapplied decision is applied to wizard data and advances without completion agent`() = runBlocking {
        val project = projectService.createProject("Test")
        setFlowProgress(project.project.id, FlowStepType.PROBLEM, setOf(FlowStepType.IDEA))
        val decision = decisionService.createDecision(project.project.id, "Which audience?", FlowStepType.PROBLEM)
        decisionService.resolveDecision(project.project.id, decision.id, "opt-1", "B2B SaaS teams")
        val completionAgent = CapturingWizardAgent("Should not be called.")
        val completion = createCompletion(completionAgent)

        val result = completion.complete(
            CompleteWizardStep(
                projectId = project.project.id,
                step = FlowStepType.PROBLEM,
                fields = mapOf("coreProblem" to "Zu unklar", "primaryAudience" to ""),
            )
        )

        assertThat(completionAgent.calls).isEmpty()
        assertThat(applyAgent.calls).hasSize(1)
        assertThat(result.appliedDecisionIds).containsExactly(decision.id)
        assertThat(result.wizardDataChanged).isTrue()

        val wizardData = wizardService.getWizardData(project.project.id)
        assertThat(wizardData.steps["PROBLEM"]?.fields?.get("primaryAudience"))
            .isEqualTo(JsonPrimitive("B2B SaaS teams"))
        assertThat(decisionService.getDecision(project.project.id, decision.id).appliedAt).isNotNull()
        Unit
    }

    @Test
    fun `apply agent field updates are filtered to allowed wizard step fields`() = runBlocking {
        applyAgent = CapturingApplyAgent(
            WizardBlockerApplyResult(
                message = "Antwort wurde eingearbeitet.",
                fieldUpdates = mapOf("notAField" to JsonPrimitive("bad")),
                appliedFields = listOf("notAField"),
            )
        )
        val project = projectService.createProject("Test")
        setFlowProgress(project.project.id, FlowStepType.PROBLEM, setOf(FlowStepType.IDEA))
        val decision = decisionService.createDecision(project.project.id, "Which audience?", FlowStepType.PROBLEM)
        decisionService.resolveDecision(project.project.id, decision.id, "opt-1", "B2B SaaS teams")
        val completion = createCompletion(CapturingWizardAgent("Should not be called."))

        completion.complete(
            CompleteWizardStep(
                projectId = project.project.id,
                step = FlowStepType.PROBLEM,
                fields = mapOf("coreProblem" to "Zu unklar", "primaryAudience" to ""),
            )
        )

        val problemFields = wizardService.getWizardData(project.project.id).steps["PROBLEM"]?.fields.orEmpty()
        assertThat(problemFields).doesNotContainKey("notAField")
        assertThat(decisionService.getDecision(project.project.id, decision.id).appliedFields).isEmpty()
        Unit
    }

    @Test
    fun `already applied clarification is not applied twice`() = runBlocking {
        val project = projectService.createProject("Test")
        setFlowProgress(project.project.id, FlowStepType.PROBLEM, setOf(FlowStepType.IDEA))
        val clarification = clarificationService.createClarification(
            project.project.id,
            "Wer ist die Zielgruppe?",
            "Grundlage fuer alles weitere",
            FlowStepType.PROBLEM,
        )
        val answered = clarificationService.answerClarification(project.project.id, clarification.id, "B2B SaaS teams")
        clarificationService.markApplied(project.project.id, answered.id, listOf("primaryAudience"))
        val completionAgent = CapturingWizardAgent("Looks good.")
        val completion = createCompletion(completionAgent)

        val result = completion.complete(
            CompleteWizardStep(
                projectId = project.project.id,
                step = FlowStepType.PROBLEM,
                fields = mapOf("coreProblem" to "Zu unklar", "primaryAudience" to "B2B SaaS teams"),
            )
        )

        assertThat(applyAgent.calls).isEmpty()
        assertThat(completionAgent.calls).hasSize(1)
        assertThat(result.nextStep).isEqualTo(FlowStepType.FEATURES)
        Unit
    }

    @Test
    fun `answered unapplied clarification on FEATURES syncs tasks from merged fields`() = runBlocking {
        applyAgent = CapturingApplyAgent(
            WizardBlockerApplyResult(
                message = "Feature wurde eingearbeitet.",
                fieldUpdates = mapOf(
                    "features" to WizardMarkdown.toJsonElement(
                        listOf(
                            mapOf(
                                "id" to "f-apply",
                                "title" to "Applied Feature",
                                "scopes" to listOf("BACKEND"),
                                "scopeFields" to mapOf("apiEndpoints" to "POST /apply"),
                            )
                        )
                    ),
                    "edges" to WizardMarkdown.toJsonElement(emptyList<Any>()),
                ),
                appliedFields = listOf("features", "edges"),
            )
        )
        val project = projectService.createProject("Test")
        wizardService.saveStepData(
            project.project.id,
            "IDEA",
            WizardStepData(fields = mapOf("category" to JsonPrimitive("SaaS"))),
        )
        setFlowProgress(project.project.id, FlowStepType.FEATURES, setOf(FlowStepType.IDEA, FlowStepType.PROBLEM))
        val clarification = clarificationService.createClarification(
            project.project.id,
            "Welche Features fehlen?",
            "Feature-Liste vervollstaendigen",
            FlowStepType.FEATURES,
        )
        clarificationService.answerClarification(project.project.id, clarification.id, "Applied Feature")
        val completionAgent = CapturingWizardAgent("Should not be called.")
        val completion = createCompletion(completionAgent)

        val result = completion.complete(
            CompleteWizardStep(
                projectId = project.project.id,
                step = FlowStepType.FEATURES,
                fields = mapOf(
                    "features" to emptyList<Any>(),
                    "edges" to emptyList<Any>(),
                ),
            )
        )

        assertThat(completionAgent.calls).isEmpty()
        assertThat(result.nextStep).isEqualTo(FlowStepType.MVP)
        assertThat(taskService.listTasks(project.project.id).map { it.id }).containsExactly("epic-f-apply")
        Unit
    }

    @Test
    fun `answered blocker on REVIEW uses normal final spec path without apply agent`() = runBlocking {
        val project = projectService.createProject("Test")
        setFlowProgress(
            project.project.id,
            FlowStepType.REVIEW,
            setOf(
                FlowStepType.IDEA,
                FlowStepType.PROBLEM,
                FlowStepType.FEATURES,
                FlowStepType.MVP,
                FlowStepType.DESIGN,
                FlowStepType.ARCHITECTURE,
                FlowStepType.BACKEND,
                FlowStepType.FRONTEND,
            ),
        )
        val clarification = clarificationService.createClarification(
            project.project.id,
            "Finale Rueckfrage?",
            "Sollte nicht per apply verarbeitet werden",
            FlowStepType.REVIEW,
        )
        clarificationService.answerClarification(project.project.id, clarification.id, "Bestaetigt")
        val agent = SequenceWizardAgent(
            listOf(
                "Review confirmed.",
                "# Product Specification\n\nDone.",
            )
        )
        val completion = createCompletion(agent)

        val result = completion.complete(
            CompleteWizardStep(
                projectId = project.project.id,
                step = FlowStepType.REVIEW,
                fields = mapOf("confirmed" to true),
            )
        )

        assertThat(applyAgent.calls).isEmpty()
        assertThat(agent.calls).hasSize(2)
        assertThat(result.exportTriggered).isTrue()
        assertThat(result.action.type).isEqualTo("OPEN_EXPORT")
        assertThat(projectService.readSpecFile(project.project.id, "spec.md"))
            .isEqualTo("# Product Specification\n\nDone.")
        Unit
    }

    @Test
    fun `mark clarification applied records applied fields`() {
        val project = projectService.createProject("Test")
        val clarification = clarificationService.createClarification(
            project.project.id,
            "Wer ist die Zielgruppe?",
            "Grundlage fuer alles weitere",
            FlowStepType.PROBLEM,
        )
        val answered = clarificationService.answerClarification(project.project.id, clarification.id, "Teams in KMU")

        val applied = clarificationService.markApplied(project.project.id, answered.id, listOf("primaryAudience"))

        assertThat(applied.appliedAt).isNotNull()
        assertThat(applied.appliedFields).containsExactly("primaryAudience")
        val reloaded = clarificationService.getClarification(project.project.id, answered.id)
        assertThat(reloaded.appliedAt).isEqualTo(applied.appliedAt)
        assertThat(reloaded.appliedFields).containsExactly("primaryAudience")
    }

    @Test
    fun `mark decision applied records applied fields`() = runBlocking {
        val project = projectService.createProject("Test")
        val decision = decisionService.createDecision(project.project.id, "Which audience?", FlowStepType.PROBLEM)
        val resolved = decisionService.resolveDecision(project.project.id, decision.id, "opt-1", "B2B teams")

        val applied = decisionService.markApplied(project.project.id, resolved.id, listOf("primaryAudience"))

        assertThat(applied.appliedAt).isNotNull()
        assertThat(applied.appliedFields).containsExactly("primaryAudience")
        val reloaded = decisionService.getDecision(project.project.id, resolved.id)
        assertThat(reloaded.appliedAt).isEqualTo(applied.appliedAt)
        assertThat(reloaded.appliedFields).containsExactly("primaryAudience")
        Unit
    }

    @Test
    fun `open clarification blocks completion without asking agent again`() = runBlocking {
        val project = projectService.createProject("Test")
        setFlowProgress(project.project.id, FlowStepType.PROBLEM, setOf(FlowStepType.IDEA))
        clarificationService.createClarification(
            project.project.id,
            "Wer ist die Zielgruppe?",
            "Grundlage fuer alles weitere",
            FlowStepType.PROBLEM,
        )
        val agent = CapturingWizardAgent(
            "Gut.\n[CLARIFICATION_NEEDED]: Wer ist die Zielgruppe? | Grundlage fuer alles weitere"
        )
        val completion = createCompletion(agent)

        val result = completion.complete(
            CompleteWizardStep(
                projectId = project.project.id,
                step = FlowStepType.PROBLEM,
                fields = mapOf("description" to "Kurz"),
            )
        )

        assertThat(result.nextStep).isNull()
        assertThat(result.clarificationId).isNull()
        assertThat(result.action.type).isEqualTo("STAY")
        assertThat(agent.calls).isEmpty()
        assertThat(clarificationService.listClarifications(project.project.id)).hasSize(1)

        val flowState = projectService.getFlowState(project.project.id)
        assertThat(flowState.currentStep).isEqualTo(FlowStepType.PROBLEM)
        assertThat(flowState.steps.first { it.stepType == FlowStepType.PROBLEM }.status)
            .isEqualTo(FlowStepStatus.IN_PROGRESS)
        Unit
    }

    @Test
    fun `complete FRONTEND advances to review without generating final spec`() = runBlocking {
        val project = projectService.createProject("Test")
        setFlowProgress(
            project.project.id,
            FlowStepType.FRONTEND,
            setOf(
                FlowStepType.IDEA,
                FlowStepType.PROBLEM,
                FlowStepType.FEATURES,
                FlowStepType.MVP,
                FlowStepType.DESIGN,
                FlowStepType.ARCHITECTURE,
                FlowStepType.BACKEND,
            ),
        )
        val agent = CapturingWizardAgent("Frontend looks good.")
        val completion = createCompletion(agent)

        val result = completion.complete(
            CompleteWizardStep(
                projectId = project.project.id,
                step = FlowStepType.FRONTEND,
                fields = mapOf("framework" to "Next.js+React", "theme" to "Both"),
            )
        )

        assertThat(result.message).isEqualTo("Frontend looks good.")
        assertThat(result.nextStep).isEqualTo(FlowStepType.REVIEW)
        assertThat(result.exportTriggered).isFalse()
        assertThat(result.progression.status).isEqualTo("IN_PROGRESS")
        assertThat(result.progression.currentStep).isEqualTo(FlowStepType.REVIEW.name)
        assertThat(result.progression.primaryAction.type).isEqualTo("COMPLETE_STEP")
        assertThat(result.progression.primaryAction.step).isEqualTo(FlowStepType.REVIEW.name)
        assertThat(result.action.type).isEqualTo("SHOW_STEP")
        assertThat(result.action.step).isEqualTo(FlowStepType.REVIEW.name)
        assertThat(projectService.readSpecFile(project.project.id, "spec.md")).isNull()
        Unit
    }

    @Test
    fun `complete REVIEW suppresses blocker markers and generates final spec`() = runBlocking {
        val project = projectService.createProject("Test")
        setFlowProgress(
            project.project.id,
            FlowStepType.REVIEW,
            setOf(
                FlowStepType.IDEA,
                FlowStepType.PROBLEM,
                FlowStepType.FEATURES,
                FlowStepType.MVP,
                FlowStepType.DESIGN,
                FlowStepType.ARCHITECTURE,
                FlowStepType.BACKEND,
                FlowStepType.FRONTEND,
            ),
        )
        val agent = SequenceWizardAgent(
            listOf(
                "Review confirmed.\n[DECISION_NEEDED]: Ignore this marker\n[CLARIFICATION_NEEDED]: Ignore? | final step",
                "# Product Specification\n\nDone.",
            )
        )
        val completion = createCompletion(agent)

        val result = completion.complete(
            CompleteWizardStep(
                projectId = project.project.id,
                step = FlowStepType.REVIEW,
                fields = mapOf("confirmed" to true),
            )
        )

        assertThat(result.message).isEqualTo("Review confirmed.")
        assertThat(result.nextStep).isNull()
        assertThat(result.exportTriggered).isTrue()
        assertThat(result.decisionId).isNull()
        assertThat(result.clarificationId).isNull()
        assertThat(result.progression.status).isEqualTo("READY_FOR_EXPORT")
        assertThat(result.progression.currentStep).isEqualTo(FlowStepType.REVIEW.name)
        assertThat(result.progression.primaryAction.type).isEqualTo("OPEN_EXPORT")
        assertThat(result.progression.steps.first { it.step == FlowStepType.REVIEW.name }.finalVisibleStep).isTrue()
        assertThat(result.action.type).isEqualTo("OPEN_EXPORT")
        assertThat(decisionService.listDecisions(project.project.id)).isEmpty()
        assertThat(clarificationService.listClarifications(project.project.id)).isEmpty()
        assertThat(projectService.readSpecFile(project.project.id, "spec.md"))
            .isEqualTo("# Product Specification\n\nDone.")
        assertThat(agent.calls).hasSize(2)
        for (call in agent.calls) {
            assertThat(call.userPrompt).doesNotContain("MANDATORY OUTPUT REQUIREMENT")
            assertThat(call.userPrompt).doesNotContain("Err on the side of including a marker")
        }
        Unit
    }

    @Test
    fun `complete final step instructs spec agent to reference design artifact when present`() = runBlocking {
        val project = projectService.createProject("Test")
        setFlowProgress(
            project.project.id,
            FlowStepType.REVIEW,
            setOf(
                FlowStepType.IDEA,
                FlowStepType.PROBLEM,
                FlowStepType.FEATURES,
                FlowStepType.MVP,
                FlowStepType.DESIGN,
                FlowStepType.ARCHITECTURE,
                FlowStepType.BACKEND,
                FlowStepType.FRONTEND,
            ),
        )
        designWorkbenchStorage.writeDesignSummary(project.project.id, "# Design\n\nGenerated dashboard design.")
        val agent = SequenceWizardAgent(
            listOf(
                "Final step complete.",
                "# Product Specification\n\nReady.",
            )
        )
        val completion = createCompletion(agent)

        completion.complete(
            CompleteWizardStep(
                projectId = project.project.id,
                step = FlowStepType.REVIEW,
                fields = mapOf("confirmed" to true),
            )
        )

        assertThat(agent.calls).hasSize(2)
        assertThat(agent.calls[1].userPrompt)
            .contains("A design artifact exists for this project.")
            .contains("design/design.md")
            .contains("design/screens/design/index.html")
            .contains("Do not duplicate the full design summary")
        assertThat(projectService.readSpecFile(project.project.id, "spec.md"))
            .contains("[design/design.md](../design/design.md)")
            .contains("[design/screens/design/index.html](../design/screens/design/index.html)")
        Unit
    }

    @Test
    fun `library completion advances from MVP to review`() = runBlocking {
        val project = projectService.createProject("Test")
        wizardService.saveStepData(
            project.project.id,
            "IDEA",
            WizardStepData(fields = mapOf("category" to JsonPrimitive("Library"))),
        )
        setFlowProgress(
            project.project.id,
            FlowStepType.MVP,
            setOf(FlowStepType.IDEA, FlowStepType.PROBLEM, FlowStepType.FEATURES),
        )
        val agent = CapturingWizardAgent("Done.")
        val completion = createCompletion(agent)

        val result = completion.complete(
            CompleteWizardStep(
                projectId = project.project.id,
                step = FlowStepType.MVP,
                fields = mapOf("scope" to "Core library API"),
            )
        )

        assertThat(result.nextStep).isEqualTo(FlowStepType.REVIEW)
        assertThat(result.exportTriggered).isFalse()

        val flowState = projectService.getFlowState(project.project.id)
        assertThat(flowState.currentStep).isEqualTo(FlowStepType.REVIEW)
        assertThat(flowState.steps.first { it.stepType == FlowStepType.MVP }.status)
            .isEqualTo(FlowStepStatus.COMPLETED)
        assertThat(flowState.steps.first { it.stepType == FlowStepType.REVIEW }.status)
            .isEqualTo(FlowStepStatus.IN_PROGRESS)
        assertThat(projectService.readSpecFile(project.project.id, "spec.md")).isNull()
        Unit
    }

    @Test
    fun `api completion advances from BACKEND to review`() = runBlocking {
        val project = projectService.createProject("Test")
        wizardService.saveStepData(
            project.project.id,
            "IDEA",
            WizardStepData(fields = mapOf("category" to JsonPrimitive("API"))),
        )
        setFlowProgress(
            project.project.id,
            FlowStepType.BACKEND,
            setOf(
                FlowStepType.IDEA,
                FlowStepType.PROBLEM,
                FlowStepType.FEATURES,
                FlowStepType.MVP,
                FlowStepType.ARCHITECTURE,
            ),
        )
        val agent = CapturingWizardAgent("Done.")
        val completion = createCompletion(agent)

        val result = completion.complete(
            CompleteWizardStep(
                projectId = project.project.id,
                step = FlowStepType.BACKEND,
                fields = mapOf("endpoints" to "GET /health"),
            )
        )

        assertThat(result.nextStep).isEqualTo(FlowStepType.REVIEW)
        assertThat(result.exportTriggered).isFalse()

        val flowState = projectService.getFlowState(project.project.id)
        assertThat(flowState.currentStep).isEqualTo(FlowStepType.REVIEW)
        assertThat(flowState.steps.first { it.stepType == FlowStepType.BACKEND }.status)
            .isEqualTo(FlowStepStatus.COMPLETED)
        assertThat(flowState.steps.first { it.stepType == FlowStepType.REVIEW }.status)
            .isEqualTo(FlowStepStatus.IN_PROGRESS)
        assertThat(projectService.readSpecFile(project.project.id, "spec.md")).isNull()
        Unit
    }

    @Test
    fun `library completion rejects future visible MVP step`() {
        val project = projectService.createProject("Test")
        wizardService.saveStepData(
            project.project.id,
            "IDEA",
            WizardStepData(fields = mapOf("category" to JsonPrimitive("Library"))),
        )
        val agent = CapturingWizardAgent("Should not be called.")
        val completion = createCompletion(agent)

        assertThatThrownBy {
            runBlocking {
                completion.complete(
                    CompleteWizardStep(
                        projectId = project.project.id,
                        step = FlowStepType.MVP,
                        fields = mapOf("scope" to "Core library API"),
                    )
                )
            }
        }
            .isInstanceOf(WizardStepNotCurrentException::class.java)
            .hasMessage("Wizard step MVP is not the current wizard step (IDEA)")
        assertThat(agent.calls).isEmpty()
    }

    @Test
    fun `library completion rejects hidden DESIGN step`() {
        val project = projectService.createProject("Test")
        wizardService.saveStepData(
            project.project.id,
            "IDEA",
            WizardStepData(fields = mapOf("category" to JsonPrimitive("Library"))),
        )
        val agent = CapturingWizardAgent("Should not be called.")
        val completion = createCompletion(agent)

        assertThatThrownBy {
            runBlocking {
                completion.complete(
                    CompleteWizardStep(
                        projectId = project.project.id,
                        step = FlowStepType.DESIGN,
                        fields = mapOf("style" to "Minimal"),
                    )
                )
            }
        }
            .isInstanceOf(WizardStepNotVisibleException::class.java)
            .hasMessage("Wizard step DESIGN is not visible for category ${ProductCategory.LIBRARY.wireValue}")
        assertThat(agent.calls).isEmpty()
    }

    @Test
    fun `complete FEATURES renders graph prompt and syncs wizard tasks`() = runBlocking {
        val project = projectService.createProject("Test")
        setFlowProgress(project.project.id, FlowStepType.FEATURES, setOf(FlowStepType.IDEA, FlowStepType.PROBLEM))
        val agent = CapturingWizardAgent("OK.")
        val completion = createCompletion(agent)

        completion.complete(
            CompleteWizardStep(
                projectId = project.project.id,
                step = FlowStepType.FEATURES,
                fields = mapOf(
                    "features" to listOf(
                        mapOf(
                            "id" to "f-1",
                            "title" to "Login",
                            "scopes" to listOf("BACKEND"),
                            "scopeFields" to mapOf("apiEndpoints" to "POST /auth/login"),
                        )
                    ),
                    "edges" to emptyList<Any>(),
                ),
            )
        )

        assertThat(agent.calls.single().userPrompt)
            .contains("Features & Dependencies")
            .contains("[f-1] Login (Backend)")
        assertThat(taskService.listTasks(project.project.id).map { it.id }).containsExactly("epic-f-1")
        Unit
    }

    @Test
    fun `active feature completion stops before flow mutation while a changeset is pending`() {
        val project = projectService.createProject("Test")
        setFlowProgress(
            project.project.id,
            FlowStepType.FEATURES,
            setOf(FlowStepType.IDEA, FlowStepType.PROBLEM),
        )
        val okfFeatures = mock<OkfFeatureService>()
        whenever(okfFeatures.isActive(project.project.id)).thenReturn(true)
        whenever(okfFeatures.hasPending(project.project.id)).thenReturn(true)
        val completion = createCompletion(
            CapturingWizardAgent("Review.\n[DECISION_NEEDED]: Choose login policy"),
            okfFeatures,
        )

        assertThatThrownBy {
            runBlocking {
                completion.complete(
                    CompleteWizardStep(
                        projectId = project.project.id,
                        step = FlowStepType.FEATURES,
                        fields = mapOf(
                            "features" to listOf(
                                mapOf(
                                    "id" to "f-1",
                                    "title" to "Login",
                                    "description" to "Users can sign in.",
                                    "scopes" to listOf("BACKEND"),
                                ),
                            ),
                            "edges" to emptyList<Any>(),
                        ),
                    ),
                )
            }
        }.isInstanceOf(OkfChangesetPendingException::class.java)

        val flowState = projectService.getFlowState(project.project.id)
        assertThat(flowState.currentStep).isEqualTo(FlowStepType.FEATURES)
        assertThat(flowState.steps.first { it.stepType == FlowStepType.FEATURES }.status)
            .isEqualTo(FlowStepStatus.IN_PROGRESS)
        assertThat(taskService.listTasks(project.project.id)).isEmpty()
        assertThat(decisionService.listDecisions(project.project.id)).isEmpty()
    }

    private fun createCompletion(
        agent: WizardCompletionAgent,
        okfFeatureService: OkfFeatureService? = null,
    ): WizardStepCompletion =
        WizardStepCompletionService(
            contextBuilder = contextBuilder,
            projectService = projectService,
            promptService = promptService,
            decisionService = decisionService,
            clarificationService = clarificationService,
            wizardService = wizardService,
            completionAgent = agent,
            applyAgent = applyAgent,
            taskService = taskService,
            designWorkbenchStorage = designWorkbenchStorage,
            okfFeatureService = okfFeatureService,
        )

    private fun setFlowProgress(
        projectId: String,
        currentStep: FlowStepType,
        completedSteps: Set<FlowStepType>,
    ) {
        val now = java.time.Instant.now().toString()
        val flowState = projectService.getFlowState(projectId)
        val updatedSteps = flowState.steps.map { step ->
            when (step.stepType) {
                in completedSteps -> step.copy(status = FlowStepStatus.COMPLETED, updatedAt = now)
                currentStep -> step.copy(status = FlowStepStatus.IN_PROGRESS, updatedAt = now)
                else -> step.copy(status = FlowStepStatus.OPEN, updatedAt = now)
            }
        }
        projectService.updateFlowState(projectId, flowState.copy(steps = updatedSteps, currentStep = currentStep))
    }

    private data class AgentCall(val systemPrompt: String, val userPrompt: String)

    private class CapturingWizardAgent(private val response: String) : WizardCompletionAgent {
        val calls = mutableListOf<AgentCall>()

        override suspend fun respond(systemPrompt: String, userPrompt: String): String {
            calls.add(AgentCall(systemPrompt, userPrompt))
            return response
        }
    }

    private class CapturingApplyAgent(private val result: WizardBlockerApplyResult) : WizardBlockerApplyAgent {
        val calls = mutableListOf<ApplyWizardBlockers>()

        override suspend fun apply(command: ApplyWizardBlockers): WizardBlockerApplyResult {
            calls.add(command)
            return result
        }
    }

    private class SequenceWizardAgent(private val responses: List<String>) : WizardCompletionAgent {
        val calls = mutableListOf<AgentCall>()

        override suspend fun respond(systemPrompt: String, userPrompt: String): String {
            calls.add(AgentCall(systemPrompt, userPrompt))
            return responses[calls.lastIndex]
        }
    }
}

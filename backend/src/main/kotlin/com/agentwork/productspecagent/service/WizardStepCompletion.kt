package com.agentwork.productspecagent.service

import com.agentwork.productspecagent.agent.KoogAgentRunner
import com.agentwork.productspecagent.agent.SpecContextBuilder
import com.agentwork.productspecagent.agent.AgentResponseMarkers
import com.agentwork.productspecagent.domain.Clarification
import com.agentwork.productspecagent.domain.ClarificationStatus
import com.agentwork.productspecagent.domain.Decision
import com.agentwork.productspecagent.domain.DecisionStatus
import com.agentwork.productspecagent.domain.FlowState
import com.agentwork.productspecagent.domain.FlowStepStatus
import com.agentwork.productspecagent.domain.FlowStepType
import com.agentwork.productspecagent.domain.ProductCategory
import com.agentwork.productspecagent.domain.WizardClientActionDto
import com.agentwork.productspecagent.domain.WizardCreatedArtifacts
import com.agentwork.productspecagent.domain.WizardProgressionView
import com.agentwork.productspecagent.domain.WizardStepData
import com.agentwork.productspecagent.domain.WizardStepView
import com.agentwork.productspecagent.domain.emptyWizardProgressionView
import com.agentwork.productspecagent.service.okf.OkfFeatureService
import com.agentwork.productspecagent.service.okf.OkfProjectLocks
import com.agentwork.productspecagent.storage.DesignWorkbenchStorage
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import java.time.Instant

interface WizardStepCompletion {
    suspend fun complete(command: CompleteWizardStep): WizardStepCompletionResult
}

data class CompleteWizardStep(
    val projectId: String,
    val step: FlowStepType,
    val fields: Map<String, Any>,
    val locale: String = "en",
)

data class WizardStepCompletionResult(
    val message: String,
    val nextStep: FlowStepType?,
    val exportTriggered: Boolean,
    val decisionId: String? = null,
    val clarificationId: String? = null,
    val progression: WizardProgressionView = emptyWizardProgressionView(),
    val action: WizardClientActionDto = stayClientAction,
    val artifacts: WizardCreatedArtifacts = WizardCreatedArtifacts(),
    val appliedDecisionIds: List<String> = emptyList(),
    val appliedClarificationIds: List<String> = emptyList(),
    val wizardDataChanged: Boolean = false,
)

class WizardStepNotVisibleException(
    step: FlowStepType,
    category: ProductCategory?,
) : RuntimeException("Wizard step ${step.name} is not visible for category ${category?.wireValue ?: "default"}")

class WizardStepNotCurrentException(
    step: FlowStepType,
    currentStep: String?,
) : RuntimeException("Wizard step ${step.name} is not the current wizard step (${currentStep ?: "none"})")

class OkfChangesetPendingException(projectId: String) :
    RuntimeException("feature changes for project $projectId must be approved before completing the step")

interface WizardCompletionAgent {
    suspend fun respond(systemPrompt: String, userPrompt: String): String
}

@Component
class KoogWizardCompletionAgent(
    private val koogRunner: KoogAgentRunner,
) : WizardCompletionAgent {
    override suspend fun respond(systemPrompt: String, userPrompt: String): String =
        koogRunner.run(AGENT_ID, systemPrompt, userPrompt)

    private companion object {
        const val AGENT_ID = "idea-to-spec"
    }
}

@Service
class WizardStepCompletionService(
    private val contextBuilder: SpecContextBuilder,
    private val projectService: ProjectService,
    private val promptService: PromptService,
    private val decisionService: DecisionService,
    private val clarificationService: ClarificationService,
    private val wizardService: WizardService,
    private val completionAgent: WizardCompletionAgent,
    private val applyAgent: WizardBlockerApplyAgent,
    private val designWorkbenchStorage: DesignWorkbenchStorage,
    private val taskService: TaskService? = null,
    private val progressionPolicy: WizardProgressionPolicy = WizardProgressionPolicy(),
    private val okfFeatureService: OkfFeatureService? = null,
) : WizardStepCompletion, WizardProgression {

    private val logger = LoggerFactory.getLogger(WizardStepCompletionService::class.java)

    override suspend fun complete(command: CompleteWizardStep): WizardStepCompletionResult {
        val wizardData = wizardService.getWizardData(command.projectId)
        val plan = progressionPolicy.planFor(wizardData)
        if (command.step !in plan.visibleSteps) {
            throw WizardStepNotVisibleException(command.step, plan.category)
        }
        val flowState = projectService.getFlowState(command.projectId)
        val statusesByStep = flowState.steps.associate { it.stepType to it.status }
        val currentStep = currentVisibleStep(plan, statusesByStep, flowState.currentStep)
        if (command.step != currentStep) {
            throw WizardStepNotCurrentException(command.step, currentStep?.name)
        }
        val openBlockers = countOpenStepBlockers(command.projectId, command.step)
        if (openBlockers.total > 0) {
            return WizardStepCompletionResult(
                message = openBlockers.message(),
                nextStep = null,
                exportTriggered = false,
                progression = snapshotFor(command.projectId),
                action = stayClientAction,
            )
        }
        val isLastStep = plan.isTerminal(command.step)
        val unappliedDecisions = answeredUnappliedDecisions(command.projectId, command.step)
        val unappliedClarifications = answeredUnappliedClarifications(command.projectId, command.step)
        if (!isLastStep && (unappliedDecisions.isNotEmpty() || unappliedClarifications.isNotEmpty())) {
            return applyAnsweredBlockers(
                command = command,
                flowState = flowState,
                plan = plan,
                decisions = unappliedDecisions,
                clarifications = unappliedClarifications,
            )
        }
        val nextStep = if (!isLastStep) plan.nextAfter(command.step) else null
        val stepName = command.step.name
        val wizardContext = contextBuilder.buildWizardContext(wizardData, stepName, command.fields)

        val wizardCategory = wizardData.steps["IDEA"]?.fields?.get("category")
            ?.let { runCatching { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }.getOrNull() }

        val prompt = buildWizardStepFeedbackPrompt(stepName, command.fields, wizardCategory, isLastStep)

        val baseSystemPrompt = promptService.get("idea-base")
        val stepPrompt = buildStepPrompt(command.step)
        val localeInstruction = buildLocaleInstruction(command.locale)
        val systemPrompt = "$baseSystemPrompt\n\n$stepPrompt\n\n$localeInstruction\n\n$wizardContext"

        val rawResponse = completionAgent.respond(systemPrompt, prompt)
        val decisionTitle = AgentResponseMarkers.extractDecisionTitle(rawResponse)
        val clarification = AgentResponseMarkers.extractClarification(rawResponse)
        val cleanMessage = AgentResponseMarkers.clean(rawResponse)
        val hasAnsweredBlocker = hasAnsweredStepBlocker(command.projectId, command.step)

        logger.info(
            "completeWizardStep({}) markers - decision={}, clarification={}, isLastStep={}, hasAnsweredBlocker={}",
            stepName, decisionTitle, clarification?.first, isLastStep, hasAnsweredBlocker
        )

        val commandFields = command.fields.mapValues { (_, value) -> WizardMarkdown.toJsonElement(value) }
        syncWizardFeatureTasks(command.projectId, command.step, commandFields, wizardCategory)

        val now = Instant.now().toString()
        wizardService.saveStepData(
            command.projectId,
            stepName,
            WizardStepData(
                fields = commandFields,
                completedAt = now,
            ),
        )
        requireNoPendingFeatureChanges(command)

        var createdDecisionId: String? = null
        if (!isLastStep && !hasAnsweredBlocker && decisionTitle != null) {
            createdDecisionId = decisionService.createDecision(command.projectId, decisionTitle, command.step).id
        }

        var createdClarificationId: String? = null
        if (!isLastStep && !hasAnsweredBlocker && clarification != null) {
            createdClarificationId = clarificationService.createClarification(
                command.projectId,
                clarification.first,
                clarification.second,
                command.step,
            ).id
        }

        val createdBlockingArtifact = createdDecisionId != null || createdClarificationId != null
        if (createdBlockingArtifact) {
            val progression = snapshotFor(command.projectId)
            return WizardStepCompletionResult(
                message = cleanMessage,
                nextStep = null,
                exportTriggered = false,
                decisionId = createdDecisionId,
                clarificationId = createdClarificationId,
                progression = progression,
                action = stayClientAction,
                artifacts = WizardCreatedArtifacts(
                    decisionIds = listOfNotNull(createdDecisionId),
                    clarificationIds = listOfNotNull(createdClarificationId),
                ),
            )
        }

        val updatedSteps = flowState.steps.map { step ->
            when (step.stepType) {
                command.step -> step.copy(status = FlowStepStatus.COMPLETED, updatedAt = now)
                nextStep -> step.copy(status = FlowStepStatus.IN_PROGRESS, updatedAt = now)
                else -> step
            }
        }
        updateFlowStateWithPendingGate(
            command,
            flowState.copy(steps = updatedSteps, currentStep = nextStep ?: command.step),
        )

        if (isLastStep) {
            val fullContext = contextBuilder.buildContext(command.projectId)
            val designInstruction = buildDesignArtifactInstruction(command.projectId)
            val summaryPrompt = buildString {
                appendLine("Based on all the information gathered in the wizard steps below, generate a complete product specification summary in markdown format.")
                appendLine("Include sections for: Product Overview, Problem, Target Audience, Scope, MVP, and any technical decisions made.")
                appendLine()
                appendLine(fullContext)
                if (designInstruction != null) {
                    appendLine()
                    appendLine(designInstruction)
                }
            }
            val summarySystemPrompt = "$baseSystemPrompt\n\n$localeInstruction"
            val specContent = completionAgent.respond(summarySystemPrompt, summaryPrompt)
            projectService.saveSpecFile(command.projectId, "spec.md", ensureDesignReference(specContent, designInstruction))
        } else {
            projectService.regenerateDocsScaffold(command.projectId)
        }

        val progression = snapshotFor(command.projectId)
        val action = when {
            isLastStep -> openExportClientAction
            nextStep != null -> showStepAction(nextStep)
            else -> stayClientAction
        }

        return WizardStepCompletionResult(
            message = cleanMessage,
            nextStep = nextStep,
            exportTriggered = isLastStep,
            decisionId = createdDecisionId,
            clarificationId = createdClarificationId,
            progression = progression,
            action = action,
            artifacts = WizardCreatedArtifacts(
                decisionIds = listOfNotNull(createdDecisionId),
                clarificationIds = listOfNotNull(createdClarificationId),
            ),
        )
    }

    private suspend fun applyAnsweredBlockers(
        command: CompleteWizardStep,
        flowState: FlowState,
        plan: WizardProgressionPlan,
        decisions: List<Decision>,
        clarifications: List<Clarification>,
    ): WizardStepCompletionResult {
        val stepName = command.step.name
        val existingFields = wizardService.getWizardData(command.projectId).steps[stepName]?.fields.orEmpty()
        val commandFields = command.fields.mapValues { (_, value) -> WizardMarkdown.toJsonElement(value) }
        val baseFields = existingFields + commandFields
        val applyResult = applyAgent.apply(
            ApplyWizardBlockers(
                projectId = command.projectId,
                step = command.step,
                fields = baseFields,
                decisions = decisions,
                clarifications = clarifications,
                locale = command.locale,
            )
        )
        val validFieldUpdates = applyResult.fieldUpdates
            .filterKeys { it in WizardStepFieldSchema.allowedFields(command.step) }
        val appliedFields = validFieldUpdates.keys.toList()
        val mergedFields = baseFields + validFieldUpdates
        val wizardCategory = wizardService.getWizardData(command.projectId).steps["IDEA"]?.fields?.get("category")
            ?.let { runCatching { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }.getOrNull() }
        syncWizardFeatureTasks(command.projectId, command.step, mergedFields, wizardCategory)
        val now = Instant.now().toString()
        wizardService.saveStepData(
            command.projectId,
            stepName,
            WizardStepData(fields = mergedFields, completedAt = now),
        )
        requireNoPendingFeatureChanges(command)
        val isLastStep = plan.isTerminal(command.step)
        val nextStep = if (!isLastStep) plan.nextAfter(command.step) else null
        val updatedSteps = flowState.steps.map { step ->
            when (step.stepType) {
                command.step -> step.copy(status = FlowStepStatus.COMPLETED, updatedAt = now)
                nextStep -> step.copy(status = FlowStepStatus.IN_PROGRESS, updatedAt = now)
                else -> step
            }
        }
        OkfProjectLocks.withLock(command.projectId) {
            requireNoPendingFeatureChanges(command)
            decisions.forEach { decisionService.markApplied(command.projectId, it.id, appliedFields) }
            clarifications.forEach { clarificationService.markApplied(command.projectId, it.id, appliedFields) }
            projectService.updateFlowState(
                command.projectId,
                flowState.copy(steps = updatedSteps, currentStep = nextStep ?: command.step),
            )
        }
        if (!isLastStep) {
            projectService.regenerateDocsScaffold(command.projectId)
        }

        val progression = snapshotFor(command.projectId)
        val action = when {
            isLastStep -> openExportClientAction
            nextStep != null -> showStepAction(nextStep)
            else -> stayClientAction
        }
        return WizardStepCompletionResult(
            message = applyResult.message,
            nextStep = nextStep,
            exportTriggered = isLastStep,
            progression = progression,
            action = action,
            appliedDecisionIds = decisions.map { it.id },
            appliedClarificationIds = clarifications.map { it.id },
            wizardDataChanged = true,
        )
    }

    private suspend fun syncWizardFeatureTasks(
        projectId: String,
        step: FlowStepType,
        fields: Map<String, JsonElement>,
        wizardCategory: String?,
    ) {
        if (step != FlowStepType.FEATURES || taskService == null) return
        if (okfFeatureService?.isActive(projectId) == true) return

        val parsedFeatures = WizardFeatureParser.parse(
            fields.mapValues { (_, value) -> jsonElementToKotlin(value) },
            wizardCategory,
        )
        if (parsedFeatures.isNotEmpty()) {
            logger.info("completeWizardStep(FEATURES) syncing {} wizard feature tasks", parsedFeatures.size)
            taskService.replaceWizardFeatureTasks(projectId, parsedFeatures)
        }
    }

    private fun requireNoPendingFeatureChanges(command: CompleteWizardStep) {
        if (command.step == FlowStepType.FEATURES && okfFeatureService?.hasPending(command.projectId) == true) {
            throw OkfChangesetPendingException(command.projectId)
        }
    }

    private fun updateFlowStateWithPendingGate(command: CompleteWizardStep, flowState: FlowState) {
        OkfProjectLocks.withLock(command.projectId) {
            requireNoPendingFeatureChanges(command)
            projectService.updateFlowState(command.projectId, flowState)
        }
    }

    private fun jsonElementToKotlin(value: JsonElement): Any? = when (value) {
        JsonNull -> null
        is JsonArray -> value.map(::jsonElementToKotlin)
        is JsonObject -> value.mapValues { (_, child) -> jsonElementToKotlin(child) }
        else -> WizardMarkdown.jsonPrimitiveValue(value)
    }

    private fun answeredUnappliedDecisions(projectId: String, step: FlowStepType) =
        decisionService.listDecisions(projectId).filter {
            it.stepType == step && it.status == DecisionStatus.RESOLVED && it.appliedAt == null
        }

    private fun answeredUnappliedClarifications(projectId: String, step: FlowStepType) =
        clarificationService.listClarifications(projectId).filter {
            it.stepType == step && it.status == ClarificationStatus.ANSWERED && it.appliedAt == null
        }

    private fun hasAnsweredStepBlocker(projectId: String, step: FlowStepType): Boolean =
        decisionService.listDecisions(projectId).any {
            it.stepType == step && it.status == DecisionStatus.RESOLVED
        } || clarificationService.listClarifications(projectId).any {
            it.stepType == step && it.status == ClarificationStatus.ANSWERED
        }

    private fun countOpenStepBlockers(projectId: String, step: FlowStepType): StepBlockerCounts {
        val pendingDecisions = decisionService.listDecisions(projectId)
            .count { it.stepType == step && it.status == DecisionStatus.PENDING }
        val openClarifications = clarificationService.listClarifications(projectId)
            .count { it.stepType == step && it.status == ClarificationStatus.OPEN }
        return StepBlockerCounts(pendingDecisions, openClarifications)
    }

    private data class StepBlockerCounts(
        val pendingDecisions: Int,
        val openClarifications: Int,
    ) {
        val total: Int = pendingDecisions + openClarifications

        fun message(): String = when {
            pendingDecisions > 0 && openClarifications > 0 ->
                "${pendingDecisions} offene ${decisionLabel()} und ${openClarifications} offene ${clarificationLabel()} blockieren den naechsten Schritt."
            pendingDecisions > 0 ->
                "${pendingDecisions} offene ${decisionLabel()} blockier${if (pendingDecisions == 1) "t" else "en"} den naechsten Schritt."
            else ->
                "${openClarifications} offene ${clarificationLabel()} blockier${if (openClarifications == 1) "t" else "en"} den naechsten Schritt."
        }

        private fun decisionLabel(): String =
            if (pendingDecisions == 1) "Entscheidung" else "Entscheidungen"

        private fun clarificationLabel(): String =
            if (openClarifications == 1) "Klaerung" else "Klaerungen"
    }

    private fun buildDesignArtifactInstruction(projectId: String): String? =
        designWorkbenchStorage.readDesignSummary(projectId)?.takeIf { it.isNotBlank() }?.let {
            """
            A design artifact exists for this project.
            Include a concise Design section in the final product specification.
            Link to [design/design.md](../design/design.md).
            If an HTML preview is relevant, link to [design/screens/design/index.html](../design/screens/design/index.html).
            Do not duplicate the full design summary; use the links as the source of detailed design truth.
            """.trimIndent()
        }

    private fun ensureDesignReference(specContent: String, designInstruction: String?): String {
        if (designInstruction == null || specContent.contains("design/design.md")) return specContent
        return buildString {
            append(specContent.trimEnd())
            appendLine()
            appendLine()
            appendLine("## Design")
            appendLine()
            appendLine("The UI design was created in the Design Workbench.")
            appendLine()
            appendLine("- [design/design.md](../design/design.md)")
            appendLine("- [design/screens/design/index.html](../design/screens/design/index.html)")
        }
    }

    override fun snapshot(projectId: String): WizardProgressionView =
        snapshotFor(projectId)

    private fun snapshotFor(projectId: String): WizardProgressionView {
        val wizardData = wizardService.getWizardData(projectId)
        val plan = progressionPolicy.planFor(wizardData)
        val flowState = projectService.getFlowState(projectId)
        val statusesByStep = flowState.steps.associate { it.stepType to it.status }
        val completedVisibleSteps = plan.visibleSteps.isNotEmpty() &&
            plan.visibleSteps.all { statusesByStep[it] == FlowStepStatus.COMPLETED }
        val current = currentVisibleStep(plan, statusesByStep, flowState.currentStep)

        return WizardProgressionView(
            category = plan.category?.wireValue,
            steps = plan.visibleSteps.map { step ->
                WizardStepView(
                    step = step.name,
                    status = statusesByStep[step]?.name ?: FlowStepStatus.OPEN.name,
                    finalVisibleStep = plan.isTerminal(step),
                )
            },
            currentStep = current?.name,
            status = if (completedVisibleSteps) "READY_FOR_EXPORT" else "IN_PROGRESS",
            primaryAction = if (completedVisibleSteps) {
                openExportPrimaryAction
            } else {
                current?.let(::completeStepAction) ?: nonePrimaryAction
            },
        )
    }

    private fun currentVisibleStep(
        plan: WizardProgressionPlan,
        statusesByStep: Map<FlowStepType, FlowStepStatus>,
        flowCurrentStep: FlowStepType,
    ): FlowStepType? =
        flowCurrentStep
            .takeIf { it in plan.visibleSteps && statusesByStep[it] != FlowStepStatus.COMPLETED }
            ?: plan.visibleSteps.firstOrNull { statusesByStep[it] != FlowStepStatus.COMPLETED }
            ?: plan.visibleSteps.lastOrNull()

    private fun buildWizardStepFeedbackPrompt(
        step: String,
        fields: Map<String, Any>,
        category: String? = null,
        isLastStep: Boolean = false,
    ): String {
        val fieldsDescription = fields.entries.joinToString("\n") { "- ${it.key}: ${it.value}" }

        if (isLastStep) {
            return buildString {
                appendLine("The user just completed the FINAL wizard step: $step")
                appendLine("Their input for this step:")
                appendLine(fieldsDescription)
                appendLine()
                appendLine("This is the last step of the wizard. The specification is now complete.")
                appendLine("Provide a short closing message (2-3 sentences) acknowledging the final confirmation.")
                appendLine("Do NOT ask for clarifications. Do NOT propose decisions. Do NOT emit any markers.")
            }
        }

        return when (step) {
            "FEATURES" -> buildString {
                appendLine("The user just completed the FEATURES wizard step.")
                appendLine()

                val parsedFeatures = WizardFeatureParser.parse(fields, category)
                if (parsedFeatures.isNotEmpty()) {
                    appendLine(SpecContextBuilder.renderFeaturesBlock(parsedFeatures, category))
                    appendLine()
                } else {
                    appendLine(fieldsDescription)
                    appendLine()
                }

                appendLine("Validator rules for the FEATURES step:")
                appendLine("- If the graph contains isolated nodes (no incoming and no outgoing edges), ask the user whether that is intentional.")
                appendLine("- If a feature's scope seems inconsistent with its title (e.g. 'Login UI' with BACKEND only), emit a clarification.")
                appendLine("- If the category is SaaS / Mobile / Desktop and obvious core features are missing (e.g. Auth, Registration), emit a clarification.")
                appendLine("Otherwise remain silent on those points.")
                appendLine()
                appendLine("Provide brief, helpful feedback about the feature graph.")
                appendLine("Be encouraging and mention any suggestions for improvement if applicable.")
                appendLine()
                appendLine(promptService.get("idea-marker-reminder"))
            }
            "IDEA" -> buildString {
                appendLine("The user just completed the IDEA wizard step with the following input:")
                appendLine(fieldsDescription)
                appendLine()
                appendLine("IMPORTANT: This is the IDEA step. Focus ONLY on the idea itself. Do NOT discuss problem statement, target audience, value proposition, or technical details – these are handled in later steps (PROBLEM, FEATURES, etc.).")
                appendLine()
                appendLine("Analyze ONLY the idea:")
                appendLine("1. Is the product idea clearly described? Can you understand what the product is supposed to DO?")
                appendLine("2. Does the chosen category fit?")
                appendLine("3. Is the product name clear and fitting?")
                appendLine("4. Is the vision concrete enough to work with, or is it too vague?")
                appendLine()
                appendLine("If the vision is too vague (e.g. just a few words), ask the user to elaborate on WHAT the product does – not WHY or for WHOM (those come later).")
                appendLine("If the idea is clear enough, acknowledge it and confirm it as a solid starting point.")
                appendLine()
                appendLine(promptService.get("idea-marker-reminder"))
            }
            "PROBLEM" -> buildString {
                appendLine("The user just completed the PROBLEM wizard step with the following input:")
                appendLine(fieldsDescription)
                appendLine()
                appendLine("This step now covers BOTH the core problem AND the primary target audience + their pain points.")
                appendLine("Analyze the combined problem-and-audience definition:")
                appendLine("1. Is the core problem clearly defined and specific enough?")
                appendLine("2. Is the primary audience concrete (not 'everyone' or 'users')?")
                appendLine("3. Are the pain points tied to the stated audience and problem?")
                appendLine()
                appendLine("The generated problem section should document problem, audience, and pain points together in one coherent section.")
                appendLine("If the audience is too broad or a strategic choice is needed (e.g., B2B vs B2C), use [DECISION_NEEDED].")
                appendLine("If there are contradictions or missing aspects, use [CLARIFICATION_NEEDED].")
                appendLine("Be encouraging and constructive.")
                appendLine()
                appendLine(promptService.get("idea-marker-reminder"))
            }
            "ARCHITECTURE" -> buildString {
                appendLine("The user just completed the ARCHITECTURE wizard step with the following input:")
                appendLine(fieldsDescription)
                appendLine()
                appendLine("Analyze the architecture:")
                appendLine("1. Does the tech stack fit the requirements and team skills?")
                appendLine("2. Is the system design appropriate for the scale?")
                appendLine("3. Are there important architectural trade-offs to consider?")
                appendLine()
                appendLine("If there is a major architectural choice (e.g., monolith vs. microservices, SQL vs. NoSQL), use [DECISION_NEEDED].")
                appendLine("If details are unclear, use [CLARIFICATION_NEEDED].")
                appendLine("Be encouraging and constructive.")
                appendLine()
                appendLine(promptService.get("idea-marker-reminder"))
            }
            else -> buildString {
                appendLine("The user just completed wizard step: $step")
                appendLine("Their input:")
                appendLine(fieldsDescription)
                appendLine()
                appendLine("Please provide brief, helpful feedback about their input for this step.")
                appendLine("Be encouraging and mention any suggestions for improvement if applicable.")
                appendLine()
                appendLine(promptService.get("idea-marker-reminder"))
            }
        }
    }

    private fun buildStepPrompt(step: FlowStepType): String = when (step) {
        FlowStepType.IDEA -> promptService.get("idea-step-IDEA")
        else -> ""
    }

    private fun buildLocaleInstruction(locale: String): String {
        val langCode = locale.split("-", "_").first().lowercase()
        val languageName = mapOf(
            "de" to "Deutsch", "en" to "English", "fr" to "Français",
            "es" to "Español", "it" to "Italiano", "pt" to "Português",
            "nl" to "Nederlands", "pl" to "Polski", "ja" to "日本語",
            "zh" to "中文", "ko" to "한국語", "ru" to "Русский",
        )[langCode]

        return if (languageName != null) {
            "IMPORTANT: Always respond in $languageName ($langCode). Do not switch languages."
        } else {
            "IMPORTANT: Always respond in the language with code '$langCode'. Do not switch languages."
        }
    }

}

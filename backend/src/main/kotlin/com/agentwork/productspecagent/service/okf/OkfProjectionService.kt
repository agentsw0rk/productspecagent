package com.agentwork.productspecagent.service.okf

import com.agentwork.productspecagent.domain.WizardFeatureGraph
import com.agentwork.productspecagent.domain.okf.OkfBundle
import com.agentwork.productspecagent.domain.okf.OkfIssue
import com.agentwork.productspecagent.domain.okf.OkfIssueSeverity
import com.agentwork.productspecagent.domain.okf.OkfParseException
import com.agentwork.productspecagent.storage.OkfPresentationStorage
import com.agentwork.productspecagent.storage.OkfProjectionStorage
import org.springframework.stereotype.Service

data class OkfProjectionResult(
    val bundle: OkfBundle?,
    val graph: WizardFeatureGraph?,
    val issues: List<OkfIssue>,
) {
    val valid: Boolean = issues.none { it.severity == OkfIssueSeverity.ERROR }
}

@Service
class OkfProjectionService(
    private val projectionStorage: OkfProjectionStorage,
    private val presentationStorage: OkfPresentationStorage,
    private val parser: OkfParser,
    private val validator: OkfProfileValidator,
    private val featureCodec: OkfFeatureCodec,
) {

    fun project(projectId: String, files: Map<String, String>): OkfProjectionResult {
        projectionStorage.replaceRawFiles(projectId, files)

        val issues = mutableListOf<OkfIssue>()
        val documents = files.toSortedMap().mapNotNull { (path, content) ->
            try {
                parser.parse(path, content)
            } catch (exception: OkfParseException) {
                issues += OkfIssue(
                    severity = OkfIssueSeverity.ERROR,
                    path = path,
                    message = exception.message ?: "invalid OKF document",
                )
                null
            }
        }
        if (issues.isNotEmpty()) {
            return OkfProjectionResult(null, null, issues)
        }

        val bundle = OkfBundle(documents)
        issues += validator.validate(bundle)
        if (issues.any { it.severity == OkfIssueSeverity.ERROR }) {
            return OkfProjectionResult(bundle, null, issues)
        }

        val decoded = featureCodec.decode(bundle, presentationStorage.loadPositions(projectId))
        issues += decoded.issues
        if (issues.none { it.severity == OkfIssueSeverity.ERROR }) {
            projectionStorage.saveFeatureProjection(projectId, decoded.graph)
        }
        return OkfProjectionResult(bundle, decoded.graph, issues)
    }
}

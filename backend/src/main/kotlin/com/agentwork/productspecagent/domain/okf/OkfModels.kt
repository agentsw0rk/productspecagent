package com.agentwork.productspecagent.domain.okf

import com.agentwork.productspecagent.domain.WizardFeatureGraph
import kotlinx.serialization.Serializable

data class OkfDocument(
    val path: String,
    val frontmatter: LinkedHashMap<String, Any?>,
    val body: String,
)

data class OkfBundle(
    val documents: List<OkfDocument>,
) {
    init {
        val duplicatePath = documents.groupingBy { it.path }.eachCount().entries.firstOrNull { it.value > 1 }?.key
        require(duplicatePath == null) { "duplicate OKF document path: $duplicatePath" }
    }

    val byPath: Map<String, OkfDocument> = documents.associateBy { it.path }
}

@Serializable
enum class OkfIssueSeverity { ERROR, WARNING }

@Serializable
data class OkfIssue(
    val severity: OkfIssueSeverity,
    val path: String,
    val field: String? = null,
    val message: String,
)

data class OkfFeatureDecodeResult(
    val graph: WizardFeatureGraph,
    val issues: List<OkfIssue>,
)

data class OkfFeaturePath(
    val id: String,
    val path: String,
)

class OkfParseException(
    val documentPath: String,
    message: String,
    cause: Throwable? = null,
) : RuntimeException("$documentPath: $message", cause)

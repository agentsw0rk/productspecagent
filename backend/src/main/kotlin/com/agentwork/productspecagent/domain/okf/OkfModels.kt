package com.agentwork.productspecagent.domain.okf

import kotlinx.serialization.Serializable

data class OkfDocument(
    val path: String,
    val frontmatter: LinkedHashMap<String, Any?>,
    val body: String,
)

data class OkfBundle(
    val documents: List<OkfDocument>,
) {
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

class OkfParseException(
    val documentPath: String,
    message: String,
    cause: Throwable? = null,
) : RuntimeException("$documentPath: $message", cause)

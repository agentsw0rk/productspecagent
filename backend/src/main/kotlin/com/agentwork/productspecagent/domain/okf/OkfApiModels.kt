package com.agentwork.productspecagent.domain.okf

import kotlinx.serialization.Serializable

@Serializable
data class OkfMigrationDocument(
    val sourceId: String,
    val documentId: String,
    val path: String,
    val normalizations: List<String>,
)

@Serializable
data class OkfMigrationPreview(
    val projectId: String,
    val baseCommit: String,
    val documents: List<OkfMigrationDocument>,
    val operations: List<OkfFileOperation>,
    val issues: List<OkfIssue>,
)

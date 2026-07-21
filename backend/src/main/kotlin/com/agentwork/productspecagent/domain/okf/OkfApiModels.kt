package com.agentwork.productspecagent.domain.okf

import kotlinx.serialization.Serializable

@Serializable
enum class OkfConflictResolution { REMOTE, PROPOSED }

@Serializable
data class ConnectOkfRequest(
    val remoteUrl: String,
    val baseBranch: String = "main",
)

@Serializable
data class ResolveOkfConflictRequest(val resolution: OkfConflictResolution)

@Serializable
data class OkfStatusResponse(
    val state: OkfProjectState,
    val pending: OkfChangeset? = null,
    val conflicts: List<OkfConflict> = emptyList(),
)

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

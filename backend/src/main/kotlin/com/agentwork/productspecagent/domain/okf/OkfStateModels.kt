package com.agentwork.productspecagent.domain.okf

import java.time.Instant
import kotlinx.serialization.Serializable

@Serializable
enum class OkfMode { LEGACY, MIGRATION_READY, OKF_ACTIVE }

@Serializable
enum class OkfSyncStatus { CLEAN, INCOMING, PROPOSAL_PENDING, CONFLICT, INVALID_REMOTE, PUSH_FAILED }

@Serializable
enum class OkfChangesetKind { EDIT, MIGRATION }

@Serializable
enum class OkfOperationType { ADD, UPDATE, RENAME, DELETE }

@Serializable
enum class OkfChangesetStatus { PENDING, APPROVED }

@Serializable
data class OkfGitConnection(
    val remoteUrl: String,
    val baseBranch: String = "main",
    val canonicalBranch: String = "productspec/okf",
)

@Serializable
data class OkfFileOperation(
    val type: OkfOperationType,
    val path: String,
    val previousPath: String? = null,
    val baseContent: String? = null,
    val content: String? = null,
    val documentId: String? = null,
)

@Serializable
data class OkfChangeset(
    val id: String,
    val projectId: String,
    val kind: OkfChangesetKind,
    val status: OkfChangesetStatus = OkfChangesetStatus.PENDING,
    val baseCommit: String?,
    val operations: List<OkfFileOperation>,
    val issues: List<OkfIssue>,
    val createdAt: String,
    val updatedAt: String,
    val approvedCommit: String? = null,
)

@Serializable
data class OkfConflict(
    val changesetId: String,
    val documentId: String?,
    val path: String,
    val baseContent: String?,
    val remoteContent: String?,
    val proposedContent: String?,
)

@Serializable
data class OkfProjectState(
    val projectId: String,
    val mode: OkfMode = OkfMode.LEGACY,
    val syncStatus: OkfSyncStatus = OkfSyncStatus.CLEAN,
    val connection: OkfGitConnection? = null,
    val baseCommit: String? = null,
    val pendingChangesetId: String? = null,
    val lastError: String? = null,
    val updatedAt: String = Instant.EPOCH.toString(),
)

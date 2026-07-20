package com.agentwork.productspecagent.storage

import com.agentwork.productspecagent.domain.okf.OkfChangeset
import com.agentwork.productspecagent.domain.okf.OkfChangesetStatus
import com.agentwork.productspecagent.domain.okf.OkfConflict
import com.agentwork.productspecagent.domain.okf.OkfProjectState
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.springframework.stereotype.Service

@Service
class OkfStateStorage(private val objectStore: ObjectStore) {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    fun saveState(state: OkfProjectState) {
        putJson(stateKey(state.projectId), json.encodeToString(state))
    }

    fun loadState(projectId: String): OkfProjectState =
        readJson(stateKey(projectId)) ?: OkfProjectState(projectId = projectId)

    fun savePendingChangeset(changeset: OkfChangeset) {
        putJson(pendingChangesetKey(changeset.projectId), json.encodeToString(changeset))
    }

    fun loadPendingChangeset(projectId: String): OkfChangeset? =
        readJson(pendingChangesetKey(projectId))

    fun deletePendingChangeset(projectId: String) {
        objectStore.delete(pendingChangesetKey(projectId))
    }

    fun saveApprovedChangeset(changeset: OkfChangeset) {
        require(changeset.status == OkfChangesetStatus.APPROVED) {
            "approved changeset receipt must have APPROVED status"
        }
        val key = approvedChangesetKey(changeset.projectId, changeset.id)
        val existing = readJson<OkfChangeset>(key)
        check(existing == null || existing == changeset) {
            "approved changeset receipt ${changeset.id} is immutable"
        }
        if (existing == null) {
            putJson(key, json.encodeToString(changeset))
        }
    }

    fun loadApprovedChangeset(projectId: String, changesetId: String): OkfChangeset? =
        readJson(approvedChangesetKey(projectId, changesetId))

    fun saveConflict(projectId: String, conflict: OkfConflict) {
        putJson(conflictKey(projectId), json.encodeToString(conflict))
    }

    fun loadConflict(projectId: String): OkfConflict? = readJson(conflictKey(projectId))

    fun deleteConflict(projectId: String) {
        objectStore.delete(conflictKey(projectId))
    }

    private inline fun <reified T> readJson(key: String): T? = objectStore.get(key)
        ?.toString(Charsets.UTF_8)
        ?.let(json::decodeFromString)

    private fun putJson(key: String, value: String) {
        objectStore.put(key, value.toByteArray(), "application/json")
    }

    private fun stateKey(projectId: String) = "projects/$projectId/okf/state.json"
    private fun pendingChangesetKey(projectId: String) = "projects/$projectId/okf/changesets/pending.json"
    private fun approvedChangesetKey(projectId: String, changesetId: String) =
        "projects/$projectId/okf/changesets/approved/$changesetId.json"
    private fun conflictKey(projectId: String) = "projects/$projectId/okf/conflicts/current.json"
}

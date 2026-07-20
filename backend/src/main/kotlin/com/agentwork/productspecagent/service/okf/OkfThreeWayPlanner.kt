package com.agentwork.productspecagent.service.okf

import com.agentwork.productspecagent.domain.okf.OkfBundle
import com.agentwork.productspecagent.domain.okf.OkfConflict
import com.agentwork.productspecagent.domain.okf.OkfDocument
import java.nio.file.Path
import java.time.OffsetDateTime
import org.springframework.stereotype.Service

data class OkfMergePlan(
    val merged: OkfBundle?,
    val conflicts: List<OkfConflict>,
)

@Service
class OkfThreeWayPlanner(private val featureCodec: OkfFeatureCodec) {

    fun plan(base: OkfBundle, remote: OkfBundle, proposed: OkfBundle): OkfMergePlan {
        val baseById = conceptsByIdentity(base)
        val remoteById = conceptsByIdentity(remote)
        val proposedById = conceptsByIdentity(proposed)
        val identities = baseById.keys + remoteById.keys + proposedById.keys
        val conflicts = mutableListOf<OkfConflict>()

        identities.sorted().forEach { identity ->
            val baseDocument = baseById[identity]
            val remoteDocument = remoteById[identity]
            val proposedDocument = proposedById[identity]
            val remoteChanged = remoteDocument != baseDocument
            val proposedChanged = proposedDocument != baseDocument
            if (remoteChanged && proposedChanged && remoteDocument != proposedDocument) {
                conflicts += conflict(identity, baseDocument, remoteDocument, proposedDocument)
            }
        }

        val remotelyMerged = remoteById.toMutableMap()
        identities.filter { proposedById[it] != baseById[it] }.forEach { identity ->
            val proposedDocument = proposedById[identity]
            if (proposedDocument == null) remotelyMerged.remove(identity) else remotelyMerged[identity] = proposedDocument
        }
        conflicts += pathCollisions(remotelyMerged)

        if (conflicts.isNotEmpty()) {
            return OkfMergePlan(null, conflicts.distinctBy { it.documentId to it.path })
        }

        val concepts = remotelyMerged.values.sortedBy { it.path }
        val conceptBundle = OkfBundle(concepts)
        val decoded = featureCodec.decode(conceptBundle, emptyMap())
        val generated = featureCodec.encode(decoded.graph, conceptBundle, OffsetDateTime.now())
        val indexes = generated.documents.filter(::isIndex).sortedBy { it.path }
        return OkfMergePlan(OkfBundle(indexes + concepts), emptyList())
    }

    private fun conceptsByIdentity(bundle: OkfBundle): Map<String, OkfDocument> = bundle.documents
        .filterNot(::isIndex)
        .associateBy(::identity)

    private fun pathCollisions(documents: Map<String, OkfDocument>): List<OkfConflict> = documents.entries
        .groupBy { it.value.path }
        .values
        .filter { entries -> entries.map { it.key }.distinct().size > 1 }
        .flatMap { entries ->
            entries.map { (identity, document) ->
                OkfConflict(
                    changesetId = "",
                    documentId = documentId(identity),
                    path = document.path,
                    baseContent = null,
                    remoteContent = null,
                    proposedContent = document.body,
                )
            }
        }

    private fun conflict(
        identity: String,
        base: OkfDocument?,
        remote: OkfDocument?,
        proposed: OkfDocument?,
    ) = OkfConflict(
        changesetId = "",
        documentId = documentId(identity),
        path = proposed?.path ?: remote?.path ?: requireNotNull(base).path,
        baseContent = base?.body,
        remoteContent = remote?.body,
        proposedContent = proposed?.body,
    )

    private fun identity(document: OkfDocument): String =
        (document.frontmatter["id"] as? String)?.let { "id:$it" } ?: "path:${document.path}"

    private fun documentId(identity: String): String? = identity.removePrefix("id:").takeIf { identity.startsWith("id:") }

    private fun isIndex(document: OkfDocument): Boolean = Path.of(document.path).fileName.toString() == "index.md"
}

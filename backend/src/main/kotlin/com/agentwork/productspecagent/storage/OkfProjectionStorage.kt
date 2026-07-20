package com.agentwork.productspecagent.storage

import com.agentwork.productspecagent.domain.WizardFeatureGraph
import com.agentwork.productspecagent.domain.okf.OkfBundle
import com.agentwork.productspecagent.service.okf.OkfParser
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.springframework.stereotype.Service

@Service
class OkfProjectionStorage(
    private val objectStore: ObjectStore,
    private val parser: OkfParser,
) {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    fun replaceRawBundle(projectId: String, bundle: OkfBundle) {
        val renderedDocuments = bundle.documents.map { document ->
            require(document.path.startsWith(RAW_BUNDLE_PATH_PREFIX)) {
                "OKF document path must be below $RAW_BUNDLE_PATH_PREFIX: ${document.path}"
            }
            require(document.path.split('/').none { it == ".." }) {
                "OKF document path must not contain parent traversal: ${document.path}"
            }
            document.path to parser.render(document).toByteArray()
        }

        objectStore.deletePrefix(rawBundlePrefix(projectId))
        renderedDocuments.forEach { (path, content) ->
            objectStore.put(
                "projects/$projectId/$path",
                content,
                "text/markdown",
            )
        }
    }

    fun loadRawBundle(projectId: String): OkfBundle {
        val projectPrefix = "projects/$projectId/"
        val documents = objectStore.listKeys(rawBundlePrefix(projectId)).mapNotNull { key ->
            objectStore.get(key)?.toString(Charsets.UTF_8)?.let { content ->
                parser.parse(key.removePrefix(projectPrefix), content)
            }
        }
        return OkfBundle(documents)
    }

    fun saveFeatureProjection(projectId: String, graph: WizardFeatureGraph) {
        objectStore.put(
            featureProjectionKey(projectId),
            json.encodeToString(graph).toByteArray(),
            "application/json",
        )
    }

    fun loadFeatureProjection(projectId: String): WizardFeatureGraph? =
        objectStore.get(featureProjectionKey(projectId))
            ?.toString(Charsets.UTF_8)
            ?.let(json::decodeFromString)

    private fun rawBundlePrefix(projectId: String) = "projects/$projectId/$RAW_BUNDLE_PATH_PREFIX"
    private fun featureProjectionKey(projectId: String) = "projects/$projectId/okf/feature-projection.json"

    private companion object {
        const val RAW_BUNDLE_PATH_PREFIX = "docs/okf/"
    }
}

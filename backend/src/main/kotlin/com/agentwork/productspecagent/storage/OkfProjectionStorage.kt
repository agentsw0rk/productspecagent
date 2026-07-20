package com.agentwork.productspecagent.storage

import com.agentwork.productspecagent.domain.WizardFeatureGraph
import com.agentwork.productspecagent.domain.okf.OkfBundle
import com.agentwork.productspecagent.service.okf.OkfParser
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.springframework.stereotype.Service
import java.nio.file.Path

@Service
class OkfProjectionStorage(
    private val objectStore: ObjectStore,
    private val parser: OkfParser,
) {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    fun replaceRawBundle(projectId: String, bundle: OkfBundle) {
        val files = bundle.documents.associate { document ->
            require(document.path.startsWith(RAW_BUNDLE_PATH_PREFIX)) {
                "OKF document path must be below $RAW_BUNDLE_PATH_PREFIX: ${document.path}"
            }
            document.path.removePrefix(RAW_BUNDLE_PATH_PREFIX) to parser.render(document)
        }
        replaceRawFiles(projectId, files)
    }

    fun replaceRawFiles(projectId: String, files: Map<String, String>) {
        val encodedFiles = files.map { (path, content) ->
            validateRelativePath(path) to content.toByteArray()
        }

        objectStore.deletePrefix(rawBundlePrefix(projectId))
        encodedFiles.forEach { (path, content) ->
            objectStore.put(
                "${rawBundlePrefix(projectId)}$path",
                content,
                "text/markdown",
            )
        }
    }

    fun loadRawFiles(projectId: String): Map<String, String> =
        objectStore.listKeys(rawBundlePrefix(projectId)).associate { key ->
            key.removePrefix(rawBundlePrefix(projectId)) to
                objectStore.get(key)!!.toString(Charsets.UTF_8)
        }

    fun loadRawBundle(projectId: String): OkfBundle {
        val documents = loadRawFiles(projectId).map { (path, content) ->
            parser.parse("$RAW_BUNDLE_PATH_PREFIX$path", content)
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

    private fun validateRelativePath(path: String): String {
        require(path.isNotBlank() && !path.contains('\\')) { "invalid OKF file path: $path" }
        val parsed = Path.of(path)
        require(!parsed.isAbsolute && parsed.none { it.toString() == ".." }) { "invalid OKF file path: $path" }
        val normalized = parsed.normalize().toString().replace('\\', '/')
        require(normalized == path && normalized != ".") { "invalid OKF file path: $path" }
        return normalized
    }
}

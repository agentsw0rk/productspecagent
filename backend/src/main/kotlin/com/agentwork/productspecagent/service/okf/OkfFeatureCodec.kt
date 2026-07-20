package com.agentwork.productspecagent.service.okf

import com.agentwork.productspecagent.domain.AcceptanceCriterion
import com.agentwork.productspecagent.domain.FeatureScope
import com.agentwork.productspecagent.domain.GraphPosition
import com.agentwork.productspecagent.domain.WizardFeature
import com.agentwork.productspecagent.domain.WizardFeatureEdge
import com.agentwork.productspecagent.domain.WizardFeatureGraph
import com.agentwork.productspecagent.domain.okf.OkfBundle
import com.agentwork.productspecagent.domain.okf.OkfDocument
import com.agentwork.productspecagent.domain.okf.OkfFeatureDecodeResult
import com.agentwork.productspecagent.domain.okf.OkfFeaturePath
import com.agentwork.productspecagent.domain.okf.OkfIssue
import com.agentwork.productspecagent.domain.okf.OkfIssueSeverity
import org.springframework.stereotype.Service
import java.nio.charset.StandardCharsets
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.text.Normalizer
import java.time.OffsetDateTime
import java.util.Locale
import java.util.UUID

@Service
class OkfFeatureCodec(private val parser: OkfParser) {

    fun encode(graph: WizardFeatureGraph, existing: OkfBundle?, timestamp: OffsetDateTime): OkfBundle {
        val existingFeaturesById = existing.orEmpty()
            .filter { it.frontmatter["type"] == FEATURE_TYPE }
            .mapNotNull { document ->
                (document.frontmatter["id"] as? String)?.let { it to document }
            }
            .toMap()
        val featurePaths = allocateFeaturePaths(graph.features)
        val pathsById = featurePaths.associate { it.id to it.path }
        val featuresById = graph.features.associateBy { it.id }
        val incomingEdges = graph.edges.groupBy { it.to }

        val featureDocuments = graph.features.map { feature ->
            val existingDocument = existingFeaturesById[feature.id]
            val frontmatter = existingDocument?.frontmatter?.let(::LinkedHashMap) ?: linkedMapOf()
            updateFeatureFrontmatter(frontmatter, feature, timestamp)
            OkfDocument(
                path = pathsById.getValue(feature.id),
                frontmatter = frontmatter,
                body = renderFeatureBody(
                    feature = feature,
                    incomingEdges = incomingEdges[feature.id].orEmpty(),
                    featuresById = featuresById,
                    pathsById = pathsById,
                ),
            )
        }

        val generatedPaths = setOf(ROOT_INDEX_PATH, FEATURES_INDEX_PATH)
        val preservedDocuments = existing.orEmpty().filterNot { document ->
            document.path in generatedPaths ||
                document.frontmatter["type"] == FEATURE_TYPE ||
                Path.of(document.path).fileName?.toString() == LOG_FILENAME
        }
        val indexEntries = graph.features
            .map { feature -> feature to pathsById.getValue(feature.id) }
            .sortedWith(compareBy({ it.first.title.lowercase(Locale.ROOT) }, { it.first.id }))

        return OkfBundle(
            listOf(
                OkfDocument(ROOT_INDEX_PATH, linkedMapOf(), renderRootIndex(indexEntries)),
                OkfDocument(FEATURES_INDEX_PATH, linkedMapOf(), renderFeaturesIndex(indexEntries)),
            ) + featureDocuments + preservedDocuments,
        )
    }

    fun decode(bundle: OkfBundle, positions: Map<String, GraphPosition>): OkfFeatureDecodeResult {
        val issues = mutableListOf<OkfIssue>()
        val decodedDocuments = mutableListOf<DecodedFeatureDocument>()
        val seenIds = mutableSetOf<String>()

        for (document in bundle.documents.filter { it.frontmatter["type"] == FEATURE_TYPE }) {
            val id = requiredUuid(document, "id", issues) ?: continue
            val title = (document.frontmatter["title"] as? String)?.takeIf { it.isNotBlank() }
            if (title == null) {
                issues += error(document.path, "title", "feature title must be a non-blank string")
                continue
            }
            if (!seenIds.add(id)) {
                issues += error(document.path, "id", "duplicate feature id '$id'")
                continue
            }

            val description = when (val value = document.frontmatter["description"]) {
                null -> ""
                is String -> value
                else -> {
                    issues += warning(document.path, "description", "feature description must be a string; using empty text")
                    ""
                }
            }
            val feature = WizardFeature(
                id = id,
                title = title,
                scopes = decodeScopes(document, issues),
                description = description,
                scopeFields = decodeScopeFields(document, issues),
                acceptanceCriteria = decodeAcceptanceCriteria(document, id, issues),
                position = positions[id] ?: GraphPosition(),
            )
            decodedDocuments += DecodedFeatureDocument(document, feature)
        }

        val featureIdByPath = decodedDocuments.associate { it.document.path to it.feature.id }
        val edges = decodedDocuments.flatMap { decoded ->
            decodeRelationships(decoded, featureIdByPath, issues)
        }

        return OkfFeatureDecodeResult(
            graph = WizardFeatureGraph(decodedDocuments.map { it.feature }, edges),
            issues = issues,
        )
    }

    private fun updateFeatureFrontmatter(
        frontmatter: LinkedHashMap<String, Any?>,
        feature: WizardFeature,
        timestamp: OffsetDateTime,
    ) {
        val tags = (frontmatter["tags"] as? List<*>)?.toList() ?: emptyList<String>()
        val legacyIds = (frontmatter["legacy_ids"] as? List<*>)?.toList() ?: emptyList<String>()
        frontmatter["type"] = FEATURE_TYPE
        frontmatter["id"] = feature.id
        frontmatter["title"] = feature.title
        frontmatter["description"] = feature.description
        frontmatter["tags"] = tags
        frontmatter["timestamp"] = timestamp.toString()
        frontmatter["scopes"] = FeatureScope.entries.filter(feature.scopes::contains).map { it.name }
        frontmatter["scope_fields"] = LinkedHashMap(feature.scopeFields.toSortedMap())
        frontmatter["legacy_ids"] = legacyIds
    }

    private fun allocateFeaturePaths(features: List<WizardFeature>): List<OkfFeaturePath> {
        val usedSlugs = mutableSetOf<String>()
        return features.map { feature ->
            val baseSlug = slug(feature.title)
            val selectedSlug = if (usedSlugs.add(baseSlug)) {
                baseSlug
            } else {
                "$baseSlug-${feature.id.take(8).lowercase(Locale.ROOT)}".also(usedSlugs::add)
            }
            OkfFeaturePath(feature.id, "features/$selectedSlug.md")
        }
    }

    private fun slug(title: String): String {
        val normalized = Normalizer.normalize(
            title.lowercase(Locale.ROOT).replace("ß", "ss"),
            Normalizer.Form.NFD,
        ).replace(COMBINING_MARKS, "")
        return normalized
            .replace(NON_ASCII_ALPHANUMERIC, "-")
            .trim('-')
            .replace(REPEATED_HYPHENS, "-")
            .ifEmpty { "feature" }
    }

    private fun renderFeatureBody(
        feature: WizardFeature,
        incomingEdges: List<WizardFeatureEdge>,
        featuresById: Map<String, WizardFeature>,
        pathsById: Map<String, String>,
    ): String {
        val criteria = feature.acceptanceCriteria.joinToString("\n") { criterion ->
            "- [ ] ${criterion.text} <!-- okf:id=${criterion.id} -->"
        }
        val targetPath = pathsById.getValue(feature.id)
        val relationships = incomingEdges.mapNotNull { edge ->
            val source = featuresById[edge.from] ?: return@mapNotNull null
            val sourcePath = pathsById[edge.from] ?: return@mapNotNull null
            val relativePath = relativePath(targetPath, sourcePath)
            "- depends-on: [${source.title}]($relativePath) <!-- okf:id=${edge.id} -->"
        }.joinToString("\n")

        return buildString {
            appendSection("Problem", feature.description)
            appendSection("Outcome", "")
            appendSection("Acceptance Criteria", criteria)
            append("## Relationships\n")
            if (relationships.isNotEmpty()) {
                append("\n")
                append(relationships)
                append("\n")
            }
        }
    }

    private fun StringBuilder.appendSection(title: String, content: String) {
        append("## ")
        append(title)
        append("\n\n")
        append(content)
        append("\n\n")
    }

    private fun renderRootIndex(entries: List<Pair<WizardFeature, String>>): String = buildString {
        append("# Product Specification\n\n## Features\n")
        if (entries.isNotEmpty()) append("\n")
        entries.forEach { (feature, path) -> append("- [${feature.title}]($path)\n") }
    }

    private fun renderFeaturesIndex(entries: List<Pair<WizardFeature, String>>): String = buildString {
        append("# Features\n")
        if (entries.isNotEmpty()) append("\n")
        entries.forEach { (feature, path) ->
            append("- [${feature.title}](${Path.of(path).fileName})\n")
        }
    }

    private fun decodeScopes(document: OkfDocument, issues: MutableList<OkfIssue>): Set<FeatureScope> {
        val values = document.frontmatter["scopes"] ?: return emptySet()
        if (values !is List<*>) {
            issues += warning(document.path, "scopes", "feature scopes must be a list; ignoring value")
            return emptySet()
        }
        return values.mapNotNull { value ->
            val scope = (value as? String)?.let { name ->
                FeatureScope.entries.firstOrNull { it.name == name }
            }
            if (scope == null) {
                issues += warning(document.path, "scopes", "unknown feature scope '$value' was ignored")
            }
            scope
        }.toSet()
    }

    private fun decodeScopeFields(document: OkfDocument, issues: MutableList<OkfIssue>): Map<String, String> {
        val values = document.frontmatter["scope_fields"] ?: return emptyMap()
        if (values !is Map<*, *>) {
            issues += warning(document.path, "scope_fields", "feature scope_fields must be a map; ignoring value")
            return emptyMap()
        }
        val result = linkedMapOf<String, String>()
        for ((key, value) in values) {
            if (key is String && value is String) {
                result[key] = value
            } else {
                issues += warning(document.path, "scope_fields", "non-string scope field was ignored")
            }
        }
        return result
    }

    private fun decodeAcceptanceCriteria(
        document: OkfDocument,
        featureId: String,
        issues: MutableList<OkfIssue>,
    ): List<AcceptanceCriterion> = sectionLines(document.body, ACCEPTANCE_CRITERIA_HEADING).mapNotNull { line ->
        val match = ACCEPTANCE_CRITERION.matchEntire(line.trim()) ?: return@mapNotNull null
        val text = match.groupValues[1].trim()
        val parsedId = match.groupValues[2].takeIf { it.isNotBlank() }?.let(::canonicalUuid)
        val id = parsedId ?: stableUuid("criterion:$featureId:$text")
        if (parsedId == null) {
            issues += warning(
                document.path,
                "acceptanceCriteria",
                "acceptance criterion is missing a valid okf:id comment; generated deterministic id $id",
            )
        }
        AcceptanceCriterion(id, text)
    }

    private fun decodeRelationships(
        decoded: DecodedFeatureDocument,
        featureIdByPath: Map<String, String>,
        issues: MutableList<OkfIssue>,
    ): List<WizardFeatureEdge> = sectionLines(decoded.document.body, RELATIONSHIPS_HEADING).mapNotNull { line ->
        val match = RELATIONSHIP.matchEntire(line.trim()) ?: return@mapNotNull null
        val sourcePath = resolveRelativePath(decoded.document.path, match.groupValues[1])
        val sourceId = sourcePath?.let(featureIdByPath::get)
        if (sourceId == null) {
            issues += warning(
                decoded.document.path,
                "relationships",
                "depends-on relationship target '${match.groupValues[1]}' is not a decoded feature",
            )
            return@mapNotNull null
        }
        val parsedId = match.groupValues[2].takeIf { it.isNotBlank() }?.let(::canonicalUuid)
        val id = parsedId ?: stableUuid("edge:$sourceId:${decoded.feature.id}:depends-on")
        if (parsedId == null) {
            issues += warning(
                decoded.document.path,
                "relationships",
                "relationship is missing a valid okf:id comment; generated deterministic id $id",
            )
        }
        WizardFeatureEdge(id = id, from = sourceId, to = decoded.feature.id)
    }

    private fun sectionLines(body: String, heading: String): List<String> {
        val lines = body.lines()
        val start = lines.indexOfFirst { it.trim() == "## $heading" }
        if (start < 0) return emptyList()
        val end = (start + 1 until lines.size).firstOrNull { lines[it].startsWith("## ") } ?: lines.size
        return lines.subList(start + 1, end)
    }

    private fun requiredUuid(
        document: OkfDocument,
        field: String,
        issues: MutableList<OkfIssue>,
    ): String? {
        val id = (document.frontmatter[field] as? String)?.let(::canonicalUuid)
        if (id == null) issues += error(document.path, field, "feature $field must be a valid UUID")
        return id
    }

    private fun canonicalUuid(value: String): String? = try {
        UUID.fromString(value).toString().takeIf { it.equals(value, ignoreCase = true) }
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun stableUuid(value: String): String =
        UUID.nameUUIDFromBytes(value.toByteArray(StandardCharsets.UTF_8)).toString()

    private fun relativePath(from: String, to: String): String {
        val parent = Path.of(from).parent ?: Path.of("")
        return parent.relativize(Path.of(to)).joinToString("/")
    }

    private fun resolveRelativePath(from: String, target: String): String? = try {
        if (target.contains('\\')) return null
        val targetPath = Path.of(target)
        if (targetPath.isAbsolute) return null
        val parent = Path.of(from).parent ?: Path.of("")
        val resolved = parent.resolve(targetPath).normalize()
        if (resolved.startsWith("..")) null else resolved.joinToString("/")
    } catch (_: InvalidPathException) {
        null
    }

    private fun warning(path: String, field: String, message: String) =
        OkfIssue(OkfIssueSeverity.WARNING, path, field, message)

    private fun error(path: String, field: String, message: String) =
        OkfIssue(OkfIssueSeverity.ERROR, path, field, message)

    private fun OkfBundle?.orEmpty(): List<OkfDocument> = this?.documents.orEmpty()

    private data class DecodedFeatureDocument(
        val document: OkfDocument,
        val feature: WizardFeature,
    )

    private companion object {
        const val FEATURE_TYPE = "feature"
        const val ROOT_INDEX_PATH = "index.md"
        const val FEATURES_INDEX_PATH = "features/index.md"
        const val LOG_FILENAME = "log.md"
        const val ACCEPTANCE_CRITERIA_HEADING = "Acceptance Criteria"
        const val RELATIONSHIPS_HEADING = "Relationships"
        val COMBINING_MARKS = Regex("\\p{M}+")
        val NON_ASCII_ALPHANUMERIC = Regex("[^a-z0-9]+")
        val REPEATED_HYPHENS = Regex("-+")
        val ACCEPTANCE_CRITERION = Regex(
            "^- \\[ \\] (.*?)(?: \\Q<!-- okf:id=\\E([^>]+)\\Q -->\\E)?$",
        )
        val RELATIONSHIP = Regex(
            "^- depends-on: \\[[^]]+\\]\\(([^)]+)\\)(?: \\Q<!-- okf:id=\\E([^>]+)\\Q -->\\E)?$",
        )
    }
}

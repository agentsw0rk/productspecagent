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
        validateGraph(graph)
        val existingDocuments = existing.orEmpty()
        val generatedPaths = setOf(ROOT_INDEX_PATH, FEATURES_INDEX_PATH)
        val preservedDocuments = existingDocuments
            .filterNot { it.path in generatedPaths || it.frontmatter["type"] == FEATURE_TYPE }
            .sortedBy { it.path }
        val existingFeaturesById = linkedMapOf<String, OkfDocument>()
        existingDocuments
            .filter { it.frontmatter["type"] == FEATURE_TYPE }
            .sortedBy { it.path }
            .forEach { document ->
                val key = (document.frontmatter["id"] as? String)?.let(::canonicalUuidKey)
                if (key != null) existingFeaturesById.putIfAbsent(key, document)
            }
        val sortedFeatures = graph.features.sortedWith(
            compareBy<WizardFeature>({ slug(it.title) }, { canonicalUuidKey(it.id) ?: it.id }, { it.title }),
        )
        val featurePaths = allocateFeaturePaths(sortedFeatures, generatedPaths + preservedDocuments.map { it.path })
        val pathsById = featurePaths.associate { it.id to it.path }
        val pathsByCanonicalId = featurePaths.associate { canonicalUuidKey(it.id)!! to it.path }
        val featuresByCanonicalId = graph.features.associateBy { canonicalUuidKey(it.id)!! }
        val incomingEdges = graph.edges
            .sortedWith(
                compareBy(
                    { pathsByCanonicalId[canonicalUuidKey(it.from)] ?: it.from },
                    { pathsByCanonicalId[canonicalUuidKey(it.to)] ?: it.to },
                    { it.id },
                ),
            )
            .groupBy { canonicalUuidKey(it.to)!! }

        val featureDocuments = sortedFeatures.map { feature ->
            val existingDocument = canonicalUuidKey(feature.id)?.let(existingFeaturesById::get)
            val frontmatter = existingDocument?.frontmatter?.let(::LinkedHashMap) ?: linkedMapOf()
            val body = renderFeatureBody(
                feature = feature,
                incomingEdges = incomingEdges[canonicalUuidKey(feature.id)].orEmpty(),
                featuresById = featuresByCanonicalId,
                pathsById = pathsByCanonicalId,
                existingBody = existingDocument?.body,
            )
            val semanticChange = existingDocument == null ||
                knownFeatureFieldsChanged(existingDocument.frontmatter, feature) ||
                existingDocument.body != body
            val documentTimestamp = if (semanticChange) {
                timestamp.toString()
            } else {
                existingDocument.frontmatter["timestamp"] as? String ?: timestamp.toString()
            }
            updateFeatureFrontmatter(frontmatter, feature, documentTimestamp)
            OkfDocument(
                path = pathsById.getValue(feature.id),
                frontmatter = frontmatter,
                body = body,
            )
        }.sortedBy { it.path }
        val indexEntries = sortedFeatures
            .map { feature -> feature to pathsById.getValue(feature.id) }
            .sortedBy { it.second }

        return OkfBundle(
            listOf(
                OkfDocument(
                    ROOT_INDEX_PATH,
                    linkedMapOf("okf_version" to OKF_VERSION, "profile" to OKF_PROFILE),
                    renderRootIndex(indexEntries),
                ),
                OkfDocument(FEATURES_INDEX_PATH, linkedMapOf(), renderFeaturesIndex(indexEntries)),
            ) + featureDocuments + preservedDocuments,
        )
    }

    fun decode(bundle: OkfBundle, positions: Map<String, GraphPosition>): OkfFeatureDecodeResult {
        val issues = mutableListOf<OkfIssue>()
        val decodedDocuments = mutableListOf<DecodedFeatureDocument>()
        val seenIds = mutableSetOf<String>()
        val assignedContentIds = mutableSetOf<String>()

        for (document in bundle.documents.filter { it.frontmatter["type"] == FEATURE_TYPE }.sortedBy { it.path }) {
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
                acceptanceCriteria = decodeAcceptanceCriteria(document, id, assignedContentIds, issues),
                position = positions[id] ?: GraphPosition(),
            )
            decodedDocuments += DecodedFeatureDocument(document, feature)
        }

        val featureIdByPath = decodedDocuments.associate { it.document.path to it.feature.id }
        val edges = decodedDocuments.flatMap { decoded ->
            decodeRelationships(decoded, featureIdByPath, assignedContentIds, issues)
        }

        return OkfFeatureDecodeResult(
            graph = WizardFeatureGraph(decodedDocuments.map { it.feature }, edges),
            issues = issues,
        )
    }

    private fun updateFeatureFrontmatter(
        frontmatter: LinkedHashMap<String, Any?>,
        feature: WizardFeature,
        timestamp: String,
    ) {
        val tags = (frontmatter["tags"] as? List<*>)?.toList() ?: emptyList<String>()
        val legacyIds = (frontmatter["legacy_ids"] as? List<*>)?.toList() ?: emptyList<String>()
        frontmatter["type"] = FEATURE_TYPE
        frontmatter["id"] = feature.id
        frontmatter["title"] = feature.title
        frontmatter["description"] = feature.description
        frontmatter["tags"] = tags
        frontmatter["timestamp"] = timestamp
        frontmatter["scopes"] = FeatureScope.entries.filter(feature.scopes::contains).map { it.name }
        frontmatter["scope_fields"] = LinkedHashMap(feature.scopeFields.toSortedMap())
        frontmatter["legacy_ids"] = legacyIds
    }

    private fun knownFeatureFieldsChanged(frontmatter: Map<String, Any?>, feature: WizardFeature): Boolean {
        val expectedScopes = FeatureScope.entries.filter(feature.scopes::contains).map { it.name }.toSet()
        val existingScopes = (frontmatter["scopes"] as? List<*>)?.filterIsInstance<String>()?.toSet()
        return frontmatter["type"] != FEATURE_TYPE ||
            canonicalUuidKey(frontmatter["id"] as? String ?: "") != canonicalUuidKey(feature.id) ||
            frontmatter["title"] != feature.title ||
            frontmatter["description"] != feature.description ||
            frontmatter["tags"] !is List<*> ||
            existingScopes != expectedScopes ||
            frontmatter["scope_fields"] != feature.scopeFields ||
            frontmatter["legacy_ids"] !is List<*>
    }

    private fun validateGraph(graph: WizardFeatureGraph) {
        val featureIds = mutableSetOf<String>()
        val contentIds = mutableSetOf<String>()
        graph.features.forEach { feature ->
            val featureId = requireCanonicalId(feature.id, "feature id")
            require(featureIds.add(featureId)) { "duplicate feature id: ${feature.id}" }
            feature.acceptanceCriteria.forEach { criterion ->
                val criterionId = requireCanonicalId(criterion.id, "criterion id")
                require(contentIds.add(criterionId)) { "duplicate content id: ${criterion.id}" }
                require('\n' !in criterion.text && '\r' !in criterion.text) {
                    "acceptance criterion ${criterion.id} must be a single line"
                }
            }
        }
        graph.edges.forEach { edge ->
            val edgeId = requireCanonicalId(edge.id, "edge id")
            require(contentIds.add(edgeId)) { "duplicate content id: ${edge.id}" }
            require(canonicalUuidKey(edge.from) in featureIds) { "edge source does not exist: ${edge.from}" }
            require(canonicalUuidKey(edge.to) in featureIds) { "edge target does not exist: ${edge.to}" }
        }
    }

    private fun requireCanonicalId(id: String, label: String): String =
        requireNotNull(canonicalUuidKey(id)) { "$label must be a canonical UUID: $id" }

    private fun allocateFeaturePaths(
        features: List<WizardFeature>,
        reservedPaths: Collection<String>,
    ): List<OkfFeaturePath> {
        val usedPaths = reservedPaths.toMutableSet()
        return features.map { feature ->
            val baseSlug = slug(feature.title)
            OkfFeaturePath(feature.id, uniqueFeaturePath(baseSlug, feature.id, usedPaths))
        }
    }

    private fun uniqueFeaturePath(baseSlug: String, id: String, usedPaths: MutableSet<String>): String {
        val basePath = "features/$baseSlug.md"
        if (usedPaths.add(basePath)) return basePath

        val stableId = id.lowercase(Locale.ROOT)
        val shortCandidate = "features/$baseSlug-${stableId.take(8)}.md"
        if (usedPaths.add(shortCandidate)) return shortCandidate

        val fullCandidate = "features/$baseSlug-$stableId.md"
        if (usedPaths.add(fullCandidate)) return fullCandidate

        var discriminator = 2
        var candidate = "features/$baseSlug-$stableId-$discriminator.md"
        while (!usedPaths.add(candidate)) {
            discriminator += 1
            candidate = "features/$baseSlug-$stableId-$discriminator.md"
        }
        return candidate
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
        existingBody: String?,
    ): String {
        val criteria = feature.acceptanceCriteria.joinToString("\n") { criterion ->
            "- [ ] ${criterion.text} <!-- okf:id=${criterion.id} -->"
        }
        val targetPath = pathsById.getValue(canonicalUuidKey(feature.id)!!)
        val relationships = incomingEdges.mapNotNull { edge ->
            val sourceId = canonicalUuidKey(edge.from) ?: return@mapNotNull null
            val source = featuresById[sourceId] ?: return@mapNotNull null
            val sourcePath = pathsById[sourceId] ?: return@mapNotNull null
            val relativePath = relativePath(targetPath, sourcePath)
            "- depends-on: [${markdownLabel(source.title)}]($relativePath) <!-- okf:id=${edge.id} -->"
        }.joinToString("\n")
        val acceptanceSection = renderManagedSection(ACCEPTANCE_CRITERIA_HEADING, criteria)
        val relationshipsSection = renderRelationshipsSection(relationships)

        if (existingBody != null) {
            return replaceManagedSections(existingBody, acceptanceSection, relationshipsSection)
        }
        return buildString {
            appendSection("Problem", "")
            appendSection("Outcome", "")
            append(acceptanceSection)
            append(relationshipsSection)
        }
    }

    private fun renderManagedSection(title: String, content: String): String = buildString {
        appendSection(title, content)
    }

    private fun renderRelationshipsSection(relationships: String): String = buildString {
        append("## Relationships\n")
        if (relationships.isNotEmpty()) {
            append("\n")
            append(relationships)
            append("\n")
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
        entries.forEach { (feature, path) -> append("- [${markdownLabel(feature.title)}]($path)\n") }
    }

    private fun renderFeaturesIndex(entries: List<Pair<WizardFeature, String>>): String = buildString {
        append("# Features\n")
        if (entries.isNotEmpty()) append("\n")
        entries.forEach { (feature, path) ->
            append("- [${markdownLabel(feature.title)}](${Path.of(path).fileName})\n")
        }
    }

    private fun markdownLabel(value: String): String = value
        .replace(Regex("[\\r\\n]+"), " ")
        .replace("\\", "\\\\")
        .replace("[", "\\[")
        .replace("]", "\\]")

    private fun replaceManagedSections(
        body: String,
        acceptanceSection: String,
        relationshipsSection: String,
    ): String {
        val sections = markdownSections(body)
        val replacements = mutableListOf<SectionReplacement>()
        for ((heading, replacement) in listOf(
            ACCEPTANCE_CRITERIA_HEADING to acceptanceSection,
            RELATIONSHIPS_HEADING to relationshipsSection,
        )) {
            sections.filter { it.level == 2 && it.heading == heading }.forEachIndexed { index, section ->
                replacements += SectionReplacement(section.start, section.end, if (index == 0) replacement else "")
            }
        }

        var result = body
        replacements.sortedByDescending { it.start }.forEach { replacement ->
            result = result.replaceRange(replacement.start, replacement.end, replacement.text)
        }
        if (sections.none { it.level == 2 && it.heading == ACCEPTANCE_CRITERIA_HEADING }) {
            result = appendSectionToBody(result, acceptanceSection)
        }
        if (sections.none { it.level == 2 && it.heading == RELATIONSHIPS_HEADING }) {
            result = appendSectionToBody(result, relationshipsSection)
        }
        return result
    }

    private fun appendSectionToBody(body: String, section: String): String = when {
        body.isEmpty() -> section
        body.endsWith("\n\n") -> body + section
        body.endsWith('\n') -> "$body\n$section"
        else -> "$body\n\n$section"
    }

    private fun markdownSections(body: String): List<MarkdownSection> {
        val lines = markdownLines(body)
        val headings = mutableListOf<DetectedHeading>()
        lines.forEachIndexed { index, line ->
            if (line.inFence) return@forEachIndexed
            val atx = atxHeading(line.text)
            if (atx != null) {
                headings += DetectedHeading(index, index, line.start, atx.level, atx.text)
                return@forEachIndexed
            }
            val setextLevel = setextHeadingLevel(line.text) ?: return@forEachIndexed
            val titleLine = lines.getOrNull(index - 1)
                ?.takeIf { !it.inFence && it.text.isNotBlank() && atxHeading(it.text) == null }
                ?: return@forEachIndexed
            headings += DetectedHeading(index - 1, index, titleLine.start, setextLevel, titleLine.text.trim())
        }
        return headings.mapIndexed { headingIndex, heading ->
            val nextHeading = headings.getOrNull(headingIndex + 1)
            MarkdownSection(
                heading = heading.text,
                level = heading.level,
                start = heading.start,
                end = nextHeading?.start ?: body.length,
                contentLines = lines.subList(
                    heading.contentLineIndex + 1,
                    nextHeading?.startLineIndex ?: lines.size,
                ).filterNot { it.inFence },
            )
        }
    }

    private fun markdownLines(body: String): List<MarkdownLine> {
        val result = mutableListOf<MarkdownLine>()
        var offset = 0
        var fence: Fence? = null
        while (offset < body.length) {
            val newline = body.indexOf('\n', offset)
            val contentEnd = if (newline < 0) body.length else newline
            val lineEnd = if (newline < 0) body.length else newline + 1
            val text = body.substring(offset, contentEnd).removeSuffix("\r")
            val marker = fenceMarker(text)
            val inFence = fence != null || marker != null
            if (fence == null && marker != null) {
                fence = marker
            } else if (fence != null && isClosingFence(text, fence)) {
                fence = null
            }
            result += MarkdownLine(offset, text, inFence)
            offset = lineEnd
        }
        return result
    }

    private fun fenceMarker(line: String): Fence? {
        val content = line.dropWhile { it == ' ' }
        if (line.length - content.length > 3) return null
        val marker = content.firstOrNull()?.takeIf { it == '`' || it == '~' } ?: return null
        val length = content.takeWhile { it == marker }.length
        return if (length >= 3) Fence(marker, length) else null
    }

    private fun isClosingFence(line: String, fence: Fence): Boolean {
        val content = line.dropWhile { it == ' ' }
        if (line.length - content.length > 3) return false
        val length = content.takeWhile { it == fence.marker }.length
        return length >= fence.length && content.drop(length).isBlank()
    }

    private fun atxHeading(line: String): HeadingValue? {
        val content = line.dropWhile { it == ' ' }
        if (line.length - content.length > 3 || !content.startsWith('#')) return null
        val level = content.takeWhile { it == '#' }.length
        if (level !in 1..2) return null
        if (content.length > level && !content[level].isWhitespace()) return null
        val text = content.drop(level).trim().replace(Regex("\\s+#+\\s*$"), "").trim()
        return HeadingValue(level, text)
    }

    private fun setextHeadingLevel(line: String): Int? {
        val content = line.dropWhile { it == ' ' }
        if (line.length - content.length > 3) return null
        val marker = content.trimEnd()
        return when {
            marker.isNotEmpty() && marker.all { it == '=' } -> 1
            marker.isNotEmpty() && marker.all { it == '-' } -> 2
            else -> null
        }
    }

    private fun managedSectionLines(body: String, heading: String): List<MarkdownLine> =
        markdownSections(body).firstOrNull { it.level == 2 && it.heading == heading }?.contentLines.orEmpty()

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
        assignedContentIds: MutableSet<String>,
        issues: MutableList<OkfIssue>,
    ): List<AcceptanceCriterion> {
        val result = mutableListOf<AcceptanceCriterion>()
        var occurrence = 0
        for (line in managedSectionLines(document.body, ACCEPTANCE_CRITERIA_HEADING)) {
            val textLine = line.text.trim()
            if (!MARKDOWN_LIST_ITEM.containsMatchIn(textLine)) continue
            val match = ACCEPTANCE_CRITERION.matchEntire(textLine)
            if (match == null) {
                issues += warning(document.path, "acceptanceCriteria", "unsupported list item in Acceptance Criteria")
                continue
            }
            val text = match.groupValues[1].trim()
            val explicitId = match.groupValues[2].takeIf { it.isNotBlank() }?.let(::canonicalUuid)
            if (explicitId != null && !assignedContentIds.add(explicitId)) {
                issues += error(document.path, "acceptanceCriteria", "duplicate explicit okf:id '$explicitId'")
            }
            val id = explicitId ?: stableUuid("criterion:$featureId:$occurrence:$text")
            if (explicitId == null) {
                if (!assignedContentIds.add(id)) {
                    issues += error(document.path, "acceptanceCriteria", "duplicate assigned okf:id '$id'")
                }
                issues += warning(
                    document.path,
                    "acceptanceCriteria",
                    "acceptance criterion is missing a valid okf:id comment; generated deterministic id $id",
                )
            }
            result += AcceptanceCriterion(id, text)
            occurrence += 1
        }
        return result
    }

    private fun decodeRelationships(
        decoded: DecodedFeatureDocument,
        featureIdByPath: Map<String, String>,
        assignedContentIds: MutableSet<String>,
        issues: MutableList<OkfIssue>,
    ): List<WizardFeatureEdge> {
        val result = mutableListOf<WizardFeatureEdge>()
        var occurrence = 0
        for (line in managedSectionLines(decoded.document.body, RELATIONSHIPS_HEADING)) {
            val textLine = line.text.trim()
            if (!MARKDOWN_LIST_ITEM.containsMatchIn(textLine)) continue
            val relationship = parseRelationship(textLine)
            if (relationship == null) {
                issues += warning(decoded.document.path, "relationships", "unsupported list item in Relationships")
                continue
            }
            val explicitId = relationship.id?.let(::canonicalUuid)
            if (explicitId != null && !assignedContentIds.add(explicitId)) {
                issues += error(decoded.document.path, "relationships", "duplicate explicit okf:id '$explicitId'")
            }
            val sourcePath = resolveRelativePath(decoded.document.path, relationship.path)
            val sourceId = sourcePath?.let(featureIdByPath::get)
            if (sourceId == null) {
                issues += warning(
                    decoded.document.path,
                    "relationships",
                    "depends-on relationship target '${relationship.path}' is not a decoded feature",
                )
                occurrence += 1
                continue
            }
            val id = explicitId ?: stableUuid("edge:$sourceId:${decoded.feature.id}:depends-on:$occurrence")
            if (explicitId == null) {
                if (!assignedContentIds.add(id)) {
                    issues += error(decoded.document.path, "relationships", "duplicate assigned okf:id '$id'")
                }
                issues += warning(
                    decoded.document.path,
                    "relationships",
                    "relationship is missing a valid okf:id comment; generated deterministic id $id",
                )
            }
            result += WizardFeatureEdge(id = id, from = sourceId, to = decoded.feature.id)
            occurrence += 1
        }
        return result
    }

    private fun parseRelationship(line: String): ParsedRelationship? {
        if (!line.startsWith(RELATIONSHIP_PREFIX)) return null
        var index = RELATIONSHIP_PREFIX.length
        var escaped = false
        while (index < line.length) {
            val character = line[index]
            if (escaped) {
                escaped = false
            } else if (character == '\\') {
                escaped = true
            } else if (character == ']') {
                break
            }
            index += 1
        }
        if (index >= line.length || line.getOrNull(index + 1) != '(') return null
        val pathStart = index + 2
        val pathEnd = line.indexOf(')', pathStart)
        if (pathEnd < 0) return null
        val suffix = line.substring(pathEnd + 1)
        val id = when {
            suffix.isEmpty() -> null
            else -> OKF_ID_SUFFIX.matchEntire(suffix)?.groupValues?.get(1) ?: return null
        }
        return ParsedRelationship(path = line.substring(pathStart, pathEnd), id = id)
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

    private fun canonicalUuidKey(value: String): String? = canonicalUuid(value)

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

    private data class MarkdownLine(
        val start: Int,
        val text: String,
        val inFence: Boolean,
    )

    private data class MarkdownSection(
        val heading: String,
        val level: Int,
        val start: Int,
        val end: Int,
        val contentLines: List<MarkdownLine>,
    )

    private data class DetectedHeading(
        val startLineIndex: Int,
        val contentLineIndex: Int,
        val start: Int,
        val level: Int,
        val text: String,
    )

    private data class HeadingValue(val level: Int, val text: String)

    private data class SectionReplacement(
        val start: Int,
        val end: Int,
        val text: String,
    )

    private data class Fence(val marker: Char, val length: Int)

    private data class ParsedRelationship(val path: String, val id: String?)

    private companion object {
        const val FEATURE_TYPE = "feature"
        const val ROOT_INDEX_PATH = "index.md"
        const val FEATURES_INDEX_PATH = "features/index.md"
        const val OKF_VERSION = "0.1"
        const val OKF_PROFILE = "productspecagent/1"
        const val ACCEPTANCE_CRITERIA_HEADING = "Acceptance Criteria"
        const val RELATIONSHIPS_HEADING = "Relationships"
        val COMBINING_MARKS = Regex("\\p{M}+")
        val NON_ASCII_ALPHANUMERIC = Regex("[^a-z0-9]+")
        val REPEATED_HYPHENS = Regex("-+")
        val MARKDOWN_LIST_ITEM = Regex("^(?:[-+*]|\\d+[.)])(?:\\s+|$)")
        val ACCEPTANCE_CRITERION = Regex(
            "^- \\[ \\] (.*?)(?: \\Q<!-- okf:id=\\E([^>]+)\\Q -->\\E)?$",
        )
        const val RELATIONSHIP_PREFIX = "- depends-on: ["
        val OKF_ID_SUFFIX = Regex("^ \\Q<!-- okf:id=\\E([^>]+)\\Q -->\\E$")
    }
}

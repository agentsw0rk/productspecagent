package com.agentwork.productspecagent.service.okf

import com.agentwork.productspecagent.domain.okf.OkfBundle
import com.agentwork.productspecagent.domain.okf.OkfDocument
import com.agentwork.productspecagent.domain.okf.OkfIssue
import com.agentwork.productspecagent.domain.okf.OkfIssueSeverity
import org.commonmark.node.AbstractVisitor
import org.commonmark.node.Heading
import org.commonmark.node.Link
import org.commonmark.parser.Parser
import org.commonmark.renderer.text.TextContentRenderer
import org.springframework.stereotype.Service
import java.net.URI
import java.net.URISyntaxException
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import java.util.UUID

@Service
class OkfProfileValidator {

    fun validate(bundle: OkfBundle): List<OkfIssue> {
        val issues = mutableListOf<OkfIssue>()
        val ids = mutableMapOf<UUID, String>()
        val bundlePaths = bundle.documents.mapNotNull { normalizedBundlePath(it.path) }.toSet()

        for (document in bundle.documents) {
            if (!isIndexDocument(document.path)) {
                validatePath(document, issues)
                validateFrontmatter(document, ids, issues)
            }
            validateLinks(document, bundlePaths, issues)
        }

        return issues
    }

    private fun isIndexDocument(path: String): Boolean {
        val normalized = normalizedBundlePath(path) ?: return false
        return Path.of(normalized).fileName?.toString() == INDEX_FILENAME
    }

    private fun validatePath(document: OkfDocument, issues: MutableList<OkfIssue>) {
        if (normalizedBundlePath(document.path) == null) {
            issues += error(document.path, "path", "document path is not a safe normalized bundle path")
        }
    }

    private fun validateFrontmatter(
        document: OkfDocument,
        ids: MutableMap<UUID, String>,
        issues: MutableList<OkfIssue>,
    ) {
        for (field in REQUIRED_FIELDS) {
            if (!document.frontmatter.containsKey(field)) {
                issues += error(document.path, field, "$field is required")
            }
        }

        validateType(document, issues)
        validateId(document, ids, issues)

        val title = document.frontmatter["title"] as? String
        if (document.frontmatter.containsKey("title") && title.isNullOrBlank()) {
            issues += error(document.path, "title", "title must not be blank")
        }

        val description = document.frontmatter["description"] as? String
        if (document.frontmatter.containsKey("description") && !isValidDescription(description)) {
            issues += error(
                document.path,
                "description",
                "description must be one sentence of at most 240 characters",
            )
        }

        if (document.frontmatter.containsKey("tags") && document.frontmatter["tags"] !is List<*>) {
            issues += error(document.path, "tags", "tags must be a YAML list")
        }

        if (document.frontmatter.containsKey("timestamp") && !isOffsetDateTime(document.frontmatter["timestamp"])) {
            issues += error(document.path, "timestamp", "timestamp must be a valid ISO offset date-time")
        }
    }

    private fun validateType(document: OkfDocument, issues: MutableList<OkfIssue>) {
        if (!document.frontmatter.containsKey("type")) return

        val type = document.frontmatter["type"]
        if (type !is String) {
            issues += error(document.path, "type", "type must be a string")
        } else if (type !in KNOWN_TYPES) {
            issues += OkfIssue(
                severity = OkfIssueSeverity.WARNING,
                path = document.path,
                field = "type",
                message = "unknown type '$type' is preserved",
            )
        }
    }

    private fun validateId(
        document: OkfDocument,
        ids: MutableMap<UUID, String>,
        issues: MutableList<OkfIssue>,
    ) {
        if (!document.frontmatter.containsKey("id")) return

        val id = (document.frontmatter["id"] as? String)?.let(::parseUuid)
        if (id == null) {
            issues += error(document.path, "id", "id must be a valid UUID")
            return
        }

        val firstPath = ids.putIfAbsent(id, document.path)
        if (firstPath != null) {
            issues += error(document.path, "id", "duplicate id; first declared in $firstPath")
        }
    }

    private fun validateLinks(document: OkfDocument, bundlePaths: Set<String>, issues: MutableList<OkfIssue>) {
        val root = Parser.builder().build().parse(document.body)
        var currentLevelTwoHeading: String? = null
        val headingRenderer = TextContentRenderer.builder().build()

        root.accept(object : AbstractVisitor() {
            override fun visit(heading: Heading) {
                if (heading.level == 2) {
                    currentLevelTwoHeading = headingRenderer.render(heading).trimEnd()
                } else if (heading.level < 2) {
                    currentLevelTwoHeading = null
                }
                visitChildren(heading)
            }

            override fun visit(link: Link) {
                validateLink(
                    document = document,
                    destination = link.destination,
                    inCitations = currentLevelTwoHeading == CITATIONS_HEADING,
                    bundlePaths = bundlePaths,
                    issues = issues,
                )
                visitChildren(link)
            }
        })
    }

    private fun validateLink(
        document: OkfDocument,
        destination: String,
        inCitations: Boolean,
        bundlePaths: Set<String>,
        issues: MutableList<OkfIssue>,
    ) {
        if (destination.isBlank()) return

        val uri = try {
            URI(destination)
        } catch (_: URISyntaxException) {
            issues += error(document.path, "link", "link target is not a safe normalized bundle path")
            return
        }
        val scheme = uri.scheme?.lowercase()
        if (scheme != null) {
            if (scheme !in HTTP_SCHEMES || !inCitations) {
                issues += error(document.path, "link", "external links are allowed only in Citations")
            }
            return
        }

        val target = resolveRelativeTarget(document.path, uri)
        if (target == null) {
            issues += error(document.path, "link", "link target is not a safe normalized bundle path")
        } else if (target !in bundlePaths) {
            issues += OkfIssue(
                severity = OkfIssueSeverity.WARNING,
                path = document.path,
                field = "link",
                message = "link target does not exist in bundle: $target",
            )
        }
    }

    private fun resolveRelativeTarget(sourcePath: String, destination: URI): String? {
        if (destination.rawAuthority != null) return null
        val decodedPath = destination.path ?: return null
        if (decodedPath.isBlank() || decodedPath.contains('\\')) return null

        return try {
            val destinationPath = Path.of(decodedPath)
            if (destinationPath.isAbsolute) return null

            val normalizedSource = normalizedBundlePath(sourcePath) ?: return null
            val sourceParent = Path.of(normalizedSource).parent ?: Path.of("")
            val target = sourceParent.resolve(destinationPath).normalize()
            if (target.isAbsolute || target.startsWith("..")) null else target.toBundlePath()
        } catch (_: InvalidPathException) {
            null
        }
    }

    private fun normalizedBundlePath(path: String): String? {
        if (path.isBlank() || path.contains('\\')) return null

        return try {
            val parsed = Path.of(path)
            val normalized = parsed.normalize()
            val canonical = normalized.toBundlePath()
            if (parsed.isAbsolute || normalized.startsWith("..") || canonical != path) null else canonical
        } catch (_: InvalidPathException) {
            null
        }
    }

    private fun Path.toBundlePath(): String = iterator().asSequence().joinToString("/") { it.toString() }

    private fun isValidDescription(description: String?): Boolean =
        description != null &&
            description.isNotBlank() &&
            description.length <= 240 &&
            '\n' !in description &&
            '\r' !in description &&
            description.last() in DESCRIPTION_ENDINGS

    private fun isOffsetDateTime(value: Any?): Boolean {
        if (value !is String) return false
        return try {
            OffsetDateTime.parse(value)
            true
        } catch (_: DateTimeParseException) {
            false
        }
    }

    private fun parseUuid(value: String): UUID? = try {
        UUID.fromString(value).takeIf { it.toString().equals(value, ignoreCase = true) }
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun error(path: String, field: String, message: String) =
        OkfIssue(OkfIssueSeverity.ERROR, path, field, message)

    private companion object {
        const val INDEX_FILENAME = "index.md"
        const val CITATIONS_HEADING = "Citations"
        val REQUIRED_FIELDS = listOf("type", "id", "title", "description", "tags", "timestamp")
        val KNOWN_TYPES = setOf(
            "feature",
            "decision",
            "architecture-overview",
            "architecture-component",
            "architecture-interface",
            "architecture-data-model",
            "architecture-constraint",
        )
        val HTTP_SCHEMES = setOf("http", "https")
        val DESCRIPTION_ENDINGS = setOf('.', '!', '?')
    }
}

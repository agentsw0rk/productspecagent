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

        for (document in bundle.documents) {
            if (document.path != INDEX_PATH) {
                validatePath(document, issues)
                validateFrontmatter(document, ids, issues)
            }
            validateLinks(document, bundle.byPath.keys, issues)
        }

        return issues
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
                    destination = link.destination.substringBefore('#'),
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

        val scheme = URI_SCHEME.find(destination)?.groupValues?.get(1)
        if (scheme != null) {
            if (scheme.lowercase() !in HTTP_SCHEMES || !inCitations) {
                issues += error(document.path, "link", "external links are allowed only in Citations")
            }
            return
        }

        val target = resolveRelativeTarget(document.path, destination)
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

    private fun resolveRelativeTarget(sourcePath: String, destination: String): String? {
        if (destination.contains('\\')) return null

        return try {
            val destinationPath = Path.of(destination)
            if (destinationPath.isAbsolute) return null

            val sourceParent = Path.of(sourcePath).parent ?: Path.of("")
            val target = sourceParent.resolve(destinationPath).normalize()
            if (target.isAbsolute || target.startsWith("..")) null else target.toString()
        } catch (_: InvalidPathException) {
            null
        }
    }

    private fun normalizedBundlePath(path: String): String? {
        if (path.isBlank() || path.contains('\\')) return null

        return try {
            val parsed = Path.of(path)
            val normalized = parsed.normalize()
            if (parsed.isAbsolute || normalized.startsWith("..") || normalized.toString() != path) null else path
        } catch (_: InvalidPathException) {
            null
        }
    }

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
        UUID.fromString(value)
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun error(path: String, field: String, message: String) =
        OkfIssue(OkfIssueSeverity.ERROR, path, field, message)

    private companion object {
        const val INDEX_PATH = "index.md"
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
        val URI_SCHEME = Regex("^([A-Za-z][A-Za-z0-9+.-]*):")
        val DESCRIPTION_ENDINGS = setOf('.', '!', '?')
    }
}

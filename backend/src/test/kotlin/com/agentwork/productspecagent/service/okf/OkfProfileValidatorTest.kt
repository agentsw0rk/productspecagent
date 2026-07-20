package com.agentwork.productspecagent.service.okf

import com.agentwork.productspecagent.domain.okf.OkfBundle
import com.agentwork.productspecagent.domain.okf.OkfDocument
import com.agentwork.productspecagent.domain.okf.OkfIssue
import com.agentwork.productspecagent.domain.okf.OkfIssueSeverity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OkfProfileValidatorTest {

    private val validator = OkfProfileValidator()

    @Test
    fun `requires exactly the profile fields`() {
        val issues = validate(document(frontmatter = linkedMapOf("x-extra" to "preserved")))

        assertEquals(
            setOf("type", "id", "title", "description", "tags", "timestamp"),
            issues.filter { it.severity == OkfIssueSeverity.ERROR }.mapNotNull { it.field }.toSet(),
        )
        assertEquals(6, issues.count { it.severity == OkfIssueSeverity.ERROR })
    }

    @Test
    fun `rejects invalid UUID values and duplicate ids without skipping broken links`() {
        val duplicateId = "ed0e7df7-4a92-4ab1-a4a1-83ce157456cb"
        val first = document(path = "concepts/first.md", id = duplicateId)
        val second = document(
            path = "concepts/second.md",
            id = duplicateId,
            body = "[Missing](missing.md)",
        )
        val invalid = document(path = "concepts/invalid.md", id = "not-a-uuid")

        val issues = validator.validate(OkfBundle(listOf(first, second, invalid)))

        assertIssue(issues, OkfIssueSeverity.ERROR, "concepts/invalid.md", "id", "id must be a valid UUID")
        assertTrue(
            issues.any {
                it.severity == OkfIssueSeverity.ERROR &&
                    it.path == "concepts/second.md" &&
                    it.field == "id" &&
                    it.message.contains("concepts/first.md")
            },
        )
        assertTrue(
            issues.any {
                it.severity == OkfIssueSeverity.WARNING &&
                    it.path == "concepts/second.md" &&
                    it.message.contains("concepts/missing.md")
            },
        )
    }

    @Test
    fun `requires canonical UUID text while accepting canonical uppercase`() {
        val shortenedIssues = validate(document(id = "1-1-1-1-1"))
        val uppercaseIssues = validate(
            document(
                path = "concepts/uppercase.md",
                id = "BE8F64A8-4BD0-4FD6-9E57-F1A69F25D103",
            ),
        )

        assertIssue(
            shortenedIssues,
            OkfIssueSeverity.ERROR,
            field = "id",
            message = "id must be a valid UUID",
        )
        assertTrue(uppercaseIssues.none { it.field == "id" })
    }

    @Test
    fun `requires ids tags and timestamps to have their profile types`() {
        val frontmatter = validFrontmatter().apply {
            this["id"] = 123
            this["tags"] = "architecture"
            this["timestamp"] = "2026-07-17T12:30:00"
        }

        val issues = validate(document(frontmatter = frontmatter))

        assertIssue(issues, OkfIssueSeverity.ERROR, field = "id", message = "id must be a valid UUID")
        assertIssue(issues, OkfIssueSeverity.ERROR, field = "tags", message = "tags must be a YAML list")
        assertIssue(
            issues,
            OkfIssueSeverity.ERROR,
            field = "timestamp",
            message = "timestamp must be a valid ISO offset date-time",
        )
    }

    @Test
    fun `accepts a YAML list and ISO offset date-time timestamp`() {
        val issues = validate(document())

        assertTrue(issues.none { it.field == "tags" || it.field == "timestamp" })
    }

    @Test
    fun `warns that unknown string types are preserved`() {
        val document = document(type = "custom-concept")

        val issues = validate(document)

        assertTrue(
            issues.any {
                it.severity == OkfIssueSeverity.WARNING &&
                    it.field == "type" &&
                    it.message.contains("custom-concept") &&
                    it.message.contains("preserved")
            },
        )
        assertEquals("custom-concept", document.frontmatter["type"])
    }

    @Test
    fun `accepts every known profile type`() {
        val knownTypes = listOf(
            "feature",
            "decision",
            "architecture-overview",
            "architecture-component",
            "architecture-interface",
            "architecture-data-model",
            "architecture-constraint",
        )

        val issues = knownTypes.flatMapIndexed { index, type ->
            validate(
                document(
                    path = "concepts/$type.md",
                    id = "00000000-0000-0000-0000-${(index + 1).toString().padStart(12, '0')}",
                    type = type,
                ),
            )
        }

        assertTrue(issues.none { it.field == "type" })
    }

    @Test
    fun `validates title and description text`() {
        val invalidDescriptions = listOf(
            "",
            "This description has no terminal punctuation",
            "This description has\na newline.",
            "a".repeat(240) + ".",
        )

        val titleIssues = validate(document(title = " "))
        val descriptionIssues = invalidDescriptions.flatMapIndexed { index, description ->
            validate(
                document(
                    path = "concepts/description-$index.md",
                    id = "00000000-0000-0000-0000-${(index + 20).toString().padStart(12, '0')}",
                    description = description,
                ),
            )
        }

        assertIssue(titleIssues, OkfIssueSeverity.ERROR, field = "title", message = "title must not be blank")
        assertEquals(
            invalidDescriptions.size,
            descriptionIssues.count {
                it.severity == OkfIssueSeverity.ERROR &&
                    it.field == "description" &&
                    it.message == "description must be one sentence of at most 240 characters"
            },
        )
    }

    @Test
    fun `accepts safe normalized document paths and rejects unsafe paths`() {
        val documents = listOf(
            document(path = "concepts/safe.md", id = "00000000-0000-0000-0000-000000000101"),
            document(path = "concepts/../unsafe.md", id = "00000000-0000-0000-0000-000000000102"),
            document(path = "/absolute.md", id = "00000000-0000-0000-0000-000000000103"),
            document(path = "concepts\\windows.md", id = "00000000-0000-0000-0000-000000000104"),
        )

        val issues = validator.validate(OkfBundle(documents))

        assertTrue(issues.none { it.path == "concepts/safe.md" && it.field == "path" })
        assertEquals(
            setOf("concepts/../unsafe.md", "/absolute.md", "concepts\\windows.md"),
            issues.filter {
                it.severity == OkfIssueSeverity.ERROR &&
                    it.field == "path" &&
                    it.message == "document path is not a safe normalized bundle path"
            }.map { it.path }.toSet(),
        )
    }

    @Test
    fun `windows drive index paths do not bypass profile validation`() {
        val indexes = listOf(
            OkfDocument("C:/index.md", linkedMapOf(), "# Index"),
            OkfDocument("C:index.md", linkedMapOf(), "# Index"),
        )

        val issues = validator.validate(OkfBundle(indexes))

        indexes.forEach { index ->
            assertIssue(
                issues,
                OkfIssueSeverity.ERROR,
                path = index.path,
                field = "path",
                message = "document path is not a safe normalized bundle path",
            )
            assertIssue(issues, OkfIssueSeverity.ERROR, path = index.path, field = "type")
        }
    }

    @Test
    fun `accepts existing relative links after stripping fragments`() {
        val source = document(
            path = "concepts/source.md",
            body = """
                [Fragment](../architecture/target.md#details)
                [Query](../architecture/target.md?view=details)
            """.trimIndent(),
        )
        val target = document(
            path = "architecture/target.md",
            id = "00000000-0000-0000-0000-000000000201",
        )

        val issues = validator.validate(OkfBundle(listOf(source, target)))

        assertTrue(issues.none { it.path == source.path && it.field == "link" })
    }

    @Test
    fun `accepts safe relative links with a query`() {
        val source = document(
            path = "concepts/source.md",
            body = "[Target](../architecture/target.md?view=full#details)",
        )
        val target = document(
            path = "architecture/target.md",
            id = "00000000-0000-0000-0000-000000000202",
        )

        val issues = validator.validate(OkfBundle(listOf(source, target)))

        assertTrue(issues.none { it.path == source.path && it.field == "link" })
    }

    @Test
    fun `fragment-only and query-only links resolve to the source document`() {
        val source = document(
            path = "concepts/source.md",
            body = "[Fragment](#details)\n[Query](?view=full)",
        )

        val issues = validate(source)

        assertTrue(issues.none { it.path == source.path && it.field == "link" })
    }

    @Test
    fun `decodes internal URI paths before enforcing bundle containment`() {
        val source = document(
            path = "concepts/source.md",
            body = """
                [Encoded dots](%2e%2e/%2e%2e/secret.md)
                [Encoded separators](%2e%2e%2F%2e%2e%2Fsecret.md)
            """.trimIndent(),
        )

        val issues = validate(source)

        assertEquals(
            2,
            issues.count {
                it.severity == OkfIssueSeverity.ERROR &&
                    it.field == "link" &&
                    it.message == "link target is not a safe normalized bundle path"
            },
        )
        assertTrue(issues.none { it.severity == OkfIssueSeverity.WARNING && it.field == "link" })
    }

    @Test
    fun `rejects Windows drive paths revealed by URI decoding`() {
        val source = document(
            path = "concepts/source.md",
            body = "[Absolute drive](C%3A/secret.md)\n[Drive relative](C%3Asecret.md)",
        )

        val issues = validate(source)

        assertEquals(
            2,
            issues.count {
                it.severity == OkfIssueSeverity.ERROR &&
                    it.path == source.path &&
                    it.field == "link" &&
                    it.message == "link target is not a safe normalized bundle path"
            },
        )
        assertTrue(issues.none { it.severity == OkfIssueSeverity.WARNING && it.field == "link" })
    }

    @Test
    fun `reports links that escape the bundle as errors`() {
        val source = document(
            path = "concepts/source.md",
            body = "[Secret](../../secret.md)",
        )

        val issues = validate(source)

        assertIssue(
            issues,
            OkfIssueSeverity.ERROR,
            path = source.path,
            field = "link",
            message = "link target is not a safe normalized bundle path",
        )
    }

    @Test
    fun `reports percent encoded traversal as an error`() {
        val source = document(
            path = "concepts/source.md",
            body = "[Secret](%2e%2e/%2e%2e/secret.md)",
        )

        val issues = validate(source)

        assertIssue(
            issues,
            OkfIssueSeverity.ERROR,
            path = source.path,
            field = "link",
            message = "link target is not a safe normalized bundle path",
        )
    }

    @Test
    fun `allows external HTTP links only under an exact level two Citations heading`() {
        val body = """
            [Outside](https://outside.example)

            ## citations

            [Wrong case](https://case.example)

            ## Citations

            [HTTP](http://example.com)
            [HTTPS](https://example.com)
        """.trimIndent()

        val issues = validate(document(body = body))

        assertEquals(
            2,
            issues.count {
                it.severity == OkfIssueSeverity.ERROR &&
                    it.field == "link" &&
                    it.message == "external links are allowed only in Citations"
            },
        )
    }

    @Test
    fun `nested Citations headings do not authorize later root links`() {
        val body = """
            > ## Citations
            >
            > Quoted section.

            [Outside](https://outside.example)
        """.trimIndent()

        val issues = validate(document(body = body))

        assertIssue(
            issues,
            OkfIssueSeverity.ERROR,
            field = "link",
            message = "external links are allowed only in Citations",
        )
    }

    @Test
    fun `links inside a Citations heading are not authorized by that heading`() {
        val body = """
            ## [Citations](https://heading.example)

            [Allowed](https://citation.example)
        """.trimIndent()

        val issues = validate(document(body = body))

        assertEquals(
            1,
            issues.count {
                it.severity == OkfIssueSeverity.ERROR &&
                    it.field == "link" &&
                    it.message == "external links are allowed only in Citations"
            },
        )
    }

    @Test
    fun `HTTP links under Citations require an authority`() {
        val body = """
            ## Citations

            [Opaque](http:foo)
            [No authority](https:/path)
        """.trimIndent()

        val issues = validate(document(body = body))

        assertEquals(
            2,
            issues.count {
                it.severity == OkfIssueSeverity.ERROR &&
                    it.field == "link" &&
                    it.message == "external links are allowed only in Citations"
            },
        )
    }

    @Test
    fun `rejects non HTTP URI schemes even under Citations`() {
        val document = document(body = "## Citations\n\n[Mail](mailto:owner@example.com)")

        val issues = validate(document)

        assertIssue(
            issues,
            OkfIssueSeverity.ERROR,
            field = "link",
            message = "external links are allowed only in Citations",
        )
    }

    @Test
    fun `allows a resource URL in frontmatter and preserves unknown fields`() {
        val frontmatter = validFrontmatter().apply {
            this["resource"] = "https://example.com/external-resource"
            this["x-owner"] = "architecture"
        }
        val document = document(frontmatter = frontmatter)

        val issues = validate(document)

        assertTrue(issues.none { it.field == "resource" || it.field == "x-owner" })
        assertEquals("https://example.com/external-resource", document.frontmatter["resource"])
        assertEquals("architecture", document.frontmatter["x-owner"])
    }

    @Test
    fun `index documents validate links only`() {
        val index = OkfDocument(
            path = "index.md",
            frontmatter = linkedMapOf(),
            body = "[Missing](missing.md)",
        )

        val issues = validate(index)

        assertEquals(1, issues.size)
        assertIssue(issues, OkfIssueSeverity.WARNING, path = "index.md", field = "link")
    }

    @Test
    fun `nested index documents validate links only`() {
        val index = OkfDocument(
            path = "architecture/index.md",
            frontmatter = linkedMapOf(),
            body = "[Existing](overview.md)\n[Missing](missing.md)",
        )
        val target = document(
            path = "architecture/overview.md",
            id = "00000000-0000-0000-0000-000000000301",
        )

        val issues = validator.validate(OkfBundle(listOf(index, target)))

        assertEquals(1, issues.size)
        assertIssue(issues, OkfIssueSeverity.WARNING, path = "architecture/index.md", field = "link")
    }

    private fun validate(document: OkfDocument): List<OkfIssue> =
        validator.validate(OkfBundle(listOf(document)))

    private fun document(
        path: String = "concepts/example.md",
        id: Any = "be8f64a8-4bd0-4fd6-9e57-f1a69f25d103",
        type: String = "feature",
        title: String = "Example",
        description: String = "A valid concept description.",
        body: String = "# Example",
        frontmatter: LinkedHashMap<String, Any?> = validFrontmatter(id, type, title, description),
    ) = OkfDocument(path, frontmatter, body)

    private fun validFrontmatter(
        id: Any = "be8f64a8-4bd0-4fd6-9e57-f1a69f25d103",
        type: String = "feature",
        title: String = "Example",
        description: String = "A valid concept description.",
    ) = linkedMapOf<String, Any?>(
        "type" to type,
        "id" to id,
        "title" to title,
        "description" to description,
        "tags" to listOf("product", "specification"),
        "timestamp" to "2026-07-17T14:30:00+02:00",
    )

    private fun assertIssue(
        issues: List<OkfIssue>,
        severity: OkfIssueSeverity,
        path: String = "concepts/example.md",
        field: String? = null,
        message: String? = null,
    ) {
        assertTrue(
            issues.any {
                it.severity == severity &&
                    it.path == path &&
                    (field == null || it.field == field) &&
                    (message == null || it.message == message)
            },
            "Expected $severity issue for $path field=$field message=$message but found $issues",
        )
    }
}

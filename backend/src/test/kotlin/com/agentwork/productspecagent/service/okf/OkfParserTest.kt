package com.agentwork.productspecagent.service.okf

import com.agentwork.productspecagent.domain.okf.OkfDocument
import com.agentwork.productspecagent.domain.okf.OkfParseException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class OkfParserTest {

    private val parser = OkfParser()

    @Test
    fun `parse reads valid frontmatter and markdown body`() {
        val document = parser.parse(
            "concepts/customer.md",
            """
                ---
                type: BigQuery Table
                title: Customer
                tags:
                  - analytics
                  - pii
                ---
                # Schema

                Customer fields.
            """.trimIndent(),
        )

        assertEquals("concepts/customer.md", document.path)
        assertEquals("BigQuery Table", document.frontmatter["type"])
        assertEquals("Customer", document.frontmatter["title"])
        assertEquals(listOf("analytics", "pii"), document.frontmatter["tags"])
        assertEquals("# Schema\n\nCustomer fields.", document.body)
    }

    @Test
    fun `parse tolerates a body-only document`() {
        val body = "# Overview\n\nNo frontmatter is present."

        val document = parser.parse("index.md", body)

        assertTrue(document.frontmatter.isEmpty())
        assertEquals(body, document.body)
    }

    @Test
    fun `parse preserves unknown frontmatter fields and their order`() {
        val document = parser.parse(
            "concept.md",
            "---\ntitle: Example\nx-producer: custom\ntype: Reference\n---\nBody",
        )

        assertEquals(listOf("title", "x-producer", "type"), document.frontmatter.keys.toList())
        assertEquals("custom", document.frontmatter["x-producer"])
    }

    @Test
    fun `parse rejects duplicate YAML keys`() {
        val exception = assertThrows<OkfParseException> {
            parser.parse(
                "duplicate.md",
                "---\ntype: Reference\ntype: Playbook\n---\nBody",
            )
        }

        assertEquals("duplicate.md", exception.documentPath)
    }

    @Test
    fun `parse rejects excessive YAML aliases`() {
        val aliases = (1..21).joinToString("\n") { index -> "alias$index: *shared" }
        val content = "---\nshared: &shared\n  - value\n$aliases\n---\nBody"

        assertThrows<OkfParseException> {
            parser.parse("aliases.md", content)
        }
    }

    @Test
    fun `parse normalizes CRLF input to LF`() {
        val document = parser.parse(
            "windows.md",
            "---\r\ntype: Reference\r\ntitle: Windows\r\n---\r\n# Body\r\n\r\nText\r\n",
        )

        assertEquals(listOf("type", "title"), document.frontmatter.keys.toList())
        assertEquals("# Body\n\nText\n", document.body)
    }

    @Test
    fun `render and parse roundtrip preserves key order unknown fields and body`() {
        val original = OkfDocument(
            path = "concepts/example.md",
            frontmatter = linkedMapOf(
                "type" to "Reference",
                "x-producer" to linkedMapOf("enabled" to true),
                "title" to "Example",
            ),
            body = "# Details\n\nRoundtrip body.\n",
        )

        val rendered = parser.render(original)
        val reparsed = parser.parse(original.path, rendered)

        assertEquals(listOf("type", "x-producer", "title"), reparsed.frontmatter.keys.toList())
        assertEquals(linkedMapOf("enabled" to true), reparsed.frontmatter["x-producer"])
        assertEquals(original.body, reparsed.body)
    }
}

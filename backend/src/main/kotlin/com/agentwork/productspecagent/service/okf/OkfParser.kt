package com.agentwork.productspecagent.service.okf

import com.agentwork.productspecagent.domain.okf.OkfDocument
import com.agentwork.productspecagent.domain.okf.OkfParseException
import org.springframework.stereotype.Service
import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import org.yaml.snakeyaml.representer.Representer

@Service
class OkfParser {

    fun parse(path: String, content: String): OkfDocument {
        val normalized = content.replace("\r\n", "\n").replace('\r', '\n')
        if (!normalized.startsWith("---\n")) {
            return OkfDocument(path, linkedMapOf(), normalized)
        }

        val closingDelimiter = normalized.indexOf("\n---\n", startIndex = 4)
        if (closingDelimiter < 0) {
            throw OkfParseException(path, "unterminated YAML frontmatter")
        }

        val loaded = try {
            createYaml().load<Any?>(normalized.substring(4, closingDelimiter))
        } catch (exception: Exception) {
            val message = exception.message?.takeIf { it.isNotBlank() } ?: "invalid YAML frontmatter"
            throw OkfParseException(path, message, exception)
        }

        if (loaded !is Map<*, *>) {
            throw OkfParseException(path, "YAML frontmatter must be a mapping")
        }

        val frontmatter = linkedMapOf<String, Any?>()
        for ((key, value) in loaded) {
            if (key !is String) {
                throw OkfParseException(path, "YAML frontmatter keys must be strings")
            }
            frontmatter[key] = value
        }

        val body = normalized.substring(closingDelimiter + "\n---\n".length)
        return OkfDocument(path, frontmatter, body)
    }

    fun render(document: OkfDocument): String {
        if (document.frontmatter.isEmpty()) {
            return document.body
        }

        val dumped = createYaml().dump(document.frontmatter)
            .removeSuffix("...\n")
            .trimEnd()
        return "---\n$dumped\n---\n${document.body}"
    }

    private fun createYaml(): Yaml {
        val loaderOptions = LoaderOptions().apply {
            maxAliasesForCollections = 20
            nestingDepthLimit = 40
            codePointLimit = 1_000_000
            isAllowDuplicateKeys = false
            allowRecursiveKeys = false
        }
        val dumperOptions = DumperOptions().apply {
            defaultFlowStyle = DumperOptions.FlowStyle.BLOCK
            isPrettyFlow = true
            indent = 2
            indicatorIndent = 2
            indentWithIndicator = true
            width = 120
        }
        return Yaml(
            SafeConstructor(loaderOptions),
            Representer(dumperOptions),
            dumperOptions,
            loaderOptions,
        )
    }
}

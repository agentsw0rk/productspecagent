package com.agentwork.productspecagent.service

import com.agentwork.productspecagent.domain.FlowStepType
import com.agentwork.productspecagent.domain.AssetBundleManifest
import com.agentwork.productspecagent.domain.AssetBundleScope
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AssetBundleZipExtractorTest {

    private val extractor = AssetBundleZipExtractor()

    // ── Happy-path tests (Task 2) ─────────────────────────────────────────────

    @Test
    fun `extract returns manifest and files for valid ZIP`() {
        val zip = buildZip(
            manifest = sampleManifest(),
            files = mapOf(
                "skills/spring-testing/SKILL.md" to "skill body".toByteArray(),
                "commands/gradle-build.md" to "command body".toByteArray(),
            )
        )

        val result = extractor.extract(zip)

        assertEquals("backend.framework.kotlin-spring", result.manifest.id)
        assertEquals(FlowStepType.BACKEND, result.manifest.step)
        assertEquals(2, result.files.size)
        assertEquals("skill body", result.files["skills/spring-testing/SKILL.md"]?.toString(Charsets.UTF_8))
    }

    @Test
    fun `extract preserves nested file paths`() {
        val zip = buildZip(
            files = mapOf(
                "skills/a/b/c/deep.md" to "x".toByteArray(),
                "agents/agent.md" to "y".toByteArray(),
            )
        )

        val result = extractor.extract(zip)

        assertEquals(setOf("skills/a/b/c/deep.md", "agents/agent.md"), result.files.keys)
    }

    @Test
    fun `extract accepts global manifest without step field value triple`() {
        val zip = buildZip(
            manifest = AssetBundleManifest(
                id = "global.living-sync-reporter",
                scope = AssetBundleScope.GLOBAL,
                version = "1.0.0",
                title = "Living Sync Reporter",
                description = "Always included.",
                createdAt = "2026-05-07T00:00:00Z",
                updatedAt = "2026-05-07T00:00:00Z",
            ),
            files = mapOf("skills/living-sync/SKILL.md" to "skill body".toByteArray()),
        )

        val result = extractor.extract(zip)

        assertEquals(AssetBundleScope.GLOBAL, result.manifest.scope)
        assertEquals("global.living-sync-reporter", result.manifest.id)
        assertNull(result.manifest.step)
        assertNull(result.manifest.field)
        assertNull(result.manifest.value)
    }

    @Test
    fun `extract accepts zip with single enclosing folder`() {
        val manifest = sampleManifest(
            scope = AssetBundleScope.GLOBAL,
            id = "global.living-sync-reporter",
            title = "Living Sync Reporter",
            description = "Always included.",
        )
        val zip = buildZip(
            manifest = null,
            rawExtras = mapOf(
                "living-sync-reporter-bundle/manifest.json" to kotlinx.serialization.json.Json.encodeToString(AssetBundleManifest.serializer(), manifest).toByteArray(),
                "living-sync-reporter-bundle/skills/living-sync-reporter/SKILL.md" to "skill body".toByteArray(),
            ),
        )

        val result = extractor.extract(zip)

        assertEquals("global.living-sync-reporter", result.manifest.id)
        assertEquals(setOf("skills/living-sync-reporter/SKILL.md"), result.files.keys)
    }

    // ── Validation tests (Task 3) ─────────────────────────────────────────────

    @Test
    fun `extract throws MissingManifestException when manifest_json missing`() {
        val zip = buildZip(manifest = null, files = mapOf("skills/x.md" to "x".toByteArray()))
        assertThrows(MissingManifestException::class.java) { extractor.extract(zip) }
    }

    @Test
    fun `extract throws MissingManifestException when manifest_json nested in folder`() {
        val zip = buildZip(
            manifest = null,
            rawExtras = mapOf("skills/manifest.json" to "{}".toByteArray())
        )
        assertThrows(MissingManifestException::class.java) { extractor.extract(zip) }
    }

    @Test
    fun `extract throws InvalidManifestException for malformed JSON`() {
        val zip = buildZip(manifest = null, rawExtras = mapOf("manifest.json" to "not json {".toByteArray()))
        assertThrows(InvalidManifestException::class.java) { extractor.extract(zip) }
    }

    @Test
    fun `extract throws InvalidManifestException for blank required field`() {
        val zip = buildZip(manifest = sampleManifest(title = ""))
        val ex = assertThrows(InvalidManifestException::class.java) { extractor.extract(zip) }
        assertTrue(ex.message!!.contains("title"))
    }

    @Test
    fun `extract throws UnsupportedStepException for IDEA step`() {
        val zip = buildZip(manifest = sampleManifest(step = FlowStepType.IDEA))
        assertThrows(UnsupportedStepException::class.java) { extractor.extract(zip) }
    }

    @Test
    fun `extract throws UnsupportedStepException for PROBLEM step`() {
        val zip = buildZip(manifest = sampleManifest(step = FlowStepType.PROBLEM))
        assertThrows(UnsupportedStepException::class.java) { extractor.extract(zip) }
    }

    @Test
    fun `extract throws ManifestIdMismatchException when id does not match triple`() {
        val zip = buildZip(manifest = sampleManifest(id = "backend.framework.wrong-slug"))
        val ex = assertThrows(ManifestIdMismatchException::class.java) { extractor.extract(zip) }
        assertTrue(ex.message!!.contains("backend.framework.kotlin-spring"))
    }

    @Test
    fun `extract throws IllegalBundleEntryException for top-level file outside allowlist`() {
        val zip = buildZip(
            manifest = sampleManifest(),
            rawExtras = mapOf("README.md" to "x".toByteArray())
        )
        val ex = assertThrows(IllegalBundleEntryException::class.java) { extractor.extract(zip) }
        assertTrue(ex.message!!.contains("README.md"))
    }

    @Test
    fun `extract throws IllegalBundleEntryException for top-level folder outside allowlist`() {
        val zip = buildZip(
            manifest = sampleManifest(),
            rawExtras = mapOf("rules/foo.md" to "x".toByteArray())
        )
        assertThrows(IllegalBundleEntryException::class.java) { extractor.extract(zip) }
    }

    @Test
    fun `extract throws IllegalBundleEntryException for path traversal entry`() {
        val zip = buildZip(
            manifest = sampleManifest(),
            rawExtras = mapOf("skills/../../etc/passwd" to "x".toByteArray())
        )
        assertThrows(IllegalBundleEntryException::class.java) { extractor.extract(zip) }
    }

    @Test
    fun `extract throws IllegalBundleEntryException for absolute path entry`() {
        val zip = buildZip(
            manifest = sampleManifest(),
            rawExtras = mapOf("/etc/passwd" to "x".toByteArray())
        )
        assertThrows(IllegalBundleEntryException::class.java) { extractor.extract(zip) }
    }

    @Test
    fun `extract throws IllegalBundleEntryException for invalid ZIP bytes`() {
        val notAZip = "this is not a zip".toByteArray()
        assertThrows(IllegalBundleEntryException::class.java) { extractor.extract(notAZip) }
    }

    @Test
    fun `extract silently skips DS_Store, __MACOSX, Thumbs_db entries`() {
        val zip = buildZip(
            manifest = sampleManifest(),
            files = mapOf("skills/x/SKILL.md" to "x".toByteArray()),
            rawExtras = mapOf(
                ".DS_Store" to "junk".toByteArray(),
                "__MACOSX/foo" to "junk".toByteArray(),
                "skills/x/.DS_Store" to "junk".toByteArray(),
                "skills/x/scripts/__pycache__/tool.cpython-312.pyc" to "junk".toByteArray(),
                "Thumbs.db" to "junk".toByteArray(),
            )
        )

        val result = extractor.extract(zip)

        assertEquals(1, result.files.size)
        assertEquals(setOf("skills/x/SKILL.md"), result.files.keys)
    }

    @Test
    fun `extract throws BundleTooLargeException for file count over 200`() {
        val files = (1..201).associate { "skills/file-$it.md" to "x".toByteArray() }
        val zip = buildZip(manifest = sampleManifest(), files = files)
        assertThrows(BundleTooLargeException::class.java) { extractor.extract(zip) }
    }

    @Test
    fun `extract throws BundleTooLargeException for single file over 8 MB`() {
        val zip = buildZip(
            manifest = sampleManifest(),
            files = mapOf("skills/big.md" to bytesOfSize(8 * 1024 * 1024 + 1))
        )
        assertThrows(BundleTooLargeException::class.java) { extractor.extract(zip) }
    }

    @Test
    fun `extract throws BundleTooLargeException when total size over 40 MB`() {
        val files = (1..6).associate { "skills/file-$it.md" to bytesOfSize(7 * 1024 * 1024) }
        val zip = buildZip(manifest = sampleManifest(), files = files)
        assertThrows(BundleTooLargeException::class.java) { extractor.extract(zip) }
    }

    @Test
    fun `extract counts non-filtered entries against the limit`() {
        // 200 valid files + a few filtered junk entries should still pass
        val files = (1..200).associate { "skills/file-$it.md" to "x".toByteArray() }
        val zipWithFilters = buildZip(
            manifest = sampleManifest(),
            files = files,
            rawExtras = mapOf(
                ".DS_Store" to "junk".toByteArray(),
                "__MACOSX/foo" to "junk".toByteArray(),
            )
        )
        val result = extractor.extract(zipWithFilters)
        assertEquals(200, result.files.size)
    }
}

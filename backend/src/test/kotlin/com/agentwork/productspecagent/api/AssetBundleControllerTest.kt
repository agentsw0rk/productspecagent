package com.agentwork.productspecagent.api

import com.agentwork.productspecagent.domain.AssetBundleManifest
import com.agentwork.productspecagent.domain.AssetBundleScope
import com.agentwork.productspecagent.domain.FlowStepType
import com.agentwork.productspecagent.storage.ObjectStore
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*

@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser(roles = ["ADMIN"])
class AssetBundleControllerTest {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var objectStore: ObjectStore

    private val json = Json { ignoreUnknownKeys = true }

    @BeforeEach
    fun cleanBundles() {
        objectStore.deletePrefix("asset-bundles/")
    }

    private fun putBundle(id: String, step: FlowStepType, field: String, value: String, files: Map<String, String> = emptyMap()) {
        val m = AssetBundleManifest(
            id = id, step = step, field = field, value = value,
            version = "1.0.0", title = "T", description = "D",
            createdAt = "2026-04-29T12:00:00Z", updatedAt = "2026-04-29T12:00:00Z",
        )
        objectStore.put("asset-bundles/$id/manifest.json", json.encodeToString(m).toByteArray())
        files.forEach { (rel, content) ->
            objectStore.put("asset-bundles/$id/$rel", content.toByteArray())
        }
    }

    private fun putGlobalBundle(id: String, files: Map<String, String> = emptyMap()) {
        val m = AssetBundleManifest(
            id = id,
            scope = AssetBundleScope.GLOBAL,
            version = "1.0.0",
            title = "Global Bundle",
            description = "Always included",
            createdAt = "2026-05-07T00:00:00Z",
            updatedAt = "2026-05-07T00:00:00Z",
        )
        objectStore.put("asset-bundles/$id/manifest.json", json.encodeToString(m).toByteArray())
        files.forEach { (rel, content) ->
            objectStore.put("asset-bundles/$id/$rel", content.toByteArray())
        }
    }

    @Test
    fun `GET asset-bundles returns empty array when none exist`() {
        mockMvc.perform(get("/api/v1/asset-bundles"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(0))
    }

    @Test
    fun `GET asset-bundles lists all bundles with file count`() {
        putBundle("backend.framework.kotlin-spring", FlowStepType.BACKEND, "framework", "Kotlin+Spring",
                  files = mapOf("skills/x/SKILL.md" to "a", "commands/y.md" to "b"))
        putBundle("frontend.framework.stitch", FlowStepType.FRONTEND, "framework", "Stitch",
                  files = mapOf("skills/z/SKILL.md" to "c"))

        mockMvc.perform(get("/api/v1/asset-bundles"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(2))
            .andExpect(jsonPath("$[?(@.id == 'backend.framework.kotlin-spring')].fileCount").value(2))
            .andExpect(jsonPath("$[?(@.id == 'frontend.framework.stitch')].fileCount").value(1))
    }

    @Test
    fun `GET asset-bundle detail returns manifest and files`() {
        putBundle("backend.framework.kotlin-spring", FlowStepType.BACKEND, "framework", "Kotlin+Spring",
                  files = mapOf("skills/x/SKILL.md" to "a"))

        mockMvc.perform(get("/api/v1/asset-bundles/BACKEND/framework/Kotlin+Spring"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.manifest.id").value("backend.framework.kotlin-spring"))
            .andExpect(jsonPath("$.files.length()").value(1))
            .andExpect(jsonPath("$.files[0].relativePath").value("skills/x/SKILL.md"))
            .andExpect(jsonPath("$.files[0].contentType").value("text/markdown"))
    }

    @Test
    fun `GET asset-bundle detail returns 404 for unknown triple`() {
        mockMvc.perform(get("/api/v1/asset-bundles/BACKEND/framework/Nonexistent"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error").value("NOT_FOUND"))
    }

    @Test
    fun `GET asset-bundle detail by id returns global bundle`() {
        putGlobalBundle("global.living-sync-reporter", files = mapOf("skills/x/SKILL.md" to "a"))

        mockMvc.perform(get("/api/v1/asset-bundles/by-id/global.living-sync-reporter"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.manifest.id").value("global.living-sync-reporter"))
            .andExpect(jsonPath("$.manifest.scope").value("GLOBAL"))
            .andExpect(jsonPath("$.manifest.step").doesNotExist())
            .andExpect(jsonPath("$.files[0].relativePath").value("skills/x/SKILL.md"))
    }

    @Test
    fun `DELETE asset-bundle by id deletes global bundle`() {
        putGlobalBundle("global.living-sync-reporter", files = mapOf("skills/x/SKILL.md" to "a"))

        mockMvc.perform(delete("/api/v1/asset-bundles/by-id/global.living-sync-reporter"))
            .andExpect(status().isNoContent)

        mockMvc.perform(get("/api/v1/asset-bundles/by-id/global.living-sync-reporter"))
            .andExpect(status().isNotFound)
    }

    @Test
    fun `GET asset-bundle detail returns 400 for invalid step enum`() {
        mockMvc.perform(get("/api/v1/asset-bundles/INVALID_STEP/framework/Kotlin+Spring"))
            .andExpect(status().isBadRequest)
    }

    private fun zipFor(
        id: String = "backend.framework.kotlin-spring",
        step: FlowStepType = FlowStepType.BACKEND,
        field: String = "framework",
        value: String = "Kotlin+Spring",
        files: Map<String, ByteArray> = mapOf("skills/x.md" to "x".toByteArray()),
    ): ByteArray {
        val manifest = AssetBundleManifest(
            id = id, step = step, field = field, value = value,
            version = "1.0.0", title = "T", description = "D",
            createdAt = "2026-04-29T12:00:00Z", updatedAt = "2026-04-29T12:00:00Z",
        )
        val baos = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(baos).use { zip ->
            zip.putNextEntry(java.util.zip.ZipEntry("manifest.json"))
            zip.write(json.encodeToString(manifest).toByteArray())
            zip.closeEntry()
            files.forEach { (path, bytes) ->
                zip.putNextEntry(java.util.zip.ZipEntry(path))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return baos.toByteArray()
    }

    @Test
    fun `POST asset-bundles 201 with valid ZIP`() {
        val zipBytes = zipFor(files = mapOf("skills/x.md" to "skill".toByteArray(), "commands/y.md" to "cmd".toByteArray()))
        val mockFile = org.springframework.mock.web.MockMultipartFile(
            "file", "bundle.zip", "application/zip", zipBytes
        )

        mockMvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart("/api/v1/asset-bundles")
                .file(mockFile)
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.manifest.id").value("backend.framework.kotlin-spring"))
            .andExpect(jsonPath("$.fileCount").value(2))
    }

    @Test
    fun `POST asset-bundles 400 with malformed manifest`() {
        val baos = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(baos).use { zip ->
            zip.putNextEntry(java.util.zip.ZipEntry("manifest.json"))
            zip.write("not json {".toByteArray())
            zip.closeEntry()
        }
        val mockFile = org.springframework.mock.web.MockMultipartFile("file", "bad.zip", "application/zip", baos.toByteArray())

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart("/api/v1/asset-bundles").file(mockFile))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error").value("INVALID_BUNDLE"))
    }

    @Test
    fun `POST asset-bundles 400 with missing manifest_json`() {
        val baos = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(baos).use { zip ->
            zip.putNextEntry(java.util.zip.ZipEntry("skills/x.md"))
            zip.write("x".toByteArray())
            zip.closeEntry()
        }
        val mockFile = org.springframework.mock.web.MockMultipartFile("file", "no-manifest.zip", "application/zip", baos.toByteArray())

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart("/api/v1/asset-bundles").file(mockFile))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error").value("INVALID_BUNDLE"))
    }

    @Test
    fun `POST asset-bundles 413 for too-large bundle`() {
        // 201 files exceeds the 200-file limit -> BundleTooLargeException -> 413
        val files = (1..201).associate { "skills/file-$it.md" to "x".toByteArray() }
        val zipBytes = zipFor(files = files)
        val mockFile = org.springframework.mock.web.MockMultipartFile("file", "huge.zip", "application/zip", zipBytes)

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart("/api/v1/asset-bundles").file(mockFile))
            .andExpect(status().isPayloadTooLarge)
            .andExpect(jsonPath("$.error").value("BUNDLE_TOO_LARGE"))
    }

    @Test
    fun `POST asset-bundles 400 with manifest id mismatch`() {
        val zipBytes = zipFor(id = "backend.framework.totally-wrong")
        val mockFile = org.springframework.mock.web.MockMultipartFile("file", "bundle.zip", "application/zip", zipBytes)

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart("/api/v1/asset-bundles").file(mockFile))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error").value("INVALID_BUNDLE"))
    }

    @Test
    fun `POST asset-bundles 400 with unsupported step`() {
        val zipBytes = zipFor(id = "idea.framework.kotlin-spring", step = FlowStepType.IDEA)
        val mockFile = org.springframework.mock.web.MockMultipartFile("file", "bundle.zip", "application/zip", zipBytes)

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart("/api/v1/asset-bundles").file(mockFile))
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `POST asset-bundles overwrites existing bundle`() {
        // First upload with files A and B
        mockMvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart("/api/v1/asset-bundles")
                .file(org.springframework.mock.web.MockMultipartFile("file", "v1.zip", "application/zip",
                    zipFor(files = mapOf("skills/a.md" to "a".toByteArray(), "skills/b.md" to "b".toByteArray()))))
        ).andExpect(status().isCreated)

        // Second upload with only file C
        mockMvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart("/api/v1/asset-bundles")
                .file(org.springframework.mock.web.MockMultipartFile("file", "v2.zip", "application/zip",
                    zipFor(files = mapOf("skills/c.md" to "c".toByteArray()))))
        ).andExpect(status().isCreated).andExpect(jsonPath("$.fileCount").value(1))

        // Verify old files are gone via detail endpoint
        mockMvc.perform(get("/api/v1/asset-bundles/BACKEND/framework/Kotlin+Spring"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.files.length()").value(1))
            .andExpect(jsonPath("$.files[0].relativePath").value("skills/c.md"))
    }

    @Test
    fun `DELETE asset-bundles 204 for existing bundle`() {
        mockMvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart("/api/v1/asset-bundles")
                .file(org.springframework.mock.web.MockMultipartFile("file", "v.zip", "application/zip", zipFor()))
        ).andExpect(status().isCreated)

        mockMvc.perform(delete("/api/v1/asset-bundles/BACKEND/framework/Kotlin+Spring"))
            .andExpect(status().isNoContent)

        mockMvc.perform(get("/api/v1/asset-bundles/BACKEND/framework/Kotlin+Spring"))
            .andExpect(status().isNotFound)
    }

    @Test
    fun `DELETE asset-bundles 404 for unknown bundle`() {
        mockMvc.perform(delete("/api/v1/asset-bundles/BACKEND/framework/Nonexistent"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error").value("NOT_FOUND"))
    }

    @Test
    fun `GET asset-bundle file 200 with bytes and correct Content-Type`() {
        mockMvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart("/api/v1/asset-bundles")
                .file(org.springframework.mock.web.MockMultipartFile("file", "v.zip", "application/zip",
                    zipFor(files = mapOf("skills/x/SKILL.md" to "# Hello".toByteArray()))))
        ).andExpect(status().isCreated)

        mockMvc.perform(get("/api/v1/asset-bundles/BACKEND/framework/Kotlin+Spring/files/skills/x/SKILL.md"))
            .andExpect(status().isOk)
            .andExpect(content().contentType("text/markdown;charset=UTF-8"))
            .andExpect(content().string("# Hello"))
    }

    @Test
    fun `GET asset-bundle file 404 when bundle does not exist`() {
        mockMvc.perform(get("/api/v1/asset-bundles/BACKEND/framework/Nonexistent/files/skills/x.md"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error").value("NOT_FOUND"))
    }

    @Test
    fun `GET asset-bundle file 404 for unknown file path`() {
        mockMvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart("/api/v1/asset-bundles")
                .file(org.springframework.mock.web.MockMultipartFile("file", "v.zip", "application/zip",
                    zipFor(files = mapOf("skills/x.md" to "x".toByteArray()))))
        ).andExpect(status().isCreated)

        mockMvc.perform(get("/api/v1/asset-bundles/BACKEND/framework/Kotlin+Spring/files/skills/missing.md"))
            .andExpect(status().isNotFound)
    }

    @Test
    fun `GET asset-bundle file 400 for path traversal attempt`() {
        mockMvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart("/api/v1/asset-bundles")
                .file(org.springframework.mock.web.MockMultipartFile("file", "v.zip", "application/zip", zipFor()))
        ).andExpect(status().isCreated)

        mockMvc.perform(get("/api/v1/asset-bundles/BACKEND/framework/Kotlin+Spring/files/skills/../etc/passwd"))
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `GET asset-bundle file with deeply nested path`() {
        mockMvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart("/api/v1/asset-bundles")
                .file(org.springframework.mock.web.MockMultipartFile("file", "v.zip", "application/zip",
                    zipFor(files = mapOf("skills/a/b/c/d.md" to "deep".toByteArray()))))
        ).andExpect(status().isCreated)

        mockMvc.perform(get("/api/v1/asset-bundles/BACKEND/framework/Kotlin+Spring/files/skills/a/b/c/d.md"))
            .andExpect(status().isOk)
            .andExpect(content().string("deep"))
    }
}

package com.agentwork.productspecagent.api

import com.agentwork.productspecagent.storage.ObjectStore
import com.agentwork.productspecagent.domain.okf.OkfMode
import com.agentwork.productspecagent.domain.okf.OkfProjectState
import com.agentwork.productspecagent.storage.OkfStateStorage
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*

@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser
class FileControllerTest {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var objectStore: ObjectStore
    @Autowired lateinit var okfStateStorage: OkfStateStorage

    private val createdProjectIds = mutableListOf<String>()

    @org.junit.jupiter.api.AfterEach
    fun cleanupProjects() {
        for (pid in createdProjectIds) {
            objectStore.deletePrefix("projects/$pid/")
        }
        createdProjectIds.clear()
    }

    private fun createProject(): String {
        val result = mockMvc.perform(
            post("/api/v1/projects").contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"File Test"}""")
        ).andExpect(status().isCreated()).andReturn()
        val pid = """"id"\s*:\s*"([^"]+)"""".toRegex().find(result.response.contentAsString)!!.groupValues[1]
        createdProjectIds.add(pid)
        return pid
    }

    @Test
    fun `GET files returns file tree`() {
        val pid = createProject()
        mockMvc.perform(get("/api/v1/projects/$pid/files"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$").isArray())
    }

    @Test
    fun `GET files includes docs directory`() {
        val pid = createProject()
        mockMvc.perform(get("/api/v1/projects/$pid/files"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.name == 'docs')]").exists())
            .andExpect(jsonPath("$[?(@.name == 'docs')].isDirectory").value(true))
    }

    @Test
    fun `GET file content returns file with language`() {
        val pid = createProject()
        mockMvc.perform(get("/api/v1/projects/$pid/files/project.json"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value("project.json"))
            .andExpect(jsonPath("$.language").value("json"))
            .andExpect(jsonPath("$.content").isNotEmpty())
            .andExpect(jsonPath("$.lineCount").isNumber())
    }

    @Test
    fun `GET file content for spec file`() {
        val pid = createProject()
        mockMvc.perform(get("/api/v1/projects/$pid/files/docs/features/00-feature-set-overview.md"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.language").value("markdown"))
            .andExpect(jsonPath("$.content").isNotEmpty())
    }

    @Test
    fun `GET files includes files inside subdirectories with full paths`() {
        val pid = createProject()
        // put a known file under docs/uploads/
        objectStore.put("projects/$pid/docs/uploads/notes.md", "# notes".toByteArray())

        mockMvc.perform(get("/api/v1/projects/$pid/files"))
            .andExpect(status().isOk())
            // notes.md must be reachable via docs > uploads
            .andExpect(jsonPath("$[?(@.name == 'docs')].children[?(@.name == 'uploads')].children[?(@.name == 'notes.md')]").exists())
            .andExpect(jsonPath("$[?(@.name == 'docs')].children[?(@.name == 'uploads')].children[?(@.name == 'notes.md')].isDirectory").value(false))
            // and its path must be the FULL path from project root, otherwise the
            // frontend's GET /files/<path> would 404
            .andExpect(jsonPath("$[?(@.name == 'docs')].children[?(@.name == 'uploads')].children[?(@.name == 'notes.md')].path").value("docs/uploads/notes.md"))
            .andExpect(jsonPath("$[?(@.name == 'docs')].children[?(@.name == 'uploads')].path").value("docs/uploads"))
    }

    @Test
    fun `GET files exposes canonical okf bundle for active project`() {
        val pid = createProject()
        okfStateStorage.saveState(OkfProjectState(pid, mode = OkfMode.OKF_ACTIVE))
        objectStore.put("projects/$pid/docs/okf/index.md", "# Open Knowledge".toByteArray())

        mockMvc.perform(get("/api/v1/projects/$pid/files"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.name == 'docs')].children[?(@.name == 'okf')].children[?(@.name == 'index.md')]").exists())

        mockMvc.perform(get("/api/v1/projects/$pid/files/docs/okf/index.md"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content").value("# Open Knowledge"))
    }

    @Test
    fun `GET file content for deeply nested file via the path returned by listing`() {
        val pid = createProject()
        objectStore.put("projects/$pid/docs/features/03-user-interface.md", "# UX".toByteArray())

        // The path returned by GET /files for this file must be the path that
        // GET /files/<path> can resolve. Round-trip test.
        mockMvc.perform(get("/api/v1/projects/$pid/files/docs/features/03-user-interface.md"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.path").value("docs/features/03-user-interface.md"))
            .andExpect(jsonPath("$.language").value("markdown"))
            .andExpect(jsonPath("$.content").value("# UX"))
    }

    @Test
    fun `GET non-existent file returns 404`() {
        val pid = createProject()
        mockMvc.perform(get("/api/v1/projects/$pid/files/nonexistent.txt"))
            .andExpect(status().isNotFound())
    }

    @Test
    fun `GET binary file returns binary=true with empty content`() {
        val pid = createProject()
        // content is irrelevant — detection is by extension
        objectStore.put("projects/$pid/docs/uploads/doc.pdf", byteArrayOf(0x25, 0x50, 0x44, 0x46))

        mockMvc.perform(get("/api/v1/projects/$pid/files/docs/uploads/doc.pdf"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.binary").value(true))
            .andExpect(jsonPath("$.content").value(""))
            .andExpect(jsonPath("$.name").value("doc.pdf"))
    }
}

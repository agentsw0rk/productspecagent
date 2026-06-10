package com.agentwork.productspecagent.service

import com.agentwork.productspecagent.storage.ObjectStore
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.assertThrows
import java.time.Instant

/**
 * In-memory ObjectStore fake for PromptService unit tests.
 * Implements the methods PromptService actually uses; the rest throw NotImplementedError
 * (defensive — tests should never reach them).
 */
private class InMemoryObjectStore : ObjectStore {
    private val data = mutableMapOf<String, ByteArray>()
    var getCallCount = 0
        private set

    override fun put(key: String, bytes: ByteArray, contentType: String?) {
        data[key] = bytes
    }

    override fun get(key: String): ByteArray? {
        getCallCount++
        return data[key]
    }

    override fun exists(key: String): Boolean = data.containsKey(key)

    override fun delete(key: String) {
        data.remove(key)
    }

    override fun deletePrefix(prefix: String): Unit = throw NotImplementedError()
    override fun listKeys(prefix: String): List<String> = throw NotImplementedError()
    override fun listEntries(prefix: String): List<ObjectStore.ObjectEntry> = throw NotImplementedError()
    override fun listCommonPrefixes(prefix: String, delimiter: String): List<String> = throw NotImplementedError()
}

class PromptServiceTest {

    private fun newService(): Pair<PromptService, InMemoryObjectStore> {
        val store = InMemoryObjectStore()
        val service = PromptService(PromptRegistry(), store)
        return service to store
    }

    @Test
    fun `get returns resource default when S3 has no override`() {
        val (service, _) = newService()

        val content = service.get("decision-system")

        assertTrue(content.contains("Produkt-Entscheidungs-Berater"),
            "Expected default to mention 'Produkt-Entscheidungs-Berater', got: $content")
    }

    @Test
    fun `get returns S3 content when override exists`() {
        val (service, store) = newService()
        store.put("prompts/decision-system.md", "OVERRIDDEN".toByteArray(), null)

        val content = service.get("decision-system")

        assertEquals("OVERRIDDEN", content)
    }

    @Test
    fun `get caches the result and does not call S3 twice for the same id`() {
        val (service, store) = newService()
        store.put("prompts/decision-system.md", "CACHED".toByteArray(), null)

        service.get("decision-system")
        service.get("decision-system")
        service.get("decision-system")

        assertEquals(1, store.getCallCount,
            "Expected exactly one S3 get for the same id (caching)")
    }

    @Test
    fun `put writes to S3, updates cache, and validates content`() {
        val (service, store) = newService()
        val newContent = "Du bist der Decision-Agent v2."

        service.put("decision-system", newContent)

        // S3 has the new content
        assertArrayEquals(newContent.toByteArray(), store.get("prompts/decision-system.md"))
        // Subsequent get returns the cached new content
        assertEquals(newContent, service.get("decision-system"))
    }

    @Test
    fun `put rejects empty content with PromptValidationException`() {
        val (service, store) = newService()

        val ex = assertThrows<PromptValidationException> {
            service.put("decision-system", "")
        }
        assertTrue(ex.errors.any { it.contains("nicht leer") })
        assertNull(store.get("prompts/decision-system.md"),
            "S3 should be untouched when validation fails")
    }

    @Test
    fun `put rejects content missing required marker`() {
        val (service, _) = newService()
        // idea-marker-reminder requires [STEP_COMPLETE], [DECISION_NEEDED], [CLARIFICATION_NEEDED]
        val incomplete = "Nur [DECISION_NEEDED] und [CLARIFICATION_NEEDED] erwähnt."

        val ex = assertThrows<PromptValidationException> {
            service.put("idea-marker-reminder", incomplete)
        }
        assertTrue(ex.errors.any { it.contains("[STEP_COMPLETE]") },
            "Expected error to mention missing [STEP_COMPLETE] marker, got: ${ex.errors}")
    }

    @Test
    fun `reset deletes from S3 and evicts cache`() {
        val (service, store) = newService()
        // Prime an override and cache
        store.put("prompts/decision-system.md", "OVERRIDDEN".toByteArray(), null)
        assertEquals("OVERRIDDEN", service.get("decision-system"))

        service.reset("decision-system")

        assertFalse(store.exists("prompts/decision-system.md"))
        // After reset: cache evicted → next get falls back to resource (S3 also empty)
        val afterReset = service.get("decision-system")
        assertTrue(afterReset.contains("Produkt-Entscheidungs-Berater"))
    }

    @Test
    fun `list returns isOverridden=true for prompts that exist in S3`() {
        val (service, store) = newService()
        store.put("prompts/idea-base.md", "override".toByteArray(), null)

        val items = service.list()

        assertEquals(PromptRegistry().definitions.size, items.size)
        assertTrue(items.first { it.id == "idea-base" }.isOverridden)
        assertFalse(items.first { it.id == "decision-system" }.isOverridden)
    }

    @Test
    fun `get throws PromptNotFoundException for unknown id`() {
        val (service, _) = newService()
        assertThrows<PromptNotFoundException> { service.get("nonexistent") }
    }
}

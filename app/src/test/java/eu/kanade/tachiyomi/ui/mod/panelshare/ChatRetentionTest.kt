package eu.kanade.tachiyomi.ui.mod.panelshare

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ChatRetentionTest {
    private class FakeStore(count: Int) : ChatRetention.Store<Int> {
        val messages = (1..count).toMutableSet()
        val panels = (1..count).filter { it % 2 == 0 }.toMutableSet()
        val batches = mutableListOf<Int>()
        var failDelete = false
        var onBoundary: () -> Unit = {}
        override suspend fun newest(limit: Long): List<Int> =
            messages.sortedDescending().take(limit.toInt()).also { onBoundary() }
        override suspend fun olderThan(boundary: Int, limit: Long) =
            messages.filter { it < boundary }.sortedDescending().take(limit.toInt())
        override suspend fun deleteWithPanels(messages: List<Int>) {
            check(!failDelete) { "Permission denied" }
            batches += messages.size
            this.messages.removeAll(messages.toSet())
            panels.removeAll(messages.toSet())
        }
    }

    @Test
    fun `large backlog retains latest two hundred and removes only expired panel blobs`() = runBlocking {
        val store = FakeStore(651)
        ChatRetention.prune(store)
        assertEquals((452..651).toSet(), store.messages)
        assertEquals((452..651).filter { it % 2 == 0 }.toSet(), store.panels)
        assertEquals(List(45) { 10 } + 1, store.batches)
    }

    @Test
    fun `fewer than two hundred messages are untouched`() = runBlocking {
        val store = FakeStore(150)
        ChatRetention.prune(store)
        assertEquals(150, store.messages.size)
        assertEquals(emptyList<Int>(), store.batches)
    }

    @Test
    fun `failed atomic cleanup leaves both messages and panels available for retry`() {
        val store = FakeStore(201).apply { failDelete = true }
        assertThrows(IllegalStateException::class.java) { runBlocking { ChatRetention.prune(store) } }
        assertEquals(201, store.messages.size)
        assertEquals(100, store.panels.size)
        store.failDelete = false
        runBlocking { ChatRetention.prune(store) }
        assertEquals((2..201).toSet(), store.messages)
    }

    @Test
    fun `send during cleanup is preserved and next pass reaches two hundred`() = runBlocking {
        val store = FakeStore(201)
        store.onBoundary = { store.messages += 202 }
        ChatRetention.prune(store)
        assertEquals((2..202).toSet(), store.messages)
        store.onBoundary = {}
        ChatRetention.prune(store)
        assertEquals((3..202).toSet(), store.messages)
    }
}

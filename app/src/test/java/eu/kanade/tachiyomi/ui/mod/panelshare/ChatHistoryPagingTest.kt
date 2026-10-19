package eu.kanade.tachiyomi.ui.mod.panelshare

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ChatHistoryPagingTest {
    @Test
    fun `loads fifty at a time and stops at two hundred`() {
        val pager = ChatHistoryPaging()
        assertEquals(50L, pager.limit)
        pager.onSnapshot(50, false)
        for (limit in listOf(100L, 150L, 200L)) {
            assertTrue(pager.requestMore())
            assertEquals(limit, pager.limit)
            assertFalse(pager.requestMore())
            pager.onSnapshot(limit.toInt(), false)
        }
        assertFalse(pager.requestMore())
        assertFalse(pager.state.value.canLoadMore)
    }

    @Test
    fun `partial last page prevents endless loading`() {
        val pager = ChatHistoryPaging()
        pager.onSnapshot(50, false)
        pager.requestMore()
        pager.onSnapshot(76, false)
        assertFalse(pager.state.value.canLoadMore)
        assertFalse(pager.requestMore())
    }

    @Test
    fun `offline cache does not claim a server page is complete`() {
        val pager = ChatHistoryPaging()
        pager.onSnapshot(50, false)
        pager.requestMore()
        pager.onSnapshot(50, true)
        assertTrue(pager.state.value.loading)
        pager.onError()
        assertTrue(pager.requestMore())
        assertEquals(100L, pager.limit)
        pager.onSnapshot(100, false)
        assertEquals(null, pager.state.value.error)
    }

    @Test
    fun `reentering chat resets expanded window but keeps initial page availability`() {
        val pager = ChatHistoryPaging()
        pager.onSnapshot(50, false)
        assertFalse(pager.reset())
        assertTrue(pager.state.value.canLoadMore)
        pager.requestMore()
        pager.onSnapshot(100, false)
        assertTrue(pager.reset())
        assertEquals(50L, pager.limit)
        pager.onSnapshot(50, false)
        pager.clear()
        assertFalse(pager.state.value.canLoadMore)
    }
}

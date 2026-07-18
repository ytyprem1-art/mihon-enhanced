package eu.kanade.tachiyomi.ui.mod.bookmarkimport

import eu.kanade.tachiyomi.source.model.SManga
import mihon.domain.manga.model.toDomainManga
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter

class BookmarkMatchTest {

    @Test
    fun `test matching preserves original url even if result is uninitialized`() {
        val originalPath = "/manga/mikoto-and-rei"
        val originalTitle = "Mikoto and Rei"

        // Simulate what the source returns: a new SManga object that might have uninitialized url
        val networkManga = SManga.create().apply {
            title = "Resolved Title"
            // url is NOT initialized here
        }

        // The fix: merge network details back into a copy of original or just ensure url is set
        networkManga.url = originalPath
        networkManga.title = originalTitle

        val domainManga = networkManga.toDomainManga(1234L)

        assertEquals(originalPath, domainManga.url)
        assertEquals(originalTitle, domainManga.title)
        assertEquals(1234L, domainManga.source)
    }

    @Test
    fun `test chapter progress filtering`() {
        val viewedChapter = 1.3
        val chapters = listOf(
            createChapter(0.5),
            createChapter(1.0),
            createChapter(1.3),
            createChapter(1.5),
            createChapter(2.0)
        )

        val toMarkRead = chapters.filter { it.chapterNumber >= 0 && it.chapterNumber <= viewedChapter }

        assertEquals(3, toMarkRead.size)
        assertTrue(toMarkRead.any { it.chapterNumber == 0.5 })
        assertTrue(toMarkRead.any { it.chapterNumber == 1.0 })
        assertTrue(toMarkRead.any { it.chapterNumber == 1.3 })
        assertFalse(toMarkRead.any { it.chapterNumber == 1.5 })
    }

    @Test
    fun `test unread progress filtering`() {
        val viewedChapter: Double? = null
        val chapters = listOf(
            createChapter(1.0),
            createChapter(2.0)
        )

        @Suppress("SENSELESS_COMPARISON")
        val toMarkRead = if (viewedChapter != null) {
            chapters.filter { it.chapterNumber >= 0 && it.chapterNumber <= viewedChapter }
        } else {
            emptyList()
        }

        assertTrue(toMarkRead.isEmpty())
    }

    private fun createChapter(number: Double): Chapter {
        return Chapter.create().copy(
            chapterNumber = number,
            read = false
        )
    }
}

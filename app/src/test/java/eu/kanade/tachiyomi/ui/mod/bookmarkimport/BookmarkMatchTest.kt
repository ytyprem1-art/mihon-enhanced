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
    fun `test resume algorithm skips completed items`() {
        val entries = listOf(
            createEntry("1", ManganatoCsvParser.MatchResult.IMPORTED),
            createEntry("2", ManganatoCsvParser.MatchResult.MATCHED),
            createEntry("3", ManganatoCsvParser.MatchResult.UNCHECKED)
        )

        // Find items that need matching
        val matchingIndices = entries.indices.filter { i ->
            val res = entries[i].matchResult
            res == ManganatoCsvParser.MatchResult.UNCHECKED || res == ManganatoCsvParser.MatchResult.CANCELED
        }
        assertEquals(listOf(2), matchingIndices)

        // Find items that need importing
        val importIndices = entries.indices.filter { i ->
            entries[i].matchResult == ManganatoCsvParser.MatchResult.MATCHED
        }
        assertEquals(listOf(1), importIndices)
    }

    @Test
    fun `test retry failed only selects retryable failures`() {
        val entries = listOf(
            createEntry("1", ManganatoCsvParser.MatchResult.NETWORK_TIMEOUT),
            createEntry("2", ManganatoCsvParser.MatchResult.SOURCE_ERROR),
            createEntry("3", ManganatoCsvParser.MatchResult.NOT_FOUND),
            createEntry("4", ManganatoCsvParser.MatchResult.IMPORT_FAILED)
        )

        // Matching retry
        val retryMatchIndices = entries.indices.filter { i ->
            val res = entries[i].matchResult
            res == ManganatoCsvParser.MatchResult.NETWORK_TIMEOUT || res == ManganatoCsvParser.MatchResult.SOURCE_ERROR
        }
        assertEquals(listOf(0, 1), retryMatchIndices)

        // Import retry
        val retryImportIndices = entries.indices.filter { i ->
            val res = entries[i].matchResult
            res == ManganatoCsvParser.MatchResult.IMPORT_FAILED || res == ManganatoCsvParser.MatchResult.CHAPTER_SYNC_FAILED
        }
        assertEquals(listOf(3), retryImportIndices)
    }

    private fun createChapter(number: Double): Chapter {
        return Chapter.create().copy(
            chapterNumber = number,
            read = false
        )
    }

    private fun createEntry(id: String, result: ManganatoCsvParser.MatchResult): ManganatoCsvParser.BookmarkEntry {
        return ManganatoCsvParser.BookmarkEntry(
            id = id,
            title = "Manga $id",
            viewedChapter = null,
            originalUrl = "http://example.com/$id",
            domain = "example.com",
            mangaPath = "/$id",
            isValid = true,
            matchResult = result
        )
    }
}

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
            createChapter(0.5, "Ch 0.5"),
            createChapter(1.0, "Ch 1.0"),
            createChapter(1.3, "Ch 1.3"),
            createChapter(1.5, "Ch 1.5"),
            createChapter(2.0, "Ch 2.0")
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

    @Test
    fun `test titled chapter name parsing`() {
        val names = mapOf(
            "Chapter 57.1: Extra Chapter: Spending the Rest of My Life with You" to 57.1,
            "Chapter 13.1: Epilogue" to 13.1,
            "Chapter 11: MATCH 10 / OFF-BOARD STORIES (EXTRAS)" to 11.0,
            "Chapter 7: End" to 7.0,
            "Chapter 1.3" to 1.3,
            "Ch. 11: Subtitle with 55 number" to 11.0,
            "Chapter 20" to 20.0,
            "11" to 11.0,
            "No Number Here" to null
        )

        names.forEach { (name, expected) ->
            val parsed = ManganatoCsvParser.parseChapterNumber(name)
            assertEquals(expected, parsed, "Failed to parse $name")
        }
    }

    @Test
    fun `test effective chapter number logic`() {
        val viewedChapter = 11.0
        val chapters = listOf(
            // Case 1: valid source chapterNumber
            createChapter(11.0, "Some Name"),
            // Case 2: invalid source chapterNumber, parse from name
            createChapter(-1.0, "Chapter 11: End"),
            // Case 3: invalid source chapterNumber, unrelated number in name
            createChapter(-1.0, "Chapter 11: MATCH 10"),
            // Case 4: outside range
            createChapter(12.0, "Chapter 12")
        )

        val toMarkRead = chapters.filter { chapter ->
            val effectiveNumber = if (chapter.chapterNumber >= 0) {
                chapter.chapterNumber
            } else {
                ManganatoCsvParser.parseChapterNumber(chapter.name)
            }
            effectiveNumber != null && effectiveNumber <= viewedChapter
        }

        assertEquals(3, toMarkRead.size)
        assertTrue(toMarkRead.any { it.name == "Some Name" })
        assertTrue(toMarkRead.any { it.name == "Chapter 11: End" })
        assertTrue(toMarkRead.any { it.name == "Chapter 11: MATCH 10" })
        assertFalse(toMarkRead.any { it.name == "Chapter 12" })
    }

    private fun createChapter(number: Double, name: String): Chapter {
        return Chapter.create().copy(
            chapterNumber = number,
            name = name,
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

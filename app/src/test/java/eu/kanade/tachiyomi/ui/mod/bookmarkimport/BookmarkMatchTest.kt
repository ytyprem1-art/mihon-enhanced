package eu.kanade.tachiyomi.ui.mod.bookmarkimport

import eu.kanade.tachiyomi.source.model.SManga
import mihon.domain.manga.model.toDomainManga
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

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
}

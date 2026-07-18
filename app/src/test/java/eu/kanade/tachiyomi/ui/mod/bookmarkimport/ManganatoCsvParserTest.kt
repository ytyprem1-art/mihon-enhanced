package eu.kanade.tachiyomi.ui.mod.bookmarkimport

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream

class ManganatoCsvParserTest {

    @Test
    fun `test valid csv with various viewed formats`() {
        val csv = """
            ID,Title,Viewed,URL
            1,Mikoto and Rei,Chapter 1.3,https://www.manganato.gg/manga-ab123456
            2,Solo Leveling,None,https://manganato.gg/manga-cd789012
            3,One Piece,Ch. 66.1,https://www.natomanga.com/manga-ef345678
            4,Bleach,66,https://nelomanga.com/manga-gh901234
        """.trimIndent()

        val entries = ManganatoCsvParser.parse(ByteArrayInputStream(csv.toByteArray()))

        assertEquals(4, entries.size)

        // Mikoto and Rei - Chapter 1.3
        with(entries[0]) {
            assertEquals("Mikoto and Rei", title)
            assertEquals(1.3, viewedChapter!!, 0.0)
            assertEquals("manganato.gg", domain)
            assertEquals("/manga-ab123456", mangaPath)
            assertTrue(isValid)
        }

        // Solo Leveling - None
        with(entries[1]) {
            assertEquals("Solo Leveling", title)
            assertNull(viewedChapter)
            assertEquals("manganato.gg", domain)
            assertEquals("/manga-cd789012", mangaPath)
            assertTrue(isValid)
        }

        // One Piece - Ch. 66.1
        with(entries[2]) {
            assertEquals("One Piece", title)
            assertEquals(66.1, viewedChapter!!, 0.0)
            assertEquals("natomanga.com", domain)
            assertTrue(isValid)
        }

        // Bleach - 66
        with(entries[3]) {
            assertEquals("Bleach", title)
            assertEquals(66.0, viewedChapter!!, 0.0)
            assertEquals("nelomanga.com", domain)
            assertTrue(isValid)
        }
    }

    @Test
    fun `test quoted title with commas`() {
        val csv = """
            ID,Title,Viewed,URL
            5,"Title, With, Commas",Ch. 1,https://manganato.gg/manga-ij567890
        """.trimIndent()

        val entries = ManganatoCsvParser.parse(ByteArrayInputStream(csv.toByteArray()))

        assertEquals(1, entries.size)
        assertEquals("Title, With, Commas", entries[0].title)
        assertTrue(entries[0].isValid)
    }

    @Test
    fun `test unsupported domain`() {
        val csv = """
            ID,Title,Viewed,URL
            6,Fake Manga,None,https://example.com/manga-fake
        """.trimIndent()

        val entries = ManganatoCsvParser.parse(ByteArrayInputStream(csv.toByteArray()))

        assertEquals(1, entries.size)
        assertFalse(entries[0].isValid)
        assertTrue(entries[0].validationError!!.contains("Unsupported domain"))
    }

    @Test
    fun `test escaped quotes in title`() {
        val csv = """
            ID,Title,Viewed,URL
            7,"Manga ""With"" Quotes",None,https://manganato.gg/manga-kq112233
        """.trimIndent()

        val entries = ManganatoCsvParser.parse(ByteArrayInputStream(csv.toByteArray()))

        assertEquals(1, entries.size)
        assertEquals("Manga \"With\" Quotes", entries[0].title)
        assertTrue(entries[0].isValid)
    }

    @Test
    fun `test manganato gg with manga slash path`() {
        val csv = """
            ID,Title,Viewed,URL
            8,Mikoto and Re,Chapter 1.3,https://www.manganato.gg/manga/mikoto-and-rei
        """.trimIndent()

        val entries = ManganatoCsvParser.parse(ByteArrayInputStream(csv.toByteArray()))

        assertEquals(1, entries.size)
        with(entries[0]) {
            assertEquals("manganato.gg", domain)
            assertEquals("/manga/mikoto-and-rei", mangaPath)
            assertTrue(isValid)
        }
    }
}

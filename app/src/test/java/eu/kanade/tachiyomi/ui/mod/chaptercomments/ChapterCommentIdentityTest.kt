package eu.kanade.tachiyomi.ui.mod.chaptercomments

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore

class ChapterCommentIdentityTest {
    private val id = "9e641d9c-a91c-459b-a8b6-1d6627b974fa"

    @Test fun `installed Shinigami bare UUID becomes chapter URL instead of invalid hostname`() {
        val base = "https://11.shinigami.asia"
        assertEquals("$base/chapter/$id", normalizeCommentChapterUrl(base, id, base + id))
        assertEquals("$base/chapter/$id", normalizeCommentChapterUrl(base, "/chapter/$id", "$base/chapter/$id"))
        assertEquals(
            "$base/chapter/$id",
            normalizeCommentChapterUrl(
                base,
                "https://08.shinigami.asia/chapter/$id",
                "https://08.shinigami.asia/chapter/$id",
            ),
        )
        assertThrows(IllegalArgumentException::class.java) {
            normalizeCommentChapterUrl(
                base,
                "not-an-id",
                base + "not-an-id",
            )
        }
    }

    @Test fun `other sources retain their own chapter URL`() {
        val url = "https://09.ikiru.wtf/manga/hokiku/chapter-22"
        assertEquals(url, normalizeCommentChapterUrl("https://09.ikiru.wtf", "/manga/hokiku/chapter-22", url))
    }

    @Test fun `reaction selection survives reopening and explicit removal stays removed`() {
        val saved = InMemoryPreferenceStore.InMemoryPreference("reactions", "{}", "{}")
        val legacy = InMemoryPreferenceStore.InMemoryPreference<Set<String>>("legacy", setOf("chapter31"), emptySet())
        val preferences = object : tachiyomi.core.common.preference.PreferenceStore by InMemoryPreferenceStore() {
            override fun getString(key: String, defaultValue: String) = saved
            override fun getStringSet(key: String, defaultValue: Set<String>) = legacy
        }
        val choices = listOf(ChapterReactionOption("like", "👍", "👍"), ChapterReactionOption("love", "❤️", "❤️"))
        val store = ChapterReactionSelections(preferences)
        assertEquals("like", store.get("chapter31", choices))
        store.set("chapter31", "love")
        assertEquals("love", ChapterReactionSelections(preferences).get("chapter31", choices))
        store.set("chapter31", null)
        assertNull(ChapterReactionSelections(preferences).get("chapter31", choices))
        assertNull(store.get("chapter32", choices))
    }
}

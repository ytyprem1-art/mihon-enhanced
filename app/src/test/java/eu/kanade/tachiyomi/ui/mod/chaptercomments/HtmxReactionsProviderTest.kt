package eu.kanade.tachiyomi.ui.mod.chaptercomments

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HtmxReactionsProviderTest {
    @Test fun `htmx error headers reject successful HTTP status without mistaking success or user text for errors`() {
        assertEquals("Bad nonce", htmxReactionError("""{"raiseError":{"value":"<b>Bad nonce</b>"}}"""))
        assertEquals("Login required", htmxReactionError("""{"raiseError":"Login required"}"""))
        assertNotNull(htmxReactionError("refresh, raiseError"))
        assertNull(htmxReactionError("""{"raiseSuccess":"Thanks", "message":"raiseError"}"""))
        assertNull(htmxReactionError(null))
    }
    private val endpoint = "https://template.example/wp-admin/admin-ajax.php"
    private val target = CommentTarget("https://template.example/chapter/31", "31", "Shared template")
    private val chapter = """<div hx-get="$endpoint?chapter_id=31&amp;action=get_comments"></div>"""

    private fun response(selected: String? = null, count: Int = 10): String = """
        <div id="full-comments"><div id="response-data">
        <div hx-get="$endpoint?type=reaction_form&amp;action=get_nonce"></div>
        ${listOf("1" to "Upvote", "3" to "Love").joinToString("") { (id, label) ->
        """
        <div data-selected="${selected == id}" hx-vals='{"id":31,"reaction":$id}' hx-post="$endpoint?action=set_my_reaction">
        <img alt="$label" src="https://template.example/$id.webp"><span>$count</span></div>"""
    }}
        </div><div id="comment-area"></div></div>
    """.trimIndent()

    @Test fun `generic template prepares clickable reactions but only user write fetches nonce and posts`() = runTest {
        val gets = mutableListOf<String>()
        val writes = mutableListOf<Map<String, String>>()
        var enabled = true
        val transport = object : CommentsTransport {
            override suspend fun get(url: String, referer: String?): String {
                gets += url
                return if ("get_nonce" in url) """<input name="reaction_nonce" value="public1234">""" else response()
            }
            override suspend fun postReactionForm(url: String, values: Map<String, String>, referer: String): String {
                assertEquals("$endpoint?action=set_my_reaction", url)
                assertEquals(target.url, referer)
                writes += values
                return response(if (enabled) "3" else null, if (enabled) 11 else 10)
            }
        }
        val provider = KiryuuCommentsProvider(target, transport, chapter)
        assertTrue(gets.isEmpty())
        assertTrue(provider.prepare() is ChapterReactionsProvider)
        assertEquals(listOf("1", "3"), provider.reactionOptions.map { it.key })
        provider.comments(CommentSort.POPULAR, 1)
        provider.reactions()
        assertTrue(writes.isEmpty())
        assertFalse(gets.any { "get_nonce" in it })
        provider.setChapterReaction(null, "3")
        assertEquals(mapOf("id" to "31", "reaction" to "3", "reaction_nonce" to "public1234"), writes.single())
        assertEquals(11, provider.reactions().last().count)
        enabled = false
        provider.setChapterReaction("3", null)
        assertEquals("3", writes.last()["reaction"])
        assertEquals(10, provider.reactions().last().count)
        assertEquals(2, gets.count { "get_nonce" in it })
    }

    @Test fun `failed server acknowledgement never pretends the requested reaction was accepted`() = runTest {
        var posts = 0
        val transport = object : CommentsTransport {
            override suspend fun get(url: String, referer: String?) = if ("get_nonce" in
                url
            ) {
                """<input name="reaction_nonce" value="public1234">"""
            } else {
                response()
            }
            override suspend fun postReactionForm(
                url: String,
                values: Map<String, String>,
                referer: String,
            ): String {
                posts++
                return "-1"
            }
        }
        val provider = KiryuuCommentsProvider(target, transport, chapter)
        assertTrue(runCatching { provider.setChapterReaction(null, "3") }.isFailure)
        assertEquals(1, posts)
        assertEquals(10, provider.reactions().last().count)
    }

    @Test fun `anonymous all unselected response confirms changed counters without a false failure`() = runTest {
        var count = 10
        val transport = object : CommentsTransport {
            override suspend fun get(url: String, referer: String?) = if ("get_nonce" in
                url
            ) {
                """<input name="reaction_nonce" value="public1234">"""
            } else {
                response()
            }
            override suspend fun postReactionForm(url: String, values: Map<String, String>, referer: String): String {
                count = if (count == 10) 11 else 10
                return response(count = count)
            }
        }
        val provider = KiryuuCommentsProvider(target, transport, chapter)
        provider.setChapterReaction(null, "1")
        assertEquals(11, provider.reactions().first().count)
        provider.setChapterReaction("1", null)
        assertEquals(10, provider.reactions().first().count)
    }

    @Test fun `unselected response with unchanged counters is not a successful add`() = runTest {
        val transport = object : CommentsTransport {
            override suspend fun get(url: String, referer: String?) = if ("get_nonce" in
                url
            ) {
                """<input name="reaction_nonce" value="public1234">"""
            } else {
                response()
            }
            override suspend fun postReactionForm(
                url: String,
                values: Map<String, String>,
                referer: String,
            ) = response()
        }
        val provider = KiryuuCommentsProvider(target, transport, chapter)
        assertTrue(
            runCatching {
                provider.setChapterReaction(null, "1")
            }.exceptionOrNull() is ChapterReactionUpdateException,
        )
        assertEquals(10, provider.reactions().first().count)
    }

    @Test fun `foreign reaction actions and nonce forms remain read only`() = runTest {
        val transport = object : CommentsTransport {
            override suspend fun get(
                url: String,
                referer: String?,
            ) = response().replace(
                "$endpoint?action=set_my_reaction",
                "https://other.example/wp-admin/admin-ajax.php?action=set_my_reaction",
            )
        }
        val provider = KiryuuCommentsProvider(target, transport, chapter)
        assertFalse(provider.prepare() is ChapterReactionsProvider)
        assertTrue(runCatching { provider.setChapterReaction(null, "3") }.isFailure)
    }
}

package eu.kanade.tachiyomi.ui.mod.chaptercomments

import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class KiryuuCommentsProviderTest {
    private val target = CommentTarget("https://template.example/chapter/8", "8", "A shared template")
    private val endpoint = "https://template.example/wp-admin/admin-ajax.php"
    private val chapter = """<div hx-get="$endpoint?chapter_id=814435&amp;utm_cid=0&amp;action=get_comments"></div>"""

    @Test
    fun `template reads share initial load and parse nested replies and reactions without executing actions`() = runTest {
        val fake = FakeTransport { fullComments() }
        val provider = KiryuuCommentsProvider(target, fake, chapter)
        assertTrue(fake.urls.isEmpty())
        val page = async { provider.comments(CommentSort.POPULAR, 1) }
        val reactions = async { provider.reactions() }
        val root = page.await().comments.single()
        assertEquals("Reader", root.author)
        assertEquals("A public comment", root.content)
        assertEquals(7, root.upvotes)
        assertEquals(2, root.downvotes)
        assertEquals("1 day ago", root.createdAt)
        assertEquals(1, root.replyCount)
        assertEquals("334804", root.replies.single().replyTo)
        assertEquals("334821", root.replies.single().replies.single().replyTo)
        assertEquals(listOf(ChapterReaction("Upvote", 11)), reactions.await())
        assertEquals(1, fake.urls.size)
        assertEquals(root.replies, provider.replies(root))
        assertTrue(page.await().hasMore)
        assertTrue(fake.urls.all { "action=get_comments" in it })
    }

    @Test
    fun `sort and more follow advertised GET URLs and terminal page stops`() = runTest {
        val fake = FakeTransport { url ->
            when {
                "action=get_comments_order_page" in url -> comment("336371", "Last page")
                "action=get_comments_order" in url -> "<div id='comment-area'>${comment("335327", "Newest")}${more("newest")}</div>"
                else -> fullComments()
            }
        }
        val provider = KiryuuCommentsProvider(target, fake, chapter)
        val second = provider.comments(CommentSort.NEWEST, 2)
        assertEquals("336371", second.comments.single().id)
        assertFalse(second.hasMore)
        assertTrue(fake.urls.any { "order=newest" in it && "action=get_comments_order_page" in it })
        val requests = fake.urls.size
        assertTrue(provider.comments(CommentSort.NEWEST, 3).comments.isEmpty())
        assertEquals(requests, fake.urls.size)
    }

    @Test
    fun `untrusted pagination actions and foreign hosts are not requested`() = runTest {
        val fake = FakeTransport {
            fullComments().replace(
                "$endpoint?order=best&amp;id=814435&amp;page=2&amp;action=get_comments_order_page",
                "https://other.example/wp-admin/admin-ajax.php?page=2&amp;action=get_comments_order_page",
            )
        }
        val provider = KiryuuCommentsProvider(target, fake, chapter)
        var rejected = false
        try {
            provider.comments(CommentSort.POPULAR, 2)
        } catch (_: IllegalArgumentException) {
            rejected = true
        }
        assertTrue(rejected)
        assertEquals(1, fake.urls.size)
    }

    private fun comment(id: String, text: String, replies: String = ""): String = """
        <div id="comment-$id"><img class="avatar" src="https://template.example/avatar.webp">
        <a href="https://template.example/profile/reader/">Reader</a><span class="text-gray-400 text-xs">1 day ago</span>
        <div id="content-$id"><p>$text</p></div>
        <div id="like-dislike-$id"><button data-ltype="like" hx-post="$endpoint?action=like_comment"><span>icon</span><span>7</span></button>
        <button data-ltype="dislike" hx-post="$endpoint?action=dislike_comment"><span>icon</span><span>2</span></button></div>
        <div id="reply-section-$id">$replies</div></div>
    """.trimIndent()

    private fun more(order: String): String = """<button hx-get="$endpoint?order=$order&amp;id=814435&amp;page=2&amp;action=get_comments_order_page">Load more comments</button>"""

    private fun fullComments(): String = """
        <div id="full-comments"><div id="response-data"><div hx-post="$endpoint?action=set_my_reaction"><img alt="Upvote"><span>11</span><span>Upvote</span></div></div>
        <div id="comment-area"><span hx-get="$endpoint?order=newest&amp;id=814435&amp;action=get_comments_order">Newest</span>
        <div id="comments-section">${comment("334804", "A public comment", comment("334821", "A reply", comment("335314", "A nested reply")))}</div>${more("best")}</div></div>
    """.trimIndent()

    private inner class FakeTransport(val response: (String) -> String) : CommentsTransport {
        val urls = mutableListOf<String>()
        override suspend fun get(url: String, referer: String?): String {
            urls += url
            assertEquals(target.url, referer)
            yield()
            return response(url)
        }
    }
}

package eu.kanade.tachiyomi.ui.mod.chaptercomments

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class HtmlCommentsProviderTest {
    private val target = CommentTarget("https://template.example/webtoon/sample/chapter-25", "25", "Template")
    private val noNetwork = object : CommentsTransport {
        override suspend fun get(url: String, referer: String?): String = error("Unexpected request")
        override suspend fun write(method: String, url: String, json: String, referer: String?): String = error("Unexpected write")
    }

    @Test fun `verified empty standalone server list is supported without making requests`() = runTest {
        val provider = HtmlCommentsProvider(target, noNetwork, shell(""))
        val page = provider.comments(CommentSort.OLDEST, 1)
        assertTrue(page.comments.isEmpty())
        assertFalse(page.hasMore)
        assertEquals(0, page.total)
        assertTrue(provider.reactions().isEmpty())
    }

    @Test fun `standalone article template preserves own content nested replies and image URLs as data`() = runTest {
        val nested = article("3", "Nested reader", "Nested reply")
        val reply = article("2", "Reply reader", "Reply body", nested)
        val root = article("1", "Root reader", "<p>Root <b>body</b><img src='/comments/example.png'></p>", reply)
        val provider = HtmlCommentsProvider(target, noNetwork, shell(root + article("4", "Last reader", "Last body")))
        val page = provider.comments(CommentSort.OLDEST, 1)
        assertEquals(listOf("div-comment-1", "div-comment-4"), page.comments.map { it.id })
        val first = page.comments.first()
        assertEquals("Root reader", first.author)
        assertTrue(first.content.contains("<b>body</b>"))
        assertFalse(first.content.contains("Reply body"))
        assertEquals(listOf("https://template.example/comments/example.png"), first.media)
        assertEquals("https://template.example/images/avatar.png", first.avatar)
        assertEquals("div-comment-1", first.replies.single().replyTo)
        assertEquals("Reply reader", first.replies.single().author)
        assertEquals("div-comment-2", first.replies.single().replies.single().replyTo)
        assertTrue(provider.comments(CommentSort.OLDEST, 2).comments.isEmpty())
    }

    @Test fun `standard WordPress li roots and children remain supported`() = runTest {
        val html = """<div id='comments'><ol class='comment-list'><li class='comment' id='comment-1'>
            <article id='div-comment-1' class='comment-body'><span class='fn'>Root</span><div class='comment-metadata'><time datetime='2026-10-20T01:00:00Z'>Today</time></div><div class='comment-content'><p>Root text</p></div></article>
            <ol class='children'><li class='comment' id='comment-2'><span class='fn'>Reply</span><div class='comment-content'>Reply text</div></li></ol>
            </li></ol></div>"""
        val page = HtmlCommentsProvider(target, noNetwork, html).comments(CommentSort.OLDEST, 1)
        val root = page.comments.single()
        assertEquals("Root", root.author)
        assertEquals("2026-10-20T01:00:00Z", root.createdAt)
        assertFalse(root.content.contains("Reply text"))
        assertEquals("comment-1", root.replies.single().replyTo)
        assertEquals("Reply", root.replies.single().author)
    }

    @Test fun `arbitrary comments div remains unsupported and advertised pagination is not silently discarded`() = runTest {
        val unknown = HtmlCommentsProvider(target, noNetwork, "<div id='comments' class='comment-list'></div>")
        assertTrue(runCatching { unknown.comments(CommentSort.POPULAR, 1) }.isFailure)
        val paginated = HtmlCommentsProvider(target, noNetwork, "<ol class='comment-list'></ol><div class='comment-navigation'><span class='nav-next'><a href='?cpage=2'>Next</a></span></div>")
        assertTrue(runCatching { paginated.comments(CommentSort.POPULAR, 1) }.isFailure)
    }

    // Minimal server-list/form markers and article structure from the public inline writer.
    private fun shell(comments: String) = """<div class='comment-box'><form id='mainForm' class='commentForm'><input name='blog_id' value='c241132'><input name='parent_id' value='0'></form><div id='comments' class='comment-list'>$comments</div></div>"""
    private fun article(id: String, author: String, body: String, replies: String = "") = """<article id='div-comment-$id' class='comment-body'><div class='comment-avatar'><img class='avatar' src='/images/avatar.png'></div><div class='comment-author'><h5 class='heading fn'>$author</h5></div><div class='comment-content'>$body</div>$replies</article>"""
}

package eu.kanade.tachiyomi.ui.mod.chaptercomments

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AutoCommentsProviderTest {
    @Test fun `unknown domains reuse HTML engine after one on-demand discovery request`() = runTest {
        val urls = mutableListOf<String>()
        val transport = object : CommentsTransport {
            override suspend fun get(url: String, referer: String?): String {
                urls += url
                return """<div id="comments"><ol class="comment-list"><li class="comment" id="comment-31"><b class="fn">Reader</b><div class="comment-content">Chapter31 comment</div></li></ol></div>"""
            }
        }
        val generic = chapterCommentsProvider(CommentTarget("https://unlisted.example/chapter31", "31", "Other source"), transport)
        assertTrue(urls.isEmpty())
        val resolved = generic.prepare()
        assertTrue(resolved is HtmlCommentsProvider)
        assertFalse(CommentSort.POPULAR in resolved.supportedSorts)
        val page = resolved.comments(CommentSort.OLDEST, 1)
        assertEquals("comment-31", page.comments.single().id)
        assertFalse(page.comments.single().voteCountsAvailable)
        assertEquals(listOf("https://unlisted.example/chapter31"), urls)
    }

    @Test fun `unknown XenForo forum is identified by template instead of website name`() = runTest {
        var requests = 0
        val transport = object : CommentsTransport {
            override suspend fun get(url: String, referer: String?): String {
                requests++
                return """<html id="XF" data-template="thread_view" data-content-key="thread-31"><body><article class="message--post" data-content="post-1" data-author="Reader"><div class="message-body"><div class="bbWrapper">Discussion</div></div></article></body></html>"""
            }
        }
        val resolved = chapterCommentsProvider(CommentTarget("https://another.example/threads/topic.31/", "31", "Other forum"), transport).prepare()
        assertTrue(resolved is XenForoCommentsProvider)
        assertEquals(listOf(CommentSort.OLDEST), resolved.supportedSorts)
        assertEquals("Reader", resolved.comments(CommentSort.OLDEST, 1).comments.single().author)
        assertEquals(1, requests)
    }
}

package eu.kanade.tachiyomi.ui.mod.chaptercomments

import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MangaDexCommentsProviderTest {
    private val id = "71e67c07-0d9b-4d2b-bcf7-212f1db2ea66"
    private val target = CommentTarget("https://mangadex.org/chapter/$id", "Chapter9", "MangaDex")

    @Test fun `official statistics resolve one chapter discussion and omit only its opening card`() = runTest {
        val urls = mutableListOf<String>()
        val transport = fake { url ->
            urls += url
            if (url.startsWith(
                    "https://api.mangadex.org/",
                )
            ) {
                stats("""{"threadId":2748720,"repliesCount":1}""")
            } else {
                thread()
            }
        }
        val provider = MangaDexCommentsProvider(target, transport)
        assertTrue(urls.isEmpty())
        val first = async { provider.prepare() }
        val second = async { provider.prepare() }
        assertTrue(first.await() is XenForoCommentsProvider)
        second.await()
        assertEquals(1, urls.size)
        val page = provider.comments(CommentSort.OLDEST, 1)
        assertEquals(1, page.total)
        assertEquals(listOf("post-2"), page.comments.map { it.id })
        assertEquals("Reader", page.comments.single().author)
        assertTrue(page.comments.single().content.contains("<b>discussion</b>"))
        assertEquals("2026-10-20T01:00:00+0000", page.comments.single().createdAt)
        assertEquals(listOf(ChapterReaction("Reactions", 6)), page.comments.single().reactionCounts)
        assertTrue(
            urls.all {
                it.contains("statistics/chapter/") || it == "https://forums.mangadex.org/threads/2748720"
            },
        )
        assertEquals(listOf(CommentSort.OLDEST), provider.supportedSorts)
    }

    @Test fun `explicit null discussion is empty while absent or invalid statistics is an error`() = runTest {
        val urls = mutableListOf<String>()
        val none = MangaDexCommentsProvider(
            target,
            fake {
                urls += it
                stats("null")
            },
        )
        assertTrue(none.comments(CommentSort.OLDEST, 1).comments.isEmpty())
        assertTrue(none.comments(CommentSort.OLDEST, 2).comments.isEmpty())
        assertEquals(1, urls.size)
        for (response in listOf(
            """{"result":"error"}""",
            """{"result":"ok","statistics":{}}""",
            """{"result":"ok","statistics":{"$id":{}}}""",
        )) {
            val provider = MangaDexCommentsProvider(target, fake { response })
            assertTrue(runCatching { provider.comments(CommentSort.OLDEST, 1) }.isFailure)
        }
    }

    @Test fun `generic XenForo follows same thread page links and never repeats terminal posts`() = runTest {
        val urls = mutableListOf<String>()
        val forum = CommentTarget("https://forum.example/threads/topic.2748720/", "Discussion", "Forum")
        val provider = XenForoCommentsProvider(
            forum,
            fake { url ->
                urls += url
                if (url.endsWith("page-2")) thread(post("3", "Last reader", "Last post")) else thread(next = true)
            },
        )
        val page = provider.comments(CommentSort.OLDEST, 2)
        assertEquals(listOf("post-3"), page.comments.map { it.id })
        assertFalse(page.hasMore)
        assertEquals("https://forum.example/threads/topic.2748720/page-2", urls.last())
        assertTrue(provider.comments(CommentSort.OLDEST, 3).comments.isEmpty())
        assertEquals(2, urls.size)
        assertTrue(runCatching { provider.comments(CommentSort.POPULAR, 1) }.isFailure)
    }

    @Test fun `generic forum rejects login pages and foreign pagination without requesting foreign hosts`() = runTest {
        val forum = target.copy(url = "https://forums.mangadex.org/threads/2748720")
        val inaccessible =
            XenForoCommentsProvider(forum, fake { "<html id='XF' data-template='login'><form>Login</form></html>" })
        assertTrue(runCatching { inaccessible.comments(CommentSort.OLDEST, 1) }.isFailure)
        val urls = mutableListOf<String>()
        val hostile = XenForoCommentsProvider(
            forum,
            fake {
                urls += it
                thread(
                    next = true,
                ).replace("/threads/topic.2748720/page-2", "https://other.example/threads/topic.2748720/page-2")
            },
        )
        assertTrue(runCatching { hostile.comments(CommentSort.OLDEST, 2) }.isFailure)
        assertEquals(1, urls.size)
    }

    private fun fake(response: (String) -> String) = object : CommentsTransport {
        override suspend fun get(url: String, referer: String?): String {
            yield()
            return response(url)
        }
        override suspend fun write(
            method: String,
            url: String,
            json: String,
            referer: String?,
        ): String = error("Unexpected write")
    }

    private fun stats(comments: String) = """{"result":"ok","statistics":{"$id":{"comments":$comments}}}"""
    private fun post(
        id: String,
        author: String,
        body: String,
        first: Boolean = false,
    ) = """<article class='message message--post ${if (first) "is-first" else ""}' data-content='post-$id' data-author='$author'>
        <div class='message-avatar'><img src='/avatar.png'></div><header class='message-attribution'><time datetime='2026-10-20T01:00:00+0000'>Today</time></header>
        <article class='message-body'><div class='bbWrapper'>$body</div></article>
        <div class='reactionsBar'><a class='reactionsBar-link'><bdi>A</bdi>, <bdi>B</bdi>, <bdi>C</bdi> and 3 others</a></div></article>"""
    private fun thread(
        posts: String = post(
            "1",
            "MangaDex",
            "<a href='https://mangadex.org/chapter/$id'><img src='https://og.mangadex.org/chapter-card'></a>",
            true,
        ) +
            post("2", "Reader", "A <b>discussion</b>"),
        next: Boolean = false,
    ) = """<html id='XF' data-template='thread_view' data-content-key='thread-2748720'><body>$posts
        ${if (next) "<a class='pageNav-jump pageNav-jump--next' href='/threads/topic.2748720/page-2'>Next</a>" else ""}
        <script type='application/ld+json'>{"@type":"DiscussionForumPosting","interactionStatistic":{"interactionType":"https://schema.org/ReplyAction","userInteractionCount":1}}</script></body></html>"""
}

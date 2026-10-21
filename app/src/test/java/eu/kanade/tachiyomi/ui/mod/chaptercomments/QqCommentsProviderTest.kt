// Raw protocol fixtures intentionally preserve their original single-line payloads.
@file:Suppress("ktlint:standard:max-line-length")

package eu.kanade.tachiyomi.ui.mod.chaptercomments

import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class QqCommentsProviderTest {
    private val target = CommentTarget("https://template.example/manga/sample/chapter-327", "327", "Template")

    @Test fun `public wrapper resolves once and sends exact pagination and chronological directions`() = runTest {
        val urls = mutableListOf<String>()
        val transport = fake { url ->
            urls += url
            if (url == target.url) HTML else RESPONSE
        }
        val provider = QqCommentsProvider(target, transport)
        assertTrue(urls.isEmpty())
        val newest = async { provider.comments(CommentSort.NEWEST, 1) }
        val oldest = async { provider.comments(CommentSort.OLDEST, 2) }
        newest.await()
        oldest.await()
        assertEquals(1, urls.count { it == target.url })
        val reads = urls.filter { it != target.url }.map { it.toHttpUrl() }
        assertTrue(
            reads.all {
                it.encodedPath == "/api/comments/list" &&
                    it.queryParameter("commentable_type") == "chapters" &&
                    it.queryParameter("commentable_id") == "comic_id-36955-chapter_id-402"
            },
        )
        assertEquals(setOf("desc", "asc"), reads.map { it.queryParameter("direction") }.toSet())
        assertEquals(setOf("1", "2"), reads.map { it.queryParameter("page") }.toSet())
        assertEquals(listOf(CommentSort.NEWEST, CommentSort.OLDEST), provider.supportedSorts)
    }

    @Test fun `public counts nested children avatar spelling and media are preserved without image requests`() = runTest {
        val urls = mutableListOf<String>()
        val provider = QqCommentsProvider(
            target,
            fake {
                urls += it
                RESPONSE
            },
            HTML,
        )
        val page = provider.comments(CommentSort.OLDEST, 1)
        assertEquals(3, page.total)
        assertTrue(page.hasMore)
        val root = page.comments.first()
        assertEquals("2334878", root.id)
        assertEquals("Reader", root.author)
        assertEquals(2, root.upvotes)
        assertEquals(1, root.downvotes)
        assertTrue(root.content.contains("<b>comment</b>"))
        assertEquals("https://template.example/avatar.webp", root.avatar)
        assertEquals(listOf("https://template.example/comments/sample.webp"), root.media)
        assertEquals("2334878", root.replies.single().replyTo)
        assertEquals("Unknown User", root.replies.single().author)
        assertEquals(root.replies, provider.replies(root))
        assertTrue(provider.reactions().isEmpty())
        assertEquals(1, urls.size)
    }

    @Test fun `empty terminal page is valid but login or malformed response stays an error`() = runTest {
        val empty =
            QqCommentsProvider(
                target,
                fake {
                    """{"comments":[],"comment_count":"0 comments","pagination":{"current_page":2,"has_more_pages":false}}"""
                },
                HTML,
            )
        val page = empty.comments(CommentSort.NEWEST, 2)
        assertTrue(page.comments.isEmpty())
        assertFalse(page.hasMore)
        for (response in listOf(
            "<html>Login</html>",
            """{"success":false,"message":"Denied"}""",
            """{"comments":[]}""",
        )) {
            val provider = QqCommentsProvider(target, fake { response }, HTML)
            assertTrue(runCatching { provider.comments(CommentSort.NEWEST, 1) }.isFailure)
        }
        assertTrue(runCatching { empty.comments(CommentSort.POPULAR, 1) }.isFailure)
    }

    @Test fun `hidden and deleted records are not exposed and unrelated wrappers fail discovery`() = runTest {
        val data = """{"comments":[{"id":1,"content":"hidden","is_hidden":true},{"id":2,"content":"deleted","deleted_at":"2026-10-20"}],"comment_count":"0 comments","pagination":{"current_page":1,"has_more_pages":false}}"""
        assertTrue(QqCommentsProvider(target, fake { data }, HTML).comments(CommentSort.OLDEST, 1).comments.isEmpty())
        val unrelated =
            QqCommentsProvider(
                target,
                fake {
                    error("Unexpected API read")
                },
                "<div id='comment-wrapper' data-model='x' data-id='y'></div>",
            )
        assertTrue(runCatching { unrelated.comments(CommentSort.OLDEST, 1) }.isFailure)
    }

    private fun fake(response: (String) -> String) = object : CommentsTransport {
        override suspend fun get(url: String, referer: String?): String {
            yield()
            return response(url)
        }
        override suspend fun getWithHeaders(url: String, referer: String?, headers: Map<String, String>): String {
            assertEquals(mapOf("Accept" to "application/json"), headers)
            assertEquals(target.url, referer)
            return get(url, referer)
        }
        override suspend fun write(
            method: String,
            url: String,
            json: String,
            referer: String?,
        ): String = error("Unexpected write")
    }

    private companion object {
        const val HTML = """<!-- QQ Comment Service v1.1.0 --><div id="comment-wrapper" data-model="chapters" data-id="comic_id-36955-chapter_id-402"><div id="comment-root"></div><div id="comments-list-template" hidden></div></div>"""
        const val RESPONSE = """{"comments":[{"id":2334878,"content":"A <b>comment</b><img src='/comments/sample.webp'>","created_at":"2026-08-27T11:19:36.000000Z","is_hidden":false,"deleted_at":null,"parent_id":null,"like_count":2,"dislike_count":1,"commenter":{"id":"user-1","name":"Reader","avata":"/avatar.webp"},"children":[{"id":2334880,"content":"Reply","parent_id":2334878,"like_count":1,"dislike_count":0,"commenter":null}]},{"id":2335255,"content":"Another comment","children":[],"commenter":{"name":"Another reader","avata":null}}],"comment_count":"3 comments","pagination":{"current_page":1,"has_more_pages":true}}"""
    }
}

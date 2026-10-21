// Raw protocol fixtures intentionally preserve their original single-line payloads.
@file:Suppress("ktlint:standard:max-line-length")

package eu.kanade.tachiyomi.ui.mod.chaptercomments

import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class KomikuCommentsProviderTest {
    private val target = CommentTarget("https://komiku.org/ao-ashi-chapter-410/", "410", "An arbitrary source label")
    private val html = """<div data-auto-preview="true" data-series-id="3194" data-chapter-id="574308" data-load-id="comment-section-574308"></div><div id="commentOverlay"><div id="commentList"></div></div>"""

    @Test
    fun `detected markup and anonymous app headers load shared chapter counts and plain text`() = runTest {
        val reads = mutableListOf<String>()
        val headersSeen = mutableListOf<Map<String, String>>()
        val transport = object : CommentsTransport {
            override suspend fun get(url: String, referer: String?): String = error("Unexpected headerless API read")
            override suspend fun getWithHeaders(url: String, referer: String?, headers: Map<String, String>): String {
                reads += url
                headersSeen += headers
                yield()
                assertEquals(target.url, referer)
                return """{"code":"OK","data":[{"hash_id":"root","body":"<b>literal user text</b>","member_display_name":"Reader","reply_total":2,"reaction_total":3,"reaction_emotes":[{"emote":"love","total":3}],"sticker_image":"stickers/sample.png"}],"paging":{"next_page":null,"prev_page":null},"chapter_data":{"comment_total":5,"reaction_emotes":[{"emote":"sad","total":1}]}}"""
            }
        }
        val provider = KomikuCommentsProvider(target, transport, html)
        assertTrue(reads.isEmpty())
        val comments = async { provider.comments(CommentSort.NEWEST, 1) }
        val reactions = async { provider.reactions() }
        val result = comments.await()
        assertEquals(1, reads.size)
        assertTrue(reads.single().contains("series_id=3194&chapter_id=574308&sort_type=latest&page=1"))
        assertEquals(
            mapOf("x-package-name" to "id.komiku.plus", "x-fcm-token" to "null", "x-version-code" to "5"),
            headersSeen.single(),
        )
        assertEquals(5, result.total)
        assertFalse(result.hasMore)
        assertEquals("&lt;b&gt;literal user text&lt;/b&gt;", result.comments.single().content)
        assertEquals(listOf(ChapterReaction("love", 3)), result.comments.single().reactionCounts)
        assertEquals(0, result.comments.single().upvotes)
        assertEquals(
            listOf("https://plus.komiku.org/assets/media/images/stickers/sample.png"),
            result.comments.single().media,
        )
        assertEquals(listOf(ChapterReaction("sad", 1)), reactions.await())
    }

    @Test
    fun `reply expansion follows independent paging and root sort mapping remains public`() = runTest {
        val reads = mutableListOf<String>()
        val transport = object : CommentsTransport {
            override suspend fun get(url: String, referer: String?): String = html
            override suspend fun getWithHeaders(url: String, referer: String?, headers: Map<String, String>): String {
                reads += url
                return when {
                    "/comment/list" in url -> """{"code":"OK","data":[],"paging":{"next_page":2},"chapter_data":{"comment_total":50}}"""
                    url.endsWith(
                        "page=1",
                    ) -> """{"code":"OK","data":[{"hash_id":"a","body":"First"}],"paging":{"next_page":2}}"""
                    else -> """{"code":"OK","data":[{"hash_id":"b","body":"Second","nested_reply_hash_id":"a"}],"paging":{"next_page":null}}"""
                }
            }
        }
        val provider = KomikuCommentsProvider(target, transport)
        assertTrue(provider.comments(CommentSort.OLDEST, 1).hasMore)
        assertTrue(reads.first().contains("sort_type=earliest"))
        provider.comments(CommentSort.POPULAR, 1)
        assertTrue(reads[1].contains("sort_type=popular"))
        val replies = provider.replies(ChapterComment("root", "A", "Root"))
        assertEquals(listOf("a", "b"), replies.map { it.id })
        assertEquals("root", replies[0].replyTo)
        assertEquals("a", replies[1].replyTo)
        assertTrue(reads.last().contains("use_parent=true&sort_type=latest&page=2"))
    }

    @Test
    fun `API failures are not presented as empty comments`() = runTest {
        val provider = KomikuCommentsProvider(
            target,
            object : CommentsTransport {
                override suspend fun get(
                    url: String,
                    referer: String?,
                ): String = """{"code":"ERROR","message":"Public read unavailable"}"""
            },
            html,
        )
        assertEquals(
            "Public read unavailable",
            runCatching {
                provider.comments(CommentSort.NEWEST, 1)
            }.exceptionOrNull()?.message,
        )
    }
}

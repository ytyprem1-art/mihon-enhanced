// Raw protocol fixtures intentionally preserve their original single-line payloads.
@file:Suppress("ktlint:standard:max-line-length")

package eu.kanade.tachiyomi.ui.mod.chaptercomments

import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class IkiruCommentsProviderTest {
    private val target = CommentTarget("https://09.ikiru.wtf/manga/sample/chapter-22", "22", "Ikiru")

    @Test
    fun `reaction add switch and removal use exact chapter protocol and refresh cached counts`() = runTest {
        val writes = mutableListOf<JsonObject>()
        var commentGets = 0
        val provider = IkiruCommentsProvider(
            target,
            object : CommentsTransport {
                override suspend fun get(url: String, referer: String?): String = if ("/api/public/chapter/" in url) {
                    """{"success":true,"data":{"id":"chapter-uuid"}}"""
                } else {
                    commentGets++
                    """{"success":true,"data":{"comments":[],"pageData":{"totalCount":0,"reaction":{"LOVE":2}}}}"""
                }
                override suspend fun write(method: String, url: String, json: String, referer: String?): String {
                    assertEquals("POST", method)
                    assertEquals("https://09.ikiru.wtf/api/private/reaction", url)
                    assertEquals(target.url, referer)
                    val payload = Json.parseToJsonElement(json) as JsonObject
                    writes += payload
                    assertEquals("chapter-uuid", (payload["postId"] as JsonPrimitive).content)
                    assertEquals("chapter", (payload["postType"] as JsonPrimitive).content)
                    assertEquals(setOf("postId", "type", "postType"), payload.keys)
                    return when (writes.size) {
                        1 -> """{"success":true,"code":200,"data":{"action":"created","type":"LOVE"}}"""
                        2 -> """{"success":true,"code":200,"data":{"action":"updated","type":"ANGRY"}}"""
                        else -> """{"success":true,"code":200,"data":{"action":"deleted","type":null}}"""
                    }
                }
            },
        )
        provider.comments(CommentSort.POPULAR, 1)
        provider.reactions()
        assertEquals(1, commentGets)
        assertTrue(writes.isEmpty())
        assertEquals("ikiru:chapter:chapter-uuid", provider.chapterVoteIdentity())
        provider.setChapterReaction(null, "LOVE")
        provider.reactions()
        assertEquals(2, commentGets)
        provider.setChapterReaction("LOVE", "ANGRY")
        provider.setChapterReaction("ANGRY", null)
        provider.setChapterReaction(null, null)
        assertEquals(listOf("LOVE", "ANGRY", "ANGRY"), writes.map { (it["type"] as JsonPrimitive).content })
        assertEquals(
            setOf("UPVOTE", "FUNNY", "LOVE", "SURPRISED", "SAD", "ANGRY"),
            provider.reactionOptions.map {
                it.key
            }.toSet(),
        )
    }

    @Test
    fun `rejected anonymous reaction keeps read cache and reports login requirement`() = runTest {
        var commentGets = 0
        val provider = IkiruCommentsProvider(
            target,
            object : CommentsTransport {
                override suspend fun get(url: String, referer: String?): String = if ("/api/public/chapter/" in url) {
                    """{"success":true,"data":{"id":"chapter-uuid"}}"""
                } else {
                    commentGets++
                    """{"success":true,"data":{"comments":[],"pageData":{"reaction":{"LOVE":2}}}}"""
                }
                override suspend fun write(
                    method: String,
                    url: String,
                    json: String,
                    referer: String?,
                ) = """{"success":false,"code":401,"message":"Unauthorized"}"""
            },
        )
        provider.reactions()
        val failure = runCatching { provider.setChapterReaction(null, "LOVE") }.exceptionOrNull()
        assertTrue(failure?.message.orEmpty().contains("login"))
        provider.reactions()
        assertEquals(1, commentGets)
    }

    @Test
    fun `lazy concurrent reads share identity and first page while page two retains metadata`() = runTest {
        val calls = mutableListOf<String>()
        val transport = object : CommentsTransport {
            override suspend fun get(url: String, referer: String?): String {
                calls += url
                yield()
                assertEquals(target.url, referer)
                return when {
                    url.contains("/api/public/chapter/") -> """{"success":true,"data":{"id":"chapter-uuid"}}"""
                    url.contains(
                        "/replies?",
                    ) -> """{"success":true,"data":{"replies":[{"id":"child","parentId":"c1","repliedToId":"c1","content":"<b>Reply</b>","likes":4,"user":{"username":"B"}}]}}"""
                    url.endsWith(
                        "page=2",
                    ) -> """{"success":true,"data":{"comments":[{"id":"last","content":"last"}]}}"""
                    else -> """{"success":true,"data":{"comments":[${(1..10).joinToString {
                        """{"id":"c$it","content":"<p>Visible HTML</p>","likes":2,"isSpoiler":true,"replyCount":1,"user":{"username":"A"}}"""
                    }}],"pageData":{"totalCount":11,"reaction":{"UPVOTE":157,"FUNNY":6},"pinned":{"id":"pin","content":"Pinned"}}}}"""
                }
            }
        }
        val provider = IkiruCommentsProvider(target, transport)
        assertTrue(calls.isEmpty())
        val page = async { provider.comments(CommentSort.POPULAR, 1) }
        val reactions = async { provider.reactions() }
        val first = page.await()
        assertEquals(1, calls.count { "/api/public/chapter/" in it })
        assertEquals(1, calls.count { "orderby=best&page=1" in it })
        assertEquals("pin", first.comments.first().id)
        assertTrue(first.hasMore)
        assertEquals(157, reactions.await().first().count)
        assertTrue(first.comments[1].isSpoiler)
        assertEquals("<p>Visible HTML</p>", first.comments[1].content)
        val second = provider.comments(CommentSort.POPULAR, 2)
        assertEquals(11, second.total)
        assertFalse(second.hasMore)
        val reply = provider.replies(first.comments[1]).single()
        assertEquals("c1", reply.replyTo)
        assertEquals(4, reply.upvotes)
        assertEquals("<b>Reply</b>", reply.content)
    }

    @Test
    fun `failed public response remains an error`() = runTest {
        val provider = IkiruCommentsProvider(
            target,
            object : CommentsTransport {
                override suspend fun get(
                    url: String,
                    referer: String?,
                ) = """{"success":false,"message":"Temporarily unavailable"}"""
            },
        )
        val failure = runCatching { provider.comments(CommentSort.NEWEST, 1) }.exceptionOrNull()
        assertEquals("Temporarily unavailable", failure?.message)
    }
}

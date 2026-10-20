package eu.kanade.tachiyomi.ui.mod.chaptercomments

import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class VoratoonCommentsProviderTest {
    private val target = CommentTarget("https://v10.voratoon.com/series/sample/chapter/924", "924", "Voratoon")

    @Test
    fun `reads never vote and explicit chapter like toggle sends numeric identity`() = runTest {
        val writes = mutableListOf<JsonObject>()
        val transport = object : CommentsTransport {
            override suspend fun get(url: String, referer: String?) = when {
                url.endsWith("/reactions") -> """{"status":200,"data":["like"]}"""
                "/comments?" in url -> """{"status":200,"data":[],"meta":{"lastPage":1}}"""
                else -> """{"status":200,"data":{"id":435871,"seriesId":4718}}"""
            }
            override suspend fun write(method: String, url: String, json: String, referer: String?): String {
                assertEquals("POST", method)
                assertEquals("https://api.voratoon.com/reactions", url)
                assertEquals(target.url, referer)
                val payload = Json.parseToJsonElement(json) as JsonObject
                writes += payload
                val selected = if ((payload["isAdd"] as JsonPrimitive).booleanOrNull == true) "\"like\"" else "null"
                return """{"status":200,"data":{"reactions":[],"selectedReaction":$selected}}"""
            }
        }
        val provider = VoratoonCommentsProvider(target, transport)
        provider.comments(CommentSort.POPULAR, 1)
        provider.reactions()
        assertEquals("https://api.voratoon.com/series/4718/chapters/435871", provider.chapterVoteIdentity())
        assertTrue(writes.isEmpty())
        provider.setChapterUpvote(true)
        provider.setChapterUpvote(false)
        assertEquals(2, writes.size)
        writes.forEach { payload ->
            assertEquals(4718L, (payload["seriesId"] as JsonPrimitive).longOrNull)
            assertEquals(435871L, (payload["chapterId"] as JsonPrimitive).longOrNull)
            assertFalse((payload["seriesId"] as JsonPrimitive).isString)
            assertFalse((payload["chapterId"] as JsonPrimitive).isString)
            assertEquals("like", (payload["reactionType"] as JsonPrimitive).contentOrNull)
            assertEquals(setOf("seriesId", "chapterId", "reactionType", "isAdd"), payload.keys)
        }
        assertEquals(listOf(true, false), writes.map { (it["isAdd"] as JsonPrimitive).booleanOrNull })
    }

    @Test
    fun `emoji switch removes previous before adding next and off only removes`() = runTest {
        val writes = mutableListOf<Pair<String, Boolean>>()
        val provider = VoratoonCommentsProvider(target, object : CommentsTransport {
            override suspend fun get(url: String, referer: String?) = """{"status":200,"data":{"id":435871,"seriesId":4718}}"""
            override suspend fun write(method: String, url: String, json: String, referer: String?): String {
                val payload = Json.parseToJsonElement(json) as JsonObject
                val type = (payload["reactionType"] as JsonPrimitive).content
                val enabled = (payload["isAdd"] as JsonPrimitive).booleanOrNull == true
                writes += type to enabled
                val selected = if (enabled) "\"$type\"" else "null"
                return """{"status":200,"data":{"reactions":[],"selectedReaction":$selected}}"""
            }
        })
        assertEquals(setOf("like", "laugh", "love", "sad", "angry"), provider.reactionOptions.map { it.key }.toSet())
        provider.setChapterReaction(null, "love")
        provider.setChapterReaction("love", "angry")
        provider.setChapterReaction("angry", null)
        provider.setChapterReaction(null, null)
        assertEquals(listOf("love" to true, "love" to false, "angry" to true, "angry" to false), writes)
    }

    @Test
    fun `mismatched server selection is not acknowledged as successful reaction`() = runTest {
        val provider = VoratoonCommentsProvider(target, object : CommentsTransport {
            override suspend fun get(url: String, referer: String?) = """{"status":200,"data":{"id":1,"seriesId":2}}"""
            override suspend fun write(method: String, url: String, json: String, referer: String?) = """{"status":200,"data":{"selectedReaction":null,"reactions":[]}}"""
        })
        assertTrue(runCatching { provider.setChapterReaction(null, "love") }.isFailure)
    }

    @Test
    fun `failed addition after successful removal reports confirmed empty selection`() = runTest {
        val writes = mutableListOf<String>()
        val provider = VoratoonCommentsProvider(target, object : CommentsTransport {
            override suspend fun get(url: String, referer: String?) = """{"status":200,"data":{"id":1,"seriesId":2}}"""
            override suspend fun write(method: String, url: String, json: String, referer: String?): String {
                writes += json
                return if (writes.size == 1) {
                    """{"status":200,"data":{"selectedReaction":null,"reactions":[]}}"""
                } else {
                    """{"status":401,"message":"Unauthorized"}"""
                }
            }
        })
        val failure = runCatching { provider.setChapterReaction("love", "angry") }.exceptionOrNull()
        assertTrue(failure is ChapterReactionUpdateException)
        assertEquals(null, (failure as ChapterReactionUpdateException).confirmedSelection)
        assertTrue(failure.cause?.message.orEmpty().contains("login"))
        assertEquals(2, writes.size)
        assertEquals(listOf(false, true), writes.map { ((Json.parseToJsonElement(it) as JsonObject)["isAdd"] as JsonPrimitive).booleanOrNull })
    }

    @Test
    fun `vote authentication rejection surfaces login requirement and does not retry`() = runTest {
        var writes = 0
        val provider = VoratoonCommentsProvider(target, object : CommentsTransport {
            override suspend fun get(url: String, referer: String?) = """{"status":200,"data":{"id":435871,"seriesId":4718}}"""
            override suspend fun write(method: String, url: String, json: String, referer: String?): String {
                writes++
                return """{"status":401,"message":"Unauthorized"}"""
            }
        })
        val failure = runCatching { provider.setChapterUpvote(true) }.exceptionOrNull()
        assertTrue(failure?.message.orEmpty().contains("login"))
        assertEquals(1, writes)
    }

    @Test
    fun `flat replies and ignored backend pagination are assembled into local root pages`() = runTest {
        val calls = mutableListOf<String>()
        val roots = (1..11).joinToString { """{"id":$it,"content":"Root $it","createdAt":"2026-10-20T00:00:00Z","data":{"user":{"fullName":"A"},"votes":{"upvotes":${if (it == 11) 10 else 0}}}}""" }
        val transport = object : CommentsTransport {
            override suspend fun get(url: String, referer: String?): String {
                calls += url
                yield()
                return when {
                    url.endsWith("/reactions") -> """{"status":200,"data":["like","laugh","like"]}"""
                    url.contains("/comments?") -> """{"status":200,"data":[$roots,{"id":12,"content":"![photo](https://cdn.example/reply.png)","isSpoiler":true,"data":{"parentCommentId":11,"votes":{"upvotes":4}}}],"meta":{"page":1,"take":12,"total":12,"lastPage":1,"hasNextPage":false}}"""
                    else -> """{"status":200,"data":{"id":435871,"seriesId":4718}}"""
                }
            }
        }
        val provider = VoratoonCommentsProvider(target, transport)
        assertTrue(calls.isEmpty())
        val firstRequest = async { provider.comments(CommentSort.POPULAR, 1) }
        val reactionRequest = async { provider.reactions() }
        val first = firstRequest.await()
        assertEquals(1, calls.count { it.endsWith("/series/sample/chapters/924") })
        assertEquals(10, first.comments.size)
        assertEquals(11, first.total)
        assertTrue(first.hasMore)
        assertEquals("11", first.comments.first().id)
        val reply = first.comments.first().replies.single()
        assertEquals("11", reply.replyTo)
        assertEquals(listOf("https://cdn.example/reply.png"), reply.media)
        assertTrue(reply.isSpoiler)
        assertEquals(4, reply.upvotes)
        assertEquals(2, reactionRequest.await().first().count)
        val second = provider.comments(CommentSort.POPULAR, 2)
        assertEquals(1, second.comments.size)
        assertFalse(second.hasMore)
        assertEquals(1, calls.count { "/comments?" in it })
        assertEquals(listOf(reply), provider.replies(first.comments.first()))
    }

    @Test
    fun `cycles and missing parents remain visible and date ordering handles timezone offsets`() = runTest {
        val transport = object : CommentsTransport {
            override suspend fun get(url: String, referer: String?) = if ("/comments?" in url) {
                """{"status":200,"data":[{"id":1,"createdAt":"2026-10-20T07:30:00+07:00","data":{"parentCommentId":2}},{"id":2,"createdAt":"2026-10-20T01:00:00Z","data":{"parentCommentId":1}},{"id":3,"createdAt":"2026-10-20T00:00:00Z","data":{"parentCommentId":999}}],"meta":{"page":1,"lastPage":1}}"""
            } else {
                """{"status":200,"data":{"id":7,"seriesId":8}}"""
            }
        }
        val page = VoratoonCommentsProvider(target, transport).comments(CommentSort.NEWEST, 1)
        val all = page.comments.flatMap { listOf(it) + it.replies }
        assertEquals(setOf("1", "2", "3"), all.map { it.id }.toSet())
        assertEquals("3", page.comments.last().id)
        assertFalse(page.hasMore)
    }
}

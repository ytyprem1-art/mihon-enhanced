package eu.kanade.tachiyomi.ui.mod.chaptercomments

import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ShinigamiCommentsProviderTest {
    @Test fun `failed new reaction after confirmed removal reports no selection without retrying`() = runBlocking {
        val writes = mutableListOf<String>()
        val transport = object : CommentsTransport {
            override suspend fun get(url: String, referer: String?): String = error("Unexpected read")
            override suspend fun write(method: String, url: String, json: String, referer: String?): String {
                writes += json
                return if (writes.size == 1) """{"errno":0,"data":{}}""" else """{"errno":403,"errmsg":"Denied"}"""
            }
        }
        val provider = ShinigamiCommentsProvider(CommentTarget("https://11.shinigami.asia/chapter/id-31", "Chapter", "Shinigami"), transport)
        val failure = runCatching { provider.setChapterReaction("reaction0", "reaction1") }.exceptionOrNull()
        assertTrue(failure is ChapterReactionUpdateException)
        assertNull((failure as ChapterReactionUpdateException).confirmedSelection)
        assertTrue(failure.cause is IllegalStateException)
        assertEquals(2, writes.size)
        assertTrue(writes.first().contains("\"action\":\"desc\""))
        assertFalse(writes.last().contains("\"action\""))
    }

    @Test fun `reaction switching decrements old before incrementing new and removal only decrements`() = runBlocking {
        val writes = mutableListOf<kotlinx.serialization.json.JsonElement>()
        var fail = false
        val transport = object : CommentsTransport {
            override suspend fun get(url: String, referer: String?): String =
                """{"errno":0,"data":[{"reaction0":1,"reaction1":2,"reaction2":3}]}"""
            override suspend fun write(method: String, url: String, json: String, referer: String?): String {
                assertEquals("POST", method)
                assertEquals("https://commento.shngm.io/api/article?lang=en", url)
                writes += kotlinx.serialization.json.Json.parseToJsonElement(json)
                return if (fail) """{"errno":403,"errmsg":"Denied"}""" else """{"errno":0,"data":{}}"""
            }
        }
        val provider = ShinigamiCommentsProvider(CommentTarget("https://11.shinigami.asia/chapter/id-31", "Chapter", "Shinigami"), transport)
        assertEquals(listOf("Upvote", "Suki", "Apsih"), provider.reactionOptions.map { it.countLabel })
        assertTrue(provider.reactionOptions.all { it.imageUrl?.endsWith(".webp") == true })
        provider.reactions()
        provider.setChapterReaction(null, null)
        assertTrue(writes.isEmpty())
        provider.setChapterReaction("reaction0", "reaction1")
        provider.setChapterReaction("reaction1", null)
        provider.setChapterReaction("reaction2", "reaction2")
        val expected = listOf(
            """{"path":"chapter/id-31","type":"reaction0","action":"desc"}""",
            """{"path":"chapter/id-31","type":"reaction1"}""",
            """{"path":"chapter/id-31","type":"reaction1","action":"desc"}""",
        ).map { kotlinx.serialization.json.Json.parseToJsonElement(it) }
        assertEquals(expected, writes)
        assertTrue(runCatching { provider.setChapterReaction("invalid", "reaction1") }.isFailure)
        assertTrue(runCatching { provider.setChapterReaction(null, "invalid") }.isFailure)
        assertEquals(3, writes.size)
        fail = true
        assertTrue(runCatching { provider.setChapterReaction("reaction1", "reaction2") }.isFailure)
        assertEquals(4, writes.size) // Failed decrement never proceeds to increment.
    }

    @Test fun `chapter upvote uses explicit anonymous increment and decrement requests`() = runBlocking {
        val target = CommentTarget("https://11.shinigami.asia/chapter/id-31", "Chapter31", "Shinigami")
        val writes = mutableListOf<kotlinx.serialization.json.JsonObject>()
        var response = """{"errno":0,"errmsg":"","data":{"reaction0":101}}"""
        val transport = object : CommentsTransport {
            override suspend fun get(url: String, referer: String?): String =
                """{"errno":0,"data":[{"reaction0":100,"reaction1":2,"reaction2":3}]}"""
            override suspend fun write(method: String, url: String, json: String, referer: String?): String {
                assertEquals("POST", method)
                assertEquals("https://commento.shngm.io/api/article?lang=en", url)
                assertEquals(target.url, referer)
                writes += kotlinx.serialization.json.Json.parseToJsonElement(json) as kotlinx.serialization.json.JsonObject
                return response
            }
        }
        val provider = ShinigamiCommentsProvider(target, transport)
        assertEquals("shinigami:id-31", provider.chapterVoteIdentity())
        provider.reactions()
        assertTrue(writes.isEmpty())
        provider.setChapterUpvote(true)
        provider.setChapterUpvote(false)
        assertEquals(kotlinx.serialization.json.Json.parseToJsonElement("""{"path":"chapter/id-31","type":"reaction0"}"""), writes[0])
        assertEquals(kotlinx.serialization.json.Json.parseToJsonElement("""{"path":"chapter/id-31","type":"reaction0","action":"desc"}"""), writes[1])
        for (error in listOf("""{"errno":403,"errmsg":"Denied"}""", """{"errno":0,"success":false}""", """{"errno":0,"error":"Denied"}""", """{}""")) {
            response = error
            assertTrue(runCatching { provider.setChapterUpvote(true) }.isFailure)
        }
    }

    @Test fun `captured anonymous shape handles null metadata numeric dates and nested replies`() = runBlocking {
        val transport = object : CommentsTransport {
            override suspend fun get(url: String, referer: String?): String =
                """{"errno":0,"errmsg":"","data":{"page":1,"totalPages":4,"pageSize":10,"count":60,"data":[{"status":"approved","comment":"<p>A public comment</p>","link":"","nick":"Reader","pid":null,"rid":null,"user_id":123,"sticky":null,"like":27,"objectId":1001,"level":0,"type":"guest","label":null,"avatar":"https://example.com/avatar.png","orig":"A public comment","time":1792400000000,"children":[{"objectId":1002,"nick":"Reply reader","comment":"<p>A reply</p>","like":2,"time":1792400100000,"reply_user":{"nick":"Reader"},"children":[{"objectId":1003,"nick":"Nested reader","comment":"Nested","time":1792400200000,"like":0,"children":[]}]}]}]}}"""
        }
        val page = ShinigamiCommentsProvider(
            CommentTarget("https://11.shinigami.asia/chapter/sample-id", "Chapter", "Shinigami"),
            transport,
        ).comments(CommentSort.POPULAR, 1)
        assertEquals(60, page.total)
        assertTrue(page.hasMore)
        val root = page.comments.single()
        assertEquals("1001", root.id)
        assertEquals("1792400000000", root.createdAt)
        assertEquals(27, root.upvotes)
        assertEquals("Reader", root.replies.single().replyTo)
        assertEquals("1003", root.replies.single().replies.single().id)
    }

    @Test fun `no preload and exact chapter path pagination sorts replies and reaction counts`() = runBlocking {
        val paths = mutableListOf<String>()
        val transport = object : CommentsTransport {
            override suspend fun get(url: String, referer: String?): String {
                paths.add(url)
                val parsed = url.toHttpUrl()
                assertEquals("chapter/id-31", parsed.queryParameter("path"))
                return if (parsed.pathSegments.last() == "article") {
                    """{"errno":0,"data":[{"reaction0":100,"reaction1":2,"reaction2":3}]}"""
                } else """{"errno":0,"data":{"count":20,"page":1,"totalPages":2,"data":[{"objectId":31,"nick":"A","comment":"<p>chapter31</p>","like":4,"children":[{"objectId":32,"nick":"B","comment":"reply","reply_user":{"nick":"A"}}]}]}}"""
            }
        }
        val provider = ShinigamiCommentsProvider(CommentTarget("https://11.shinigami.asia/chapter/id-31", "Chapter31", "Shinigami"), transport)
        assertTrue(paths.isEmpty())
        val page = provider.comments(CommentSort.POPULAR, 1)
        assertTrue(page.hasMore)
        assertEquals("B", page.comments.single().replies.single().author)
        assertEquals(4, page.comments.single().upvotes)
        assertEquals("like_desc", paths.last().toHttpUrl().queryParameter("sortBy"))
        provider.comments(CommentSort.NEWEST, 2)
        assertEquals("2", paths.last().toHttpUrl().queryParameter("page"))
        assertEquals("insertedAt_desc", paths.last().toHttpUrl().queryParameter("sortBy"))
        assertEquals(listOf(100, 2, 3), provider.reactions().map { it.count })
    }
}

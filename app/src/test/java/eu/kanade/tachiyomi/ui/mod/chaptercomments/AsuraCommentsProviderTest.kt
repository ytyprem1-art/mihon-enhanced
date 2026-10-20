package eu.kanade.tachiyomi.ui.mod.chaptercomments

import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AsuraCommentsProviderTest {
    private val target = CommentTarget("https://asurascans.com/comics/sample/chapter/8", "8", "Asura")

    @Test
    fun `all reaction choices switch atomically and removal sends null`() = runTest {
        val writes = mutableListOf<String>()
        val transport = object : CommentsTransport {
            override suspend fun get(url: String, referer: String?): String = if (url == target.url) HTML else REACTIONS
            override suspend fun write(method: String, url: String, json: String, referer: String?): String {
                assertEquals("POST", method)
                assertEquals("https://api.asurascans.com/api/chapters/262359/reactions", url)
                writes += json
                val selected = Json.parseToJsonElement(json).jsonObject["type"]
                return """{"data":{"reaction_counts":{"love":541,"angry":3},"user_reaction":$selected}}"""
            }
        }
        val provider = AsuraCommentsProvider(target, transport)
        assertEquals(setOf("angry", "funny", "love", "sad", "surprised", "upvote"), provider.reactionOptions.map { it.key }.toSet())
        assertTrue(provider.reactionOptions.all { it.key == it.countLabel })
        provider.reactions()
        provider.setChapterReaction(null, null)
        assertTrue(writes.isEmpty())
        provider.setChapterReaction(null, "love")
        provider.setChapterReaction("love", "angry")
        provider.setChapterReaction("angry", null)
        provider.setChapterReaction("sad", "sad")
        assertEquals(listOf("""{"type":"love"}""", """{"type":"angry"}""", """{"type":null}"""), writes)
        assertTrue(runCatching { provider.setChapterReaction("invalid", "love") }.isFailure)
        assertTrue(runCatching { provider.setChapterReaction(null, "invalid") }.isFailure)
        assertEquals(3, writes.size)
    }

    @Test
    fun `chapter upvote writes exact public toggle protocol only on explicit calls`() = runTest {
        val writes = mutableListOf<String>()
        var htmlRequests = 0
        var response = """{"data":{"reaction_counts":{"upvote":44},"user_reaction":"upvote"}}"""
        val transport = object : CommentsTransport {
            override suspend fun get(url: String, referer: String?): String {
                if (url == target.url) { htmlRequests++; return HTML }
                return REACTIONS
            }
            override suspend fun write(method: String, url: String, json: String, referer: String?): String {
                assertEquals("POST", method)
                assertEquals("https://api.asurascans.com/api/chapters/262359/reactions", url)
                assertEquals(target.url, referer)
                writes += json
                return response
            }
        }
        val provider = AsuraCommentsProvider(target, transport)
        assertTrue(writes.isEmpty())
        provider.reactions()
        assertEquals("asura:262359", provider.chapterVoteIdentity())
        assertTrue(writes.isEmpty())
        provider.setChapterUpvote(true)
        response = """{"data":{"reaction_counts":{"upvote":43},"user_reaction":null}}"""
        provider.setChapterUpvote(false)
        assertEquals(listOf("""{"type":"upvote"}""", """{"type":null}"""), writes)
        assertEquals(1, htmlRequests)
        for (error in listOf("""{"success":false,"error":"Denied"}""", """{"errno":401,"data":{}}""", """{}""", """{"success":true,"data":{}}""")) {
            response = error
            assertTrue(runCatching { provider.setChapterUpvote(true) }.isFailure)
        }
    }

    @Test
    fun `confirmed mutation counts replace stale public GET counts without inventing an increment`() = runTest {
        var reactionReads = 0
        var response = """{"data":{"reaction_counts":{"upvote":49,"love":5},"user_reaction":"upvote"}}"""
        val transport = object : CommentsTransport {
            override suspend fun get(url: String, referer: String?): String {
                if (url == target.url) return HTML
                reactionReads++
                return """{"data":{"reaction_counts":{"upvote":48,"love":5},"user_reaction":null}}"""
            }
            override suspend fun write(method: String, url: String, json: String, referer: String?): String = response
        }
        val provider = AsuraCommentsProvider(target, transport)
        assertEquals(48, provider.reactions().first { it.label == "upvote" }.count)
        provider.setChapterReaction(null, "upvote")
        assertEquals(49, provider.reactions().first { it.label == "upvote" }.count)
        assertEquals(1, reactionReads)
        response = """{"data":{"reaction_counts":{"upvote":48,"love":8},"user_reaction":"love"}}"""
        provider.setChapterReaction("upvote", "love")
        assertEquals(8, provider.reactions().first { it.label == "love" }.count)
        assertEquals(48, provider.reactions().first { it.label == "upvote" }.count)
        response = """{"data":{"reaction_counts":{"upvote":48,"love":7},"user_reaction":null}}"""
        provider.setChapterReaction("love", null)
        assertEquals(7, provider.reactions().first { it.label == "love" }.count)
        assertEquals(1, reactionReads)
    }

    @Test
    fun `unconfirmed selection propagates confirmed server state instead of marking requested reaction successful`() = runTest {
        val provider = AsuraCommentsProvider(target, object : CommentsTransport {
            override suspend fun get(url: String, referer: String?): String = HTML
            override suspend fun write(method: String, url: String, json: String, referer: String?): String =
                """{"data":{"reaction_counts":{"upvote":48},"user_reaction":null}}"""
        })
        val failure = runCatching { provider.setChapterReaction(null, "upvote") }.exceptionOrNull()
        assertTrue(failure is ChapterReactionUpdateException)
        assertEquals(null, (failure as ChapterReactionUpdateException).confirmedSelection)
        assertEquals(48, provider.reactions().single().count)
    }

    @Test
    fun `parallel reads share lazy chapter resolution and preserve public counts`() = runTest {
        val fake = FakeTransport { url ->
            when {
                url == target.url -> HTML
                url.endsWith("/reactions") -> REACTIONS
                else -> COMMENTS
            }
        }
        val provider = AsuraCommentsProvider(target, fake)
        assertTrue(fake.urls.isEmpty())
        val comments = async { provider.comments(CommentSort.POPULAR, 1) }
        val reactions = async { provider.reactions() }
        val page = comments.await()
        assertEquals(1, fake.urls.count { it == target.url })
        assertTrue(fake.urls.contains("https://api.asurascans.com/api/chapters/262359/comments?sort=top&limit=20&offset=0"))
        assertEquals(256, page.total)
        assertTrue(page.hasMore)
        val comment = page.comments.single()
        assertEquals(323, comment.upvotes)
        assertEquals(2, comment.downvotes)
        assertEquals("Reader", comment.author)
        assertEquals(listOf("https://cdn.asurascans.com/sample.webp"), comment.media)
        assertEquals("13990205", comment.replies.single().replyTo)
        assertEquals(50, comment.replies.single().upvotes)
        assertEquals(listOf(ChapterReaction("love", 541), ChapterReaction("upvote", 43)), reactions.await())
        val requestCount = fake.urls.size
        assertEquals(comment.replies, provider.replies(comment))
        assertEquals(requestCount, fake.urls.size)
    }

    @Test
    fun `sort and page map to server offset without another chapter request`() = runTest {
        val fake = FakeTransport { if (it == target.url) HTML else """{"data":[],"meta":{"total":0,"has_more":false},"success":true}""" }
        val provider = AsuraCommentsProvider(target, fake)
        assertFalse(provider.comments(CommentSort.NEWEST, 2).hasMore)
        provider.comments(CommentSort.OLDEST, 3)
        assertTrue(fake.urls.any { it.endsWith("comments?sort=newest&limit=20&offset=20") })
        assertTrue(fake.urls.any { it.endsWith("comments?sort=oldest&limit=20&offset=40") })
        assertEquals(1, fake.urls.count { it == target.url })
    }

    @Test
    fun `bare reply arrays retain parent identity and tolerate nullable presentation fields`() = runTest {
        val fake = FakeTransport { """[{"id":13990294,"content":"A reply","upvotes":50,"downvotes":0,"user":null,"media_urls":null,"gif_url":null}]""" }
        val provider = AsuraCommentsProvider(target, fake)
        val replies = provider.replies(ChapterComment("13990205", "Reader", "A comment", replyCount = 1))
        assertEquals("13990205", replies.single().replyTo)
        assertEquals("Pengguna", replies.single().author)
        assertTrue(replies.single().media.isEmpty())
        assertEquals(listOf("https://api.asurascans.com/api/comments/13990205/replies"), fake.urls)
    }

    @Test
    fun `failed chapter resolution can be retried without caching the failure`() = runTest {
        var htmlRequests = 0
        val fake = FakeTransport {
            if (it == target.url) {
                htmlRequests++
                if (htmlRequests == 1) "<html>Temporary response</html>" else HTML
            } else {
                REACTIONS
            }
        }
        val provider = AsuraCommentsProvider(target, fake)
        var failed = false
        try {
            provider.reactions()
        } catch (_: IllegalStateException) {
            failed = true
        }
        assertTrue(failed)
        assertEquals(2, provider.reactions().size)
        assertEquals(2, htmlRequests)
    }

    private inner class FakeTransport(val response: (String) -> String) : CommentsTransport {
        val urls = mutableListOf<String>()
        override suspend fun get(url: String, referer: String?): String {
            assertEquals(target.url, referer)
            urls += url
            yield()
            return response(url)
        }
    }

    private companion object {
        // Minimal sanitized excerpts of the public 2026-10-20 captures.
        const val HTML = """<astro-island component-url="/_astro/ChapterReader.BYs7Qbam.js" props="{&quot;chapterId&quot;:[0,262359],&quot;commentsEnabled&quot;:[0,true]}"></astro-island>"""
        const val COMMENTS = """{"data":[{"id":13990205,"content":"A public comment","created_at":"2026-10-19T20:59:03.184727Z","upvotes":323,"downvotes":2,"reply_count":1,"media_urls":["https://cdn.asurascans.com/sample.webp"],"gif_url":"https://cdn.asurascans.com/sample.webp","user":{"username":"Reader"},"replies":[{"id":13990294,"content":"A reply","upvotes":50,"downvotes":0,"reply_count":0,"user":{"username":"Another reader"}}]}],"meta":{"total":256,"has_more":true},"success":true}"""
        const val REACTIONS = """{"data":{"reaction_counts":{"love":541,"upvote":43},"user_reaction":null}}"""
    }
}

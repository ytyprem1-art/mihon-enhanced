// Raw protocol fixtures intentionally preserve their original single-line payloads.
@file:Suppress("ktlint:standard:max-line-length")

package eu.kanade.tachiyomi.ui.mod.chaptercomments

import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class KomikuAsiaCommentsProviderTest {
    private val target = CommentTarget("https://01.komiku.asia/read/id/nano-machine/ch334-517833", "334", "Komiku.com")

    @Test fun `stored comic and chapter ID route resolves number through API without fetching obsolete HTML`() = runTest {
        val stored = target.copy(url = "https://01.komiku.asia/85049/517833")
        val urls = mutableListOf<String>()
        val provider = KomikuAsiaCommentsProvider(
            stored,
            object : CommentsTransport {
                override suspend fun get(url: String, referer: String?): String {
                    assertEquals(stored.url, referer)
                    urls += url
                    yield()
                    return when {
                        url.endsWith("/chapters/id/517833") -> """{"id":517833,"comicId":85049,"n":334,"pages":[]}"""
                        url.contains(
                            "/comics?ids=85049&perPage=1",
                        ) -> """{"items":[{"id":85049,"slug":"nano-machine"}],"total":1}"""
                        url.endsWith("/chapters/334/comments") -> COMMENTS
                        url.endsWith("/chapters/334/reactions") -> """{"upvote":33,"love":3}"""
                        else -> error("Unexpected request: $url")
                    }
                }
            },
        )
        val page = async { provider.comments(CommentSort.POPULAR, 1) }
        val reactions = async { provider.reactions() }
        assertEquals(3, page.await().total)
        assertEquals(33, reactions.await().first().count)
        provider.prepare()
        assertEquals("https://01.komiku.asia/read/id/nano-machine/ch334-517833/", provider.discussionUrl)
        assertFalse(urls.contains(stored.url))
        assertEquals(1, urls.count { it.endsWith("/chapters/id/517833") })
        assertEquals(1, urls.count { it.contains("/comics?ids=85049") })
        assertTrue(urls.none { it.contains("/chapters/517833/comments") })
    }

    @Test fun `stored route rejects chapter identity mismatch and optional slug failure does not prevent reading`() = runTest {
        val stored = target.copy(url = "https://01.komiku.asia/85049/517833")
        val wrong = KomikuAsiaCommentsProvider(
            stored,
            object : CommentsTransport {
                override suspend fun get(
                    url: String,
                    referer: String?,
                ): String = """{"id":517834,"comicId":85049,"n":334}"""
            },
        )
        assertTrue(runCatching { wrong.comments(CommentSort.POPULAR, 1) }.isFailure)
        val valid = KomikuAsiaCommentsProvider(
            stored,
            object : CommentsTransport {
                override suspend fun get(url: String, referer: String?): String = when {
                    url.endsWith("/chapters/id/517833") -> """{"id":517833,"comicId":85049,"n":334}"""
                    url.contains("/comics?") -> error("Slug lookup unavailable")
                    url.endsWith("/chapters/334/comments") -> COMMENTS
                    else -> error("Unexpected request")
                }
            },
        )
        assertEquals(3, valid.comments(CommentSort.POPULAR, 1).total)
        assertNull(valid.discussionUrl)
    }

    @Test fun `canonical chapter uses numeric comic identity and chapter number from inert flight data`() = runTest {
        val urls = mutableListOf<String>()
        val provider = KomikuAsiaCommentsProvider(
            target,
            fake { url ->
                urls += url
                when {
                    url == target.url -> html()
                    url.endsWith("/comments") -> COMMENTS
                    else -> """{"upvote":33,"funny":2,"love":3,"surprised":3,"angry":1,"sad":1}"""
                }
            },
        )
        assertTrue(urls.isEmpty())
        val comments = async { provider.comments(CommentSort.POPULAR, 1) }
        val reactions = async { provider.reactions() }
        val page = comments.await()
        assertEquals(3, page.total)
        assertEquals(33, reactions.await().first().count)
        assertEquals(1, urls.count { it == target.url })
        assertTrue(urls.contains("https://01.komiku.asia/api/v2/comics/85049/chapters/334/comments"))
        assertTrue(urls.none { it.contains("/chapters/517833/") })
        val root = page.comments.single()
        assertEquals("Reader", root.author)
        assertEquals("A public comment", root.content)
        assertEquals(4, root.upvotes)
        assertEquals(1, root.downvotes)
        assertEquals("1970-01-01T00:00:01Z", root.createdAt)
        assertEquals(listOf("2", "3"), root.replies.map { it.id })
        assertTrue(root.replies.all { it.replyTo == "1" })
        assertEquals(listOf("https://01.komiku.asia/comments/sample.webp"), root.media)
        assertEquals(root.replies, provider.replies(root))
    }

    @Test fun `all comments are fetched once and best newest oldest with local pages match shipped ordering`() = runTest {
        var reads = 0
        val records = JsonArray(
            (1..21).map { id ->
                buildJsonObject {
                    put("id", id)
                    put("at", id * 1000L)
                    put("user", "Reader")
                    put("text", "Comment")
                    put("up", if (id == 2) 100 else id)
                    put("down", if (id == 2) 99 else 0)
                }
            },
        ).toString()
        val provider = KomikuAsiaCommentsProvider(
            target,
            fake { url ->
                if (url == target.url) {
                    html()
                } else {
                    reads++
                    records
                }
            },
        )
        val best = provider.comments(CommentSort.POPULAR, 1)
        assertEquals("21", best.comments.first().id)
        assertTrue(best.hasMore)
        assertEquals(20, best.comments.size)
        assertEquals("1", provider.comments(CommentSort.OLDEST, 1).comments.first().id)
        assertEquals("21", provider.comments(CommentSort.NEWEST, 1).comments.first().id)
        val last = provider.comments(CommentSort.OLDEST, 2)
        assertEquals(listOf("21"), last.comments.map { it.id })
        assertFalse(last.hasMore)
        assertTrue(provider.comments(CommentSort.OLDEST, 3).comments.isEmpty())
        assertEquals(1, reads)
    }

    @Test fun `mismatched flight identity and error envelopes cannot become successful empty comments`() = runTest {
        val mismatch = KomikuAsiaCommentsProvider(target, fake { html(123) })
        assertTrue(runCatching { mismatch.comments(CommentSort.POPULAR, 1) }.isFailure)
        for (bad in listOf("""{"error":"Denied"}""", "<html>Login</html>")) {
            val provider = KomikuAsiaCommentsProvider(target, fake { if (it == target.url) html() else bad })
            assertTrue(runCatching { provider.comments(CommentSort.NEWEST, 1) }.isFailure)
            assertTrue(runCatching { provider.reactions() }.isFailure)
        }
    }

    private fun fake(response: (String) -> String) = object : CommentsTransport {
        override suspend fun get(url: String, referer: String?): String {
            assertEquals(target.url, referer)
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

    private fun html(chapterId: Int = 517833): String {
        val flight = "8:[\"${'$'}\",\"ReaderView\",null,{\"comic\":{\"id\":85049},\"chapterId\":$chapterId,\"chapter\":334}]\n"
        val chunk = JsonArray(listOf(JsonPrimitive(1), JsonPrimitive(flight)))
        return "<html><script>self.__next_f.push($chunk)</script></html>"
    }

    private companion object {
        const val COMMENTS = """[{"id":1,"user":"Reader","username":"Reader","at":1000,"text":"A public comment","up":4,"down":1,"media":[{"url":"/comments/sample.webp"}],"replies":[{"id":3,"user":"B","at":3000,"text":"Later reply","up":0,"down":0},{"id":2,"user":"A","at":2000,"text":"Earlier reply","up":1,"down":0}]}]"""
    }
}

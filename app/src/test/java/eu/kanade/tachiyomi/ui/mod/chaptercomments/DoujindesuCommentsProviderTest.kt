package eu.kanade.tachiyomi.ui.mod.chaptercomments

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DoujindesuCommentsProviderTest {
    private val chapter = "b19a6f3a-bf85-4711-a6e3-8e415fbe79ec"
    private val rootId = "00000000-0000-0000-0000-000000000001"
    private val target = CommentTarget("https://reader.example/reader/$chapter", "65", "Arbitrary engine label")

    @Test
    fun `encrypted response uses adjacent hour key without reader HTML or images`() = runTest {
        val calls = mutableListOf<String>()
        val provider = DoujindesuCommentsProvider(target, object : CommentsTransport {
            override suspend fun get(url: String, referer: String?): String = error("Missing app identity header")
            override suspend fun getWithHeaders(url: String, referer: String?, headers: Map<String, String>): String {
                calls += url
                assertEquals(target.url, referer)
                assertEquals(mapOf("x-app-secret" to "dfdf72051dbfdc7d76889ebd31324e74"), headers)
                return """{"_enc_resp_":"$ENCRYPTED_HOUR_100"}"""
            }
        }, nowMillis = { 101 * 3_600_000L })
        assertTrue(calls.isEmpty())
        val page = provider.comments(CommentSort.NEWEST, 1)
        assertEquals(listOf("https://reader.example/api/chapters/$chapter/comments?sortBy=Latest&page=1&limit=15"), calls)
        assertEquals(1, page.total)
        assertFalse(page.hasMore)
        assertEquals("<b>A+B</b>", page.comments.single().content)
        assertEquals("Reader", page.comments.single().author)
        assertEquals(4, page.comments.single().upvotes)
        assertFalse(ChapterUpvoteProvider::class.java.isAssignableFrom(provider.javaClass))
    }

    @Test
    fun `plain protocol preserves flat reply tree and independently paginates expanded replies`() = runTest {
        val calls = mutableListOf<String>()
        val provider = DoujindesuCommentsProvider(target, object : CommentsTransport {
            override suspend fun get(url: String, referer: String?) = error("Missing headers")
            override suspend fun getWithHeaders(url: String, referer: String?, headers: Map<String, String>): String {
                calls += url
                return when {
                    url.endsWith("/reactions") -> """{"counts":[{"id":"love-id","name":"Love","icon_url":"https://cdn.example/love.webp","count":16}],"userReactions":[]}"""
                    "/replies?" in url && "page=1" in url -> """{"replies":[{"id":"a","parent_id":"$rootId","content":"First"}],"hasMore":true,"total":2}"""
                    "/replies?" in url -> """{"replies":[{"id":"b","parent_id":"$rootId","content":"Second","image_url":"https://cdn.example/comment.webp"}],"hasMore":false,"total":2}"""
                    else -> """{"comments":[{"id":"$rootId","content":"[spoiler]hidden[/spoiler]","reply_count":2},{"id":"a","parent_id":"$rootId","root_id":"$rootId","content":"First"}],"total":29,"hasMore":true}"""
                }
            }
        })
        val page = provider.comments(CommentSort.POPULAR, 2)
        assertTrue(calls.first().endsWith("sortBy=Popular&page=2&limit=15"))
        assertEquals(29, page.total)
        assertTrue(page.hasMore)
        assertEquals(1, page.comments.size)
        assertEquals("a", page.comments.single().replies.single().id)
        assertTrue(page.comments.single().isSpoiler)
        assertEquals("hidden", page.comments.single().content)
        val replies = provider.replies(page.comments.single())
        assertEquals(listOf("a", "b"), replies.map { it.id })
        assertEquals(listOf("https://cdn.example/comment.webp"), replies[1].media)
        assertEquals(listOf(ChapterReaction("Love", 16)), provider.reactions())
        assertEquals(2, calls.count { "/replies?" in it })
    }

    @Test
    fun `invalid encrypted data is an error instead of an empty chapter`() = runTest {
        val provider = DoujindesuCommentsProvider(target, object : CommentsTransport {
            override suspend fun get(url: String, referer: String?) = """{"_enc_resp_":"001122"}"""
        })
        assertTrue(runCatching { provider.comments(CommentSort.OLDEST, 1) }.isFailure)
    }

    @Test
    fun `engine detection recognizes shell on another domain without accepting arbitrary Vite sites`() {
        assertTrue(DoujindesuCommentsProvider.recognizes("""<title>Doujindesu</title><script src="/assets/index-current.js"></script>"""))
        assertTrue(DoujindesuCommentsProvider.recognizes("const key='doujindesu_device_id'; data._enc_resp_"))
        assertFalse(DoujindesuCommentsProvider.recognizes("""<title>Other reader</title><script src="/assets/index-other.js"></script>"""))
    }

    private companion object {
        // Independently encoded fixture for hour100, using the shipped public wire algorithm.
        const val ENCRYPTED_HOUR_100 = "4d6adacdde56daedb86564ba335f9d6b9179937527f7b50d66f6e899584d004dc95ac07cbdb270d12e35ba052360f5e6dea3bb75a124f1ca127f8c4324568b1767e6ab6eaf98f71db0051a44b571d575901f6f84212cd85b94eef8a471e3f7e5982a7703333fe7a852ae320cdcb8e26cbfd52d840630384e79b1de0f21d618fb091e64f29468e1045e969645e9f1d575911eef04a19d2328fb1e84213d16183b499e24728d39e5c19469ac65f3812fb35cb6c0951576ddc675be03c1c9834672f5e2d1112e0f123cad77e9b53352dd6be028900373d436a86c72f8eea316183ac447cae3e8c0c70d69"
    }
}

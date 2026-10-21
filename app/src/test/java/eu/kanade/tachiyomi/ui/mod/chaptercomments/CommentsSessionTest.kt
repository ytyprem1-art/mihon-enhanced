// Raw protocol fixtures intentionally preserve their original single-line payloads.
@file:Suppress("ktlint:standard:max-line-length")

package eu.kanade.tachiyomi.ui.mod.chaptercomments

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class CommentsSessionTest {
    @Test fun `closing popup cancels response body in progress and leaves reader client alone`() = runBlocking {
        ServerSocket(0).use { server ->
            val headersSent = CountDownLatch(1)
            val finish = CountDownLatch(1)
            val worker = thread(isDaemon = true) {
                runCatching {
                    server.accept().use { socket ->
                        socket.soTimeout = 5000
                        val input = socket.getInputStream().bufferedReader()
                        while (!input.readLine().isNullOrBlank()) { /* request headers */ }
                        socket.getOutputStream().apply {
                            write(
                                "HTTP/1.1 200 OK\r\nContent-Length: 1000000\r\nConnection: close\r\n\r\nx".toByteArray(),
                            )
                            flush()
                        }
                        headersSent.countDown()
                        finish.await(5, TimeUnit.SECONDS)
                    }
                }
            }
            val reader = OkHttpClient()
            val session = CommentsSession(reader)
            try {
                val result =
                    async(Dispatchers.IO) {
                        runCatching { session.get("http://127.0.0.1:${server.localPort}/chapter31") }
                    }
                assertTrue(headersSent.await(3, TimeUnit.SECONDS))
                session.close()
                assertTrue(withTimeout(3000) { result.await() }.isFailure)
                assertNotSame(reader.dispatcher, session.client.dispatcher)
                assertFalse(reader.dispatcher.executorService.isShutdown)
                assertTrue(session.client.dispatcher.executorService.isShutdown)
                assertTrue(runCatching { session.get("http://127.0.0.1:${server.localPort}/closed") }.isFailure)
                session.close() // idempotent
            } finally {
                session.close()
                finish.countDown()
                worker.join(1000)
                reader.dispatcher.executorService.shutdown()
                reader.connectionPool.evictAll()
            }
        }
    }

    @Test fun `body rendering strips active markup preserves images and collapses spoilers`() {
        val value =
            commentBody(
                ChapterComment(
                    "1",
                    "User",
                    "<p>Hello<br>world</p><script>evil()</script><span class='spoiler'>secret</span><img src='https://example.org/meme.webp'><img src='javascript:evil'>",
                ),
            )
        assertTrue(value.spoiler)
        assertTrue(value.text.contains("Hello\nworld"))
        assertFalse(value.text.contains("evil"))
        assertEquals(listOf("https://example.org/meme.webp"), value.media)
    }

    @Test fun `nested replies remain readable and duplicate IDs do not repeat`() {
        val grandchild = ChapterComment("3", "C", "nested", replyTo = "2")
        val child = ChapterComment("2", "B", "reply", replies = listOf(grandchild))
        val flat = flattenCommentReplies(listOf(child, grandchild), "A")
        assertEquals(listOf("2", "3"), flat.map { it.id })
        assertEquals(listOf("A", "B"), flat.map { it.replyTo })
    }
}

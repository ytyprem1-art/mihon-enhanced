package eu.kanade.tachiyomi.ui.mod.panelshare

import eu.kanade.tachiyomi.ui.mod.chatgif.ChatGifStore
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.util.Base64

class ChatGifTest {
    private val gif = Base64.getDecoder().decode("R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7")

    @Test fun realGifAndSizeBoundaryAreValidatedWithoutTranscodingAnimation() {
        val draft = ChatGifStore.validate(gif)
        assertArrayEquals(gif, draft.bytes)
        assertEquals(1, draft.width)
        assertEquals(1, draft.height)
        assertArrayEquals(gif, ChatGifStore.readBounded(gif.inputStream()))
        assertEquals(
            ChatGifStore.MAX_BYTES,
            ChatGifStore.readBounded(ByteArray(ChatGifStore.MAX_BYTES).inputStream()).size,
        )
        assertThrows(IllegalArgumentException::class.java) {
            ChatGifStore.readBounded(ByteArray(ChatGifStore.MAX_BYTES + 1).inputStream())
        }
    }

    @Test fun malformedTruncatedAndExcessiveResolutionAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            ChatGifStore.validate("<html>not gif</html>".toByteArray())
        }
        assertThrows(IllegalArgumentException::class.java) { ChatGifStore.validate(gif.copyOf(gif.size - 3)) }
        assertThrows(IllegalArgumentException::class.java) {
            ChatGifStore.validate(
                gif.clone().apply {
                    this[6] =
                        0xff.toByte()
                    this[7] = 0x7f
                },
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ChatGifStore.validate(
                gif.clone().apply {
                    this[
                        gif.indexOf(0x2c.toByte()) +
                            5,
                    ] =
                        0
                },
            )
        }
    }

    @Test fun onlyDirectPublicGifCdnLinksAreShared() {
        assertNotNull(ChatGifStore.publicGifUrl("https://media.tenor.com/example/hello.gif"))
        assertNotNull(ChatGifStore.publicGifUrl("https://media2.giphy.com/media/example/giphy.gif"))
        listOf(
            "content://keyboard/hello.gif",
            "http://media.tenor.com/a.gif",
            "https://media.tenor.com.evil.test/a.gif",
            "https://user:pass@media.tenor.com/a.gif",
            "https://media.tenor.com:444/a.gif",
            "https://tenor.com/view/hello",
            "https://media.tenor.com/a.gif#secret",
        ).forEach {
            assertNull(ChatGifStore.publicGifUrl(it), it)
        }
    }
}

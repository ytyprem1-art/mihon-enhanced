package eu.kanade.tachiyomi.ui.mod.panelshare

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ImgUpscaleProtocolTest {
    @Test
    fun `supports exactly two and four times`() {
        ImgUpscaleProtocol.requireScale(2)
        ImgUpscaleProtocol.requireScale(4)
        assertThrows(IllegalArgumentException::class.java) { ImgUpscaleProtocol.requireScale(8) }
    }

    @Test
    fun `parses observed anonymous upload response`() {
        assertEquals("sVhotE5G", ImgUpscaleProtocol.taskId("""{"taskId":"sVhotE5G","imageId":"","raw":{"code":200}}"""))
    }

    @Test
    fun `invalid task ids and missing task fail before forming request`() {
        assertThrows(IllegalArgumentException::class.java) { ImgUpscaleProtocol.taskId("""{"taskId":"../x\""}""") }
        assertThrows(IllegalArgumentException::class.java) { ImgUpscaleProtocol.taskId("""{"error":"Limit reached"}""") }
        assertThrows(IllegalArgumentException::class.java) { ImgUpscaleProtocol.taskId("""{"taskId":null}""") }
    }

    @Test
    fun `waiting does not download incomplete results directory`() {
        assertNull(ImgUpscaleProtocol.resultUrl("""{"status":"waiting","downloadUrls":["https://imgupscaler.com/results/"]}"""))
    }

    @Test
    fun `parses observed completed response`() {
        val url = "https://get1.imglarger.com/upscaler/results/sVhotE5G_2x.jpg"
        assertEquals(url, ImgUpscaleProtocol.resultUrl("""{"status":"success","downloadUrls":["$url"]}"""))
    }

    @Test
    fun `server failures missing image and changed status are errors`() {
        listOf(
            """{"status":"failed","error":"Processing failed"}""",
            """{"status":"success","downloadUrls":[]}""",
            """{"status":"something-new"}""",
        ).forEach { body -> assertThrows(IllegalStateException::class.java) { ImgUpscaleProtocol.resultUrl(body) } }
    }

    @Test
    fun `only accepts https service result hosts and actual file paths`() {
        assertTrue(ImgUpscaleProtocol.isAllowedResultUrl("https://get2.imglarger.com/upscaler/results/test_4x.jpg"))
        listOf(
            "http://get1.imglarger.com/test.jpg", "https://imglarger.com.evil.example/test.jpg",
            "https://evil.example/imglarger.com/test.jpg", "file:///sdcard/test.jpg",
            "https://user:password@get1.imglarger.com/test.jpg", "https://imgupscaler.com/results/",
            "https://get1.imglarger.com:8080/test.jpg",
        ).forEach { url -> assertFalse(ImgUpscaleProtocol.isAllowedResultUrl(url), url) }
    }
}

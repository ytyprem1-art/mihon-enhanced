package eu.kanade.tachiyomi.ui.mod

import android.graphics.Bitmap
import android.util.Base64
import androidx.test.platform.app.InstrumentationRegistry
import coil3.decode.DataSource
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import eu.kanade.tachiyomi.ui.mod.chaptercomments.CommentImages
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicInteger

class CommentImagesDeviceTest {
    @Test fun scrollingReusesPngAndGifBytesAndPopupCloseReleasesCache() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        val png = ByteArrayOutputStream().use { stream -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream); stream.toByteArray() }
        bitmap.recycle()
        val gif = Base64.decode("R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7", Base64.DEFAULT)
        val requests = AtomicInteger()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests.incrementAndGet()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body((if (chain.request().url.encodedPath.endsWith("gif")) gif else png).toResponseBody())
                .build()
        }.build()
        val media = CommentImages(context, client)
        try {
            for ((index, extension) in listOf("png", "gif").withIndex()) {
                val url = "https://comment-images.example/sample.$extension"
                val thumbnail = ImageRequest.Builder(context).data(url).size(64, 64).build()
                assertTrue(media.loader.execute(thumbnail) is SuccessResult)
                assertEquals(index + 1, requests.get())
                // Recreate an offscreen list item after decoded memory was reclaimed.
                media.loader.memoryCache!!.clear()
                val returned = media.loader.execute(thumbnail)
                assertTrue(returned is SuccessResult)
                assertEquals(DataSource.DISK, (returned as SuccessResult).dataSource)
                assertEquals(index + 1, requests.get())
                // Enlarging uses the original cached file with a different decode size.
                media.loader.memoryCache!!.clear()
                assertTrue(media.loader.execute(ImageRequest.Builder(context).data(url).size(1024, 12000).build()) is SuccessResult)
                assertEquals(index + 1, requests.get())
            }
            instrumentation.runOnMainSync { media.close() }
            withTimeout(5000) { media.cleanupComplete.await() }
            assertEquals(0L, media.loader.memoryCache!!.size)
            withContext(Dispatchers.IO) { assertFalse(media.directory.exists()) }
            media.close() // Safe when controller.close and Compose disposal both run.
        } finally {
            media.close()
            client.dispatcher.executorService.shutdown()
            withContext(Dispatchers.IO) { client.connectionPool.evictAll() }
        }
    }
}

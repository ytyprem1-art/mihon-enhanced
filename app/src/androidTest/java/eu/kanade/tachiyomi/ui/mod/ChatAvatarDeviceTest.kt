package eu.kanade.tachiyomi.ui.mod

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import android.util.Base64
import android.widget.ImageView
import androidx.test.platform.app.InstrumentationRegistry
import com.github.chrisbanes.photoview.PhotoView
import eu.kanade.tachiyomi.ui.mod.chatprofile.ChatAvatarPhoto
import eu.kanade.tachiyomi.ui.mod.chatprofile.captureAvatar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

class ChatAvatarDeviceTest {
    @Test fun hdPhotoAndSmallAvatarKeepTheSameCropWithinSeparateLimits() {
        val square = Bitmap.createBitmap(ChatAvatarPhoto.HD_SIDE, ChatAvatarPhoto.HD_SIDE, Bitmap.Config.ARGB_8888)
        try {
            Canvas(square).apply {
                drawColor(android.graphics.Color.RED)
                drawRect(0f, 384f, 768f, 768f, Paint().apply { color = android.graphics.Color.BLUE })
            }
            val upload = ChatAvatarPhoto.prepareUpload(square)
            assertTrue(upload.photo.size in 1..ChatAvatarPhoto.MAX_HD_BYTES)
            assertTrue(upload.thumbnail.length <= ChatAvatarPhoto.MAX_ENCODED)
            val hd = ChatAvatarPhoto.decodeHd(upload.photo)
            val thumbnail = requireNotNull(ChatAvatarPhoto.decode(upload.thumbnail))
            try {
                assertEquals(768, hd.width)
                assertEquals(768, hd.height)
                assertEquals(96, thumbnail.width)
                assertTrue(android.graphics.Color.red(hd.getPixel(384, 100)) > 240)
                assertTrue(android.graphics.Color.blue(hd.getPixel(384, 650)) > 240)
                assertTrue(android.graphics.Color.red(thumbnail.getPixel(48, 12)) > 240)
                assertTrue(android.graphics.Color.blue(thumbnail.getPixel(48, 82)) > 240)
            } finally {
                hd.recycle()
                thumbnail.recycle()
            }
            assertThrows(IllegalArgumentException::class.java) {
                ChatAvatarPhoto.decodeHd(ByteArray(ChatAvatarPhoto.MAX_HD_BYTES + 1))
            }
            assertThrows(IllegalArgumentException::class.java) {
                ChatAvatarPhoto.decodeHd("invalid photo".toByteArray())
            }
            val pixels = IntArray(768 * 768)
            val random = java.util.Random(4)
            for (index in pixels.indices) pixels[index] = random.nextInt() or 0xff000000.toInt()
            square.setPixels(pixels, 0, 768, 0, 0, 768, 768)
            val detailed = ChatAvatarPhoto.prepareUpload(square)
            assertTrue(detailed.photo.size <= ChatAvatarPhoto.MAX_HD_BYTES)
            assertNotEquals(upload.version, detailed.version)
        } finally {
            square.recycle()
        }
    }

    @Test fun suppliedProblemPhotoCanBeDecodedAndCropped(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "avatar-regression-input.jpg")
        org.junit.Assume.assumeTrue("Optional user-supplied photo is only copied to the test device", file.exists())
        val preview = ChatAvatarPhoto.loadForCrop(context, Uri.fromFile(file))
        try {
            assertTrue(preview.width in 1..1024 && preview.height in 1..1024)
            val cropped = withContext(Dispatchers.Main) {
                PhotoView(context).let { view ->
                    view.scaleType = ImageView.ScaleType.CENTER_CROP
                    view.setImageBitmap(preview)
                    view.layout(0, 0, 320, 320)
                    captureAvatar(view).also { view.setImageDrawable(null) }
                }
            }
            try {
                assertNotNull(ChatAvatarPhoto.decode(ChatAvatarPhoto.encodeSquare(cropped)))
            } finally {
                cropped.recycle()
            }
        } finally {
            preview.recycle()
        }
    }

    @Test fun largePhotoUsesBoundedPreviewAndCorruptPhotoReturnsError(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "avatar-large-${UUID.randomUUID()}.png")
        try {
            val large = Bitmap.createBitmap(4096, 3072, Bitmap.Config.ARGB_8888)
            try {
                large.eraseColor(android.graphics.Color.MAGENTA)
                file.outputStream().use { assertTrue(large.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            } finally {
                large.recycle()
            }
            val preview = ChatAvatarPhoto.loadForCrop(context, Uri.fromFile(file))
            assertTrue(preview.width in 1..1024 && preview.height in 1..1024)
            assertEquals(android.graphics.Color.MAGENTA, preview.getPixel(preview.width / 2, preview.height / 2))
            preview.recycle()
            file.writeText("this is not a photo")
            var rejected = false
            try {
                ChatAvatarPhoto.loadForCrop(context, Uri.fromFile(file))
            } catch (
                _: IllegalArgumentException,
            ) {
                rejected = true
            }
            assertTrue("Corrupt photo must return a recoverable error", rejected)
            assertFalse(context.cacheDir.listFiles().orEmpty().any { it.name.startsWith("chat-avatar-") })
        } finally {
            file.delete()
        }
    }

    @Test fun pickerImageBecomesBoundedSquareAndInvalidRemotePhotosFallBack(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "avatar-test-${UUID.randomUUID()}.png")
        val bitmap = Bitmap.createBitmap(600, 1800, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(android.graphics.Color.RED)
            Canvas(bitmap).apply {
                drawRect(0f, 600f, 600f, 1200f, Paint().apply { color = android.graphics.Color.GREEN })
                drawRect(0f, 1200f, 600f, 1800f, Paint().apply { color = android.graphics.Color.BLUE })
            }
            file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            val source = ChatAvatarPhoto.loadForCrop(context, Uri.fromFile(file))
            val cropped = withContext(Dispatchers.Main) {
                val view = PhotoView(context).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    setImageBitmap(source)
                    layout(0, 0, 256, 256)
                }
                val center = captureAvatar(view)
                assertEquals(android.graphics.Color.GREEN, center.getPixel(48, 48))
                center.recycle()
                // Repositioning the photo selects its upper section, not the automatic center.
                view.setDisplayMatrix(Matrix().apply { postTranslate(0f, 256f) })
                val upper = captureAvatar(view)
                assertEquals(android.graphics.Color.RED, upper.getPixel(48, 48))
                view.setImageDrawable(null)
                upper
            }
            val encoded = ChatAvatarPhoto.encodeSquare(cropped)
            cropped.recycle()
            assertTrue(encoded.length <= ChatAvatarPhoto.MAX_ENCODED)
            assertTrue(Base64.decode(encoded, Base64.NO_WRAP).size <= ChatAvatarPhoto.MAX_BYTES)
            val decoded = requireNotNull(ChatAvatarPhoto.decode(encoded))
            assertEquals(96, decoded.width)
            assertEquals(96, decoded.height)
            decoded.recycle()
            assertNull(ChatAvatarPhoto.decode(null))
            assertNull(ChatAvatarPhoto.decode("invalid image"))
            assertNull(ChatAvatarPhoto.decode("A".repeat(ChatAvatarPhoto.MAX_ENCODED + 1)))
            // Small compressed input must still reject oversized decoded dimensions.
            val oversized = ByteArrayOutputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                it.toByteArray()
            }
            assertNull(ChatAvatarPhoto.decode(Base64.encodeToString(oversized, Base64.NO_WRAP)))
        } finally {
            bitmap.recycle()
            file.delete()
        }
    }
}

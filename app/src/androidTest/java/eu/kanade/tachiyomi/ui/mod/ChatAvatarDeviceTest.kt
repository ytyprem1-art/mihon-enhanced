package eu.kanade.tachiyomi.ui.mod

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import android.widget.ImageView
import android.util.Base64
import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.tachiyomi.ui.mod.chatprofile.ChatAvatarPhoto
import eu.kanade.tachiyomi.ui.mod.chatprofile.captureAvatar
import com.github.chrisbanes.photoview.PhotoView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

class ChatAvatarDeviceTest {
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
            val oversized = ByteArrayOutputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it); it.toByteArray() }
            assertNull(ChatAvatarPhoto.decode(Base64.encodeToString(oversized, Base64.NO_WRAP)))
        } finally {
            bitmap.recycle()
            file.delete()
        }
    }
}

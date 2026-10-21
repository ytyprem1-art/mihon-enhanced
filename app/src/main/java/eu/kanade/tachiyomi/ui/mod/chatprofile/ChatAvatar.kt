package eu.kanade.tachiyomi.ui.mod.chatprofile

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.asDrawable
import coil3.imageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.allowHardware
import eu.kanade.tachiyomi.util.system.getBitmapOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/** A tiny image lives in the existing owner-only presence document, not in every message. */
object ChatAvatarPhoto {
    const val SIDE = 96
    const val MAX_BYTES = 8 * 1024
    const val MAX_ENCODED = ((MAX_BYTES + 2) / 3) * 4

    suspend fun loadForCrop(context: Context, uri: Uri): Bitmap = withContext(Dispatchers.IO) {
        val request = ImageRequest.Builder(context).data(uri).size(1024, 1024).allowHardware(false)
            .memoryCachePolicy(CachePolicy.DISABLED).diskCachePolicy(CachePolicy.DISABLED).build()
        requireNotNull(context.imageLoader.execute(request).image?.asDrawable(context.resources)?.getBitmapOrNull()) {
            "Could not open this image. Please choose another photo."
        }
    }

    fun encodeSquare(square: Bitmap): String {
        require(square.width == SIDE && square.height == SIDE)
        for (quality in listOf(85, 70, 55, 40, 25)) {
            val bytes = ByteArrayOutputStream().use {
                check(square.compress(Bitmap.CompressFormat.JPEG, quality, it))
                it.toByteArray()
            }
            if (bytes.size <= MAX_BYTES) return Base64.encodeToString(bytes, Base64.NO_WRAP)
        }
        error("This photo could not be compressed. Please choose another photo.")
    }

    fun decode(encoded: String?): Bitmap? = runCatching {
        require(!encoded.isNullOrBlank() && encoded.length <= MAX_ENCODED)
        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
        require(bytes.size in 1..MAX_BYTES)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outWidth in 1..SIDE && bounds.outHeight in 1..SIDE)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }.getOrNull()
}

@Composable
fun ChatAvatar(username: String, photo: String?, size: Dp = 32.dp, color: Color = MaterialTheme.colorScheme.primaryContainer) {
    val bitmap by produceState<Bitmap?>(null, photo) {
        value = null
        value = withContext(Dispatchers.Default) { ChatAvatarPhoto.decode(photo) }
    }
    Surface(modifier = Modifier.size(size), shape = CircleShape, color = color,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline)) {
        val image = bitmap
        if (image != null) {
            Image(image.asImageBitmap(), "$username profile photo", contentScale = ContentScale.Crop)
        } else {
            Box(contentAlignment = Alignment.Center) {
                Text(username.take(1).uppercase(), style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

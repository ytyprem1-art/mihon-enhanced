package eu.kanade.tachiyomi.ui.mod.chatprofile

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
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
import coil3.size.Precision
import coil3.size.Scale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/** A tiny image lives in the existing owner-only presence document, not in every message. */
object ChatAvatarPhoto {
    const val SIDE = 96
    const val MAX_BYTES = 8 * 1024
    const val MAX_ENCODED = ((MAX_BYTES + 2) / 3) * 4
    const val HD_SIDE = 768
    const val MAX_HD_BYTES = 200 * 1024

    fun prepareUpload(square: Bitmap): ChatAvatarUpload {
        require(square.width == HD_SIDE && square.height == HD_SIDE)
        val thumbnail = Bitmap.createScaledBitmap(square, SIDE, SIDE, true)
        val encoded = try { encodeSquare(thumbnail) } finally { thumbnail.recycle() }
        for (quality in listOf(92, 88, 84, 80, 74, 68, 60, 50, 40, 30)) {
            val bytes = ByteArrayOutputStream().use {
                check(square.compress(Bitmap.CompressFormat.JPEG, quality, it))
                it.toByteArray()
            }
            if (bytes.size <= MAX_HD_BYTES) return ChatAvatarUpload(encoded, bytes, java.util.UUID.randomUUID().toString())
        }
        error("This photo could not be compressed. Please choose another photo.")
    }

    fun decodeHd(bytes: ByteArray): Bitmap {
        require(bytes.size in 1..MAX_HD_BYTES) { "Profile photo is too large." }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outWidth == HD_SIDE && bounds.outHeight == HD_SIDE) { "Profile photo has invalid dimensions." }
        return requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size)) { "Image is not supported." }
    }

    suspend fun loadForCrop(context: Context, uri: Uri): Bitmap = withContext(Dispatchers.IO) {
        val file = java.io.File.createTempFile("chat-avatar-", ".image", context.cacheDir)
        try {
            // Bound the input before decoding; never materialize the original drawable at its intrinsic size.
            context.contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "Foto tidak bisa dibuka. Pilih ulang fotonya." }
                file.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    var total = 0
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= 64 * 1024 * 1024) { "File foto melebihi 64 MB. Coba simpan ulang sebagai JPG." }
                        output.write(buffer, 0, count)
                    }
                }
            }
            val request = ImageRequest.Builder(context).data(file).size(1024, 1024).allowHardware(false)
                .precision(Precision.EXACT).scale(Scale.FIT)
                .memoryCachePolicy(CachePolicy.DISABLED).diskCachePolicy(CachePolicy.DISABLED).build()
            val image = requireNotNull(context.imageLoader.execute(request).image) {
                "Image is not supported. Please choose another photo."
            }
            require(image.width > 0 && image.height > 0) { "Ukuran foto tidak valid." }
            val ratio = minOf(1f, 1024f / image.width, 1024f / image.height)
            val bitmap = Bitmap.createBitmap(maxOf(1, (image.width * ratio).toInt()), maxOf(1, (image.height * ratio).toInt()), Bitmap.Config.ARGB_8888)
            try {
                image.asDrawable(context.resources).apply { setBounds(0, 0, bitmap.width, bitmap.height); draw(android.graphics.Canvas(bitmap)) }
                bitmap
            } catch (e: Throwable) { bitmap.recycle(); throw e }
        } catch (e: CancellationException) { throw e }
        catch (e: OutOfMemoryError) {
            throw IllegalArgumentException("Memori tidak cukup untuk foto ini. Coba foto beresolusi lebih kecil.")
        } finally {
            file.delete()
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
fun ChatAvatar(username: String, photo: String?, size: Dp = 32.dp, color: Color = MaterialTheme.colorScheme.primaryContainer, onPhotoClick: ((Bitmap) -> Unit)? = null) {
    val bitmap by produceState<Bitmap?>(null, photo) {
        value = null
        value = withContext(Dispatchers.Default) { ChatAvatarPhoto.decode(photo) }
    }
    Surface(modifier = Modifier.size(size).then(
        if (onPhotoClick != null && bitmap != null) Modifier.clickable(onClickLabel = "Open profile photo") { bitmap?.let(onPhotoClick) }
        else Modifier,
    ), shape = CircleShape, color = color,
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

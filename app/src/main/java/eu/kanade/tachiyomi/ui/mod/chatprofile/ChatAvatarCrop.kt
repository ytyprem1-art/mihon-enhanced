package eu.kanade.tachiyomi.ui.mod.chatprofile

import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.widget.ImageView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.github.chrisbanes.photoview.PhotoView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Fixed square viewport: pan/zoom changes the photo, never the crop dimensions. */
@Composable
fun ChatAvatarCrop(uri: Uri, onDismiss: () -> Unit, onConfirm: (ChatAvatarUpload) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val view =
        remember(uri) {
            PhotoView(context).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setScaleLevels(1f, 3f, 8f)
            }
        }
    var loaded by remember(uri) { mutableStateOf(false) }
    var busy by remember(uri) { mutableStateOf(false) }
    var error by remember(uri) { mutableStateOf<String?>(null) }
    LaunchedEffect(uri) {
        try {
            val image = ChatAvatarPhoto.loadForCrop(context, uri)
            view.setImageBitmap(image)
            loaded = true
        } catch (
            e: CancellationException,
        ) {
            throw e
        } catch (
            e: OutOfMemoryError,
        ) {
            error = "Memori tidak cukup. Coba foto yang lebih kecil."
        } catch (
            e: Exception,
        ) {
            error = e.message ?: "Gambar tidak bisa dibuka."
        }
    }
    DisposableEffect(view) { onDispose { view.setImageDrawable(null) } }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Atur foto profil") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Geser gambar untuk mengatur posisi. Cubit untuk zoom.")
                Box(Modifier.fillMaxWidth().aspectRatio(1f), contentAlignment = Alignment.Center) {
                    AndroidView(factory = { view }, modifier = Modifier.fillMaxSize())
                    Box(Modifier.fillMaxSize().border(BorderStroke(1.dp, Color.White), CircleShape))
                    if (!loaded && error == null) CircularProgressIndicator()
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(enabled = loaded && !busy, onClick = {
                scope.launch {
                    busy = true
                    error = null
                    try {
                        val bitmap = captureAvatar(view, ChatAvatarPhoto.HD_SIDE)
                        val encoded = try {
                            withContext(Dispatchers.Default) { ChatAvatarPhoto.prepareUpload(bitmap) }
                        } finally {
                            bitmap.recycle()
                        }
                        onConfirm(encoded)
                    } catch (
                        e: CancellationException,
                    ) {
                        throw e
                    } catch (
                        e: OutOfMemoryError,
                    ) {
                        error = "Memori tidak cukup. Coba foto yang lebih kecil."
                    } catch (
                        e: Exception,
                    ) {
                        error = e.message ?: "Foto tidak bisa diproses."
                    } finally {
                        busy = false
                    }
                }
            }) { Text("Gunakan foto") }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Batal") } },
    )
}

internal fun captureAvatar(view: PhotoView, side: Int = ChatAvatarPhoto.SIDE): Bitmap {
    require(side in 1..ChatAvatarPhoto.HD_SIDE)
    require(view.width > 0 && view.height > 0 && view.drawable != null) { "Gambar belum siap." }
    return Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888).also { bitmap ->
        Canvas(bitmap).apply {
            drawColor(android.graphics.Color.WHITE)
            scale(side.toFloat() / view.width, side.toFloat() / view.height)
            view.draw(this)
        }
    }
}

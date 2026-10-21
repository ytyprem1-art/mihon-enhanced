package eu.kanade.tachiyomi.ui.mod.chatprofile

import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.widget.ImageView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
fun ChatAvatarCrop(uri: Uri, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val view = remember(uri) { PhotoView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setScaleLevels(1f, 3f, 8f) } }
    var loaded by remember(uri) { mutableStateOf(false) }
    var busy by remember(uri) { mutableStateOf(false) }
    var error by remember(uri) { mutableStateOf<String?>(null) }
    LaunchedEffect(uri) {
        try {
            val image = ChatAvatarPhoto.loadForCrop(context, uri)
            view.setImageBitmap(image)
            loaded = true
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: "Gambar tidak bisa dibuka." }
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
                    busy = true; error = null
                    try {
                        val bitmap = captureAvatar(view)
                        val encoded = try { withContext(Dispatchers.Default) { ChatAvatarPhoto.encodeSquare(bitmap) } }
                        finally { bitmap.recycle() }
                        onConfirm(encoded)
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { error = e.message ?: "Foto tidak bisa diproses." }
                    finally { busy = false }
                }
            }) { Text("Gunakan foto") }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Batal") } },
    )
}

internal fun captureAvatar(view: PhotoView): Bitmap {
    require(view.width > 0 && view.height > 0 && view.drawable != null) { "Gambar belum siap." }
    return Bitmap.createBitmap(ChatAvatarPhoto.SIDE, ChatAvatarPhoto.SIDE, Bitmap.Config.ARGB_8888).also { bitmap ->
        Canvas(bitmap).apply {
            drawColor(android.graphics.Color.WHITE)
            scale(ChatAvatarPhoto.SIDE.toFloat() / view.width, ChatAvatarPhoto.SIDE.toFloat() / view.height)
            view.draw(this)
        }
    }
}

package eu.kanade.tachiyomi.ui.mod.chaptercomments

import android.graphics.drawable.Animatable
import android.view.GestureDetector
import android.view.MotionEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.ImageLoader
import coil3.asDrawable
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import com.github.chrisbanes.photoview.PhotoView

/** Preview owns only its active request/drawable. No download, gallery write or persistent cache. */
@Composable
fun CommentImagePreview(url: String, images: ImageLoader, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var loading by remember(url) { mutableStateOf(true) }
    var error by remember(url) { mutableStateOf(false) }
    var retry by remember(url) { mutableIntStateOf(0) }
    val view = remember(url) {
        PhotoView(context).apply {
            setBackgroundColor(android.graphics.Color.BLACK)
            setScaleLevels(1f, 3f, 64f)
            setOnDoubleTapListener(object : GestureDetector.SimpleOnGestureListener() {
                override fun onDoubleTap(event: MotionEvent): Boolean {
                    val image = drawable ?: return true
                    val fit = minOf(
                        width.toFloat() / image.intrinsicWidth.coerceAtLeast(1),
                        height.toFloat() / image.intrinsicHeight.coerceAtLeast(1),
                    )
                    val fillWidth = (width / (image.intrinsicWidth * fit)).coerceIn(2f, 32f)
                    setScale(if (scale > 1.05f) 1f else fillWidth, event.x, event.y, true)
                    return true
                }
            })
        }
    }
    DisposableEffect(url, retry) {
        loading = true
        error = false
        val request = ImageRequest.Builder(context).data(url)
            // Keep large/long images readable while bounding the decoded bitmap.
            .size(1024, 12000)
            // Reuse the original bytes even when a thumbnail was decoded at a smaller size.
            .memoryCachePolicy(CachePolicy.DISABLED).diskCachePolicy(CachePolicy.ENABLED)
            .target(
                onSuccess = { image ->
                    val drawable = image.asDrawable(context.resources)
                    view.setImageDrawable(drawable)
                    (drawable as? Animatable)?.start()
                    loading = false
                },
                onError = {
                    loading = false
                    error = true
                },
            ).build()
        val disposable = images.enqueue(request)
        onDispose {
            disposable.dispose()
            (view.drawable as? Animatable)?.stop()
            view.setImageDrawable(null)
        }
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            AndroidView(factory = { view }, modifier = Modifier.fillMaxSize())
            if (loading) CircularProgressIndicator(Modifier.align(Alignment.Center))
            if (error) {
                Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Gambar gagal dimuat.", color = Color.White)
                    TextButton(onClick = { retry++ }) { Text("Coba lagi") }
                }
            }
            IconButton(onClick = onDismiss, modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding()) {
                Icon(Icons.Outlined.Close, "Tutup gambar", tint = Color.White)
            }
        }
    }
}

package eu.kanade.tachiyomi.ui.mod.panelshare

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.github.chrisbanes.photoview.PhotoView

/** Reuse the decoded chat bitmap; opening never downloads or writes an image to the gallery. */
@Composable
fun ChatPanelImagePreview(bitmap: Bitmap, onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            ZoomableChatImage(bitmap, Modifier.fillMaxSize())
            IconButton(onClick = onDismiss, modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(4.dp)) {
                Icon(Icons.Outlined.Close, "Close image", tint = Color.White)
            }
        }
    }
}

/** The same image surface can remain inside a dialog while an HD photo finishes loading. */
@Composable
fun ZoomableChatImage(bitmap: Bitmap, modifier: Modifier = Modifier) {
    AndroidView(
        factory = { context ->
            PhotoView(context).apply {
                setBackgroundColor(android.graphics.Color.BLACK)
                setScaleLevels(1f, 3f, 10f)
                setImageBitmap(bitmap)
            }
        },
        modifier = modifier,
        onRelease = { it.setImageDrawable(null) },
    )
}

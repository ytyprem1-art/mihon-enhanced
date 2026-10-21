package eu.kanade.tachiyomi.ui.mod.chatprofile

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import eu.kanade.tachiyomi.ui.mod.panelshare.ZoomableChatImage
import kotlinx.coroutines.CancellationException

@Composable
fun ChatProfilePhotoPreview(uid: String, version: String?, thumbnail: Bitmap, onDismiss: () -> Unit) {
    var image by remember(uid, version) { mutableStateOf(if (version == null) thumbnail else ChatProfilePhotoStore.cached(uid, version)) }
    var error by remember(uid, version) { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(uid, version, attempt) {
        if (image != null || version == null) return@LaunchedEffect
        error = null
        try { image = ChatProfilePhotoStore.load(uid, version) }
        catch (e: CancellationException) { throw e }
        catch (e: OutOfMemoryError) { error = "Not enough memory to open this photo." }
        catch (e: Exception) { error = e.message ?: "Could not load profile photo." }
    }
    val photo = image
    Dialog(onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            if (photo != null) ZoomableChatImage(photo, Modifier.fillMaxSize()) else Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (error == null) CircularProgressIndicator() else {
                    Text(error.orEmpty(), color = Color.White)
                    TextButton(onClick = { attempt++ }) { Text("Retry") }
                    TextButton(onClick = { image = thumbnail }) { Text("Show small photo") }
                }
            }
            IconButton(onClick = onDismiss, modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(4.dp)) {
                Icon(Icons.Outlined.Close, "Close image", tint = Color.White)
            }
        }
    }
}

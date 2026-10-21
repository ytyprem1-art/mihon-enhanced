package eu.kanade.tachiyomi.ui.mod.panelshare

import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.ui.chat.ChatMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** A bounded in-memory cache; Firestore's disk cache has its own size/eviction policy. */
private object PanelCache {
    val lock = Mutex()
    val images = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }
}

@Composable
fun ChatPanelCard(message: ChatMessage, onMangaClick: (ChatMessage) -> Unit, captionColor: Color = MaterialTheme.colorScheme.onSurface) {
    var revealed by rememberSaveable(message.id) { mutableStateOf(false) }
    val hidden = message.isSpoiler && !revealed
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Card(Modifier.width(240.dp)) {
            Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (hidden) {
                    Box(
                        Modifier.fillMaxWidth().height(180.dp).clickable(onClickLabel = "Reveal spoiler") { revealed = true },
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("SPOILER", style = MaterialTheme.typography.titleMedium)
                            Text("Tap to reveal image and message", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                } else {
                    ChatPanelImage(message.id)
                }
                Column(Modifier.fillMaxWidth().clickable { onMangaClick(message) }) {
                    Text(message.mangaTitle.orEmpty(), style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(message.chapterName.orEmpty(), style = MaterialTheme.typography.bodySmall)
                    Text(message.sourceName.orEmpty(), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (!hidden && message.text.isNotBlank() && !message.text.startsWith("Shared a manga:")) {
            Text(message.text, style = MaterialTheme.typography.bodyMedium, color = captionColor, modifier = Modifier.padding(vertical = 4.dp))
        }
    }
}

/** Only composed after reveal, so cached images cannot flash through the spoiler cover. */
@Composable
private fun ChatPanelImage(messageId: String) {
    var image by remember(messageId) { mutableStateOf<Bitmap?>(PanelCache.images.get(messageId)) }
    var failed by remember(messageId) { mutableStateOf(false) }
    var attempt by remember(messageId) { mutableStateOf(0) }
    var previewOpen by remember(messageId) { mutableStateOf(false) }
    LaunchedEffect(messageId, attempt) {
        if (image != null) return@LaunchedEffect
        failed = false
        try {
            image = withContext(Dispatchers.IO) {
                PanelCache.lock.withLock {
                    PanelCache.images.get(messageId) ?: PanelImages.decode(ChatPanelStore.load(messageId)).also {
                        PanelCache.images.put(messageId, it)
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            failed = true
        }
    }
    val bitmap = image
    if (bitmap != null) {
        Image(
            bitmap.asImageBitmap(),
            "Shared panel",
            Modifier.fillMaxWidth().heightIn(max = 320.dp).clickable(onClickLabel = "Enlarge image") { previewOpen = true },
            contentScale = ContentScale.Fit,
        )
    } else if (failed) {
        Text("Image unavailable")
        TextButton(onClick = { attempt++ }) { Text("Retry") }
    } else {
        CircularProgressIndicator()
    }
    if (previewOpen && bitmap != null) {
        ChatPanelImagePreview(bitmap) { previewOpen = false }
    }
}

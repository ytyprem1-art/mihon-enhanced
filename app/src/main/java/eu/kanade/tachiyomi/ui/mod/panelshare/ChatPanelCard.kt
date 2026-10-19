package eu.kanade.tachiyomi.ui.mod.panelshare

import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.Modifier
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
fun ChatPanelCard(message: ChatMessage, onMangaClick: (ChatMessage) -> Unit) {
    var image by remember(message.id) { mutableStateOf<Bitmap?>(PanelCache.images.get(message.id)) }
    var failed by remember(message.id) { mutableStateOf(false) }
    var attempt by remember(message.id) { mutableStateOf(0) }
    LaunchedEffect(message.id, attempt) {
        if (image != null) return@LaunchedEffect
        failed = false
        try {
            image = withContext(Dispatchers.IO) {
                PanelCache.lock.withLock {
                    PanelCache.images.get(message.id) ?: PanelImages.decode(ChatPanelStore.load(message.id)).also {
                        PanelCache.images.put(message.id, it)
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            failed = true
        }
    }
    Card(Modifier.width(240.dp)) {
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val bitmap = image
            if (bitmap != null) {
                Image(
                    bitmap.asImageBitmap(),
                    "Shared panel",
                    Modifier.fillMaxWidth().heightIn(max = 320.dp),
                    contentScale = ContentScale.Fit,
                )
            } else if (failed) {
                Text("Image unavailable")
                TextButton(onClick = { attempt++ }) { Text("Retry") }
            } else {
                CircularProgressIndicator()
            }
            Column(Modifier.fillMaxWidth().clickable { onMangaClick(message) }) {
                Text(message.mangaTitle.orEmpty(), style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(message.chapterName.orEmpty(), style = MaterialTheme.typography.bodySmall)
                Text(message.sourceName.orEmpty(), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

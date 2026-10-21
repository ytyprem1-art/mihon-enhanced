@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package eu.kanade.tachiyomi.ui.mod.chatgif

import android.util.LruCache
import android.view.inputmethod.InputContentInfo
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.content.ReceiveContentListener
import androidx.compose.foundation.content.consume
import androidx.compose.foundation.content.contentReceiver
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.gif.GifDecoder
import coil3.memory.MemoryCache
import eu.kanade.tachiyomi.ui.chat.ChatMessage
import eu.kanade.tachiyomi.ui.chat.GlobalChatState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

private object GifImages {
    val encoded = object : LruCache<String, ByteArray>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ByteArray) = value.size
    }
    private var loader: ImageLoader? = null

    @Synchronized fun loader(context: android.content.Context): ImageLoader =
        loader ?: ImageLoader.Builder(context.applicationContext)
            .components { add(GifDecoder.Factory()) }
            .memoryCache { MemoryCache.Builder().maxSizeBytes(8L * 1024 * 1024).build() }
            .build().also { loader = it }
}

@Composable
private fun AnimatedGif(bytes: ByteArray, modifier: Modifier = Modifier) {
    AsyncImage(
        bytes,
        "GIF",
        imageLoader = GifImages.loader(LocalContext.current),
        modifier = modifier,
        contentScale = ContentScale.Fit,
    )
}

@Composable
fun ChatGifBubble(message: ChatMessage) {
    var bytes by remember(message.id) { mutableStateOf(GifImages.encoded.get(message.id)) }
    var error by remember(message.id) { mutableStateOf<String?>(null) }
    var attempt by remember(message.id) { mutableIntStateOf(0) }
    var opened by remember { mutableStateOf(false) }
    LaunchedEffect(message.id, attempt) {
        if (bytes != null) return@LaunchedEffect
        error = null
        try {
            bytes = ChatGifStore.load(message).also { GifImages.encoded.put(message.id, it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: "GIF tidak tersedia"
        }
    }
    Column {
        bytes?.let { data ->
            AnimatedGif(
                data,
                Modifier.width(240.dp).height(200.dp).clickable(
                    onClickLabel =
                    "Buka GIF",
                ) { opened = true },
            )
            if (opened) {
                Dialog(
                    onDismissRequest = { opened = false },
                    properties =
                    DialogProperties(usePlatformDefaultWidth = false),
                ) {
                    Box(Modifier.fillMaxSize().background(Color.Black)) {
                        AnimatedGif(data, Modifier.fillMaxSize())
                        IconButton(
                            onClick = { opened = false },
                            modifier =
                            Modifier.align(Alignment.TopEnd).statusBarsPadding(),
                        ) {
                            Icon(Icons.Default.Close, "Tutup GIF", tint = Color.White)
                        }
                    }
                }
            }
        } ?: if (error != null) {
            Text("GIF tidak tersedia")
            TextButton(onClick = { attempt++ }) { Text("Coba lagi") }
        } else {
            CircularProgressIndicator(Modifier.size(24.dp))
        }
        if (message.text.isNotBlank() && message.text != "[GIF]") {
            Text(
                message.text,
                style =
                MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
fun ChatGifComposer(
    room: GlobalChatState.ChatRoom,
    text: String,
    onTextChange: (String) -> Unit,
    onSendMessage: (String) -> Unit,
    onTyping: (Boolean) -> Unit,
    onGifSent: () -> Unit,
    onInputFocused: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val field = rememberTextFieldState(initialText = text)
    var draft by remember { mutableStateOf<ChatGifDraft?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(text) { if (field.text.toString() != text) field.setTextAndPlaceCursorAtEnd(text) }
    LaunchedEffect(field) {
        snapshotFlow { field.text.toString() }.distinctUntilChanged().collect {
            onTextChange(it)
            onTyping(it.isNotBlank())
        }
    }
    val receiver = ReceiveContentListener { content ->
        content.consume { item ->
            val uri = item.uri
            if (uri == null) {
                false
            } else {
                android.util.Log.d("ChatGif", "Keyboard media received; busy=$busy")
                if (!busy) {
                    busy = true
                    error = null
                    val link = content.platformTransferableContent?.linkUri

                    @Suppress("DEPRECATION")
                    val permission =
                        content.platformTransferableContent?.extras?.getParcelable<InputContentInfo>(
                            "EXTRA_INPUT_CONTENT_INFO",
                        )
                    scope.launch {
                        try {
                            draft = ChatGifStore.prepare(context, uri, link)
                            android.util.Log.d("ChatGif", "Keyboard GIF preview prepared")
                        } catch (e: CancellationException) {
                            android.util.Log.d("ChatGif", "Keyboard GIF preparation cancelled")
                            throw e
                        } catch (e: Exception) {
                            android.util.Log.d("ChatGif", "Keyboard GIF preparation failed: ${e.javaClass.simpleName}")
                            error = e.message ?: "Tidak bisa membaca GIF."
                        } finally {
                            permission?.releasePermission()
                            busy = false
                        }
                    }
                }
                true
            }
        }
    }
    Column(Modifier.fillMaxWidth().padding(8.dp)) {
        draft?.let { gif ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                AnimatedGif(gif.bytes, Modifier.size(96.dp))
                Text(
                    "GIF · ${gif.bytes.size / 1000} KB",
                    Modifier.weight(1f),
                    style =
                    MaterialTheme.typography.bodySmall,
                )
                IconButton(onClick = { draft = null }, enabled = !busy) { Icon(Icons.Default.Close, "Hapus GIF") }
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                state = field,
                placeholder = { Text("Type a message...") },
                modifier =
                Modifier.weight(1f).onFocusChanged { if (it.isFocused) onInputFocused() }.contentReceiver(receiver),
                lineLimits = TextFieldLineLimits.MultiLine(maxHeightInLines = 3),
                enabled = !busy,
            )
            Spacer(Modifier.width(8.dp))
            if (busy) {
                CircularProgressIndicator(Modifier.size(24.dp))
            } else {
                IconButton(
                    enabled = draft != null || field.text.isNotBlank(),
                    onClick = {
                        val gif = draft
                        if (gif == null) {
                            onSendMessage(field.text.toString())
                        } else {
                            busy = true
                            error = null
                            scope.launch {
                                try {
                                    ChatGifStore.send(room, gif, field.text.toString())
                                    draft = null
                                    onTextChange("")
                                    field.setTextAndPlaceCursorAtEnd("")
                                    onGifSent()
                                    onTyping(false)
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    error = "GIF gagal dikirim. Coba lagi. ${e.message.orEmpty()}"
                                } finally {
                                    busy = false
                                }
                            }
                        }
                    },
                ) { Icon(Icons.AutoMirrored.Filled.Send, "Send") }
            }
        }
    }
}

package eu.kanade.tachiyomi.ui.chat

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.request.ImageRequest
import coil3.request.crossfade
import eu.kanade.presentation.manga.components.MangaCover
import tachiyomi.domain.manga.model.MangaCover as MangaCoverModel
import tachiyomi.presentation.core.components.material.Scaffold
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun GlobalChatScreen(
    state: GlobalChatState,
    onSetUsername: (String) -> Unit,
    onSendMessage: (String) -> Unit,
    onMangaClick: (ChatMessage) -> Unit,
    onReplyClick: (ChatMessage) -> Unit,
    onCancelReply: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
    ) {
        when (state) {
            is GlobalChatState.NeedUsername -> {
                UsernameEntry(onSetUsername)
            }
            is GlobalChatState.ChatRoom -> {
                ChatRoomContent(state, onSendMessage, onMangaClick, onReplyClick, onCancelReply)
            }
        }
    }
}

@Composable
private fun ColumnScope.UsernameEntry(onSetUsername: (String) -> Unit) {
    var username by remember { mutableStateOf("") }
    Column(
        modifier = Modifier
            .weight(1f)
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "Welcome to Global Chat",
            style = MaterialTheme.typography.headlineMedium,
        )
        Spacer(modifier = Modifier.size(16.dp))
        OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            label = { Text("Enter Username") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.size(16.dp))
        Button(
            onClick = { onSetUsername(username) },
            enabled = username.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Join Chat")
        }
    }
    // Anchor to bottom safely
    Spacer(modifier = Modifier.windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars).only(WindowInsetsSides.Bottom)))
}

@Composable
private fun ColumnScope.ChatRoomContent(
    state: GlobalChatState.ChatRoom,
    onSendMessage: (String) -> Unit,
    onMangaClick: (ChatMessage) -> Unit,
    onReplyClick: (ChatMessage) -> Unit,
    onCancelReply: () -> Unit,
) {
    val listState = rememberLazyListState()

    // Reversed list for standard chat behavior (newest at bottom, sticks to bottom)
    val reversedMessages = remember(state.messages) {
        state.messages.asReversed()
    }

    LazyColumn(
        state = listState,
        modifier = Modifier
            .weight(1f)
            .fillMaxWidth(),
        reverseLayout = true,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.Bottom),
    ) {
        items(reversedMessages) { message ->
            ChatBubble(
                message = message,
                isMe = message.sender == state.username,
                onMangaClick = onMangaClick,
                onReplyClick = onReplyClick,
            )
        }
    }

    HorizontalDivider()

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars).only(WindowInsetsSides.Bottom)),
    ) {
        Column {
            state.replyingTo?.let { reply ->
                ReplyPreview(message = reply, onCancelReply = onCancelReply)
            }
            ChatInput(onSendMessage)
        }
    }
}

@Composable
private fun ReplyPreview(
    message: ChatMessage,
    onCancelReply: () -> Unit,
) {
    val userColor = remember(message.sender) { getUserColor(message.sender) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .width(4.dp)
                .height(32.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(userColor),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = message.sender,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = userColor,
            )
            Text(
                text = if (message.isMangaShare) "[Manga] ${message.mangaTitle}" else message.text,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onCancelReply) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Cancel reply",
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChatBubble(
    message: ChatMessage,
    isMe: Boolean,
    onMangaClick: (ChatMessage) -> Unit,
    onReplyClick: (ChatMessage) -> Unit,
) {
    val alignment = if (isMe) Alignment.End else Alignment.Start
    val userColor = remember(message.sender) { getUserColor(message.sender) }

    val backgroundColor = if (isMe) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        userColor.copy(alpha = 0.2f)
    }

    val textColor = if (isMe) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    val labelColor = if (isMe) {
        MaterialTheme.colorScheme.primary
    } else {
        userColor
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = alignment,
    ) {
        Surface(
            color = backgroundColor,
            shape = RoundedCornerShape(
                topStart = 12.dp,
                topEnd = 12.dp,
                bottomStart = if (isMe) 12.dp else 0.dp,
                bottomEnd = if (isMe) 0.dp else 12.dp,
            ),
            modifier = Modifier.combinedClickable(
                onClick = {},
                onLongClick = { onReplyClick(message) },
            ),
        ) {
            Column(modifier = Modifier.padding(8.dp)) {
                if (!isMe) {
                    Text(
                        text = message.sender,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = labelColor,
                    )
                }

                // Reply info
                if (message.replyToId != null) {
                    val replyUserColor = remember(message.replyToUser) { getUserColor(message.replyToUser ?: "") }
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(4.dp),
                        modifier = Modifier.padding(bottom = 4.dp),
                    ) {
                        Row(modifier = Modifier.height(IntrinsicSize.Min)) {
                            Box(
                                modifier = Modifier
                                    .width(2.dp)
                                    .fillMaxHeight()
                                    .background(replyUserColor),
                            )
                            Column(modifier = Modifier.padding(4.dp)) {
                                Text(
                                    text = message.replyToUser ?: "Unknown",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = replyUserColor,
                                )
                                Text(
                                    text = message.replyToText ?: "",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontSize = 10.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }

                if (message.isMangaShare) {
                    MangaShareCard(message, onMangaClick)
                } else {
                    Text(
                        text = message.text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = textColor,
                    )
                }

                val timeText = remember(message.timestamp) {
                    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(message.timestamp))
                }
                Text(
                    text = timeText,
                    style = MaterialTheme.typography.labelSmall,
                    color = textColor.copy(alpha = 0.6f),
                    modifier = Alignment.End.let { Modifier.align(it) },
                )
            }
        }
    }
}

@Composable
private fun MangaShareCard(
    message: ChatMessage,
    onMangaClick: (ChatMessage) -> Unit,
) {
    Card(
        modifier = Modifier
            .width(240.dp)
            .padding(vertical = 4.dp)
            .clickable { onMangaClick(message) },
    ) {
        Row(
            modifier = Modifier.padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val imageModel = remember(message.mangaCoverUrl, message.sourceId) {
                if (message.sourceId != null && message.mangaCoverUrl != null) {
                    MangaCoverModel(
                        mangaId = -1L,
                        sourceId = message.sourceId,
                        isMangaFavorite = false,
                        url = message.mangaCoverUrl,
                        lastModified = 0L,
                    )
                } else {
                    message.mangaCoverUrl
                }
            }

            MangaCover.Book(
                data = imageModel,
                modifier = Modifier.size(60.dp, 80.dp),
            )

            Spacer(modifier = Modifier.width(8.dp))
            Column {
                Text(
                    text = message.mangaTitle ?: "Unknown Manga",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                )
                Text(
                    text = message.sourceName ?: "Unknown Source",
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun ChatInput(onSendMessage: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            placeholder = { Text("Type a message...") },
            modifier = Modifier.weight(1f),
            maxLines = 3,
        )
        Spacer(modifier = Modifier.width(8.dp))
        IconButton(
            onClick = {
                onSendMessage(text)
                text = ""
            },
            enabled = text.isNotBlank(),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.Send,
                contentDescription = "Send",
            )
        }
    }
}

private fun getUserColor(username: String): Color {
    val colors = listOf(
        Color(0xFFEF5350), // Red
        Color(0xFFEC407A), // Pink
        Color(0xFFAB47BC), // Purple
        Color(0xFF7E57C2), // Deep Purple
        Color(0xFF5C6BC0), // Indigo
        Color(0xFF42A5F5), // Blue
        Color(0xFF29B6F6), // Light Blue
        Color(0xFF26C6DA), // Cyan
        Color(0xFF26A69A), // Teal
        Color(0xFF66BB6A), // Green
        Color(0xFF9CCC65), // Light Green
        Color(0xFFD4E157), // Lime
        Color(0xFFFFEE58), // Yellow
        Color(0xFFFFCA28), // Amber
        Color(0xFFFFA726), // Orange
        Color(0xFFFF7043), // Deep Orange
    )
    val hash = username.hashCode()
    val index = Math.abs(hash) % colors.size
    return colors[index]
}

package eu.kanade.tachiyomi.ui.chat

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.RemoveRedEye
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil3.request.ImageRequest
import coil3.request.crossfade
import eu.kanade.presentation.manga.components.MangaCover
import kotlinx.coroutines.launch
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
    updatePresence: (Boolean) -> Unit,
    markAsRead: () -> Unit,
    onTyping: (Boolean) -> Unit,
) {
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(Unit) {
        updatePresence(true)
        markAsRead()
        onDispose {
            updatePresence(false)
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                updatePresence(true)
                markAsRead()
            } else if (event == Lifecycle.Event.ON_PAUSE) {
                updatePresence(false)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Auto-mark as read when new messages arrive while screen is active
    LaunchedEffect(state) {
        if (state is GlobalChatState.ChatRoom && lifecycleOwner.lifecycle.currentState == Lifecycle.State.RESUMED) {
            markAsRead()
        }
    }

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
                PresenceHeader(users = state.onlineUsers, unreadCount = state.unreadCount)
                ChatRoomContent(state, onSendMessage, onMangaClick, onReplyClick, onCancelReply, onTyping)
            }
        }
    }
}

@Composable
private fun PresenceHeader(users: List<ChatUser>, unreadCount: Int) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Global Chat",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f)
            )
            if (unreadCount > 0) {
                Badge { Text(unreadCount.toString()) }
                Spacer(modifier = Modifier.width(8.dp))
            }
        }
        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(users) { user ->
                OnlineUserItem(user)
            }
        }
        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun OnlineUserItem(user: ChatUser) {
    val color = remember(user.username) { getUserColor(user.username) }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box {
            Surface(
                modifier = Modifier.size(32.dp),
                shape = CircleShape,
                color = color.copy(alpha = 0.2f),
                border = androidx.compose.foundation.BorderStroke(1.dp, color)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = user.username.take(1).uppercase(),
                        style = MaterialTheme.typography.labelMedium,
                        color = color
                    )
                }
            }
            if (user.isOnline) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(Color.Green)
                        .align(Alignment.BottomEnd)
                        .border(1.dp, MaterialTheme.colorScheme.surface, CircleShape)
                )
            }
        }
        Text(
            text = user.username,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(40.dp),
            textAlign = TextAlign.Center
        )
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
    Spacer(
        modifier = Modifier
            .navigationBarsPadding()
            .imePadding(),
    )
}

@Composable
private fun ColumnScope.ChatRoomContent(
    state: GlobalChatState.ChatRoom,
    onSendMessage: (String) -> Unit,
    onMangaClick: (ChatMessage) -> Unit,
    onReplyClick: (ChatMessage) -> Unit,
    onCancelReply: () -> Unit,
    onTyping: (Boolean) -> Unit,
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var inputText by remember { mutableStateOf("") }

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
        contentPadding = PaddingValues(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.Bottom),
    ) {
        items(reversedMessages) { message ->
            val isMe = message.senderUid == (state as? GlobalChatState.ChatRoom)?.onlineUsers?.find { it.username == state.username }?.uid
                || message.sender == state.username

            ChatBubble(
                message = message,
                isMe = isMe,
                onMangaClick = onMangaClick,
                onReplyClick = onReplyClick,
                onReplyJumpClick = { replyId ->
                    val index = reversedMessages.indexOfFirst { it.id == replyId }
                    if (index != -1) {
                        scope.launch {
                            listState.animateScrollToItem(index)
                        }
                    }
                },
                allUsers = state.onlineUsers
            )
        }
    }

    if (!state.typingIndicator.isNullOrBlank()) {
        Text(
            text = "${state.typingIndicator} is typing...",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )
    }

    HorizontalDivider()

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .imePadding(),
    ) {
        Column {
            state.replyingTo?.let { reply ->
                ReplyPreview(message = reply, onCancelReply = onCancelReply)
            }
            ChatInput(
                text = inputText,
                onTextChange = { inputText = it },
                onSendMessage = {
                    onSendMessage(it)
                    inputText = ""
                },
                onTyping = onTyping,
            )
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
    onReplyJumpClick: (String) -> Unit,
    allUsers: List<ChatUser>,
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

    var showReadReceipts by remember { mutableStateOf(false) }

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
                if (message.replyToMessageId != null) {
                    val replyUserColor = remember(message.replyToSenderName) { getUserColor(message.replyToSenderName ?: "") }
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(4.dp),
                        modifier = Modifier
                            .padding(bottom = 4.dp)
                            .clickable { onReplyJumpClick(message.replyToMessageId) },
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
                                    text = message.replyToSenderName ?: "Unknown",
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

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.End,
                    modifier = Modifier.align(Alignment.End)
                ) {
                    val timeText = remember(message.timestamp) {
                        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(message.timestamp))
                    }
                    Text(
                        text = timeText,
                        style = MaterialTheme.typography.labelSmall,
                        color = textColor.copy(alpha = 0.6f),
                    )

                    if (isMe && message.readBy.size > 1) { // 1 is me
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(
                            imageVector = Icons.Default.RemoveRedEye,
                            contentDescription = "Read receipts",
                            modifier = Modifier
                                .size(12.dp)
                                .clickable { showReadReceipts = true },
                            tint = labelColor.copy(alpha = 0.7f)
                        )
                    }
                }
            }
        }
    }

    if (showReadReceipts) {
        ReadReceiptsDialog(
            readBy = message.readBy,
            allUsers = allUsers,
            onDismiss = { showReadReceipts = false }
        )
    }
}

@Composable
private fun ReadReceiptsDialog(
    readBy: Map<String, Long>,
    allUsers: List<ChatUser>,
    onDismiss: () -> Unit,
) {
    val locale = androidx.compose.ui.text.intl.Locale.current
    val timeFormatter = remember(locale) { SimpleDateFormat("HH:mm:ss", locale.platformLocale) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Read by") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                readBy.forEach { (uid, timestamp) ->
                    val user = allUsers.find { it.uid == uid }
                    if (user != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OnlineUserItem(user)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = timeFormatter.format(Date(timestamp)),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
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
private fun ChatInput(
    text: String,
    onTextChange: (String) -> Unit,
    onSendMessage: (String) -> Unit,
    onTyping: (Boolean) -> Unit,
) {
    LaunchedEffect(text) {
        onTyping(text.isNotBlank())
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = onTextChange,
            placeholder = { Text("Type a message...") },
            modifier = Modifier.weight(1f),
            maxLines = 3,
        )
        Spacer(modifier = Modifier.width(8.dp))
        IconButton(
            onClick = {
                onSendMessage(text)
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

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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.RemoveRedEye
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FloatingActionButton
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
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
import eu.kanade.presentation.manga.components.DotSeparatorText
import eu.kanade.presentation.manga.components.MangaCover
import kotlinx.coroutines.launch
import tachiyomi.domain.manga.model.MangaCover as MangaCoverModel
import tachiyomi.presentation.core.components.material.Scaffold
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import eu.kanade.tachiyomi.ui.mod.EnhancedPreferences
import tachiyomi.presentation.core.util.collectAsState
import kotlin.time.Duration.Companion.milliseconds
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@Composable
fun GlobalChatScreen(
    state: GlobalChatState,
    onSetUsername: (String) -> Unit,
    onSendMessage: (String) -> Unit,
    onMangaClick: (ChatMessage) -> Unit,
    onOpenManga: (String, Long) -> Unit,
    onReplyClick: (ChatMessage) -> Unit,
    onCancelReply: () -> Unit,
    updatePresence: (Boolean) -> Unit,
    markAsRead: () -> Unit,
    onTyping: (Boolean) -> Unit,
    startListeners: () -> Unit,
    stopListeners: () -> Unit,
) {
    val lifecycleOwner = LocalLifecycleOwner.current

    // Snapshot the unread state for the current viewing session.
    var sessionFirstUnreadId by rememberSaveable { mutableStateOf<String?>(null) }
    var hasSnapshottedUnread by rememberSaveable { mutableStateOf(false) }

    DisposableEffect(Unit) {
        startListeners()
        updatePresence(true)
        onDispose {
            updatePresence(false)
            stopListeners()
            // Reset session unread state when the screen is disposed (navigated away)
            sessionFirstUnreadId = null
            hasSnapshottedUnread = false
        }
    }

    // Mark as read and snapshot once on entry
    LaunchedEffect(state) {
        val chatRoom = state as? GlobalChatState.ChatRoom ?: return@LaunchedEffect
        if (!hasSnapshottedUnread && chatRoom.messages.isNotEmpty()) {
            // Threshold: Only show unread divider if there are >= 3 unread messages
            if (chatRoom.unreadCount >= 3) {
                val currentUid = chatRoom.currentUid
                // Find the oldest unread message (first in chronologically ascending list)
                val unreadMessages = chatRoom.messages.filter {
                    it.senderUid != currentUid && !it.readBy.containsKey(currentUid)
                }

                // Smart Unread Cap: If > 30 unread, only jump to the last 30
                val targetFirstUnread = if (unreadMessages.size > 30) {
                    unreadMessages[unreadMessages.size - 30]
                } else {
                    unreadMessages.firstOrNull()
                }

                sessionFirstUnreadId = targetFirstUnread?.id
            }
            hasSnapshottedUnread = true
            markAsRead()
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                updatePresence(true)
            } else if (event == Lifecycle.Event.ON_PAUSE) {
                updatePresence(false)
                markAsRead()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Auto-mark as read for incoming messages during the session if we are already caught up
    LaunchedEffect(state) {
        if (state is GlobalChatState.ChatRoom && lifecycleOwner.lifecycle.currentState == Lifecycle.State.RESUMED) {
            if (sessionFirstUnreadId == null) {
                markAsRead()
            }
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
                var selectedUserForProfile by remember { mutableStateOf<ChatUser?>(null) }

                PresenceHeader(
                    users = state.onlineUsers,
                    unreadCount = state.unreadCount,
                    onUserClick = { selectedUserForProfile = it }
                )

                selectedUserForProfile?.let { user ->
                    ProfileBottomSheet(
                        user = user,
                        onDismissRequest = { selectedUserForProfile = null },
                        onOpenManga = { title, sourceId ->
                            onOpenManga(title, sourceId)
                            selectedUserForProfile = null
                        }
                    )
                }

                ChatRoomContent(
                    state = state,
                    onSendMessage = onSendMessage,
                    onMangaClick = onMangaClick,
                    onReplyClick = onReplyClick,
                    onCancelReply = onCancelReply,
                    onTyping = onTyping,
                    sessionFirstUnreadId = sessionFirstUnreadId,
                    onUserClick = { selectedUserForProfile = it }
                )
            }
        }
    }
}

@Composable
private fun PresenceHeader(
    users: List<ChatUser>,
    unreadCount: Int,
    onUserClick: (ChatUser) -> Unit
) {
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
                OnlineUserItem(user, onUserClick)
            }
        }
        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun OnlineUserItem(user: ChatUser, onUserClick: (ChatUser) -> Unit) {
    val enhancedPreferences = remember { Injekt.get<EnhancedPreferences>() }
    val avEnabled by enhancedPreferences.avEnabled.collectAsState()
    val richEnabled = PresenceManager.RICH_PRESENCE_ENABLED

    val now = System.currentTimeMillis()
    val lastActivity = user.lastActiveAt ?: user.lastSeen
    val isStale = lastActivity != 0L && (now - lastActivity > 3 * 60 * 1000)
    val resolvedStatus = if (isStale) "OFFLINE" else (user.status ?: if (user.isOnline) "ONLINE" else "OFFLINE")

    val dotColor = when (resolvedStatus) {
        "IN_CHAT" -> Color(0xFF2196F3) // Blue
        "READING" -> Color(0xFFFF9800) // Orange
        "ONLINE" -> Color.Green
        else -> Color.Gray
    }

    val color = remember(user.username) { getUserColor(user.username) }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable(enabled = richEnabled && avEnabled) { onUserClick(user) }
    ) {
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

            val showDot = if (richEnabled && avEnabled) true else user.isOnline && !isStale
            if (showDot) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(if (richEnabled && avEnabled) dotColor else Color.Green)
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
private fun ProfileBottomSheet(
    user: ChatUser,
    onDismissRequest: () -> Unit,
    onOpenManga: (String, Long) -> Unit
) {
    eu.kanade.presentation.components.AdaptiveSheet(onDismissRequest = onDismissRequest) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            val color = remember(user.username) { getUserColor(user.username) }
            Surface(
                modifier = Modifier.size(80.dp),
                shape = CircleShape,
                color = color.copy(alpha = 0.2f),
                border = androidx.compose.foundation.BorderStroke(2.dp, color)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = user.username.take(1).uppercase(),
                        style = MaterialTheme.typography.headlineLarge,
                        color = color
                    )
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            Text(text = user.username, style = MaterialTheme.typography.headlineSmall)

            val now = System.currentTimeMillis()
            val lastActivity = user.lastActiveAt ?: user.lastSeen
            val isStale = lastActivity != 0L && (now - lastActivity > 3 * 60 * 1000)
            val resolvedStatus = if (isStale) "OFFLINE" else (user.status ?: if (user.isOnline) "ONLINE" else "OFFLINE")

            val statusText = when (resolvedStatus) {
                "IN_CHAT" -> "In Chat"
                "READING" -> "Reading"
                "ONLINE" -> "Online"
                else -> "Offline"
            }

            Text(
                text = statusText,
                style = MaterialTheme.typography.bodyMedium,
                color = if (resolvedStatus == "OFFLINE") MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.primary
            )

            if (resolvedStatus == "READING" && !user.readingManga.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(text = user.readingManga, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                if (!user.readingChapter.isNullOrBlank()) {
                    Text(text = user.readingChapter, style = MaterialTheme.typography.bodySmall)
                }

                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = {
                        val url = user.readingMangaId
                        val sourceId = user.readingSourceId
                        if (url != null && sourceId != null) {
                            onOpenManga(url, sourceId)
                        }
                    },
                    enabled = user.readingMangaId != null && user.readingSourceId != null
                ) {
                    Text("Buka Manga")
                }
            } else if (resolvedStatus == "OFFLINE") {
                val lastTime = user.lastActiveAt ?: user.lastSeen
                if (lastTime != 0L) {
                    Spacer(modifier = Modifier.height(8.dp))
                    val locale = androidx.compose.ui.text.intl.Locale.current
                    val df = remember(locale) { SimpleDateFormat("HH:mm, dd MMM", locale.platformLocale) }
                    Text(
                        text = "Last active: ${df.format(Date(lastTime))}",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
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
    sessionFirstUnreadId: String?,
    onUserClick: (ChatUser) -> Unit,
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var inputText by remember { mutableStateOf("") }

    // Reversed list for standard chat behavior (newest at bottom, sticks to bottom)
    val reversedMessages = remember(state.messages) {
        state.messages.asReversed()
    }

    // displayItems order: [Newest ... DateHeader ... Divider ... Oldest]
    val displayItems = remember(reversedMessages, sessionFirstUnreadId) {
        val items = mutableListOf<ChatDisplayItem>()

        reversedMessages.forEachIndexed { index, msg ->
            val currentDate = formatDateHeader(msg.timestamp)

            items.add(ChatDisplayItem.Message(msg))

            if (msg.id == sessionFirstUnreadId) {
                items.add(ChatDisplayItem.UnreadDivider)
            }

            // Look ahead to check if date changed (since it's reversed, we check the next item which is chronologically previous)
            val nextMsg = reversedMessages.getOrNull(index + 1)
            val nextDate = nextMsg?.let { formatDateHeader(it.timestamp) }

            if (currentDate != nextDate) {
                items.add(ChatDisplayItem.DateHeader(currentDate))
            }
        }
        items
    }

    // Initial scroll to unread divider
    LaunchedEffect(sessionFirstUnreadId) {
        if (sessionFirstUnreadId != null) {
            val index = displayItems.indexOfFirst { it is ChatDisplayItem.UnreadDivider }
            if (index != -1) {
                listState.scrollToItem(index)
            }
        }
    }

    Box(modifier = Modifier.weight(1f)) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            reverseLayout = true,
            contentPadding = PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.Bottom),
        ) {
            items(
                items = displayItems,
            ) { item ->
                when (item) {
                    is ChatDisplayItem.Message -> {
                        val message = item.message
                        val isMe = message.senderUid == state.currentUid || message.sender == state.username

                        ChatBubble(
                            message = message,
                            isMe = isMe,
                            onMangaClick = onMangaClick,
                            onReplyClick = onReplyClick,
                            onReplyJumpClick = { replyId ->
                                val replyIndex = displayItems.indexOfFirst {
                                    it is ChatDisplayItem.Message && it.message.id == replyId
                                }
                                if (replyIndex != -1) {
                                    scope.launch {
                                        listState.animateScrollToItem(replyIndex)
                                    }
                                }
                            },
                            allUsers = state.onlineUsers,
                            onUserClick = onUserClick,
                        )
                    }
                    ChatDisplayItem.UnreadDivider -> {
                        UnreadMessagesDivider()
                    }
                    is ChatDisplayItem.DateHeader -> {
                        DateDivider(item.date)
                    }
                }
            }
        }

        // Scroll to Bottom FAB
        val showFab by remember {
            derivedStateOf {
                listState.firstVisibleItemIndex > 3
            }
        }

        androidx.compose.animation.AnimatedVisibility(
            visible = showFab,
            enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.scaleIn(),
            exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.scaleOut(),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp)
        ) {
            BadgedBox(
                badge = {
                    if (state.unreadCount > 0) {
                        Badge { Text(state.unreadCount.toString()) }
                    }
                }
            ) {
                FloatingActionButton(
                    onClick = {
                        scope.launch {
                            listState.animateScrollToItem(0)
                        }
                    },
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(48.dp),
                    shape = CircleShape,
                ) {
                    Icon(Icons.Default.ArrowDownward, contentDescription = "Scroll to bottom")
                }
            }
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

@Composable
private fun UnreadMessagesDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            color = MaterialTheme.colorScheme.secondaryContainer,
            shape = RoundedCornerShape(16.dp),
        ) {
            Text(
                text = "Unread Messages",
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

@Composable
private fun DateDivider(date: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(8.dp),
        ) {
            Text(
                text = date,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun formatDateHeader(timestamp: Long): String {
    val messageCalendar = Calendar.getInstance().apply { timeInMillis = timestamp }
    val now = Calendar.getInstance()

    return when {
        isSameDay(messageCalendar, now) -> "Today"
        isYesterday(messageCalendar, now) -> "Yesterday"
        isWithinLastWeek(messageCalendar, now) -> {
            SimpleDateFormat("EEEE", Locale.getDefault()).format(messageCalendar.time)
        }
        else -> {
            SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(messageCalendar.time)
        }
    }
}

private fun isSameDay(cal1: Calendar, cal2: Calendar): Boolean {
    return cal1.get(Calendar.YEAR) == cal2.get(Calendar.YEAR) &&
        cal1.get(Calendar.DAY_OF_YEAR) == cal2.get(Calendar.DAY_OF_YEAR)
}

private fun isYesterday(cal1: Calendar, cal2: Calendar): Boolean {
    val yesterday = Calendar.getInstance().apply {
        timeInMillis = cal2.timeInMillis
        add(Calendar.DAY_OF_YEAR, -1)
    }
    return isSameDay(cal1, yesterday)
}

private fun isWithinLastWeek(cal1: Calendar, cal2: Calendar): Boolean {
    val lastWeek = Calendar.getInstance().apply {
        timeInMillis = cal2.timeInMillis
        add(Calendar.DAY_OF_YEAR, -7)
    }
    return cal1.after(lastWeek)
}

private sealed class ChatDisplayItem {
    abstract val key: String

    data class Message(val message: ChatMessage) : ChatDisplayItem() {
        override val key: String = message.id
    }

    data object UnreadDivider : ChatDisplayItem() {
        override val key: String = "unread-divider"
    }

    data class DateHeader(val date: String) : ChatDisplayItem() {
        override val key: String = "date-$date"
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
    onUserClick: (ChatUser) -> Unit,
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
            modifier = Modifier
                .widthIn(max = 300.dp)
                .combinedClickable(
                    onClick = {},
                    onLongClick = { onReplyClick(message) },
                ),
        ) {
            Column(
                modifier = Modifier
                    .width(IntrinsicSize.Max)
                    .padding(8.dp),
            ) {
                if (!isMe || message.isMangaShare) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (!isMe) {
                            Text(
                                text = message.sender,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = labelColor,
                            )
                        }
                        if (message.isMangaShare) {
                            if (!isMe) {
                                DotSeparatorText()
                            }
                            Text(
                                text = if (message.chapterName != null) "Shared a chapter" else "Shared a manga",
                                style = MaterialTheme.typography.labelSmall,
                                color = textColor.copy(alpha = 0.6f),
                            )
                        }
                    }
                }

                // Reply info
                if (message.replyToMessageId != null) {
                    val replyUserColor = remember(message.replyToSenderName) { getUserColor(message.replyToSenderName ?: "") }
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(4.dp),
                        modifier = Modifier
                            .fillMaxWidth()
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
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }

                if (message.isMangaShare) {
                    MangaShareCard(message, onMangaClick)

                    // Render custom caption UNDER the card if present and not the default fallback
                    if (message.text.isNotBlank() && !message.text.startsWith("Shared a manga:")) {
                        Text(
                            text = message.text,
                            style = MaterialTheme.typography.bodyMedium,
                            color = textColor,
                            modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
                        )
                    }
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
            onDismiss = { showReadReceipts = false },
            onUserClick = onUserClick
        )
    }
}

@Composable
private fun ReadReceiptsDialog(
    readBy: Map<String, Long>,
    allUsers: List<ChatUser>,
    onDismiss: () -> Unit,
    onUserClick: (ChatUser) -> Unit,
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
                            OnlineUserItem(user, onUserClick = onUserClick)
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
                    overflow = TextOverflow.Ellipsis,
                )
                if (!message.chapterName.isNullOrBlank()) {
                    Text(
                        text = message.chapterName,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = message.sourceName ?: "Unknown Source",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
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

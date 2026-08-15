package eu.kanade.tachiyomi.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Book
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import tachiyomi.presentation.core.components.material.Scaffold
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun GlobalChatScreen(
    state: GlobalChatState,
    onSetUsername: (String) -> Unit,
    onSendMessage: (String) -> Unit,
) {
    Scaffold { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
        ) {
            when (state) {
                is GlobalChatState.NeedUsername -> {
                    UsernameEntry(onSetUsername)
                }
                is GlobalChatState.ChatRoom -> {
                    ChatRoomContent(state, onSendMessage)
                }
            }
        }
    }
}

@Composable
private fun UsernameEntry(onSetUsername: (String) -> Unit) {
    var username by remember { mutableStateOf("") }
    Column(
        modifier = Modifier
            .fillMaxSize()
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
}

@Composable
private fun ChatRoomContent(
    state: GlobalChatState.ChatRoom,
    onSendMessage: (String) -> Unit,
) {
    val listState = rememberLazyListState()

    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) {
            listState.animateScrollToItem(state.messages.size - 1)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(state.messages) { message ->
                ChatBubble(message, isMe = message.sender == state.username)
            }
        }

        HorizontalDivider()

        ChatInput(onSendMessage)
    }
}

@Composable
private fun ChatBubble(message: ChatMessage, isMe: Boolean) {
    val alignment = if (isMe) Alignment.End else Alignment.Start
    val backgroundColor = if (isMe) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer
    val textColor = if (isMe) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSecondaryContainer

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
        ) {
            Column(modifier = Modifier.padding(8.dp)) {
                if (!isMe) {
                    Text(
                        text = message.sender,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = textColor,
                    )
                }

                if (message.isMangaShare) {
                    MangaShareCard(message)
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
private fun MangaShareCard(message: ChatMessage) {
    Card(
        modifier = Modifier
            .width(240.dp)
            .padding(vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier.padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (message.mangaCoverUrl != null) {
                AsyncImage(
                    model = message.mangaCoverUrl,
                    contentDescription = null,
                    modifier = Modifier
                        .size(60.dp, 80.dp)
                        .clip(RoundedCornerShape(4.dp)),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(60.dp, 80.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(MaterialTheme.colorScheme.secondary),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Default.Book,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondary,
                    )
                }
            }
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

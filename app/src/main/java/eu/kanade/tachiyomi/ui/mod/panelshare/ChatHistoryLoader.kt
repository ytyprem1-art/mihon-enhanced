package eu.kanade.tachiyomi.ui.mod.panelshare

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.ui.chat.ChatManager
import kotlinx.coroutines.flow.first
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

const val CHAT_HISTORY_LOADER_KEY = "chat-load-older"

@Composable
fun ChatHistoryLoader(listState: LazyListState) {
    val manager = remember { Injekt.get<ChatManager>() }
    val page by manager.historyPaging.state.collectAsState()
    LaunchedEffect(listState, page) {
        if (!page.canLoadMore || page.loading || page.error != null) return@LaunchedEffect
        snapshotFlow {
            listState.isScrollInProgress && listState.layoutInfo.visibleItemsInfo.any { it.key == CHAT_HISTORY_LOADER_KEY }
        }.first { it }
        manager.loadOlderMessages()
    }
    Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
        when {
            page.loading -> CircularProgressIndicator()
            page.error != null -> TextButton(onClick = manager::loadOlderMessages) { Text("Retry loading earlier messages") }
            page.canLoadMore -> TextButton(onClick = manager::loadOlderMessages) { Text("Load 50 earlier messages") }
            else -> Text("No earlier messages")
        }
    }
}

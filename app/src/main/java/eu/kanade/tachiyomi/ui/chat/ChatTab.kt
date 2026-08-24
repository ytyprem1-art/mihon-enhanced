package eu.kanade.tachiyomi.ui.chat

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.currentOrThrow
import cafe.adriel.voyager.navigator.tab.TabOptions
import eu.kanade.domain.source.interactor.GetEnabledSources
import eu.kanade.presentation.browse.components.QuickSourceSwitcherDialog
import eu.kanade.presentation.util.Tab
import eu.kanade.tachiyomi.ui.browse.source.browse.BrowseSourceScreen
import eu.kanade.tachiyomi.util.system.toast
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

data object ChatTab : Tab {

    override val options: TabOptions
        @Composable
        get() {
            return TabOptions(
                index = 5u,
                title = "Chat", // Hardcoded for POC
                icon = rememberVectorPainter(Icons.Default.Chat),
            )
        }

    override suspend fun onReselect(navigator: Navigator) {
        // No-op for now
    }

    @Composable
    override fun Content() {
        val viewModel = viewModel<GlobalChatViewModel>()
        val state by viewModel.state.collectAsState()
        val navigator = LocalNavigator.currentOrThrow

        var selectedMessageForSourcePicker by remember { mutableStateOf<ChatMessage?>(null) }

        GlobalChatScreen(
            state = state,
            onSetUsername = viewModel::setUsername,
            onSendMessage = viewModel::sendMessage,
            onMangaClick = { message ->
                if (!message.mangaTitle.isNullOrBlank()) {
                    selectedMessageForSourcePicker = message
                }
            },
            onReplyClick = viewModel::setReplyingTo,
            onCancelReply = { viewModel.setReplyingTo(null) },
            updatePresence = viewModel::updatePresence,
            markAsRead = viewModel::markMessagesAsRead,
            onTyping = viewModel::setTyping,
            startListeners = viewModel::startListeners,
            stopListeners = viewModel::stopListeners,
        )

        val currentMessage = selectedMessageForSourcePicker
        val context = LocalContext.current
        if (currentMessage != null) {
            val getEnabledSources: GetEnabledSources = remember { Injekt.get() }
            val sources by getEnabledSources.subscribe().collectAsState(emptyList())

            QuickSourceSwitcherDialog(
                onDismissRequest = { selectedMessageForSourcePicker = null },
                sources = sources,
                currentSourceId = -1L,
                title = "Select Source to Search",
                onSourceSelected = { source ->
                    val mangaTitle = currentMessage.mangaTitle
                    if (mangaTitle != null) {
                        navigator.push(
                            BrowseSourceScreen(
                                sourceId = source.id,
                                listingQuery = mangaTitle,
                                smartJumpTitle = mangaTitle,
                                smartJumpSessionId = java.util.UUID.randomUUID().toString()
                            )
                        )
                    } else {
                        context.toast("Error: Shared manga title is missing.")
                    }
                },
                excludeCurrentSource = false,
            )
        }
    }
}

package eu.kanade.tachiyomi.ui.chat

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.lifecycle.viewmodel.compose.viewModel
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.tab.TabOptions
import eu.kanade.presentation.util.Tab
import tachiyomi.presentation.core.i18n.stringResource

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

        GlobalChatScreen(
            state = state,
            onSetUsername = viewModel::setUsername,
            onSendMessage = viewModel::sendMessage,
        )
    }
}

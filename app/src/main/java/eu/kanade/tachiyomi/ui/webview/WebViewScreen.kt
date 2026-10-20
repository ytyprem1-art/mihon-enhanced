package eu.kanade.tachiyomi.ui.webview

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.zacsweers.metrox.viewmodel.assistedMetroViewModel
import eu.kanade.presentation.util.AssistContentScreen
import eu.kanade.presentation.util.Screen
import eu.kanade.presentation.webview.WebViewScreenContent
import tachiyomi.presentation.core.screens.LoadingScreen

class WebViewScreen(
    private val url: String,
    private val initialTitle: String? = null,
    private val sourceId: Long? = null,
    private val onDismissed: (() -> Unit)? = null,
    private val onAutoCloseCondition: (suspend (url: String, html: String) -> Boolean)? = null,
) : Screen(), AssistContentScreen {

    private var assistUrl: String? = null

    override fun onProvideAssistUrl() = assistUrl

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val viewModel =
            assistedMetroViewModel<WebViewViewModel, WebViewViewModel.Factory> { create(sourceId = sourceId) }

        val headers by viewModel.headers.collectAsState()
        if (headers == null) {
            LoadingScreen()
            return
        }

        val handlePop = {
            navigator.pop()
            onDismissed?.invoke()
            Unit
        }

        BackHandler(enabled = true, onBack = handlePop)

        WebViewScreenContent(
            onNavigateUp = handlePop,
            initialTitle = initialTitle,
            url = url,
            headers = headers.orEmpty(),
            defaultUserAgentProvider = viewModel::defaultUserAgentProvider,
            onUrlChange = { assistUrl = it },
            onShare = { viewModel.shareWebpage(context, it) },
            onOpenInBrowser = { viewModel.openInBrowser(context, it) },
            onClearCookies = viewModel::clearCookies,
            onAutoCloseCondition = onAutoCloseCondition,
        )
    }
}

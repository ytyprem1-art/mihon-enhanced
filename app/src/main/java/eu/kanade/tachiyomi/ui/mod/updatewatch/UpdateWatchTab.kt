package eu.kanade.tachiyomi.ui.mod.updatewatch

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.currentOrThrow
import cafe.adriel.voyager.navigator.tab.TabOptions
import eu.kanade.presentation.util.Tab
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import eu.kanade.tachiyomi.ui.mod.updatewatch.UpdateWatchManagerScreen
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.components.material.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import eu.kanade.presentation.components.AppBarTitle
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import androidx.compose.material.icons.automirrored.outlined.ListAlt
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import tachiyomi.domain.history.model.UpdateWatchInboxItem
import eu.kanade.tachiyomi.ui.mod.updatewatch.components.UpdateWatchInboxSheet

data object UpdateWatchTab : Tab {

    var openInboxOnLoad = false

    override val options: TabOptions
        @Composable
        get() {
            return TabOptions(
                index = 2u,
                title = "Update Watch",
                icon = rememberVectorPainter(Icons.Outlined.NotificationsActive),
            )
        }

    override suspend fun onReselect(navigator: Navigator) {
        // No-op for now
    }

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val screenModel = rememberScreenModel { UpdateWatchScreenModel() }
        val state by screenModel.state.collectAsState()
        val snackbarHostState = remember { SnackbarHostState() }

        var showInboxSheet by remember { mutableStateOf(false) }

        LaunchedEffect(Unit) {
            if (openInboxOnLoad) {
                openInboxOnLoad = false
                screenModel.triggerInboxSheet()
            }
        }

        LaunchedEffect(state.showInboxOnLoad) {
            if (state.showInboxOnLoad) {
                showInboxSheet = true
                screenModel.clearInboxLoadTrigger()
            }
        }

        Scaffold(
            topBar = { scrollBehavior ->
                AppBar(
                    titleContent = { AppBarTitle("Update Watch") },
                    actions = {
                        AppBarActions(
                            listOf(
                                AppBar.Action(
                                    title = "Tracked manga",
                                    icon = Icons.AutoMirrored.Outlined.ListAlt,
                                    onClick = { navigator.push(UpdateWatchManagerScreen()) },
                                )
                            )
                        )
                    },
                    scrollBehavior = scrollBehavior,
                )
            },
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
            floatingActionButton = {
                val inboxCount = state.enrichedInboxItems.size
                if (inboxCount > 0) {
                    val updateCount = state.enrichedInboxItems.count { it.item.type == UpdateWatchInboxItem.TYPE_UPDATE }
                    val warningCount = state.enrichedInboxItems.count { it.item.type == UpdateWatchInboxItem.TYPE_INACTIVITY_WARNING }

                    val text = when {
                        updateCount > 0 && warningCount > 0 -> "$updateCount updates, $warningCount warnings"
                        updateCount > 0 -> if (updateCount == 1) "1 update found" else "$updateCount updates found"
                        warningCount > 0 -> if (warningCount == 1) "1 inactivity warning" else "$warningCount inactivity warnings"
                        else -> "$inboxCount items in inbox"
                    }

                    ExtendedFloatingActionButton(
                        text = { Text(text) },
                        icon = { Icon(Icons.Outlined.NotificationsActive, contentDescription = null) },
                        onClick = { showInboxSheet = true },
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    )
                } else {
                    FloatingActionButton(
                        onClick = { showInboxSheet = true },
                    ) {
                        Icon(Icons.Outlined.Notifications, contentDescription = "Updates Inbox")
                    }
                }
            }
        ) { contentPadding ->
            UpdateWatchContent(
                state = state,
                contentPadding = contentPadding,
                onClickManga = { navigator.push(MangaScreen(it)) },
                onPauseTracking = screenModel::pauseTracking,
            )

            if (showInboxSheet) {
                UpdateWatchInboxSheet(
                    items = state.enrichedInboxItems,
                    notificationsEnabled = state.notificationsEnabled,
                    onDismissRequest = { showInboxSheet = false },
                    onClickItem = { navigator.push(MangaScreen(it)) },
                    onDeleteItem = screenModel::dismissInboxItem,
                    onDisableAutoRefresh = screenModel::disableAutoRefresh,
                    onToggleNotifications = screenModel::toggleNotifications,
                )
            }
        }
    }
}

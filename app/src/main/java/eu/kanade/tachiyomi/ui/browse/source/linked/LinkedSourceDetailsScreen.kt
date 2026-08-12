package eu.kanade.tachiyomi.ui.browse.source.linked

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.compose.viewModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.browse.LinkedSourceDetailsScreen
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.ui.manga.LinkedMember
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.flow.collectLatest
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class LinkedSourceDetailsScreen(private val groupId: Long) : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val viewModel = viewModel<LinkedSourceDetailsViewModel>(
            factory = LinkedSourceDetailsViewModel.Factory,
            extras = CreationExtras {
                set(LinkedSourceDetailsViewModel.GROUP_ID_KEY, groupId)
            },
        )
        val state by viewModel.state.collectAsState()

        var memberToDelete by remember { mutableStateOf<LinkedMember?>(null) }

        LinkedSourceDetailsScreen(
            group = state.group,
            members = state.members,
            refreshingIds = state.refreshingIds,
            onClickMember = { navigator.push(MangaScreen(it.manga.id)) },
            onClickAdd = {
                state.group?.let {
                    navigator.push(LinkedSourceSearchScreen(it.id, it.name))
                }
            },
            onClickCreateHistoryGroup = viewModel::createHistoryGroup,
            onClickSetTrackingSource = viewModel::showTrackingSourcePicker,
            onRefreshMember = { viewModel.refreshMember(it.manga.id) },
            onDeleteMember = { memberToDelete = it },
            navigateUp = navigator::pop,
        )

        memberToDelete?.let { member ->
            val sourceManager = remember { Injekt.get<SourceManager>() }
            val sourceName = remember(member.manga.source) {
                sourceManager.getOrStub(member.manga.source).name
            }
            AlertDialog(
                onDismissRequest = { memberToDelete = null },
                title = { Text("Remove Source") },
                text = {
                    Text("Remove '${member.manga.title}' from '$sourceName' group?")
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            viewModel.removeMember(member.manga.id, member.manga.source)
                            memberToDelete = null
                        },
                    ) {
                        Text(stringResource(MR.strings.action_remove))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { memberToDelete = null }) {
                        Text(stringResource(MR.strings.action_cancel))
                    }
                },
            )
        }

        when (val dialog = state.dialog) {
            is LinkedSourceDetailsViewModel.Dialog.CreateHistoryGroupWarning -> {
                val isEligible = dialog.eligible.size >= 2
                val sourceManager = remember { Injekt.get<SourceManager>() }

                AlertDialog(
                    onDismissRequest = viewModel::dismissDialog,
                    title = { Text("Some sources will be skipped") },
                    text = {
                        androidx.compose.foundation.layout.Column {
                            if (dialog.withoutHistory.isNotEmpty()) {
                                Text(
                                    text = "No read history:",
                                    style = androidx.compose.material3.MaterialTheme.typography.labelLarge,
                                    color = androidx.compose.material3.MaterialTheme.colorScheme.error
                                )
                                Text(
                                    text = dialog.withoutHistory.joinToString(", ") {
                                        sourceManager.get(it.manga.source)?.name ?: "Unknown source"
                                    },
                                    style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.padding(bottom = 8.dp)
                                )
                            }
                            if (dialog.inOtherGroup.isNotEmpty()) {
                                Text(
                                    text = "Already in another History Group:",
                                    style = androidx.compose.material3.MaterialTheme.typography.labelLarge,
                                    color = androidx.compose.material3.MaterialTheme.colorScheme.error
                                )
                                Text(
                                    text = dialog.inOtherGroup.joinToString(", ") {
                                        sourceManager.get(it.manga.source)?.name ?: "Unknown source"
                                    },
                                    style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.padding(bottom = 8.dp)
                                )
                            }
                            Text(
                                text = if (isEligible) {
                                    "Create history group using the remaining ${dialog.eligible.size} sources?"
                                } else {
                                    "At least two eligible sources are required to create a History Group."
                                },
                                style = androidx.compose.material3.MaterialTheme.typography.bodyLarge
                            )
                        }
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                if (isEligible) {
                                    viewModel.performCreateHistoryGroup(state.group?.name ?: "", dialog.eligible.map { it.manga.id })
                                } else {
                                    viewModel.dismissDialog()
                                }
                            },
                        ) {
                            Text(stringResource(MR.strings.action_ok))
                        }
                    },
                    dismissButton = if (isEligible) {
                        {
                            TextButton(onClick = viewModel::dismissDialog) {
                                Text(stringResource(MR.strings.action_cancel))
                            }
                        }
                    } else null,
                )
            }
            is LinkedSourceDetailsViewModel.Dialog.TrackingSourcePicker -> {
                val sourceManager = remember { Injekt.get<SourceManager>() }
                AlertDialog(
                    onDismissRequest = viewModel::dismissDialog,
                    title = { Text("Manage tracking") },
                    text = {
                        androidx.compose.foundation.lazy.LazyColumn {
                            items(
                                items = state.members,
                                key = { it.manga.id }
                            ) { member ->
                                androidx.compose.material3.ListItem(
                                    modifier = Modifier.clickable {
                                        viewModel.toggleTracking(member.manga.id)
                                    },
                                    headlineContent = { Text(member.manga.title) },
                                    supportingContent = {
                                        Text(sourceManager.getOrStub(member.manga.source).name)
                                    },
                                    trailingContent = {
                                        Checkbox(
                                            checked = member.isTracking,
                                            onCheckedChange = null
                                        )
                                    }
                                )
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = viewModel::dismissDialog) {
                            Text(stringResource(MR.strings.action_ok))
                        }
                    },
                )
            }
            null -> {}
        }

        LaunchedEffect(Unit) {
            viewModel.events.collect { event: LinkedSourceDetailsViewModel.Event ->
                when (event) {
                    LinkedSourceDetailsViewModel.Event.HistoryGroupCreated -> {
                        context.toast("History group created")
                    }
                    is LinkedSourceDetailsViewModel.Event.ShowMessage -> {
                        context.toast(event.message)
                    }
                }
            }
        }
    }
}

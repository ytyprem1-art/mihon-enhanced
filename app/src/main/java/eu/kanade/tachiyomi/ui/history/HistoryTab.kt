package eu.kanade.tachiyomi.ui.history

import android.content.Context
import androidx.compose.animation.graphics.res.animatedVectorResource
import androidx.compose.animation.graphics.res.rememberAnimatedVectorPainter
import androidx.compose.animation.graphics.vector.AnimatedImageVector
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.currentOrThrow
import cafe.adriel.voyager.navigator.tab.LocalTabNavigator
import cafe.adriel.voyager.navigator.tab.TabOptions
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource as androidStringResource
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.category.components.ChangeCategoryDialog
import eu.kanade.presentation.history.HistoryScreen
import eu.kanade.presentation.history.components.HistoryCategoryDialog
import eu.kanade.presentation.history.components.HistoryDeleteAllDialog
import eu.kanade.presentation.history.components.HistoryDeleteDialog
import eu.kanade.presentation.manga.DuplicateMangaDialog
import eu.kanade.presentation.util.Tab
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.ui.category.CategoryScreen
import eu.kanade.tachiyomi.ui.browse.source.linked.LinkedSourcesScreen
import eu.kanade.tachiyomi.ui.mod.historygroup.HistoryGroupDetailScreen
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.receiveAsFlow
import mihon.feature.migration.dialog.MigrateMangaDialog
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.history.group.model.HistoryGroup
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import kotlinx.coroutines.launch
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember

data object HistoryTab : Tab {

    private val snackbarHostState = SnackbarHostState()

    private val resumeLastChapterReadEvent = Channel<Unit>()

    override val options: TabOptions
        @Composable
        get() {
            val isSelected = LocalTabNavigator.current.current.key == key
            val image = AnimatedImageVector.animatedVectorResource(R.drawable.anim_history_enter)
            return TabOptions(
                index = 3u,
                title = stringResource(MR.strings.label_recent_manga),
                icon = rememberAnimatedVectorPainter(image, isSelected),
            )
        }

    override suspend fun onReselect(navigator: Navigator) {
        resumeLastChapterReadEvent.send(Unit)
    }

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val viewModel = viewModel<HistoryViewModel>()
        val state by viewModel.state.collectAsStateWithLifecycle()

        HistoryScreen(
            state = state,
            snackbarHostState = snackbarHostState,
            onSearchQueryChange = viewModel::updateSearchQuery,
            onClickCover = { navigator.push(MangaScreen(it)) },
            onClickResume = viewModel::getNextChapterForManga,
            onDialogChange = viewModel::setDialog,
            onClickFavorite = viewModel::addFavorite,
            onTabSelected = viewModel::updateSelectedCategory,
            onClickChangeCategory = viewModel::showChangeHistoryCategoryDialog,
            onClickLinkedSourceGroups = { navigator.push(LinkedSourcesScreen()) },
            onClickGroup = { navigator.push(HistoryGroupDetailScreen(it)) },
            viewModel = viewModel,
        )

        val onDismissRequest = { viewModel.setDialog(null) }
        when (val dialog = state.dialog) {

            is HistoryViewModel.Dialog.CreateHistoryCategory -> {
                var categoryName by remember { mutableStateOf("") }
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = onDismissRequest,
                    title = { androidx.compose.material3.Text(androidStringResource(R.string.history_categories_create_title)) },
                    text = {
                        androidx.compose.material3.OutlinedTextField(
                            value = categoryName,
                            onValueChange = { categoryName = it },
                            label = { androidx.compose.material3.Text(androidStringResource(R.string.history_categories_name)) },
                            singleLine = true
                        )
                    },
                    confirmButton = {
                        androidx.compose.material3.TextButton(
                            onClick = {
                                if (categoryName.isNotBlank()) {
                                    viewModel.createHistoryCategory(categoryName)
                                    onDismissRequest()
                                }
                            }
                        ) {
                            androidx.compose.material3.Text(androidStringResource(R.string.history_categories_save))
                        }
                    },
                    dismissButton = {
                        androidx.compose.material3.TextButton(onClick = onDismissRequest) {
                            androidx.compose.material3.Text(stringResource(MR.strings.action_cancel))
                        }
                    }
                )
            }

            is HistoryViewModel.Dialog.ManageHistoryCategory -> {
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = onDismissRequest,
                    title = { androidx.compose.material3.Text(androidStringResource(R.string.history_categories_edit_title)) },
                    text = {
                        androidx.compose.foundation.layout.Column {
                            androidx.compose.material3.Text(
                                text = androidStringResource(R.string.history_categories_current, dialog.category.name),
                                style = androidx.compose.material3.MaterialTheme.typography.bodyMedium
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            androidx.compose.material3.TextButton(
                                onClick = {
                                    viewModel.setDialog(HistoryViewModel.Dialog.RenameHistoryCategory(dialog.category))
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                                ) {
                                    androidx.compose.material3.Icon(
                                        imageVector = Icons.Outlined.Edit,
                                        contentDescription = null
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    androidx.compose.material3.Text(androidStringResource(R.string.history_categories_rename))
                                }
                            }
                            Row(modifier = Modifier.fillMaxWidth()) {
                                androidx.compose.material3.OutlinedButton(
                                    onClick = {
                                        viewModel.moveHistoryCategoryLeft(dialog.category)
                                        onDismissRequest()
                                    },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    androidx.compose.material3.Text(androidStringResource(R.string.history_categories_move_left))
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                androidx.compose.material3.OutlinedButton(
                                    onClick = {
                                        viewModel.moveHistoryCategoryRight(dialog.category)
                                        onDismissRequest()
                                    },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    androidx.compose.material3.Text(androidStringResource(R.string.history_categories_move_right))
                                }
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            androidx.compose.material3.TextButton(
                                onClick = {
                                    viewModel.setDialog(HistoryViewModel.Dialog.DeleteHistoryCategory(dialog.category))
                                },
                                modifier = Modifier.fillMaxWidth(),
                                colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                                    contentColor = androidx.compose.material3.MaterialTheme.colorScheme.error
                                )
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                                ) {
                                    androidx.compose.material3.Icon(
                                        imageVector = Icons.Outlined.Delete,
                                        contentDescription = null
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    androidx.compose.material3.Text(androidStringResource(R.string.history_categories_delete))
                                }
                            }
                        }
                    },
                    confirmButton = {
                        androidx.compose.material3.TextButton(onClick = onDismissRequest) {
                            androidx.compose.material3.Text(stringResource(MR.strings.action_ok))
                        }
                    }
                )
            }

            is HistoryViewModel.Dialog.RenameHistoryCategory -> {
                var categoryName by remember { mutableStateOf(dialog.category.name) }
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = onDismissRequest,
                    title = { androidx.compose.material3.Text(androidStringResource(R.string.history_categories_rename_title)) },
                    text = {
                        androidx.compose.material3.OutlinedTextField(
                            value = categoryName,
                            onValueChange = { categoryName = it },
                            label = { androidx.compose.material3.Text(androidStringResource(R.string.history_categories_name)) },
                            singleLine = true
                        )
                    },
                    confirmButton = {
                        androidx.compose.material3.TextButton(
                            onClick = {
                                if (categoryName.isNotBlank()) {
                                    viewModel.renameHistoryCategory(dialog.category.id, categoryName)
                                    onDismissRequest()
                                }
                            }
                        ) {
                            androidx.compose.material3.Text(androidStringResource(R.string.history_categories_save))
                        }
                    },
                    dismissButton = {
                        androidx.compose.material3.TextButton(onClick = onDismissRequest) {
                            androidx.compose.material3.Text(stringResource(MR.strings.action_cancel))
                        }
                    }
                )
            }
            is HistoryViewModel.Dialog.DeleteHistoryCategory -> {
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = onDismissRequest,
                    title = { androidx.compose.material3.Text(androidStringResource(R.string.history_categories_delete_title)) },
                    text = { androidx.compose.material3.Text(androidStringResource(R.string.history_categories_delete_msg)) },
                    confirmButton = {
                        androidx.compose.material3.TextButton(
                            onClick = {
                                viewModel.deleteHistoryCategory(dialog.category.id)
                                onDismissRequest()
                            }
                        ) {
                            androidx.compose.material3.Text(androidStringResource(R.string.history_categories_delete))
                        }
                    },
                    dismissButton = {
                        androidx.compose.material3.TextButton(onClick = onDismissRequest) {
                            androidx.compose.material3.Text(stringResource(MR.strings.action_cancel))
                        }
                    }
                )
            }

            is HistoryViewModel.Dialog.Delete -> {
                HistoryDeleteDialog(
                    onDismissRequest = onDismissRequest,
                    onDelete = { all ->
                        if (all) {
                            viewModel.removeAllFromHistory(dialog.history.mangaId)
                        } else {
                            viewModel.removeFromHistory(dialog.history)
                        }
                    },
                )
            }
            is HistoryViewModel.Dialog.DeleteSelected -> {
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = onDismissRequest,
                    title = { androidx.compose.material3.Text(androidStringResource(R.string.history_delete_selected_title)) },
                    text = { androidx.compose.material3.Text(androidStringResource(R.string.history_delete_selected_msg)) },
                    confirmButton = {
                        androidx.compose.material3.TextButton(
                            onClick = {
                                viewModel.removeSelectedFromHistory(dialog.mangaIds)
                                onDismissRequest()
                            }
                        ) {
                            androidx.compose.material3.Text(androidStringResource(R.string.history_categories_delete))
                        }
                    },
                    dismissButton = {
                        androidx.compose.material3.TextButton(onClick = onDismissRequest) {
                            androidx.compose.material3.Text(stringResource(MR.strings.action_cancel))
                        }
                    }
                )
            }
            is HistoryViewModel.Dialog.DeleteAll -> {
                HistoryDeleteAllDialog(
                    onDismissRequest = onDismissRequest,
                    onDelete = viewModel::removeAllHistory,
                )
            }
            is HistoryViewModel.Dialog.DuplicateManga -> {
                DuplicateMangaDialog(
                    duplicates = dialog.duplicates,
                    onDismissRequest = onDismissRequest,
                    onConfirm = { viewModel.addFavorite(dialog.manga) },
                    onOpenManga = { navigator.push(MangaScreen(it.id)) },
                    onMigrate = { viewModel.showMigrateDialog(dialog.manga, it) },
                )
            }
            is HistoryViewModel.Dialog.ChangeHistoryCategory -> {
                HistoryCategoryDialog(
                    categories = dialog.categories,
                    initialSelection = dialog.initialSelection,
                    onDismissRequest = onDismissRequest,
                    onConfirm = { categoryId ->
                        viewModel.moveMangaToHistoryCategory(dialog.mangaId, categoryId)
                    },
                )
            }
            is HistoryViewModel.Dialog.MoveSelectedToHistoryCategory -> {
                HistoryCategoryDialog(
                    categories = dialog.categories,
                    initialSelection = 0L,
                    onDismissRequest = onDismissRequest,
                    onConfirm = { categoryId ->
                        viewModel.moveSelectedToHistoryCategory(dialog.mangaIds, categoryId)
                    },
                )
            }
            is HistoryViewModel.Dialog.CreateHistoryGroup -> {
                var groupName by remember(dialog) { mutableStateOf(dialog.suggestedName) }
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = onDismissRequest,
                    title = { androidx.compose.material3.Text("Create History Group") },
                    text = {
                        androidx.compose.material3.OutlinedTextField(
                            value = groupName,
                            onValueChange = { groupName = it },
                            label = { androidx.compose.material3.Text("Group Name") },
                            singleLine = true
                        )
                    },
                    confirmButton = {
                        androidx.compose.material3.TextButton(
                            onClick = {
                                if (groupName.isNotBlank()) {
                                    viewModel.createHistoryGroup(groupName, dialog.mangaIds)
                                }
                            }
                        ) {
                            androidx.compose.material3.Text(stringResource(MR.strings.action_ok))
                        }
                    },
                    dismissButton = {
                        androidx.compose.material3.TextButton(onClick = onDismissRequest) {
                            androidx.compose.material3.Text(stringResource(MR.strings.action_cancel))
                        }
                    }
                )
            }
            is HistoryViewModel.Dialog.AddToHistoryGroup -> {
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = onDismissRequest,
                    title = { androidx.compose.material3.Text("Add to History Group") },
                    text = {
                        if (dialog.groups.isEmpty()) {
                            androidx.compose.material3.Text("No existing groups found.")
                        } else {
                            androidx.compose.foundation.lazy.LazyColumn {
                                items(
                                    items = dialog.groups,
                                    key = { it.id }
                                ) { group ->
                                    androidx.compose.material3.ListItem(
                                        modifier = Modifier.clickable {
                                            viewModel.addMangaToHistoryGroup(dialog.mangaId, group.id)
                                        },
                                        headlineContent = { androidx.compose.material3.Text(group.name) },
                                    )
                                }
                            }
                        }
                    },
                    confirmButton = {},
                    dismissButton = {
                        androidx.compose.material3.TextButton(onClick = onDismissRequest) {
                            androidx.compose.material3.Text(stringResource(MR.strings.action_cancel))
                        }
                    }
                )
            }
            is HistoryViewModel.Dialog.RenameHistoryGroup -> {
                var groupName by remember(dialog) { mutableStateOf(dialog.group.name) }
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = onDismissRequest,
                    title = { androidx.compose.material3.Text("Rename History Group") },
                    text = {
                        androidx.compose.material3.OutlinedTextField(
                            value = groupName,
                            onValueChange = { groupName = it },
                            label = { androidx.compose.material3.Text("Group Name") },
                            singleLine = true
                        )
                    },
                    confirmButton = {
                        androidx.compose.material3.TextButton(
                            onClick = {
                                if (groupName.isNotBlank()) {
                                    viewModel.renameHistoryGroup(dialog.group.id, groupName)
                                }
                            }
                        ) {
                            androidx.compose.material3.Text(stringResource(MR.strings.action_ok))
                        }
                    },
                    dismissButton = {
                        androidx.compose.material3.TextButton(onClick = onDismissRequest) {
                            androidx.compose.material3.Text(stringResource(MR.strings.action_cancel))
                        }
                    }
                )
            }
            is HistoryViewModel.Dialog.DeleteHistoryGroup -> {
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = onDismissRequest,
                    title = { androidx.compose.material3.Text("Delete history group?") },
                    text = { androidx.compose.material3.Text("Members will return to the normal History list. Reading history will not be deleted.") },
                    confirmButton = {
                        androidx.compose.material3.TextButton(
                            onClick = {
                                viewModel.deleteHistoryGroup(dialog.group.id)
                                onDismissRequest()
                            }
                        ) {
                            androidx.compose.material3.Text(stringResource(MR.strings.action_ok))
                        }
                    },
                    dismissButton = {
                        androidx.compose.material3.TextButton(onClick = onDismissRequest) {
                            androidx.compose.material3.Text(stringResource(MR.strings.action_cancel))
                        }
                    }
                )
            }
            is HistoryViewModel.Dialog.ChangeCategory -> {
                ChangeCategoryDialog(
                    initialSelection = dialog.initialSelection,
                    onDismissRequest = onDismissRequest,
                    onEditCategories = { navigator.push(CategoryScreen()) },
                    onConfirm = { include, _ ->
                        viewModel.moveMangaToCategoriesAndAddToLibrary(dialog.manga, include)
                    },
                )
            }
            is HistoryViewModel.Dialog.Migrate -> {
                MigrateMangaDialog(
                    current = dialog.current,
                    target = dialog.target,
                    // Initiated from the context of [dialog.target] so we show [dialog.current].
                    onClickTitle = { navigator.push(MangaScreen(dialog.current.id)) },
                    onDismissRequest = onDismissRequest,
                )
            }
            null -> {}
        }

        LaunchedEffect(state.list) {
            if (state.list != null) {
                (context as? MainActivity)?.ready = true
            }
        }

        LaunchedEffect(Unit) {
            viewModel.events.collectLatest { e ->
                when (e) {
                    HistoryViewModel.Event.InternalError ->
                        snackbarHostState.showSnackbar(context.stringResource(MR.strings.internal_error))
                    HistoryViewModel.Event.HistoryCleared ->
                        snackbarHostState.showSnackbar(context.stringResource(MR.strings.clear_history_completed))
                    HistoryViewModel.Event.HistoryGroupCreated ->
                        snackbarHostState.showSnackbar("History group created")
                    HistoryViewModel.Event.AddedToHistoryGroup ->
                        snackbarHostState.showSnackbar("Added to history group")
                    is HistoryViewModel.Event.Error ->
                        snackbarHostState.showSnackbar(e.message)
                    is HistoryViewModel.Event.OpenChapter -> openChapter(context, e.chapter)
                }
            }
        }

        LaunchedEffect(Unit) {
            resumeLastChapterReadEvent.receiveAsFlow().collectLatest {
                openChapter(context, viewModel.getNextChapter())
            }
        }
    }

    private suspend fun openChapter(context: Context, chapter: Chapter?) {
        if (chapter != null) {
            val intent = ReaderActivity.newIntent(context, chapter.mangaId, chapter.id)
            context.startActivity(intent)
        } else {
            snackbarHostState.showSnackbar(context.stringResource(MR.strings.no_next_chapter))
        }
    }
}

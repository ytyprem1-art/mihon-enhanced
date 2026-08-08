package eu.kanade.tachiyomi.ui.history

import android.content.Context
import androidx.compose.animation.graphics.res.animatedVectorResource
import androidx.compose.animation.graphics.res.rememberAnimatedVectorPainter
import androidx.compose.animation.graphics.vector.AnimatedImageVector
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import cafe.adriel.voyager.core.model.rememberScreenModel
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

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
        val screenModel = rememberScreenModel { HistoryScreenModel() }
        val state by screenModel.state.collectAsState()

        HistoryScreen(
            state = state,
            snackbarHostState = snackbarHostState,
            onSearchQueryChange = screenModel::updateSearchQuery,
            onClickCover = { navigator.push(MangaScreen(it)) },
            onClickResume = screenModel::getNextChapterForManga,
            onDialogChange = screenModel::setDialog,
            onClickFavorite = screenModel::addFavorite,
            onTabSelected = screenModel::updateSelectedCategory,
            onClickChangeCategory = screenModel::showChangeHistoryCategoryDialog,
            onClickLinkedSourceGroups = { navigator.push(LinkedSourcesScreen()) },
            onClickGroup = { navigator.push(HistoryGroupDetailScreen(it)) },
            screenModel = screenModel,
        )

        val onDismissRequest = { screenModel.setDialog(null) }
        when (val dialog = state.dialog) {

            // 👇 TAMBAHKAN BLOK DIALOG KUSTOM INI DI SINI
            is HistoryScreenModel.Dialog.CreateHistoryCategory -> {
                var categoryName by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }
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
                                    screenModel.createHistoryCategory(categoryName)
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

            is HistoryScreenModel.Dialog.ManageHistoryCategory -> {
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
                                    screenModel.setDialog(HistoryScreenModel.Dialog.RenameHistoryCategory(dialog.category))
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
                                        screenModel.moveHistoryCategoryLeft(dialog.category)
                                        onDismissRequest()
                                    },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    androidx.compose.material3.Text(androidStringResource(R.string.history_categories_move_left))
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                androidx.compose.material3.OutlinedButton(
                                    onClick = {
                                        screenModel.moveHistoryCategoryRight(dialog.category)
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
                                    screenModel.setDialog(HistoryScreenModel.Dialog.DeleteHistoryCategory(dialog.category))
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

            is HistoryScreenModel.Dialog.RenameHistoryCategory -> {
                var categoryName by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(dialog.category.name) }
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
                                    screenModel.renameHistoryCategory(dialog.category.id, categoryName)
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
            is HistoryScreenModel.Dialog.DeleteHistoryCategory -> {
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = onDismissRequest,
                    title = { androidx.compose.material3.Text(androidStringResource(R.string.history_categories_delete_title)) },
                    text = { androidx.compose.material3.Text(androidStringResource(R.string.history_categories_delete_msg)) },
                    confirmButton = {
                        androidx.compose.material3.TextButton(
                            onClick = {
                                screenModel.deleteHistoryCategory(dialog.category.id)
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

            is HistoryScreenModel.Dialog.Delete -> {
                HistoryDeleteDialog(
                    onDismissRequest = onDismissRequest,
                    onDelete = { all ->
                        if (all) {
                            screenModel.removeAllFromHistory(dialog.history.mangaId)
                        } else {
                            screenModel.removeFromHistory(dialog.history)
                        }
                    },
                )
            }
            is HistoryScreenModel.Dialog.DeleteSelected -> {
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = onDismissRequest,
                    title = { androidx.compose.material3.Text(androidStringResource(R.string.history_delete_selected_title)) },
                    text = { androidx.compose.material3.Text(androidStringResource(R.string.history_delete_selected_msg)) },
                    confirmButton = {
                        androidx.compose.material3.TextButton(
                            onClick = {
                                screenModel.removeSelectedFromHistory(dialog.mangaIds)
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
            is HistoryScreenModel.Dialog.DeleteAll -> {
                HistoryDeleteAllDialog(
                    onDismissRequest = onDismissRequest,
                    onDelete = screenModel::removeAllHistory,
                )
            }
            is HistoryScreenModel.Dialog.DuplicateManga -> {
                DuplicateMangaDialog(
                    duplicates = dialog.duplicates,
                    onDismissRequest = onDismissRequest,
                    onConfirm = { screenModel.addFavorite(dialog.manga) },
                    onOpenManga = { navigator.push(MangaScreen(it.id)) },
                    onMigrate = { screenModel.showMigrateDialog(dialog.manga, it) },
                )
            }
            is HistoryScreenModel.Dialog.ChangeHistoryCategory -> {
                HistoryCategoryDialog(
                    categories = dialog.categories,
                    initialSelection = dialog.initialSelection,
                    onDismissRequest = onDismissRequest,
                    onConfirm = { categoryId ->
                        screenModel.moveMangaToHistoryCategory(dialog.mangaId, categoryId)
                    },
                )
            }
            is HistoryScreenModel.Dialog.MoveSelectedToHistoryCategory -> {
                HistoryCategoryDialog(
                    categories = dialog.categories,
                    initialSelection = 0L,
                    onDismissRequest = onDismissRequest,
                    onConfirm = { categoryId ->
                        screenModel.moveSelectedToHistoryCategory(dialog.mangaIds, categoryId)
                    },
                )
            }
            is HistoryScreenModel.Dialog.CreateHistoryGroup -> {
                var groupName by androidx.compose.runtime.remember(dialog) { androidx.compose.runtime.mutableStateOf(dialog.suggestedName) }
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
                                    screenModel.createHistoryGroup(groupName, dialog.mangaIds)
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
            is HistoryScreenModel.Dialog.AddToHistoryGroup -> {
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
                                            screenModel.addMangaToHistoryGroup(dialog.mangaId, group.id)
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
            is HistoryScreenModel.Dialog.RenameHistoryGroup -> {
                var groupName by androidx.compose.runtime.remember(dialog) { androidx.compose.runtime.mutableStateOf(dialog.group.name) }
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
                                    screenModel.renameHistoryGroup(dialog.group.id, groupName)
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
            is HistoryScreenModel.Dialog.DeleteHistoryGroup -> {
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = onDismissRequest,
                    title = { androidx.compose.material3.Text("Delete history group?") },
                    text = { androidx.compose.material3.Text("Members will return to the normal History list. Reading history will not be deleted.") },
                    confirmButton = {
                        androidx.compose.material3.TextButton(
                            onClick = {
                                screenModel.deleteHistoryGroup(dialog.group.id)
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
            is HistoryScreenModel.Dialog.ChangeCategory -> {
                ChangeCategoryDialog(
                    initialSelection = dialog.initialSelection,
                    onDismissRequest = onDismissRequest,
                    onEditCategories = { navigator.push(CategoryScreen()) },
                    onConfirm = { include, _ ->
                        screenModel.moveMangaToCategoriesAndAddToLibrary(dialog.manga, include)
                    },
                )
            }
            is HistoryScreenModel.Dialog.Migrate -> {
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
            screenModel.events.collectLatest { e ->
                when (e) {
                    HistoryScreenModel.Event.InternalError ->
                        snackbarHostState.showSnackbar(context.stringResource(MR.strings.internal_error))
                    HistoryScreenModel.Event.HistoryCleared ->
                        snackbarHostState.showSnackbar(context.stringResource(MR.strings.clear_history_completed))
                    HistoryScreenModel.Event.HistoryGroupCreated ->
                        snackbarHostState.showSnackbar("History group created")
                    HistoryScreenModel.Event.AddedToHistoryGroup ->
                        snackbarHostState.showSnackbar("Added to history group")
                    is HistoryScreenModel.Event.Error ->
                        snackbarHostState.showSnackbar(e.message)
                    is HistoryScreenModel.Event.OpenChapter -> openChapter(context, e.chapter)
                }
            }
        }

        LaunchedEffect(Unit) {
            resumeLastChapterReadEvent.receiveAsFlow().collectLatest {
                openChapter(context, screenModel.getNextChapter())
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

package eu.kanade.presentation.history

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ListAlt
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.CollectionsBookmark
import androidx.compose.material.icons.outlined.Create
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.FlipToBack
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Merge
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource as androidStringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.presentation.components.AppBarTitle
import eu.kanade.presentation.components.SearchToolbar
import eu.kanade.presentation.components.relativeDateText
import eu.kanade.presentation.history.components.HistoryItem
import eu.kanade.presentation.theme.TachiyomiPreviewTheme
import eu.kanade.presentation.util.isTabletUi
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.ui.history.HistoryViewModel
import eu.kanade.tachiyomi.ui.mod.components.HistoryAppBarActions
import eu.kanade.tachiyomi.ui.mod.components.HistorySearchExpandedActions
import kotlinx.datetime.LocalDate
import tachiyomi.domain.history.model.HistoryWithRelations
import tachiyomi.domain.manga.model.MangaCover
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import tachiyomi.presentation.core.components.FastScrollLazyColumn
import tachiyomi.presentation.core.components.ListGroupHeader
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.LoadingScreen
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch

@Composable
fun HistoryScreen(
    state: HistoryViewModel.State,
    snackbarHostState: SnackbarHostState,
    onSearchQueryChange: (String?) -> Unit,
    onClickCover: (mangaId: Long) -> Unit,
    onClickResume: (mangaId: Long, chapterId: Long) -> Unit,
    onClickFavorite: (mangaId: Long) -> Unit,
    onDialogChange: (HistoryViewModel.Dialog?) -> Unit,
    onTabSelected: (Long) -> Unit,
    onClickChangeCategory: (mangaId: Long) -> Unit,
    onClickLinkedSourceGroups: () -> Unit,
    onClickGroup: (groupId: Long) -> Unit,
    viewModel: HistoryViewModel,
) {
    val scrollStates = rememberSaveable(
        saver = Saver<MutableMap<Long, LazyListState>, Map<Long, List<Int>>>(
            save = { map ->
                map.mapValues { (_, state) ->
                    listOf(state.firstVisibleItemIndex, state.firstVisibleItemScrollOffset)
                }
            },
            restore = { savedMap ->
                val restoredMap = mutableMapOf<Long, LazyListState>()
                savedMap.forEach { (id, values) ->
                    restoredMap[id] = LazyListState(
                        firstVisibleItemIndex = values[0],
                        firstVisibleItemScrollOffset = values[1],
                    )
                }
                restoredMap
            },
        ),
    ) {
        mutableMapOf()
    }
    val scrollState = scrollStates.getOrPut(state.selectedCategoryId) { LazyListState() }

    val onCancelSelection = { viewModel.toggleSelectionMode() }
    val onCancelSearch = { onSearchQueryChange(null) }

    var searchActionsExpanded by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(state.searchQuery, state.selectedCategoryId) {
        if (state.searchQuery == null) {
            searchActionsExpanded = false
        }
    }

    BackHandler(enabled = state.selectionMode || state.searchQuery != null) {
        if (state.selectionMode) {
            onCancelSelection()
        } else {
            onCancelSearch()
        }
    }

    val filteredHistory = remember(state.list, state.selectedCategoryId, state.mangaToCategoryMap) {
        val history = state.list ?: emptyList()
        val result = history.filter { uiModel ->
            when (uiModel) {
                is HistoryUiModel.Header -> true
                is HistoryUiModel.Item -> {
                    val cId = state.mangaToCategoryMap[uiModel.item.mangaId] ?: 0L
                    cId == state.selectedCategoryId
                }
                is HistoryUiModel.Group -> {
                    val cId = state.mangaToCategoryMap[uiModel.representative.mangaId] ?: 0L
                    cId == state.selectedCategoryId
                }
            }
        }
        result.filterIndexed { index, uiModel ->
            if (uiModel is HistoryUiModel.Header) {
                val next = result.getOrNull(index + 1)
                next is HistoryUiModel.Item || next is HistoryUiModel.Group
            } else {
                true
            }
        }
    }

    Scaffold(
        topBar = { scrollBehavior ->
            Column(modifier = Modifier.fillMaxWidth()) {
                if (state.selectionMode) {
                    val filteredHistoryIds = remember(filteredHistory) {
                        filteredHistory.filterIsInstance<HistoryUiModel.Item>().map { it.item.mangaId }
                    }
                    AppBar(
                        titleContent = {
                            AppBarTitle(state.selected.size.toString())
                        },
                        actions = {
                            AppBarActions(
                                listOf(
                                    AppBar.Action(
                                        title = androidStringResource(R.string.history_categories_delete),
                                        icon = Icons.Outlined.Delete,
                                        onClick = {
                                            onDialogChange(
                                                HistoryViewModel.Dialog.DeleteSelected(state.selected)
                                            )
                                        },
                                        enabled = state.selected.isNotEmpty(),
                                    ),
                                    AppBar.Action(
                                        title = androidStringResource(R.string.history_categories_move_to),
                                        icon = Icons.Outlined.Folder,
                                        onClick = {
                                            onDialogChange(
                                                HistoryViewModel.Dialog.MoveSelectedToHistoryCategory(
                                                    state.selected,
                                                    state.historyCategories
                                                )
                                            )
                                        },
                                        enabled = state.selected.isNotEmpty(),
                                    ),
                                    AppBar.Action(
                                        title = if (state.selected.size == 1) "Add to history group" else "Create history group",
                                        icon = Icons.Outlined.Merge,
                                        onClick = {
                                            if (state.selected.size == 1) {
                                                viewModel.showAddToHistoryGroupDialog(state.selected.first())
                                            } else {
                                                val selectedItems = state.list?.filterIsInstance<HistoryUiModel.Item>()
                                                    ?.filter { it.item.mangaId in state.selected }
                                                    .orEmpty()

                                                val distinctTitles = selectedItems.map { it.item.title }.distinct()
                                                val suggestedName = if (distinctTitles.size == 1) {
                                                    distinctTitles.first()
                                                } else {
                                                    selectedItems.firstOrNull()?.item?.title ?: ""
                                                }

                                                onDialogChange(
                                                    HistoryViewModel.Dialog.CreateHistoryGroup(
                                                        state.selected,
                                                        suggestedName
                                                    )
                                                )
                                            }
                                        },
                                        enabled = run {
                                            if (state.selected.size >= 2) return@run true
                                            if (state.selected.size == 1) {
                                                // Only enable for ungrouped items as per requirements
                                                val mangaId = state.selected.first()
                                                val isGrouped = state.list?.filterIsInstance<HistoryUiModel.Group>()
                                                    ?.any { it.representative.mangaId == mangaId } == true
                                                !isGrouped
                                            } else {
                                                false
                                            }
                                        },
                                    ),
                                    AppBar.Action(
                                        title = stringResource(MR.strings.action_select_all),
                                        icon = Icons.Outlined.SelectAll,
                                        onClick = { viewModel.selectAll(filteredHistoryIds) },
                                    ),
                                    AppBar.Action(
                                        title = stringResource(MR.strings.action_select_inverse),
                                        icon = Icons.Outlined.FlipToBack,
                                        onClick = { viewModel.invertSelection(filteredHistoryIds) },
                                    ),
                                ),
                            )
                        },
                        isActionMode = true,
                        onCancelActionMode = onCancelSelection,
                        scrollBehavior = scrollBehavior,
                    )
                } else {
                    val manageCategoryText = androidStringResource(R.string.history_categories_manage)
                    val linkedGroupsText = "Linked source groups"
                    val selectText = androidStringResource(R.string.history_select)
                    val createCategoryText = androidStringResource(R.string.history_categories_create)
                    val clearHistoryText = stringResource(MR.strings.pref_clear_history)

                    val actions = remember(state.selectedCategoryId, state.historyCategories) {
                        val list = mutableListOf<AppBar.Action>()

                        // Tombol Edit Kategori (Hanya muncul jika bukan tab "Semua")
                        if (state.selectedCategoryId != 0L) {
                            state.historyCategories.find { it.id == state.selectedCategoryId }?.let { category ->
                                list.add(
                                    AppBar.Action(
                                        title = manageCategoryText,
                                        icon = Icons.Outlined.Settings,
                                        onClick = {
                                            onDialogChange(HistoryViewModel.Dialog.ManageHistoryCategory(category))
                                        },
                                    )
                                )
                            }
                        }

                        list.add(
                            AppBar.Action(
                                title = linkedGroupsText,
                                icon = Icons.Outlined.CollectionsBookmark,
                                onClick = onClickLinkedSourceGroups,
                            )
                        )

                        list.add(
                            AppBar.Action(
                                title = selectText,
                                icon = Icons.Outlined.Checklist,
                                onClick = { viewModel.toggleSelectionMode() },
                            )
                        )

                        list.add(
                            AppBar.Action(
                                title = createCategoryText,
                                icon = Icons.Outlined.Create,
                                onClick = {
                                    onDialogChange(HistoryViewModel.Dialog.CreateHistoryCategory)
                                },
                            )
                        )

                        list.add(
                            AppBar.Action(
                                title = clearHistoryText,
                                icon = Icons.Outlined.DeleteSweep,
                                onClick = {
                                    onDialogChange(HistoryViewModel.Dialog.DeleteAll)
                                },
                            ),
                        )
                        list
                    }

                    SearchToolbar(
                        titleContent = { AppBarTitle(stringResource(MR.strings.history)) },
                        searchQuery = state.searchQuery,
                        onChangeSearchQuery = onSearchQueryChange,
                        onClickCloseSearch = onCancelSearch,
                        actions = {
                            HistoryAppBarActions(
                                actions = actions,
                                isSearchActive = state.searchQuery != null,
                                isTabletUi = isTabletUi(),
                                isExpanded = searchActionsExpanded,
                                onToggleExpand = { searchActionsExpanded = !searchActionsExpanded },
                            )
                        },
                        scrollBehavior = scrollBehavior,
                    )

                    HistorySearchExpandedActions(
                        actions = actions,
                        isVisible = state.searchQuery != null && !isTabletUi() && searchActionsExpanded,
                    )
                }

                ScrollableTabRow(
                    selectedTabIndex = when (state.selectedCategoryId) {
                        0L -> 0
                        else -> {
                            val index = state.historyCategories.indexOfFirst { it.id == state.selectedCategoryId }
                            if (index == -1) 0 else index + 1
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Tab(
                        selected = state.selectedCategoryId == 0L,
                        onClick = { onTabSelected(0L) },
                        text = { Text(androidStringResource(R.string.history_categories_all)) },
                    )
                    state.historyCategories.forEach { category ->
                        Tab(
                            selected = state.selectedCategoryId == category.id,
                            onClick = { onTabSelected(category.id) },
                            text = { Text(category.name) },
                        )
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
    ) { contentPadding ->
        state.list.let {
            if (it == null) {
                LoadingScreen(Modifier.padding(contentPadding))
            } else if (it.isEmpty()) {
                val msg = if (!state.searchQuery.isNullOrEmpty()) {
                    MR.strings.no_results_found
                } else {
                    MR.strings.information_no_recent_manga
                }
                EmptyScreen(
                    stringRes = msg,
                    modifier = Modifier.padding(contentPadding),
                )
            } else {
                HistoryScreenContent(
                    history = filteredHistory,
                    contentPadding = contentPadding,
                    scrollState = scrollState,
                    selectionMode = state.selectionMode,
                    selected = state.selected,
                    onClickCover = { history -> onClickCover(history.mangaId) },
                    onClickResume = { history -> onClickResume(history.mangaId, history.chapterId) },
                    onClickDelete = { item -> onDialogChange(HistoryViewModel.Dialog.Delete(item)) },
                    onClickFavorite = { history -> onClickFavorite(history.mangaId) },
                    onClickChangeCategory = onClickChangeCategory,
                    onClickGroup = onClickGroup,
                    onClickRenameGroup = { group -> onDialogChange(HistoryViewModel.Dialog.RenameHistoryGroup(group)) },
                    onClickDeleteGroup = { group -> onDialogChange(HistoryViewModel.Dialog.DeleteHistoryGroup(group)) },
                    onToggleSelection = viewModel::toggleSelection,
                )
            }
        }
    }
}

@Composable
private fun HistoryScreenContent(
    history: List<HistoryUiModel>,
    contentPadding: PaddingValues,
    scrollState: LazyListState,
    selectionMode: Boolean,
    selected: Set<Long>,
    onClickCover: (HistoryWithRelations) -> Unit,
    onClickResume: (HistoryWithRelations) -> Unit,
    onClickDelete: (HistoryWithRelations) -> Unit,
    onClickFavorite: (HistoryWithRelations) -> Unit,
    onClickChangeCategory: (Long) -> Unit,
    onClickGroup: (Long) -> Unit,
    onClickRenameGroup: (tachiyomi.domain.history.group.model.HistoryGroup) -> Unit,
    onClickDeleteGroup: (tachiyomi.domain.history.group.model.HistoryGroup) -> Unit,
    onToggleSelection: (Long) -> Unit,
) {
    FastScrollLazyColumn(
        contentPadding = contentPadding,
        state = scrollState,
    ) {
        items(
            items = history,
            key = { item ->
                when (item) {
                    is HistoryUiModel.Header -> "header-${item.date}"
                    is HistoryUiModel.Item -> "item-${item.item.id}"
                    is HistoryUiModel.Group -> "group-${item.group.id}"
                }
            },
            contentType = { item ->
                when (item) {
                    is HistoryUiModel.Header -> "header"
                    is HistoryUiModel.Item -> "item"
                    is HistoryUiModel.Group -> "group"
                }
            },
        ) { item ->
            when (item) {
                is HistoryUiModel.Header -> {
                    ListGroupHeader(
                        modifier = Modifier.animateItem(),
                        text = relativeDateText(item.date),
                    )
                }

                is HistoryUiModel.Item -> {
                    val value = item.item

                    HistoryItem(
                        modifier = Modifier
                            .fillMaxWidth()
                            .animateItem(),
                        history = value,
                        onClickCover = { onClickCover(value) },
                        onClickResume = { onClickResume(value) },
                        onClickDelete = { onClickDelete(value) },
                        onClickFavorite = { onClickFavorite(value) },
                        onLongClick = {
                            if (selectionMode) {
                                onToggleSelection(value.mangaId)
                            } else {
                                onClickChangeCategory(value.mangaId)
                            }
                        },
                        selectionMode = selectionMode,
                        selected = value.mangaId in selected,
                    )
                }

                is HistoryUiModel.Group -> {
                    val value = item.representative
                    var showMenu by remember { mutableStateOf(false) }

                    HistoryItem(
                        modifier = Modifier
                            .fillMaxWidth()
                            .animateItem(),
                        history = value,
                        titleOverride = item.group.name,
                        onClickCover = { onClickGroup(item.group.id) },
                        onClickResume = { onClickGroup(item.group.id) },
                        onClickDelete = { onClickDeleteGroup(item.group) },
                        onClickFavorite = null,
                        onLongClick = {
                            onToggleSelection(value.mangaId)
                        },
                        selectionMode = selectionMode,
                        selected = value.mangaId in selected,
                        onOverflowClick = { showMenu = true },
                        overflowContent = {
                            androidx.compose.material3.DropdownMenu(
                                expanded = showMenu,
                                onDismissRequest = { showMenu = false },
                            ) {
                                androidx.compose.material3.DropdownMenuItem(
                                    text = { Text("Rename group") },
                                    onClick = {
                                        onClickRenameGroup(item.group)
                                        showMenu = false
                                    },
                                )
                            }
                        },
                        subtitleBadge = {
                            val metadata = remember(item.memberCount, item.sourceNames) {
                                val sourceList = item.sourceNames.distinct()
                                val displayLimit = 2
                                val displayedSources = sourceList.take(displayLimit)
                                val remainingCount = item.memberCount - displayedSources.size

                                buildString {
                                    append("${item.memberCount} sources")
                                    if (displayedSources.isNotEmpty()) {
                                        append(" · ")
                                        append(displayedSources.joinToString(" · "))
                                    }
                                    if (remainingCount > 0) {
                                        append(" · +$remainingCount")
                                    }
                                }
                            }
                            Text(
                                text = metadata,
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .background(
                                        MaterialTheme.colorScheme.secondaryContainer,
                                        MaterialTheme.shapes.extraSmall
                                    )
                                    .padding(horizontal = 4.dp, vertical = 2.dp),
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                    )
                }
            }
        }
    }
}

sealed interface HistoryUiModel {
    data class Header(val date: LocalDate) : HistoryUiModel
    data class Item(val item: HistoryWithRelations) : HistoryUiModel
    data class Group(
        val group: tachiyomi.domain.history.group.model.HistoryGroup,
        val representative: HistoryWithRelations,
        val memberCount: Int,
        val sourceNames: List<String>,
    ) : HistoryUiModel
}

@PreviewLightDark
@Composable
internal fun HistoryScreenPreviews(
    @PreviewParameter(HistoryviewModelStateProvider::class)
    historyState: HistoryViewModel.State,
) {
    TachiyomiPreviewTheme {
        Text("Preview Mode")
    }
}

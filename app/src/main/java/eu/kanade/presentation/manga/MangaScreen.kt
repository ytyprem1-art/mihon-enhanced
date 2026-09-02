package eu.kanade.presentation.manga

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.SmallExtendedFloatingActionButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.animateFloatingActionButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastAll
import androidx.compose.ui.util.fastAny
import androidx.compose.ui.util.fastMap
import eu.kanade.presentation.components.relativeDateText
import eu.kanade.presentation.manga.components.ChapterDownloadAction
import eu.kanade.presentation.manga.components.ChapterHeader
import eu.kanade.presentation.manga.components.ExpandableMangaDescription
import eu.kanade.presentation.manga.components.MangaActionRow
import eu.kanade.presentation.manga.components.MangaBottomActionMenu
import eu.kanade.presentation.manga.components.MangaChapterGroupListItem
import eu.kanade.presentation.manga.components.MangaChapterListItem
import eu.kanade.presentation.manga.components.MangaInfoBox
import eu.kanade.presentation.manga.components.MangaQuickSourceSwitcher
import eu.kanade.presentation.manga.components.MangaSmartSourceJump
import eu.kanade.presentation.manga.components.MangaToolbar
import eu.kanade.presentation.manga.components.MissingChapterCountListItem
import eu.kanade.presentation.util.formatChapterNumber
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.source.getNameForMangaInfo
import eu.kanade.tachiyomi.source.mod.MERGED_SOURCE_ID
import eu.kanade.tachiyomi.ui.manga.ChapterList
import eu.kanade.tachiyomi.ui.manga.MangaViewModel
import eu.kanade.tachiyomi.ui.mod.util.SourceColorUtil
import eu.kanade.tachiyomi.util.system.copyToClipboard
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.service.missingChaptersCount
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.model.StubSource
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import tachiyomi.presentation.core.components.TwoPanelBox
import tachiyomi.presentation.core.components.VerticalFastScroller
import tachiyomi.presentation.core.components.material.PullRefresh
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.shouldExpandFAB
import tachiyomi.source.local.isLocal
import kotlin.time.Instant

@Composable
fun MangaScreen(
    state: MangaViewModel.State.Success,
    snackbarHostState: SnackbarHostState,
    nextUpdate: Instant?,
    isTabletUi: Boolean,
    chapterSwipeStartAction: LibraryPreferences.ChapterSwipeAction,
    chapterSwipeEndAction: LibraryPreferences.ChapterSwipeAction,
    navigateUp: () -> Unit,
    onChapterClicked: (Chapter) -> Unit,
    onDownloadChapter: ((List<ChapterList.Item>, ChapterDownloadAction) -> Unit)?,
    onAddToLibraryClicked: () -> Unit,
    onWebViewClicked: (() -> Unit)?,
    onWebViewLongClicked: (() -> Unit)?,
    onTrackingClicked: () -> Unit,

    // For tags menu
    onTagSearch: (String) -> Unit,

    onFilterButtonClicked: () -> Unit,
    onRefresh: () -> Unit,
    onContinueReading: () -> Unit,
    onSearch: (query: String, global: Boolean) -> Unit,

    // For cover dialog
    onCoverClicked: () -> Unit,

    // For top action menu
    onShareClicked: (() -> Unit)?,
    onShareToChatClicked: (() -> Unit)?,
    onDownloadActionClicked: ((DownloadAction) -> Unit)?,
    onEditCategoryClicked: (() -> Unit)?,
    onEditFetchIntervalClicked: (() -> Unit)?,
    onMigrateClicked: (() -> Unit)?,
    onEditNotesClicked: () -> Unit,

    // For bottom action menu
    onMultiBookmarkClicked: (List<Chapter>, bookmarked: Boolean) -> Unit,
    onMultiMarkAsReadClicked: (List<Chapter>, markAsRead: Boolean) -> Unit,
    onMarkPreviousAsReadClicked: (Chapter) -> Unit,
    onMultiDeleteClicked: (List<Chapter>) -> Unit,

    // For chapter swipe
    onChapterSwipe: (ChapterList.Item, LibraryPreferences.ChapterSwipeAction) -> Unit,

    // Chapter selection
    onChapterSelected: (ChapterList.Item, Boolean, Boolean) -> Unit,
    onAllChapterSelected: (Boolean) -> Unit,
    onInvertSelection: () -> Unit,
    onLinkedSourcesClicked: () -> Unit,
    onUpdateWatchClicked: () -> Unit,
    // SY -->
    onMergedSettingsClicked: () -> Unit,
    onMergeClicked: () -> Unit,
    // SY <--
    onOpenSource: (Long, String) -> Unit,
) {
    val context = LocalContext.current
    val onCopyTagToClipboard: (tag: String) -> Unit = {
        if (it.isNotEmpty()) {
            context.copyToClipboard(it, it)
        }
    }

    val (_, listItem, _) = remember(state) {
        Triple(
            first = state.processedChapters,
            second = state.chapterListItems,
            third = state.isAnySelected,
        )
    }

    val sourceColorMap = remember(listItem) {
        val ids = listItem.filterIsInstance<ChapterList.Item>()
            .mapNotNull { it.sourceId }
            .distinct()
        if (ids.size > 1) {
            SourceColorUtil.getColorsForSources(ids)
        } else {
            emptyMap()
        }
    }

    val expandedChapters = rememberSaveable(
        saver = Saver(
            save = { it.keys.toList() },
            restore = {
                mutableStateMapOf<Double, Boolean>().apply {
                    it.forEach { num -> put(num, true) }
                }
            },
        ),
    ) {
        mutableStateMapOf<Double, Boolean>()
    }

    val groupedListItem by remember(listItem) {
        derivedStateOf {
            if (state.manga.source != MERGED_SOURCE_ID) return@derivedStateOf listItem

            val references = state.mergedData?.references ?: emptyList()
            val sortDescending = state.manga.sortDescending()

            val sortedItems = listItem.filterIsInstance<ChapterList.Item>()
                .groupBy { it.chapter.chapterNumber }
                .toList()
                .sortedWith { a, b ->
                    if (a.first == -1.0 && b.first == -1.0) return@sortedWith 0
                    if (a.first == -1.0) return@sortedWith 1
                    if (b.first == -1.0) return@sortedWith -1
                    if (sortDescending) b.first.compareTo(a.first) else a.first.compareTo(b.first)
                }
                .flatMap { (_, group) ->
                    group.sortedBy { item ->
                        references.find { it.mangaId == item.chapter.mangaId }?.chapterPriority ?: Int.MAX_VALUE
                    }
                }

            val result = mutableListOf<ChapterList>()
            var i = 0
            while (i < sortedItems.size) {
                val current = sortedItems[i]
                val group = mutableListOf<ChapterList.Item>()
                group.add(current)
                var j = i + 1
                while (j < sortedItems.size &&
                    sortedItems[j].chapter.chapterNumber == current.chapter.chapterNumber &&
                    current.chapter.chapterNumber != -1.0
                ) {
                    group.add(sortedItems[j])
                    j++
                }

                if (group.size > 1) {
                    val isExpanded = expandedChapters[current.chapter.chapterNumber] ?: false
                    result.add(
                        ChapterList.Group(
                            chapterNumber = current.chapter.chapterNumber,
                            items = group,
                            isExpanded = isExpanded,
                        ),
                    )
                    if (isExpanded) {
                        result.addAll(group.fastMap { it.copy(isGrouped = true) })
                    }
                    i = j
                } else {
                    result.add(current.copy(isGrouped = false))
                    i++
                }
            }
            result
        }
    }

    val anyChapterGrouped by remember(groupedListItem) {
        derivedStateOf {
            groupedListItem.fastAny { it is ChapterList.Group }
        }
    }

    val allGroupsExpanded by remember(groupedListItem, expandedChapters) {
        derivedStateOf {
            val groups = groupedListItem.filterIsInstance<ChapterList.Group>()
            groups.isNotEmpty() && groups.all { it.isExpanded }
        }
    }

    val onToggleAllExpand = {
        val groups = groupedListItem.filterIsInstance<ChapterList.Group>()
        if (allGroupsExpanded) {
            expandedChapters.clear()
        } else {
            groups.forEach {
                expandedChapters[it.chapterNumber] = true
            }
        }
    }

    val enhancedPreferences = remember { Injekt.get<eu.kanade.tachiyomi.ui.mod.EnhancedPreferences>() }
    val useSourceColor by enhancedPreferences.enableMergeSourceColors.collectAsState()
    val useChapterColor by enhancedPreferences.enableMergeChapterTitleColors.collectAsState()

    if (!isTabletUi) {
        MangaScreenSmallImpl(
            state = state,
            snackbarHostState = snackbarHostState,
            nextUpdate = nextUpdate,
            chapterSwipeStartAction = chapterSwipeStartAction,
            chapterSwipeEndAction = chapterSwipeEndAction,
            navigateUp = navigateUp,
            onChapterClicked = onChapterClicked,
            onDownloadChapter = onDownloadChapter,
            onAddToLibraryClicked = onAddToLibraryClicked,
            onWebViewClicked = onWebViewClicked,
            onWebViewLongClicked = onWebViewLongClicked,
            onTrackingClicked = onTrackingClicked,
            onTagSearch = onTagSearch,
            onCopyTagToClipboard = onCopyTagToClipboard,
            onFilterClicked = onFilterButtonClicked,
            onRefresh = onRefresh,
            onContinueReading = onContinueReading,
            onSearch = onSearch,
            onCoverClicked = onCoverClicked,
            onShareClicked = onShareClicked,
            onShareToChatClicked = onShareToChatClicked,
            onDownloadActionClicked = onDownloadActionClicked,
            onEditCategoryClicked = onEditCategoryClicked,
            onEditIntervalClicked = onEditFetchIntervalClicked,
            onMigrateClicked = onMigrateClicked,
            onEditNotesClicked = onEditNotesClicked,
            onMultiBookmarkClicked = onMultiBookmarkClicked,
            onMultiMarkAsReadClicked = onMultiMarkAsReadClicked,
            onMarkPreviousAsReadClicked = onMarkPreviousAsReadClicked,
            onMultiDeleteClicked = onMultiDeleteClicked,
            onChapterSwipe = onChapterSwipe,
            onChapterSelected = onChapterSelected,
            onAllChapterSelected = onAllChapterSelected,
            onInvertSelection = onInvertSelection,
            onLinkedSourcesClicked = onLinkedSourcesClicked,
            onUpdateWatchClicked = onUpdateWatchClicked,
            onMergedSettingsClicked = onMergedSettingsClicked,
            onMergeClicked = onMergeClicked,
            onOpenSource = onOpenSource,
            sourceColorMap = sourceColorMap,
            expandedChapters = expandedChapters,
            groupedListItem = groupedListItem,
            anyChapterGrouped = anyChapterGrouped,
            allGroupsExpanded = allGroupsExpanded,
            onToggleAllExpand = onToggleAllExpand,
            // Mihon Enhanced -->
            useSourceColor = useSourceColor,
            useChapterColor = useChapterColor,
            // Mihon Enhanced <--
        )
    } else {
        MangaScreenLargeImpl(
            state = state,
            snackbarHostState = snackbarHostState,
            chapterSwipeStartAction = chapterSwipeStartAction,
            chapterSwipeEndAction = chapterSwipeEndAction,
            nextUpdate = nextUpdate,
            navigateUp = navigateUp,
            onChapterClicked = onChapterClicked,
            onDownloadChapter = onDownloadChapter,
            onAddToLibraryClicked = onAddToLibraryClicked,
            onWebViewClicked = onWebViewClicked,
            onWebViewLongClicked = onWebViewLongClicked,
            onTrackingClicked = onTrackingClicked,
            onTagSearch = onTagSearch,
            onCopyTagToClipboard = onCopyTagToClipboard,
            onFilterButtonClicked = onFilterButtonClicked,
            onRefresh = onRefresh,
            onContinueReading = onContinueReading,
            onSearch = onSearch,
            onCoverClicked = onCoverClicked,
            onShareClicked = onShareClicked,
            onShareToChatClicked = onShareToChatClicked,
            onDownloadActionClicked = onDownloadActionClicked,
            onEditCategoryClicked = onEditCategoryClicked,
            onEditIntervalClicked = onEditFetchIntervalClicked,
            onMigrateClicked = onMigrateClicked,
            onEditNotesClicked = onEditNotesClicked,
            onMultiBookmarkClicked = onMultiBookmarkClicked,
            onMultiMarkAsReadClicked = onMultiMarkAsReadClicked,
            onMarkPreviousAsReadClicked = onMarkPreviousAsReadClicked,
            onMultiDeleteClicked = onMultiDeleteClicked,
            onChapterSwipe = onChapterSwipe,
            onChapterSelected = onChapterSelected,
            onAllChapterSelected = onAllChapterSelected,
            onInvertSelection = onInvertSelection,
            onLinkedSourcesClicked = onLinkedSourcesClicked,
            onUpdateWatchClicked = onUpdateWatchClicked,
            onMergedSettingsClicked = onMergedSettingsClicked,
            onMergeClicked = onMergeClicked,
            onOpenSource = onOpenSource,
            sourceColorMap = sourceColorMap,
            expandedChapters = expandedChapters,
            groupedListItem = groupedListItem,
            anyChapterGrouped = anyChapterGrouped,
            allGroupsExpanded = allGroupsExpanded,
            onToggleAllExpand = onToggleAllExpand,
            // Mihon Enhanced -->
            useSourceColor = useSourceColor,
            useChapterColor = useChapterColor,
            // Mihon Enhanced <--
        )
    }
}

@Composable
private fun MangaScreenSmallImpl(
    state: MangaViewModel.State.Success,
    snackbarHostState: SnackbarHostState,
    nextUpdate: Instant?,
    chapterSwipeStartAction: LibraryPreferences.ChapterSwipeAction,
    chapterSwipeEndAction: LibraryPreferences.ChapterSwipeAction,
    navigateUp: () -> Unit,
    onChapterClicked: (Chapter) -> Unit,
    onDownloadChapter: ((List<ChapterList.Item>, ChapterDownloadAction) -> Unit)?,
    onAddToLibraryClicked: () -> Unit,
    onWebViewClicked: (() -> Unit)?,
    onWebViewLongClicked: (() -> Unit)?,
    onTrackingClicked: () -> Unit,

    // For tags menu
    onTagSearch: (String) -> Unit,
    onCopyTagToClipboard: (tag: String) -> Unit,

    onFilterClicked: () -> Unit,
    onRefresh: () -> Unit,
    onContinueReading: () -> Unit,
    onSearch: (query: String, global: Boolean) -> Unit,

    // For cover dialog
    onCoverClicked: () -> Unit,

    // For top action menu
    onShareClicked: (() -> Unit)?,
    onShareToChatClicked: (() -> Unit)?,
    onDownloadActionClicked: ((DownloadAction) -> Unit)?,
    onEditCategoryClicked: (() -> Unit)?,
    onEditIntervalClicked: (() -> Unit)?,
    onMigrateClicked: (() -> Unit)?,
    onEditNotesClicked: () -> Unit,

    // For bottom action menu
    onMultiBookmarkClicked: (List<Chapter>, bookmarked: Boolean) -> Unit,
    onMultiMarkAsReadClicked: (List<Chapter>, markAsRead: Boolean) -> Unit,
    onMarkPreviousAsReadClicked: (Chapter) -> Unit,
    onMultiDeleteClicked: (List<Chapter>) -> Unit,

    // For chapter swipe
    onChapterSwipe: (ChapterList.Item, LibraryPreferences.ChapterSwipeAction) -> Unit,

    // Chapter selection
    onChapterSelected: (ChapterList.Item, Boolean, Boolean) -> Unit,
    onAllChapterSelected: (Boolean) -> Unit,
    onInvertSelection: () -> Unit,
    onLinkedSourcesClicked: () -> Unit,
    onUpdateWatchClicked: () -> Unit,
    // SY -->
    onMergedSettingsClicked: () -> Unit,
    onMergeClicked: () -> Unit,
    // SY <--
    onOpenSource: (Long, String) -> Unit,

    // Mihon Enhanced -->
    sourceColorMap: Map<Long, androidx.compose.ui.graphics.Color>,
    expandedChapters: MutableMap<Double, Boolean>,
    groupedListItem: List<ChapterList>,
    anyChapterGrouped: Boolean,
    allGroupsExpanded: Boolean,
    onToggleAllExpand: () -> Unit,
    // Mihon Enhanced -->
    useSourceColor: Boolean,
    useChapterColor: Boolean,
    // Mihon Enhanced <--
) {
    val chapterListState = rememberLazyListState()

    val (chapters, _, isAnySelected) = remember(state) {
        Triple(
            first = state.processedChapters,
            second = state.chapterListItems,
            third = state.isAnySelected,
        )
    }

    val displayChapterCount = remember(chapters) {
        if (state.manga.source == MERGED_SOURCE_ID) {
            val (recognized, unrecognized) = chapters.partition { it.chapter.chapterNumber != -1.0 }
            recognized.map { it.chapter.chapterNumber }.distinct().size + unrecognized.size
        } else {
            chapters.size
        }
    }

    BackHandler(enabled = isAnySelected) {
        onAllChapterSelected(false)
    }

    Scaffold(
        topBar = {
            val selectedChapterCount: Int = remember(chapters) {
                chapters.count { it.selected }
            }
            val isFirstItemVisible by remember {
                derivedStateOf { chapterListState.firstVisibleItemIndex == 0 }
            }
            val isFirstItemScrolled by remember {
                derivedStateOf { chapterListState.firstVisibleItemScrollOffset > 0 }
            }
            val titleAlpha by animateFloatAsState(
                if (!isFirstItemVisible) 1f else 0f,
                label = "Top Bar Title",
            )
            val backgroundAlpha by animateFloatAsState(
                if (!isFirstItemVisible || isFirstItemScrolled) 1f else 0f,
                label = "Top Bar Background",
            )
            MangaToolbar(
                title = state.manga.title,
                hasFilters = state.filterActive,
                navigateUp = navigateUp,
                onClickFilter = onFilterClicked,
                onClickShare = onShareClicked,
                onClickShareToChat = onShareToChatClicked,
                onClickDownload = onDownloadActionClicked,
                onClickEditCategory = onEditCategoryClicked,
                onClickRefresh = onRefresh,
                onClickMigrate = onMigrateClicked,
                onClickEditNotes = onEditNotesClicked,
                onLinkedSourcesClicked = onLinkedSourcesClicked,
                // SY -->
                onClickMergedSettings = onMergedSettingsClicked.takeIf { state.manga.source == MERGED_SOURCE_ID },
                onClickMerge = onMergeClicked,
                // SY <--
                actionModeCounter = selectedChapterCount,
                onCancelActionMode = { onAllChapterSelected(false) },
                onSelectAll = { onAllChapterSelected(true) },
                onInvertSelection = { onInvertSelection() },
                titleAlphaProvider = { titleAlpha },
                backgroundAlphaProvider = { backgroundAlpha },
            )
        },
        bottomBar = {
            val selectedChapters = remember(chapters) {
                chapters.filter { it.selected }
            }
            SharedMangaBottomActionMenu(
                selected = selectedChapters,
                onMultiBookmarkClicked = onMultiBookmarkClicked,
                onMultiMarkAsReadClicked = onMultiMarkAsReadClicked,
                onMarkPreviousAsReadClicked = onMarkPreviousAsReadClicked,
                onDownloadChapter = onDownloadChapter,
                onMultiDeleteClicked = onMultiDeleteClicked,
                fillFraction = 1f,
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        floatingActionButton = {
            val isFABVisible = remember(chapters) {
                chapters.fastAny { !it.chapter.read } && !isAnySelected
            }
            SmallExtendedFloatingActionButton(
                text = {
                    val isReading = remember(state.chapters) {
                        state.chapters.fastAny { it.chapter.read }
                    }
                    Text(
                        text = stringResource(if (isReading) MR.strings.action_resume else MR.strings.action_start),
                    )
                },
                icon = { Icon(imageVector = Icons.Filled.PlayArrow, contentDescription = null) },
                onClick = onContinueReading,
                expanded = chapterListState.shouldExpandFAB(),
                modifier = Modifier.animateFloatingActionButton(
                    visible = isFABVisible,
                    alignment = Alignment.BottomEnd,
                ),
            )
        },
    ) { contentPadding ->
        val topPadding = contentPadding.calculateTopPadding()

        PullRefresh(
            refreshing = state.isRefreshingData,
            onRefresh = onRefresh,
            enabled = !isAnySelected,
            indicatorPadding = PaddingValues(top = topPadding),
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                val layoutDirection = LocalLayoutDirection.current
                VerticalFastScroller(
                    listState = chapterListState,
                    topContentPadding = topPadding,
                    endContentPadding = contentPadding.calculateEndPadding(layoutDirection),
                ) {
                    LazyColumn(
                        modifier = Modifier.fillMaxHeight(),
                        state = chapterListState,
                        contentPadding = PaddingValues(
                            start = contentPadding.calculateStartPadding(layoutDirection),
                            end = contentPadding.calculateEndPadding(layoutDirection),
                            bottom = contentPadding.calculateBottomPadding(),
                        ),
                    ) {
                        item(
                            key = MangaScreenItem.INFO_BOX,
                            contentType = MangaScreenItem.INFO_BOX,
                        ) {
                            MangaInfoBox(
                                isTabletUi = false,
                                appBarPadding = topPadding,
                                manga = state.manga,
                                sourceName = remember(state.source, state.mergedData?.sources) {
                                    state.source.getNameForMangaInfo(state.mergedData?.sources)
                                },
                                isStubSource = remember { state.source is StubSource },
                                onCoverClick = onCoverClicked,
                                doSearch = onSearch,
                            )
                        }

                        item(
                            key = MangaScreenItem.ACTION_ROW,
                            contentType = MangaScreenItem.ACTION_ROW,
                        ) {
                            MangaActionRow(
                                favorite = state.manga.favorite,
                                trackingCount = state.trackingCount,
                                nextUpdate = nextUpdate,
                                isUserIntervalMode = state.manga.fetchInterval < 0,
                                onAddToLibraryClicked = onAddToLibraryClicked,
                                onWebViewClicked = onWebViewClicked,
                                onWebViewLongClicked = onWebViewLongClicked,
                                onTrackingClicked = onTrackingClicked,
                                onEditIntervalClicked = onEditIntervalClicked,
                                onEditCategory = onEditCategoryClicked,
                                updateWatchTracked = state.isUpdateWatchTracked,
                                onUpdateWatchClicked = onUpdateWatchClicked,
                                // SY -->
                                onMergeClicked = onMergeClicked,
                                // SY <--
                            )
                        }

                        if (onShareToChatClicked != null) {
                            item(
                                key = MangaScreenItem.SHARE_TO_CHAT,
                                contentType = MangaScreenItem.SHARE_TO_CHAT,
                            ) {
                                Spacer(modifier = Modifier.height(8.dp))
                                FilledTonalButton(
                                    onClick = onShareToChatClicked,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.Forum,
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp),
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(text = "Share to chat")
                                }
                                Spacer(modifier = Modifier.height(12.dp))
                            }
                        }

                        item(
                            key = MangaScreenItem.DESCRIPTION_WITH_TAG,
                            contentType = MangaScreenItem.DESCRIPTION_WITH_TAG,
                        ) {
                            ExpandableMangaDescription(
                                defaultExpandState = state.isFromSource,
                                description = state.manga.description,
                                tagsProvider = { state.manga.genre },
                                notes = state.manga.notes,
                                onTagSearch = onTagSearch,
                                onCopyTagToClipboard = onCopyTagToClipboard,
                                onEditNotes = onEditNotesClicked,
                            )
                        }

                        item(
                            key = MangaScreenItem.CHAPTER_HEADER,
                            contentType = MangaScreenItem.CHAPTER_HEADER,
                        ) {
                            val missingChapterCount = remember(chapters) {
                                chapters.map { it.chapter.chapterNumber }.missingChaptersCount()
                            }
                            ChapterHeader(
                                enabled = !isAnySelected,
                                chapterCount = displayChapterCount,
                                missingChapterCount = missingChapterCount,
                                onClick = onFilterClicked,
                                // SY -->
                                onClickGroupToggle = onToggleAllExpand.takeIf { anyChapterGrouped },
                                groupToggleEnabled = allGroupsExpanded,
                                // SY <--
                            )
                        }

                        sharedChapterItems(
                            manga = state.manga,
                            chapters = groupedListItem,
                            isAnyChapterSelected = chapters.fastAny { it.selected },
                            chapterSwipeStartAction = chapterSwipeStartAction,
                            chapterSwipeEndAction = chapterSwipeEndAction,
                            // SY -->
                            sourceColorMap = sourceColorMap,
                            onToggleExpand = { num ->
                                if (expandedChapters.containsKey(num)) {
                                    expandedChapters.remove(num)
                                } else {
                                    expandedChapters[num] = true
                                }
                            },
                            useSourceColor = useSourceColor,
                            useChapterColor = useChapterColor,
                            // SY <--
                            onChapterClicked = onChapterClicked,
                            onDownloadChapter = onDownloadChapter,
                            onChapterSelected = onChapterSelected,
                            onChapterSwipe = onChapterSwipe,
                        )
                    }
                }

                Row(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = 16.dp, bottom = contentPadding.calculateBottomPadding() + 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    MangaQuickSourceSwitcher(
                        currentSourceId = state.manga.source,
                        onOpenSource = onOpenSource,
                    )
                    MangaSmartSourceJump(
                        mangaTitle = state.manga.title,
                        currentSourceId = state.manga.source,
                        onOpenSource = onOpenSource,
                        snackbarHostState = snackbarHostState,
                    )
                }
            }
        }
    }
}

@Composable
fun MangaScreenLargeImpl(
    state: MangaViewModel.State.Success,
    snackbarHostState: SnackbarHostState,
    nextUpdate: Instant?,
    chapterSwipeStartAction: LibraryPreferences.ChapterSwipeAction,
    chapterSwipeEndAction: LibraryPreferences.ChapterSwipeAction,
    navigateUp: () -> Unit,
    onChapterClicked: (Chapter) -> Unit,
    onDownloadChapter: ((List<ChapterList.Item>, ChapterDownloadAction) -> Unit)?,
    onAddToLibraryClicked: () -> Unit,
    onWebViewClicked: (() -> Unit)?,
    onWebViewLongClicked: (() -> Unit)?,
    onTrackingClicked: () -> Unit,

    // For tags menu
    onTagSearch: (String) -> Unit,
    onCopyTagToClipboard: (tag: String) -> Unit,

    onFilterButtonClicked: () -> Unit,
    onRefresh: () -> Unit,
    onContinueReading: () -> Unit,
    onSearch: (query: String, global: Boolean) -> Unit,

    // For cover dialog
    onCoverClicked: () -> Unit,

    // For top action menu
    onShareClicked: (() -> Unit)?,
    onShareToChatClicked: (() -> Unit)?,
    onDownloadActionClicked: ((DownloadAction) -> Unit)?,
    onEditCategoryClicked: (() -> Unit)?,
    onEditIntervalClicked: (() -> Unit)?,
    onMigrateClicked: (() -> Unit)?,
    onEditNotesClicked: () -> Unit,

    // For bottom action menu
    onMultiBookmarkClicked: (List<Chapter>, bookmarked: Boolean) -> Unit,
    onMultiMarkAsReadClicked: (List<Chapter>, markAsRead: Boolean) -> Unit,
    onMarkPreviousAsReadClicked: (Chapter) -> Unit,
    onMultiDeleteClicked: (List<Chapter>) -> Unit,

    // For swipe actions
    onChapterSwipe: (ChapterList.Item, LibraryPreferences.ChapterSwipeAction) -> Unit,

    // Chapter selection
    onChapterSelected: (ChapterList.Item, Boolean, Boolean) -> Unit,
    onAllChapterSelected: (Boolean) -> Unit,
    onInvertSelection: () -> Unit,
    onLinkedSourcesClicked: () -> Unit,
    onUpdateWatchClicked: () -> Unit,
    // SY -->
    onMergedSettingsClicked: () -> Unit,
    onMergeClicked: () -> Unit,
    // SY <--
    onOpenSource: (Long, String) -> Unit,

    // Mihon Enhanced -->
    sourceColorMap: Map<Long, androidx.compose.ui.graphics.Color>,
    expandedChapters: MutableMap<Double, Boolean>,
    groupedListItem: List<ChapterList>,
    anyChapterGrouped: Boolean,
    allGroupsExpanded: Boolean,
    onToggleAllExpand: () -> Unit,
    // Mihon Enhanced -->
    useSourceColor: Boolean,
    useChapterColor: Boolean,
    // Mihon Enhanced <--
) {
    val layoutDirection = LocalLayoutDirection.current
    val density = LocalDensity.current

    val (chapters, _, isAnySelected) = remember(state) {
        Triple(
            first = state.processedChapters,
            second = state.chapterListItems,
            third = state.isAnySelected,
        )
    }

    val displayChapterCount = remember(chapters) {
        if (state.manga.source == MERGED_SOURCE_ID) {
            val (recognized, unrecognized) = chapters.partition { it.chapter.chapterNumber != -1.0 }
            recognized.map { it.chapter.chapterNumber }.distinct().size + unrecognized.size
        } else {
            chapters.size
        }
    }

    val insetPadding = WindowInsets.systemBars.only(WindowInsetsSides.Horizontal).asPaddingValues()
    var topBarHeight by remember { mutableIntStateOf(0) }

    val chapterListState = rememberLazyListState()

    BackHandler(enabled = isAnySelected) {
        onAllChapterSelected(false)
    }

    Scaffold(
        topBar = {
            val selectedChapterCount = remember(chapters) {
                chapters.count { it.selected }
            }
            MangaToolbar(
                modifier = Modifier.onSizeChanged { topBarHeight = it.height },
                title = state.manga.title,
                hasFilters = state.filterActive,
                navigateUp = navigateUp,
                onClickFilter = onFilterButtonClicked,
                onClickShare = onShareClicked,
                onClickShareToChat = onShareToChatClicked,
                onClickDownload = onDownloadActionClicked,
                onClickEditCategory = onEditCategoryClicked,
                onClickRefresh = onRefresh,
                onClickMigrate = onMigrateClicked,
                onClickEditNotes = onEditNotesClicked,
                onLinkedSourcesClicked = onLinkedSourcesClicked,
                // SY -->
                onClickMergedSettings = onMergedSettingsClicked.takeIf { state.manga.source == MERGED_SOURCE_ID },
                onClickMerge = onMergeClicked,
                // SY <--
                onCancelActionMode = { onAllChapterSelected(false) },
                actionModeCounter = selectedChapterCount,
                onSelectAll = { onAllChapterSelected(true) },
                onInvertSelection = { onInvertSelection() },
                titleAlphaProvider = { 1f },
                backgroundAlphaProvider = { 1f },
            )
        },
        bottomBar = {
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.BottomEnd,
            ) {
                val selectedChapters = remember(chapters) {
                    chapters.filter { it.selected }
                }
                SharedMangaBottomActionMenu(
                    selected = selectedChapters,
                    onMultiBookmarkClicked = onMultiBookmarkClicked,
                    onMultiMarkAsReadClicked = onMultiMarkAsReadClicked,
                    onMarkPreviousAsReadClicked = onMarkPreviousAsReadClicked,
                    onDownloadChapter = onDownloadChapter,
                    onMultiDeleteClicked = onMultiDeleteClicked,
                    fillFraction = 0.5f,
                )
            }
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        floatingActionButton = {
            val isFABVisible = remember(chapters) {
                chapters.fastAny { !it.chapter.read } && !isAnySelected
            }
            SmallExtendedFloatingActionButton(
                text = {
                    val isReading = remember(state.chapters) {
                        state.chapters.fastAny { it.chapter.read }
                    }
                    Text(
                        text = stringResource(
                            if (isReading) MR.strings.action_resume else MR.strings.action_start,
                        ),
                    )
                },
                icon = { Icon(imageVector = Icons.Filled.PlayArrow, contentDescription = null) },
                onClick = onContinueReading,
                expanded = chapterListState.shouldExpandFAB(),
                modifier = Modifier.animateFloatingActionButton(
                    visible = isFABVisible,
                    alignment = Alignment.BottomEnd,
                ),
            )
        },
    ) { contentPadding ->
        PullRefresh(
            refreshing = state.isRefreshingData,
            onRefresh = onRefresh,
            enabled = !isAnySelected,
            indicatorPadding = PaddingValues(
                start = insetPadding.calculateStartPadding(layoutDirection),
                top = with(density) { topBarHeight.toDp() },
                end = insetPadding.calculateEndPadding(layoutDirection),
            ),
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                TwoPanelBox(
                    modifier = Modifier.padding(
                        start = contentPadding.calculateStartPadding(layoutDirection),
                        end = contentPadding.calculateEndPadding(layoutDirection),
                    ),
                    startContent = {
                        Column(
                            modifier = Modifier
                                .verticalScroll(rememberScrollState())
                                .padding(bottom = contentPadding.calculateBottomPadding()),
                        ) {
                            MangaInfoBox(
                                isTabletUi = true,
                                appBarPadding = contentPadding.calculateTopPadding(),
                                manga = state.manga,
                                sourceName = remember(state.source, state.mergedData?.sources) {
                                    state.source.getNameForMangaInfo(state.mergedData?.sources)
                                },
                                isStubSource = remember { state.source is StubSource },
                                onCoverClick = onCoverClicked,
                                doSearch = onSearch,
                            )
                            MangaActionRow(
                                favorite = state.manga.favorite,
                                trackingCount = state.trackingCount,
                                nextUpdate = nextUpdate,
                                isUserIntervalMode = state.manga.fetchInterval < 0,
                                onAddToLibraryClicked = onAddToLibraryClicked,
                                onWebViewClicked = onWebViewClicked,
                                onWebViewLongClicked = onWebViewLongClicked,
                                onTrackingClicked = onTrackingClicked,
                                onEditIntervalClicked = onEditIntervalClicked,
                                onEditCategory = onEditCategoryClicked,
                                updateWatchTracked = state.isUpdateWatchTracked,
                                onUpdateWatchClicked = onUpdateWatchClicked,
                                // SY -->
                                onMergeClicked = onMergeClicked,
                                // SY <--
                            )

                            if (onShareToChatClicked != null) {
                                Spacer(modifier = Modifier.height(8.dp))
                                FilledTonalButton(
                                    onClick = onShareToChatClicked,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.Forum,
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp),
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(text = "Share to chat")
                                }
                                Spacer(modifier = Modifier.height(12.dp))
                            }

                            ExpandableMangaDescription(
                                defaultExpandState = true,
                                description = state.manga.description,
                                tagsProvider = { state.manga.genre },
                                notes = state.manga.notes,
                                onTagSearch = onTagSearch,
                                onCopyTagToClipboard = onCopyTagToClipboard,
                                onEditNotes = onEditNotesClicked,
                            )
                        }
                    },
                    endContent = {
                        VerticalFastScroller(
                            listState = chapterListState,
                            topContentPadding = contentPadding.calculateTopPadding(),
                        ) {
                            LazyColumn(
                                modifier = Modifier.fillMaxHeight(),
                                state = chapterListState,
                                contentPadding = PaddingValues(
                                    top = contentPadding.calculateTopPadding(),
                                    bottom = contentPadding.calculateBottomPadding(),
                                ),
                            ) {
                                item(
                                    key = MangaScreenItem.CHAPTER_HEADER,
                                    contentType = MangaScreenItem.CHAPTER_HEADER,
                                ) {
                                    val missingChapterCount = remember(chapters) {
                                        chapters.map { it.chapter.chapterNumber }.missingChaptersCount()
                                    }
                                    ChapterHeader(
                                        enabled = !isAnySelected,
                                        chapterCount = displayChapterCount,
                                        missingChapterCount = missingChapterCount,
                                        onClick = onFilterButtonClicked,
                                        // SY -->
                                        onClickGroupToggle = onToggleAllExpand.takeIf { anyChapterGrouped },
                                        groupToggleEnabled = allGroupsExpanded,
                                        // SY <--
                                    )
                                }

                                sharedChapterItems(
                                    manga = state.manga,
                                    chapters = groupedListItem,
                                    isAnyChapterSelected = chapters.fastAny { it.selected },
                                    chapterSwipeStartAction = chapterSwipeStartAction,
                                    chapterSwipeEndAction = chapterSwipeEndAction,
                                    // SY -->
                                    sourceColorMap = sourceColorMap,
                                    onToggleExpand = { num ->
                                        if (expandedChapters.containsKey(num)) {
                                            expandedChapters.remove(num)
                                        } else {
                                            expandedChapters[num] = true
                                        }
                                    },
                                    useSourceColor = useSourceColor,
                                    useChapterColor = useChapterColor,
                                    // SY <--
                                    onChapterClicked = onChapterClicked,
                                    onDownloadChapter = onDownloadChapter,
                                    onChapterSelected = onChapterSelected,
                                    onChapterSwipe = onChapterSwipe,
                                )
                            }
                        }
                    },
                )

                Row(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = 16.dp, bottom = contentPadding.calculateBottomPadding() + 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    MangaQuickSourceSwitcher(
                        currentSourceId = state.manga.source,
                        onOpenSource = onOpenSource,
                    )
                    MangaSmartSourceJump(
                        mangaTitle = state.manga.title,
                        currentSourceId = state.manga.source,
                        onOpenSource = onOpenSource,
                        snackbarHostState = snackbarHostState,
                    )
                }
            }
        }
    }
}

@Composable
private fun SharedMangaBottomActionMenu(
    selected: List<ChapterList.Item>,
    onMultiBookmarkClicked: (List<Chapter>, bookmarked: Boolean) -> Unit,
    onMultiMarkAsReadClicked: (List<Chapter>, markAsRead: Boolean) -> Unit,
    onMarkPreviousAsReadClicked: (Chapter) -> Unit,
    onDownloadChapter: ((List<ChapterList.Item>, ChapterDownloadAction) -> Unit)?,
    onMultiDeleteClicked: (List<Chapter>) -> Unit,
    fillFraction: Float,
    modifier: Modifier = Modifier,
) {
    MangaBottomActionMenu(
        visible = selected.isNotEmpty(),
        modifier = modifier.fillMaxWidth(fillFraction),
        onBookmarkClicked = {
            onMultiBookmarkClicked.invoke(selected.fastMap { it.chapter }, true)
        }.takeIf { selected.fastAny { !it.chapter.bookmark } },
        onRemoveBookmarkClicked = {
            onMultiBookmarkClicked.invoke(selected.fastMap { it.chapter }, false)
        }.takeIf { selected.fastAll { it.chapter.bookmark } },
        onMarkAsReadClicked = {
            onMultiMarkAsReadClicked(selected.fastMap { it.chapter }, true)
        }.takeIf { selected.fastAny { !it.chapter.read } },
        onMarkAsUnreadClicked = {
            onMultiMarkAsReadClicked(selected.fastMap { it.chapter }, false)
        }.takeIf { selected.fastAny { it.chapter.read || it.chapter.lastPageRead > 0L } },
        onMarkPreviousAsReadClicked = {
            onMarkPreviousAsReadClicked(selected[0].chapter)
        }.takeIf { selected.size == 1 },
        onDownloadClicked = {
            onDownloadChapter!!(selected.toList(), ChapterDownloadAction.START)
        }.takeIf {
            onDownloadChapter != null && selected.fastAny { it.downloadState != Download.State.DOWNLOADED }
        },
        onDeleteClicked = {
            onMultiDeleteClicked(selected.fastMap { it.chapter })
        }.takeIf {
            selected.fastAny { it.downloadState == Download.State.DOWNLOADED }
        },
    )
}

private fun LazyListScope.sharedChapterItems(
    manga: Manga,
    chapters: List<ChapterList>,
    isAnyChapterSelected: Boolean,
    chapterSwipeStartAction: LibraryPreferences.ChapterSwipeAction,
    chapterSwipeEndAction: LibraryPreferences.ChapterSwipeAction,
    // SY -->
    sourceColorMap: Map<Long, androidx.compose.ui.graphics.Color>,
    onToggleExpand: (Double) -> Unit,
    useSourceColor: Boolean,
    useChapterColor: Boolean,
    // SY <--
    onChapterClicked: (Chapter) -> Unit,
    onDownloadChapter: ((List<ChapterList.Item>, ChapterDownloadAction) -> Unit)?,
    onChapterSelected: (ChapterList.Item, Boolean, Boolean) -> Unit,
    onChapterSwipe: (ChapterList.Item, LibraryPreferences.ChapterSwipeAction) -> Unit,
) {
    items(
        items = chapters,
        key = { item ->
            when (item) {
                is ChapterList.MissingCount -> "missing-count-${item.id}"
                is ChapterList.Item -> "chapter-${item.id}"
                // SY -->
                is ChapterList.Group -> "group-${item.chapterNumber}"
                // SY <--
            }
        },
        contentType = {
            when (it) {
                is ChapterList.MissingCount -> MangaScreenItem.CHAPTER
                is ChapterList.Item -> MangaScreenItem.CHAPTER
                // SY -->
                is ChapterList.Group -> MangaScreenItem.CHAPTER
                // SY <--
            }
        },
    ) { item ->
        val haptic = LocalHapticFeedback.current

        when (item) {
            is ChapterList.MissingCount -> {
                MissingChapterCountListItem(count = item.count)
            }
            // SY -->
            is ChapterList.Group -> {
                MangaChapterGroupListItem(
                    title = if (manga.displayMode == Manga.CHAPTER_DISPLAY_NUMBER) {
                        stringResource(
                            MR.strings.display_mode_chapter,
                            formatChapterNumber(item.chapterNumber),
                        )
                    } else {
                        "Chapter ${formatChapterNumber(item.chapterNumber)}"
                    },
                    sourceCount = item.items.size,
                    isExpanded = item.isExpanded,
                    onClick = { onToggleExpand(item.chapterNumber) },
                    modifier = Modifier.animateItem(),
                )
            }
            // SY <--
            is ChapterList.Item -> {
                MangaChapterListItem(
                    title = if (manga.displayMode == Manga.CHAPTER_DISPLAY_NUMBER) {
                        stringResource(
                            MR.strings.display_mode_chapter,
                            formatChapterNumber(item.chapter.chapterNumber),
                        )
                    } else {
                        item.chapter.name
                    },
                    date = relativeDateText(item.chapter.dateUpload),
                    readProgress = item.chapter.lastPageRead
                        .takeIf { !item.chapter.read && it > 0L }
                        ?.let {
                            stringResource(
                                MR.strings.chapter_progress,
                                it + 1,
                            )
                        },
                    scanlator = item.chapter.scanlator.takeIf { !it.isNullOrBlank() },
                    read = item.chapter.read,
                    bookmark = item.chapter.bookmark,
                    selected = item.selected,
                    sourceName = item.sourceName,
                    sourceColor = if (item.isGrouped) sourceColorMap[item.sourceId] else null,
                    useSourceColor = useSourceColor,
                    useChapterColor = useChapterColor,
                    downloadIndicatorEnabled = !isAnyChapterSelected && !manga.isLocal(),
                    downloadStateProvider = { item.downloadState },
                    downloadProgressProvider = { item.downloadProgress },
                    chapterSwipeStartAction = chapterSwipeStartAction,
                    chapterSwipeEndAction = chapterSwipeEndAction,
                    onLongClick = {
                        onChapterSelected(item, !item.selected, true)
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    },
                    onClick = {
                        onChapterItemClick(
                            chapterItem = item,
                            isAnyChapterSelected = isAnyChapterSelected,
                            onToggleSelection = { onChapterSelected(item, !item.selected, false) },
                            onChapterClicked = onChapterClicked,
                        )
                    },
                    onDownloadClick = if (onDownloadChapter != null) {
                        { onDownloadChapter(listOf(item), it) }
                    } else {
                        null
                    },
                    onChapterSwipe = {
                        onChapterSwipe(item, it)
                    },
                )
            }
        }
    }
}

private fun onChapterItemClick(
    chapterItem: ChapterList.Item,
    isAnyChapterSelected: Boolean,
    onToggleSelection: (Boolean) -> Unit,
    onChapterClicked: (Chapter) -> Unit,
) {
    when {
        chapterItem.selected -> onToggleSelection(false)
        isAnyChapterSelected -> onToggleSelection(true)
        else -> onChapterClicked(chapterItem.chapter)
    }
}

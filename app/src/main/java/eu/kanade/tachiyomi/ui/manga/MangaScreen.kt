package eu.kanade.tachiyomi.ui.manga

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.compose.viewModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.core.util.ifSourcesLoaded
import eu.kanade.domain.manga.model.hasCustomCover
import eu.kanade.domain.manga.model.toSManga
import eu.kanade.presentation.category.components.ChangeCategoryDialog
import eu.kanade.presentation.components.NavigatorAdaptiveSheet
import eu.kanade.presentation.manga.ChapterSettingsDialog
import eu.kanade.presentation.manga.DuplicateMangaDialog
import eu.kanade.presentation.manga.EditCoverAction
import eu.kanade.presentation.manga.MangaScreen
import eu.kanade.presentation.manga.components.DeleteChaptersDialog
import eu.kanade.presentation.manga.components.LinkedSourcesSheet
import eu.kanade.presentation.manga.components.MangaCoverDialog
import eu.kanade.presentation.manga.components.ScanlatorFilterDialog
import eu.kanade.presentation.manga.components.SetIntervalDialog
import eu.kanade.presentation.manga.components.TrackUpdateWatchDialog
import eu.kanade.presentation.util.AssistContentScreen
import eu.kanade.presentation.util.Screen
import eu.kanade.presentation.util.isTabletUi
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.isLocalOrStub
import eu.kanade.tachiyomi.source.online.HttpSource
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import eu.kanade.tachiyomi.ui.browse.source.browse.BrowseSourceScreen
import eu.kanade.tachiyomi.ui.browse.source.globalsearch.GlobalSearchScreen
import eu.kanade.tachiyomi.ui.browse.source.linked.LinkedSourceDetailsScreen
import eu.kanade.tachiyomi.ui.browse.source.linked.LinkedSourceSearchScreen
import eu.kanade.tachiyomi.ui.category.CategoryScreen
import eu.kanade.tachiyomi.ui.home.HomeScreen
import eu.kanade.tachiyomi.ui.manga.notes.MangaNotesScreen
import eu.kanade.tachiyomi.ui.mod.EnhancedPreferences
import java.net.URI
import eu.kanade.tachiyomi.ui.manga.track.TrackInfoDialogHomeScreen
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.setting.SettingsScreen
import eu.kanade.tachiyomi.ui.webview.WebViewScreen
import eu.kanade.tachiyomi.util.system.copyToClipboard
import eu.kanade.tachiyomi.util.system.toShareIntent
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.launch
import logcat.LogPriority
import mihon.feature.migration.config.MigrationConfigScreen
import mihon.feature.migration.dialog.MigrateMangaDialog
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import tachiyomi.presentation.core.screens.LoadingScreen
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

class MangaScreen(
    private val mangaId: Long,
    val fromSource: Boolean = false,
    val smartJumpSessionId: String? = null,
    val smartJumpAnchorSourceId: Long? = null,
) : Screen(), AssistContentScreen {

    private var assistUrl: String? = null

    override fun onProvideAssistUrl() = assistUrl

    @Composable
    override fun Content() {
        if (!ifSourcesLoaded()) {
            LoadingScreen()
            return
        }

        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val haptic = LocalHapticFeedback.current
        val scope = rememberCoroutineScope()
        val viewModel = viewModel<MangaViewModel>(
            factory = MangaViewModel.Factory,
            extras = CreationExtras {
                set(MangaViewModel.MANGA_ID_KEY, mangaId)
                set(MangaViewModel.IS_FROM_SOURCE_KEY, fromSource)
            },
        )

        val state by viewModel.state.collectAsStateWithLifecycle()

        if (state is MangaViewModel.State.Loading) {
            LoadingScreen()
            return
        }

        val successState = state as MangaViewModel.State.Success
        val isHttpSource = remember { successState.source is HttpSource }

        var showLinkedSourcesSheet by remember { mutableStateOf(false) }

        LaunchedEffect(successState.manga, viewModel.source) {
            if (isHttpSource) {
                try {
                    withIOContext {
                        assistUrl = getMangaUrl(viewModel.manga, viewModel.source)
                    }
                } catch (e: Exception) {
                    logcat(LogPriority.ERROR, e) { "Failed to get manga URL" }
                }
            }
        }

        MangaScreen(
            state = successState,
            snackbarHostState = viewModel.snackbarHostState,
            nextUpdate = successState.manga.expectedNextUpdate,
            isTabletUi = isTabletUi(),
            chapterSwipeStartAction = viewModel.chapterSwipeStartAction,
            chapterSwipeEndAction = viewModel.chapterSwipeEndAction,
            navigateUp = navigator::pop,
            onChapterClicked = { openChapter(context, it) },
            onDownloadChapter = viewModel::runChapterDownloadActions.takeIf { !successState.source.isLocalOrStub() },
            onAddToLibraryClicked = {
                viewModel.toggleFavorite()
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            },
            onWebViewClicked = {
                openMangaInWebView(
                    navigator,
                    viewModel.manga,
                    viewModel.source,
                )
            }.takeIf { isHttpSource },
            onWebViewLongClicked = {
                copyMangaUrl(
                    context,
                    viewModel.manga,
                    viewModel.source,
                )
            }.takeIf { isHttpSource },
            onTrackingClicked = {
                if (!successState.hasLoggedInTrackers) {
                    navigator.push(SettingsScreen(SettingsScreen.Destination.Tracking))
                } else {
                    viewModel.showTrackDialog()
                }
            },
            onTagSearch = { scope.launch { performGenreSearch(navigator, it, viewModel.source!!) } },
            onFilterButtonClicked = viewModel::showSettingsDialog,
            onRefresh = viewModel::fetchAllFromSource,
            onContinueReading = { continueReading(context, viewModel.getNextUnreadChapter()) },
            onSearch = { query, global -> scope.launch { performSearch(navigator, query, global) } },
            onCoverClicked = viewModel::showCoverDialog,
            onShareClicked = { shareManga(context, viewModel.manga, viewModel.source) }.takeIf { isHttpSource },
            onShareToChatClicked = { shareMangaToChat(context, viewModel.manga, viewModel.source) }.takeIf { isHttpSource },
            onDownloadActionClicked = viewModel::runDownloadAction.takeIf { !successState.source.isLocalOrStub() },
            onEditCategoryClicked = viewModel::showChangeCategoryDialog.takeIf { successState.manga.favorite },
            onEditFetchIntervalClicked = viewModel::showSetFetchIntervalDialog.takeIf {
                successState.manga.favorite
            },
            onMigrateClicked = {
                navigator.push(MigrationConfigScreen(successState.manga.id))
            }.takeIf { successState.manga.favorite },
            onEditNotesClicked = { navigator.push(MangaNotesScreen(manga = successState.manga)) },
            onMultiBookmarkClicked = viewModel::bookmarkChapters,
            onMultiMarkAsReadClicked = viewModel::markChaptersRead,
            onMarkPreviousAsReadClicked = viewModel::markPreviousChapterRead,
            onMultiDeleteClicked = viewModel::showDeleteChapterDialog,
            onChapterSwipe = viewModel::chapterSwipe,
            onChapterSelected = viewModel::toggleSelection,
            onAllChapterSelected = viewModel::toggleAllSelection,
            onInvertSelection = viewModel::invertSelection,
            onLinkedSourcesClicked = { showLinkedSourcesSheet = true },
            onUpdateWatchClicked = viewModel::toggleUpdateWatch,
            onOpenSource = { sourceId, query ->
                val currentSourceId = successState.source.id

                var shouldPerformJump = true

                // Handle jumping back to the original source
                if (smartJumpSessionId != null && sourceId == smartJumpAnchorSourceId) {
                    navigator.popUntil { it is MangaScreen && it.smartJumpSessionId == null }
                    shouldPerformJump = false
                }

                // Handle same-source selection as no-op
                if (shouldPerformJump && sourceId == currentSourceId) {
                    shouldPerformJump = false
                }

                if (shouldPerformJump) {
                    val title = successState.manga.title
                    val nextSessionId = java.util.UUID.randomUUID().toString()

                    // If we are already a jump target, pop back to the anchor before pushing new jump
                    if (smartJumpSessionId != null) {
                        navigator.popUntil { it is MangaScreen && it.smartJumpSessionId == null }
                    }

                    val anchorSourceId = if (smartJumpSessionId == null) currentSourceId else smartJumpAnchorSourceId

                    navigator.push(
                        BrowseSourceScreen(
                            sourceId,
                            query,
                            title,
                            nextSessionId,
                            anchorSourceId
                        )
                    )
                }
            },
        )

        if (showLinkedSourcesSheet) {
            LaunchedEffect(Unit) {
                viewModel.refreshAllLinkedSources(manual = false)
            }
            LinkedSourcesSheet(
                onDismissRequest = { showLinkedSourcesSheet = false },
                onSearchSourcesClick = {
                    showLinkedSourcesSheet = false
                    viewModel.showCreateGroupDialog()
                },
                onJoinGroupClick = {
                    showLinkedSourcesSheet = false
                    viewModel.showJoinGroupDialog()
                },
                onAddSourceClick = {
                    showLinkedSourcesSheet = false
                    successState.linkedGroup?.let { group ->
                        navigator.push(LinkedSourceSearchScreen(group.id, group.name))
                    }
                },
                onMemberOpenClick = { member ->
                    showLinkedSourcesSheet = false
                    if (member.id != mangaId) {
                        navigator.push(MangaScreen(member.id))
                    }
                },
                onMemberReadClick = { memberMangaId, chapterId ->
                    showLinkedSourcesSheet = false
                    context.startActivity(ReaderActivity.newIntent(context, memberMangaId, chapterId))
                },
                onMemberLatestClick = { memberMangaId, chapterId ->
                    showLinkedSourcesSheet = false
                    context.startActivity(ReaderActivity.newIntent(context, memberMangaId, chapterId))
                },
                onMemberRemoveClick = { member ->
                    showLinkedSourcesSheet = false
                    viewModel.showRemoveMemberDialog(member)
                },
                onMemberRefreshClick = { member ->
                    viewModel.refreshLinkedMember(member.id)
                },
                onRefreshAllClick = viewModel::refreshAllLinkedSources,
                onRenameGroupClick = viewModel::showRenameGroupDialog,
                onDeleteGroupClick = viewModel::showDeleteGroupDialog,
                isWideCompact = successState.isWideCompact,
                onToggleWideCompact = viewModel::toggleWideCompact,
                linkedGroup = successState.linkedGroup,
                linkedMembers = successState.linkedMembers,
                currentMangaId = mangaId,
                refreshingIds = successState.refreshingIds,
            )
        }

        var showScanlatorsDialog by remember { mutableStateOf(false) }

        val onDismissRequest = { viewModel.dismissDialog() }
        when (val dialog = successState.dialog) {
            null -> {}
            is MangaViewModel.Dialog.ChangeCategory -> {
                ChangeCategoryDialog(
                    initialSelection = dialog.initialSelection,
                    onDismissRequest = onDismissRequest,
                    onEditCategories = { navigator.push(CategoryScreen()) },
                    onConfirm = { include, _ ->
                        viewModel.moveMangaToCategoriesAndAddToLibrary(dialog.manga, include)
                    },
                )
            }
            is MangaViewModel.Dialog.DeleteChapters -> {
                DeleteChaptersDialog(
                    onDismissRequest = onDismissRequest,
                    onConfirm = {
                        viewModel.toggleAllSelection(false)
                        viewModel.deleteChapters(dialog.chapters)
                    },
                )
            }

            is MangaViewModel.Dialog.DuplicateManga -> {
                DuplicateMangaDialog(
                    duplicates = dialog.duplicates,
                    onDismissRequest = onDismissRequest,
                    onConfirm = { viewModel.toggleFavorite(onRemoved = {}, checkDuplicate = false) },
                    onOpenManga = { navigator.push(MangaScreen(it.id)) },
                    onMigrate = { viewModel.showMigrateDialog(it) },
                )
            }

            is MangaViewModel.Dialog.Migrate -> {
                MigrateMangaDialog(
                    current = dialog.current,
                    target = dialog.target,
                    // Initiated from the context of [dialog.target] so we show [dialog.current].
                    onClickTitle = { navigator.push(MangaScreen(dialog.current.id)) },
                    onDismissRequest = onDismissRequest,
                )
            }
            MangaViewModel.Dialog.SettingsSheet -> ChapterSettingsDialog(
                onDismissRequest = onDismissRequest,
                manga = successState.manga,
                onDownloadFilterChanged = viewModel::setDownloadedFilter,
                onUnreadFilterChanged = viewModel::setUnreadFilter,
                onBookmarkedFilterChanged = viewModel::setBookmarkedFilter,
                onSortModeChanged = viewModel::setSorting,
                onDisplayModeChanged = viewModel::setDisplayMode,
                onSetAsDefault = viewModel::setCurrentSettingsAsDefault,
                onResetToDefault = viewModel::resetToDefaultSettings,
                scanlatorFilterActive = successState.scanlatorFilterActive,
                onScanlatorFilterClicked = { showScanlatorsDialog = true },
            )
            MangaViewModel.Dialog.TrackSheet -> {
                NavigatorAdaptiveSheet(
                    screen = TrackInfoDialogHomeScreen(
                        mangaId = successState.manga.id,
                        mangaTitle = successState.manga.title,
                        sourceId = successState.source.id,
                    ),
                    enableSwipeDismiss = { it.lastItem is TrackInfoDialogHomeScreen },
                    onDismissRequest = onDismissRequest,
                )
            }
            MangaViewModel.Dialog.FullCover -> {
                val sm = viewModel<MangaCoverViewModel>(
                    factory = MangaCoverViewModel.Factory,
                    extras = CreationExtras {
                        set(MangaCoverViewModel.MANGA_ID_KEY, successState.manga.id)
                    },
                )
                val manga by sm.state.collectAsStateWithLifecycle()
                if (manga != null) {
                    val getContent = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) {
                        if (it == null) return@rememberLauncherForActivityResult
                        sm.editCover(context, it)
                    }
                    MangaCoverDialog(
                        manga = manga!!,
                        snackbarHostState = sm.snackbarHostState,
                        isCustomCover = remember(manga) { manga!!.hasCustomCover() },
                        onShareClick = { sm.shareCover(context) },
                        onSaveClick = { sm.saveCover(context) },
                        onEditClick = {
                            when (it) {
                                EditCoverAction.EDIT -> getContent.launch("image/*")
                                EditCoverAction.DELETE -> sm.deleteCustomCover(context)
                            }
                        },
                        onDismissRequest = onDismissRequest,
                    )
                } else {
                    LoadingScreen(Modifier.systemBarsPadding())
                }
            }
            is MangaViewModel.Dialog.SetFetchInterval -> {
                SetIntervalDialog(
                    interval = dialog.manga.fetchInterval,
                    nextUpdate = dialog.manga.expectedNextUpdate,
                    onDismissRequest = onDismissRequest,
                    onValueChanged = { interval: Int -> viewModel.setFetchInterval(dialog.manga, interval) }
                        .takeIf { viewModel.isUpdateIntervalEnabled },
                )
            }
            is MangaViewModel.Dialog.CreateLinkedGroup -> {
                var name by remember { mutableStateOf(dialog.defaultName) }
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = onDismissRequest,
                    title = { androidx.compose.material3.Text("Create New Group") },
                    text = {
                        androidx.compose.material3.OutlinedTextField(
                            value = name,
                            onValueChange = { name = it },
                            label = { androidx.compose.material3.Text("Group Name") },
                            singleLine = true,
                        )
                    },
                    confirmButton = {
                        androidx.compose.material3.TextButton(
                            onClick = {
                                if (name.isNotBlank()) {
                                    viewModel.createGroup(name)
                                    onDismissRequest()
                                }
                            },
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
            is MangaViewModel.Dialog.JoinLinkedGroup -> {
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = onDismissRequest,
                    title = { androidx.compose.material3.Text("Join Group") },
                    text = {
                        androidx.compose.foundation.lazy.LazyColumn {
                            items(
                                items = dialog.allGroups,
                                key = { it.group.id }
                            ) { item ->
                                androidx.compose.material3.ListItem(
                                    modifier = Modifier.clickable {
                                        viewModel.joinGroup(item.group.id)
                                        onDismissRequest()
                                    },
                                    leadingContent = {
                                        eu.kanade.presentation.manga.components.MangaCover.Book(
                                            modifier = Modifier.height(48.dp),
                                            data = item.representativeManga,
                                        )
                                    },
                                    headlineContent = { androidx.compose.material3.Text(item.group.name) },
                                    supportingContent = { androidx.compose.material3.Text("${item.group.memberCount} sources") },
                                )
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
            is MangaViewModel.Dialog.RemoveLinkedMember -> {
                val sourceManager = remember { Injekt.get<SourceManager>() }
                val sourceName = remember(dialog.member.source) {
                    sourceManager.getOrStub(dialog.member.source).name
                }
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = onDismissRequest,
                    title = { androidx.compose.material3.Text("Remove Source") },
                    text = {
                        androidx.compose.material3.Text(
                            if (dialog.isLast) {
                                "This is the last source in this group.\n\nRemoving it will also delete the linked source group."
                            } else {
                                "Remove '$sourceName' from this group?"
                            }
                        )
                    },
                    confirmButton = {
                        androidx.compose.material3.TextButton(
                            onClick = {
                                viewModel.removeMember(dialog.member)
                                onDismissRequest()
                            },
                        ) {
                            androidx.compose.material3.Text(stringResource(MR.strings.action_remove))
                        }
                    },
                    dismissButton = {
                        androidx.compose.material3.TextButton(onClick = onDismissRequest) {
                            androidx.compose.material3.Text(stringResource(MR.strings.action_cancel))
                        }
                    }
                )
            }
            is MangaViewModel.Dialog.RenameLinkedGroup -> {
                var name by remember { mutableStateOf(dialog.group.name) }
                val scope = rememberCoroutineScope()
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = onDismissRequest,
                    title = { androidx.compose.material3.Text("Rename Group") },
                    text = {
                        androidx.compose.material3.OutlinedTextField(
                            value = name,
                            onValueChange = { name = it },
                            label = { androidx.compose.material3.Text("Group Name") },
                            singleLine = true,
                        )
                    },
                    confirmButton = {
                        androidx.compose.material3.TextButton(
                            onClick = {
                                if (name.isNotBlank()) {
                                    scope.launch {
                                        try {
                                            viewModel.renameLinkedGroup(name)
                                            onDismissRequest()
                                        } catch (e: Exception) {
                                            // Dialog stays open, error shown in snackbar from viewModel
                                        }
                                    }
                                }
                            },
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
            is MangaViewModel.Dialog.DeleteLinkedGroup -> {
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = onDismissRequest,
                    title = { androidx.compose.material3.Text("Delete Group") },
                    text = { androidx.compose.material3.Text("Are you sure you want to delete '${dialog.group.name}'?") },
                    confirmButton = {
                        androidx.compose.material3.TextButton(
                            onClick = {
                                viewModel.deleteLinkedGroup()
                                onDismissRequest()
                            },
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
            MangaViewModel.Dialog.TrackUpdateWatch -> {
                TrackUpdateWatchDialog(
                    onDismissRequest = onDismissRequest,
                    onConfirm = viewModel::trackUpdateWatch,
                )
            }
        }

        if (showScanlatorsDialog) {
            ScanlatorFilterDialog(
                availableScanlators = successState.availableScanlators,
                excludedScanlators = successState.excludedScanlators,
                onDismissRequest = { showScanlatorsDialog = false },
                onConfirm = viewModel::setExcludedScanlators,
            )
        }
    }

    private fun continueReading(context: Context, unreadChapter: Chapter?) {
        if (unreadChapter != null) openChapter(context, unreadChapter)
    }

    private fun openChapter(context: Context, chapter: Chapter) {
        context.startActivity(ReaderActivity.newIntent(context, chapter.mangaId, chapter.id))
    }

    private fun getMangaUrl(manga_: Manga?, source_: Source?): String? {
        val manga = manga_ ?: return null
        val source = source_ as? HttpSource ?: return null

        return try {
            source.getMangaUrl(manga.toSManga())
        } catch (e: Exception) {
            null
        }
    }

    private fun openMangaInWebView(navigator: Navigator, manga_: Manga?, source_: Source?) {
        getMangaUrl(manga_, source_)?.let { url ->
            navigator.push(
                WebViewScreen(
                    url = url,
                    initialTitle = manga_?.title,
                    sourceId = source_?.id,
                ),
            )
        }
    }

    private fun shareManga(context: Context, manga_: Manga?, source_: Source?) {
        try {
            getMangaUrl(manga_, source_)?.let { url ->
                val intent = url.toUri().toShareIntent(context, type = "text/plain")
                context.startActivity(intent)
            }
        } catch (e: Exception) {
            context.toast(e.message)
        }
    }

    private fun shareMangaToChat(context: Context, manga_: Manga?, source_: Source?) {
        val manga = manga_ ?: return
        val source = source_ ?: return
        val enhancedPreferences = Injekt.get<EnhancedPreferences>()
        val username = enhancedPreferences.globalChatUsername.get()

        if (username.isBlank()) {
            context.toast("Please set a username in Chat first")
            return
        }

        val db = com.google.firebase.firestore.FirebaseFirestore.getInstance()
        val sourceDomain = (source as? HttpSource)?.baseUrl?.let {
            try {
                URI(it).host?.removePrefix("www.")
            } catch (e: Exception) {
                null
            }
        }

        val messageData = mapOf(
            "senderName" to username,
            "text" to "Shared a manga",
            "timestamp" to com.google.firebase.firestore.FieldValue.serverTimestamp(),
            "isMangaShare" to true,
            "mangaUrl" to manga.url,
            "sourceId" to manga.source,
            "sourceDomain" to sourceDomain,
            "mangaTitle" to manga.title,
            "mangaCoverUrl" to manga.thumbnailUrl,
            "sourceName" to source.name,
        )

        db.collection("global_chat").add(messageData)
            .addOnSuccessListener {
                context.toast("Shared to Chat")
            }
            .addOnFailureListener { e ->
                context.toast("Failed to share: ${e.message}")
            }
    }

    /**
     * Perform a search using the provided query.
     *
     * @param query the search query to the parent controller
     */
    private suspend fun performSearch(navigator: Navigator, query: String, global: Boolean) {
        if (global) {
            navigator.push(GlobalSearchScreen(query))
            return
        }

        if (navigator.size < 2) {
            return
        }

        when (val previousController = navigator.items[navigator.size - 2]) {
            is HomeScreen -> {
                navigator.pop()
                previousController.search(query)
            }
            is BrowseSourceScreen -> {
                navigator.pop()
                previousController.search(query)
            }
        }
    }

    /**
     * Performs a genre search using the provided genre name.
     *
     * @param genreName the search genre to the parent controller
     */
    private suspend fun performGenreSearch(navigator: Navigator, genreName: String, source: Source) {
        if (navigator.size < 2) {
            return
        }

        val previousController = navigator.items[navigator.size - 2]
        if (previousController is BrowseSourceScreen && source is HttpSource) {
            navigator.pop()
            previousController.searchGenre(genreName)
        } else {
            performSearch(navigator, genreName, global = false)
        }
    }

    /**
     * Copy Manga URL to Clipboard
     */
    private fun copyMangaUrl(context: Context, manga_: Manga?, source_: Source?) {
        val manga = manga_ ?: return
        val source = source_ as? HttpSource ?: return
        val url = source.getMangaUrl(manga.toSManga())
        context.copyToClipboard(url, url)
    }
}

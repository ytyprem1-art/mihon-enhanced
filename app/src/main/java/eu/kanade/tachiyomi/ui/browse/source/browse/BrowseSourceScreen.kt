package eu.kanade.tachiyomi.ui.browse.source.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.NewReleases
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalUriHandler
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.compose.viewModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.core.util.ifSourcesLoaded
import eu.kanade.presentation.browse.BrowseSourceContent
import eu.kanade.presentation.browse.MissingSourceScreen
import eu.kanade.presentation.browse.components.BrowseSourceToolbar
import eu.kanade.presentation.browse.components.RemoveMangaDialog
import eu.kanade.presentation.category.components.ChangeCategoryDialog
import eu.kanade.presentation.manga.DuplicateMangaDialog
import eu.kanade.presentation.util.AssistContentScreen
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.ui.browse.extension.details.SourcePreferencesScreen
import eu.kanade.tachiyomi.ui.browse.source.browse.BrowseSourceViewModel.Listing
import eu.kanade.tachiyomi.ui.category.CategoryScreen
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import tachiyomi.domain.manga.model.Manga
import androidx.paging.LoadState
import androidx.compose.runtime.saveable.rememberSaveable
import eu.kanade.tachiyomi.ui.webview.WebViewScreen
import eu.kanade.tachiyomi.ui.mod.helper.TitleMatchHelper
import eu.kanade.tachiyomi.ui.mod.helper.CloudflareChallengeHelper
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.receiveAsFlow
import mihon.feature.migration.dialog.MigrateMangaDialog
import mihon.presentation.core.util.collectAsLazyPagingItems
import tachiyomi.core.common.Constants
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.domain.source.model.StubSource
import androidx.compose.material.icons.automirrored.outlined.CompareArrows
import androidx.compose.material3.FloatingActionButton
import eu.kanade.domain.source.interactor.GetEnabledSources
import eu.kanade.presentation.browse.components.QuickSourceSwitcherDialog
import tachiyomi.domain.source.interactor.GetRemoteManga
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.LoadingScreen
import tachiyomi.source.local.LocalSource

data class BrowseSourceScreen(
    val sourceId: Long,
    private val listingQuery: String?,
    private val smartJumpTitle: String? = null,
    private val smartJumpSessionId: String? = null,
    private val smartJumpAnchorSourceId: Long? = null,
) : Screen(), AssistContentScreen {

    private var assistUrl: String? = null

    override fun onProvideAssistUrl() = assistUrl

    @Composable
    override fun Content() {
        if (!ifSourcesLoaded()) {
            LoadingScreen()
            return
        }

        val viewModel = viewModel<BrowseSourceViewModel>(
            factory = BrowseSourceViewModel.Factory,
            extras = CreationExtras {
                set(BrowseSourceViewModel.SOURCE_ID_KEY, sourceId)
                set(BrowseSourceViewModel.LISTING_QUERY_KEY, listingQuery)
            },
        )
        val state by viewModel.state.collectAsState()

        val navigator = LocalNavigator.currentOrThrow

        // MOD START: Quick Switcher
        val getEnabledSources: GetEnabledSources = remember { Injekt.get() }
        val sources by getEnabledSources.subscribe().collectAsState(emptyList())
        var showSourceSwitcher by remember { mutableStateOf(false) }

        var hasAutoOpened by rememberSaveable(smartJumpSessionId) { mutableStateOf(false) }
        val mangaLazyPagingItems = viewModel.mangaPagerFlowFlow.collectAsLazyPagingItems()

        LaunchedEffect(mangaLazyPagingItems.loadState.refresh, smartJumpTitle) {
            if (smartJumpTitle == null || hasAutoOpened) return@LaunchedEffect
            if (mangaLazyPagingItems.loadState.refresh !is LoadState.NotLoading) return@LaunchedEffect

            val matchesByLevel = mutableMapOf<TitleMatchHelper.MatchType, MutableList<Manga>>()
            for (i in 0 until mangaLazyPagingItems.itemCount) {
                val mangaFlow = mangaLazyPagingItems[i] ?: continue
                val manga = mangaFlow.first()
                val matchType = TitleMatchHelper.getMatchType(smartJumpTitle, manga.title)
                if (matchType != TitleMatchHelper.MatchType.NONE) {
                    matchesByLevel.getOrPut(matchType) { mutableListOf() }.add(manga)
                }
            }

            // Priority: EXACT_MATCH > TITLE_MATCH
            val bestMatches = matchesByLevel[TitleMatchHelper.MatchType.EXACT_MATCH]
                ?: matchesByLevel[TitleMatchHelper.MatchType.TITLE_MATCH]

            if (bestMatches != null && bestMatches.size == 1) {
                hasAutoOpened = true
                navigator.replace(
                    MangaScreen(
                        bestMatches.first().id,
                        true,
                        smartJumpSessionId,
                        smartJumpAnchorSourceId
                    )
                )
            }
        }
        // MOD END: Quick Switcher

        val navigateUp: () -> Unit = {
            when {
                !state.isUserQuery && state.toolbarQuery != null -> viewModel.setToolbarQuery(null)
                else -> navigator.pop()
            }
        }

        if (viewModel.source is StubSource) {
            MissingSourceScreen(
                source = viewModel.source,
                navigateUp = navigateUp,
            )
            return
        }

        val scope = rememberCoroutineScope()
        val haptic = LocalHapticFeedback.current
        val uriHandler = LocalUriHandler.current
        val snackbarHostState = remember { SnackbarHostState() }

        // MOD START: Manganato Cloudflare Auto-Challenge
        var isWebViewSolved by remember { mutableStateOf(false) }

        LaunchedEffect(state.listing) {
            val listing = state.listing
            if (listing is Listing.Search &&
                CloudflareChallengeHelper.isManganato(viewModel.source) &&
                !viewModel.hasAutoTriggeredCloudflare
            ) {
                val source = viewModel.source as? HttpSource ?: return@LaunchedEffect
                val searchUrl = CloudflareChallengeHelper.getSearchUrl(source, listing.query, listing.filters)

                if (!CloudflareChallengeHelper.hasValidCfClearance(source, searchUrl)) {
                    viewModel.hasAutoTriggeredCloudflare = true
                    isWebViewSolved = false
                    navigator.push(
                        WebViewScreen(
                            url = searchUrl ?: source.getHomeUrl(),
                            initialTitle = source.name,
                            sourceId = source.id,
                            onDismissed = {
                                if (!isWebViewSolved) {
                                    CloudflareChallengeHelper.invalidateManganatoClearance()
                                }
                                mangaLazyPagingItems.refresh()
                            },
                            onAutoCloseCondition = { url, html ->
                                val isSolved = url.contains("/search/story/") &&
                                    "window._cf_chl_opt" !in html &&
                                    "Ray ID is" !in html
                                if (isSolved) {
                                    isWebViewSolved = true
                                    CloudflareChallengeHelper.markManganatoClearanceVerified()
                                }
                                isSolved
                            },
                        ),
                    )
                }
            }
        }

        LaunchedEffect(mangaLazyPagingItems.loadState.refresh) {
            val loadState = mangaLazyPagingItems.loadState.refresh
            if (loadState is LoadState.Error &&
                CloudflareChallengeHelper.isManganato(viewModel.source) &&
                CloudflareChallengeHelper.isCloudflareBypassFailure(loadState.error) &&
                !viewModel.hasAutoTriggeredCloudflare
            ) {
                val source = viewModel.source as? HttpSource ?: return@LaunchedEffect
                val listing = state.listing
                val searchUrl = CloudflareChallengeHelper.getSearchUrl(source, listing.query, listing.filters)

                CloudflareChallengeHelper.invalidateManganatoClearance()
                viewModel.hasAutoTriggeredCloudflare = true
                isWebViewSolved = false
                navigator.push(
                    WebViewScreen(
                        url = searchUrl ?: source.getHomeUrl(),
                        initialTitle = source.name,
                        sourceId = source.id,
                        onDismissed = {
                            if (!isWebViewSolved) {
                                CloudflareChallengeHelper.invalidateManganatoClearance()
                            }
                            mangaLazyPagingItems.retry()
                        },
                        onAutoCloseCondition = { url, html ->
                            val isSolved = url.contains("/search/story/") &&
                                "window._cf_chl_opt" !in html &&
                                "Ray ID is" !in html
                            if (isSolved) {
                                isWebViewSolved = true
                                CloudflareChallengeHelper.markManganatoClearanceVerified()
                            }
                            isSolved
                        },
                    ),
                )
            }
        }
        // MOD END: Manganato Cloudflare Auto-Challenge

        val onHelpClick = { uriHandler.openUri(LocalSource.HELP_URL) }
        val onWebViewClick = f@{
            val source = viewModel.source as? HttpSource ?: return@f
            navigator.push(
                WebViewScreen(
                    url = source.getHomeUrl(),
                    initialTitle = source.name,
                    sourceId = source.id,
                ),
            )
        }

        LaunchedEffect(viewModel.source) {
            assistUrl = (viewModel.source as? HttpSource)?.getHomeUrl()
        }

        Scaffold(
            topBar = {
                Column(
                    modifier = Modifier
                        .background(MaterialTheme.colorScheme.surface)
                        .pointerInput(Unit) {},
                ) {
                    BrowseSourceToolbar(
                        searchQuery = state.toolbarQuery,
                        onSearchQueryChange = viewModel::setToolbarQuery,
                        source = viewModel.source,
                        displayMode = viewModel.displayMode,
                        onDisplayModeChange = { viewModel.displayMode = it },
                        navigateUp = navigateUp,
                        onWebViewClick = onWebViewClick,
                        onHelpClick = onHelpClick,
                        onSettingsClick = { navigator.push(SourcePreferencesScreen(sourceId)) },
                        onSearch = viewModel::search,
                    )

                    Row(
                        modifier = Modifier
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = MaterialTheme.padding.small),
                        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
                    ) {
                        FilterChip(
                            selected = state.listing == Listing.Popular,
                            onClick = {
                                viewModel.resetFilters()
                                viewModel.setListing(Listing.Popular)
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Outlined.Favorite,
                                    contentDescription = null,
                                    modifier = Modifier
                                        .size(FilterChipDefaults.IconSize),
                                )
                            },
                            label = {
                                Text(text = stringResource(MR.strings.popular))
                            },
                        )
                        if (viewModel.source.supportsLatest) {
                            FilterChip(
                                selected = state.listing == Listing.Latest,
                                onClick = {
                                    viewModel.resetFilters()
                                    viewModel.setListing(Listing.Latest)
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Outlined.NewReleases,
                                        contentDescription = null,
                                        modifier = Modifier
                                            .size(FilterChipDefaults.IconSize),
                                    )
                                },
                                label = {
                                    Text(text = stringResource(MR.strings.latest))
                                },
                            )
                        }
                        if (state.filters.isNotEmpty()) {
                            FilterChip(
                                selected = state.listing is Listing.Search,
                                onClick = viewModel::openFilterSheet,
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Outlined.FilterList,
                                        contentDescription = null,
                                        modifier = Modifier
                                            .size(FilterChipDefaults.IconSize),
                                    )
                                },
                                label = {
                                    Text(text = stringResource(MR.strings.action_filter))
                                },
                            )
                        }
                    }

                    HorizontalDivider()
                }
            },
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
            floatingActionButton = {
                // MOD START: Quick Switcher
                FloatingActionButton(
                    onClick = { showSourceSwitcher = true },
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.CompareArrows,
                        contentDescription = "Switch Source",
                    )
                }
                // MOD END: Quick Switcher
            },
        ) { paddingValues ->
            BrowseSourceContent(
                source = viewModel.source,
                mangaList = mangaLazyPagingItems,
                columns = viewModel.getColumnsPreference(LocalConfiguration.current.orientation),
                displayMode = viewModel.displayMode,
                snackbarHostState = snackbarHostState,
                contentPadding = paddingValues,
                onWebViewClick = onWebViewClick,
                onHelpClick = { uriHandler.openUri(Constants.URL_HELP) },
                onLocalSourceHelpClick = onHelpClick,
                onMangaClick = {
                    navigator.push(
                        MangaScreen(
                            it.id,
                            true,
                            smartJumpSessionId,
                            smartJumpAnchorSourceId
                        )
                    )
                },
                onMangaLongClick = { manga ->
                    scope.launchIO {
                        val duplicates = viewModel.getDuplicateLibraryManga(manga)
                        when {
                            manga.favorite -> viewModel.setDialog(BrowseSourceViewModel.Dialog.RemoveManga(manga))
                            duplicates.isNotEmpty() -> viewModel.setDialog(
                                BrowseSourceViewModel.Dialog.AddDuplicateManga(manga, duplicates),
                            )
                            else -> viewModel.addFavorite(manga)
                        }
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    }
                },
                smartJumpTitle = smartJumpTitle,
            )
        }

        val onDismissRequest = { viewModel.setDialog(null) }
        when (val dialog = state.dialog) {
            is BrowseSourceViewModel.Dialog.Filter -> {
                SourceFilterDialog(
                    onDismissRequest = onDismissRequest,
                    filters = state.filters,
                    onReset = viewModel::resetFilters,
                    onFilter = { viewModel.search(filters = state.filters) },
                    onUpdate = viewModel::setFilters,
                )
            }
            is BrowseSourceViewModel.Dialog.AddDuplicateManga -> {
                DuplicateMangaDialog(
                    duplicates = dialog.duplicates,
                    onDismissRequest = onDismissRequest,
                    onConfirm = { viewModel.addFavorite(dialog.manga) },
                    onOpenManga = { navigator.push(MangaScreen(it.id)) },
                    onMigrate = { viewModel.setDialog(BrowseSourceViewModel.Dialog.Migrate(dialog.manga, it)) },
                )
            }

            is BrowseSourceViewModel.Dialog.Migrate -> {
                MigrateMangaDialog(
                    current = dialog.current,
                    target = dialog.target,
                    // Initiated from the context of [dialog.target] so we show [dialog.current].
                    onClickTitle = { navigator.push(MangaScreen(dialog.current.id)) },
                    onDismissRequest = onDismissRequest,
                )
            }
            is BrowseSourceViewModel.Dialog.RemoveManga -> {
                RemoveMangaDialog(
                    onDismissRequest = onDismissRequest,
                    onConfirm = {
                        viewModel.changeMangaFavorite(dialog.manga)
                    },
                    mangaToRemove = dialog.manga,
                )
            }
            is BrowseSourceViewModel.Dialog.ChangeMangaCategory -> {
                ChangeCategoryDialog(
                    initialSelection = dialog.initialSelection,
                    onDismissRequest = onDismissRequest,
                    onEditCategories = { navigator.push(CategoryScreen()) },
                    onConfirm = { include, _ ->
                        viewModel.changeMangaFavorite(dialog.manga)
                        viewModel.moveMangaToCategories(dialog.manga, include)
                    },
                )
            }
            else -> {}
        }

        // MOD START: Quick Switcher
        if (showSourceSwitcher) {
            QuickSourceSwitcherDialog(
                onDismissRequest = { showSourceSwitcher = false },
                sources = sources,
                currentSourceId = sourceId,
                onSourceSelected = { source ->
                    val query = if (source.supportsLatest) {
                        GetRemoteManga.QUERY_LATEST
                    } else {
                        GetRemoteManga.QUERY_POPULAR
                    }
                    navigator.replace(BrowseSourceScreen(source.id, query))
                },
            )
        }
        // MOD END: Quick Switcher

        LaunchedEffect(Unit) {
            queryEvent.receiveAsFlow()
                .collectLatest {
                    when (it) {
                        is SearchType.Genre -> viewModel.searchGenre(it.txt)
                        is SearchType.Text -> viewModel.search(it.txt)
                    }
                }
        }
    }

    suspend fun search(query: String) = queryEvent.send(SearchType.Text(query))
    suspend fun searchGenre(name: String) = queryEvent.send(SearchType.Genre(name))

    companion object {
        private val queryEvent = Channel<SearchType>()
    }

    sealed class SearchType(val txt: String) {
        class Text(txt: String) : SearchType(txt)
        class Genre(txt: String) : SearchType(txt)
    }
}

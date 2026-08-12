package eu.kanade.tachiyomi.ui.browse.source.linked

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.compose.viewModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.core.util.ifSourcesLoaded
import eu.kanade.presentation.browse.GlobalSearchScreen
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.ui.browse.source.browse.BrowseSourceScreen
import eu.kanade.tachiyomi.ui.browse.source.globalsearch.SearchItemResult
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import eu.kanade.tachiyomi.util.system.toast
import tachiyomi.domain.manga.model.Manga
import tachiyomi.presentation.core.screens.LoadingScreen

class LinkedSourceSearchScreen(
    private val linkedGroupId: Long,
    private val searchQuery: String = "",
) : Screen() {

    @Composable
    override fun Content() {
        if (!ifSourcesLoaded()) {
            LoadingScreen()
            return
        }

        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current

        val viewModel = viewModel<LinkedSourceSearchViewModel>(
            factory = LinkedSourceSearchViewModel.Factory,
            extras = CreationExtras {
                set(LinkedSourceSearchViewModel.LINKED_GROUP_ID_KEY, linkedGroupId)
                set(LinkedSourceSearchViewModel.INITIAL_QUERY_KEY, searchQuery)
            },
        )
        val state by viewModel.state.collectAsState()
        var showSingleLoadingScreen by remember {
            mutableStateOf(searchQuery.isNotEmpty() && state.total == 1)
        }

        if (showSingleLoadingScreen) {
            LoadingScreen()

            LaunchedEffect(state.items) {
                when (val result = state.items.values.singleOrNull()) {
                    SearchItemResult.Loading -> return@LaunchedEffect
                    is SearchItemResult.Success -> {
                        val manga = result.result.singleOrNull()
                        if (manga != null) {
                            navigator.replace(MangaScreen(manga.id, true))
                        } else {
                            showSingleLoadingScreen = false
                        }
                    }
                    else -> showSingleLoadingScreen = false
                }
            }
        } else {
            GlobalSearchScreen(
                state = state,
                navigateUp = navigator::pop,
                onChangeSearchQuery = viewModel::updateSearchQuery,
                onSearch = { viewModel.search() },
                getManga = { viewModel.getManga(it) },
                onChangeSearchFilter = viewModel::setSourceFilter,
                onToggleResults = viewModel::toggleFilterResults,
                onClickSource = {
                    navigator.push(BrowseSourceScreen(it.id, state.searchQuery))
                },
                onClickItem = { navigator.push(MangaScreen(it.id, true)) },
                onLongClickItem = { navigator.push(MangaScreen(it.id, true)) },
                itemAction = { manga ->
                    SearchResultAction(
                        manga = manga,
                        currentGroupId = linkedGroupId,
                        mangaGroupId = state.mangaGroupIds[manga.id],
                        onAddClick = { targetManga ->
                            viewModel.addMangaToGroup(targetManga) { result ->
                                when (result) {
                                    AddResult.Success -> context.toast("Added to group")
                                    AddResult.AlreadyInThisGroup -> {}
                                    is AddResult.InAnotherGroup -> context.toast("Already in group: ${result.groupName}")
                                    is AddResult.Error -> context.toast("Error: ${result.message}")
                                }
                            }
                        }
                    )
                },
            )
        }
    }
}

@Composable
private fun SearchResultAction(
    manga: Manga,
    currentGroupId: Long,
    mangaGroupId: Long?,
    onAddClick: (Manga) -> Unit,
) {
    if (mangaGroupId != null) {
        val text = if (mangaGroupId == currentGroupId) "Added" else "Linked"
        val backgroundColor = if (mangaGroupId == currentGroupId) Color.Black else Color.Gray
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(backgroundColor.copy(alpha = 0.6f))
                .padding(horizontal = 4.dp, vertical = 2.dp),
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
            )
        }
    } else {
        Surface(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .clickable { onAddClick(manga) },
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            tonalElevation = 4.dp,
            shadowElevation = 4.dp,
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.5f))
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Outlined.Add,
                    contentDescription = "Add to group",
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

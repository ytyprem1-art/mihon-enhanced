package eu.kanade.tachiyomi.ui.mod.search

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.browse.components.GlobalSearchErrorResultItem
import eu.kanade.presentation.browse.components.GlobalSearchLoadingResultItem
import eu.kanade.presentation.browse.components.GlobalSearchResultItem
import eu.kanade.presentation.components.SearchToolbar
import eu.kanade.presentation.library.components.MangaListItem
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.util.system.LocaleHelper
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.asMangaCover
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource

class GlobalSourceSearchScreen(
    private val initialQuery: String? = null,
    private val parentMangaId: Long? = null,
) : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val screenModel = viewModel { GlobalSourceSearchScreenModel(initialQuery, parentMangaId) }
        val state by screenModel.state.collectAsState()

        Scaffold(
            topBar = { scrollBehavior ->
                SearchToolbar(
                    searchQuery = state.searchQuery,
                    onChangeSearchQuery = screenModel::updateSearchQuery,
                    onSearch = { screenModel.search() },
                    onClickCloseSearch = navigator::pop,
                    navigateUp = navigator::pop,
                    scrollBehavior = scrollBehavior,
                )
            },
        ) { paddingValues ->
            GlobalSourceSearchContent(
                items = state.items,
                mergedMangaIds = state.mergedMangaIds,
                contentPadding = paddingValues,
                getManga = { screenModel.getManga(it) },
                onAdd = screenModel::showMergeConfirmation,
            )

            when (val dialog = state.dialog) {
                is GlobalSourceSearchScreenModel.Dialog.MergeConfirmation -> {
                    androidx.compose.material3.AlertDialog(
                        onDismissRequest = screenModel::dismissDialog,
                        title = { Text(stringResource(MR.strings.merge_with_another)) },
                        text = { Text(stringResource(MR.strings.merge_with_another_desc)) },
                        confirmButton = {
                            TextButton(
                                onClick = { screenModel.performMerge(dialog.manga) },
                            ) {
                                Text(stringResource(MR.strings.action_ok))
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = screenModel::dismissDialog) {
                                Text(stringResource(MR.strings.action_cancel))
                            }
                        },
                    )
                }
                null -> {}
            }
        }
    }
}

@Composable
private fun GlobalSourceSearchContent(
    items: Map<CatalogueSource, GlobalSourceSearchScreenModel.SearchItemResult>,
    mergedMangaIds: Set<Long>,
    contentPadding: PaddingValues,
    getManga: @Composable (Manga) -> State<Manga>,
    onAdd: (Manga) -> Unit,
) {
    LazyColumn(
        contentPadding = contentPadding,
    ) {
        items.forEach { (source, result) ->
            item(key = source.id) {
                GlobalSearchResultItem(
                    title = source.name,
                    subtitle = LocaleHelper.getLocalizedDisplayName(source.lang),
                    onClick = { /* No-op */ },
                    modifier = Modifier.animateItem(),
                ) {
                    if (result is GlobalSourceSearchScreenModel.SearchItemResult.Loading) {
                        GlobalSearchLoadingResultItem()
                    }
                    if (result is GlobalSourceSearchScreenModel.SearchItemResult.Error) {
                        GlobalSearchErrorResultItem(message = result.throwable.message)
                    }
                }
            }

            if (result is GlobalSourceSearchScreenModel.SearchItemResult.Success) {
                items(
                    items = result.result,
                    key = { "${source.id}-${it.id}" },
                ) { manga ->
                    val mangaState by getManga(manga)
                    val isMerged = mangaState.id in mergedMangaIds

                    MangaListItem(
                        title = mangaState.title,
                        coverData = mangaState.asMangaCover(),
                        badge = {
                            if (isMerged) {
                                FilledTonalButton(
                                    onClick = {},
                                    enabled = false,
                                    colors = ButtonDefaults.filledTonalButtonColors(
                                        disabledContainerColor = Color(0xFF4CAF50), // Green
                                        disabledContentColor = Color.White
                                    ),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                                    modifier = Modifier.height(32.dp)
                                ) {
                                    Text("MERGED", style = MaterialTheme.typography.labelSmall)
                                }
                            } else {
                                FilledTonalButton(
                                    onClick = { onAdd(mangaState) },
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                                    modifier = Modifier.height(32.dp)
                                ) {
                                    Text("ADD", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        },
                        onClick = {},
                        onLongClick = {},
                    )
                }
            }
        }
    }
}

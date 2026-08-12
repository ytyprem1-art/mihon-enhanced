package eu.kanade.presentation.browse

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import eu.kanade.presentation.browse.components.LinkedSourceGroupItem
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.presentation.components.SearchToolbar
import eu.kanade.tachiyomi.ui.browse.source.linked.LinkedSourcesViewModel
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.source.linked.model.LinkedSourceGroup
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.FastScrollLazyColumn
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen

@Composable
fun LinkedSourcesScreen(
    groups: List<LinkedSourcesViewModel.GroupWithMetadata>,
    searchQuery: String?,
    onSearchQueryChange: (String?) -> Unit,
    sortMode: LibraryPreferences.LinkedSourceGroupSort,
    onSortModeChange: (LibraryPreferences.LinkedSourceGroupSort) -> Unit,
    onClickCreate: () -> Unit,
    onClickRename: (LinkedSourceGroup) -> Unit,
    onClickDelete: (LinkedSourceGroup) -> Unit,
    onClickGroup: (LinkedSourceGroup) -> Unit,
    navigateUp: () -> Unit,
) {
    val scrollState = rememberLazyListState()

    LaunchedEffect(sortMode) {
        scrollState.scrollToItem(0)
    }

    Scaffold(
        topBar = { scrollBehavior ->
            SearchToolbar(
                searchQuery = searchQuery,
                onChangeSearchQuery = onSearchQueryChange,
                titleContent = { Text("Linked Source Groups") },
                navigateUp = navigateUp,
                actions = {
                    AppBarActions(
                        listOf(
                            AppBar.Action(
                                title = stringResource(MR.strings.action_add),
                                icon = Icons.Outlined.Add,
                                onClick = onClickCreate,
                            ),
                        ),
                    )

                    var showSortMenu by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { showSortMenu = true }) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Outlined.Sort,
                                contentDescription = stringResource(MR.strings.action_sort),
                            )
                        }

                        DropdownMenu(
                            expanded = showSortMenu,
                            onDismissRequest = { showSortMenu = false },
                        ) {
                            listOf(
                                LibraryPreferences.LinkedSourceGroupSort.Newest to MR.strings.action_newest,
                                LibraryPreferences.LinkedSourceGroupSort.Oldest to MR.strings.action_oldest,
                                LibraryPreferences.LinkedSourceGroupSort.TitleAZ to MR.strings.action_sort_alpha,
                                LibraryPreferences.LinkedSourceGroupSort.TitleZA to null,
                            ).forEach { (mode, titleRes) ->
                                DropdownMenuItem(
                                    text = {
                                        val title = if (titleRes != null) stringResource(titleRes) else "Title Z–A"
                                        Text(title)
                                    },
                                    onClick = {
                                        onSortModeChange(mode)
                                        showSortMenu = false
                                    },
                                    trailingIcon = {
                                        RadioButton(
                                            selected = sortMode == mode,
                                            onClick = null,
                                        )
                                    },
                                )
                            }
                        }
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { contentPadding ->
        if (groups.isEmpty()) {
            val msg = if (!searchQuery.isNullOrBlank()) {
                MR.strings.no_results_found
            } else {
                MR.strings.information_no_recent // Placeholder
            }
            EmptyScreen(
                stringRes = msg,
                modifier = Modifier.padding(contentPadding),
            )
            return@Scaffold
        }

        FastScrollLazyColumn(
            contentPadding = contentPadding,
            state = scrollState,
        ) {
            items(
                items = groups,
                key = { it.group.id },
            ) { item ->
                LinkedSourceGroupItem(
                    modifier = Modifier.animateItem(),
                    group = item.group,
                    representativeManga = item.representativeManga,
                    sourceNames = item.sourceNames,
                    onClick = { onClickGroup(item.group) },
                    onLongClick = { onClickDelete(item.group) },
                    onRename = { onClickRename(item.group) },
                    onDelete = { onClickDelete(item.group) },
                )
            }
        }
    }
}

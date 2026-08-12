package eu.kanade.presentation.browse

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.browse.components.LinkedSourceMemberItem
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.presentation.components.DropdownMenu
import eu.kanade.tachiyomi.ui.manga.LinkedMember
import tachiyomi.domain.source.linked.model.LinkedSourceGroup
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.FastScrollLazyColumn
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

@Composable
fun LinkedSourceDetailsScreen(
    group: LinkedSourceGroup?,
    members: List<LinkedMember>,
    refreshingIds: Set<Long>,
    onClickMember: (LinkedMember) -> Unit,
    onClickAdd: () -> Unit,
    onClickCreateHistoryGroup: () -> Unit,
    onClickSetTrackingSource: () -> Unit,
    onRefreshMember: (LinkedMember) -> Unit,
    onDeleteMember: (LinkedMember) -> Unit,
    navigateUp: () -> Unit,
) {
    Scaffold(
        topBar = { scrollBehavior ->
            AppBar(
                title = group?.name ?: "",
                navigateUp = navigateUp,
                actions = {
                    AppBarActions(
                        buildList {
                            add(
                                AppBar.Action(
                                    title = stringResource(MR.strings.action_add),
                                    icon = Icons.Outlined.Add,
                                    onClick = onClickAdd,
                                )
                            )
                            add(
                                AppBar.OverflowAction(
                                    title = "Set tracking source",
                                    onClick = onClickSetTrackingSource,
                                )
                            )
                            add(
                                AppBar.OverflowAction(
                                    title = "Create history group",
                                    onClick = onClickCreateHistoryGroup,
                                )
                            )
                        },
                    )
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { contentPadding ->
        val sourceManager: SourceManager = remember { Injekt.get() }
        FastScrollLazyColumn(
            contentPadding = contentPadding,
        ) {
            val trackingMembers = members.filter { it.isTracking }
            if (trackingMembers.isNotEmpty()) {
                item {
                    androidx.compose.material3.Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    ) {
                        androidx.compose.foundation.layout.Column(
                            modifier = Modifier.padding(horizontal = MaterialTheme.padding.medium, vertical = 8.dp)
                        ) {
                            trackingMembers.forEach { member ->
                                val sourceName = sourceManager.getOrStub(member.manga.source).name
                                val statusText = if (member.isPaused) " (Paused)" else ""
                                Text(
                                    text = "Tracking: $sourceName$statusText",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
            items(
                items = members,
                key = { it.manga.id },
            ) { member ->
                LinkedSourceMemberItem(
                    modifier = Modifier.animateItem(),
                    member = member,
                    isRefreshing = refreshingIds.contains(member.manga.id),
                    onRefresh = { onRefreshMember(member) },
                    onDelete = { onDeleteMember(member) },
                    onClick = { onClickMember(member) },
                )
            }
        }
    }
}

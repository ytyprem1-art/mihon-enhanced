package eu.kanade.presentation.manga.components

import android.text.format.DateUtils
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DensityMedium
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.AdaptiveSheet
import eu.kanade.tachiyomi.ui.manga.LinkedMember
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.linked.model.LinkedSourceGroup
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.TextButton
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.time.Instant
import java.util.concurrent.TimeUnit

@Composable
fun LinkedSourcesSheet(
    onDismissRequest: () -> Unit,
    onSearchSourcesClick: () -> Unit,
    onJoinGroupClick: () -> Unit,
    onAddSourceClick: () -> Unit,
    onMemberOpenClick: (Manga) -> Unit,
    onMemberReadClick: (mangaId: Long, chapterId: Long) -> Unit,
    onMemberLatestClick: (mangaId: Long, chapterId: Long) -> Unit,
    onMemberRemoveClick: (Manga) -> Unit,
    onMemberRefreshClick: (Manga) -> Unit,
    onRefreshAllClick: () -> Unit,
    onRenameGroupClick: () -> Unit,
    onDeleteGroupClick: () -> Unit,
    isWideCompact: Boolean,
    onToggleWideCompact: () -> Unit,
    linkedGroup: LinkedSourceGroup?,
    linkedMembers: List<LinkedMember>,
    currentMangaId: Long,
    refreshingIds: Set<Long>,
) {
    val configuration = LocalConfiguration.current
    val screenWidth = configuration.screenWidthDp.dp

    AdaptiveSheet(
        onDismissRequest = onDismissRequest,
        maxWidth = if (isWideCompact) screenWidth * 0.95f else 460.dp,
        modifier = if (isWideCompact) Modifier.requiredWidth(screenWidth * 0.95f) else Modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp),
        ) {
            Text(
                text = "Linked Sources",
                modifier = Modifier.padding(horizontal = 24.dp),
                style = MaterialTheme.typography.titleLarge,
            )

            if (linkedGroup == null) {
                Text(
                    text = "This manga is not linked yet.",
                    modifier = Modifier
                        .padding(horizontal = 24.dp)
                        .padding(top = 8.dp, bottom = 16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                TextButton(
                    onClick = onSearchSourcesClick,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(text = "Search Sources")
                }

                TextButton(
                    onClick = onJoinGroupClick,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(text = "Join Existing Group")
                }
            } else {
                GroupHeader(
                    group = linkedGroup,
                    isWideCompact = isWideCompact,
                    onToggleWideCompact = onToggleWideCompact,
                    onRefreshAll = onRefreshAllClick,
                    onRenameGroup = onRenameGroupClick,
                    onDeleteGroup = onDeleteGroupClick,
                    isRefreshingAny = refreshingIds.isNotEmpty(),
                )

                Spacer(modifier = Modifier.height(8.dp))

                if (isWideCompact) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        MembersTableHeader(isWideCompact = true)
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        for (member in linkedMembers) {
                            MemberTableRow(
                                member = member,
                                isRefreshing = refreshingIds.contains(member.manga.id),
                                currentMangaId = currentMangaId,
                                isWideCompact = true,
                                onRefresh = { onMemberRefreshClick(member.manga) },
                                onOpen = { onMemberOpenClick(member.manga) },
                                onRead = { onMemberReadClick(member.manga.id, it) },
                                onLatest = { onMemberLatestClick(member.manga.id, it) },
                                onRemove = { onMemberRemoveClick(member.manga) },
                            )
                        }
                    }
                } else {
                    val scrollState = rememberScrollState()
                    Column(
                        modifier = Modifier
                            .horizontalScroll(scrollState)
                            .width(IntrinsicSize.Max),
                    ) {
                        MembersTableHeader(isWideCompact = false)
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        for (member in linkedMembers) {
                            MemberTableRow(
                                member = member,
                                isRefreshing = refreshingIds.contains(member.manga.id),
                                currentMangaId = currentMangaId,
                                isWideCompact = false,
                                onRefresh = { onMemberRefreshClick(member.manga) },
                                onOpen = { onMemberOpenClick(member.manga) },
                                onRead = { onMemberReadClick(member.manga.id, it) },
                                onLatest = { onMemberLatestClick(member.manga.id, it) },
                                onRemove = { onMemberRemoveClick(member.manga) },
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                TextButton(
                    onClick = onAddSourceClick,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(text = "+ Add Source")
                }
            }

            TextButton(
                onClick = onDismissRequest,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(text = stringResource(MR.strings.action_cancel))
            }
        }
    }
}

@Composable
private fun GroupHeader(
    group: LinkedSourceGroup,
    isWideCompact: Boolean,
    onToggleWideCompact: () -> Unit,
    onRefreshAll: () -> Unit,
    onRenameGroup: () -> Unit,
    onDeleteGroup: () -> Unit,
    isRefreshingAny: Boolean,
) {
    var showMenu by remember { mutableStateOf(false) }
    var isExpanded by remember { mutableStateOf(false) }
    var isOverflowing by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(top = 8.dp), // Align with button centers roughly
        ) {
            Text(
                text = group.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = if (isExpanded) Int.MAX_VALUE else 2,
                overflow = TextOverflow.Ellipsis,
                onTextLayout = { textLayoutResult ->
                    if (!isExpanded) {
                        isOverflowing = textLayoutResult.hasVisualOverflow || textLayoutResult.lineCount > 2
                    }
                },
            )
            if (isOverflowing || isExpanded) {
                Text(
                    text = if (isExpanded) "Less" else "More",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clickable { isExpanded = !isExpanded }
                        .padding(vertical = 4.dp),
                )
            }
        }

        Row(
            modifier = Modifier.padding(start = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val infiniteTransition = rememberInfiniteTransition(label = "infinite")
            val rotation by infiniteTransition.animateFloat(
                initialValue = 0f,
                targetValue = 360f,
                animationSpec = infiniteRepeatable(
                    animation = tween(1000, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart,
                ),
                label = "rotation",
            )

            IconButton(
                onClick = onRefreshAll,
                enabled = !isRefreshingAny,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Refresh,
                    contentDescription = "Refresh all",
                    modifier = if (isRefreshingAny) Modifier.rotate(rotation) else Modifier,
                )
            }

            IconButton(onClick = onToggleWideCompact) {
                Icon(
                    imageVector = Icons.Outlined.DensityMedium,
                    contentDescription = if (isWideCompact) "Disable wide compact view" else "Enable wide compact view",
                    tint = if (isWideCompact) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                )
            }

            IconButton(onClick = { showMenu = true }) {
                Icon(
                    imageVector = Icons.Outlined.MoreVert,
                    contentDescription = "Group actions",
                )

                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("Rename Group") },
                        onClick = {
                            showMenu = false
                            onRenameGroup()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Delete Group") },
                        onClick = {
                            showMenu = false
                            onDeleteGroup()
                        },
                    )
                }
            }
        }
    }
}

private val coverAreaWidth = 40.dp
private val actionsAreaWidth = 144.dp

private fun RowScope.sourceModifier(isWideCompact: Boolean) =
    if (isWideCompact) {
        Modifier.weight(1.5f)
    } else {
        Modifier.width(120.dp)
    }
private fun RowScope.readModifier(isWideCompact: Boolean) = if (isWideCompact) Modifier.weight(1f) else Modifier.width(90.dp)
private fun RowScope.latestModifier(isWideCompact: Boolean) = if (isWideCompact) Modifier.weight(1f) else Modifier.width(80.dp)
private fun RowScope.statusModifier(isWideCompact: Boolean) = if (isWideCompact) Modifier.weight(1.2f) else Modifier.width(100.dp)
private fun RowScope.lastCheckModifier(isWideCompact: Boolean) = if (isWideCompact) Modifier.weight(1.2f) else Modifier.width(100.dp)
private fun actionsModifier() = Modifier.width(actionsAreaWidth)

@Composable
private fun MembersTableHeader(isWideCompact: Boolean) {
    Row(
        modifier = Modifier
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = sourceModifier(isWideCompact)) {
            Text(
                text = "Source",
                modifier = Modifier.fillMaxWidth().padding(start = coverAreaWidth),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
        }
        HeaderCell(text = "Read", modifier = readModifier(isWideCompact))
        HeaderCell(text = "Latest", modifier = latestModifier(isWideCompact))
        HeaderCell(text = "Status", modifier = statusModifier(isWideCompact))
        HeaderCell(text = "Last Check", modifier = lastCheckModifier(isWideCompact))
        Spacer(modifier = actionsModifier())
    }
}

@Composable
private fun HeaderCell(text: String, modifier: Modifier) {
    Text(
        text = text,
        modifier = modifier,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun MemberTableRow(
    member: LinkedMember,
    isRefreshing: Boolean,
    currentMangaId: Long,
    isWideCompact: Boolean,
    onRefresh: () -> Unit,
    onOpen: () -> Unit,
    onRead: (Long) -> Unit,
    onLatest: (Long) -> Unit,
    onRemove: () -> Unit,
) {
    val manga = member.manga
    val sourceManager: SourceManager = remember { Injekt.get() }
    val sourceName = remember(manga.source) {
        sourceManager.getOrStub(manga.source).name
    }
    val isCurrentManga = manga.id == currentMangaId

    Row(
        modifier = Modifier
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .fillMaxWidth()
            .height(60.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = sourceModifier(isWideCompact)
                .then(
                    if (isCurrentManga) {
                        Modifier
                    } else {
                        Modifier.clickable { onOpen() }
                    }
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MangaCover.Book(
                data = manga,
                modifier = Modifier.size(height = 48.dp, width = 32.dp),
            )

            Spacer(modifier = Modifier.width(8.dp))

            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = sourceName,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    textAlign = TextAlign.Center,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = if (isCurrentManga) "(Now)" else "",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                    if (!isCurrentManga) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.ArrowForward,
                            contentDescription = null,
                            modifier = Modifier.size(12.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        // Read (2 lines)
        val isReadClickable = member.lastReadChapterId != null
        Column(
            modifier = readModifier(isWideCompact)
                .then(
                    if (member.lastReadChapterId != null) {
                        Modifier.clickable { onRead(member.lastReadChapterId) }
                    } else {
                        Modifier
                    }
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = member.lastRead?.let { decimalFormat.format(it) } ?: "—",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isReadClickable) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
                if (isReadClickable) {
                    Spacer(modifier = Modifier.width(2.dp))
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.OpenInNew,
                        contentDescription = null,
                        modifier = Modifier.size(12.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Text(
                text = member.readAt?.let { formatCompactRelativeTime(it) } ?: "",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }

        // Latest (2 lines)
        val isLatestClickable = member.latestChapterId != null
        Column(
            modifier = latestModifier(isWideCompact)
                .then(
                    if (member.latestChapterId != null) {
                        Modifier.clickable { onLatest(member.latestChapterId) }
                    } else {
                        Modifier
                    }
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = member.latestChapter?.let { decimalFormat.format(it) } ?: "—",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isLatestClickable) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
                if (isLatestClickable) {
                    Spacer(modifier = Modifier.width(2.dp))
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.OpenInNew,
                        contentDescription = null,
                        modifier = Modifier.size(12.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Text(
                text = member.latestChapterDateUpload?.let { formatCompactRelativeTime(it) } ?: "",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }

        // Status
        val statusText = when {
            member.latestChapter != null && member.lastRead != null && member.latestChapter > member.lastRead ->
                "+${decimalFormat.format(member.latestChapter - member.lastRead)} unread"
            member.latestChapter != null && member.lastRead != null && member.latestChapter == member.lastRead ->
                "Up to date"
            else -> ""
        }
        Text(
            text = statusText,
            modifier = statusModifier(isWideCompact),
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )

        // Last Check
        val lastCheckTimestamp = manga.lastModifiedAt * 1000
        val lastCheckText = if (lastCheckTimestamp > 0L) {
            formatCompactRelativeTime(lastCheckTimestamp)
        } else {
            "Never"
        }
        Text(
            text = lastCheckText,
            modifier = lastCheckModifier(isWideCompact),
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )

        // Actions
        Row(
            modifier = actionsModifier(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val infiniteTransition = rememberInfiniteTransition(label = "infinite")
            val rotation by infiniteTransition.animateFloat(
                initialValue = 0f,
                targetValue = 360f,
                animationSpec = infiniteRepeatable(
                    animation = tween(1000, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart,
                ),
                label = "rotation",
            )

            IconButton(
                onClick = onRefresh,
                enabled = !isRefreshing,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Refresh,
                    contentDescription = "Refresh",
                    modifier = if (isRefreshing) Modifier.rotate(rotation) else Modifier,
                )
            }

            if (manga.id != currentMangaId) {
                IconButton(onClick = { onOpen() }) {
                    Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = "Open")
                }
            } else {
                Spacer(modifier = Modifier.width(48.dp))
            }

            IconButton(onClick = onRemove) {
                Icon(Icons.Outlined.Delete, contentDescription = "Remove")
            }
        }
    }
}

@Composable
private fun formatCompactRelativeTime(epochMillis: Long): String {
    val now = Instant.now().toEpochMilli()
    val diff = now - epochMillis

    return when {
        diff < TimeUnit.MINUTES.toMillis(1) -> "Just now"
        diff < TimeUnit.HOURS.toMillis(1) -> "${TimeUnit.MILLISECONDS.toMinutes(diff)}m ago"
        DateUtils.isToday(epochMillis) -> "Today"
        isYesterday(epochMillis) -> "Yesterday"
        else -> "${TimeUnit.MILLISECONDS.toDays(diff)}d ago"
    }
}

private fun isYesterday(epochMillis: Long): Boolean {
    return DateUtils.isToday(epochMillis + DateUtils.DAY_IN_MILLIS)
}

private val decimalFormat = java.text.DecimalFormat("#.###").apply {
    decimalFormatSymbols = java.text.DecimalFormatSymbols.getInstance(java.util.Locale.US)
}

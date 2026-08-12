package eu.kanade.tachiyomi.ui.mod.updatewatch

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.history.components.HistoryItem
import eu.kanade.presentation.util.relativeTimeSpanString
import eu.kanade.tachiyomi.ui.mod.updatewatch.helper.UpdateWatchOverdueHelper
import eu.kanade.tachiyomi.ui.mod.updatewatch.helper.UpdateWatchRefreshHelper
import tachiyomi.domain.history.model.HistoryWithRelations
import tachiyomi.domain.history.model.UpdateWatch
import tachiyomi.domain.manga.model.MangaCover
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.presentation.core.components.FastScrollLazyColumn
import tachiyomi.presentation.core.components.ListGroupHeader
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.LoadingScreen
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

@Composable
fun UpdateWatchContent(
    state: UpdateWatchViewModel.State,
    contentPadding: PaddingValues,
    onClickManga: (Long) -> Unit,
    onPauseTracking: (Long) -> Unit,
) {
    val items = state.items
    if (items == null) {
        LoadingScreen(Modifier.padding(contentPadding))
    } else if (items.isEmpty()) {
        EmptyScreen(
            message = "Tracked linked-source updates will appear here.",
            modifier = Modifier.padding(contentPadding),
        )
    } else {
        FastScrollLazyColumn(contentPadding = contentPadding) {
            items(
                items = items,
                key = {
                    when (it) {
                        is UpdateWatchUiModel.Header -> "header-${it.title}"
                        is UpdateWatchUiModel.Item -> "item-${it.trackingManga.id}"
                    }
                }
            ) { item ->
                when (item) {
                    is UpdateWatchUiModel.Header -> {
                        ListGroupHeader(
                            modifier = Modifier.animateItem(),
                            text = item.title,
                        )
                    }
                    is UpdateWatchUiModel.Item -> {
                        val sourceManager = remember { Injekt.get<SourceManager>() }
                        val sourceName = remember(item.trackingManga.source) {
                            sourceManager.getOrStub(item.trackingManga.source).name
                        }

                        var showMenu by remember { mutableStateOf(false) }

                        val warningText = when {
                            item.daysSinceRelease == item.expectedIntervalDays.toLong() - 1 -> "Expected update tomorrow"
                            item.daysSinceRelease == item.expectedIntervalDays.toLong() -> "Expected update around today"
                            else -> stringResource(UpdateWatchOverdueHelper.getOverdueMessage(item.daysSinceRelease, state.funOverdueMessages))
                        }

                        val warningColor = when {
                            item.daysSinceRelease == item.expectedIntervalDays.toLong() - 1 -> MaterialTheme.colorScheme.secondary
                            item.daysSinceRelease == item.expectedIntervalDays.toLong() -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.error
                        }

                        HistoryItem(
                            modifier = Modifier
                                .fillMaxWidth()
                                .animateItem(),
                            history = HistoryWithRelations(
                                id = 0L,
                                mangaId = item.trackingManga.id,
                                chapterId = item.latestChapter.id,
                                title = item.group?.name ?: item.trackingManga.title,
                                chapterNumber = item.latestChapter.chapterNumber,
                                readAt = java.util.Date(item.latestChapter.dateUpload),
                                readDuration = 0L,
                                coverData = MangaCover(
                                    mangaId = item.trackingManga.id,
                                    sourceId = item.trackingManga.source,
                                    isMangaFavorite = item.trackingManga.favorite,
                                    url = item.trackingManga.thumbnailUrl,
                                    lastModified = item.trackingManga.coverLastModified,
                                )
                            ),
                            secondaryText = "Ch. ${eu.kanade.presentation.util.formatChapterNumber(item.latestChapter.chapterNumber)} · $sourceName",
                            onClickCover = { onClickManga(item.trackingManga.id) },
                            onClickResume = { onClickManga(item.trackingManga.id) },
                            onClickDelete = null,
                            onOverflowClick = { showMenu = true },
                            overflowContent = {
                                DropdownMenu(
                                    expanded = showMenu,
                                    onDismissRequest = { showMenu = false },
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("Pause tracking") },
                                        onClick = {
                                            onPauseTracking(item.trackingManga.id)
                                            showMenu = false
                                        },
                                    )
                                }
                            },
                            onClickFavorite = { /* unused */ },
                            onLongClick = { /* unused */ },
                            selectionMode = false,
                            selected = false,
                            subtitleBadge = {
                                androidx.compose.foundation.layout.Column {
                                    Text(
                                        text = "${item.daysSinceRelease} days since latest release",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Text(
                                        text = warningText,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = warningColor,
                                    )

                                    if (item.backgroundRefreshEnabled) {
                                        val eligibility = UpdateWatchRefreshHelper.getEligibility(
                                            enabled = item.backgroundRefreshEnabled,
                                            expectedIntervalDays = item.expectedIntervalDays,
                                            refreshProfile = item.refreshProfile,
                                            latestChapterUploadDate = item.latestChapter.dateUpload,
                                            lastCheckAt = item.lastBackgroundCheckAt,
                                            now = System.currentTimeMillis()
                                        )

                                        val nextTimeText = when {
                                            eligibility.status == UpdateWatchRefreshHelper.RefreshStatus.ACTIVE -> {
                                                if (eligibility.isDue) {
                                                    if (item.isQueued) {
                                                        "Refresh queued · ${eligibility.bucket}"
                                                    } else {
                                                        "Recovery pending · ${eligibility.bucket}"
                                                    }
                                                } else {
                                                    val time = java.time.Instant.ofEpochMilli(eligibility.nextEligibleAt ?: 0L)
                                                        .atZone(java.time.ZoneId.systemDefault())
                                                        .format(java.time.format.DateTimeFormatter.ofLocalizedTime(java.time.format.FormatStyle.SHORT))
                                                    "Next check around $time · ${eligibility.bucket}"
                                                }
                                            }
                                            eligibility.status == UpdateWatchRefreshHelper.RefreshStatus.WAITING -> {
                                                val date = java.time.Instant.ofEpochMilli(eligibility.nextEligibleAt ?: 0L)
                                                    .atZone(java.time.ZoneId.systemDefault())
                                                    .format(java.time.format.DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.SHORT))
                                                "Auto refresh starts $date · ${eligibility.bucket}"
                                            }
                                            else -> null
                                        }

                                        if (nextTimeText != null) {
                                            Text(
                                                text = nextTimeText,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.primary,
                                            )
                                        }
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

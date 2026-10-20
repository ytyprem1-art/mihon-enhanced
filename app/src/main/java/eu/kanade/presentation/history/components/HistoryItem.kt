package eu.kanade.presentation.history.components

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.source.mod.MERGED_SOURCE_ID
import eu.kanade.presentation.manga.components.DotSeparatorText
import eu.kanade.presentation.manga.components.MangaCover
import eu.kanade.presentation.theme.TachiyomiPreviewTheme
import eu.kanade.presentation.util.formatChapterNumber
import eu.kanade.tachiyomi.util.lang.toTimestampString
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Delete
import mihon.icons.materialsymbols.rounded.Favorite
import tachiyomi.domain.history.model.HistoryWithRelations
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.theme.active

private val HistoryItemHeight = 96.dp

@Composable
fun HistoryItem(
    history: HistoryWithRelations,
    onClickCover: () -> Unit,
    onClickResume: () -> Unit,
    onClickDelete: (() -> Unit)?,
    onClickFavorite: (() -> Unit)?,
    onLongClick: () -> Unit,
    selectionMode: Boolean,
    selected: Boolean,
    modifier: Modifier = Modifier,
    subtitleBadge: @Composable (() -> Unit)? = null,
    onOverflowClick: (() -> Unit)? = null,
    overflowContent: (@Composable () -> Unit)? = null,
    sourceName: String? = null,
    sourceColor: Color? = null,
    secondaryText: String? = null,
    titleOverride: String? = null,
) {
    Surface(
        modifier = modifier
            .combinedClickable(
                onClick = if (selectionMode) onLongClick else onClickResume,
                onLongClick = onLongClick,
            ),
        color = if (selected) MaterialTheme.colorScheme.active.copy(alpha = 0.12f) else Color.Transparent,
    ) {
        Row(
            modifier = Modifier
                .heightIn(min = HistoryItemHeight)
                .padding(
                    horizontal = MaterialTheme.padding.medium,
                    vertical = MaterialTheme.padding.small,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selectionMode) {
                Checkbox(
                    checked = selected,
                    onCheckedChange = null,
                    modifier = Modifier.padding(end = MaterialTheme.padding.medium),
                )
            }

            MangaCover.Book(
                modifier = Modifier.height(HistoryItemHeight - 16.dp), // Fixed height to match normal rows
                data = history.coverData,
                onClick = onClickCover.takeIf { !selectionMode },
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(
                        start = MaterialTheme.padding.medium,
                        end = MaterialTheme.padding.small,
                    ),
            ) {
                val textStyle = MaterialTheme.typography.bodyMedium

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (history.coverData.sourceId == MERGED_SOURCE_ID) {
                        Icon(
                            imageVector = Icons.Outlined.Link,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.secondary,
                        )
                    }
                    Text(
                        text = titleOverride ?: history.title,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        style = textStyle,
                    )
                }

                val readAt = remember {
                    history.readAt?.toTimestampString() ?: ""
                }

                if (secondaryText != null) {
                    Text(
                        text = secondaryText,
                        modifier = Modifier.padding(top = 4.dp),
                        style = textStyle,
                    )
                } else {
                    Row(
                        modifier = Modifier.padding(top = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (history.chapterNumber > -1) {
                            Text(
                                text = "Ch. ${formatChapterNumber(history.chapterNumber)}",
                                style = textStyle,
                                maxLines = 1,
                            )
                            DotSeparatorText()
                        }
                        if (sourceName != null) {
                            Text(
                                text = sourceName,
                                style = textStyle,
                                color = sourceColor ?: LocalContentColor.current,
                                maxLines = 1,
                            )
                            DotSeparatorText()
                        }
                        Text(
                            text = readAt,
                            style = textStyle,
                            maxLines = 1,
                        )
                    }
                }

                if (subtitleBadge != null) {
                    Box(modifier = Modifier.padding(top = 4.dp)) {
                        subtitleBadge()
                    }
                }
            }

            if (!selectionMode) {
                if (onClickFavorite != null && !history.coverData.isMangaFavorite) {
                    IconButton(
                        onClick = onClickFavorite,
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.FavoriteBorder,
                            contentDescription = stringResource(MR.strings.add_to_library),
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }

                if (onOverflowClick != null && overflowContent != null) {
                    Box {
                        IconButton(onClick = onOverflowClick) {
                            Icon(
                                imageVector = Icons.Outlined.MoreVert,
                                contentDescription = stringResource(MR.strings.action_menu_overflow_description),
                                tint = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                        overflowContent()
                    }
                }

                if (onClickDelete != null) {
                    IconButton(
                        onClick = onClickDelete,
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Delete,
                            contentDescription = stringResource(MR.strings.action_delete),
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }
    }
}

@PreviewLightDark
@Composable
private fun HistoryItemPreviews(
    @PreviewParameter(HistoryWithRelationsProvider::class)
    historyWithRelations: HistoryWithRelations,
) {
    TachiyomiPreviewTheme {
        Surface {
            HistoryItem(
                history = historyWithRelations,
                onClickCover = {},
                onClickResume = {},
                onClickDelete = {},
                onClickFavorite = {},
                onLongClick = {},
                selectionMode = false,
                selected = false,
            )
        }
    }
}

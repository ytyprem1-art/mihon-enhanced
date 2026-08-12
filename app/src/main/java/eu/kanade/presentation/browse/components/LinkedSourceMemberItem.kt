package eu.kanade.presentation.browse.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForwardIos
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.manga.components.MangaCover
import eu.kanade.presentation.util.isTabletUi
import eu.kanade.presentation.util.relativeTimeSpanString
import eu.kanade.tachiyomi.ui.manga.LinkedMember
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.util.secondaryItemAlpha
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

private val MemberItemHeight = 104.dp

@Composable
fun LinkedSourceMemberItem(
    member: LinkedMember,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    onDelete: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val manga = member.manga
    val sourceManager: SourceManager = remember { Injekt.get() }
    val sourceName = remember(manga.source) {
        sourceManager.getOrStub(manga.source).name
    }
    val isTablet = isTabletUi()

    Surface(
        modifier = modifier.clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(MemberItemHeight)
                .padding(
                    horizontal = MaterialTheme.padding.medium,
                    vertical = MaterialTheme.padding.small,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MangaCover.Book(
                modifier = Modifier.height(MemberItemHeight - MaterialTheme.padding.small * 2),
                data = manga,
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = MaterialTheme.padding.medium),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = manga.title,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = if (isTablet) 1 else 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium,
                )

                Text(
                    text = sourceName,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.secondaryItemAlpha(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                if (isTablet) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (member.isTracking) {
                            TrackingBadge(isPaused = member.isPaused)
                        }
                    }

                    Row(
                        modifier = Modifier.padding(top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        StatusInfo(member = member)

                        val lastCheckTimestamp = manga.lastModifiedAt * 1000
                        if (lastCheckTimestamp > 0L) {
                            Text(
                                text = "Checked: ${relativeTimeSpanString(lastCheckTimestamp)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                } else {
                    // Phone layout: compact rows
                    Row(
                        modifier = Modifier.padding(top = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        StatusInfo(member = member)
                    }

                    if (member.isTracking) {
                        Box(modifier = Modifier.padding(top = 2.dp)) {
                            TrackingBadge(isPaused = member.isPaused)
                        }
                    }
                }
            }

            // Actions
            if (isTablet) {
                TabletActions(
                    isRefreshing = isRefreshing,
                    onRefresh = onRefresh,
                    onDelete = onDelete
                )
            } else {
                PhoneActions(
                    isRefreshing = isRefreshing,
                    onRefresh = onRefresh,
                    onDelete = onDelete
                )
            }
        }
    }
}

@Composable
private fun TrackingBadge(isPaused: Boolean) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = MaterialTheme.shapes.extraSmall,
    ) {
        Text(
            text = if (isPaused) "Tracking (Paused)" else "Tracking",
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            maxLines = 1,
        )
    }
}

@Composable
private fun StatusInfo(member: LinkedMember) {
    if (member.lastRead != null) {
        Text(
            text = "Read: ${decimalFormat.format(member.lastRead)}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
    if (member.latestChapter != null) {
        Text(
            text = "Latest: ${decimalFormat.format(member.latestChapter)}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
        )
    }
}

@Composable
private fun TabletActions(
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    onDelete: () -> Unit,
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

    Row(verticalAlignment = Alignment.CenterVertically) {
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

        IconButton(onClick = onDelete) {
            Icon(
                imageVector = Icons.Outlined.Delete,
                contentDescription = "Delete",
                tint = MaterialTheme.colorScheme.error,
            )
        }

        Icon(
            imageVector = Icons.AutoMirrored.Outlined.ArrowForwardIos,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PhoneActions(
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    onDelete: () -> Unit,
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

    var showMenu by remember { mutableStateOf(false) }

    Column(
        verticalArrangement = Arrangement.SpaceBetween,
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxHeight()
    ) {
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

        Box {
            IconButton(onClick = { showMenu = true }) {
                Icon(
                    imageVector = Icons.Outlined.MoreVert,
                    contentDescription = "More",
                )
            }

            DropdownMenu(
                expanded = showMenu,
                onDismissRequest = { showMenu = false },
            ) {
                DropdownMenuItem(
                    text = { Text("Delete") },
                    onClick = {
                        showMenu = false
                        onDelete()
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Outlined.Delete,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                )
            }
        }
    }
}

private val decimalFormat = DecimalFormat("#.###").apply {
    decimalFormatSymbols = DecimalFormatSymbols.getInstance(Locale.US)
}

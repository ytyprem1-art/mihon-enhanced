package eu.kanade.tachiyomi.ui.mod.updatewatch.components

import eu.kanade.tachiyomi.ui.mod.util.dateUploadMillis
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.AdaptiveSheet
import eu.kanade.presentation.manga.components.MangaCover
import eu.kanade.presentation.util.relativeTimeSpanString
import eu.kanade.tachiyomi.ui.mod.updatewatch.EnrichedUpdateWatchInboxItem
import tachiyomi.domain.history.model.UpdateWatchInboxItem
import tachiyomi.domain.manga.model.Manga
import tachiyomi.presentation.core.components.FastScrollLazyColumn
import tachiyomi.presentation.core.components.material.padding
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.ChronoUnit
import java.time.LocalDate

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.PermissionChecker
import kotlinx.coroutines.launch
import android.Manifest
import android.os.Build

@Composable
fun UpdateWatchInboxSheet(
    items: List<EnrichedUpdateWatchInboxItem>,
    notificationsEnabled: Boolean,
    onDismissRequest: () -> Unit,
    onClickItem: (Long) -> Unit,
    onDeleteItem: (Long) -> Unit,
    onDisableAutoRefresh: (Long) -> Unit,
    onToggleNotifications: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            onToggleNotifications(true)
            scope.launch {
                snackbarHostState.showSnackbar("Notifications enabled")
            }
        } else {
            scope.launch {
                snackbarHostState.showSnackbar("Notification permission denied")
            }
        }
    }

    AdaptiveSheet(
        onDismissRequest = onDismissRequest,
    ) {
        Column(
            modifier = Modifier
                .padding(vertical = MaterialTheme.padding.medium)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = MaterialTheme.padding.medium),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Tracked Updates Inbox",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f)
                )

                IconButton(
                    onClick = {
                        if (notificationsEnabled) {
                            onToggleNotifications(false)
                            scope.launch {
                                snackbarHostState.showSnackbar("Notifications disabled")
                            }
                        } else {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                when (PermissionChecker.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)) {
                                    PermissionChecker.PERMISSION_GRANTED -> {
                                        onToggleNotifications(true)
                                        scope.launch {
                                            snackbarHostState.showSnackbar("Notifications enabled")
                                        }
                                    }
                                    else -> {
                                        permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                    }
                                }
                            } else {
                                onToggleNotifications(true)
                                scope.launch {
                                    snackbarHostState.showSnackbar("Notifications enabled")
                                }
                            }
                        }
                    }
                ) {
                    Icon(
                        imageVector = if (notificationsEnabled) Icons.Outlined.NotificationsActive else Icons.Outlined.Notifications,
                        contentDescription = "Toggle Notifications",
                        tint = if (notificationsEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = MaterialTheme.padding.small))

            androidx.compose.material3.SnackbarHost(hostState = snackbarHostState)

            if (items.isEmpty()) {
                Text(
                    text = "No new updates found yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(MaterialTheme.padding.medium)
                )
            } else {
                FastScrollLazyColumn(
                    contentPadding = PaddingValues(bottom = MaterialTheme.padding.medium)
                ) {
                    items(
                        items = items,
                        key = { it.item.mangaId }
                    ) { item ->
                        UpdateWatchInboxRow(
                            enriched = item,
                            onClick = {
                                onDismissRequest()
                                onClickItem(item.item.mangaId)
                            },
                            onDelete = { onDeleteItem(item.item.mangaId) },
                            onDisableAutoRefresh = { onDisableAutoRefresh(item.item.mangaId) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun UpdateWatchInboxRow(
    enriched: EnrichedUpdateWatchInboxItem,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onDisableAutoRefresh: () -> Unit,
) {
    val item = enriched.item
    val isWarning = item.type == UpdateWatchInboxItem.TYPE_INACTIVITY_WARNING
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = MaterialTheme.padding.medium, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (enriched.manga != null) {
            MangaCover.Book(
                modifier = Modifier
                    .height(if (isWarning) 80.dp else 64.dp)
                    .padding(end = MaterialTheme.padding.medium),
                data = enriched.manga,
            )
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.mangaTitle,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            if (isWarning) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Outlined.ErrorOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(end = 4.dp).height(16.dp)
                    )
                    Text(
                        text = "No update found for ${item.milestone} days.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                Text(
                    text = "Auto refresh is still enabled.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Row(
                    modifier = Modifier.padding(top = 8.dp),
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)
                ) {
                    androidx.compose.material3.TextButton(
                        onClick = onDelete, // "Keep auto refresh" or "Dismiss warning"
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                        modifier = Modifier.height(32.dp),
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Text("Keep", style = MaterialTheme.typography.labelLarge)
                    }
                    androidx.compose.material3.OutlinedButton(
                        onClick = onDisableAutoRefresh,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                        modifier = Modifier.height(32.dp)
                    ) {
                        Text("Disable", style = MaterialTheme.typography.labelLarge)
                    }
                }
            } else {
                Text(
                    text = item.sourceName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                val chapterText = if (item.chapterCount > 1) "${item.chapterCount} new chapters" else "1 new chapter"
                Text(
                    text = chapterText,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )

                Text(
                    text = item.chapterRange,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                if (enriched.latestChapter != null) {
                    val releaseTimeText = formatReleaseTime(enriched.latestChapter.dateUploadMillis)
                    Text(
                        text = "Latest release: $releaseTimeText",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Text(
                    text = "Found ${relativeTimeSpanString(item.lastFoundAt)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (!isWarning) {
            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Outlined.Close,
                    contentDescription = "Dismiss",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun formatReleaseTime(timestamp: Long): String {
    if (timestamp <= 0L) return "Unknown"

    return remember(timestamp) {
        val instant = Instant.ofEpochMilli(timestamp)
        val zone = ZoneId.systemDefault()
        val dateTime = instant.atZone(zone)
        val today = LocalDate.now(zone)
        val date = dateTime.toLocalDate()

        val relativeDay = when {
            date == today -> "today"
            date == today.minusDays(1) -> "yesterday"
            else -> {
                val daysBetween = ChronoUnit.DAYS.between(date, today)
                if (daysBetween < 7) "$daysBetween days ago" else {
                    dateTime.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
                }
            }
        }

        // Check if time is reliable (not exactly midnight which often means date-only)
        val hasReliableTime = dateTime.hour != 0 || dateTime.minute != 0 || dateTime.second != 0

        if (hasReliableTime) {
            val timePart = dateTime.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))
            "$relativeDay at $timePart"
        } else {
            relativeDay
        }
    }
}

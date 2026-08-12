package eu.kanade.tachiyomi.ui.mod.updatewatch

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.icerock.moko.resources.StringResource
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.presentation.components.SearchToolbar
import eu.kanade.presentation.history.components.HistoryItem
import eu.kanade.presentation.util.Screen
import eu.kanade.presentation.util.relativeTimeSpanString
import eu.kanade.tachiyomi.ui.mod.updatewatch.helper.UpdateWatchRefreshHelper
import eu.kanade.tachiyomi.ui.mod.updatewatch.worker.UpdateWatchDiagnosticsManager
import eu.kanade.tachiyomi.ui.mod.updatewatch.worker.UpdateWatchRefreshScheduler
import eu.kanade.tachiyomi.ui.mod.updatewatch.worker.UpdateWatchRefreshWorker
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import androidx.compose.material.icons.automirrored.outlined.ArrowForwardIos
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Face
import eu.kanade.tachiyomi.ui.mod.updatewatch.components.UpdateWatchHelpSheet
import eu.kanade.tachiyomi.ui.mod.updatewatch.components.TrackedMangaHelpSheet
import eu.kanade.tachiyomi.ui.mod.updatewatch.components.AutoRefreshInactivityWarningDialog
import kotlinx.coroutines.launch
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Error
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.domain.history.model.HistoryWithRelations
import tachiyomi.domain.history.model.UpdateWatch
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.model.MangaCover
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.FastScrollLazyColumn
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.LoadingScreen
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

import androidx.compose.material.icons.outlined.SettingsBackupRestore
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material3.HorizontalDivider
import tachiyomi.domain.history.model.UpdateWatchHistory
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions

class UpdateWatchManagerScreen : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val viewModel = viewModel<UpdateWatchManagerViewModel>()
        val state by viewModel.state.collectAsStateWithLifecycle()

        val scrollState = rememberLazyListState()

        var editItem by remember { mutableStateOf<UpdateWatchUiModel.Item?>(null) }
        var showHelpSheet by remember { mutableStateOf(false) }
        var showTrackedHelpSheet by remember { mutableStateOf(false) }

        LaunchedEffect(viewModel.sortMode) {
            scrollState.scrollToItem(0)
        }

        Scaffold(
            topBar = { scrollBehavior ->
                SearchToolbar(
                    searchQuery = state.searchQuery,
                    onChangeSearchQuery = viewModel::updateSearchQuery,
                    titleContent = { Text("Tracked manga") },
                    navigateUp = navigator::pop,
                    actions = {
                        val scope = rememberCoroutineScope()
                        val context = LocalContext.current
                        if (eu.kanade.tachiyomi.BuildConfig.DEBUG) {
                            var showDebugMenu by remember { mutableStateOf(false) }
                            Box {
                                IconButton(onClick = { showDebugMenu = true }) {
                                    Icon(
                                        imageVector = Icons.Default.MoreVert,
                                        contentDescription = "Debug",
                                    )
                                }
                                DropdownMenu(
                                    expanded = showDebugMenu,
                                    onDismissRequest = { showDebugMenu = false },
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("Run normally (dry run)") },
                                        onClick = {
                                            UpdateWatchRefreshScheduler.runNow(context, UpdateWatchRefreshWorker.SIM_NONE)
                                            showDebugMenu = false
                                            scope.launch {
                                                viewModel.snackbarHostState.showSnackbar("Background refresh dry run started")
                                            }
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Simulate HTTP 429") },
                                        onClick = {
                                            UpdateWatchRefreshScheduler.runNow(context, UpdateWatchRefreshWorker.SIM_HTTP_429)
                                            showDebugMenu = false
                                            scope.launch {
                                                viewModel.snackbarHostState.showSnackbar("Simulating HTTP 429")
                                            }
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Simulate HTTP 403 / Cloudflare") },
                                        onClick = {
                                            UpdateWatchRefreshScheduler.runNow(context, UpdateWatchRefreshWorker.SIM_HTTP_403)
                                            showDebugMenu = false
                                            scope.launch {
                                                viewModel.snackbarHostState.showSnackbar("Simulating HTTP 403")
                                            }
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Simulate Transient Failure") },
                                        onClick = {
                                            UpdateWatchRefreshScheduler.runNow(context, UpdateWatchRefreshWorker.SIM_TRANSIENT)
                                            showDebugMenu = false
                                            scope.launch {
                                                viewModel.snackbarHostState.showSnackbar("Simulating Transient Failure")
                                            }
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Simulate Ordinary Failure") },
                                        onClick = {
                                            UpdateWatchRefreshScheduler.runNow(context, UpdateWatchRefreshWorker.SIM_ORDINARY)
                                            showDebugMenu = false
                                            scope.launch {
                                                viewModel.snackbarHostState.showSnackbar("Simulating Ordinary Failure")
                                            }
                                        },
                                    )
                                }
                            }
                        }

                        IconButton(onClick = { showHelpSheet = true }) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Outlined.HelpOutline,
                                contentDescription = "How it works",
                            )
                        }

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
                                    LibraryPreferences.TrackedMangaSort.NewestRelease to "Newest release",
                                    LibraryPreferences.TrackedMangaSort.OldestRelease to "Oldest release",
                                    LibraryPreferences.TrackedMangaSort.TitleAZ to MR.strings.action_sort_alpha,
                                    LibraryPreferences.TrackedMangaSort.TitleZA to "Title Z–A",
                                ).forEach { (mode, titleRes) ->
                                    DropdownMenuItem(
                                        text = {
                                            val title = when (titleRes) {
                                                is StringResource -> stringResource(titleRes)
                                                is String -> titleRes
                                                else -> ""
                                            }
                                            Text(title)
                                        },
                                        onClick = {
                                            viewModel.setSortMode(mode)
                                            showSortMenu = false
                                        },
                                        trailingIcon = {
                                            RadioButton(
                                                selected = viewModel.sortMode == mode,
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
            snackbarHost = { SnackbarHost(hostState = viewModel.snackbarHostState) },
        ) { contentPadding ->
            UpdateWatchManagerContent(
                state = state,
                contentPadding = contentPadding,
                scrollState = scrollState,
                onClickManga = { navigator.push(MangaScreen(it)) },
                onUntrack = viewModel::untrack,
                onEditBackgroundRefresh = { editItem = it },
                onShowHelp = { showHelpSheet = true },
                onShowTrackedHelp = { showTrackedHelpSheet = true },
                onShowDiagnostics = { navigator.push(UpdateWatchDiagnosticsScreen()) },
                viewModel = viewModel,
            )

            if (editItem != null) {
                BackgroundRefreshEditDialog(
                    item = editItem!!,
                    onDismissRequest = { editItem = null },
                    onSave = { enabled, interval, profile ->
                        viewModel.updateBackgroundRefresh(editItem!!.trackingManga.id, enabled, interval, profile)
                        editItem = null
                    },
                    onShowHelp = { showHelpSheet = true },
                    onShowTrackedHelp = { showTrackedHelpSheet = true }
                )
            }

            if (showHelpSheet) {
                UpdateWatchHelpSheet(
                    onDismissRequest = { showHelpSheet = false }
                )
            }

            if (showTrackedHelpSheet) {
                TrackedMangaHelpSheet(
                    onDismissRequest = { showTrackedHelpSheet = false }
                )
            }
        }
    }
}

@Composable
private fun UpdateWatchManagerContent(
    state: UpdateWatchManagerViewModel.State,
    contentPadding: PaddingValues,
    scrollState: androidx.compose.foundation.lazy.LazyListState,
    onClickManga: (Long) -> Unit,
    onUntrack: (Long) -> Unit,
    onEditBackgroundRefresh: (UpdateWatchUiModel.Item) -> Unit,
    onShowHelp: () -> Unit,
    onShowTrackedHelp: () -> Unit,
    onShowDiagnostics: () -> Unit,
    viewModel: UpdateWatchManagerViewModel,
) {
    val items = state.items
    if (items == null) {
        LoadingScreen(Modifier.padding(contentPadding))
    } else if (items.isEmpty()) {
        val msg = if (!state.searchQuery.isNullOrBlank()) {
            MR.strings.no_results_found
        } else {
            null
        }
        EmptyScreen(
            message = if (msg != null) stringResource(msg) else "No manga are currently tracked for updates.",
            modifier = Modifier.padding(contentPadding),
        )
    } else {
        FastScrollLazyColumn(
            contentPadding = contentPadding,
            state = scrollState,
        ) {
            item(key = "help-header") {
                Column {
                    ListItem(
                        modifier = Modifier.clickable(onClick = onShowTrackedHelp),
                        headlineContent = { Text("How Tracking works") },
                        leadingContent = {
                            Icon(
                                imageVector = Icons.AutoMirrored.Outlined.HelpOutline,
                                contentDescription = null,
                            )
                        },
                        trailingContent = {
                            Icon(
                                imageVector = Icons.AutoMirrored.Outlined.ArrowForwardIos,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    )
                    ListItem(
                        modifier = Modifier.clickable(onClick = onShowHelp),
                        headlineContent = { Text("How Auto Refresh works") },
                        leadingContent = {
                            Icon(
                                imageVector = Icons.AutoMirrored.Outlined.HelpOutline,
                                contentDescription = null,
                            )
                        },
                        trailingContent = {
                            Icon(
                                imageVector = Icons.AutoMirrored.Outlined.ArrowForwardIos,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    )
                    ListItem(
                        modifier = Modifier.clickable(onClick = onShowDiagnostics),
                        headlineContent = { Text("Scheduler diagnostics") },
                        leadingContent = {
                            Icon(
                                imageVector = Icons.Outlined.SettingsBackupRestore,
                                contentDescription = null,
                            )
                        },
                        trailingContent = {
                            Icon(
                                imageVector = Icons.AutoMirrored.Outlined.ArrowForwardIos,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    )
                    ListItem(
                        headlineContent = { Text(stringResource(MR.strings.pref_fun_overdue_messages)) },
                        supportingContent = { Text(stringResource(MR.strings.pref_fun_overdue_messages_summary)) },
                        leadingContent = {
                            Icon(imageVector = Icons.Outlined.Face, contentDescription = null)
                        },
                        trailingContent = {
                            Switch(
                                checked = viewModel.funOverdueMessages,
                                onCheckedChange = { viewModel.toggleFunOverdueMessages(it) }
                            )
                        }
                    )
                }
            }

            items(
                items = items,
                key = { "tracked-${it.trackingManga.id}" }
            ) { item ->
                val sourceManager = remember { Injekt.get<SourceManager>() }
                val sourceName = remember(item.trackingManga.source) {
                    sourceManager.getOrStub(item.trackingManga.source).name
                }

                var showMenu by remember { mutableStateOf(false) }
                var showDebugMilestoneMenu by remember { mutableStateOf(false) }
                var expanded by remember { mutableStateOf(false) }

                Column(modifier = Modifier.fillMaxWidth()) {
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
                            readAt = if (item.latestChapter.dateUpload > 0) java.util.Date(item.latestChapter.dateUpload) else null,
                            readDuration = 0L,
                            coverData = MangaCover(
                                mangaId = item.trackingManga.id,
                                sourceId = item.trackingManga.source,
                                isMangaFavorite = item.trackingManga.favorite,
                                url = item.trackingManga.thumbnailUrl,
                                lastModified = item.trackingManga.coverLastModified,
                            )
                        ),
                        secondaryText = run {
                            val ch = if (item.latestChapter.chapterNumber >= 0) {
                                "Ch. ${eu.kanade.presentation.util.formatChapterNumber(item.latestChapter.chapterNumber)}"
                            } else {
                                "No chapters"
                            }
                            "$ch · $sourceName"
                        },
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
                                    text = { Text("Edit background refresh") },
                                    onClick = {
                                        onEditBackgroundRefresh(item)
                                        showMenu = false
                                    },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Outlined.SettingsBackupRestore,
                                            contentDescription = null
                                        )
                                    }
                                )

                                DropdownMenuItem(
                                    text = { Text("Untrack") },
                                    onClick = {
                                        onUntrack(item.trackingManga.id)
                                        showMenu = false
                                    },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Outlined.Delete,
                                            contentDescription = null
                                        )
                                    }
                                )

                                if (eu.kanade.tachiyomi.BuildConfig.DEBUG) {
                                    HorizontalDivider()
                                    DropdownMenuItem(
                                        text = { Text("Debug: Sim inactivity") },
                                        onClick = {
                                            showDebugMilestoneMenu = true
                                            showMenu = false
                                        },
                                        leadingIcon = {
                                            Icon(
                                                imageVector = Icons.Default.MoreVert,
                                                contentDescription = null
                                            )
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Debug: Refresh now (real)") },
                                        onClick = {
                                            viewModel.simulateRealRefresh(item)
                                            showMenu = false
                                        },
                                        leadingIcon = {
                                            Icon(
                                                imageVector = Icons.Default.Check,
                                                contentDescription = null
                                            )
                                        }
                                    )
                                }
                            }

                            if (eu.kanade.tachiyomi.BuildConfig.DEBUG) {
                                DropdownMenu(
                                    expanded = showDebugMilestoneMenu,
                                    onDismissRequest = { showDebugMilestoneMenu = false },
                                ) {
                                    listOf(28, 56, 84).forEach { milestone ->
                                        DropdownMenuItem(
                                            text = { Text("$milestone days") },
                                            onClick = {
                                                viewModel.simulateInactivityWarning(item, milestone)
                                                showDebugMilestoneMenu = false
                                            }
                                        )
                                    }
                                }
                            }
                        },
                        onClickFavorite = { /* unused */ },
                        onLongClick = { /* unused */ },
                        selectionMode = false,
                        selected = false,
                        subtitleBadge = {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    val eligibility = UpdateWatchRefreshHelper.getEligibility(
                                        enabled = item.backgroundRefreshEnabled,
                                        expectedIntervalDays = item.expectedIntervalDays,
                                        refreshProfile = item.refreshProfile,
                                        latestChapterUploadDate = item.latestChapter.dateUpload,
                                        lastCheckAt = item.lastBackgroundCheckAt,
                                        now = System.currentTimeMillis()
                                    )

                                    val latestHistory = item.refreshHistory.firstOrNull()

                                    // Row 1: Release Age / Pattern
                                    val ageText = if (item.daysSinceRelease >= 0) {
                                        "${item.daysSinceRelease} days since latest release · about every ${item.expectedIntervalDays} days"
                                    } else {
                                        "Unknown release date"
                                    }
                                    Text(
                                        text = ageText,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )

                                    if (item.backgroundRefreshEnabled) {
                                        // Row 2: Auto Refresh Status
                                        Text(
                                            text = "Auto refresh: ${eligibility.bucket}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = if (eligibility.status == UpdateWatchRefreshHelper.RefreshStatus.ACTIVE)
                                                MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                        )

                                        // Row 3: Last Refresh Result
                                        val lastResultText = if (latestHistory != null) {
                                            val status = if (latestHistory.success) "Succeeded" else "Failed"
                                            val reason = if (latestHistory.success) {
                                                if (latestHistory.newChapters > 0) "${latestHistory.newChapters} new chapters" else "No new chapters"
                                            } else {
                                                getHumanReadableFailure(latestHistory.category, latestHistory.detail)
                                            }
                                            "Last result: $status · $reason"
                                        } else {
                                            "No refresh attempts yet"
                                        }
                                        Text(
                                            text = lastResultText,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            fontWeight = if (latestHistory?.success == false) FontWeight.Bold else FontWeight.Normal
                                        )

                                        // Row 4: Times
                                        val lastCheckText = item.lastBackgroundCheckAt?.let {
                                            if (it > 0) {
                                                val time = UpdateWatchDiagnosticsManager.formatTimestamp(it)
                                                "Last checked: $time"
                                            } else {
                                                "Not checked yet"
                                            }
                                        } ?: "Never checked"

                                        val nextCheckText = when {
                                            eligibility.status == UpdateWatchRefreshHelper.RefreshStatus.ACTIVE -> {
                                                if (eligibility.isDue) {
                                                    if (item.isQueued) {
                                                        "Refresh queued · ${eligibility.bucket}"
                                                    } else {
                                                        "Recovery pending · ${eligibility.bucket}"
                                                    }
                                                } else {
                                                    "Next check: ${UpdateWatchDiagnosticsManager.formatTimestamp(eligibility.nextEligibleAt)}"
                                                }
                                            }
                                            eligibility.status == UpdateWatchRefreshHelper.RefreshStatus.WAITING -> {
                                                "Next check: Starts ${UpdateWatchDiagnosticsManager.formatTimestamp(eligibility.nextEligibleAt)}"
                                            }
                                            else -> ""
                                        }

                                        Text(
                                            text = lastCheckText,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        if (nextCheckText.isNotEmpty()) {
                                            Text(
                                                text = nextCheckText,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                }

                                if (item.backgroundRefreshEnabled) {
                                    IconButton(onClick = { expanded = !expanded }) {
                                        Icon(
                                            imageVector = if (expanded) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown,
                                            contentDescription = "Show history",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    )

                    if (expanded) {
                        RefreshHistoryList(item.refreshHistory)
                    }
                }
            }
        }
    }
}

@Composable
private fun RefreshHistoryList(history: List<UpdateWatchHistory>) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(horizontal = MaterialTheme.padding.medium, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = "Recent Attempts (latest 5)",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 4.dp)
            )

            if (history.isEmpty()) {
                Text(
                    text = "No refresh attempts yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                history.forEach { attempt ->
                    RefreshHistoryRow(attempt)
                }
            }
        }
    }
}

@Composable
private fun RefreshHistoryRow(attempt: UpdateWatchHistory) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val timeText = remember(attempt.timestamp) {
            val dateTime = Instant.ofEpochMilli(attempt.timestamp).atZone(ZoneId.systemDefault())
            dateTime.format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT, FormatStyle.SHORT))
        }

        Text(
            text = timeText,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.width(120.dp)
        )

        val statusText = if (attempt.success) {
            if (attempt.newChapters > 0) "${attempt.newChapters} new" else "No updates"
        } else {
            getHumanReadableFailure(attempt.category, attempt.detail)
        }

        Text(
            text = statusText,
            style = MaterialTheme.typography.bodySmall,
            color = if (attempt.success) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        Icon(
            imageVector = if (attempt.success) {
                androidx.compose.material.icons.Icons.Default.Check
            } else {
                androidx.compose.material.icons.Icons.Default.Error
            },
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = if (attempt.success) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
        )
    }
}

private fun getHumanReadableFailure(category: UpdateWatchHistory.FailureCategory, detail: String?): String {
    return when (category) {
        UpdateWatchHistory.FailureCategory.RATE_LIMITED -> "Rate limited"
        UpdateWatchHistory.FailureCategory.ACCESS_BLOCKED_OR_CLOUDFLARE -> "Cloudflare / blocked"
        UpdateWatchHistory.FailureCategory.TRANSIENT_NETWORK -> "Network timeout"
        UpdateWatchHistory.FailureCategory.SOURCE_NOT_INSTALLED -> "Source not installed"
        UpdateWatchHistory.FailureCategory.SOURCE_OR_PARSING_ERROR -> "Source error"
        UpdateWatchHistory.FailureCategory.UNKNOWN -> detail ?: "Unknown error"
        UpdateWatchHistory.FailureCategory.NONE -> "Unknown"
    }
}

@Composable
private fun BackgroundRefreshEditDialog(
    item: UpdateWatchUiModel.Item,
    onDismissRequest: () -> Unit,
    onSave: (Boolean, Int, UpdateWatch.RefreshProfile) -> Unit,
    onShowHelp: () -> Unit,
    onShowTrackedHelp: () -> Unit,
) {
    var enabled by remember { mutableStateOf(item.backgroundRefreshEnabled) }
    var interval by remember { mutableIntStateOf(item.expectedIntervalDays) }
    var profile by remember { mutableStateOf(item.refreshProfile) }
    var customInterval by remember { mutableStateOf(if (interval !in listOf(7, 14, 30)) interval.toString() else "") }

    var showInactivityWarning by remember { mutableStateOf(false) }

    if (showInactivityWarning) {
        AutoRefreshInactivityWarningDialog(
            days = item.daysSinceRelease,
            onConfirm = {
                onSave(enabled, interval, profile)
                showInactivityWarning = false
            },
            onDismissRequest = {
                showInactivityWarning = false
            }
        )
    }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text("Background Refresh Settings") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onShowTrackedHelp)
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.HelpOutline,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = "How Tracking works",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onShowHelp)
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.HelpOutline,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = "How Auto Refresh works",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }

                Text(
                    text = "Expected update interval (days)",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 8.dp)
                )

                listOf(7, 14, 30).forEach { preset ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                interval = preset
                                customInterval = ""
                                if (enabled) {
                                    profile = UpdateWatch.RefreshProfile.fromInterval(preset)
                                }
                            },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = interval == preset && customInterval.isEmpty(),
                            onClick = null
                        )
                        Text(
                            text = "$preset days",
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = customInterval.isNotEmpty() || (interval !in listOf(7, 14, 30)),
                        onClick = { if (customInterval.isEmpty()) customInterval = interval.toString() }
                    )
                    OutlinedTextField(
                        value = customInterval,
                        onValueChange = {
                            customInterval = it.filter { c -> c.isDigit() }
                            val newVal = customInterval.toIntOrNull()
                            if (newVal != null && newVal > 0) {
                                interval = newVal
                                if (enabled) {
                                    profile = UpdateWatch.RefreshProfile.fromInterval(newVal)
                                }
                            }
                        },
                        label = { Text("Custom days") },
                        modifier = Modifier.padding(start = 8.dp).weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                    )
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("Auto Refresh (background checks)")
                    Switch(checked = enabled, onCheckedChange = { enabled = it })
                }

                if (enabled) {
                    Text(
                        text = "Refresh Profile",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 8.dp)
                    )

                    UpdateWatch.RefreshProfile.entries.forEach { p ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { profile = p },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = profile == p,
                                onClick = null
                            )
                            Text(
                                text = when (p) {
                                    UpdateWatch.RefreshProfile.WEEKLY_STABLE -> "Weekly stable"
                                    UpdateWatch.RefreshProfile.SLOW_PERIODIC -> "Slow periodic"
                                    UpdateWatch.RefreshProfile.RAPID_IRREGULAR -> "Rapid / irregular"
                                },
                                modifier = Modifier.padding(start = 8.dp)
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = {
                if (enabled && !item.backgroundRefreshEnabled && item.daysSinceRelease >= 60) {
                    showInactivityWarning = true
                } else {
                    onSave(enabled, interval, profile)
                }
            }) {
                Text(stringResource(MR.strings.action_ok))
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismissRequest) {
                Text(stringResource(MR.strings.action_cancel))
            }
        }
    )
}

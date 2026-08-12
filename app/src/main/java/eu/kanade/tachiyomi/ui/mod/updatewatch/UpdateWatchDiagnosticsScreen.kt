package eu.kanade.tachiyomi.ui.mod.updatewatch

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.ui.mod.updatewatch.worker.MangaDiagnosticDetail
import eu.kanade.tachiyomi.ui.mod.updatewatch.worker.UpdateWatchDiagnosticsManager
import eu.kanade.tachiyomi.ui.mod.updatewatch.worker.UpdateWatchSchedulerDiagnostic
import eu.kanade.tachiyomi.util.system.copyToClipboard
import kotlinx.coroutines.flow.update
import mihon.core.viewmodel.StateViewModel
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen

class UpdateWatchDiagnosticsScreen : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val viewModel = viewModel<UpdateWatchDiagnosticsViewModel>()
        val state by viewModel.state.collectAsStateWithLifecycle()
        val context = LocalContext.current

        var showDeleteAllDialog by remember { mutableStateOf(false) }
        var showDeleteSelectedDialog by remember { mutableStateOf(false) }

        if (state.selection.isNotEmpty()) {
            BackHandler {
                viewModel.clearSelection()
            }
        }

        Scaffold(
            topBar = { scrollBehavior ->
                AppBar(
                    title = "Scheduler diagnostics",
                    navigateUp = navigator::pop,
                    actions = {
                        IconButton(onClick = {
                            val text = state.diagnostics.joinToString("\n\n---\n\n") { diag ->
                                buildString {
                                    val title = when (diag.type) {
                                        UpdateWatchSchedulerDiagnostic.RunType.WORKER_RUN -> "WORKER RUN"
                                        UpdateWatchSchedulerDiagnostic.RunType.SCHEDULER_EVENT -> "SCHEDULER EVENT"
                                        UpdateWatchSchedulerDiagnostic.RunType.FOREGROUND_HOT_BURST -> "FOREGROUND HOT BURST"
                                    }
                                    appendLine("$title [${UpdateWatchDiagnosticsManager.formatTimestamp(diag.timestamp)}]")
                                    if (diag.eventName != null) appendLine("Event: ${diag.eventName}")
                                    if (diag.type == UpdateWatchSchedulerDiagnostic.RunType.WORKER_RUN) {
                                        appendLine("Scheduled: ${UpdateWatchDiagnosticsManager.formatTimestamp(diag.scheduledAt)}")
                                        appendLine("Started: ${UpdateWatchDiagnosticsManager.formatTimestamp(diag.startedAt)}")
                                        appendLine("Completed: ${UpdateWatchDiagnosticsManager.formatTimestamp(diag.completedAt)}")
                                        appendLine("Eligible: ${diag.eligibleCount} | Selected: ${diag.selectedCount}")
                                        appendLine("Updates: ${diag.updatedCount} | No update: ${diag.noUpdateCount} | Failed: ${diag.failedCount}")
                                        appendLine("Sources: ${diag.sourceCount}")

                                        if (diag.nextWorkerTargetAt != null) {
                                            appendLine("Final scheduled target: ${UpdateWatchDiagnosticsManager.formatTimestamp(diag.nextWorkerTargetAt)}")
                                        }
                                        if (diag.proposedTargetAt != null && diag.proposedTargetAt != diag.nextWorkerTargetAt) {
                                            appendLine("Proposed target: ${UpdateWatchDiagnosticsManager.formatTimestamp(diag.proposedTargetAt)} (+${diag.proposedMarginMinutes} min margin)")
                                        }

                                        if (diag.mangaDetails.isNotEmpty()) {
                                            appendLine("\nManga Details:")
                                            diag.mangaDetails.forEach { m ->
                                                appendLine("- [${UpdateWatchDiagnosticsManager.formatTimestamp(m.checkedAt)}] ${m.title} (${m.sourceName}): ${m.result}")
                                                if (m.errorReason != null) appendLine("  Error: ${m.errorReason}")
                                                appendLine("  Next eligible: ${UpdateWatchDiagnosticsManager.formatTimestamp(m.nextEligibleAt)}")
                                            }
                                        }
                                    } else {
                                        if (diag.wallClockBaseSlot != null) {
                                            appendLine("Base slot: ${UpdateWatchDiagnosticsManager.formatTimestamp(diag.wallClockBaseSlot)}")
                                        }
                                        if (diag.safetyMarginMinutes > 0) {
                                            appendLine("Margin: +${diag.safetyMarginMinutes} min")
                                        }
                                        if (diag.nextWorkerTargetAt != null) {
                                            appendLine("Final scheduled target: ${UpdateWatchDiagnosticsManager.formatTimestamp(diag.nextWorkerTargetAt)}")
                                        }
                                    }
                                }
                            }
                            context.copyToClipboard("Update Watch Diagnostics", text)
                        }) {
                            Icon(imageVector = Icons.Outlined.ContentCopy, contentDescription = "Copy all")
                        }
                        IconButton(onClick = { showDeleteAllDialog = true }) {
                            Icon(imageVector = Icons.Outlined.Delete, contentDescription = "Clear all")
                        }
                    },
                    actionModeCounter = state.selection.size,
                    onCancelActionMode = viewModel::clearSelection,
                    actionModeActions = {
                        IconButton(onClick = { showDeleteSelectedDialog = true }) {
                            Icon(imageVector = Icons.Outlined.Delete, contentDescription = "Delete selected")
                        }
                    },
                    scrollBehavior = scrollBehavior,
                )
            },
        ) { contentPadding ->
            if (state.diagnostics.isEmpty()) {
                EmptyScreen(
                    message = "No diagnostic records yet.",
                    modifier = Modifier.padding(contentPadding)
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = contentPadding
                ) {
                    items(state.diagnostics, key = { it.id }) { diagnostic ->
                        val isSelected = diagnostic.id in state.selection
                        DiagnosticItem(
                            diagnostic = diagnostic,
                            isSelected = isSelected,
                            isInSelectionMode = state.selection.isNotEmpty(),
                            onLongClick = { viewModel.toggleSelection(diagnostic.id) },
                            onClick = {
                                if (state.selection.isNotEmpty()) {
                                    viewModel.toggleSelection(diagnostic.id)
                                }
                            }
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    }
                }
            }

            if (showDeleteAllDialog) {
                AlertDialog(
                    onDismissRequest = { showDeleteAllDialog = false },
                    confirmButton = {
                        TextButton(onClick = {
                            viewModel.clear()
                            showDeleteAllDialog = false
                        }) {
                            Text(stringResource(MR.strings.action_ok))
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showDeleteAllDialog = false }) {
                            Text(stringResource(MR.strings.action_cancel))
                        }
                    },
                    title = { Text("Clear all diagnostics?") },
                    text = { Text("This will permanently delete all diagnostic logs.") }
                )
            }

            if (showDeleteSelectedDialog) {
                AlertDialog(
                    onDismissRequest = { showDeleteSelectedDialog = false },
                    confirmButton = {
                        TextButton(onClick = {
                            viewModel.deleteSelected()
                            showDeleteSelectedDialog = false
                        }) {
                            Text(stringResource(MR.strings.action_ok))
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showDeleteSelectedDialog = false }) {
                            Text(stringResource(MR.strings.action_cancel))
                        }
                    },
                    title = { Text("Delete selected diagnostics?") },
                    text = { Text("Delete ${state.selection.size} selected items?") }
                )
            }
        }
    }

    @Composable
    private fun DiagnosticItem(
        diagnostic: UpdateWatchSchedulerDiagnostic,
        isSelected: Boolean,
        isInSelectionMode: Boolean,
        onLongClick: () -> Unit,
        onClick: () -> Unit,
    ) {
        var expanded by remember { mutableStateOf(false) }

        Surface(
            color = if (isSelected) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface,
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = {
                        if (isInSelectionMode) {
                            onClick()
                        } else {
                            expanded = !expanded
                        }
                    },
                    onLongClick = onLongClick
                )
        ) {
            Column(
                modifier = Modifier
                    .padding(MaterialTheme.padding.medium)
            ) {
                val title = when (diagnostic.type) {
                    UpdateWatchSchedulerDiagnostic.RunType.WORKER_RUN -> "Worker run"
                    UpdateWatchSchedulerDiagnostic.RunType.SCHEDULER_EVENT -> "Scheduler event"
                    UpdateWatchSchedulerDiagnostic.RunType.FOREGROUND_HOT_BURST -> "Foreground HOT burst"
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "$title · ${UpdateWatchDiagnosticsManager.formatTimestamp(diagnostic.timestamp)}",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        if (diagnostic.type == UpdateWatchSchedulerDiagnostic.RunType.SCHEDULER_EVENT) {
                            val eventTitle = diagnostic.eventName?.substringBefore("\n") ?: "Unknown event"
                            Text(text = eventTitle, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    if (!isInSelectionMode) {
                        IconButton(onClick = { expanded = !expanded }) {
                            Icon(
                                imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                                contentDescription = null
                            )
                        }
                    }
                }

                if (diagnostic.type == UpdateWatchSchedulerDiagnostic.RunType.WORKER_RUN || diagnostic.type == UpdateWatchSchedulerDiagnostic.RunType.FOREGROUND_HOT_BURST) {
                    val delay = if (diagnostic.scheduledAt != null && diagnostic.startedAt != null) {
                        (diagnostic.startedAt - diagnostic.scheduledAt) / 1000 / 60
                    } else null

                    val duration = if (diagnostic.startedAt != null && diagnostic.completedAt != null) {
                        (diagnostic.completedAt - diagnostic.startedAt) / 1000 / 60
                    } else null

                    Text(
                        text = buildString {
                            if (diagnostic.scheduledAt != null) append("Scheduled: ${UpdateWatchDiagnosticsManager.formatTimestamp(diagnostic.scheduledAt)}\n")
                            if (delay != null) append("Android delay: ${if (delay >= 0) "+" else ""}$delay min\n")
                            append("Eligible: ${diagnostic.eligibleCount} · Selected: ${diagnostic.selectedCount}\n")
                            append("Updates: ${diagnostic.updatedCount} · No update: ${diagnostic.noUpdateCount} · Failed: ${diagnostic.failedCount}\n")
                            append("Sources: ${diagnostic.sourceCount} · Duration: ${duration ?: 0} min")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    if (diagnostic.nextWorkerTargetAt != null) {
                        Text(
                            text = "Final scheduled target: ${UpdateWatchDiagnosticsManager.formatTimestamp(diagnostic.nextWorkerTargetAt)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )
                        // Only show proposed if it differs from final
                        if (diagnostic.proposedTargetAt != null && diagnostic.proposedTargetAt != diagnostic.nextWorkerTargetAt) {
                            Text(
                                text = "Proposed target: ${UpdateWatchDiagnosticsManager.formatTimestamp(diagnostic.proposedTargetAt)} (+${diagnostic.proposedMarginMinutes} min margin)",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else if (diagnostic.type == UpdateWatchSchedulerDiagnostic.RunType.SCHEDULER_EVENT) {
                    if (diagnostic.isRecoveryRun) {
                        Text(
                            text = "RECOVERY RUN: A backlog of due manga was detected.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Text(
                        text = buildString {
                            if (diagnostic.wallClockBaseSlot != null) {
                                append("Base slot: ${UpdateWatchDiagnosticsManager.formatTimestamp(diagnostic.wallClockBaseSlot)}\n")
                            }
                            if (diagnostic.safetyMarginMinutes > 0) {
                                append("Margin: +${diagnostic.safetyMarginMinutes} min\n")
                            }
                            if (diagnostic.nextWorkerTargetAt != null) {
                                append("Final scheduled target: ${UpdateWatchDiagnosticsManager.formatTimestamp(diagnostic.nextWorkerTargetAt)}\n")
                            }
                            if (diagnostic.timezone != null) {
                                append("Zone: ${diagnostic.timezone}")
                            }
                            val oldTargetText = diagnostic.eventName?.lines()?.find { it.contains("Old target:") }
                            if (oldTargetText != null) {
                                append("\n$oldTargetText")
                            }
                        }.trim(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                AnimatedVisibility(visible = expanded && !isInSelectionMode) {
                    Column(modifier = Modifier.padding(top = 8.dp)) {
                        if (diagnostic.mangaDetails.isNotEmpty()) {
                            Text(
                                text = "${diagnostic.mangaDetails.size} manga refreshed:",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(vertical = 4.dp)
                            )
                            diagnostic.mangaDetails.forEach { detail ->
                                MangaDetailRow(detail)
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun MangaDetailRow(detail: MangaDiagnosticDetail) {
        Column(modifier = Modifier.padding(vertical = 4.dp, horizontal = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "[${UpdateWatchDiagnosticsManager.formatTimestamp(detail.checkedAt)}] ${detail.title}",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f)
                )
                val color = when (detail.result) {
                    "Update found" -> MaterialTheme.colorScheme.primary
                    "FAILED", "EXCEPTION" -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
                Text(text = detail.result, style = MaterialTheme.typography.labelSmall, color = color)
            }
            Text(
                text = "${detail.sourceName} · Next eligible: ${UpdateWatchDiagnosticsManager.formatTimestamp(detail.nextEligibleAt)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (detail.errorReason != null) {
                Text(text = "Error: ${detail.errorReason}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

class UpdateWatchDiagnosticsViewModel : StateViewModel<UpdateWatchDiagnosticsViewModel.State>(State()) {
    data class State(
        val diagnostics: List<UpdateWatchSchedulerDiagnostic> = emptyList(),
        val selection: Set<String> = emptySet(),
    )

    init {
        refresh()
    }

    fun refresh() {
        mutableState.update { it.copy(diagnostics = UpdateWatchDiagnosticsManager.getDiagnostics()) }
    }

    fun toggleSelection(id: String) {
        mutableState.update {
            val newSelection = if (id in it.selection) {
                it.selection - id
            } else {
                it.selection + id
            }
            it.copy(selection = newSelection)
        }
    }

    fun clearSelection() {
        mutableState.update { it.copy(selection = emptySet()) }
    }

    fun deleteSelected() {
        val ids = state.value.selection
        if (ids.isEmpty()) return
        UpdateWatchDiagnosticsManager.deleteSelected(ids)
        clearSelection()
        refresh()
    }

    fun clear() {
        UpdateWatchDiagnosticsManager.clear()
        clearSelection()
        refresh()
    }
}

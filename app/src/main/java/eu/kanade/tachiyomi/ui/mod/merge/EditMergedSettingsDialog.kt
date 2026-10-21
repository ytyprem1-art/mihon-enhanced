package eu.kanade.tachiyomi.ui.mod.merge

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.GetApp
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.SyncDisabled
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenu
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.manga.components.MangaCover
import eu.kanade.tachiyomi.source.mod.MERGED_SOURCE_ID
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MergedMangaReference
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

@Composable
fun EditMergedSettingsDialog(
    onDismissRequest: () -> Unit,
    mergedData: MergedMangaData,
    onDeleteClick: (MergedMangaReference) -> Unit,
    onPositiveClick: (List<MergedMangaReference>) -> Unit,
) {
    val references = remember(mergedData) { mergedData.references.toMutableStateList() }
    val mainReference = remember(references) { references.firstOrNull { it.mangaSourceId == MERGED_SOURCE_ID } }

    var dedupeEnabled by remember(mainReference) {
        mutableStateOf(mainReference?.chapterSortMode != MergedMangaReference.CHAPTER_SORT_NONE)
    }
    var dedupeMode by remember(mainReference) {
        mutableStateOf(
            mainReference?.chapterSortMode?.takeIf { it != MergedMangaReference.CHAPTER_SORT_NONE }
                ?: MergedMangaReference.CHAPTER_SORT_PRIORITY,
        )
    }

    val infoMangaId = references.firstOrNull { it.isInfoManga }?.mangaId

    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = {
            TextButton(
                onClick = {
                    val updatedMain = mainReference?.copy(
                        chapterSortMode = if (dedupeEnabled) dedupeMode else MergedMangaReference.CHAPTER_SORT_NONE,
                    )
                    val finalRefs = references.map { ref ->
                        if (ref.mangaSourceId == MERGED_SOURCE_ID) {
                            updatedMain ?: ref
                        } else {
                            ref
                        }
                    }
                    onPositiveClick(finalRefs)
                    onDismissRequest()
                },
            ) {
                Text(stringResource(MR.strings.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(stringResource(MR.strings.action_cancel))
            }
        },
        title = {
            Text(stringResource(MR.strings.chapter_settings))
        },
        text = {
            val lazyListState = rememberLazyListState()
            val reorderableState = rememberReorderableLazyListState(lazyListState) { from, to ->
                if (dedupeMode == MergedMangaReference.CHAPTER_SORT_PRIORITY) {
                    val fromIdx = references.indexOfFirst { it.id == (from.key as Long) }
                    val toIdx = references.indexOfFirst { it.id == (to.key as Long) }
                    if (fromIdx != -1 && toIdx != -1) {
                        val item = references.removeAt(fromIdx)
                        references.add(toIdx, item)

                        val childRefs = references.filter { it.mangaSourceId != MERGED_SOURCE_ID }
                        childRefs.forEachIndexed { index, ref ->
                            val refIdx = references.indexOfFirst { it.id == ref.id }
                            references[refIdx] = ref.copy(chapterPriority = index)
                        }
                    }
                }
            }

            Column {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Text(stringResource(MR.strings.allow_deduplication))
                    Spacer(Modifier.width(8.dp))
                    Switch(
                        checked = dedupeEnabled,
                        onCheckedChange = { dedupeEnabled = it },
                    )
                }

                if (dedupeEnabled) {
                    DedupeModeSelector(
                        currentMode = dedupeMode,
                        onModeSelected = { dedupeMode = it },
                    )
                }

                InfoMangaSelector(
                    references = references.filter { it.mangaSourceId != MERGED_SOURCE_ID },
                    mergedData = mergedData,
                    currentInfoMangaId = infoMangaId,
                    onInfoMangaSelected = { id ->
                        val updated = references.map {
                            it.copy(isInfoManga = it.mangaId == id)
                        }
                        references.clear()
                        references.addAll(updated)
                    },
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                LazyColumn(
                    state = lazyListState,
                    modifier = Modifier.weight(1f, fill = false),
                ) {
                    itemsIndexed(
                        items = references.filter { it.mangaSourceId != MERGED_SOURCE_ID },
                        key = { _, ref -> ref.id },
                    ) { _, ref ->
                        val manga = mergedData.manga[ref.mangaId]
                        val source = mergedData.sources.find { it.id == ref.mangaSourceId }

                        ReorderableItem(reorderableState, ref.id) {
                            MergedMangaItem(
                                manga = manga,
                                sourceName = source?.name ?: ref.mangaSourceId.toString(),
                                reference = ref,
                                isPriorityMode = dedupeMode == MergedMangaReference.CHAPTER_SORT_PRIORITY,
                                onToggleUpdates = {
                                    val idx = references.indexOfFirst { it.id == ref.id }
                                    references[idx] = ref.copy(getChapterUpdates = !ref.getChapterUpdates)
                                },
                                onToggleDownloads = {
                                    val idx = references.indexOfFirst { it.id == ref.id }
                                    references[idx] = ref.copy(downloadChapters = !ref.downloadChapters)
                                },
                                onDelete = { onDeleteClick(ref) },
                            )
                        }
                    }
                }
            }
        },
    )
}

@Composable
private fun MergedMangaItem(
    manga: Manga?,
    sourceName: String,
    reference: MergedMangaReference,
    isPriorityMode: Boolean,
    onToggleUpdates: () -> Unit,
    onToggleDownloads: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(48.dp), contentAlignment = Alignment.Center) {
            if (isPriorityMode) {
                Icon(
                    imageVector = Icons.Default.DragHandle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        MangaCover.Book(
            data = manga,
            modifier = Modifier.size(40.dp, 60.dp),
        )

        Column(
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
        ) {
            Text(
                text = manga?.title ?: "",
                maxLines = 1,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = sourceName,
                maxLines = 1,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Row {
            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = stringResource(MR.strings.action_delete),
                    modifier = Modifier.size(20.dp),
                )
            }
            IconButton(onClick = onToggleDownloads) {
                Icon(
                    imageVector = Icons.Default.GetApp,
                    contentDescription = null,
                    tint = if (reference.downloadChapters) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        LocalContentColor.current
                    },
                    modifier = Modifier.size(20.dp),
                )
            }
            IconButton(onClick = onToggleUpdates) {
                Icon(
                    imageVector = if (reference.getChapterUpdates) Icons.Default.Sync else Icons.Default.SyncDisabled,
                    contentDescription = stringResource(MR.strings.fetch_chapter_updates),
                    tint = if (reference.getChapterUpdates) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        LocalContentColor.current
                    },
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
private fun DedupeModeSelector(
    currentMode: Int,
    onModeSelected: (Int) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val modes = listOf(
        MergedMangaReference.CHAPTER_SORT_PRIORITY to stringResource(MR.strings.dedupe_priority),
        MergedMangaReference.CHAPTER_SORT_MOST_CHAPTERS to stringResource(MR.strings.dedupe_most_chapters),
        MergedMangaReference.CHAPTER_SORT_HIGHEST_CHAPTER_NUMBER to stringResource(MR.strings.dedupe_highest_chapter),
    )
    val currentModeName = modes.find { it.first == currentMode }?.second ?: ""

    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(MR.strings.deduplication_mode), style = MaterialTheme.typography.labelSmall)
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = !expanded },
            modifier = Modifier.padding(vertical = 4.dp),
        ) {
            OutlinedTextField(
                value = currentModeName,
                onValueChange = {},
                readOnly = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                modes.forEach { (mode, name) ->
                    DropdownMenuItem(
                        text = { Text(name) },
                        onClick = {
                            onModeSelected(mode)
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun InfoMangaSelector(
    references: List<MergedMangaReference>,
    mergedData: MergedMangaData,
    currentInfoMangaId: Long?,
    onInfoMangaSelected: (Long) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val currentInfoManga = mergedData.manga[currentInfoMangaId]
    val currentInfoSourceName = mergedData.sources.find { it.id == currentInfoManga?.source }?.name ?: ""

    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(MR.strings.deduplication_entry_info), style = MaterialTheme.typography.labelSmall)
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = !expanded },
            modifier = Modifier.padding(vertical = 4.dp),
        ) {
            OutlinedTextField(
                value = currentInfoSourceName,
                onValueChange = {},
                readOnly = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                references.forEach { ref ->
                    val sourceName =
                        mergedData.sources.find { it.id == ref.mangaSourceId }?.name ?: ref.mangaSourceId.toString()
                    DropdownMenuItem(
                        text = { Text(sourceName) },
                        onClick = {
                            ref.mangaId?.let { onInfoMangaSelected(it) }
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}

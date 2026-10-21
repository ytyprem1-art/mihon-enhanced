package eu.kanade.tachiyomi.ui.mod.updatecategory

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.mod.MERGED_SOURCE_ID
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.flow.Flow
import tachiyomi.presentation.core.components.LabeledCheckbox

@Composable
fun UpdatesCategoryBar(store: UpdatesCategories, sources: Flow<List<Source>>, clearSelection: () -> Unit) {
    val config by store.changes.collectAsStateWithLifecycle(initialValue = store.snapshot())
    var manage by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var editingId by remember { mutableStateOf<Long?>(null) }
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        FilterChip(selected = config.selected == 0L || config.categories.none { it.id == config.selected }, onClick = {
            clearSelection()
            store.select(0)
        }, label = { Text("Umum") })
        config.categories.forEach { category ->
            FilterChip(selected = config.selected == category.id, onClick = {
                clearSelection()
                store.select(category.id)
            }, label = { Text(category.name) })
        }
        TextButton(onClick = { manage = true }) { Text("Kelola kategori") }
    }
    if (manage) {
        AlertDialog(
            onDismissRequest = { manage = false },
            title = { Text("Kategori Updates") },
            text = {
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    item {
                        Text(
                            "Tautkan source agar semua updatenya otomatis masuk ke kategori. Umum menampilkan source yang belum ditautkan.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    items(config.categories, key = { it.id }) { category ->
                        TextButton(onClick = {
                            editingId = category.id
                            editing = true
                            manage = false
                        }, modifier = Modifier.fillMaxWidth()) {
                            Text(
                                "${category.name} · ${category.sources.size} source",
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    editingId = null
                    editing = true
                    manage = false
                }) { Text("Tambah kategori") }
            },
            dismissButton = { TextButton(onClick = { manage = false }) { Text("Tutup") } },
        )
    }
    if (editing) {
        key(editingId) {
            UpdatesCategoryEditor(store, config, editingId, sources, clearSelection) { editing = false }
        }
    }
}

@Composable
private fun UpdatesCategoryEditor(
    store: UpdatesCategories,
    config: UpdatesCategoryConfig,
    id: Long?,
    sources: Flow<List<Source>>,
    clearSelection: () -> Unit,
    dismiss: () -> Unit,
) {
    val category = config.categories.firstOrNull { it.id == id }
    val installed by sources.collectAsStateWithLifecycle(initialValue = emptyList())
    var name by remember { mutableStateOf(category?.name.orEmpty()) }
    var selected by remember { mutableStateOf(category?.sources.orEmpty()) }
    var query by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf(false) }
    val available = installed.filterIsInstance<HttpSource>().filter { it.id != MERGED_SOURCE_ID }
        .sortedWith(compareBy({ it.name.lowercase() }, { it.lang }))
        .filter { query.isBlank() || it.name.contains(query, true) || it.lang.contains(query, true) }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(if (id == null) "Tambah kategori Updates" else "Edit kategori Updates") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, {
                    name = it
                    error = null
                }, label = { Text("Nama kategori") }, singleLine = true)
                OutlinedTextField(query, { query = it }, label = { Text("Cari source") }, singleLine = true)
                LazyColumn(Modifier.heightIn(max = 250.dp)) {
                    if (available.isEmpty()) item { Text("Tidak ada source yang cocok.") }
                    items(available, key = { it.id }) { source ->
                        val other = config.categories.firstOrNull { it.id != id && source.id in it.sources }
                        Column {
                            LabeledCheckbox(
                                label = "${source.name} (${source.lang})",
                                checked = source.id in selected,
                                enabled = other == null,
                                onCheckedChange = { checked ->
                                    selected =
                                        if (checked) selected + source.id else selected - source.id
                                },
                            )
                            if (other !=
                                null
                            ) {
                                Text("Sudah ditautkan ke ${other.name}", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    val missing = selected - installed.map { it.id }.toSet()
                    if (missing.isNotEmpty()) {
                        item {
                            Text(
                                "${missing.size} tautan source yang belum terpasang tetap disimpan.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (id != null) {
                    Row {
                        TextButton(onClick = {
                            store.move(id, -1)
                        }, enabled = config.categories.firstOrNull()?.id != id) { Text("Ke kiri") }
                        TextButton(onClick = {
                            store.move(id, 1)
                        }, enabled = config.categories.lastOrNull()?.id != id) { Text("Ke kanan") }
                        TextButton(onClick = {
                            deleting = true
                        }) { Text("Hapus", color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                try {
                    store.save(id, name, selected)
                    clearSelection()
                    dismiss()
                } catch (exception: IllegalArgumentException) {
                    error = exception.message
                }
            }, enabled = name.isNotBlank()) { Text("Simpan") }
        },
        dismissButton = { TextButton(onClick = dismiss) { Text("Batal") } },
    )
    if (deleting) {
        AlertDialog(
            onDismissRequest = { deleting = false },
            title = { Text("Hapus kategori?") },
            text = { Text("Update dari source kategori ini kembali ke Umum. Komik dan chapter tetap tersimpan.") },
            confirmButton = {
                TextButton(onClick = {
                    store.delete(id!!)
                    clearSelection()
                    dismiss()
                }) { Text("Hapus") }
            },
            dismissButton = { TextButton(onClick = { deleting = false }) { Text("Batal") } },
        )
    }
}

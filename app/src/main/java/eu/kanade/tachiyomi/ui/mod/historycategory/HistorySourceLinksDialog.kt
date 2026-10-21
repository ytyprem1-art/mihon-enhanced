package eu.kanade.tachiyomi.ui.mod.historycategory

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.mod.MERGED_SOURCE_ID
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.flow.Flow
import tachiyomi.domain.history.repository.HistoryCategory
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.LabeledCheckbox
import tachiyomi.presentation.core.i18n.stringResource
import androidx.compose.ui.res.stringResource as androidStringResource

@Composable
fun HistorySourceLinksDialog(
    category: HistoryCategory,
    categories: List<HistoryCategory>,
    sources: Flow<List<Source>>,
    links: Map<Long, Long>,
    onSave: (Set<Long>) -> Unit,
    onDismissRequest: () -> Unit,
) {
    val installedSources by sources.collectAsStateWithLifecycle(initialValue = emptyList())
    var selected by remember(category.id) { mutableStateOf(links.filterValues { it == category.id }.keys.toSet()) }
    var query by remember { mutableStateOf("") }
    val available = installedSources.filterIsInstance<HttpSource>()
        .filter { it.id != MERGED_SOURCE_ID }
        .sortedWith(compareBy({ it.name.lowercase() }, { it.lang }))
        .filter {
            query.isBlank() || it.name.contains(query, ignoreCase = true) ||
                it.lang.contains(query, ignoreCase = true)
        }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(androidStringResource(R.string.history_sources_title, category.name)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(androidStringResource(R.string.history_sources_description))
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text(androidStringResource(R.string.history_sources_search)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                    if (available.isEmpty()) item { Text(androidStringResource(R.string.history_sources_empty)) }
                    items(available, key = { it.id }) { source ->
                        val otherCategory = links[source.id]?.takeIf { it != category.id }
                            ?.let { id -> categories.find { it.id == id } }
                        Column {
                            LabeledCheckbox(
                                label = "${source.name} (${source.lang})",
                                checked = source.id in selected,
                                enabled = otherCategory == null,
                                onCheckedChange = { checked ->
                                    selected = if (checked) selected + source.id else selected - source.id
                                },
                            )
                            if (otherCategory != null) {
                                Text(
                                    androidStringResource(R.string.history_sources_other_category, otherCategory.name),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(selected) }) { Text(stringResource(MR.strings.action_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) { Text(stringResource(MR.strings.action_cancel)) }
        },
    )
}

package eu.kanade.presentation.browse.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.AdaptiveSheet
import eu.kanade.tachiyomi.util.system.LocaleHelper
import tachiyomi.domain.source.model.Source
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.ListGroupHeader
import tachiyomi.presentation.core.components.ScrollbarLazyColumn
import tachiyomi.presentation.core.components.material.TextButton
import tachiyomi.presentation.core.i18n.stringResource

@Composable
fun QuickSourceSwitcherDialog(
    onDismissRequest: () -> Unit,
    sources: List<Source>,
    currentSourceId: Long,
    onSourceSelected: (Source) -> Unit,
    excludeCurrentSource: Boolean = true,
    title: String = "Switch Source",
) {
    val context = LocalContext.current
    val groupedSources = remember(sources, currentSourceId, excludeCurrentSource) {
        sources
            .filter { (!excludeCurrentSource || it.id != currentSourceId) && !it.isUsedLast }
            .groupBy { it.lang }
            .toSortedMap(
                compareBy<String> { lang ->
                    when (lang) {
                        "id" -> 0
                        "en" -> 1
                        "all" -> 2
                        "other", "" -> 3
                        else -> 4
                    }
                }.thenBy { LocaleHelper.getSourceDisplayName(it, context) },
            )
    }

    AdaptiveSheet(onDismissRequest = onDismissRequest) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp),
        ) {
            Text(
                text = title,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                style = MaterialTheme.typography.titleLarge,
            )

            ScrollbarLazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false),
            ) {
                groupedSources.forEach { (lang, sources) ->
                    item(key = "header-$lang") {
                        ListGroupHeader(
                            text = LocaleHelper.getSourceDisplayName(lang, context),
                        )
                    }

                    items(
                        items = sources,
                        key = { it.id },
                    ) { source ->
                        BaseSourceItem(
                            source = source,
                            showLanguageInContent = false,
                            onClickItem = {
                                onDismissRequest()
                                onSourceSelected(source)
                            },
                        )
                    }
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

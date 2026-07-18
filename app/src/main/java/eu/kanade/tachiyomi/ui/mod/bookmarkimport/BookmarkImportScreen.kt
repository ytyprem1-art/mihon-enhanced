package eu.kanade.tachiyomi.ui.mod.bookmarkimport

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarTitle
import eu.kanade.presentation.util.Screen
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.padding

class BookmarkImportScreen : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val screenModel = rememberScreenModel { BookmarkImportScreenModel() }
        val state by screenModel.state.collectAsState()

        val pickFileLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.GetContent(),
        ) { uri ->
            if (uri != null) {
                screenModel.processFile(context, uri)
            }
        }

        Scaffold(
            topBar = { scrollBehavior ->
                AppBar(
                    titleContent = { AppBarTitle("External bookmark import") },
                    navigateUp = navigator::pop,
                    scrollBehavior = scrollBehavior,
                )
            },
        ) { contentPadding ->
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = contentPadding,
            ) {
                item {
                    Column(
                        modifier = Modifier.padding(MaterialTheme.padding.medium),
                        verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.medium),
                    ) {
                        Text(
                            text = "Import manga bookmarks from CSV and add matched titles to your Mihon Library.",
                            style = MaterialTheme.typography.bodyLarge,
                        )

                        InfoSection(title = "Supported website family") {
                            BulletItem("Manganato")
                            BulletItem("Natomanga")
                            BulletItem("Nelomanga")
                            BulletItem("Mangakakalot")
                        }

                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = MaterialTheme.shapes.medium,
                        ) {
                            Column(modifier = Modifier.padding(MaterialTheme.padding.medium)) {
                                Text(
                                    text = "Source Information:",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold,
                                )
                                Text(
                                    text = state.resolvedSourceInfo ?: "Resolving source...",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "The installed Manganato source and its selected Preferred Mirror will be used during import.",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "Imported manga will not enable Auto Refresh automatically.",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }

                        Button(
                            onClick = { pickFileLauncher.launch("text/*") },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(imageVector = Icons.Outlined.FileOpen, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Choose CSV file")
                        }

                        if (state.isParsing) {
                            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator()
                            }
                        }

                        state.error?.let { error ->
                            ErrorPreview(error)
                        }

                        if (state.isValid) {
                            ValidPreview(state)

                            state.diagnosticResult?.let { diagnostic ->
                                Surface(
                                    color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.3f),
                                    shape = MaterialTheme.shapes.medium,
                                    modifier = Modifier.fillMaxWidth().padding(top = MaterialTheme.padding.small)
                                ) {
                                    Column(modifier = Modifier.padding(MaterialTheme.padding.medium)) {
                                        Text(
                                            text = "Debug Diagnostics (ExternalBookmarkImport):",
                                            style = MaterialTheme.typography.labelLarge,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.tertiary
                                        )
                                        androidx.compose.foundation.text.selection.SelectionContainer {
                                            Text(
                                                text = diagnostic,
                                                style = MaterialTheme.typography.bodySmall,
                                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                                            )
                                        }
                                    }
                                }
                            }

                            val mikotoIndex = state.entries.indexOfFirst { it.title.contains("Mikoto and Rei", ignoreCase = true) }
                            if (mikotoIndex != -1 && state.entries[mikotoIndex].matchResult == ManganatoCsvParser.MatchResult.UNCHECKED) {
                                OutlinedButton(
                                    onClick = { screenModel.checkMatches(mikotoIndex) },
                                    modifier = Modifier.fillMaxWidth(),
                                    enabled = !state.isMatching
                                ) {
                                    Text("Debug: Test matching 'Mikoto and Rei'")
                                }
                            }

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = MaterialTheme.padding.small),
                                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small)
                            ) {
                                if (state.isMatching) {
                                    Button(
                                        onClick = screenModel::cancelMatching,
                                        modifier = Modifier.weight(1f),
                                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                                    ) {
                                        Text("Cancel matching")
                                    }
                                } else {
                                    Button(
                                        onClick = screenModel::checkMatches,
                                        modifier = Modifier.weight(1f),
                                        enabled = state.entries.any { it.isValid && it.matchResult == ManganatoCsvParser.MatchResult.UNCHECKED }
                                    ) {
                                        Text("Check matches")
                                    }
                                }
                            }
                        }
                    }
                }

                if (state.isValid && state.entries.isNotEmpty()) {
                    item {
                        Text(
                            text = "Row Preview",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = MaterialTheme.padding.medium, vertical = 8.dp)
                        )
                    }

                    items(state.entries) { entry ->
                        BookmarkRowPreview(entry)
                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = MaterialTheme.padding.medium),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun InfoSection(title: String, content: @Composable ColumnScope.() -> Unit) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            content()
        }
    }

    @Composable
    private fun BulletItem(text: String) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(text = "•", modifier = Modifier.padding(end = 8.dp))
            Text(text = text, modifier = Modifier.weight(1f))
        }
    }

    @Composable
    private fun ErrorPreview(error: String) {
        PreviewCard(
            title = "Invalid file",
            icon = Icons.Outlined.ErrorOutline,
            iconTint = MaterialTheme.colorScheme.error,
        ) {
            Text(text = error, color = MaterialTheme.colorScheme.error)
        }
    }

    @Composable
    private fun ValidPreview(state: BookmarkImportScreenModel.State) {
        PreviewCard(
            title = "File ready",
            icon = Icons.Outlined.CheckCircle,
            iconTint = MaterialTheme.colorScheme.primary,
        ) {
            DetailRow("File", state.fileName ?: "Unknown")
            DetailRow("Total rows", state.rowCount.toString())
            DetailRow("Valid bookmarks", state.validCount.toString())
            DetailRow("Invalid rows", state.invalidCount.toString())
            DetailRow("With progress", state.progressCount.toString())
            DetailRow("Unread", state.unreadCount.toString())

            if (state.entries.any { it.matchResult != ManganatoCsvParser.MatchResult.UNCHECKED }) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                DetailRow("Matched", state.matchedCount.toString())
                DetailRow("Not found", state.notFoundCount.toString())
                DetailRow("Timeout", state.timeoutCount.toString())
                DetailRow("Failed", state.failedCount.toString())
            }
        }
    }

    @Composable
    private fun BookmarkRowPreview(entry: ManganatoCsvParser.BookmarkEntry) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = MaterialTheme.padding.medium, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = entry.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (!entry.isValid || entry.matchResult != ManganatoCsvParser.MatchResult.UNCHECKED) {
                    val statusText = when (entry.matchResult) {
                        ManganatoCsvParser.MatchResult.MATCHED -> "Matched"
                        ManganatoCsvParser.MatchResult.NOT_FOUND -> "Not found"
                        ManganatoCsvParser.MatchResult.SOURCE_UNAVAILABLE -> "Source unavailable"
                        ManganatoCsvParser.MatchResult.NETWORK_TIMEOUT -> "Network timeout"
                        ManganatoCsvParser.MatchResult.SOURCE_ERROR -> "Source error"
                        ManganatoCsvParser.MatchResult.CANCELED -> "Canceled"
                        else -> null
                    }
                    if (statusText != null) {
                        Icon(
                            imageVector = if (entry.matchResult == ManganatoCsvParser.MatchResult.MATCHED) Icons.Outlined.CheckCircle else Icons.Outlined.ErrorOutline,
                            contentDescription = null,
                            tint = if (entry.matchResult == ManganatoCsvParser.MatchResult.MATCHED) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = statusText,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (entry.matchResult == ManganatoCsvParser.MatchResult.MATCHED) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                        )
                    } else if (!entry.isValid) {
                        Icon(
                            imageVector = Icons.Outlined.ErrorOutline,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            if (entry.matchResult == ManganatoCsvParser.MatchResult.MATCHED && entry.resolvedManga != null) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
                    shape = MaterialTheme.shapes.extraSmall,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        Text(
                            text = "Resolved: ${entry.resolvedManga.title}",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Source: ${entry.resolvedSourceName} · ${entry.resolvedManga.url}",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusBadge(
                    text = entry.viewedChapter?.let { "Ch. $it" } ?: "Unread",
                    color = if (entry.viewedChapter != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
                )
                entry.domain?.let {
                    StatusBadge(text = it, color = MaterialTheme.colorScheme.tertiary)
                }
            }

            Text(
                text = entry.mangaPath ?: entry.originalUrl,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            if (!entry.isValid) {
                Text(
                    text = entry.validationError ?: "Invalid row",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }

    @Composable
    private fun StatusBadge(text: String, color: androidx.compose.ui.graphics.Color) {
        Surface(
            color = color.copy(alpha = 0.1f),
            contentColor = color,
            shape = MaterialTheme.shapes.extraSmall,
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                fontWeight = FontWeight.Bold
            )
        }
    }

    @Composable
    private fun PreviewCard(
        title: String,
        icon: ImageVector,
        iconTint: androidx.compose.ui.graphics.Color,
        content: @Composable ColumnScope.() -> Unit,
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
        ) {
            Column(modifier = Modifier.padding(MaterialTheme.padding.medium), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(imageVector = icon, contentDescription = null, tint = iconTint)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
                content()
            }
        }
    }

    @Composable
    private fun DetailRow(label: String, value: String) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(text = label, style = MaterialTheme.typography.labelLarge)
            Text(text = value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        }
    }
}

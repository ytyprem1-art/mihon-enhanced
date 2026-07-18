package eu.kanade.tachiyomi.ui.mod.bookmarkimport

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.Immutable
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import logcat.LogPriority
import mihon.domain.manga.model.toDomainManga
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class BookmarkImportScreenModel : StateScreenModel<BookmarkImportScreenModel.State>(State()) {

    private val sourceManager: SourceManager = Injekt.get()
    private var matchingJob: Job? = null

    @Immutable
    data class State(
        val fileName: String? = null,
        val rowCount: Int = 0,
        val validCount: Int = 0,
        val invalidCount: Int = 0,
        val unreadCount: Int = 0,
        val progressCount: Int = 0,
        val entries: List<ManganatoCsvParser.BookmarkEntry> = emptyList(),
        val isValid: Boolean = false,
        val error: String? = null,
        val isParsing: Boolean = false,
        val isMatching: Boolean = false,
        val matchedCount: Int = 0,
        val notFoundCount: Int = 0,
        val timeoutCount: Int = 0,
        val failedCount: Int = 0,
        val resolvedSourceInfo: String? = null,
        val diagnosticResult: String? = null,
    )

    init {
        val manganato = sourceManager.getOnlineSources().find { it.name == "Manganato" && it.lang == "en" }
        if (manganato != null) {
            mutableState.update { it.copy(resolvedSourceInfo = "Source: ${manganato.name} (ID: ${manganato.id}) [${manganato::class.java.name}]") }
        } else {
            mutableState.update { it.copy(resolvedSourceInfo = "Manganato (EN) source not found or disabled") }
        }
    }

    fun processFile(context: Context, uri: Uri) {
        cancelMatching()
        mutableState.update { it.copy(isParsing = true, error = null, fileName = null, rowCount = 0, isValid = false, entries = emptyList(), diagnosticResult = null) }

        screenModelScope.launch {
            try {
                val fileName = getFileName(context, uri)
                val entries = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use {
                        ManganatoCsvParser.parse(it)
                    } ?: throw Exception("Could not open file")
                }

                val valid = entries.filter { it.isValid }
                val invalid = entries.size - valid.size
                val unread = valid.count { it.viewedChapter == null }
                val progress = valid.size - unread

                mutableState.update {
                    it.copy(
                        fileName = fileName,
                        rowCount = entries.size,
                        validCount = valid.size,
                        invalidCount = invalid,
                        unreadCount = unread,
                        progressCount = progress,
                        entries = entries,
                        isValid = true,
                        isParsing = false,
                    )
                }
            } catch (e: ManganatoCsvParser.InvalidHeaderException) {
                mutableState.update {
                    it.copy(
                        error = e.message,
                        isParsing = false,
                        isValid = false
                    )
                }
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e)
                mutableState.update {
                    it.copy(
                        error = "Failed to parse file: ${e.message}",
                        isParsing = false,
                        isValid = false
                    )
                }
            }
        }
    }

    fun checkMatches(singleRowIndex: Int? = null) {
        if (state.value.isMatching) return

        matchingJob = screenModelScope.launch {
            mutableState.update { it.copy(isMatching = true, diagnosticResult = null) }

            val manganato = sourceManager.getOnlineSources().find { it.name == "Manganato" && it.lang == "en" }
            if (manganato == null) {
                val updatedEntries = state.value.entries.map {
                    if (it.isValid && it.matchResult == ManganatoCsvParser.MatchResult.UNCHECKED) {
                        it.copy(matchResult = ManganatoCsvParser.MatchResult.SOURCE_UNAVAILABLE)
                    } else it
                }
                updateEntriesAndSummary(updatedEntries)
                mutableState.update { it.copy(isMatching = false) }
                return@launch
            }

            val entries = state.value.entries.toMutableList()
            val indices = if (singleRowIndex != null) listOf(singleRowIndex) else entries.indices

            for (i in indices) {
                val entry = entries[i]
                if (!entry.isValid || entry.matchResult != ManganatoCsvParser.MatchResult.UNCHECKED) continue

                var result = ManganatoCsvParser.MatchResult.UNCHECKED
                var resolvedManga: tachiyomi.domain.manga.model.Manga? = null

                // Retry loop
                for (attempt in 0..2) {
                    try {
                        val sManga = eu.kanade.tachiyomi.source.model.SManga.create().apply {
                            url = entry.mangaPath!!
                            title = entry.title
                        }

                        if (entry.title.contains("Mikoto and Rei", ignoreCase = true)) {
                            val info = buildString {
                                appendLine("START DIAGNOSTIC (ExternalBookmarkImport)")
                                appendLine("Source: ${manganato.name} (ID: ${manganato.id})")
                                appendLine("Class: ${manganato::class.java.name}")
                                appendLine("Is HttpSource: ${manganato is HttpSource}")
                                appendLine("SManga.url: ${sManga.url}")
                                appendLine("SManga.title: ${sManga.title}")
                                if (manganato is HttpSource) {
                                    try {
                                        @Suppress("DEPRECATION")
                                        val req = manganato.mangaDetailsRequest(sManga)
                                        appendLine("Generated Request URL: ${req.url}")
                                    } catch (e: Exception) {
                                        appendLine("Failed to generate request URL: ${e.message}")
                                    }
                                }
                            }
                            Log.d("ExternalBookmarkImport", info)
                            mutableState.update { it.copy(diagnosticResult = info) }
                        }

                        Log.d("ExternalBookmarkImport", "Checking match for ${entry.title} at ${entry.mangaPath} (Attempt ${attempt + 1})")

                        val networkManga = withContext(Dispatchers.IO) {
                            manganato.getMangaUpdate(sManga, emptyList(), fetchDetails = true, fetchChapters = false).manga
                        }

                        // Fix: SManga returned by getMangaUpdate might have uninitialized lateinit 'url' or different 'title'.
                        // We must ensure the original canonical source path is preserved.
                        networkManga.url = entry.mangaPath!!
                        networkManga.title = entry.title

                        resolvedManga = networkManga.toDomainManga(manganato.id)
                        result = ManganatoCsvParser.MatchResult.MATCHED
                        Log.d("ExternalBookmarkImport", "Matched: ${resolvedManga.title}")

                        if (entry.title.contains("Mikoto and Rei", ignoreCase = true)) {
                            val successInfo = "MATCHED SUCCESS: ${resolvedManga.title} -> ${resolvedManga.url}"
                            Log.d("ExternalBookmarkImport", successInfo)
                            mutableState.update { it.copy(diagnosticResult = (it.diagnosticResult ?: "") + "\n" + successInfo) }
                        }
                        break
                    } catch (e: java.net.SocketTimeoutException) {
                        result = ManganatoCsvParser.MatchResult.NETWORK_TIMEOUT
                        Log.w("ExternalBookmarkImport", "Timeout matching ${entry.title}", e)
                        if (attempt < 2) delay(1000L * (attempt + 1))
                    } catch (e: Exception) {
                        Log.e("ExternalBookmarkImport", "Error matching ${entry.title} via ${manganato.name} (${manganato.id}): ${e::class.java.simpleName}", e)

                        if (entry.title.contains("Mikoto and Rei", ignoreCase = true)) {
                            val errorInfo = buildString {
                                appendLine("EXCEPTION Class: ${e::class.java.name}")
                                appendLine("EXCEPTION Message: ${e.message}")
                                appendLine("STACK TRACE:")
                                appendLine(Log.getStackTraceString(e))
                            }
                            mutableState.update { it.copy(diagnosticResult = (it.diagnosticResult ?: "") + "\n" + errorInfo) }
                        }

                        val message = e.message ?: ""
                        result = when {
                            message.contains("404") -> ManganatoCsvParser.MatchResult.NOT_FOUND
                            else -> ManganatoCsvParser.MatchResult.SOURCE_ERROR
                        }
                        // For Source Error, we want to store a concise reason
                        val reason = "${e::class.java.simpleName}: ${message.take(100)}"
                        entries[i] = entry.copy(validationError = reason)
                        break
                    }
                }

                entries[i] = entry.copy(
                    matchResult = result,
                    resolvedManga = resolvedManga,
                    resolvedSourceName = manganato.name
                )

                updateEntriesAndSummary(entries.toList())

                delay(500L) // Small delay between rows
            }

            mutableState.update { it.copy(isMatching = false) }
        }
    }

    fun cancelMatching() {
        matchingJob?.cancel()
        matchingJob = null
        if (state.value.isMatching) {
            val updatedEntries = state.value.entries.map {
                if (it.isValid && it.matchResult == ManganatoCsvParser.MatchResult.UNCHECKED) {
                    it.copy(matchResult = ManganatoCsvParser.MatchResult.CANCELED)
                } else it
            }
            updateEntriesAndSummary(updatedEntries)
            mutableState.update { it.copy(isMatching = false) }
        }
    }

    private fun updateEntriesAndSummary(entries: List<ManganatoCsvParser.BookmarkEntry>) {
        val matched = entries.count { it.matchResult == ManganatoCsvParser.MatchResult.MATCHED }
        val notFound = entries.count { it.matchResult == ManganatoCsvParser.MatchResult.NOT_FOUND }
        val timeout = entries.count { it.matchResult == ManganatoCsvParser.MatchResult.NETWORK_TIMEOUT }
        val failed = entries.count { it.matchResult == ManganatoCsvParser.MatchResult.SOURCE_ERROR || it.matchResult == ManganatoCsvParser.MatchResult.SOURCE_UNAVAILABLE }

        mutableState.update {
            it.copy(
                entries = entries,
                matchedCount = matched,
                notFoundCount = notFound,
                timeoutCount = timeout,
                failedCount = failed,
            )
        }
    }

    private fun getFileName(context: Context, uri: Uri): String? {
        return context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst()) {
                cursor.getString(nameIndex)
            } else {
                null
            }
        } ?: uri.path?.substringAfterLast('/')
    }
}

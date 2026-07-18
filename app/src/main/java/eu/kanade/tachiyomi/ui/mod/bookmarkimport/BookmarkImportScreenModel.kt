package eu.kanade.tachiyomi.ui.mod.bookmarkimport

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.Immutable
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.domain.chapter.interactor.SetReadStatus
import eu.kanade.domain.chapter.interactor.SyncChaptersWithSource
import eu.kanade.domain.manga.interactor.UpdateManga
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
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString
import java.util.UUID

class BookmarkImportScreenModel : StateScreenModel<BookmarkImportScreenModel.State>(State()) {

    private val sourceManager: SourceManager = Injekt.get()
    private val mangaRepository: MangaRepository = Injekt.get()
    private val updateManga: UpdateManga = Injekt.get()
    private val syncChaptersWithSource: SyncChaptersWithSource = Injekt.get()
    private val getChaptersByMangaId: GetChaptersByMangaId = Injekt.get()
    private val setReadStatus: SetReadStatus = Injekt.get()
    private val preferences: BookmarkImportPreferences = Injekt.get()

    private val json = Json { ignoreUnknownKeys = true }
    private var matchingJob: Job? = null
    private var importJob: Job? = null

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
        val isImporting: Boolean = false,
        val matchedCount: Int = 0,
        val notFoundCount: Int = 0,
        val timeoutCount: Int = 0,
        val failedCount: Int = 0,
        val resolvedSourceInfo: String? = null,
        val diagnosticResult: String? = null,

        // Import summary
        val importImportedCount: Int = 0,
        val importExistedCount: Int = 0,
        val importProgressCount: Int = 0,
        val importFailedCount: Int = 0,
        val importCurrent: Int = 0,
        val importTotal: Int = 0,
        val showImportConfirmation: Boolean = false,

        // Session
        val hasSession: Boolean = false,
        val sessionFileName: String? = null,
    )

    init {
        val manganato = sourceManager.getOnlineSources().find { it.name == "Manganato" && it.lang == "en" }
        if (manganato != null) {
            mutableState.update { it.copy(resolvedSourceInfo = "Source: ${manganato.name} (ID: ${manganato.id}) [${manganato::class.java.name}]") }
        } else {
            mutableState.update { it.copy(resolvedSourceInfo = "Manganato (EN) source not found or disabled") }
        }
        loadSession()
    }

    private fun loadSession() {
        val sessionStr = preferences.importSession.get()
        if (sessionStr.isNotEmpty()) {
            try {
                val session = json.decodeFromString<BookmarkImportSession>(sessionStr)
                mutableState.update {
                    it.copy(
                        hasSession = true,
                        sessionFileName = session.fileName,
                        fileName = session.fileName,
                        entries = session.entries,
                        isValid = true,
                        rowCount = session.entries.size,
                        validCount = session.entries.count { e -> e.isValid },
                        invalidCount = session.entries.count { e -> !e.isValid },
                        unreadCount = session.entries.count { e -> e.isValid && e.viewedChapter == null },
                        progressCount = session.entries.count { e -> e.isValid && e.viewedChapter != null },
                    )
                }
                updateEntriesAndSummary(session.entries)
                updateImportSummary(session.entries)
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "Failed to load session" }
                preferences.importSession.delete()
            }
        }
    }

    private fun saveSession() {
        val currentState = state.value
        if (currentState.entries.isEmpty()) return

        val session = BookmarkImportSession(
            sessionId = currentState.fileName ?: "unknown",
            fileName = currentState.fileName ?: "unknown",
            entries = currentState.entries
        )
        preferences.importSession.set(json.encodeToString(session))
    }

    fun discardSession() {
        cancelMatching()
        cancelImport()
        preferences.importSession.delete()
        mutableState.update { State().copy(resolvedSourceInfo = it.resolvedSourceInfo) }
    }

    fun resumeSession() {
        if (state.value.isMatching || state.value.isImporting) return

        val entries = state.value.entries
        val hasUnchecked = entries.any { it.isValid && (it.matchResult == ManganatoCsvParser.MatchResult.UNCHECKED || it.matchResult == ManganatoCsvParser.MatchResult.CANCELED) }

        if (hasUnchecked) {
            checkMatches()
        } else {
            val matchedCount = entries.count { it.matchResult == ManganatoCsvParser.MatchResult.MATCHED }
            if (matchedCount > 0) {
                showImportConfirmation()
            }
        }

        mutableState.update { it.copy(hasSession = false) }
    }

    fun processFile(context: Context, uri: Uri) {
        cancelMatching()
        cancelImport()
        mutableState.update { it.copy(isParsing = true, error = null, fileName = null, rowCount = 0, isValid = false, entries = emptyList(), diagnosticResult = null, hasSession = false) }

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
                saveSession()
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

    fun checkMatches(singleRowIndex: Int? = null, retryFailedOnly: Boolean = false) {
        if (state.value.isMatching || state.value.isImporting) return

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
            val indices = if (singleRowIndex != null) {
                listOf(singleRowIndex)
            } else {
                entries.indices.filter { i ->
                    val res = entries[i].matchResult
                    if (retryFailedOnly) {
                        res == ManganatoCsvParser.MatchResult.NETWORK_TIMEOUT || res == ManganatoCsvParser.MatchResult.SOURCE_ERROR
                    } else {
                        res == ManganatoCsvParser.MatchResult.UNCHECKED || res == ManganatoCsvParser.MatchResult.CANCELED
                    }
                }
            }

            for (i in indices) {
                val entry = entries[i]
                if (!entry.isValid) continue

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
                saveSession()

                delay(500L) // Small delay between rows
            }

            mutableState.update { it.copy(isMatching = false) }
        }
    }

    fun showImportConfirmation() {
        if (state.value.isMatching || state.value.isImporting) return
        mutableState.update { it.copy(showImportConfirmation = true) }
    }

    fun hideImportConfirmation() {
        mutableState.update { it.copy(showImportConfirmation = false) }
    }

    fun importMatched(retryFailedOnly: Boolean = false) {
        if (state.value.isMatching || state.value.isImporting) return
        hideImportConfirmation()

        importJob = screenModelScope.launch {
            val manganato = sourceManager.getOnlineSources().find { it.name == "Manganato" && it.lang == "en" }
            if (manganato == null) {
                return@launch
            }

            val entries = state.value.entries.toMutableList()
            val indicesToImport = entries.indices.filter { i ->
                val res = entries[i].matchResult
                if (retryFailedOnly) {
                    res == ManganatoCsvParser.MatchResult.IMPORT_FAILED || res == ManganatoCsvParser.MatchResult.CHAPTER_SYNC_FAILED
                } else {
                    res == ManganatoCsvParser.MatchResult.MATCHED
                }
            }

            if (indicesToImport.isEmpty()) return@launch

            mutableState.update {
                it.copy(
                    isImporting = true,
                    importCurrent = 0,
                    importTotal = indicesToImport.size
                )
            }

            var processedInSession = 0

            for (i in indicesToImport) {
                val entry = entries[i]
                if (entry.resolvedManga == null) continue

                processedInSession++
                mutableState.update { it.copy(importCurrent = processedInSession) }

                var result = entry.matchResult
                try {
                    // 1. Check for duplicates
                    var localManga = mangaRepository.getMangaByUrlAndSourceId(entry.mangaPath!!, manganato.id)
                    val alreadyExisted = localManga != null && localManga.favorite

                    // 2. Persist/Add to Library
                    if (localManga == null) {
                        val inserted = mangaRepository.insertNetworkManga(listOf(entry.resolvedManga))
                        localManga = inserted.firstOrNull() ?: throw Exception("Persistence failed")
                    }

                    if (!localManga.favorite) {
                        updateManga.awaitUpdateFavorite(localManga.id, true)
                        localManga = localManga.copy(favorite = true)
                    }

                    // 3. Sync Chapters
                    val sManga = eu.kanade.tachiyomi.source.model.SManga.create().apply {
                        url = localManga.url
                        title = localManga.title
                    }

                    val networkUpdate = withContext(Dispatchers.IO) {
                        manganato.getMangaUpdate(sManga, emptyList(), fetchDetails = false, fetchChapters = true)
                    }

                    syncChaptersWithSource.await(networkUpdate.chapters, localManga, manganato, manualFetch = true)

                    // 4. Apply Reading Progress
                    if (entry.viewedChapter != null) {
                        val allChapters = getChaptersByMangaId.await(localManga.id)

                        Log.d("ExternalBookmarkImport", "Applying progress for ${entry.title}: Viewed Chapter ${entry.viewedChapter}")

                        val chaptersToMark = allChapters.filter { chapter ->
                            val effectiveNumber = if (chapter.chapterNumber >= 0) {
                                chapter.chapterNumber
                            } else {
                                ManganatoCsvParser.parseChapterNumber(chapter.name)
                            }

                            val isMatch = effectiveNumber != null && effectiveNumber <= entry.viewedChapter

                            Log.d("ExternalBookmarkImport", "  Chapter: ${chapter.name} | Num: ${chapter.chapterNumber} | Effective: $effectiveNumber | Match: $isMatch")

                            isMatch
                        }

                        Log.d("ExternalBookmarkImport", "  Total chapters: ${allChapters.size} | Selected: ${chaptersToMark.size}")

                        if (chaptersToMark.isNotEmpty()) {
                            val resultStatus = setReadStatus.await(read = true, chapters = chaptersToMark.toTypedArray())
                            Log.d("ExternalBookmarkImport", "  SetReadStatus Result: $resultStatus")
                        }
                        result = if (alreadyExisted) ManganatoCsvParser.MatchResult.ALREADY_IN_LIBRARY else ManganatoCsvParser.MatchResult.IMPORTED_WITH_PROGRESS
                    } else {
                        result = if (alreadyExisted) ManganatoCsvParser.MatchResult.ALREADY_IN_LIBRARY else ManganatoCsvParser.MatchResult.IMPORTED
                    }

                } catch (e: Exception) {
                    logcat(LogPriority.ERROR, e) { "Failed to import ${entry.title}" }
                    result = ManganatoCsvParser.MatchResult.IMPORT_FAILED
                }

                entries[i] = entry.copy(matchResult = result)
                updateImportSummary(entries.toList())
                saveSession()
                delay(500L)
            }

            mutableState.update { it.copy(isImporting = false) }
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
            saveSession()
        }
    }

    fun cancelImport() {
        importJob?.cancel()
        importJob = null
        if (state.value.isImporting) {
            mutableState.update { it.copy(isImporting = false) }
            saveSession()
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

    private fun updateImportSummary(entries: List<ManganatoCsvParser.BookmarkEntry>) {
        val imported = entries.count { it.matchResult == ManganatoCsvParser.MatchResult.IMPORTED || it.matchResult == ManganatoCsvParser.MatchResult.IMPORTED_WITH_PROGRESS }
        val existed = entries.count { it.matchResult == ManganatoCsvParser.MatchResult.ALREADY_IN_LIBRARY }
        val progress = entries.count { it.matchResult == ManganatoCsvParser.MatchResult.IMPORTED_WITH_PROGRESS }
        val failed = entries.count { it.matchResult == ManganatoCsvParser.MatchResult.IMPORT_FAILED || it.matchResult == ManganatoCsvParser.MatchResult.CHAPTER_SYNC_FAILED }

        mutableState.update {
            it.copy(
                entries = entries,
                importImportedCount = imported,
                importExistedCount = existed,
                importProgressCount = progress,
                importFailedCount = failed,
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

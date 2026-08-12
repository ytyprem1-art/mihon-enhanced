package eu.kanade.tachiyomi.ui.mod.updatewatch

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Immutable
import androidx.lifecycle.viewModelScope
import eu.kanade.tachiyomi.util.lang.toJavaLocalDate
import androidx.compose.runtime.getValue
import eu.kanade.core.preference.asState
import eu.kanade.tachiyomi.ui.mod.updatewatch.worker.UpdateWatchRefreshScheduler
import eu.kanade.tachiyomi.ui.mod.updatewatch.worker.UpdateWatchRefreshState
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.system.logcat
import logcat.LogPriority
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.history.interactor.GetUpdateWatchHistory
import tachiyomi.domain.history.interactor.ManageUpdateWatch
import tachiyomi.domain.history.interactor.ManageUpdateWatchInbox
import tachiyomi.domain.history.interactor.ManageUpdateWatchHistory
import tachiyomi.domain.history.model.UpdateWatchInboxItem
import tachiyomi.domain.history.model.UpdateWatch
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import mihon.domain.source.interactor.UpdateMangaFromRemote
import tachiyomi.domain.history.model.UpdateWatchHistory
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.linked.interactor.ManageLinkedSourceGroup
import tachiyomi.domain.source.linked.model.LinkedSourceGroup
import tachiyomi.domain.source.service.SourceManager
import mihon.core.viewmodel.StateViewModel
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.time.LocalDate
import java.time.temporal.ChronoUnit

class UpdateWatchManagerViewModel(
    private val manageUpdateWatch: ManageUpdateWatch = Injekt.get(),
    private val manageUpdateWatchInbox: ManageUpdateWatchInbox = Injekt.get(),
    private val manageUpdateWatchHistory: ManageUpdateWatchHistory = Injekt.get(),
    private val getUpdateWatchHistory: GetUpdateWatchHistory = Injekt.get(),
    private val manageLinkedSourceGroup: ManageLinkedSourceGroup = Injekt.get(),
    private val getManga: GetManga = Injekt.get(),
    private val chapterRepository: ChapterRepository = Injekt.get(),
    private val updateMangaFromRemote: UpdateMangaFromRemote = Injekt.get(),
    private val getChaptersByMangaId: GetChaptersByMangaId = Injekt.get(),
    private val sourceManager: SourceManager = Injekt.get(),
    private val libraryPreferences: LibraryPreferences = Injekt.get(),
) : StateViewModel<UpdateWatchManagerViewModel.State>(State()) {

    val sortMode by libraryPreferences.trackedMangaSort.asState(viewModelScope)
    val funOverdueMessages by libraryPreferences.funOverdueMessages.asState(viewModelScope)
    val snackbarHostState = SnackbarHostState()

    init {
        viewModelScope.launchIO {
            val itemsFlow = manageUpdateWatch.subscribeAll()
                .flatMapLatest { trackedList ->
                    if (trackedList.isEmpty()) return@flatMapLatest flowOf(emptyList<UpdateWatchUiModel.Item>())

                    val flows = trackedList.map { tracking ->
                        val mangaId = tracking.mangaId
                        combine(
                            getManga.subscribe(mangaId),
                            chapterRepository.getChapterByMangaIdAsFlow(mangaId),
                            manageLinkedSourceGroup.subscribeGroupForManga(mangaId, 0L),
                            getUpdateWatchHistory.subscribeLatest5(mangaId),
                            UpdateWatchRefreshState.queuedMangaIds,
                        ) { manga, chapters, group, history, queuedIds ->
                            if (manga == null) return@combine null

                            val latestChapter = if (chapters.isNotEmpty()) {
                                chapters.filter { it.dateUpload > 0 }
                                    .maxByOrNull { it.dateUpload }
                            } else {
                                null
                            }

                            val daysSinceRelease = latestChapter?.let {
                                val today = LocalDate.now()
                                val releaseDate = it.dateUpload.toJavaLocalDate()
                                ChronoUnit.DAYS.between(releaseDate, today)
                            } ?: -1L

                            UpdateWatchUiModel.Item(
                                group = group,
                                trackingManga = manga,
                                latestChapter = latestChapter ?: Chapter.create().copy(chapterNumber = -1.0),
                                daysSinceRelease = daysSinceRelease,
                                backgroundRefreshEnabled = tracking.backgroundRefreshEnabled,
                                expectedIntervalDays = tracking.expectedIntervalDays,
                                refreshProfile = tracking.refreshProfile,
                                lastBackgroundCheckAt = tracking.lastBackgroundCheckAt,
                                refreshHistory = history,
                                isQueued = mangaId in queuedIds,
                            )
                        }
                    }
                    combine(flows) { it.filterNotNull() }
                }

            combine(
                itemsFlow,
                state.map { it.searchQuery }.distinctUntilChanged(),
                libraryPreferences.trackedMangaSort.changes(),
            ) { items, query, sort ->
                val sortedItems = when (sort) {
                    LibraryPreferences.TrackedMangaSort.NewestRelease -> items.sortedBy { it.daysSinceRelease }
                    LibraryPreferences.TrackedMangaSort.OldestRelease -> items.sortedByDescending { it.daysSinceRelease }
                    LibraryPreferences.TrackedMangaSort.TitleAZ -> items.sortedBy { it.trackingManga.title.lowercase() }
                    LibraryPreferences.TrackedMangaSort.TitleZA -> items.sortedByDescending { it.trackingManga.title.lowercase() }
                }

                if (query.isNullOrBlank()) return@combine sortedItems

                sortedItems.filter { item ->
                    val sourceName = sourceManager.getOrStub(item.trackingManga.source).name
                    item.trackingManga.title.contains(query, ignoreCase = true) ||
                        sourceName.contains(query, ignoreCase = true)
                }
            }.collectLatest { filteredItems ->
                mutableState.update { it.copy(items = filteredItems) }
            }
        }
    }

    fun updateSearchQuery(query: String?) {
        mutableState.update { it.copy(searchQuery = query) }
    }

    fun setSortMode(mode: LibraryPreferences.TrackedMangaSort) {
        libraryPreferences.trackedMangaSort.set(mode)
    }

    fun untrack(mangaId: Long) {
        viewModelScope.launchIO {
            try {
                manageUpdateWatch.delete(mangaId)
                UpdateWatchRefreshScheduler.setupTask(Injekt.get<android.app.Application>())
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e)
            }
        }
    }

    fun toggleFunOverdueMessages(enabled: Boolean) {
        libraryPreferences.funOverdueMessages.set(enabled)
    }

    fun updateBackgroundRefresh(mangaId: Long, enabled: Boolean, interval: Int, profile: UpdateWatch.RefreshProfile) {
        viewModelScope.launchIO {
            try {
                manageUpdateWatch.updateBackgroundRefresh(mangaId, enabled, interval, profile)
                UpdateWatchRefreshScheduler.setupTask(Injekt.get<android.app.Application>())
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e)
            }
        }
    }

    fun simulateInactivityWarning(item: UpdateWatchUiModel.Item, milestone: Int) {
        viewModelScope.launchIO {
            try {
                val source = sourceManager.getOrStub(item.trackingManga.source)
                val inboxItem = UpdateWatchInboxItem(
                    mangaId = item.trackingManga.id,
                    mangaTitle = item.trackingManga.title,
                    sourceId = source.id,
                    sourceName = source.name,
                    chapterCount = 0,
                    chapterRange = "",
                    firstFoundAt = System.currentTimeMillis(),
                    lastFoundAt = System.currentTimeMillis(),
                    latestChapterId = 0,
                    latestChapterNumber = 0.0,
                    chapterIds = emptyList(),
                    type = UpdateWatchInboxItem.TYPE_INACTIVITY_WARNING,
                    milestone = milestone
                )
                manageUpdateWatchInbox.insertOrMerge(inboxItem)
                snackbarHostState.showSnackbar("Simulated $milestone-day warning for ${item.trackingManga.title}")
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e)
            }
        }
    }

    fun simulateRealRefresh(item: UpdateWatchUiModel.Item) {
        viewModelScope.launchIO {
            val mangaId = item.trackingManga.id
            val startTime = System.currentTimeMillis()
            try {
                snackbarHostState.showSnackbar("Starting manual refresh for ${item.trackingManga.title}...")

                val oldChapters = chapterRepository.getChapterByMangaId(mangaId)
                val oldLatest = oldChapters.filter { it.dateUpload > 0 }.maxByOrNull { it.dateUpload }

                val refreshResult = updateMangaFromRemote(item.trackingManga, fetchChapters = true)

                if (refreshResult.isSuccess) {
                    val newChapters = refreshResult.getOrThrow().newChapters
                    val currentChapters = chapterRepository.getChapterByMangaId(mangaId)
                    val newLatest = currentChapters.filter { it.dateUpload > 0 }.maxByOrNull { it.dateUpload }

                    val foundNew = newChapters.isNotEmpty() || (newLatest != null && newLatest.id != oldLatest?.id)
                    val newCount = newChapters.size.coerceAtLeast(if (foundNew) 1 else 0)

                    if (foundNew && newLatest != null) {
                        val source = sourceManager.getOrStub(item.trackingManga.source)
                        val inboxItem = UpdateWatchInboxItem(
                            mangaId = mangaId,
                            mangaTitle = item.trackingManga.title,
                            sourceId = source.id,
                            sourceName = source.name,
                            chapterCount = newCount,
                            chapterRange = if (newCount > 1) "Ch. Multiple" else "Ch. ${newLatest.chapterNumber}",
                            firstFoundAt = startTime,
                            lastFoundAt = startTime,
                            latestChapterId = newLatest.id,
                            latestChapterNumber = newLatest.chapterNumber,
                            chapterIds = newChapters.map { it.id }.ifEmpty { listOf(newLatest.id) },
                            latestChapterUploadAt = newLatest.dateUpload,
                        )
                        manageUpdateWatchInbox.insertOrMerge(inboxItem)
                        manageUpdateWatch.resetStaleMilestone(mangaId)
                    }

                    manageUpdateWatch.updateLastBackgroundCheckAt(mangaId, startTime)
                    manageUpdateWatchHistory.insert(
                        UpdateWatchHistory(
                            mangaId = mangaId,
                            timestamp = startTime,
                            success = true,
                            newChapters = newCount,
                            category = UpdateWatchHistory.FailureCategory.NONE,
                            detail = null
                        )
                    )
                    snackbarHostState.showSnackbar("Refresh succeeded: found $newCount new chapters")
                } else {
                    val e = refreshResult.exceptionOrNull()
                    val detail = e?.message?.take(150)
                    manageUpdateWatch.updateLastBackgroundCheckAt(mangaId, startTime)
                    manageUpdateWatchHistory.insert(
                        UpdateWatchHistory(
                            mangaId = mangaId,
                            timestamp = startTime,
                            success = false,
                            newChapters = 0,
                            category = UpdateWatchHistory.FailureCategory.UNKNOWN,
                            detail = detail
                        )
                    )
                    snackbarHostState.showSnackbar("Refresh failed: $detail")
                }
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e)
                snackbarHostState.showSnackbar("Error: ${e.message}")
            }
        }
    }

    @Immutable
    data class State(
        val searchQuery: String? = null,
        val items: List<UpdateWatchUiModel.Item>? = null,
    )
}

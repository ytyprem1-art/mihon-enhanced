package eu.kanade.tachiyomi.ui.mod.updatewatch

import androidx.compose.runtime.Immutable
import androidx.lifecycle.viewModelScope
import eu.kanade.tachiyomi.ui.mod.updatewatch.helper.UpdateWatchRefreshHelper
import eu.kanade.tachiyomi.ui.mod.updatewatch.worker.UpdateWatchRefreshScheduler
import eu.kanade.tachiyomi.ui.mod.updatewatch.worker.UpdateWatchRefreshState
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.core.common.util.system.logcat
import logcat.LogPriority
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.history.interactor.ManageUpdateWatch
import tachiyomi.domain.history.interactor.GetUpdateWatchInbox
import tachiyomi.domain.history.interactor.ManageUpdateWatchInbox
import tachiyomi.domain.history.model.UpdateWatch
import tachiyomi.domain.history.model.UpdateWatchInboxItem
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.linked.interactor.ManageLinkedSourceGroup
import tachiyomi.domain.source.linked.model.LinkedSourceGroup
import tachiyomi.domain.history.model.UpdateWatchHistory
import mihon.core.viewmodel.StateViewModel
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.time.LocalDate
import java.time.temporal.ChronoUnit

class UpdateWatchViewModel(
    private val manageUpdateWatch: ManageUpdateWatch = Injekt.get(),
    private val manageLinkedSourceGroup: ManageLinkedSourceGroup = Injekt.get(),
    private val getManga: GetManga = Injekt.get(),
    private val chapterRepository: ChapterRepository = Injekt.get(),
    private val getUpdateWatchInbox: GetUpdateWatchInbox = Injekt.get(),
    private val manageUpdateWatchInbox: ManageUpdateWatchInbox = Injekt.get(),
    private val getChaptersByMangaId: GetChaptersByMangaId = Injekt.get(),
    private val libraryPreferences: LibraryPreferences = Injekt.get(),
) : StateViewModel<UpdateWatchViewModel.State>(State()) {

    init {
        viewModelScope.launchIO {
            libraryPreferences.notifyTrackedUpdates.changes()
                .collectLatest { enabled ->
                    mutableState.update { it.copy(notificationsEnabled = enabled) }
                }
        }
        viewModelScope.launchIO {
            libraryPreferences.funOverdueMessages.changes()
                .collectLatest { enabled ->
                    mutableState.update { it.copy(funOverdueMessages = enabled) }
                }
        }
        viewModelScope.launchIO {
            getUpdateWatchInbox.subscribe()
                .flatMapLatest { inboxItems ->
                    if (inboxItems.isEmpty()) return@flatMapLatest flowOf(emptyList<EnrichedUpdateWatchInboxItem>())

                    val flows = inboxItems.map { item ->
                        combine(
                            getManga.subscribe(item.mangaId),
                            chapterRepository.getChapterByMangaIdAsFlow(item.mangaId),
                        ) { manga, chapters ->
                            val latestChapter = chapters.find { it.id == item.latestChapterId }
                                ?: chapters.filter { it.dateUpload > 0 }.maxByOrNull { it.dateUpload }

                            EnrichedUpdateWatchInboxItem(
                                item = item,
                                manga = manga,
                                latestChapter = latestChapter,
                            )
                        }
                    }
                    combine(flows) { it.toList() }
                }
                .collectLatest { enrichedItems ->
                    mutableState.update { it.copy(enrichedInboxItems = enrichedItems) }

                    // Auto-cleanup: remove item if all TRACKED chapters are read
                    enrichedItems.forEach { enriched ->
                        val item = enriched.item
                        if (item.type != UpdateWatchInboxItem.TYPE_UPDATE) return@forEach

                        val mangaId = item.mangaId
                        val allChapters = getChaptersByMangaId.await(mangaId)

                        // Check only the chapters that were marked as new in the inbox
                        val trackedChapters = allChapters.filter { it.id in item.chapterIds }

                        // If we have no info on tracked chapters (they might have been deleted from DB),
                        // we'll keep it or delete if all remaining are read.
                        // Requirement: "remove the record only after all merged detected chapters are read"
                        // If some chapters are missing from DB, we treat them as "not unread".
                        if (trackedChapters.isNotEmpty() && trackedChapters.all { it.read }) {
                            manageUpdateWatchInbox.delete(mangaId)
                        } else if (trackedChapters.isEmpty() && allChapters.isNotEmpty() && allChapters.all { it.read }) {
                            // Fallback if tracked chapters are gone from DB but everything else is read
                            manageUpdateWatchInbox.delete(mangaId)
                        }
                    }
                }
        }
        viewModelScope.launchIO {
            manageUpdateWatch.subscribeAll()
                .flatMapLatest { trackedList ->
                    val activeTracking = trackedList.filter { !it.isPaused }
                    if (activeTracking.isEmpty()) return@flatMapLatest flowOf(emptyList<UpdateWatchUiModel>())

                    val flows = activeTracking.map { tracking ->
                        val mangaId = tracking.mangaId
                        combine(
                            getManga.subscribe(mangaId),
                            chapterRepository.getChapterByMangaIdAsFlow(mangaId),
                            manageLinkedSourceGroup.subscribeGroupForManga(mangaId, 0L),
                            UpdateWatchRefreshState.queuedMangaIds,
                        ) { manga, chapters, group, queuedIds ->
                            if (manga == null || chapters.isEmpty()) return@combine null

                            val latestChapter = chapters.filter { it.dateUpload > 0 }
                                .maxByOrNull { it.dateUpload } ?: return@combine null

                            val eligibility = UpdateWatchRefreshHelper.getEligibility(
                                enabled = tracking.backgroundRefreshEnabled,
                                expectedIntervalDays = tracking.expectedIntervalDays,
                                refreshProfile = tracking.refreshProfile,
                                latestChapterUploadDate = latestChapter.dateUpload,
                                lastCheckAt = tracking.lastBackgroundCheckAt,
                                now = System.currentTimeMillis(),
                            )

                            val isVisible = eligibility.ageDays >= (tracking.expectedIntervalDays - 1)

                            if (isVisible) {
                                UpdateWatchUiModel.Item(
                                    group = group,
                                    trackingManga = manga,
                                    latestChapter = latestChapter,
                                    daysSinceRelease = eligibility.ageDays,
                                    backgroundRefreshEnabled = tracking.backgroundRefreshEnabled,
                                    expectedIntervalDays = tracking.expectedIntervalDays,
                                    refreshProfile = tracking.refreshProfile,
                                    lastBackgroundCheckAt = tracking.lastBackgroundCheckAt,
                                    isQueued = mangaId in queuedIds,
                                )
                            } else {
                                null
                            }
                        }
                    }
                    combine(flows) { it.filterNotNull() }.map { items ->
                        val upcoming = items.filter { it.daysSinceRelease == it.expectedIntervalDays.toLong() - 1 }
                        val dueToday = items.filter { it.daysSinceRelease == it.expectedIntervalDays.toLong() }
                        val overdue = items.filter { it.daysSinceRelease > it.expectedIntervalDays }

                        mutableListOf<UpdateWatchUiModel>().apply {
                            if (upcoming.isNotEmpty()) {
                                add(UpdateWatchUiModel.Header("Upcoming"))
                                addAll(upcoming)
                            }
                            if (dueToday.isNotEmpty()) {
                                add(UpdateWatchUiModel.Header("Due today"))
                                addAll(dueToday)
                            }
                            if (overdue.isNotEmpty()) {
                                add(UpdateWatchUiModel.Header("Overdue"))
                                addAll(overdue.sortedBy { it.daysSinceRelease })
                            }
                        }
                    }
                }
                .collectLatest { items ->
                    mutableState.update { it.copy(items = items) }
                }
        }
    }

    fun pauseTracking(mangaId: Long) {
        viewModelScope.launchIO {
            try {
                manageUpdateWatch.updatePaused(mangaId, true)
                UpdateWatchRefreshScheduler.setupTask(Injekt.get<android.app.Application>())
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e)
            }
        }
    }

    fun dismissInboxItem(mangaId: Long) {
        viewModelScope.launchIO {
            manageUpdateWatchInbox.delete(mangaId)
        }
    }

    fun disableAutoRefresh(mangaId: Long) {
        viewModelScope.launchIO {
            try {
                val tracking = manageUpdateWatch.getById(mangaId)
                if (tracking != null) {
                    manageUpdateWatch.updateBackgroundRefresh(
                        mangaId,
                        enabled = false,
                        interval = tracking.expectedIntervalDays,
                        profile = tracking.refreshProfile
                    )
                    UpdateWatchRefreshScheduler.setupTask(Injekt.get<android.app.Application>())
                }
                manageUpdateWatchInbox.delete(mangaId)
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e)
            }
        }
    }

    fun toggleNotifications(enabled: Boolean) {
        libraryPreferences.notifyTrackedUpdates.set(enabled)
    }

    fun triggerInboxSheet() {
        mutableState.update { it.copy(showInboxOnLoad = true) }
    }

    fun clearInboxLoadTrigger() {
        mutableState.update { it.copy(showInboxOnLoad = false) }
    }

    @Immutable
    data class State(
        val items: List<UpdateWatchUiModel>? = null,
        val enrichedInboxItems: List<EnrichedUpdateWatchInboxItem> = emptyList(),
        val notificationsEnabled: Boolean = false,
        val showInboxOnLoad: Boolean = false,
        val funOverdueMessages: Boolean = false,
    )
}

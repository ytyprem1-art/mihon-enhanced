package eu.kanade.tachiyomi.ui.browse.source.linked

import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import eu.kanade.tachiyomi.ui.manga.LinkedMember
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import logcat.LogPriority
import mihon.core.viewmodel.StateViewModel
import mihon.domain.source.interactor.UpdateMangaFromRemote
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.history.group.interactor.ManageHistoryGroups
import tachiyomi.domain.history.repository.HistoryRepository
import tachiyomi.domain.history.interactor.ManageUpdateWatch
import tachiyomi.domain.source.linked.interactor.ManageLinkedSourceGroup
import tachiyomi.domain.source.linked.model.LinkedSourceGroup
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class LinkedSourceDetailsViewModel(
    private val groupId: Long,
    private val manageLinkedSourceGroup: ManageLinkedSourceGroup = Injekt.get(),
    private val chapterRepository: ChapterRepository = Injekt.get(),
    private val historyRepository: HistoryRepository = Injekt.get(),
    private val updateMangaFromRemote: UpdateMangaFromRemote = Injekt.get(),
    private val sourceManager: SourceManager = Injekt.get(),
    private val manageHistoryGroups: ManageHistoryGroups = Injekt.get(),
    private val manageUpdateWatch: ManageUpdateWatch = Injekt.get(),
) : StateViewModel<LinkedSourceDetailsViewModel.State>(State()) {

    companion object {
        val GROUP_ID_KEY = CreationExtras.Key<Long>()

        val Factory = viewModelFactory {
            initializer {
                LinkedSourceDetailsViewModel(
                    groupId = get(GROUP_ID_KEY)!!,
                )
            }
        }
    }

    private val _events = kotlinx.coroutines.channels.Channel<Event>(kotlinx.coroutines.channels.Channel.UNLIMITED)
    val events = _events.receiveAsFlow()

    init {
        viewModelScope.launchIO {
            val group = manageLinkedSourceGroup.getGroupById(groupId)
            mutableState.update { it.copy(group = group) }
        }

        viewModelScope.launchIO {
            combine(
                manageLinkedSourceGroup.subscribeMembers(groupId),
                manageUpdateWatch.subscribeAll(),
            ) { members, trackedList ->
                members to trackedList.associateBy { it.mangaId }
            }.flatMapLatest { (members, trackingMap) ->
                if (members.isEmpty()) return@flatMapLatest kotlinx.coroutines.flow.flowOf(emptyList<LinkedMember>())

                val flows = members.map { member ->
                    combine(
                        chapterRepository.getChapterByMangaIdAsFlow(member.id),
                        historyRepository.getLatestHistoryByMangaIdAsFlow(member.id),
                    ) { chapters, history ->
                        val latestChapter = chapters.maxByOrNull { it.chapterNumber }
                        LinkedMember(
                            manga = member,
                            latestChapter = latestChapter?.chapterNumber,
                            latestChapterId = latestChapter?.id,
                            latestChapterDateUpload = latestChapter?.dateUpload?.takeIf { it > 0 },
                            lastRead = history?.chapterNumber,
                            lastReadChapterId = history?.chapterId,
                            readAt = history?.readAt?.time,
                            isTracking = trackingMap.containsKey(member.id),
                            isPaused = trackingMap[member.id]?.isPaused ?: false,
                        )
                    }
                }
                combine(flows) { it.toList() }
            }.collectLatest { members ->
                mutableState.update { it.copy(members = members) }
            }
        }
    }

    fun refreshMember(mangaId: Long) {
        if (state.value.refreshingIds.contains(mangaId)) return

        viewModelScope.launchIO {
            mutableState.update { it.copy(refreshingIds = it.refreshingIds + mangaId) }
            try {
                val manga = manageLinkedSourceGroup.subscribeMembers(groupId).map { members ->
                    members.find { it.id == mangaId }
                }.flatMapLatest { manga ->
                    if (manga == null) kotlinx.coroutines.flow.flowOf(null)
                    else kotlinx.coroutines.flow.flowOf(manga)
                }.firstOrNull() ?: return@launchIO

                val source = sourceManager.getOrStub(manga.source)
                updateMangaFromRemote(
                    source = source,
                    manga = manga,
                    fetchDetails = true,
                    fetchChapters = true,
                    manualFetch = true,
                ).getOrThrow()
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e)
            } finally {
                mutableState.update { it.copy(refreshingIds = it.refreshingIds - mangaId) }
            }
        }
    }

    fun removeMember(mangaId: Long, sourceId: Long) {
        viewModelScope.launchIO {
            try {
                manageLinkedSourceGroup.removeMember(groupId, mangaId, sourceId)
                manageUpdateWatch.delete(mangaId)
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e)
            }
        }
    }

    fun toggleTracking(mangaId: Long) {
        // Optimistic update
        mutableState.update { state ->
            state.copy(
                members = state.members.map {
                    if (it.manga.id == mangaId) it.copy(isTracking = !it.isTracking) else it
                }
            )
        }

        viewModelScope.launchIO {
            try {
                val current = manageUpdateWatch.getById(mangaId)
                if (current != null) {
                    manageUpdateWatch.delete(mangaId)
                } else {
                    manageUpdateWatch.updatePaused(mangaId, false)
                }
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e)
                _events.send(Event.ShowMessage(e.message ?: "Unknown error"))
                // Revert optimistic update
                mutableState.update { state ->
                    state.copy(
                        members = state.members.map {
                            if (it.manga.id == mangaId) it.copy(isTracking = !it.isTracking) else it
                        }
                    )
                }
            }
        }
    }

    fun togglePaused(mangaId: Long) {
        viewModelScope.launchIO {
            val current = manageUpdateWatch.getById(mangaId) ?: return@launchIO
            manageUpdateWatch.updatePaused(mangaId, !current.isPaused)
        }
    }

    fun updateTrackingSource(mangaId: Long?) {
        if (mangaId == null) return
        toggleTracking(mangaId)
    }

    fun showTrackingSourcePicker() {
        mutableState.update { it.copy(dialog = Dialog.TrackingSourcePicker) }
    }

    fun createHistoryGroup() {
        val members = state.value.members
        val groupName = state.value.group?.name ?: return

        viewModelScope.launchIO {
            val memberships = manageHistoryGroups.getAllMemberships()

            val withHistory = members.filter { it.lastRead != null }
            val withoutHistory = members.filter { it.lastRead == null }

            // Members in another history group
            val inOtherGroup = withHistory.filter { memberships.containsKey(it.manga.id) }
            // Members with history and NOT in another group
            val trulyEligible = withHistory.filter { !memberships.containsKey(it.manga.id) }

            if (withoutHistory.isNotEmpty() || inOtherGroup.isNotEmpty() || trulyEligible.size < 2) {
                mutableState.update { it.copy(dialog = Dialog.CreateHistoryGroupWarning(withoutHistory, inOtherGroup, trulyEligible)) }
            } else {
                performCreateHistoryGroup(groupName, trulyEligible.map { it.manga.id })
            }
        }
    }

    fun performCreateHistoryGroup(name: String, mangaIds: List<Long>) {
        viewModelScope.launchIO {
            try {
                manageHistoryGroups.createGroupWithMembers(name, mangaIds)
                _events.send(Event.HistoryGroupCreated)
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e)
                _events.send(Event.ShowMessage(e.message ?: "Unknown error"))
            } finally {
                dismissDialog()
            }
        }
    }

    fun dismissDialog() {
        mutableState.update { it.copy(dialog = null) }
    }

    @androidx.compose.runtime.Immutable
    data class State(
        val group: LinkedSourceGroup? = null,
        val members: List<LinkedMember> = emptyList(),
        val refreshingIds: Set<Long> = emptySet(),
        val dialog: Dialog? = null,
    )

    sealed interface Dialog {
        data class CreateHistoryGroupWarning(
            val withoutHistory: List<LinkedMember>,
            val inOtherGroup: List<LinkedMember>,
            val eligible: List<LinkedMember>
        ) : Dialog

        data object TrackingSourcePicker : Dialog
    }

    sealed interface Event {
        data object HistoryGroupCreated : Event
        data class ShowMessage(val message: String) : Event
    }
}

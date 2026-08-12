package eu.kanade.tachiyomi.ui.history

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eu.kanade.core.util.insertSeparators
import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.domain.track.interactor.AddTracks
import eu.kanade.presentation.history.HistoryUiModel
import eu.kanade.tachiyomi.util.lang.toLocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import logcat.LogPriority
import tachiyomi.core.common.preference.CheckboxState
import tachiyomi.core.common.preference.mapAsCheckboxState
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.interactor.SetMangaCategories
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.history.interactor.GetHistory
import tachiyomi.domain.history.interactor.GetNextChapters
import tachiyomi.domain.history.interactor.ManageHistoryCategory
import tachiyomi.domain.history.group.interactor.ManageHistoryGroups
import tachiyomi.domain.history.interactor.RemoveHistory
import tachiyomi.domain.history.model.HistoryWithRelations
import tachiyomi.domain.history.repository.HistoryCategory
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetDuplicateLibraryManga
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaWithChapterCount
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlin.time.Duration.Companion.seconds

class HistoryViewModel(
    private val addTracks: AddTracks = Injekt.get(),
    private val getCategories: GetCategories = Injekt.get(),
    private val getDuplicateLibraryManga: GetDuplicateLibraryManga = Injekt.get(),
    private val getHistory: GetHistory = Injekt.get(),
    val getManga: GetManga = Injekt.get(),
    private val getNextChapters: GetNextChapters = Injekt.get(),
    private val libraryPreferences: LibraryPreferences = Injekt.get(),
    private val removeHistory: RemoveHistory = Injekt.get(),
    private val setMangaCategories: SetMangaCategories = Injekt.get(),
    private val updateManga: UpdateManga = Injekt.get(),
    private val manageHistoryCategory: ManageHistoryCategory = Injekt.get(),
    private val manageHistoryGroups: ManageHistoryGroups = Injekt.get(),
    val snackbarHostState: SnackbarHostState = SnackbarHostState(),
    private val sourceManager: SourceManager = Injekt.get(),
) : ViewModel() {

    private val _events: Channel<Event> = Channel(Channel.UNLIMITED)
    val events: Flow<Event> = _events.receiveAsFlow()

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    init {
        // Coroutine 1: Daftar kategori untuk Tab Row
        viewModelScope.launchIO {
            manageHistoryCategory.subscribe()
                .collect { categories ->
                    _state.update { it.copy(historyCategories = categories) }
                }
        }

        // Coroutine 2: Mengambil list riwayat mentah + keanggotaan grup
        viewModelScope.launchIO {
            _state.map { it.searchQuery }.distinctUntilChanged()
                .flatMapLatest { query ->
                    getHistory.subscribe(query ?: "")
                }
                .combine(manageHistoryGroups.subscribe()) { historyList, groups ->
                    historyList to groups
                }
                .combine(manageHistoryGroups.subscribeAllMemberships()) { (historyList, groups), memberships ->
                    Triple(historyList, groups, memberships)
                }
                .flowOn(Dispatchers.IO)
                .collect { (list, groups, memberships) ->
                    val uiModels = processHistoryList(list, groups, memberships)

                    // Update state list beserta map kategorinya
                    val mangaIds = list.map { it.mangaId }.distinct()
                    val localMap = mutableMapOf<Long, Long>()
                    for (mId in mangaIds) {
                        localMap[mId] = manageHistoryCategory.getMangaCategory(mId) ?: 0L
                    }

                    _state.update {
                        it.copy(
                            list = uiModels,
                            mangaToCategoryMap = localMap
                        )
                    }
                }
        }
    }

    private fun processHistoryList(
        list: List<HistoryWithRelations>,
        groups: List<tachiyomi.domain.history.group.model.HistoryGroup>,
        memberships: Map<Long, Long>,
    ): List<HistoryUiModel> {
        val processedItems = mutableListOf<HistoryUiModel>()
        val groupedMangaHandled = mutableSetOf<Long>()

        for (item in list) {
            val groupId = memberships[item.mangaId]
            if (groupId == null) {
                processedItems.add(HistoryUiModel.Item(item))
            } else {
                if (groupId in groupedMangaHandled) continue

                // Find all group members in the current history list
                val group = groups.find { it.id == groupId } ?: continue
                val groupMembers = list.filter { memberships[it.mangaId] == groupId }

                // The list is sorted by readAt DESC, so the first one is the representative
                val representative = groupMembers.first()

                processedItems.add(
                    HistoryUiModel.Group(
                        group = group,
                        representative = representative,
                        memberCount = groupMembers.size,
                        sourceNames = groupMembers.map {
                            sourceManager.getOrStub(it.coverData.sourceId).name
                        }
                    )
                )
                groupedMangaHandled.add(groupId)
            }
        }

        return processedItems.insertSeparators { before, after ->
            val beforeDate = when (before) {
                is HistoryUiModel.Item -> before.item.readAt?.time?.toLocalDate()
                is HistoryUiModel.Group -> before.representative.readAt?.time?.toLocalDate()
                else -> null
            }
            val afterDate = when (after) {
                is HistoryUiModel.Item -> after.item.readAt?.time?.toLocalDate()
                is HistoryUiModel.Group -> after.representative.readAt?.time?.toLocalDate()
                else -> null
            }
            when {
                beforeDate != afterDate && afterDate != null -> HistoryUiModel.Header(afterDate)
                else -> null
            }
        }
    }

    suspend fun getNextChapter(): Chapter? {
        return withIOContext { getNextChapters.await(onlyUnread = false).firstOrNull() }
    }

    fun getNextChapterForManga(mangaId: Long, chapterId: Long) {
        viewModelScope.launchIO {
            sendNextChapterEvent(getNextChapters.await(mangaId, chapterId, onlyUnread = false))
        }
    }

    private suspend fun sendNextChapterEvent(chapters: List<Chapter>) {
        val chapter = chapters.firstOrNull()
        _events.send(Event.OpenChapter(chapter))
    }

    fun removeFromHistory(history: HistoryWithRelations) {
        viewModelScope.launchIO {
            removeHistory.await(history)
        }
    }

    fun removeAllFromHistory(mangaId: Long) {
        viewModelScope.launchIO {
            removeHistory.await(mangaId)
        }
    }

    fun removeSelectedFromHistory(mangaIds: Set<Long>) {
        viewModelScope.launchIO {
            mangaIds.forEach { mangaId ->
                removeHistory.await(mangaId)
            }
            _state.update { state ->
                state.copy(
                    selectionMode = false,
                    selected = emptySet(),
                )
            }
        }
    }

    fun removeAllHistory() {
        viewModelScope.launchIO {
            val result = removeHistory.awaitAll()
            if (!result) return@launchIO
            _events.send(Event.HistoryCleared)
        }
    }

    fun updateSearchQuery(query: String?) {
        _state.update { it.copy(searchQuery = query) }
    }

    fun toggleSelectionMode() {
        _state.update { it.copy(selectionMode = !it.selectionMode, selected = emptySet()) }
    }

    fun toggleSelection(mangaId: Long) {
        _state.update { state ->
            val newSelected = state.selected.toMutableSet().apply {
                if (contains(mangaId)) remove(mangaId) else add(mangaId)
            }
            state.copy(selected = newSelected)
        }
    }

    fun selectAll(mangaIds: List<Long>) {
        _state.update { it.copy(selected = mangaIds.toSet()) }
    }

    fun invertSelection(mangaIds: List<Long>) {
        _state.update { state ->
            val newSelected = mangaIds.filter { it !in state.selected }.toSet()
            state.copy(selected = newSelected)
        }
    }

    fun updateSelectedCategory(categoryId: Long) {
        _state.update { it.copy(selectedCategoryId = categoryId) }
    }

    fun showChangeHistoryCategoryDialog(mangaId: Long) {
        viewModelScope.launchIO {
            val categories = manageHistoryCategory.subscribe().first()
            val currentCategoryId = manageHistoryCategory.getMangaCategory(mangaId) ?: 0L
            _state.update { currentState ->
                currentState.copy(
                    dialog = Dialog.ChangeHistoryCategory(
                        mangaId = mangaId,
                        categories = categories,
                        initialSelection = currentCategoryId,
                    ),
                )
            }
        }
    }

    fun showAddToHistoryGroupDialog(mangaId: Long) {
        viewModelScope.launchIO {
            val groups = manageHistoryGroups.getGroups()
            _state.update { it.copy(dialog = Dialog.AddToHistoryGroup(mangaId, groups)) }
        }
    }

    fun moveMangaToHistoryCategory(mangaId: Long, categoryId: Long) {
        viewModelScope.launchIO {
            manageHistoryCategory.moveToCategory(mangaId, categoryId)
            _state.update { currentState ->
                val newMap = currentState.mangaToCategoryMap.toMutableMap()
                newMap[mangaId] = categoryId
                currentState.copy(mangaToCategoryMap = newMap)
            }
        }
    }

    fun moveSelectedToHistoryCategory(mangaIds: Set<Long>, categoryId: Long) {
        viewModelScope.launchIO {
            mangaIds.forEach { mangaId ->
                manageHistoryCategory.moveToCategory(mangaId, categoryId)
            }
            _state.update { currentState ->
                val newMap = currentState.mangaToCategoryMap.toMutableMap()
                mangaIds.forEach { newMap[it] = categoryId }
                currentState.copy(
                    mangaToCategoryMap = newMap,
                    selectionMode = false,
                    selected = emptySet(),
                )
            }
        }
    }

    fun createHistoryCategory(name: String) {
        viewModelScope.launch {
            manageHistoryCategory.create(name)
            val updatedCategories = manageHistoryCategory.subscribe().first()
            _state.update { it.copy(historyCategories = updatedCategories) }
        }
    }

    fun createHistoryGroup(name: String, mangaIds: Set<Long>) {
        if (name.isBlank()) return
        viewModelScope.launchIO {
            try {
                manageHistoryGroups.createGroupWithMembers(name, mangaIds.toList())
                _state.update { state ->
                    state.copy(
                        selectionMode = false,
                        selected = emptySet(),
                        dialog = null,
                    )
                }
                _events.send(Event.HistoryGroupCreated)
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e)
                _events.send(Event.Error(e.message ?: "Unknown error"))
            }
        }
    }

    fun addMangaToHistoryGroup(mangaId: Long, groupId: Long) {
        viewModelScope.launchIO {
            try {
                manageHistoryGroups.assignMangaToGroup(mangaId, groupId)
                _state.update { state ->
                    state.copy(
                        selectionMode = false,
                        selected = emptySet(),
                        dialog = null,
                    )
                }
                _events.send(Event.AddedToHistoryGroup)
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e)
                _events.send(Event.Error(e.message ?: "Unknown error"))
            }
        }
    }

    fun deleteHistoryGroup(id: Long) {
        viewModelScope.launchIO {
            try {
                manageHistoryGroups.deleteGroup(id)
                _state.update { it.copy(dialog = null) }
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e)
                _events.send(Event.Error(e.message ?: "Unknown error"))
            }
        }
    }

    fun renameHistoryGroup(id: Long, name: String) {
        if (name.isBlank()) return
        viewModelScope.launchIO {
            try {
                manageHistoryGroups.renameGroup(id, name)
                _state.update { it.copy(dialog = null) }
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e)
                _events.send(Event.Error(e.message ?: "Unknown error"))
            }
        }
    }

    fun renameHistoryCategory(id: Long, name: String) {
        viewModelScope.launch {
            manageHistoryCategory.rename(id, name)
            val updatedCategories = manageHistoryCategory.subscribe().first()
            _state.update { it.copy(historyCategories = updatedCategories) }
        }
    }

    fun moveHistoryCategoryLeft(category: HistoryCategory) {
        viewModelScope.launch {
            manageHistoryCategory.moveLeft(category)
            val updatedCategories = manageHistoryCategory.subscribe().first()
            _state.update { it.copy(historyCategories = updatedCategories) }
        }
    }

    fun moveHistoryCategoryRight(category: HistoryCategory) {
        viewModelScope.launch {
            manageHistoryCategory.moveRight(category)
            val updatedCategories = manageHistoryCategory.subscribe().first()
            _state.update { it.copy(historyCategories = updatedCategories) }
        }
    }

    fun deleteHistoryCategory(id: Long) {
        viewModelScope.launch {
            manageHistoryCategory.delete(id)
            val updatedCategories = manageHistoryCategory.subscribe().first()
            _state.update { currentState ->
                val newSelectedId = if (currentState.selectedCategoryId == id) 0L else currentState.selectedCategoryId
                val newMap = currentState.mangaToCategoryMap.mapValues { (_, v) ->
                    if (v == id) 0L else v
                }
                currentState.copy(
                    historyCategories = updatedCategories,
                    selectedCategoryId = newSelectedId,
                    mangaToCategoryMap = newMap
                )
            }
        }
    }

    fun setDialog(dialog: Dialog?) {
        _state.update { it.copy(dialog = dialog) }
    }

    suspend fun getCategories(): List<Category> {
        return getCategories.await().filterNot { it.isSystemCategory }
    }

    private fun moveMangaToCategory(mangaId: Long, categories: Category?) {
        val categoryIds = listOfNotNull(categories).map { it.id }
        moveMangaToCategory(mangaId, categoryIds)
    }

    private fun moveMangaToCategory(mangaId: Long, categoryIds: List<Long>) {
        viewModelScope.launchIO {
            setMangaCategories.await(mangaId, categoryIds)
        }
    }

    fun moveMangaToCategoriesAndAddToLibrary(manga: Manga, categories: List<Long>) {
        moveMangaToCategory(manga.id, categories)
        if (manga.favorite) return

        viewModelScope.launchIO {
            updateManga.awaitUpdateFavorite(manga.id, true)
        }
    }

    private suspend fun getMangaCategoryIds(manga: Manga): List<Long> {
        return getCategories.await(manga.id)
            .map { it.id }
    }

    fun addFavorite(mangaId: Long) {
        viewModelScope.launchIO {
            val manga = getManga.await(mangaId) ?: return@launchIO

            val duplicates = getDuplicateLibraryManga(manga)
            if (duplicates.isNotEmpty()) {
                setDialog(Dialog.DuplicateManga(manga, duplicates))
                return@launchIO
            }

            addFavorite(manga)
        }
    }

    fun addFavorite(manga: Manga) {
        viewModelScope.launchIO {
            val categories = getCategories()
            val defaultCategoryId = libraryPreferences.defaultCategory.get().toLong()
            val defaultCategory = categories.find { it.id == defaultCategoryId }

            when {
                defaultCategory != null -> {
                    val result = updateManga.awaitUpdateFavorite(manga.id, true)
                    if (!result) return@launchIO
                    moveMangaToCategory(manga.id, defaultCategory)
                }
                defaultCategoryId == 0L || categories.isEmpty() -> {
                    val result = updateManga.awaitUpdateFavorite(manga.id, true)
                    if (!result) return@launchIO
                    moveMangaToCategory(manga.id, null)
                }
                else -> showChangeCategoryDialog(manga)
            }

            addTracks.bindEnhancedTrackers(manga, sourceManager.getOrStub(manga.source))
        }
    }

    fun showMigrateDialog(target: Manga, current: Manga) {
        setDialog(Dialog.Migrate(target = target, current = current))
    }

    fun showChangeCategoryDialog(manga: Manga) {
        viewModelScope.launch {
            val categories = getCategories()
            val selection = getMangaCategoryIds(manga)
            setDialog(
                Dialog.ChangeCategory(
                    manga = manga,
                    initialSelection = categories.mapAsCheckboxState { it.id in selection },
                )
            )
        }
    }

    private suspend fun Flow<List<HistoryWithRelations>>.combine(
        flow: Flow<List<tachiyomi.domain.history.group.model.HistoryGroup>>
    ): Flow<Pair<List<HistoryWithRelations>, List<tachiyomi.domain.history.group.model.HistoryGroup>>> {
        return combine(this, flow) { a, b -> a to b }
    }

    @Immutable
    data class State(
        val searchQuery: String? = null,
        val list: List<HistoryUiModel>? = null,
        val dialog: Dialog? = null,
        val historyCategories: List<HistoryCategory> = emptyList(),
        val selectedCategoryId: Long = 0L,
        val mangaToCategoryMap: Map<Long, Long> = emptyMap(), // Cache peta kategori memori UI
        val selectionMode: Boolean = false,
        val selected: Set<Long> = emptySet(),
    )

    sealed interface Dialog {
        data object DeleteAll : Dialog
        data class Delete(val history: HistoryWithRelations) : Dialog
        data class DeleteSelected(val mangaIds: Set<Long>) : Dialog
        data object CreateHistoryCategory : Dialog
        data class ManageHistoryCategory(val category: HistoryCategory) : Dialog
        data class RenameHistoryCategory(val category: HistoryCategory) : Dialog
        data class DeleteHistoryCategory(val category: HistoryCategory) : Dialog
        data class ChangeHistoryCategory(
            val mangaId: Long,
            val categories: List<HistoryCategory>,
            val initialSelection: Long,
        ) : Dialog
        data class MoveSelectedToHistoryCategory(
            val mangaIds: Set<Long>,
            val categories: List<HistoryCategory>,
        ) : Dialog
        data class CreateHistoryGroup(val mangaIds: Set<Long>, val suggestedName: String) : Dialog
        data class RenameHistoryGroup(val group: tachiyomi.domain.history.group.model.HistoryGroup) : Dialog
        data class DeleteHistoryGroup(val group: tachiyomi.domain.history.group.model.HistoryGroup) : Dialog
        data class AddToHistoryGroup(val mangaId: Long, val groups: List<tachiyomi.domain.history.group.model.HistoryGroup>) : Dialog

        data class DuplicateManga(val manga: Manga, val duplicates: List<MangaWithChapterCount>) : Dialog
        data class ChangeCategory(
            val manga: Manga,
            val initialSelection: List<CheckboxState<Category>>,
        ) : Dialog
        data class Migrate(val target: Manga, val current: Manga) : Dialog
    }

    sealed interface Event {
        data class OpenChapter(val chapter: Chapter?) : Event
        data object InternalError : Event
        data object HistoryCleared : Event
        data object HistoryGroupCreated : Event
        data object AddedToHistoryGroup : Event
        data class Error(val message: String) : Event
    }
}

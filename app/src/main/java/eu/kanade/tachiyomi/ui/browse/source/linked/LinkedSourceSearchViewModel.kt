package eu.kanade.tachiyomi.ui.browse.source.linked

import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import androidx.lifecycle.viewModelScope
import eu.kanade.tachiyomi.ui.browse.source.globalsearch.SearchViewModel
import eu.kanade.tachiyomi.ui.browse.source.globalsearch.SearchItemResult
import eu.kanade.tachiyomi.ui.browse.source.globalsearch.SourceFilter
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.linked.interactor.ManageLinkedSourceGroup

@dev.zacsweers.metro.AssistedInject
class LinkedSourceSearchViewModel(
    @dev.zacsweers.metro.Assisted val linkedGroupId: Long,
    @dev.zacsweers.metro.Assisted initialQuery: String,
    private val manageLinkedSourceGroup: ManageLinkedSourceGroup,
    sourcePreferences: eu.kanade.domain.source.service.SourcePreferences,
    sourceManager: tachiyomi.domain.source.service.SourceManager,
    extensionManager: eu.kanade.tachiyomi.extension.ExtensionManager,
    networkToLocalManga: tachiyomi.domain.manga.interactor.NetworkToLocalManga,
    getManga: tachiyomi.domain.manga.interactor.GetManga,
) : SearchViewModel(
    State(searchQuery = initialQuery),
    sourcePreferences = sourcePreferences, sourceManager = sourceManager, extensionManager = extensionManager,
    networkToLocalManga = networkToLocalManga, getManga = getManga, preferences = sourcePreferences,
) {
    @dev.zacsweers.metro.AssistedFactory
    @dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactoryKey
    @dev.zacsweers.metro.ContributesIntoMap(dev.zacsweers.metro.AppScope::class)
    interface Factory : dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactory {
        fun create(linkedGroupId: Long, initialQuery: String): LinkedSourceSearchViewModel
    }

    init {
        // Default to All sources for this specific search flow
        setSourceFilter(SourceFilter.All)

        extensionFilter = null // initialExtensionFilter logic if needed

        if (initialQuery.isNotBlank()) {
            search()
        }

        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            state
                .map { it.items }
                .distinctUntilChanged()
                .collectLatest { items ->
                    val searchResults = items.values
                        .filterIsInstance<SearchItemResult.Success>()
                        .flatMap { it.result }

                    if (searchResults.isNotEmpty()) {
                        val memberships = mutableMapOf<Long, Long?>()
                        for (manga in searchResults) {
                            val group = manageLinkedSourceGroup.getGroupForManga(manga.id, manga.source)
                            memberships[manga.id] = group?.id
                        }
                        updateMemberships(memberships)
                    }
                }
        }
    }

    private fun updateMemberships(memberships: Map<Long, Long?>) {
        updateState { it.copy(mangaGroupIds = memberships) }
    }

    fun addMangaToGroup(manga: Manga, onResult: (AddResult) -> Unit) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val existingGroupId = manageLinkedSourceGroup.getGroupForManga(manga.id, manga.source)?.id
            if (existingGroupId != null) {
                withContext(kotlinx.coroutines.Dispatchers.Main) {
                    if (existingGroupId == linkedGroupId) {
                        onResult(AddResult.AlreadyInThisGroup)
                    } else {
                        val group = manageLinkedSourceGroup.getGroupById(existingGroupId)
                        onResult(AddResult.InAnotherGroup(group?.name ?: "Unknown"))
                    }
                }
                return@launch
            }

            try {
                manageLinkedSourceGroup.joinGroup(linkedGroupId, manga.id, manga.source)
                // Update local state immediately
                val currentMemberships = state.value.mangaGroupIds.toMutableMap()
                currentMemberships[manga.id] = linkedGroupId
                updateMemberships(currentMemberships)
                withContext(kotlinx.coroutines.Dispatchers.Main) {
                    onResult(AddResult.Success)
                }
            } catch (e: Exception) {
                withContext(kotlinx.coroutines.Dispatchers.Main) {
                    onResult(AddResult.Error(e.message ?: "Unknown error"))
                }
            }
        }
    }

    override fun updateSearchQuery(query: String?) {
        super.updateSearchQuery(query)
        updateMemberships(emptyMap())
    }
}

sealed interface AddResult {
    data object Success : AddResult
    data object AlreadyInThisGroup : AddResult
    data class InAnotherGroup(val groupName: String) : AddResult
    data class Error(val message: String) : AddResult
}

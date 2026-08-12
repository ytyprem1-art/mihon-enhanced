package eu.kanade.tachiyomi.ui.browse.source.linked

import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import eu.kanade.tachiyomi.ui.browse.source.globalsearch.SearchViewModel
import eu.kanade.tachiyomi.ui.browse.source.globalsearch.SearchItemResult
import eu.kanade.tachiyomi.ui.browse.source.globalsearch.SourceFilter
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.linked.interactor.ManageLinkedSourceGroup
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class LinkedSourceSearchViewModel(
    val linkedGroupId: Long,
    initialQuery: String = "",
    private val manageLinkedSourceGroup: ManageLinkedSourceGroup = Injekt.get(),
) : SearchViewModel(State(searchQuery = initialQuery)) {

    companion object {
        val LINKED_GROUP_ID_KEY = CreationExtras.Key<Long>()
        val INITIAL_QUERY_KEY = CreationExtras.Key<String>()

        val Factory = viewModelFactory {
            initializer {
                LinkedSourceSearchViewModel(
                    linkedGroupId = get(LINKED_GROUP_ID_KEY)!!,
                    initialQuery = get(INITIAL_QUERY_KEY) ?: "",
                )
            }
        }
    }

    init {
        // Default to All sources for this specific search flow
        setSourceFilter(SourceFilter.All)

        extensionFilter = null // initialExtensionFilter logic if needed

        if (initialQuery.isNotBlank()) {
            search()
        }

        viewModelScope.launchIO {
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
        mutableState.update { it.copy(mangaGroupIds = memberships) }
    }

    fun addMangaToGroup(manga: Manga, onResult: (AddResult) -> Unit) {
        viewModelScope.launchIO {
            val existingGroupId = manageLinkedSourceGroup.getGroupForManga(manga.id, manga.source)?.id
            if (existingGroupId != null) {
                withUIContext {
                    if (existingGroupId == linkedGroupId) {
                        onResult(AddResult.AlreadyInThisGroup)
                    } else {
                        val group = manageLinkedSourceGroup.getGroupById(existingGroupId)
                        onResult(AddResult.InAnotherGroup(group?.name ?: "Unknown"))
                    }
                }
                return@launchIO
            }

            try {
                manageLinkedSourceGroup.joinGroup(linkedGroupId, manga.id, manga.source)
                // Update local state immediately
                val currentMemberships = state.value.mangaGroupIds.toMutableMap()
                currentMemberships[manga.id] = linkedGroupId
                updateMemberships(currentMemberships)
                withUIContext {
                    onResult(AddResult.Success)
                }
            } catch (e: Exception) {
                withUIContext {
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

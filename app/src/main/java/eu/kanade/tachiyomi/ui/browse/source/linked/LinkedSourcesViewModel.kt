package eu.kanade.tachiyomi.ui.browse.source.linked

import androidx.compose.runtime.getValue
import androidx.lifecycle.viewModelScope
import eu.kanade.core.preference.asState
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.source.linked.interactor.ManageLinkedSourceGroup
import tachiyomi.domain.source.linked.model.LinkedSourceGroup
import mihon.core.viewmodel.StateViewModel
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import tachiyomi.core.common.util.lang.launchIO

class LinkedSourcesViewModel(
    private val manageLinkedSourceGroup: ManageLinkedSourceGroup = Injekt.get(),
    private val sourceManager: SourceManager = Injekt.get(),
    private val libraryPreferences: LibraryPreferences = Injekt.get(),
) : StateViewModel<LinkedSourcesViewModel.State>(State()) {

    val sortMode by libraryPreferences.linkedSourceGroupSort.asState(viewModelScope)

    init {
        viewModelScope.launchIO {
            val groupsFlow = manageLinkedSourceGroup.subscribe()
                .distinctUntilChanged()
                .flatMapLatest { groups ->
                    if (groups.isEmpty()) return@flatMapLatest kotlinx.coroutines.flow.flowOf(emptyList<GroupWithMetadata>())

                    val flows = groups.map { group ->
                        manageLinkedSourceGroup.subscribeMembers(group.id).map { members ->
                            GroupWithMetadata(
                                group = group,
                                representativeManga = members.firstOrNull(),
                                sourceNames = members.map { sourceManager.getOrStub(it.source).name },
                                memberTitles = members.map { it.title }
                            )
                        }
                    }
                    combine(flows) { it.toList() }
                }

            combine(
                groupsFlow,
                state.map { it.searchQuery }.distinctUntilChanged(),
                libraryPreferences.linkedSourceGroupSort.changes(),
            ) { groups, query, sort ->
                val q = query
                val sortedGroups = when (sort) {
                    LibraryPreferences.LinkedSourceGroupSort.Newest -> groups.sortedByDescending { it.group.id }
                    LibraryPreferences.LinkedSourceGroupSort.Oldest -> groups.sortedBy { it.group.id }
                    LibraryPreferences.LinkedSourceGroupSort.TitleAZ -> groups.sortedBy { it.group.name.lowercase() }
                    LibraryPreferences.LinkedSourceGroupSort.TitleZA -> groups.sortedByDescending { it.group.name.lowercase() }
                }

                if (q.isNullOrBlank()) return@combine sortedGroups

                sortedGroups.filter { group ->
                    group.group.name.contains(q, ignoreCase = true) ||
                        group.sourceNames.any { it.contains(q, ignoreCase = true) } ||
                        group.memberTitles.any { it.contains(q, ignoreCase = true) }
                }
            }.collectLatest { filteredGroups ->
                mutableState.update { it.copy(groups = filteredGroups) }
            }
        }
    }

    fun setSortMode(mode: LibraryPreferences.LinkedSourceGroupSort) {
        libraryPreferences.linkedSourceGroupSort.set(mode)
    }

    fun updateSearchQuery(query: String?) {
        mutableState.update { it.copy(searchQuery = query) }
    }

    fun createGroup(name: String) {
        viewModelScope.launchIO {
            manageLinkedSourceGroup.create(name)
        }
    }

    fun renameGroup(id: Long, name: String) {
        viewModelScope.launchIO {
            manageLinkedSourceGroup.rename(id, name)
        }
    }

    fun deleteGroup(id: Long) {
        viewModelScope.launchIO {
            manageLinkedSourceGroup.delete(id)
        }
    }

    data class State(
        val searchQuery: String? = null,
        val groups: List<GroupWithMetadata> = emptyList(),
    )

    data class GroupWithMetadata(
        val group: LinkedSourceGroup,
        val representativeManga: Manga?,
        val sourceNames: List<String>,
        val memberTitles: List<String>,
    )
}

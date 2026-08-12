package eu.kanade.tachiyomi.ui.mod.historygroup

import androidx.compose.runtime.Immutable
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import eu.kanade.core.util.insertSeparators
import eu.kanade.presentation.history.HistoryUiModel
import eu.kanade.tachiyomi.util.lang.toLocalDate
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.domain.history.group.interactor.ManageHistoryGroups
import tachiyomi.domain.history.interactor.GetHistory
import tachiyomi.domain.history.model.HistoryWithRelations
import mihon.core.viewmodel.StateViewModel
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class HistoryGroupDetailViewModel(
    private val groupId: Long,
    private val manageHistoryGroups: ManageHistoryGroups = Injekt.get(),
    private val getHistory: GetHistory = Injekt.get(),
) : StateViewModel<HistoryGroupDetailViewModel.State>(State()) {

    companion object {
        val GROUP_ID_KEY = CreationExtras.Key<Long>()

        val Factory = viewModelFactory {
            initializer {
                HistoryGroupDetailViewModel(
                    groupId = get(GROUP_ID_KEY)!!,
                )
            }
        }
    }

    private val _events = Channel<Event>(Int.MAX_VALUE)
    val events: Flow<Event> = _events.receiveAsFlow()

    init {
        viewModelScope.launchIO {
            val group = manageHistoryGroups.getGroups().find { it.id == groupId }
            mutableState.update { it.copy(groupName = group?.name ?: "") }
        }

        viewModelScope.launchIO {
            combine(
                manageHistoryGroups.subscribeMembers(groupId),
                getHistory.subscribe(""),
            ) { memberIds, historyList ->
                historyList.filter { it.mangaId in memberIds }
                    .toHistoryUiModels()
            }.collectLatest { list ->
                mutableState.update { it.copy(list = list) }
            }
        }
    }

    private fun List<HistoryWithRelations>.toHistoryUiModels(): List<HistoryUiModel> {
        return map { HistoryUiModel.Item(it) }
            .insertSeparators { before, after ->
                val beforeDate = before?.item?.readAt?.time?.toLocalDate()
                val afterDate = after?.item?.readAt?.time?.toLocalDate()
                when {
                    beforeDate != afterDate && afterDate != null -> HistoryUiModel.Header(afterDate)
                    else -> null
                }
            }
    }

    fun toggleSelectionMode() {
        mutableState.update { it.copy(selectionMode = !it.selectionMode, selected = emptySet()) }
    }

    fun toggleSelection(mangaId: Long) {
        mutableState.update { state ->
            val newSelected = state.selected.toMutableSet().apply {
                if (contains(mangaId)) remove(mangaId) else add(mangaId)
            }
            state.copy(selected = newSelected)
        }
    }

    fun removeSelectedFromGroup() {
        val selected = state.value.selected
        if (selected.isEmpty()) return
        viewModelScope.launchIO {
            selected.forEach { mangaId ->
                manageHistoryGroups.removeMangaFromGroup(mangaId)
            }

            val remainingMembers = manageHistoryGroups.subscribeMembers(groupId).first()
            if (remainingMembers.size <= 1) {
                if (remainingMembers.size == 1) {
                    manageHistoryGroups.removeMangaFromGroup(remainingMembers.first())
                }
                manageHistoryGroups.deleteGroup(groupId)
                _events.send(Event.GroupDissolved)
            }

            mutableState.update { it.copy(selectionMode = false, selected = emptySet()) }
        }
    }

    sealed interface Event {
        data object GroupDissolved : Event
    }

    @Immutable
    data class State(
        val groupName: String = "",
        val list: List<HistoryUiModel>? = null,
        val selectionMode: Boolean = false,
        val selected: Set<Long> = emptySet(),
    )
}

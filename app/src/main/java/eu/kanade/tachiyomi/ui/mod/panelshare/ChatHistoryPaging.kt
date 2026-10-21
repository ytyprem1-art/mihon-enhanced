package eu.kanade.tachiyomi.ui.mod.panelshare

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ChatHistoryPage(
    val limit: Long = PanelLimits.INITIAL_MESSAGES,
    val loading: Boolean = false,
    val canLoadMore: Boolean = false,
    val error: String? = null,
)

/** Expands one live query in steps of 50, keeping loaded messages and read receipts live. */
class ChatHistoryPaging {
    private val _state = MutableStateFlow(ChatHistoryPage())
    val state = _state.asStateFlow()
    val limit get() = state.value.limit

    fun requestMore(): Boolean {
        val current = state.value
        if (current.loading || (!current.canLoadMore && current.error == null)) return false
        val next = if (current.error != null) {
            current.limit
        } else {
            (current.limit + PanelLimits.INITIAL_MESSAGES).coerceAtMost(PanelLimits.RETAINED_MESSAGES)
        }
        _state.value = current.copy(limit = next, loading = true, error = null)
        return true
    }

    fun onSnapshot(count: Int, fromCache: Boolean) {
        if (fromCache) return
        _state.value = state.value.copy(
            loading = false,
            canLoadMore = count >= limit && limit < PanelLimits.RETAINED_MESSAGES,
            error = null,
        )
    }

    fun onError() {
        _state.value = state.value.copy(loading = false, error = "Could not load earlier messages")
    }

    fun reset(): Boolean {
        val changed = limit != PanelLimits.INITIAL_MESSAGES
        if (changed) clear()
        return changed
    }

    fun clear() {
        _state.value = ChatHistoryPage()
    }
}

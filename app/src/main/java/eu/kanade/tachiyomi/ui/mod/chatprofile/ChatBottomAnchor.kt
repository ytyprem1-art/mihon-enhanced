package eu.kanade.tachiyomi.ui.mod.chatprofile

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/** Stable message keys preserve history position; explicitly override that anchor only at the bottom. */
@Composable
internal fun KeepChatAtBottom(listState: LazyListState, newestMessageId: String?) {
    var previousNewestId by remember { mutableStateOf(newestMessageId) }
    SideEffect {
        if (previousNewestId != newestMessageId) {
            if (previousNewestId != null && newestMessageId != null && !listState.canScrollBackward) {
                // Applied before remeasurement so inserting a new key at index 0 cannot retain the old key.
                listState.requestScrollToItem(0)
            }
            previousNewestId = newestMessageId
        }
    }
}

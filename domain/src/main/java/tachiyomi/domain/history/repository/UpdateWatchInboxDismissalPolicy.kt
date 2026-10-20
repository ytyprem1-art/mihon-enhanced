package tachiyomi.domain.history.repository

import tachiyomi.domain.history.model.UpdateWatchInboxItem

interface UpdateWatchInboxDismissalPolicy {
    suspend fun acknowledge(item: UpdateWatchInboxItem)
    suspend fun filter(item: UpdateWatchInboxItem): UpdateWatchInboxItem?
}

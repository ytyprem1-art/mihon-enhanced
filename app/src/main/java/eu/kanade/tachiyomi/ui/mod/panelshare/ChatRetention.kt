package eu.kanade.tachiyomi.ui.mod.panelshare

/** Transport-independent retention, also exercised with concurrent sends and failed deletes. */
object ChatRetention {
    interface Store<T> {
        suspend fun newest(limit: Long): List<T>
        suspend fun olderThan(boundary: T, limit: Long): List<T>
        suspend fun deleteWithPanels(messages: List<T>)
    }

    suspend fun <T> prune(store: Store<T>) {
        val latest = store.newest(PanelLimits.RETAINED_MESSAGES)
        if (latest.size < PanelLimits.RETAINED_MESSAGES) return
        val boundary = latest.last()
        while (true) {
            // Paired deletes use two existsAfter() checks per message. Stay inside Rules' 20-call batch limit.
            val old = store.olderThan(boundary, 10)
            if (old.isEmpty()) return
            store.deleteWithPanels(old)
        }
    }
}

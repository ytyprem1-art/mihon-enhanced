package eu.kanade.tachiyomi.ui.mod.updatewatch.worker

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicInteger

/**
 * Transient in-memory state for manga currently being processed or queued
 * by an active background worker or foreground burst.
 */
object UpdateWatchRefreshState {
    private val _queuedMangaIds = MutableStateFlow<Set<Long>>(emptySet())
    val queuedMangaIds: StateFlow<Set<Long>> = _queuedMangaIds.asStateFlow()

    private val activeRunners = AtomicInteger(0)

    /**
     * Atomically claims a set of manga IDs. Returns the IDs that were successfully claimed
     * (i.e., those that were not already claimed).
     */
    fun claim(ids: Set<Long>): Set<Long> {
        var claimed = emptySet<Long>()
        _queuedMangaIds.update { current ->
            val toClaim = ids.filter { it !in current }.toSet()
            claimed = toClaim
            current + toClaim
        }
        return claimed
    }

    /**
     * Releases a set of manga IDs from the claimed state.
     */
    fun release(ids: Set<Long>) {
        _queuedMangaIds.update { it - ids }
    }

    /**
     * Releases a single manga ID.
     */
    fun releaseOne(id: Long) {
        _queuedMangaIds.update { it - id }
    }

    /**
     * Clears all claims. Use with caution.
     */
    fun clear() {
        _queuedMangaIds.value = emptySet()
    }

    fun onRunStarted() {
        activeRunners.incrementAndGet()
    }

    fun onRunFinished() {
        activeRunners.decrementAndGet()
    }

    fun getActiveRunnerCount(): Int = activeRunners.get()
}

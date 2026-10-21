package tachiyomi.domain.history.interactor

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tachiyomi.domain.history.model.UpdateWatchInboxItem
import tachiyomi.domain.history.repository.UpdateWatchInboxDismissalPolicy
import tachiyomi.domain.history.repository.UpdateWatchInboxRepository

@dev.zacsweers.metro.SingleIn(dev.zacsweers.metro.AppScope::class)
@dev.zacsweers.metro.Inject
class ManageUpdateWatchInbox(
    private val repository: UpdateWatchInboxRepository,
    private val dismissals: UpdateWatchInboxDismissalPolicy,
) {
    private val mutex = Mutex()

    suspend fun insertOrMerge(incoming: UpdateWatchInboxItem): UpdateWatchInboxItem? = mutex.withLock {
        val item = dismissals.filter(incoming) ?: return@withLock null
        val existing = repository.getByMangaId(item.mangaId)
        if (existing == null || item.type == UpdateWatchInboxItem.TYPE_INACTIVITY_WARNING) {
            // New item or it's a warning (warnings replace anything else for now,
            // though usually updates replace warnings)
            repository.insert(item)
        } else {
            // Incoming is a chapter update.
            if (existing.type == UpdateWatchInboxItem.TYPE_UPDATE) {
                val mergedChapterIds = (existing.chapterIds + item.chapterIds).distinct()
                val merged = existing.copy(
                    chapterCount = mergedChapterIds.size,
                    chapterRange = mergeRanges(existing.chapterRange, item.chapterRange),
                    lastFoundAt = item.lastFoundAt,
                    latestChapterId = item.latestChapterId,
                    latestChapterNumber = item.latestChapterNumber,
                    chapterIds = mergedChapterIds,
                    latestChapterUploadAt = item.latestChapterUploadAt,
                )
                repository.insert(merged)
            } else {
                // Existing was a warning, replace it with the new update
                repository.insert(item)
            }
        }
        item
    }

    suspend fun dismiss(mangaId: Long, onRemaining: (List<UpdateWatchInboxItem>) -> Unit = {}) = mutex.withLock {
        repository.getByMangaId(mangaId)?.let { dismissals.acknowledge(it) }
        repository.delete(mangaId)
        onRemaining(repository.getAll())
    }

    suspend fun deliverUndismissed(
        items: List<UpdateWatchInboxItem>,
        deliver: (List<UpdateWatchInboxItem>) -> Unit,
    ) = mutex.withLock {
        deliver(items.mapNotNull { dismissals.filter(it) })
    }

    suspend fun reconcileDismissed() = mutex.withLock {
        repository.getAll().forEach { item ->
            val retained = dismissals.filter(item)
            if (retained == null) {
                repository.delete(item.mangaId)
            } else if (retained != item) {
                repository.insert(retained)
            }
        }
    }

    suspend fun delete(mangaId: Long) {
        repository.delete(mangaId)
    }

    suspend fun deleteAll() {
        repository.deleteAll()
    }

    private fun mergeRanges(old: String, new: String): String {
        if (old == new) return old
        // Simple merge: "Ch. 1" + "Ch. 2" -> "Ch. 1, Ch. 2"
        return "$old, $new"
    }
}

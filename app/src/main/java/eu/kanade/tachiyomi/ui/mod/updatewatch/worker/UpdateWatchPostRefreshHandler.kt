package eu.kanade.tachiyomi.ui.mod.updatewatch.worker

import eu.kanade.tachiyomi.ui.mod.util.dateUploadMillis
import android.content.Context
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.history.interactor.ManageUpdateWatch
import tachiyomi.domain.history.interactor.ManageUpdateWatchInbox
import tachiyomi.domain.history.model.UpdateWatchInboxItem
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

object UpdateWatchPostRefreshHandler {

    suspend fun handleRefreshResult(
        manga: Manga,
        oldLatestChapter: Chapter?,
        newChapters: List<Chapter>,
        currentLatestChapter: Chapter?,
        startTime: Long,
        onInboxItemCreated: (UpdateWatchInboxItem) -> Unit
    ) {
        val foundNew = newChapters.isNotEmpty() || (currentLatestChapter != null && currentLatestChapter.id != oldLatestChapter?.id)
        val newCount = newChapters.size.coerceAtLeast(if (foundNew) 1 else 0)

        if (foundNew && currentLatestChapter != null) {
            val manageUpdateWatchInbox: ManageUpdateWatchInbox = Injekt.get()
            val manageUpdateWatch: ManageUpdateWatch = Injekt.get()
            val sourceManager: SourceManager = Injekt.get()

            val range = formatChapterRange(newChapters, currentLatestChapter)
            val source = sourceManager.getOrStub(manga.source)

            val item = UpdateWatchInboxItem(
                mangaId = manga.id,
                mangaTitle = manga.title,
                sourceId = source.id,
                sourceName = source.name,
                chapterCount = newCount,
                chapterRange = range,
                firstFoundAt = startTime,
                lastFoundAt = startTime,
                latestChapterId = currentLatestChapter.id,
                latestChapterNumber = currentLatestChapter.chapterNumber,
                chapterIds = newChapters.map { it.id }.ifEmpty { listOf(currentLatestChapter.id) },
                latestChapterUploadAt = currentLatestChapter.dateUploadMillis,
            )
            manageUpdateWatch.resetStaleMilestone(manga.id)
            val accepted = manageUpdateWatchInbox.insertOrMerge(item) ?: return
            onInboxItemCreated(accepted)
        }
    }

    fun formatChapterRange(newChapters: List<Chapter>, currentLatestChapter: Chapter): String {
        return if (newChapters.size > 1) {
            val sorted = newChapters.sortedBy { it.chapterNumber }
            val min = sorted.first().chapterNumber
            val max = sorted.last().chapterNumber
            val isContiguous = (max - min).toInt() == newChapters.size - 1
            if (isContiguous) "Ch. ${formatChapterNumber(min)}–${formatChapterNumber(max)}"
            else if (newChapters.size > 3) "Ch. ${formatChapterNumber(min)}, ..., ${formatChapterNumber(max)}"
            else "Ch. " + sorted.joinToString(", ") { formatChapterNumber(it.chapterNumber) }
        } else "Ch. ${formatChapterNumber(currentLatestChapter.chapterNumber)}"
    }

    suspend fun showNotificationIfEnabled(context: Context, items: List<UpdateWatchInboxItem>) {
        val libraryPreferences: LibraryPreferences = Injekt.get()
        Injekt.get<ManageUpdateWatchInbox>().deliverUndismissed(items) { accepted ->
            if (accepted.isNotEmpty() && libraryPreferences.notifyTrackedUpdates.get()) {
                UpdateWatchNotifier(context).showUpdateNotification(accepted)
            }
        }
    }

    private fun formatChapterNumber(number: Double): String {
        return if (number % 1 == 0.0) number.toInt().toString() else number.toString()
    }
}

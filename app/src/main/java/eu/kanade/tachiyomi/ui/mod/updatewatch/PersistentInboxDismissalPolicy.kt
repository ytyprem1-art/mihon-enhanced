package eu.kanade.tachiyomi.ui.mod.updatewatch

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.history.model.UpdateWatchInboxItem
import tachiyomi.domain.history.repository.UpdateWatchInboxDismissalPolicy
import tachiyomi.domain.manga.repository.MangaRepository

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class PersistentInboxDismissalPolicy(
    private val store: UpdateWatchDismissalStore,
    private val mangas: MangaRepository,
    private val chapters: ChapterRepository,
) : UpdateWatchInboxDismissalPolicy {
    override suspend fun acknowledge(item: UpdateWatchInboxItem) {
        val manga = mangas.getMangaById(item.mangaId)
        val warning = item.type == UpdateWatchInboxItem.TYPE_INACTIVITY_WARNING
        val ids = (item.chapterIds + item.latestChapterId).filter { it > 0 }.distinct()
        val covered = ids.mapNotNull { chapters.getChapterById(it) }
        store.restore(listOf(UpdateWatchDismissal(
            source = manga.source, url = manga.url,
            chapterUrls = if (warning) emptyList() else covered.map { it.url },
            throughChapterNumber = if (warning) 0.0 else maxOf(
                item.latestChapterNumber, covered.maxOfOrNull { it.chapterNumber } ?: 0.0,
            ),
            warningMilestone = if (warning) item.milestone else 0,
            warningFoundAt = if (warning) item.lastFoundAt else 0L,
        )))
    }

    override suspend fun filter(item: UpdateWatchInboxItem): UpdateWatchInboxItem? {
        val manga = mangas.getMangaById(item.mangaId)
        val dismissed = store.snapshot().firstOrNull { it.source == manga.source && it.url == manga.url }
            ?: return item
        if (item.type == UpdateWatchInboxItem.TYPE_INACTIVITY_WARNING) {
            return item.takeIf { it.lastFoundAt > dismissed.warningFoundAt }
        }
        val ids = item.chapterIds.ifEmpty { listOf(item.latestChapterId) }.distinct()
        val accepted = ids.mapNotNull { chapters.getChapterById(it) }
            .filterNot { dismissed.excludes(it.url, it.chapterNumber) }
        if (accepted.isEmpty()) return null
        if (accepted.map { it.id } == ids) return item
        val latest = accepted.firstOrNull { it.id == item.latestChapterId }
            ?: accepted.maxBy { it.chapterNumber }
        return item.copy(
            chapterIds = accepted.map { it.id }, chapterCount = accepted.size,
            chapterRange = "Ch. " + accepted.sortedBy { it.chapterNumber }.joinToString(", ") {
                if (it.chapterNumber % 1 == 0.0) it.chapterNumber.toLong().toString() else it.chapterNumber.toString()
            },
            latestChapterId = latest.id, latestChapterNumber = latest.chapterNumber,
            latestChapterUploadAt = latest.dateUpload?.toEpochMilliseconds() ?: 0L,
        )
    }
}

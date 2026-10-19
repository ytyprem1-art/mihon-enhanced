package eu.kanade.tachiyomi.ui.mod.historycategory

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.history.model.HistoryUpdate
import tachiyomi.domain.history.repository.HistoryCategoryRepository
import tachiyomi.domain.history.repository.HistoryRepository
import tachiyomi.domain.manga.repository.MangaRepository

class AutoCategorizingHistoryRepository(
    private val delegate: HistoryRepository,
    private val chapters: ChapterRepository,
    private val mangas: MangaRepository,
    private val categories: HistoryCategoryRepository,
    private val links: HistorySourceLinks,
) : HistoryRepository by delegate {
    private val mutex = Mutex()

    override suspend fun upsertHistory(historyUpdate: HistoryUpdate) = mutex.withLock {
        try {
            categorizeNewHistory(historyUpdate)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Could not apply history source category" }
        }
        delegate.upsertHistory(historyUpdate)
    }

    private suspend fun categorizeNewHistory(update: HistoryUpdate) {
        val sourceLinks = links.snapshot()
        if (sourceLinks.isEmpty()) return
        val chapter = chapters.getChapterById(update.chapterId) ?: return
        // Existing history, including entries manually returned to All, keeps its placement.
        if (delegate.getHistoryByMangaId(chapter.mangaId).isNotEmpty()) return
        if (categories.getCategoryForManga(chapter.mangaId) != null) return
        val manga = mangas.getMangaById(chapter.mangaId)
        val categoryId = sourceLinks[manga.source] ?: return
        if (categories.getHistoryCategories().first().none { it.id == categoryId }) return
        // Assign before publishing the history row so it does not briefly appear in All.
        categories.insertMangaMapping(manga.id, categoryId)
    }
}

package eu.kanade.tachiyomi.ui.mod.helper

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.data.Database
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.history.model.History
import tachiyomi.domain.manga.model.Manga
import tachiyomi.data.history.HistoryMapper
import java.util.Date
import kotlin.math.max

object HistoryMigrationHelper {

    /**
     * Attempts to find a canonical target manga in the Library for Record A.
     */
    suspend fun findCanonicalTarget(
        manga: Manga,
        getDuplicateLibraryManga: suspend (Manga) -> List<tachiyomi.domain.manga.model.MangaWithChapterCount>
    ): Manga? {
        // If current manga is in library, it is its own canonical record (Case B)
        if (manga.favorite) return null

        val duplicates = getDuplicateLibraryManga(manga)

        logcat(LogPriority.DEBUG) { "Mod: Checking canonical target for ${manga.title} (ID: ${manga.id}). Duplicates found: ${duplicates.size}" }

        val candidates = duplicates
            .filter { it.manga.favorite }
            .sortedByDescending { it.chapterCount }

        if (candidates.size == 1) {
            val candidate = candidates.first().manga
            if (isSafeTarget(manga, candidate)) {
                logcat(LogPriority.INFO) { "Mod: Safe canonical target found: ${candidate.title} (ID: ${candidate.id})" }
                return candidate
            }
        } else if (candidates.size > 1) {
            logcat(LogPriority.DEBUG) { "Mod: Multiple library candidates for ${manga.title}. Ambiguous, skipping auto-migration." }
        }
        return null
    }

    private suspend fun migrateHistoryCategory(database: Database, oldMangaId: Long, targetMangaId: Long) {
        val oldCategory = database.historycategoriesQueries
            .getCategoryForManga(oldMangaId)
            .awaitAsOneOrNull()

        if (oldCategory != null) {
            val targetCategory = database.historycategoriesQueries
                .getCategoryForManga(targetMangaId)
                .awaitAsOneOrNull()

            if (targetCategory == null) {
                logcat(LogPriority.INFO) { "Mod: Migrating History Category $oldCategory from manga $oldMangaId to $targetMangaId" }
                database.historycategoriesQueries.insertMangaMapping(targetMangaId, oldCategory)
                database.historycategoriesQueries.deleteMangaMapping(oldMangaId)
            } else {
                logcat(LogPriority.INFO) { "Mod: Target manga $targetMangaId already has History Category $targetCategory. Skipping migration of category $oldCategory." }
                database.historycategoriesQueries.deleteMangaMapping(oldMangaId)
            }
        }
    }

    private fun isSafeTarget(manga: Manga, candidate: Manga): Boolean {
        if (manga.author != null && candidate.author != null) {
            if (manga.author!!.trim().lowercase() != candidate.author!!.trim().lowercase()) return false
        }
        return true
    }

    /**
     * Moves history and reading progress from [oldChapters] to [targetChapters] by matching logical identity.
     */
    suspend fun migrateHistoryAndProgress(
        database: Database,
        oldChapters: List<Chapter>,
        targetChapters: List<Chapter>,
        getHistoryByMangaId: suspend (Long) -> List<History>,
        oldManga: Manga,
        targetManga: Manga
    ) {
        val oldHistoryList = getHistoryByMangaId(oldManga.id)
        if (oldHistoryList.isEmpty()) {
            logcat(LogPriority.DEBUG) { "Mod: No history found for manga ${oldManga.id} (${oldManga.title})" }
            return
        }

        logcat(LogPriority.INFO) {
            "Mod: Migrating ${oldHistoryList.size} history/progress items from manga ${oldManga.id} to ${targetManga.id}"
        }

        val reboundChapterIds = mutableSetOf<Long>()

        database.transaction {
            // 1. Migrate History Category mapping if safe
            migrateHistoryCategory(database, oldManga.id, targetManga.id)

            for (history in oldHistoryList) {
                val oldChapter = oldChapters.find { it.id == history.chapterId }
                if (oldChapter == null) {
                    logcat(LogPriority.WARN) { "Mod: Could not find chapter for history row ${history.id} (chapterId: ${history.chapterId}) in manga ${oldManga.id}" }
                    continue
                }

                val targetChapter = targetChapters.find {
                    it.isRecognizedNumber && oldChapter.isRecognizedNumber &&
                        it.chapterNumber == oldChapter.chapterNumber &&
                        ChapterReconciliationHelper.isScanlatorCompatible(it.scanlator, oldChapter.scanlator)
                } ?: targetChapters.find {
                    // Fallback to number only match if it's unique
                    it.isRecognizedNumber && oldChapter.isRecognizedNumber &&
                        it.chapterNumber == oldChapter.chapterNumber &&
                        targetChapters.count { c -> c.isRecognizedNumber && c.chapterNumber == oldChapter.chapterNumber } == 1
                }

                if (targetChapter != null) {
                    logcat(LogPriority.DEBUG) {
                        "Mod: Rebinding history from chapter ${oldChapter.id} (manga ${oldChapter.mangaId}, num ${oldChapter.chapterNumber}) " +
                            "to target chapter ${targetChapter.id} (manga ${targetChapter.mangaId}, num ${targetChapter.chapterNumber})"
                    }
                    // 1. Migrate History Row
                    val targetHistory = database.historyQueries
                        .getHistoryByChapterId(targetChapter.id, HistoryMapper::mapHistory)
                        .awaitAsOneOrNull()

                    val finalReadAt = if (targetHistory == null) {
                        history.readAt
                    } else {
                        val t1 = history.readAt?.time ?: 0L
                        val t2 = targetHistory.readAt?.time ?: 0L
                        if (t1 > 0 || t2 > 0) Date(max(t1, t2)) else null
                    }

                    database.historyQueries.upsert(targetChapter.id, finalReadAt, history.readDuration)

                    // 2. Delete old history row to rebind identity to Record B
                    database.historyQueries.deleteHistoryByChapterId(history.chapterId)
                    reboundChapterIds.add(history.chapterId)

                    // 3. Sync Chapter Progress
                    database.chaptersQueries.update(
                        mangaId = null,
                        url = null,
                        name = null,
                        scanlator = null,
                        read = targetChapter.read || oldChapter.read,
                        bookmark = targetChapter.bookmark || oldChapter.bookmark,
                        lastPageRead = max(targetChapter.lastPageRead, oldChapter.lastPageRead),
                        chapterNumber = null,
                        sourceOrder = null,
                        dateFetch = null,
                        dateUpload = null,
                        version = null,
                        isSyncing = null,
                        memo = null,
                        chapterId = targetChapter.id
                    )
                } else {
                    logcat(LogPriority.DEBUG) {
                        "Mod: Could not find target chapter match for old chapter ${oldChapter.chapterNumber} " +
                        "(URL: ${oldChapter.url}, Scanlator: ${oldChapter.scanlator})"
                    }
                }
            }

            // MOD: After rebinding history to Record B, we should prune the rebound chapters from Record A
            // if we are in a manual migration flow (i.e. Record A is about to be replaced)
            if (oldManga.id != targetManga.id && reboundChapterIds.isNotEmpty()) {
                logcat(LogPriority.INFO) { "Mod: Deleting ${reboundChapterIds.size} rebound chapters from Record A (${oldManga.id})" }
                database.chaptersQueries.removeChaptersWithIds(reboundChapterIds.toList())
            }
        }
    }
}

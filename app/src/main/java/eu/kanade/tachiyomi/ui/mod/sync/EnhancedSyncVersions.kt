package eu.kanade.tachiyomi.ui.mod.sync

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOne
import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import tachiyomi.data.Database
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.manga.repository.MangaRepository

/** Keeps the original Drive counter format without adding mod fields to upstream models. */
@Inject
class EnhancedSyncVersions(
    private val database: Database,
    private val mangas: MangaRepository,
    private val chapters: ChapterRepository,
) {
    suspend fun manga(id: Long) = database.enhanced_syncQueries.mangaVersion(id).awaitAsOne()
    suspend fun chapter(id: Long) = database.enhanced_syncQueries.chapterVersion(id).awaitAsOne()

    suspend fun chapters(mangaId: Long): Map<Long, Long> = database.enhanced_syncQueries.chapterVersionsByManga(mangaId)
        .awaitAsList().associate { it.id to it.version }

    suspend fun restoring(backups: List<BackupManga>, restore: suspend () -> Unit) {
        database.transaction {
            // Counter writes and restored rows commit together; a failed restore rolls back suppression too.
            val existing = backups.map { backup ->
                val manga = mangas.getMangaByUrlAndSourceId(backup.url, backup.source)
                val chapterVersions = manga?.let {
                    val versions = chapters(it.id)
                    chapters.getChapterByMangaId(it.id, applyScanlatorFilter = false)
                        .associate { chapter -> chapter.url to (versions[chapter.id] ?: 0L) }
                }.orEmpty()
                (manga?.let { manga(it.id) } ?: 0L) to chapterVersions
            }
            database.enhanced_syncQueries.setRestoring(1)
            restore()
            backups.forEachIndexed { index, backup ->
                val manga = checkNotNull(mangas.getMangaByUrlAndSourceId(backup.url, backup.source))
                val (oldVersion, oldChapters) = existing[index]
                database.enhanced_syncQueries.setMangaVersion(manga.id, maxOf(oldVersion, backup.version))
                val restoredChapters = chapters.getChapterByMangaId(manga.id, applyScanlatorFilter = false)
                    .associateBy { it.url }
                backup.chapters.forEach { backupChapter ->
                    val chapter = checkNotNull(restoredChapters[backupChapter.url])
                    database.enhanced_syncQueries.setChapterVersion(
                        chapter.id,
                        maxOf(oldChapters[chapter.url] ?: 0L, backupChapter.version),
                    )
                }
            }
            database.enhanced_syncQueries.setRestoring(0)
        }
    }
}

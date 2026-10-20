package eu.kanade.tachiyomi.data.backup.create.creators

import dev.zacsweers.metro.Inject
import app.cash.sqldelight.async.coroutines.awaitAsList
import eu.kanade.tachiyomi.data.backup.create.BackupOptions
import eu.kanade.tachiyomi.data.backup.models.BackupHistory
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.backup.models.backupMergedMangaReferenceMapper
import eu.kanade.tachiyomi.data.backup.models.toBackupChapter
import eu.kanade.tachiyomi.data.backup.models.toBackupTracking
import eu.kanade.tachiyomi.ui.reader.setting.ReadingMode
import mihon.core.common.extensions.toByteArray
import mihon.core.common.extensions.toEpochMillisOrZero
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.history.interactor.GetHistory
import tachiyomi.domain.history.interactor.ManageHistoryCategory
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.track.repository.TrackRepository

@Inject
class MangaBackupCreator(
    private val syncVersions: eu.kanade.tachiyomi.ui.mod.sync.EnhancedSyncVersions,
    private val database: tachiyomi.data.Database,
    private val mangaRepository: MangaRepository,
    private val chapterRepository: ChapterRepository,
    private val trackRepository: TrackRepository,
    private val getCategories: GetCategories,
    private val getHistory: GetHistory,
    private val manageHistoryCategory: ManageHistoryCategory,
) {

    suspend operator fun invoke(mangas: List<Manga>, options: BackupOptions): List<BackupManga> {
        return mangas.map {
            backupManga(it, options)
        }
    }

    private suspend fun backupManga(manga: Manga, options: BackupOptions): BackupManga {
        // Entry for this manga
        val mangaObject = manga.toBackupManga().apply { version = syncVersions.manga(manga.id) }

        mangaObject.excludedScanlators = mangaRepository.getExcludedScanlators(manga.id).toList()

        if (options.chapters) {
            // Read sync counters in one query per manga, including long chapters lists.
            val chapterVersions = syncVersions.chapters(manga.id)
            chapterRepository.getChapterByMangaId(manga.id, applyScanlatorFilter = false)
                .map { chapter -> chapter.toBackupChapter().apply { version = chapterVersions[chapter.id] ?: 0L } }
                .takeUnless { it.isEmpty() }
                ?.let { mangaObject.chapters = it }
        }

        if (options.categories) {
            // Backup categories for this manga
            val categoriesForManga = getCategories.await(manga.id)
            if (categoriesForManga.isNotEmpty()) {
                mangaObject.categories = categoriesForManga.map { it.order }
            }
        }

        if (options.tracking) {
            val tracks = trackRepository.getTracksByMangaId(manga.id).map { it.toBackupTracking() }
            if (tracks.isNotEmpty()) {
                mangaObject.tracking = tracks
            }
        }

        if (options.history) {
            val historyByMangaId = getHistory.await(manga.id)
            if (historyByMangaId.isNotEmpty()) {
                val chapterUrlsById = chapterRepository.getChapterByMangaId(manga.id).associate { it.id to it.url }
                val history = historyByMangaId.mapNotNull { history ->
                    // A chapter removed since its history was read takes that history with it
                    val url = chapterUrlsById[history.chapterId] ?: return@mapNotNull null
                    BackupHistory(url, history.readAt.toEpochMillisOrZero(), history.readDuration)
                }
                if (history.isNotEmpty()) {
                    mangaObject.history = history
                }
            }
        }

        mangaObject.historyCategory = if (options.modHistoryCategories) manageHistoryCategory.getMangaCategory(manga.id) ?: 0L else 0L

        mangaObject.mergedMangaReferences = database.mergedQueries
            .selectByMergeId(manga.id, backupMergedMangaReferenceMapper)
            .awaitAsList()

        return mangaObject
    }
}

private fun Manga.toBackupManga() =
    BackupManga(
        url = this.url,
        title = this.title,
        artist = this.artist,
        author = this.author,
        description = this.description,
        genre = this.genre.orEmpty(),
        status = this.status.toInt(),
        thumbnailUrl = this.thumbnailUrl,
        favorite = this.favorite,
        source = this.source,
        dateAdded = this.favoriteAt.toEpochMillisOrZero(),
        viewer = (this.viewerFlags.toInt() and ReadingMode.MASK),
        viewer_flags = this.viewerFlags.toInt(),
        chapterFlags = this.chapterFlags.toInt(),
        updateStrategy = this.updateStrategy,
        notes = this.notes,
        initialized = this.initialized,
        memo = this.memo.toByteArray(),

    )

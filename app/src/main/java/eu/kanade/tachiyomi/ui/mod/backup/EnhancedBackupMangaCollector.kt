package eu.kanade.tachiyomi.ui.mod.backup

import app.cash.sqldelight.async.coroutines.awaitAsList
import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.data.backup.create.BackupOptions
import tachiyomi.data.Database
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository

/** A relationship can only be restored on an empty device if its manga is backed up too. */
@Inject
class EnhancedBackupMangaCollector(private val database: Database, private val mangas: MangaRepository) {
    suspend operator fun invoke(options: BackupOptions): List<Manga> {
        if (!options.libraryEntries) return emptyList()
        return database.enhanced_backupQueries.referencedMangaIds(
            historyCategories = if (options.modHistoryCategories) 1L else 0L,
            historyGroups = if (options.modHistoryGroups) 1L else 0L,
            linkedSources = if (options.modLinkedSources) 1L else 0L,
            updateWatch = if (options.modUpdateWatch) 1L else 0L,
        ).awaitAsList().map { mangas.getMangaById(checkNotNull(it)) }
    }
}

package eu.kanade.tachiyomi.data.backup.restore.restorers

import app.cash.sqldelight.async.coroutines.awaitAsList
import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.data.backup.models.BackupCategory
import eu.kanade.tachiyomi.data.backup.models.BackupHistoryCategory
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.backup.models.BackupMergedMangaReference
import kotlinx.coroutines.flow.first
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import tachiyomi.data.Database
import tachiyomi.data.manga.MergedMangaMapper
import tachiyomi.domain.backup.model.RestoredHistory
import tachiyomi.domain.backup.model.RestoredManga
import tachiyomi.domain.backup.repository.RestoreRepository
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.history.interactor.ManageHistoryCategory
import tachiyomi.domain.manga.interactor.FetchInterval
import tachiyomi.domain.manga.interactor.GetMangaByUrlAndSourceId
import tachiyomi.domain.manga.model.Manga
import kotlin.time.Clock

@Inject
class MangaRestorer(
    private val syncVersions: eu.kanade.tachiyomi.ui.mod.sync.EnhancedSyncVersions,
    private val database: Database,
    private val manageHistoryCategory: ManageHistoryCategory,
    private val getMangaByUrlAndSourceId: GetMangaByUrlAndSourceId,
    private val restoreRepository: RestoreRepository,
    private val getCategories: GetCategories,
    private val fetchInterval: FetchInterval,
) {

    private val timeZone = TimeZone.currentSystemDefault()
    private val now = Clock.System.now().toLocalDateTime(timeZone)
    private val currentFetchWindow = fetchInterval.getWindow(now.date, timeZone)

    suspend fun sortByNew(backupMangas: List<BackupManga>): List<BackupManga> {
        val urlsBySource = restoreRepository.getMangaUrlsBySourceId()

        return backupMangas
            .sortedBy { it.url in urlsBySource[it.source].orEmpty() }
    }

    /**
     * Restores [backupMangas] all together, so either every one of them is restored or none is.
     */
    suspend fun restore(
        backupMangas: List<BackupManga>,
        backupCategories: List<BackupCategory>,
    ) {
        val dbCategoriesByName = getCategories.await().associateBy { it.name }
        val backupCategoriesByOrder = backupCategories.associateBy { it.order }

        val entries = backupMangas.map { backupManga ->
            RestoredManga(
                manga = backupManga.getMangaImpl(),
                chapters = backupManga.chapters.map { it.toChapterImpl() },
                categoryIds = backupManga.categories.mapNotNull { backupCategoryOrder ->
                    backupCategoriesByOrder[backupCategoryOrder]?.let { backupCategory ->
                        dbCategoriesByName[backupCategory.name]?.id
                    }
                },
                history = backupManga.history.map {
                    val history = it.getHistoryImpl()
                    RestoredHistory(it.url, history.readAt, history.readDuration)
                },
                tracks = backupManga.tracking.map { it.getTrackImpl() },
                excludedScanlators = backupManga.excludedScanlators,
            )
        }

        syncVersions.restoring(backupMangas) {
            restoreRepository.restoreManga(entries) { manga ->
                fetchInterval.withFetchInterval(manga, now, timeZone, currentFetchWindow).apply {
                    // A next update still ahead was worked out with the chapters the backup came from
                    manga.nextUpdate
                        ?.takeIf { it >= currentFetchWindow.start }
                        ?.let { nextUpdate = it }
                }
            }
        }
    }

    suspend fun restore(
        backupManga: BackupManga,
        backupCategories: List<BackupCategory>,
        backupHistoryCategories: List<BackupHistoryCategory>,
        restoreHistoryCategories: Boolean,
    ): Long {
        restore(listOf(backupManga), backupCategories)
        val manga = checkNotNull(getMangaByUrlAndSourceId.await(backupManga.url, backupManga.source))
        if (restoreHistoryCategories) {
            restoreHistoryCategory(
                manga,
                backupManga.historyCategory,
                backupHistoryCategories,
            )
        }
        return manga.id
    }

    suspend fun restoreMergedRelationships(backupMangas: List<BackupManga>) {
        for (backupManga in backupMangas) {
            if (backupManga.mergedMangaReferences.isEmpty()) continue
            val parent = checkNotNull(getMangaByUrlAndSourceId.await(backupManga.url, backupManga.source))
            for (ref in backupManga.mergedMangaReferences) {
                val child = checkNotNull(getMangaByUrlAndSourceId.await(ref.mangaUrl, ref.mangaSourceId)) {
                    "Missing merged child ${ref.mangaUrl} from source ${ref.mangaSourceId}"
                }
                val existing = database.mergedQueries.selectByMergeId(parent.id, MergedMangaMapper::map).awaitAsList()
                if (existing.any { it.mangaSourceId == child.source && it.mangaUrl == child.url }) continue
                val value = ref.getMergedMangaReference()
                database.mergedQueries.insert(
                    infoManga = value.isInfoManga, getChapterUpdates = value.getChapterUpdates,
                    chapterSortMode = value.chapterSortMode.toLong(), chapterPriority = value.chapterPriority.toLong(),
                    downloadChapters = value.downloadChapters, mergeId = parent.id, mergeUrl = parent.url,
                    mangaId = child.id, mangaUrl = child.url, mangaSource = child.source,
                )
            }
        }
    }

    private suspend fun restoreHistoryCategory(
        manga: Manga,
        historyCategory: Long,
        backupHistoryCategories: List<BackupHistoryCategory>,
    ) {
        if (historyCategory == 0L) return

        val backupCategory = backupHistoryCategories.find { it.id == historyCategory } ?: return
        val dbCategories = manageHistoryCategory.subscribe().first()
        var dbCategory = dbCategories.find { it.name == backupCategory.name }
            ?: run {
                manageHistoryCategory.create(backupCategory.name, backupCategory.sort)
                manageHistoryCategory.subscribe().first().find { it.name == backupCategory.name }
            }

        if (dbCategory != null && dbCategory.sort != backupCategory.sort) {
            manageHistoryCategory.updateSort(dbCategory.id, backupCategory.sort)
            dbCategory = dbCategory.copy(sort = backupCategory.sort)
        }

        if (dbCategory != null) {
            manageHistoryCategory.moveToCategory(manga.id, dbCategory.id)
        }
    }
}

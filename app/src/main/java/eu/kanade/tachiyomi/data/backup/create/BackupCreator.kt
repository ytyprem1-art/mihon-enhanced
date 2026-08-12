package eu.kanade.tachiyomi.data.backup.create

import android.content.Context
import android.net.Uri
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.data.backup.BackupFileValidator
import eu.kanade.tachiyomi.data.backup.create.creators.CategoriesBackupCreator
import eu.kanade.tachiyomi.data.backup.create.creators.ExtensionStoresBackupCreator
import eu.kanade.tachiyomi.data.backup.create.creators.MangaBackupCreator
import eu.kanade.tachiyomi.data.backup.create.creators.PreferenceBackupCreator
import eu.kanade.tachiyomi.data.backup.create.creators.SourcesBackupCreator
import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupCategory
import eu.kanade.tachiyomi.data.backup.models.BackupExtensionStore
import eu.kanade.tachiyomi.data.backup.models.BackupHistoryCategory
import eu.kanade.tachiyomi.data.backup.models.BackupLinkedSourceGroup
import eu.kanade.tachiyomi.data.backup.models.BackupManualHistoryGroup
import eu.kanade.tachiyomi.data.backup.models.BackupModMember
import eu.kanade.tachiyomi.data.backup.models.BackupUpdateWatch
import eu.kanade.tachiyomi.data.backup.models.BackupUpdateWatchHistory
import eu.kanade.tachiyomi.data.backup.models.BackupUpdateWatchInboxItem
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.backup.models.BackupPreference
import eu.kanade.tachiyomi.data.backup.models.BackupSource
import eu.kanade.tachiyomi.data.backup.models.BackupSourcePreferences
import kotlinx.serialization.protobuf.ProtoBuf
import logcat.LogPriority
import okio.buffer
import tachiyomi.domain.history.interactor.ManageHistoryCategory
import tachiyomi.domain.history.group.interactor.ManageHistoryGroups
import tachiyomi.domain.source.linked.interactor.ManageLinkedSourceGroup
import tachiyomi.domain.history.interactor.ManageUpdateWatch
import tachiyomi.domain.history.interactor.GetUpdateWatchInbox
import tachiyomi.domain.history.interactor.GetUpdateWatchHistory
import tachiyomi.domain.chapter.repository.ChapterRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import okio.gzip
import okio.sink
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.backup.service.BackupPreferences
import tachiyomi.domain.manga.interactor.GetFavorites
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.time.Clock

class BackupCreator(
    private val context: Context,
    private val isAutoBackup: Boolean,

    private val parser: ProtoBuf = Injekt.get(),
    private val getFavorites: GetFavorites = Injekt.get(),
    private val backupPreferences: BackupPreferences = Injekt.get(),
    private val mangaRepository: MangaRepository = Injekt.get(),
    private val manageHistoryCategory: ManageHistoryCategory = Injekt.get(),
    private val manageHistoryGroups: ManageHistoryGroups = Injekt.get(),
    private val manageLinkedSourceGroup: ManageLinkedSourceGroup = Injekt.get(),
    private val manageUpdateWatch: ManageUpdateWatch = Injekt.get(),
    private val getUpdateWatchInbox: GetUpdateWatchInbox = Injekt.get(),
    private val getUpdateWatchHistory: GetUpdateWatchHistory = Injekt.get(),
    private val chapterRepository: ChapterRepository = Injekt.get(),

    private val categoriesBackupCreator: CategoriesBackupCreator = CategoriesBackupCreator(),
    private val mangaBackupCreator: MangaBackupCreator = MangaBackupCreator(),
    private val preferenceBackupCreator: PreferenceBackupCreator = PreferenceBackupCreator(),
    private val extensionStoresBackupCreator: ExtensionStoresBackupCreator = ExtensionStoresBackupCreator(),
    private val sourcesBackupCreator: SourcesBackupCreator = SourcesBackupCreator(),
) {

    suspend fun backup(uri: Uri, options: BackupOptions): String {
        var file: UniFile? = null
        try {
            file = if (isAutoBackup) {
                // Get dir of file and create
                val dir = UniFile.fromUri(context, uri)

                // Delete older backups
                dir?.listFiles { _, filename -> FILENAME_REGEX.matches(filename) }
                    .orEmpty()
                    .sortedByDescending { it.name }
                    .drop(MAX_AUTO_BACKUPS - 1)
                    .forEach { it.delete() }

                // Create new file to place backup
                dir?.createFile(getFilename())
            } else {
                UniFile.fromUri(context, uri)
            }

            if (file == null || !file.isFile) {
                throw IllegalStateException(context.stringResource(MR.strings.create_backup_file_error))
            }

            val nonFavoriteManga = if (options.readEntries) mangaRepository.getReadMangaNotInLibrary() else emptyList()
            val backupManga = backupMangas(getFavorites.await() + nonFavoriteManga, options)

            val backupUpdateWatch = mutableListOf<BackupUpdateWatch>()
            manageUpdateWatch.getAll().forEach {
                val member = getModMember(it.mangaId) ?: return@forEach
                backupUpdateWatch.add(
                    BackupUpdateWatch(
                        member = member,
                        isPaused = it.isPaused,
                        backgroundRefreshEnabled = it.backgroundRefreshEnabled,
                        expectedIntervalDays = it.expectedIntervalDays,
                        refreshProfile = it.refreshProfile.ordinal,
                        lastBackgroundCheckAt = it.lastBackgroundCheckAt,
                        lastWarnedMilestone = it.lastWarnedMilestone,
                    )
                )
            }

            val backupUpdateWatchInbox = mutableListOf<BackupUpdateWatchInboxItem>()
            getUpdateWatchInbox.await().forEach { item ->
                val member = getModMember(item.mangaId) ?: return@forEach
                val latestChapterUrl = chapterRepository.getChapterById(item.latestChapterId)?.url ?: return@forEach
                val chapterUrls = item.chapterIds.mapNotNull { id -> chapterRepository.getChapterById(id)?.url }

                backupUpdateWatchInbox.add(
                    BackupUpdateWatchInboxItem(
                        member = member,
                        mangaTitle = item.mangaTitle,
                        sourceName = item.sourceName,
                        chapterCount = item.chapterCount,
                        chapterRange = item.chapterRange,
                        firstFoundAt = item.firstFoundAt,
                        lastFoundAt = item.lastFoundAt,
                        latestChapterUrl = latestChapterUrl,
                        latestChapterNumber = item.latestChapterNumber,
                        chapterUrls = chapterUrls,
                        latestChapterUploadAt = item.latestChapterUploadAt,
                        type = item.type,
                        milestone = item.milestone,
                    )
                )
            }

            val backupUpdateWatchHistory = mutableListOf<BackupUpdateWatchHistory>()
            getUpdateWatchHistory.awaitAll().forEach {
                val member = getModMember(it.mangaId) ?: return@forEach
                backupUpdateWatchHistory.add(
                    BackupUpdateWatchHistory(
                        member = member,
                        timestamp = it.timestamp,
                        success = it.success,
                        newChapters = it.newChapters,
                        category = it.category.ordinal,
                        detail = it.detail,
                    )
                )
            }

            val backup = Backup(
                backupManga = backupManga,
                backupCategories = backupCategories(options),
                backupSources = backupSources(backupManga),
                backupPreferences = backupAppPreferences(options),
                backupExtensionStores = backupExtensionStores(options),
                backupSourcePreferences = backupSourcePreferences(options),
                backupHistoryCategories = if (options.modHistoryCategories) {
                    manageHistoryCategory.subscribe().first().map {
                        BackupHistoryCategory(it.name, it.id, it.sort)
                    }
                } else {
                    emptyList()
                },
                backupLinkedSourceGroups = if (options.modLinkedSources) {
                    manageLinkedSourceGroup.subscribe().first().map { group ->
                        BackupLinkedSourceGroup(
                            name = group.name,
                            members = manageLinkedSourceGroup.subscribeMemberIds(group.id).first().mapNotNull {
                                getModMember(it)
                            }
                        )
                    }
                } else {
                    emptyList()
                },
                backupManualHistoryGroups = if (options.modHistoryGroups) {
                    manageHistoryGroups.subscribe().first().map { group ->
                        BackupManualHistoryGroup(
                            name = group.name,
                            members = manageHistoryGroups.subscribeMembers(group.id).first().mapNotNull {
                                getModMember(it)
                            }
                        )
                    }
                } else {
                    emptyList()
                },
                backupUpdateWatch = if (options.modUpdateWatch) backupUpdateWatch else emptyList(),
                backupUpdateWatchInbox = if (options.modUpdateWatch) backupUpdateWatchInbox else emptyList(),
                backupUpdateWatchHistory = if (options.modUpdateWatch) backupUpdateWatchHistory else emptyList(),
            )

            val byteArray = parser.encodeToByteArray(Backup.serializer(), backup)
            if (byteArray.isEmpty()) {
                throw IllegalStateException(context.stringResource(MR.strings.empty_backup_error))
            }

            file.openOutputStream()
                .also {
                    // Force overwrite old file
                    (it as? FileOutputStream)?.channel?.truncate(0)
                }
                .sink().gzip().buffer().use {
                    it.write(byteArray)
                }
            val fileUri = file.uri

            // Make sure it's a valid backup file
            BackupFileValidator(context).validate(fileUri)

            if (isAutoBackup) {
                backupPreferences.lastAutoBackupTimestamp.set(Clock.System.now().toEpochMilliseconds())
            }

            return fileUri.toString()
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
            file?.delete()
            throw e
        }
    }

    private suspend fun backupCategories(options: BackupOptions): List<BackupCategory> {
        if (!options.categories) return emptyList()

        return categoriesBackupCreator()
    }

    private suspend fun backupMangas(mangas: List<Manga>, options: BackupOptions): List<BackupManga> {
        if (!options.libraryEntries) return emptyList()

        return mangaBackupCreator(mangas, options)
    }

    private fun backupSources(mangas: List<BackupManga>): List<BackupSource> {
        return sourcesBackupCreator(mangas)
    }

    private fun backupAppPreferences(options: BackupOptions): List<BackupPreference> {
        if (!options.appSettings) return emptyList()

        return preferenceBackupCreator.createApp(includePrivatePreferences = options.privateSettings)
    }

    private suspend fun backupExtensionStores(options: BackupOptions): List<BackupExtensionStore> {
        if (!options.extensionStores) return emptyList()

        return extensionStoresBackupCreator()
    }

    private fun backupSourcePreferences(options: BackupOptions): List<BackupSourcePreferences> {
        if (!options.sourceSettings) return emptyList()

        return preferenceBackupCreator.createSource(includePrivatePreferences = options.privateSettings)
    }

    private suspend fun getModMember(mangaId: Long): BackupModMember? {
        return try {
            val manga = mangaRepository.getMangaById(mangaId)
            BackupModMember(manga.source, manga.url)
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        private const val MAX_AUTO_BACKUPS: Int = 4
        private val FILENAME_REGEX = """${BuildConfig.APPLICATION_ID}_\d{4}-\d{2}-\d{2}_\d{2}-\d{2}.tachibk""".toRegex()

        fun getFilename(): String {
            val date = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.ENGLISH).format(Date())
            return "${BuildConfig.APPLICATION_ID}_$date.tachibk"
        }
    }
}

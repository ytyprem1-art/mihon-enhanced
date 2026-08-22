package eu.kanade.tachiyomi.data.sync.service

import android.content.Context
import eu.kanade.domain.sync.SyncPreferences
import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupCategory
import eu.kanade.tachiyomi.data.backup.models.BackupChapter
import eu.kanade.tachiyomi.data.backup.models.BackupExtensionStore
import eu.kanade.tachiyomi.data.backup.models.BackupHistoryCategory
import eu.kanade.tachiyomi.data.backup.models.BackupLinkedSourceGroup
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.backup.models.BackupManualHistoryGroup
import eu.kanade.tachiyomi.data.backup.models.BackupPreference
import eu.kanade.tachiyomi.data.backup.models.BackupSourcePreferences
import eu.kanade.tachiyomi.data.backup.models.BackupUpdateWatch
import eu.kanade.tachiyomi.data.backup.models.BackupUpdateWatchHistory
import eu.kanade.tachiyomi.data.backup.models.BackupUpdateWatchInboxItem
import eu.kanade.tachiyomi.data.sync.SyncData
import kotlinx.serialization.json.Json
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

/**
 * Abstract base class for sync services that provides common merge logic
 * for synchronizing data between local and remote sources.
 *
 * @param context The application context
 * @param json The JSON serializer for preferences
 * @param syncPreferences The sync preferences containing device ID and settings
 */
abstract class SyncService(
    val context: Context,
    val json: Json,
    val syncPreferences: SyncPreferences,
) {
    companion object {
        private const val TAG = "SyncService"
    }

    /**
     * Performs the sync operation between local and remote data.
     *
     * @param syncData The local sync data to be synchronized
     * @return The merged backup data after synchronization, or null if sync failed
     */
    abstract suspend fun doSync(syncData: SyncData): Backup?

    /**
     * Merges local and remote sync data, selecting the most recent versions
     * of each item based on version numbers.
     *
     * @param localSyncData The local sync data
     * @param remoteSyncData The remote sync data from sync service
     * @return The merged SyncData object
     */
    protected fun mergeSyncData(localSyncData: SyncData, remoteSyncData: SyncData): SyncData {
        logcat(LogPriority.DEBUG) { "Starting sync data merge" }

        val mergedMangaCategoriesList = mergeCategoriesLists(
            localSyncData.backup?.backupCategories,
            remoteSyncData.backup?.backupCategories,
        )

        val mergedHistoryCategoriesList = mergeHistoryCategoriesLists(
            localSyncData.backup?.backupHistoryCategories,
            remoteSyncData.backup?.backupHistoryCategories,
        )

        val mergedMangaList = mergeMangaLists(
            localMangaList = localSyncData.backup?.backupManga,
            remoteMangaList = remoteSyncData.backup?.backupManga,
            localCategories = localSyncData.backup?.backupCategories ?: emptyList(),
            remoteCategories = remoteSyncData.backup?.backupCategories ?: emptyList(),
            mergedCategories = mergedMangaCategoriesList,
            localHistoryCategories = localSyncData.backup?.backupHistoryCategories ?: emptyList(),
            remoteHistoryCategories = remoteSyncData.backup?.backupHistoryCategories ?: emptyList(),
            mergedHistoryCategories = mergedHistoryCategoriesList,
        )

        val mergedSourcesList = mergeSourcesLists(
            localSyncData.backup?.backupSources,
            remoteSyncData.backup?.backupSources,
        )

        val mergedPreferencesList = mergePreferencesLists(
            localSyncData.backup?.backupPreferences,
            remoteSyncData.backup?.backupPreferences,
        )

        val mergedSourcePreferencesList = mergeSourcePreferencesLists(
            localSyncData.backup?.backupSourcePreferences,
            remoteSyncData.backup?.backupSourcePreferences,
        )

        val mergedExtensionStoresList = mergeExtensionStoreLists(
            localSyncData.backup?.backupExtensionStores,
            remoteSyncData.backup?.backupExtensionStores,
        )

        val mergedBackup = Backup(
            backupManga = mergedMangaList,
            backupCategories = mergedMangaCategoriesList,
            backupSources = mergedSourcesList,
            backupPreferences = mergedPreferencesList,
            backupSourcePreferences = mergedSourcePreferencesList,
            backupExtensionStores = mergedExtensionStoresList,
            backupHistoryCategories = mergedHistoryCategoriesList,
            backupLinkedSourceGroups = mergeLinkedSourceGroupsLists(
                localSyncData.backup?.backupLinkedSourceGroups,
                remoteSyncData.backup?.backupLinkedSourceGroups,
            ),
            backupManualHistoryGroups = mergeManualHistoryGroupsLists(
                localSyncData.backup?.backupManualHistoryGroups,
                remoteSyncData.backup?.backupManualHistoryGroups,
            ),
            backupUpdateWatch = mergeUpdateWatchLists(
                localSyncData.backup?.backupUpdateWatch,
                remoteSyncData.backup?.backupUpdateWatch,
            ),
            backupUpdateWatchInbox = mergeUpdateWatchInboxLists(
                localSyncData.backup?.backupUpdateWatchInbox,
                remoteSyncData.backup?.backupUpdateWatchInbox,
            ),
            backupUpdateWatchHistory = mergeUpdateWatchHistoryLists(
                localSyncData.backup?.backupUpdateWatchHistory,
                remoteSyncData.backup?.backupUpdateWatchHistory,
            ),
        )

        return SyncData(
            deviceId = syncPreferences.uniqueDeviceID(),
            backup = mergedBackup,
        )
    }

    /**
     * Merges two lists of BackupManga objects, selecting the most recent version
     * based on the version field and merging chapters.
     */
    protected fun mergeMangaLists(
        localMangaList: List<BackupManga>?,
        remoteMangaList: List<BackupManga>?,
        localCategories: List<BackupCategory>,
        remoteCategories: List<BackupCategory>,
        mergedCategories: List<BackupCategory>,
        localHistoryCategories: List<BackupHistoryCategory>,
        remoteHistoryCategories: List<BackupHistoryCategory>,
        mergedHistoryCategories: List<BackupHistoryCategory>,
    ): List<BackupManga> {
        val localMangaListSafe = localMangaList.orEmpty()
        val remoteMangaListSafe = remoteMangaList.orEmpty()

        fun mangaCompositeKey(manga: BackupManga): String {
            return "${manga.source}|${manga.url}|${manga.title.lowercase().trim()}|${manga.author?.lowercase()?.trim()}"
        }

        val localMangaMap = localMangaListSafe.associateBy { mangaCompositeKey(it) }
        val remoteMangaMap = remoteMangaListSafe.associateBy { mangaCompositeKey(it) }

        val localCategoriesMapByOrder = localCategories.associateBy { it.order }
        val remoteCategoriesMapByOrder = remoteCategories.associateBy { it.order }
        val mergedCategoriesMapByName = mergedCategories.associateBy { it.name }

        val localHistoryCategoriesMapById = localHistoryCategories.associateBy { it.id }
        val remoteHistoryCategoriesMapById = remoteHistoryCategories.associateBy { it.id }
        val mergedHistoryCategoriesMapByName = mergedHistoryCategories.associateBy { it.name }

        fun updateCategories(theManga: BackupManga, theMap: Map<Long, BackupCategory>): BackupManga {
            theManga.categories = theManga.categories.mapNotNull {
                theMap[it]?.let { category ->
                    mergedCategoriesMapByName[category.name]?.order
                }
            }
            return theManga
        }

        fun updateHistoryCategory(theManga: BackupManga, theMap: Map<Long, BackupHistoryCategory>): BackupManga {
            if (theManga.historyCategory != 0L) {
                theMap[theManga.historyCategory]?.let { category ->
                    mergedHistoryCategoriesMapByName[category.name]?.let { mergedCategory ->
                        theManga.historyCategory = mergedCategory.id
                    }
                }
            }
            return theManga
        }

        val mergedList = (localMangaMap.keys + remoteMangaMap.keys).distinct().mapNotNull { compositeKey ->
            val local = localMangaMap[compositeKey]
            val remote = remoteMangaMap[compositeKey]

            when {
                local != null && remote == null -> {
                    updateCategories(local, localCategoriesMapByOrder)
                    updateHistoryCategory(local, localHistoryCategoriesMapById)
                }
                local == null && remote != null -> {
                    updateCategories(remote, remoteCategoriesMapByOrder)
                    updateHistoryCategory(remote, remoteHistoryCategoriesMapById)
                }
                local != null && remote != null -> {
                    if (local.version >= remote.version) {
                        local.chapters = mergeChapters(local.chapters, remote.chapters)
                        updateCategories(local, localCategoriesMapByOrder)
                        updateHistoryCategory(local, localHistoryCategoriesMapById)
                    } else {
                        remote.chapters = mergeChapters(local.chapters, remote.chapters)
                        updateCategories(remote, remoteCategoriesMapByOrder)
                        updateHistoryCategory(remote, remoteHistoryCategoriesMapById)
                    }
                }
                else -> null
            }
        }

        logcat(LogPriority.DEBUG) {
            "Manga merge completed. Total: ${mergedList.size}, Local: ${localMangaListSafe.size}, Remote: ${remoteMangaListSafe.size}"
        }

        return mergedList
    }

    /**
     * Merges chapters from local and remote sources.
     */
    protected fun mergeChapters(
        localChapters: List<BackupChapter>,
        remoteChapters: List<BackupChapter>,
    ): List<BackupChapter> {
        fun chapterCompositeKey(chapter: BackupChapter): String {
            return "${chapter.url}|${chapter.name}|${chapter.chapterNumber}"
        }

        val localChapterMap = localChapters.associateBy { chapterCompositeKey(it) }
        val remoteChapterMap = remoteChapters.associateBy { chapterCompositeKey(it) }

        return (localChapterMap.keys + remoteChapterMap.keys).distinct().mapNotNull { compositeKey ->
            val localChapter = localChapterMap[compositeKey]
            val remoteChapter = remoteChapterMap[compositeKey]

            when {
                localChapter != null && remoteChapter == null -> localChapter
                localChapter == null && remoteChapter != null -> remoteChapter
                localChapter != null && remoteChapter != null -> {
                    if (localChapter.version >= remoteChapter.version) {
                        localChapter
                    } else {
                        remoteChapter
                    }
                }
                else -> null
            }
        }
    }

    /**
     * Merges categories lists, prioritizing the category with the higher order value.
     */
    protected fun mergeCategoriesLists(
        localCategoriesList: List<BackupCategory>?,
        remoteCategoriesList: List<BackupCategory>?,
    ): List<BackupCategory> {
        if (localCategoriesList == null) return remoteCategoriesList ?: emptyList()
        if (remoteCategoriesList == null) return localCategoriesList

        val localCategoriesMap = localCategoriesList.associateBy { it.name }
        val remoteCategoriesMap = remoteCategoriesList.associateBy { it.name }

        val mergedCategoriesMap = mutableMapOf<String, BackupCategory>()

        localCategoriesMap.forEach { (name, localCategory) ->
            val remoteCategory = remoteCategoriesMap[name]
            if (remoteCategory != null) {
                mergedCategoriesMap[name] = if (localCategory.order > remoteCategory.order) {
                    localCategory
                } else {
                    remoteCategory
                }
            } else {
                mergedCategoriesMap[name] = localCategory
            }
        }

        remoteCategoriesMap.forEach { (name, remoteCategory) ->
            if (!mergedCategoriesMap.containsKey(name)) {
                mergedCategoriesMap[name] = remoteCategory
            }
        }

        return mergedCategoriesMap.values.toList()
    }

    /**
     * Merges sources lists.
     */
    protected fun mergeSourcesLists(
        localSources: List<eu.kanade.tachiyomi.data.backup.models.BackupSource>?,
        remoteSources: List<eu.kanade.tachiyomi.data.backup.models.BackupSource>?,
    ): List<eu.kanade.tachiyomi.data.backup.models.BackupSource> {
        val localSourceMap = localSources?.associateBy { it.sourceId } ?: emptyMap()
        val remoteSourceMap = remoteSources?.associateBy { it.sourceId } ?: emptyMap()

        return (localSourceMap.keys + remoteSourceMap.keys).distinct().mapNotNull { sourceId ->
            localSourceMap[sourceId] ?: remoteSourceMap[sourceId]
        }
    }

    /**
     * Merges preferences lists.
     */
    protected fun mergePreferencesLists(
        localPreferences: List<BackupPreference>?,
        remotePreferences: List<BackupPreference>?,
    ): List<BackupPreference> {
        val localPreferencesMap = localPreferences?.associateBy { it.key } ?: emptyMap()
        val remotePreferencesMap = remotePreferences?.associateBy { it.key } ?: emptyMap()

        return (localPreferencesMap.keys + remotePreferencesMap.keys).distinct().mapNotNull { key ->
            localPreferencesMap[key] ?: remotePreferencesMap[key]
        }
    }

    /**
     * Merges source preferences lists.
     */
    protected fun mergeSourcePreferencesLists(
        localPreferences: List<BackupSourcePreferences>?,
        remotePreferences: List<BackupSourcePreferences>?,
    ): List<BackupSourcePreferences> {
        val localPreferencesMap = localPreferences?.associateBy { it.sourceKey } ?: emptyMap()
        val remotePreferencesMap = remotePreferences?.associateBy { it.sourceKey } ?: emptyMap()

        return (localPreferencesMap.keys + remotePreferencesMap.keys).distinct().mapNotNull { sourceKey ->
            val localSourcePreference = localPreferencesMap[sourceKey]
            val remoteSourcePreference = remotePreferencesMap[sourceKey]

            when {
                localSourcePreference != null && remoteSourcePreference == null -> localSourcePreference
                localSourcePreference == null && remoteSourcePreference != null -> remoteSourcePreference
                else -> null
            }
        }
    }

    /**
     * Merges history categories lists, prioritizing the local version if the category exists in both.
     */
    protected fun mergeHistoryCategoriesLists(
        local: List<BackupHistoryCategory>?,
        remote: List<BackupHistoryCategory>?,
    ): List<BackupHistoryCategory> {
        if (local == null) return remote ?: emptyList()
        if (remote == null) return local

        val localMap = local.associateBy { it.name }
        val remoteMap = remote.associateBy { it.name }

        val mergedMap = mutableMapOf<String, BackupHistoryCategory>()

        localMap.forEach { (name, localCat) ->
            mergedMap[name] = localCat
        }

        remoteMap.forEach { (name, remoteCat) ->
            if (!mergedMap.containsKey(name)) {
                mergedMap[name] = remoteCat
            }
        }

        return mergedMap.values.toList()
    }

    /**
     * Merges linked source groups lists.
     */
    protected fun mergeLinkedSourceGroupsLists(
        local: List<BackupLinkedSourceGroup>?,
        remote: List<BackupLinkedSourceGroup>?,
    ): List<BackupLinkedSourceGroup> {
        if (local == null) return remote ?: emptyList()
        if (remote == null) return local

        val localMap = local.associateBy { it.name }
        val remoteMap = remote.associateBy { it.name }

        val mergedMap = mutableMapOf<String, BackupLinkedSourceGroup>()

        localMap.forEach { (name, localGroup) ->
            val remoteGroup = remoteMap[name]
            if (remoteGroup != null) {
                val mergedMembers = (localGroup.members + remoteGroup.members).distinctBy { it.source to it.url }
                mergedMap[name] = BackupLinkedSourceGroup(name, mergedMembers)
            } else {
                mergedMap[name] = localGroup
            }
        }

        remoteMap.forEach { (name, remoteGroup) ->
            if (!mergedMap.containsKey(name)) {
                mergedMap[name] = remoteGroup
            }
        }

        return mergedMap.values.toList()
    }

    /**
     * Merges manual history groups lists.
     */
    protected fun mergeManualHistoryGroupsLists(
        local: List<BackupManualHistoryGroup>?,
        remote: List<BackupManualHistoryGroup>?,
    ): List<BackupManualHistoryGroup> {
        if (local == null) return remote ?: emptyList()
        if (remote == null) return local

        val localMap = local.associateBy { it.name }
        val remoteMap = remote.associateBy { it.name }

        val mergedMap = mutableMapOf<String, BackupManualHistoryGroup>()

        localMap.forEach { (name, localGroup) ->
            val remoteGroup = remoteMap[name]
            if (remoteGroup != null) {
                val mergedMembers = (localGroup.members + remoteGroup.members).distinctBy { it.source to it.url }
                mergedMap[name] = BackupManualHistoryGroup(name, mergedMembers)
            } else {
                mergedMap[name] = localGroup
            }
        }

        remoteMap.forEach { (name, remoteGroup) ->
            if (!mergedMap.containsKey(name)) {
                mergedMap[name] = remoteGroup
            }
        }

        return mergedMap.values.toList()
    }

    /**
     * Merges update watch lists.
     */
    protected fun mergeUpdateWatchLists(
        local: List<BackupUpdateWatch>?,
        remote: List<BackupUpdateWatch>?,
    ): List<BackupUpdateWatch> {
        if (local == null) return remote ?: emptyList()
        if (remote == null) return local

        val localMap = local.associateBy { it.member.source to it.member.url }
        val remoteMap = remote.associateBy { it.member.source to it.member.url }

        val mergedMap = mutableMapOf<Pair<Long, String>, BackupUpdateWatch>()

        localMap.forEach { (key, localWatch) ->
            val remoteWatch = remoteMap[key]
            if (remoteWatch != null) {
                // If exists in both, keep the one with newer lastBackgroundCheckAt if available
                mergedMap[key] = if ((localWatch.lastBackgroundCheckAt ?: 0) >= (remoteWatch.lastBackgroundCheckAt ?: 0)) {
                    localWatch
                } else {
                    remoteWatch
                }
            } else {
                mergedMap[key] = localWatch
            }
        }

        remoteMap.forEach { (key, remoteWatch) ->
            if (!mergedMap.containsKey(key)) {
                mergedMap[key] = remoteWatch
            }
        }

        return mergedMap.values.toList()
    }

    /**
     * Merges update watch inbox lists.
     */
    protected fun mergeUpdateWatchInboxLists(
        local: List<BackupUpdateWatchInboxItem>?,
        remote: List<BackupUpdateWatchInboxItem>?,
    ): List<BackupUpdateWatchInboxItem> {
        if (local == null) return remote ?: emptyList()
        if (remote == null) return local

        val localMap = local.associateBy { it.member.source to it.member.url }
        val remoteMap = remote.associateBy { it.member.source to it.member.url }

        val mergedMap = mutableMapOf<Pair<Long, String>, BackupUpdateWatchInboxItem>()

        localMap.forEach { (key, localItem) ->
            val remoteItem = remoteMap[key]
            if (remoteItem != null) {
                mergedMap[key] = if (localItem.lastFoundAt >= remoteItem.lastFoundAt) {
                    localItem
                } else {
                    remoteItem
                }
            } else {
                mergedMap[key] = localItem
            }
        }

        remoteMap.forEach { (key, remoteItem) ->
            if (!mergedMap.containsKey(key)) {
                mergedMap[key] = remoteItem
            }
        }

        return mergedMap.values.toList()
    }

    /**
     * Merges update watch history lists.
     */
    protected fun mergeUpdateWatchHistoryLists(
        local: List<BackupUpdateWatchHistory>?,
        remote: List<BackupUpdateWatchHistory>?,
    ): List<BackupUpdateWatchHistory> {
        if (local == null) return remote ?: emptyList()
        if (remote == null) return local

        // History items are unique by (member, timestamp)
        val localMap = local.associateBy { (it.member.source to it.member.url) to it.timestamp }
        val remoteMap = remote.associateBy { (it.member.source to it.member.url) to it.timestamp }

        val mergedMap = mutableMapOf<Pair<Pair<Long, String>, Long>, BackupUpdateWatchHistory>()

        localMap.forEach { (key, value) ->
            mergedMap[key] = value
        }

        remoteMap.forEach { (key, value) ->
            if (!mergedMap.containsKey(key)) {
                mergedMap[key] = value
            }
        }

        return mergedMap.values.toList()
    }

    /**
     * Merges extension store lists.
     */
    protected fun mergeExtensionStoreLists(
        local: List<BackupExtensionStore>?,
        remote: List<BackupExtensionStore>?,
    ): List<BackupExtensionStore> {
        if (local == null) return remote ?: emptyList()
        if (remote == null) return local

        val localMap = local.associateBy { it.indexUrl }
        val remoteMap = remote.associateBy { it.indexUrl }

        val mergedMap = mutableMapOf<String, BackupExtensionStore>()

        localMap.forEach { (url, value) ->
            mergedMap[url] = value
        }

        remoteMap.forEach { (url, value) ->
            if (!mergedMap.containsKey(url)) {
                mergedMap[url] = value
            }
        }

        return mergedMap.values.toList()
    }
}

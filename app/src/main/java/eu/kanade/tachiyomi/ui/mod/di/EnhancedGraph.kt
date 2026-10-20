package eu.kanade.tachiyomi.ui.mod.di

interface EnhancedGraph {
    val enhancedBackupCreatorFactory: eu.kanade.tachiyomi.data.backup.create.BackupCreator.Factory
    val enhancedBackupRestorerFactory: eu.kanade.tachiyomi.data.backup.restore.BackupRestorer.Factory
    val enhancedBackupNotifier: eu.kanade.tachiyomi.data.backup.BackupNotifier
    val enhancedUpdateWatchDismissalStore: eu.kanade.tachiyomi.ui.mod.updatewatch.UpdateWatchDismissalStore
    val enhancedModRestorer: eu.kanade.tachiyomi.data.backup.restore.restorers.ModRestorer
    val enhancedMangaMergeRepository: tachiyomi.domain.manga.repository.MangaMergeRepository
    val enhancedBackupMangaCollector: eu.kanade.tachiyomi.ui.mod.backup.EnhancedBackupMangaCollector
    val enhancedMangaBackupCreator: eu.kanade.tachiyomi.data.backup.create.creators.MangaBackupCreator
    val enhancedMangaRestorer: eu.kanade.tachiyomi.data.backup.restore.restorers.MangaRestorer
    val enhancedSyncVersions: eu.kanade.tachiyomi.ui.mod.sync.EnhancedSyncVersions
    val enhancedSqlDriver: app.cash.sqldelight.db.SqlDriver
    val enhancedGetEnabledSources: eu.kanade.domain.source.interactor.GetEnabledSources
    val enhancedBookmarkImportPreferences: eu.kanade.tachiyomi.ui.mod.bookmarkimport.BookmarkImportPreferences
    val enhancedChapterRepository: tachiyomi.domain.chapter.repository.ChapterRepository
    val enhancedChatManager: eu.kanade.tachiyomi.ui.chat.ChatManager
    val enhancedDatabase: tachiyomi.data.Database
    val enhancedDownloadManager: eu.kanade.tachiyomi.data.download.DownloadManager
    val enhancedDownloadPreferences: tachiyomi.domain.download.service.DownloadPreferences
    val enhancedEnhancedPreferences: eu.kanade.tachiyomi.ui.mod.EnhancedPreferences
    val enhancedFetchInterval: tachiyomi.domain.manga.interactor.FetchInterval
    val enhancedFilterChaptersForDownload: mihon.domain.chapter.interactor.FilterChaptersForDownload
    val enhancedGetCategories: tachiyomi.domain.category.interactor.GetCategories
    val enhancedGetChaptersByMangaId: tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
    val enhancedGetHistory: tachiyomi.domain.history.interactor.GetHistory
    val enhancedGetLibraryManga: tachiyomi.domain.manga.interactor.GetLibraryManga
    val enhancedGetManga: tachiyomi.domain.manga.interactor.GetManga
    val enhancedGetMangaByUrlAndSourceId: tachiyomi.domain.manga.interactor.GetMangaByUrlAndSourceId
    val enhancedGetMergedMangaForDownloading: tachiyomi.domain.manga.interactor.GetMergedMangaForDownloading
    val enhancedGetMergedReferencesById: tachiyomi.domain.manga.interactor.GetMergedReferencesById
    val enhancedGetTracks: tachiyomi.domain.track.interactor.GetTracks
    val enhancedGetUpdateWatchHistory: tachiyomi.domain.history.interactor.GetUpdateWatchHistory
    val enhancedGetUpdateWatchInbox: tachiyomi.domain.history.interactor.GetUpdateWatchInbox
    val enhancedGoogleDriveService: eu.kanade.tachiyomi.data.sync.service.GoogleDriveService
    val enhancedGoogleDriveSyncService: eu.kanade.tachiyomi.data.sync.service.GoogleDriveSyncService
    val enhancedHistoryRepository: tachiyomi.domain.history.repository.HistoryRepository
    val enhancedLibraryPreferences: tachiyomi.domain.library.service.LibraryPreferences
    val enhancedManageHistoryCategory: tachiyomi.domain.history.interactor.ManageHistoryCategory
    val enhancedManageHistoryGroups: tachiyomi.domain.history.group.interactor.ManageHistoryGroups
    val enhancedManageLinkedSourceGroup: tachiyomi.domain.source.linked.interactor.ManageLinkedSourceGroup
    val enhancedManageUpdateWatch: tachiyomi.domain.history.interactor.ManageUpdateWatch
    val enhancedManageUpdateWatchHistory: tachiyomi.domain.history.interactor.ManageUpdateWatchHistory
    val enhancedManageUpdateWatchInbox: tachiyomi.domain.history.interactor.ManageUpdateWatchInbox
    val enhancedMangaRepository: tachiyomi.domain.manga.repository.MangaRepository
    val enhancedNetworkHelper: eu.kanade.tachiyomi.network.NetworkHelper
    val enhancedNetworkToLocalManga: tachiyomi.domain.manga.interactor.NetworkToLocalManga
    val enhancedPreferenceStore: tachiyomi.core.common.preference.PreferenceStore
    val enhancedPresenceManager: eu.kanade.tachiyomi.ui.chat.PresenceManager
    val enhancedSetReadStatus: eu.kanade.domain.chapter.interactor.SetReadStatus
    val enhancedSmartSearchMerge: tachiyomi.domain.manga.interactor.SmartSearchMerge
    val enhancedSourceManager: tachiyomi.domain.source.service.SourceManager
    val enhancedSourcePreferences: eu.kanade.domain.source.service.SourcePreferences
    val enhancedSyncChaptersWithSource: eu.kanade.domain.chapter.interactor.SyncChaptersWithSource
    val enhancedSyncManager: eu.kanade.tachiyomi.data.sync.SyncManager
    val enhancedSyncPreferences: eu.kanade.domain.sync.SyncPreferences
    val enhancedUpdateManga: eu.kanade.domain.manga.interactor.UpdateManga
    val enhancedUpdateMangaFromRemote: mihon.domain.source.interactor.UpdateMangaFromRemote
    val enhancedUpdateWatchDiagnosticsPreferences: eu.kanade.tachiyomi.ui.mod.updatewatch.worker.UpdateWatchDiagnosticsPreferences
}

internal fun enhancedInjektBindings(graph: EnhancedGraph): Map<java.lang.reflect.Type, () -> Any> = mapOf(
    eu.kanade.domain.source.interactor.GetEnabledSources::class.java to { graph.enhancedGetEnabledSources },
    eu.kanade.tachiyomi.ui.mod.bookmarkimport.BookmarkImportPreferences::class.java to { graph.enhancedBookmarkImportPreferences },
    tachiyomi.domain.chapter.repository.ChapterRepository::class.java to { graph.enhancedChapterRepository },
    eu.kanade.tachiyomi.ui.chat.ChatManager::class.java to { graph.enhancedChatManager },
    tachiyomi.data.Database::class.java to { graph.enhancedDatabase },
    eu.kanade.tachiyomi.data.download.DownloadManager::class.java to { graph.enhancedDownloadManager },
    tachiyomi.domain.download.service.DownloadPreferences::class.java to { graph.enhancedDownloadPreferences },
    eu.kanade.tachiyomi.ui.mod.EnhancedPreferences::class.java to { graph.enhancedEnhancedPreferences },
    tachiyomi.domain.manga.interactor.FetchInterval::class.java to { graph.enhancedFetchInterval },
    mihon.domain.chapter.interactor.FilterChaptersForDownload::class.java to { graph.enhancedFilterChaptersForDownload },
    tachiyomi.domain.category.interactor.GetCategories::class.java to { graph.enhancedGetCategories },
    tachiyomi.domain.chapter.interactor.GetChaptersByMangaId::class.java to { graph.enhancedGetChaptersByMangaId },
    tachiyomi.domain.history.interactor.GetHistory::class.java to { graph.enhancedGetHistory },
    tachiyomi.domain.manga.interactor.GetLibraryManga::class.java to { graph.enhancedGetLibraryManga },
    tachiyomi.domain.manga.interactor.GetManga::class.java to { graph.enhancedGetManga },
    tachiyomi.domain.manga.interactor.GetMangaByUrlAndSourceId::class.java to { graph.enhancedGetMangaByUrlAndSourceId },
    tachiyomi.domain.manga.interactor.GetMergedMangaForDownloading::class.java to { graph.enhancedGetMergedMangaForDownloading },
    tachiyomi.domain.manga.interactor.GetMergedReferencesById::class.java to { graph.enhancedGetMergedReferencesById },
    tachiyomi.domain.track.interactor.GetTracks::class.java to { graph.enhancedGetTracks },
    tachiyomi.domain.history.interactor.GetUpdateWatchHistory::class.java to { graph.enhancedGetUpdateWatchHistory },
    tachiyomi.domain.history.interactor.GetUpdateWatchInbox::class.java to { graph.enhancedGetUpdateWatchInbox },
    eu.kanade.tachiyomi.data.sync.service.GoogleDriveService::class.java to { graph.enhancedGoogleDriveService },
    eu.kanade.tachiyomi.data.sync.service.GoogleDriveSyncService::class.java to { graph.enhancedGoogleDriveSyncService },
    tachiyomi.domain.history.repository.HistoryRepository::class.java to { graph.enhancedHistoryRepository },
    tachiyomi.domain.library.service.LibraryPreferences::class.java to { graph.enhancedLibraryPreferences },
    tachiyomi.domain.history.interactor.ManageHistoryCategory::class.java to { graph.enhancedManageHistoryCategory },
    tachiyomi.domain.history.group.interactor.ManageHistoryGroups::class.java to { graph.enhancedManageHistoryGroups },
    tachiyomi.domain.source.linked.interactor.ManageLinkedSourceGroup::class.java to { graph.enhancedManageLinkedSourceGroup },
    tachiyomi.domain.history.interactor.ManageUpdateWatch::class.java to { graph.enhancedManageUpdateWatch },
    tachiyomi.domain.history.interactor.ManageUpdateWatchHistory::class.java to { graph.enhancedManageUpdateWatchHistory },
    tachiyomi.domain.history.interactor.ManageUpdateWatchInbox::class.java to { graph.enhancedManageUpdateWatchInbox },
    tachiyomi.domain.manga.repository.MangaRepository::class.java to { graph.enhancedMangaRepository },
    eu.kanade.tachiyomi.network.NetworkHelper::class.java to { graph.enhancedNetworkHelper },
    tachiyomi.domain.manga.interactor.NetworkToLocalManga::class.java to { graph.enhancedNetworkToLocalManga },
    tachiyomi.core.common.preference.PreferenceStore::class.java to { graph.enhancedPreferenceStore },
    eu.kanade.tachiyomi.ui.chat.PresenceManager::class.java to { graph.enhancedPresenceManager },
    eu.kanade.domain.chapter.interactor.SetReadStatus::class.java to { graph.enhancedSetReadStatus },
    tachiyomi.domain.manga.interactor.SmartSearchMerge::class.java to { graph.enhancedSmartSearchMerge },
    tachiyomi.domain.source.service.SourceManager::class.java to { graph.enhancedSourceManager },
    eu.kanade.domain.source.service.SourcePreferences::class.java to { graph.enhancedSourcePreferences },
    eu.kanade.domain.chapter.interactor.SyncChaptersWithSource::class.java to { graph.enhancedSyncChaptersWithSource },
    eu.kanade.tachiyomi.data.sync.SyncManager::class.java to { graph.enhancedSyncManager },
    eu.kanade.domain.sync.SyncPreferences::class.java to { graph.enhancedSyncPreferences },
    eu.kanade.domain.manga.interactor.UpdateManga::class.java to { graph.enhancedUpdateManga },
    mihon.domain.source.interactor.UpdateMangaFromRemote::class.java to { graph.enhancedUpdateMangaFromRemote },
    eu.kanade.tachiyomi.ui.mod.updatewatch.worker.UpdateWatchDiagnosticsPreferences::class.java to { graph.enhancedUpdateWatchDiagnosticsPreferences },
)

package eu.kanade.tachiyomi.data.backup.restore.restorers

import android.content.Context
import eu.kanade.tachiyomi.data.backup.models.BackupLinkedSourceGroup
import eu.kanade.tachiyomi.data.backup.models.BackupManualHistoryGroup
import eu.kanade.tachiyomi.data.backup.models.BackupUpdateWatch
import eu.kanade.tachiyomi.data.backup.models.BackupUpdateWatchHistory
import eu.kanade.tachiyomi.data.backup.models.BackupUpdateWatchInboxItem
import eu.kanade.tachiyomi.ui.mod.updatewatch.worker.UpdateWatchRefreshScheduler
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.history.group.interactor.ManageHistoryGroups
import tachiyomi.domain.history.interactor.ManageUpdateWatch
import tachiyomi.domain.history.interactor.ManageUpdateWatchHistory
import tachiyomi.domain.history.interactor.ManageUpdateWatchInbox
import tachiyomi.domain.history.model.UpdateWatch
import tachiyomi.domain.history.model.UpdateWatchHistory
import tachiyomi.domain.history.model.UpdateWatchInboxItem
import tachiyomi.domain.source.linked.interactor.ManageLinkedSourceGroup
import tachiyomi.domain.source.linked.repository.LinkedSourceRepository
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlinx.coroutines.flow.first
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import app.cash.sqldelight.async.coroutines.awaitAsList
import tachiyomi.data.Database

class ModRestorer(
    context: Context,
    private val manageLinkedSourceGroup: ManageLinkedSourceGroup,
    private val manageHistoryGroups: ManageHistoryGroups,
    private val manageUpdateWatch: ManageUpdateWatch,
    private val manageUpdateWatchInbox: ManageUpdateWatchInbox,
    private val manageUpdateWatchHistory: ManageUpdateWatchHistory,
    private val linkedSourceRepository: LinkedSourceRepository,
    private val database: Database,
) {

    private val context = context.applicationContext

    suspend fun restoreGroups(
        backupLinkedSourceGroups: List<BackupLinkedSourceGroup>,
        backupManualHistoryGroups: List<BackupManualHistoryGroup>,
        backupUpdateWatch: List<BackupUpdateWatch>,
        backupUpdateWatchInbox: List<BackupUpdateWatchInboxItem>,
        backupUpdateWatchHistory: List<BackupUpdateWatchHistory>,
        mangaUrlToIdMap: Map<Pair<Long, String>, Long>,
        mangaUrlToTitleMap: Map<Pair<Long, String>, String>,
        onSkip: (String) -> Unit,
    ): Int {
        logcat(LogPriority.INFO) { "ModRestorer: Starting restoreGroups" }
        var skippedCount = 0

        // 1. Linked Source Groups
        if (backupLinkedSourceGroups.isNotEmpty()) {
            val dbGroups = manageLinkedSourceGroup.subscribe().first()
            backupLinkedSourceGroups.forEach { backupGroup ->
                val groupId = dbGroups.find { it.name == backupGroup.name }?.id
                    ?: manageLinkedSourceGroup.create(backupGroup.name)

                backupGroup.members.forEach { member ->
                    val mangaId = mangaUrlToIdMap[member.source to member.url]
                    if (mangaId != null) {
                        val existingGroupId = linkedSourceRepository.getGroupIdForManga(mangaId, member.source)
                        if (existingGroupId != groupId) {
                            manageLinkedSourceGroup.joinGroup(groupId, mangaId, member.source)
                        }
                    } else {
                        val title = mangaUrlToTitleMap[member.source to member.url] ?: "Unknown"
                        onSkip("Linked Source Group [${backupGroup.name}]: Manga '$title' not found (Source: ${member.source}, URL: ${member.url})")
                        skippedCount++
                    }
                }
            }
        }

        // 2. Manual History Groups
        if (backupManualHistoryGroups.isNotEmpty()) {
            val dbGroups = manageHistoryGroups.getGroups()
            val memberships = manageHistoryGroups.getAllMemberships()
            backupManualHistoryGroups.forEach { backupGroup ->
                val groupId = dbGroups.find { it.name == backupGroup.name }?.id
                    ?: manageHistoryGroups.createGroup(backupGroup.name)

                backupGroup.members.forEach { member ->
                    val mangaId = mangaUrlToIdMap[member.source to member.url]
                    if (mangaId != null) {
                        if (memberships[mangaId] != groupId) {
                            manageHistoryGroups.assignMangaToGroup(mangaId, groupId)
                        }
                    } else {
                        val title = mangaUrlToTitleMap[member.source to member.url] ?: "Unknown"
                        onSkip("Manual History Group [${backupGroup.name}]: Manga '$title' not found (Source: ${member.source}, URL: ${member.url})")
                        skippedCount++
                    }
                }
            }
        }

        // 3. Update Watch
        backupUpdateWatch.forEach { watch ->
            val mangaId = mangaUrlToIdMap[watch.member.source to watch.member.url]
            if (mangaId != null) {
                manageUpdateWatch.updatePaused(mangaId, watch.isPaused)

                // Restore new fields
                manageUpdateWatch.updateBackgroundRefresh(
                    mangaId = mangaId,
                    enabled = watch.backgroundRefreshEnabled,
                    interval = watch.expectedIntervalDays,
                    profile = UpdateWatch.RefreshProfile.entries.getOrElse(watch.refreshProfile) { UpdateWatch.RefreshProfile.WEEKLY_STABLE }
                )
                watch.lastBackgroundCheckAt?.let {
                    manageUpdateWatch.updateLastBackgroundCheckAt(mangaId, it)
                }
                if (watch.lastWarnedMilestone > 0) {
                    manageUpdateWatch.updateStaleMilestone(mangaId, watch.lastWarnedMilestone)
                }
            } else {
                val title = mangaUrlToTitleMap[watch.member.source to watch.member.url] ?: "Unknown"
                onSkip("Update Watch: Manga '$title' not found (Source: ${watch.member.source}, URL: ${watch.member.url})")
                skippedCount++
            }
        }

        // 4. Update Watch Inbox
        backupUpdateWatchInbox.forEach { backupItem ->
            val mangaId = mangaUrlToIdMap[backupItem.member.source to backupItem.member.url]
            if (mangaId != null) {
                val latestChapterId = getChapterIdRobust(mangaId, backupItem.latestChapterUrl, "Update Watch Inbox (latest)")
                if (latestChapterId != null) {
                    val chapterIds = backupItem.chapterUrls.mapNotNull { url ->
                        getChapterIdRobust(mangaId, url, "Update Watch Inbox (member)")
                    }

                    manageUpdateWatchInbox.insertOrMerge(
                        UpdateWatchInboxItem(
                            mangaId = mangaId,
                            mangaTitle = backupItem.mangaTitle,
                            sourceId = backupItem.member.source,
                            sourceName = backupItem.sourceName,
                            chapterCount = backupItem.chapterCount,
                            chapterRange = backupItem.chapterRange,
                            firstFoundAt = backupItem.firstFoundAt,
                            lastFoundAt = backupItem.lastFoundAt,
                            latestChapterId = latestChapterId,
                            latestChapterNumber = backupItem.latestChapterNumber,
                            chapterIds = chapterIds,
                            latestChapterUploadAt = backupItem.latestChapterUploadAt,
                            type = backupItem.type,
                            milestone = backupItem.milestone,
                        )
                    )
                } else {
                    onSkip("Update Watch Inbox [${backupItem.mangaTitle}]: Latest chapter not found (URL: ${backupItem.latestChapterUrl})")
                }
            } else {
                onSkip("Update Watch Inbox [${backupItem.mangaTitle}]: Manga not found (Source: ${backupItem.member.source}, URL: ${backupItem.member.url})")
            }
        }

        // 5. Update Watch History
        backupUpdateWatchHistory.forEach { backupHistory ->
            val mangaId = mangaUrlToIdMap[backupHistory.member.source to backupHistory.member.url]
            if (mangaId != null) {
                manageUpdateWatchHistory.insert(
                    UpdateWatchHistory(
                        mangaId = mangaId,
                        timestamp = backupHistory.timestamp,
                        success = backupHistory.success,
                        newChapters = backupHistory.newChapters,
                        category = UpdateWatchHistory.FailureCategory.entries.getOrElse(backupHistory.category) { UpdateWatchHistory.FailureCategory.UNKNOWN },
                        detail = backupHistory.detail,
                    )
                )
            } else {
                val title = mangaUrlToTitleMap[backupHistory.member.source to backupHistory.member.url] ?: "Unknown"
                onSkip("Update Watch History: Manga '$title' not found (Source: ${backupHistory.member.source}, URL: ${backupHistory.member.url})")
                skippedCount++
            }
        }

        if (backupUpdateWatch.isNotEmpty()) {
            logcat(LogPriority.INFO) { "ModRestorer: Setting up Update Watch task with context: $context" }
            try {
                UpdateWatchRefreshScheduler.setupTask(context)
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "ModRestorer: Failed to setup Update Watch task" }
            }
        }

        return skippedCount
    }

    private suspend fun getChapterIdRobust(mangaId: Long, url: String, type: String): Long? {
        val chapterList = database.chaptersQueries
            .getChapterByUrlAndMangaId(url, mangaId)
            .awaitAsList()
        if (chapterList.size > 1) {
            logcat(LogPriority.WARN) { "Restore: Duplicate chapter found for $type manga $mangaId chapter $url" }
        }
        return chapterList.firstOrNull()?._id
    }
}

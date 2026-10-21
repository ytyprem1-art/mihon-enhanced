package eu.kanade.tachiyomi.ui.mod

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.platform.app.InstrumentationRegistry
import dev.zacsweers.metro.createGraphFactory
import eu.kanade.domain.sync.SyncPreferences
import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupChapter
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.backup.models.BackupModMember
import eu.kanade.tachiyomi.data.backup.models.BackupUpdateWatchInboxItem
import eu.kanade.tachiyomi.data.sync.SyncData
import eu.kanade.tachiyomi.data.sync.service.SyncService
import eu.kanade.tachiyomi.ui.mod.updatewatch.UpdateWatchDismissalStore
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoBuf
import mihon.app.di.AppGraph
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tachiyomi.domain.history.model.UpdateWatchInboxItem
import java.io.File
import java.util.UUID

class UpdateWatchDismissalDeviceTest {
    private class IsolatedContext(base: Context, val label: String) : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun getDatabasePath(name: String): File = super.getDatabasePath("inbox_test_${label}_$name")
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            super.getSharedPreferences("inbox_test_${label}_$name", mode)
    }
    private class SyncProbe(context: Context, prefs: SyncPreferences) : SyncService(context, Json, prefs) {
        override suspend fun doSync(syncData: SyncData): Backup? = null
        fun merge(local: Backup, remote: Backup) = checkNotNull(
            mergeSyncData(
                SyncData("local", local),
                SyncData("remote", remote),
            ).backup,
        )
    }

    @Test fun dismissRefreshRestartBackupSyncAndNextRelease() = runBlocking {
        val base = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val token = UUID.randomUUID().toString()
        val context = IsolatedContext(base, "source_$token")
        val graph = createGraphFactory<AppGraph.Factory>().create(context, true)
        val target = createGraphFactory<AppGraph.Factory>().create(IsolatedContext(base, "target_$token"), true)
        try {
            val savedManga = BackupManga(
                100,
                "/inbox-series",
                "Inbox Test",
                chapters = listOf(
                    BackupChapter("/ch/171", "Chapter 171", chapterNumber = 171f),
                    BackupChapter("/ch/172", "Chapter 172", chapterNumber = 172f),
                ),
            )
            graph.enhancedMangaRestorer.restore(listOf(savedManga), emptyList())
            val manga = checkNotNull(graph.enhancedMangaRepository.getMangaByUrlAndSourceId(savedManga.url, 100))
            val chapters = graph.enhancedChapterRepository.getChapterByMangaId(manga.id, false)
            val ch171 = chapters.single { it.chapterNumber == 171.0 }
            val ch172 = chapters.single { it.chapterNumber == 172.0 }
            val item = UpdateWatchInboxItem(
                manga.id, manga.title, manga.source, "Source", 1, "Ch. 171", 1,
                1, ch171.id, 171.0, listOf(ch171.id),
            )
            val inbox = graph.enhancedManageUpdateWatchInbox
            assertNotNull(inbox.insertOrMerge(item))
            var remaining = -1
            inbox.dismiss(manga.id) { remaining = it.size }
            assertEquals(0, remaining)
            assertTrue(graph.enhancedGetUpdateWatchInbox.await().isEmpty())
            // Refresh of the same unread chapter and delayed notification delivery are both blocked.
            assertNull(inbox.insertOrMerge(item.copy(lastFoundAt = 2000)))
            var delivered = -1
            inbox.deliverUndismissed(listOf(item)) { delivered = it.size }
            assertEquals(0, delivered)
            assertTrue(graph.enhancedChapterRepository.getChapterByMangaId(manga.id, false).none { it.read })
            val records = UpdateWatchDismissalStore(graph.enhancedPreferenceStore).snapshot()
            assertEquals(171.0, records.single().throughChapterNumber, 0.0)
            val backup = ProtoBuf.decodeFromByteArray(
                Backup.serializer(),
                ProtoBuf.encodeToByteArray(
                    Backup.serializer(),
                    Backup(
                        listOf(savedManga),
                        updateWatchDismissals = records,
                    ),
                ),
            )
            val remoteItem = BackupUpdateWatchInboxItem(
                BackupModMember(100, savedManga.url), "Inbox Test",
                "Source", 1, "Ch. 171", 1, 1, "/ch/171", 171.0,
            )
            val probe = SyncProbe(context, graph.enhancedSyncPreferences)
            val merged = probe.merge(backup, Backup(emptyList(), backupUpdateWatchInbox = listOf(remoteItem)))
            assertTrue(merged.backupUpdateWatchInbox.isEmpty())
            assertEquals(records, merged.updateWatchDismissals)
            val nextMerged = probe.merge(
                backup,
                Backup(
                    emptyList(),
                    backupUpdateWatchInbox =
                    listOf(remoteItem.copy(latestChapterUrl = "/ch/172", latestChapterNumber = 172.0)),
                ),
            )
            assertEquals(172.0, nextMerged.backupUpdateWatchInbox.single().latestChapterNumber, 0.0)
            // A new release can enter, without adding dismissed chapter 171 back into its badge count.
            val next = item.copy(
                chapterIds = listOf(ch171.id, ch172.id),
                chapterCount = 2,
                latestChapterId =
                ch172.id,
                latestChapterNumber = 172.0,
            )
            val accepted = checkNotNull(inbox.insertOrMerge(next))
            assertEquals(listOf(ch172.id), accepted.chapterIds)
            assertEquals(1, accepted.chapterCount)
            // Restore on a clean database with different IDs still honors the dismissal by source + URL.
            target.enhancedMangaRestorer.restore(
                listOf(
                    BackupManga(
                        200,
                        "/other",
                        "Other",
                        chapters =
                        listOf(BackupChapter("/other-ch", "Other chapter")),
                    ),
                ),
                emptyList(),
            )
            target.enhancedUpdateWatchDismissalStore.restore(backup.updateWatchDismissals)
            target.enhancedMangaRestorer.restore(backup.backupManga, emptyList())
            val restored = checkNotNull(target.enhancedMangaRepository.getMangaByUrlAndSourceId(savedManga.url, 100))
            val restored171 = target.enhancedChapterRepository.getChapterByMangaId(
                restored.id,
                false,
            ).single { it.chapterNumber == 171.0 }
            assertNotEquals(ch171.id, restored171.id)
            assertNull(
                target.enhancedManageUpdateWatchInbox.insertOrMerge(
                    item.copy(
                        mangaId = restored.id,
                        latestChapterId = restored171.id,
                        chapterIds = listOf(restored171.id),
                    ),
                ),
            )
        } finally {
            graph.enhancedSqlDriver.close()
            target.enhancedSqlDriver.close()
        }
    }
}

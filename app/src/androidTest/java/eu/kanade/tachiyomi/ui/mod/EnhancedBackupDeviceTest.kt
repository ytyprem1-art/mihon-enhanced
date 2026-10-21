package eu.kanade.tachiyomi.ui.mod

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.platform.app.InstrumentationRegistry
import dev.zacsweers.metro.createGraphFactory
import eu.kanade.tachiyomi.data.backup.create.BackupOptions
import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupChapter
import eu.kanade.tachiyomi.data.backup.models.BackupHistory
import eu.kanade.tachiyomi.data.backup.models.BackupHistoryCategory
import eu.kanade.tachiyomi.data.backup.models.BackupLinkedSourceGroup
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.backup.models.BackupManualHistoryGroup
import eu.kanade.tachiyomi.data.backup.models.BackupMergedMangaReference
import eu.kanade.tachiyomi.data.backup.models.BackupModMember
import eu.kanade.tachiyomi.data.backup.models.BackupUpdateWatchHistory
import eu.kanade.tachiyomi.data.backup.models.BackupUpdateWatchInboxItem
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.protobuf.ProtoBuf
import mihon.app.di.AppGraph
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.UUID
import kotlin.time.Instant

/** Uses separate database files and preferences. Never clears or restores the user's database. */
class EnhancedBackupDeviceTest {
    private class IsolatedContext(base: Context, val label: String) : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun getDatabasePath(name: String): File = super.getDatabasePath("enhanced_test_${label}_$name")
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            super.getSharedPreferences("enhanced_test_${label}_$name", mode)
    }

    @Test
    fun cleanRestorePreservesChapterProgressVersionsHistoryCategoriesAndMerge() = runBlocking {
        val base = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val token = UUID.randomUUID().toString()
        val source = createGraphFactory<AppGraph.Factory>().create(IsolatedContext(base, "source_$token"), true)
        val target = createGraphFactory<AppGraph.Factory>().create(IsolatedContext(base, "target_$token"), true)
        try {
            val chapter = BackupChapter(
                url = "/chapter/1", name = "Chapter 1", read = true, bookmark = true,
                lastPageRead = 17, chapterNumber = 1f, dateFetch = 1000, dateUpload = 2000, version = 7,
            )
            val child = BackupManga(
                source = 100,
                url = "/child",
                title = "Child",
                favorite = false,
                chapters = listOf(chapter),
                history = listOf(BackupHistory(chapter.url, 5000, 12000)),
                version = 9,
                excludedScanlators = listOf("Hidden group"),
            )
            val merged = BackupManga(
                source = 100,
                url = "/parent",
                title = "Parent",
                favorite = true,
                mergedMangaReferences = listOf(
                    BackupMergedMangaReference(
                        isInfoManga = true,
                        getChapterUpdates = true,
                        chapterSortMode = 0,
                        chapterPriority = 1,
                        downloadChapters = true,
                        mergeUrl = "/parent",
                        mangaUrl = "/child",
                        mangaSourceId = 100,
                    ),
                ),
            )
            source.enhancedMangaRestorer.restore(listOf(child, merged), emptyList())
            source.enhancedMangaRestorer.restoreMergedRelationships(listOf(merged))
            source.enhancedManageHistoryCategory.create("Manhwa")
            val childRecord = checkNotNull(source.enhancedMangaRepository.getMangaByUrlAndSourceId("/child", 100))
            val category = source.enhancedManageHistoryCategory.subscribe().first().single()
            source.enhancedManageHistoryCategory.moveToCategory(childRecord.id, category.id)
            val parentRecord = checkNotNull(source.enhancedMangaRepository.getMangaByUrlAndSourceId("/parent", 100))
            val collected = source.enhancedBackupMangaCollector(BackupOptions())
            assertTrue(collected.any { it.id == childRecord.id && !it.favorite })
            val backup = source.enhancedMangaBackupCreator(collected, BackupOptions())
            val encoded = ProtoBuf.encodeToByteArray(
                Backup.serializer(),
                Backup(
                    backup,
                    backupHistoryCategories = listOf(BackupHistoryCategory(category.name, category.id, category.sort)),
                ),
            )
            val decoded = ProtoBuf.decodeFromByteArray(Backup.serializer(), encoded)
            // Different target IDs ensure relationship restoration must use source + URL.
            target.enhancedMangaRestorer.restore(listOf(BackupManga(99, "/unrelated", "Unrelated")), emptyList())
            decoded.backupManga.forEach {
                target.enhancedMangaRestorer.restore(it, emptyList(), decoded.backupHistoryCategories, true)
            }
            target.enhancedMangaRestorer.restoreMergedRelationships(decoded.backupManga)
            val restored = checkNotNull(target.enhancedMangaRepository.getMangaByUrlAndSourceId("/child", 100))
            assertNotEquals(childRecord.id, restored.id)
            assertFalse(restored.favorite)
            val restoredChapter = target.enhancedChapterRepository.getChapterByMangaId(restored.id, false).single()
            assertTrue(restoredChapter.read)
            assertTrue(restoredChapter.bookmark)
            assertEquals(17, restoredChapter.lastPageRead)
            assertEquals(Instant.fromEpochMilliseconds(2000), restoredChapter.dateUpload)
            assertEquals(9L, target.enhancedSyncVersions.manga(restored.id))
            assertEquals(7L, target.enhancedSyncVersions.chapter(restoredChapter.id))
            assertNotNull(target.enhancedManageHistoryCategory.getMangaCategory(restored.id))
            assertEquals(
                12000L,
                target.enhancedHistoryRepository.getHistoryByMangaId(restored.id).single().readDuration,
            )
            val parent = checkNotNull(target.enhancedMangaRepository.getMangaByUrlAndSourceId("/parent", 100))
            assertEquals(restored.id, target.enhancedGetMergedReferencesById.await(parent.id).single().mangaId)
            val stripped = target.enhancedMangaBackupCreator(
                listOf(restored),
                BackupOptions(chapters = false, history = false),
            )
            assertTrue(stripped.single().chapters.isEmpty())
            assertTrue(stripped.single().history.isEmpty())
            val member = BackupModMember(100, "/child")
            val errors = mutableListOf<String>()
            target.enhancedModRestorer.restoreGroups(
                backupLinkedSourceGroups = listOf(BackupLinkedSourceGroup("Linked", listOf(member))),
                backupManualHistoryGroups = listOf(BackupManualHistoryGroup("Manual", listOf(member))),
                backupUpdateWatch = emptyList(),
                backupUpdateWatchInbox = listOf(
                    BackupUpdateWatchInboxItem(
                        member = member, mangaTitle = "Child", sourceName = "Source", chapterCount = 0,
                        chapterRange = "", firstFoundAt = 1000, lastFoundAt = 2000, latestChapterUrl = "",
                        latestChapterNumber = 0.0, type = 1, milestone = 30,
                    ),
                ),
                backupUpdateWatchHistory = listOf(BackupUpdateWatchHistory(member, 3000, true, 1, 0)),
                mangaUrlToIdMap = mapOf((member.source to member.url) to restored.id),
                mangaUrlToTitleMap = mapOf((member.source to member.url) to "Child"),
                onSkip = { errors.add(it) },
            )
            assertTrue(errors.toString(), errors.isEmpty())
            val inbox = target.enhancedGetUpdateWatchInbox.await().single()
            assertEquals(1, inbox.type)
            assertEquals(30, inbox.milestone)
            assertEquals(0L, inbox.latestChapterId)
            assertEquals(1, target.enhancedGetUpdateWatchHistory.awaitAll().size)
            val linkedGroup = target.enhancedManageLinkedSourceGroup.subscribe().first().single()
            assertEquals(
                listOf(restored.id),
                target.enhancedManageLinkedSourceGroup.subscribeMemberIds(linkedGroup.id).first(),
            )
            val manualGroup = target.enhancedManageHistoryGroups.subscribe().first().single()
            assertEquals(
                listOf(restored.id),
                target.enhancedManageHistoryGroups.subscribeMembers(manualGroup.id).first(),
            )
        } finally {
            source.enhancedSqlDriver.close()
            target.enhancedSqlDriver.close()
        }
    }

    @Test
    fun mergeCreationAdditionChapterOwnershipDownloadSettingsAndRestore() = runBlocking {
        val base = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val token = UUID.randomUUID().toString()
        val graph = createGraphFactory<AppGraph.Factory>().create(IsolatedContext(base, "merge_$token"), true)
        val target = createGraphFactory<AppGraph.Factory>().create(IsolatedContext(base, "merge_restore_$token"), true)
        try {
            val originals = (100L..102L).map { id ->
                BackupManga(
                    id,
                    "/series",
                    "Same comic",
                    favorite = true,
                    chapters = listOf(
                        BackupChapter("/chapter/1", "Chapter 1", read = id == 100L, chapterNumber = 1f),
                    ),
                )
            }
            graph.enhancedMangaRestorer.restore(originals, emptyList())
            val children = originals.map {
                checkNotNull(graph.enhancedMangaRepository.getMangaByUrlAndSourceId(it.url, it.source))
            }
            val merged = graph.enhancedSmartSearchMerge.await(children[1], children[0].id)
            assertEquals(eu.kanade.tachiyomi.source.mod.MERGED_SOURCE_ID, merged.source)
            assertEquals(3, graph.enhancedGetMergedReferencesById.await(merged.id).size)
            assertEquals(2, graph.enhancedChapterRepository.getMergedChapterByMangaId(merged.id, false).size)
            graph.enhancedSmartSearchMerge.await(children[2], merged.id)
            val references = graph.enhancedGetMergedReferencesById.await(merged.id)
            assertEquals(4, references.size)
            val chapterOwners = graph.enhancedChapterRepository.getMergedChapterByMangaId(merged.id, false).map {
                it.mangaId
            }.toSet()
            assertEquals(children.map { it.id }.toSet(), chapterOwners)
            assertTrue(graph.enhancedChapterRepository.getChapterByMangaId(children[0].id, false).single().read)
            val originalReference = references.single { it.mangaId == children[0].id }
            assertTrue(
                graph.enhancedMangaMergeRepository.updateSettings(
                    tachiyomi.domain.manga.model.MergeMangaSettingsUpdate(
                        id = originalReference.id,
                        downloadChapters = false,
                        chapterPriority = 3,
                        getChapterUpdates = false,
                    ),
                ),
            )
            val downloadable = graph.enhancedGetMergedMangaForDownloading.await(merged.id)
            assertEquals(setOf(children[1].id, children[2].id), downloadable.map { it.id }.toSet())
            val saved = graph.enhancedMangaBackupCreator(
                graph.enhancedBackupMangaCollector(BackupOptions()),
                BackupOptions(),
            )
            target.enhancedMangaRestorer.restore(saved, emptyList())
            target.enhancedMangaRestorer.restoreMergedRelationships(saved)
            val restoredParent =
                checkNotNull(target.enhancedMangaRepository.getMangaByUrlAndSourceId(merged.url, merged.source))
            val restoredRefs = target.enhancedGetMergedReferencesById.await(restoredParent.id)
            assertEquals(4, restoredRefs.size)
            val restoredOriginal = restoredRefs.single { it.mangaSourceId == 100L }
            assertFalse(restoredOriginal.downloadChapters)
            assertFalse(restoredOriginal.getChapterUpdates)
            assertEquals(3, restoredOriginal.chapterPriority)
            assertEquals(3, target.enhancedChapterRepository.getMergedChapterByMangaId(restoredParent.id, false).size)
        } finally {
            graph.enhancedSqlDriver.close()
            target.enhancedSqlDriver.close()
        }
    }
}

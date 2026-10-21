package eu.kanade.tachiyomi.ui.mod

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.net.Uri
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.test.platform.app.InstrumentationRegistry
import dev.zacsweers.metro.createGraphFactory
import eu.kanade.tachiyomi.data.backup.BackupDecoder
import eu.kanade.tachiyomi.data.backup.create.BackupOptions
import eu.kanade.tachiyomi.data.backup.models.BackupChapter
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.backup.restore.RestoreOptions
import eu.kanade.tachiyomi.ui.mod.updatecategory.UpdatesCategories
import eu.kanade.tachiyomi.ui.updates.UpdatesViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mihon.app.di.AppGraph
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tachiyomi.core.common.preference.Preference
import java.io.File
import java.util.UUID

/** Actual .tachibk creation -> cleared settings -> actual restore, using isolated app storage. */
class UpdatesCategoriesBackupDeviceTest {
    @Test fun updatesViewModelRoutesAllChaptersAndBulkSelectionUsesOnlyVisibleCategory(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val base = instrumentation.targetContext.applicationContext
        val label = "updates_filter_test_${UUID.randomUUID()}_"
        val isolated = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getDatabasePath(name: String): File = super.getDatabasePath(label + name)
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                super.getSharedPreferences(label + name, mode)
        }
        val graph = createGraphFactory<AppGraph.Factory>().create(isolated, true)
        val viewModels = ViewModelStore()
        try {
            val now = System.currentTimeMillis()
            graph.enhancedMangaRestorer.restore(
                listOf(10L, 20L, 30L).map { source ->
                    BackupManga(
                        source = source,
                        url = "/test-$source",
                        title = "Test $source",
                        favorite = true,
                        dateAdded = 1000,
                        chapters = listOf(
                            BackupChapter(
                                url = "/chapter-1",
                                name = "Chapter 1",
                                chapterNumber = 1f,
                                dateFetch = now,
                                dateUpload = now,
                            ),
                        ),
                    )
                },
                emptyList(),
            )
            lateinit var viewModel: UpdatesViewModel
            instrumentation.runOnMainSync {
                viewModel = graph.viewModelFactory.create(UpdatesViewModel::class, CreationExtras.Empty)
                viewModels.put("updates", viewModel)
            }
            suspend fun stateFor(sources: Set<Long>): UpdatesViewModel.State = withTimeout(10000) {
                viewModel.state.first {
                    !it.isLoading && it.items.map { item ->
                        item.update.sourceId
                    }.toSet() == sources
                }
            }
            stateFor(setOf(10, 20, 30))
            val store = UpdatesCategories(graph.enhancedPreferenceStore)
            val category = store.save(null, "Manhwa 18+", setOf(10, 20))
            stateFor(setOf(30))
            store.select(category)
            stateFor(setOf(10, 20))
            viewModel.toggleAllSelection(true)
            val selected = withTimeout(10000) { viewModel.state.first { it.selected.size == 2 } }
            assertEquals(setOf(10L, 20L), selected.selected.map { it.update.sourceId }.toSet())
            viewModel.toggleAllSelection(false)
            store.delete(category)
            stateFor(setOf(10, 20, 30))
        } finally {
            instrumentation.runOnMainSync { viewModels.clear() }
            graph.enhancedSqlDriver.close()
            base.deleteDatabase(label + "tachiyomi.db")
        }
    }

    @Test fun actualBackupFileRestoresCategoriesAndSourceLinksWithOtherRestoreOptionsDisabled() = runBlocking {
        val base = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val label = "updates_categories_test_${UUID.randomUUID()}_"
        val isolated = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getDatabasePath(name: String): File = super.getDatabasePath(label + name)
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                super.getSharedPreferences(label + name, mode)
        }
        val graph = createGraphFactory<AppGraph.Factory>().create(isolated, true)
        val file = File(base.cacheDir, "$label.tachibk").apply { createNewFile() }
        val store = UpdatesCategories(graph.enhancedPreferenceStore)
        try {
            store.save(null, "Manhwa 18+", setOf(12345678, 98765432))
            store.save(null, "English", setOf(13579246))
            val expected = store.backup(true)
            val options = BackupOptions(
                libraryEntries = false, categories = false, chapters = false, tracking = false, history = false,
                readEntries = false, appSettings = false, extensionStores = false, sourceSettings = false,
                modHistoryCategories = false, modHistoryGroups = false, modLinkedSources = false,
                modUpdateWatch = false, modPersonalFavorites = false,
                modUpdatesCategories = true,
            )
            assertTrue(options.canCreate())
            graph.enhancedBackupCreatorFactory.create(false).backup(Uri.fromFile(file), options)
            val decoded = BackupDecoder(isolated, graph.protoBuf).decode(Uri.fromFile(file))
            assertEquals(expected, decoded.updatesCategories)
            assertTrue(decoded.backupPreferences.isEmpty())
            assertTrue(decoded.backupManga.isEmpty())

            graph.enhancedPreferenceStore.getString(Preference.appStateKey("updates_source_categories"), "{}").delete()
            assertTrue(store.snapshot().categories.isEmpty())
            val restore = RestoreOptions(
                libraryEntries = false, categories = false, appSettings = false, extensionStores = false,
                sourceSettings = false,
                modHistoryCategories = false, modHistoryGroups = false, modLinkedSources = false,
                modUpdateWatch = false, modPersonalFavorites = false,
                modUpdatesCategories = true,
            )
            assertTrue(restore.canRestore())
            graph.enhancedBackupRestorerFactory.create(
                graph.enhancedBackupNotifier,
                true,
            ).restore(Uri.fromFile(file), restore)
            val reopened = UpdatesCategories(graph.enhancedPreferenceStore)
            assertEquals(expected, reopened.backup(true))
            assertFalse(reopened.snapshot().includes(12345678))
            val restoredCategory = reopened.snapshot().categories.first()
            reopened.select(restoredCategory.id)
            assertTrue(reopened.snapshot().includes(12345678))
            assertTrue(reopened.snapshot().includes(98765432))
            assertFalse(reopened.snapshot().includes(13579246))
            // Restore is idempotent and requires neither installed extensions nor library records.
            graph.enhancedBackupRestorerFactory.create(
                graph.enhancedBackupNotifier,
                true,
            ).restore(Uri.fromFile(file), restore)
            assertEquals(expected, reopened.backup(true))
        } finally {
            graph.enhancedSqlDriver.close()
            base.deleteDatabase(label + "tachiyomi.db")
            file.delete()
        }
    }
}

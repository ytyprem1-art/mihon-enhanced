package eu.kanade.tachiyomi.ui.mod

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.platform.app.InstrumentationRegistry
import dev.zacsweers.metro.createGraphFactory
import kotlinx.coroutines.runBlocking
import mihon.app.di.AppGraph
import org.junit.Assert.assertEquals
import org.junit.Test
import tachiyomi.domain.manga.model.Manga
import java.io.File
import java.util.UUID

/** The real browse repository and bundled SQLite engine, using a separate disposable database. */
class EnhancedBrowseUpsertDeviceTest {
    @Test fun repeatedBrowseDetailsKeepIdentityAndAdvanceExistingSyncVersion() = runBlocking {
        val base = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val label = "browse_upsert_${UUID.randomUUID()}_"
        val isolated = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getDatabasePath(name: String): File = super.getDatabasePath(label + name)
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = super.getSharedPreferences(
                label + name,
                mode,
            )
        }
        val graph = createGraphFactory<AppGraph.Factory>().create(isolated, true)
        try {
            val repository = graph.enhancedMangaRepository
            val manga = Manga.create().copy(
                source = 100,
                url = "/comic",
                title = "Comic",
                description = "first",
                initialized = true,
            )
            val first = repository.insertNetworkManga(listOf(manga)).single()
            repeat(4) { index ->
                val updated = repository.insertNetworkManga(listOf(manga.copy(description = "Details $index"))).single()
                assertEquals(first.id, updated.id)
                assertEquals("Details $index", updated.description)
            }
            assertEquals(first.id, repository.getMangaByUrlAndSourceId("/comic", 100)!!.id)
        } finally {
            graph.enhancedSqlDriver.close()
            base.deleteDatabase(label + "tachiyomi.db")
        }
    }
}

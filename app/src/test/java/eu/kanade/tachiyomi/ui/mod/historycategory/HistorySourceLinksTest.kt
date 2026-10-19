package eu.kanade.tachiyomi.ui.mod.historycategory

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.history.model.History
import tachiyomi.domain.history.model.HistoryUpdate
import tachiyomi.domain.history.repository.HistoryCategory
import tachiyomi.domain.history.repository.HistoryCategoryRepository
import tachiyomi.domain.history.repository.HistoryRepository
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import java.util.Date

class HistorySourceLinksTest {
    private var stored = emptySet<String>()
    private val preference = mockk<Preference<Set<String>>>().also {
        every { it.get() } answers { stored }
        every { it.set(any()) } answers { stored = firstArg() }
    }
    private val store = mockk<PreferenceStore>().also {
        every { it.getStringSet(any(), any()) } returns preference
    }
    private val links = HistorySourceLinks(store)
    private val history = mockk<HistoryRepository>(relaxed = true)
    private val chapters = mockk<ChapterRepository>()
    private val mangas = mockk<MangaRepository>()
    private val categories = mockk<HistoryCategoryRepository>(relaxed = true)
    private val router = AutoCategorizingHistoryRepository(history, chapters, mangas, categories, links)
    private val update = HistoryUpdate(10, Date(1000), 20)

    private fun prepare(sourceId: Long = 100) {
        coEvery { chapters.getChapterById(10) } returns Chapter.create().copy(id = 10, mangaId = 20)
        coEvery { history.getHistoryByMangaId(20) } returns emptyList()
        coEvery { categories.getCategoryForManga(20) } returns null
        coEvery { mangas.getMangaById(20) } returns Manga.create().copy(id = 20, source = sourceId)
        every { categories.getHistoryCategories() } returns flowOf(listOf(HistoryCategory(1, "Doujindesu", 1)))
    }

    @Test
    fun `multiple sources persist and cannot silently replace another category link`() {
        links.save(1, setOf(100, 200))
        links.save(2, setOf(200, 300))
        assertEquals(mapOf(100L to 1L, 200L to 1L, 300L to 2L), HistorySourceLinks(store).snapshot())
        links.removeCategory(1)
        assertEquals(mapOf(300L to 2L), links.snapshot())
    }

    @Test
    fun `new history is categorized before it becomes visible`() = runBlocking {
        prepare()
        links.save(1, setOf(100, 200))
        router.upsertHistory(update)
        coVerifyOrder {
            categories.insertMangaMapping(20, 1)
            history.upsertHistory(update)
        }
    }

    @Test
    fun `second linked source routes to the same category`() = runBlocking {
        prepare(sourceId = 200)
        links.save(1, setOf(100, 200))
        router.upsertHistory(update)
        coVerify(exactly = 1) { categories.insertMangaMapping(20, 1) }
    }

    @Test
    fun `unlinked source keeps default history behavior`() = runBlocking {
        prepare(sourceId = 300)
        links.save(1, setOf(100))
        router.upsertHistory(update)
        coVerify(exactly = 0) { categories.insertMangaMapping(any(), any()) }
        coVerify(exactly = 1) { history.upsertHistory(update) }
    }

    @Test
    fun `existing history in All is not moved on reread`() = runBlocking {
        prepare()
        links.save(1, setOf(100))
        coEvery { history.getHistoryByMangaId(20) } returns listOf(History.create())
        router.upsertHistory(update)
        coVerify(exactly = 0) { categories.insertMangaMapping(any(), any()) }
        coVerify(exactly = 1) { history.upsertHistory(update) }
    }

    @Test
    fun `manual category placement wins`() = runBlocking {
        prepare()
        links.save(1, setOf(100))
        coEvery { categories.getCategoryForManga(20) } returns 2
        router.upsertHistory(update)
        coVerify(exactly = 0) { categories.insertMangaMapping(any(), any()) }
    }

    @Test
    fun `deleted category is not assigned even if a stale link exists`() = runBlocking {
        prepare()
        links.save(2, setOf(100))
        router.upsertHistory(update)
        coVerify(exactly = 0) { categories.insertMangaMapping(any(), any()) }
        coVerify(exactly = 1) { history.upsertHistory(update) }
    }

    @Test
    fun `no links leaves history writes untouched`() = runBlocking {
        router.upsertHistory(update)
        coVerify(exactly = 0) { chapters.getChapterById(any()) }
        coVerify(exactly = 1) { history.upsertHistory(update) }
    }
}

package tachiyomi.data.source

import androidx.paging.PagingState
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import mihon.domain.manga.model.toDomainManga
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.repository.SourcePagingSource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class SourceSearchPagingSource(
    source: Source,
    private val query: String,
    private val filters: FilterList,
) : BaseSourcePagingSource(source) {
    override suspend fun requestNextPage(currentPage: Int): MangasPage {
        return source.getSearchManga(currentPage, query, filters)
    }
}

class SourcePopularPagingSource(source: Source) : BaseSourcePagingSource(source) {
    override suspend fun requestNextPage(currentPage: Int): MangasPage {
        return source.getPopularManga(currentPage)
    }
}

class SourceLatestPagingSource(
    source: Source,
    libraryOnly: Boolean = false,
) : BaseSourcePagingSource(source, libraryOnly) {
    override suspend fun requestNextPage(currentPage: Int): MangasPage {
        return source.getLatestUpdates(currentPage)
    }
}

abstract class BaseSourcePagingSource(
    protected val source: Source,
    protected open val libraryOnly: Boolean = false,
    private val networkToLocalManga: NetworkToLocalManga = Injekt.get(),
) : SourcePagingSource() {

    private val seenManga = hashSetOf<String>()

    abstract suspend fun requestNextPage(currentPage: Int): MangasPage

    override suspend fun load(params: LoadParams<Long>): LoadResult<Long, Manga> {
        var currentRemotePage = params.key ?: 1
        var lastFetchedRemotePage = currentRemotePage
        val accumulated = mutableListOf<Manga>()
        var hasNextPage = true
        var fetchCount = 0
        val maxFetches = if (libraryOnly) 20 else 1

        return try {
            while (accumulated.size < params.loadSize && hasNextPage && fetchCount < maxFetches) {
                val mangasPage = withIOContext {
                    requestNextPage(currentRemotePage.toInt())
                        .takeIf { it.mangas.isNotEmpty() }
                        ?: throw NoResultsException()
                }
                fetchCount++
                lastFetchedRemotePage = currentRemotePage

                val manga = mangasPage.mangas
                    .map { it.toDomainManga(source.id) }
                    .filter { seenManga.add(it.url) }
                    .let { networkToLocalManga(it) }
                    .filter { !libraryOnly || it.favorite }

                accumulated.addAll(manga)
                hasNextPage = mangasPage.hasNextPage

                if (accumulated.size < params.loadSize && hasNextPage) {
                    currentRemotePage++
                }
            }

            if (accumulated.isEmpty()) {
                throw NoResultsException()
            }

            LoadResult.Page(
                data = accumulated,
                prevKey = null,
                nextKey = if (hasNextPage) lastFetchedRemotePage + 1 else null,
            )
        } catch (e: Exception) {
            LoadResult.Error(e)
        }
    }

    override fun getRefreshKey(state: PagingState<Long, Manga>): Long? {
        return state.anchorPosition?.let { anchorPosition ->
            val anchorPage = state.closestPageToPosition(anchorPosition)
            anchorPage?.prevKey ?: anchorPage?.nextKey
        }
    }
}

class NoResultsException : Exception()

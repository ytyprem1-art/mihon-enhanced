package tachiyomi.data.source

import androidx.paging.PagingState
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mihon.domain.manga.model.toDomainManga
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.repository.SourcePagingSource

class SourceSearchPagingSource(
    source: suspend () -> Source,
    private val query: String,
    private val filters: FilterList,
    networkToLocalManga: NetworkToLocalManga,
) : BaseSourcePagingSource(source, networkToLocalManga) {
    override suspend fun requestNextPage(source: Source, currentPage: Int): MangasPage {
        return source.getSearchManga(currentPage, query, filters)
    }
}

class SourcePopularPagingSource(
    source: suspend () -> Source,
    networkToLocalManga: NetworkToLocalManga,
) : BaseSourcePagingSource(source, networkToLocalManga) {
    override suspend fun requestNextPage(source: Source, currentPage: Int): MangasPage {
        return source.getPopularManga(currentPage)
    }
}

class SourceLatestPagingSource(
    source: suspend () -> Source,
    networkToLocalManga: NetworkToLocalManga,
    libraryOnly: Boolean,
    mangaRepository: MangaRepository,
) : BaseSourcePagingSource(source, networkToLocalManga, libraryOnly, mangaRepository) {
    override suspend fun requestNextPage(source: Source, currentPage: Int): MangasPage {
        return source.getLatestUpdates(currentPage)
    }
}

abstract class BaseSourcePagingSource(
    private val source: suspend () -> Source,
    private val networkToLocalManga: NetworkToLocalManga,
    private val libraryOnly: Boolean = false,
    private val mangaRepository: MangaRepository? = null,
) : SourcePagingSource() {

    private val seenManga = hashSetOf<String>()
    private var favoriteUrls: Set<String>? = null

    private suspend fun getFavoriteUrls(sourceId: Long): Set<String> {
        return favoriteUrls ?: withContext(Dispatchers.IO) {
            requireNotNull(mangaRepository).getFavoriteUrlsBySourceId(sourceId)
                .first()
                .toHashSet()
                .also { favoriteUrls = it }
        }
    }

    abstract suspend fun requestNextPage(source: Source, currentPage: Int): MangasPage

    override suspend fun load(params: LoadParams<Long>): LoadResult<Long, Manga> {
        var currentRemotePage = params.key ?: 1
        var lastFetchedRemotePage = currentRemotePage
        val accumulated = mutableListOf<Manga>()
        var hasNextPage = true
        var fetchCount = 0
        val maxFetches = if (libraryOnly) 20 else 1

        return try {
            val source = source()
            val favUrls = if (libraryOnly) getFavoriteUrls(source.id) else emptySet()

            while (accumulated.size < params.loadSize && hasNextPage && fetchCount < maxFetches) {
                val mangasPage = withContext(Dispatchers.IO) {
                    requestNextPage(source, currentRemotePage.toInt())
                        .takeIf { it.mangas.isNotEmpty() }
                        ?: throw NoResultsException()
                }
                fetchCount++
                lastFetchedRemotePage = currentRemotePage

                val domainManga = mangasPage.mangas
                    .map { it.toDomainManga(source.id) }
                    .filter { seenManga.add(it.url) }

                val toHydrate = if (libraryOnly) {
                    domainManga.filter { it.url in favUrls }
                } else {
                    domainManga
                }

                val manga = networkToLocalManga(toHydrate)
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
        } catch (e: CancellationException) {
            throw e
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

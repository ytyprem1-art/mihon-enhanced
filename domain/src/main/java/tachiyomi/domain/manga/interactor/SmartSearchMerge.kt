package tachiyomi.domain.manga.interactor

import eu.kanade.tachiyomi.source.mod.MERGED_SOURCE_ID
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.interactor.SetMangaCategories
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MergedMangaReference

class SmartSearchMerge(
    private val getManga: GetManga,
    private val getMangaByUrlAndSourceId: GetMangaByUrlAndSourceId,
    private val getMergedReferencesById: GetMergedReferencesById,
    private val insertMergedReference: InsertMergedReference,
    private val networkToLocalManga: NetworkToLocalManga,
    private val getCategories: GetCategories,
    private val setMangaCategories: SetMangaCategories,
    private val deleteByMergeId: DeleteByMergeId,
    private val deleteMangaById: DeleteMangaById,
) {
    suspend fun await(manga: Manga, originalMangaId: Long): Manga {
        val originalManga = getManga.await(originalMangaId)
            ?: throw IllegalArgumentException("Unknown manga ID: $originalMangaId")

        if (originalManga.source == MERGED_SOURCE_ID) {
            val children = getMergedReferencesById.await(originalMangaId)
            if (children.any { it.mangaSourceId == manga.source && it.mangaUrl == manga.url }) {
                throw IllegalArgumentException("This manga is already merged.")
            }

            val mangaReferences = mutableListOf(
                MergedMangaReference(
                    id = -1,
                    isInfoManga = false,
                    getChapterUpdates = true,
                    chapterSortMode = 0,
                    chapterPriority = 0,
                    downloadChapters = true,
                    mergeId = originalManga.id,
                    mergeUrl = originalManga.url,
                    mangaId = manga.id,
                    mangaUrl = manga.url,
                    mangaSourceId = manga.source,
                ),
            )

            if (children.isEmpty() || children.all { it.mangaSourceId != MERGED_SOURCE_ID }) {
                mangaReferences += MergedMangaReference(
                    id = -1,
                    isInfoManga = false,
                    getChapterUpdates = false,
                    chapterSortMode = 0,
                    chapterPriority = -1,
                    downloadChapters = false,
                    mergeId = originalManga.id,
                    mergeUrl = originalManga.url,
                    mangaId = originalManga.id,
                    mangaUrl = originalManga.url,
                    mangaSourceId = MERGED_SOURCE_ID,
                )
            }

            insertMergedReference.awaitAll(mangaReferences)

            return originalManga
        } else {
            if (manga.id == originalMangaId) {
                throw IllegalArgumentException("Cannot merge a manga with itself.")
            }

            var mergedManga = Manga.create().copy(
                url = originalManga.url,
                source = MERGED_SOURCE_ID,
                title = originalManga.title,
                artist = originalManga.artist,
                author = originalManga.author,
                description = originalManga.description,
                genre = originalManga.genre,
                status = originalManga.status,
                thumbnailUrl = originalManga.thumbnailUrl,
                favorite = true,
                lastUpdate = originalManga.lastUpdate,
                viewerFlags = originalManga.viewerFlags,
                chapterFlags = originalManga.chapterFlags,
                coverLastModified = originalManga.coverLastModified,
                dateAdded = System.currentTimeMillis(),
            )

            var existingManga = getMangaByUrlAndSourceId.await(mergedManga.url, mergedManga.source)
            while (existingManga != null) {
                if (existingManga.favorite) {
                    throw IllegalArgumentException("A merged entry already exists for this manga in your library.")
                } else {
                    existingManga.id.let { id ->
                        deleteByMergeId.await(id)
                        deleteMangaById.await(id)
                    }
                }
                existingManga = getMangaByUrlAndSourceId.await(mergedManga.url, mergedManga.source)
            }

            mergedManga = networkToLocalManga(mergedManga)

            val categories = getCategories.await(originalMangaId)
            if (categories.isNotEmpty()) {
                setMangaCategories.await(mergedManga.id, categories.map { it.id })
            }

            val originalMangaReference = MergedMangaReference(
                id = -1,
                isInfoManga = true,
                getChapterUpdates = true,
                chapterSortMode = 0,
                chapterPriority = 0,
                downloadChapters = true,
                mergeId = mergedManga.id,
                mergeUrl = mergedManga.url,
                mangaId = originalManga.id,
                mangaUrl = originalManga.url,
                mangaSourceId = originalManga.source,
            )

            val newMangaReference = MergedMangaReference(
                id = -1,
                isInfoManga = false,
                getChapterUpdates = true,
                chapterSortMode = 0,
                chapterPriority = 0,
                downloadChapters = true,
                mergeId = mergedManga.id,
                mergeUrl = mergedManga.url,
                mangaId = manga.id,
                mangaUrl = manga.url,
                mangaSourceId = manga.source,
            )

            val mergedMangaReference = MergedMangaReference(
                id = -1,
                isInfoManga = false,
                getChapterUpdates = false,
                chapterSortMode = 0,
                chapterPriority = -1,
                downloadChapters = false,
                mergeId = mergedManga.id,
                mergeUrl = mergedManga.url,
                mangaId = mergedManga.id,
                mangaUrl = mergedManga.url,
                mangaSourceId = MERGED_SOURCE_ID,
            )

            insertMergedReference.awaitAll(
                listOf(originalMangaReference, newMangaReference, mergedMangaReference),
            )

            return mergedManga
        }
    }
}

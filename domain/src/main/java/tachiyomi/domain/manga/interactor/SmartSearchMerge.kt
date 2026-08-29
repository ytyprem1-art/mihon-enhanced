package tachiyomi.domain.manga.interactor

import eu.kanade.tachiyomi.source.mod.MERGED_SOURCE_ID
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MergedMangaReference
import tachiyomi.domain.manga.repository.MangaMergeRepository

class SmartSearchMerge(
    private val getManga: GetManga,
    private val getMergedReferencesById: GetMergedReferencesById,
    private val insertMergedReference: InsertMergedReference,
    private val mangaMergeRepository: MangaMergeRepository,
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

            val newMergedManga = Manga.create().copy(
                url = originalManga.url,
                source = MERGED_SOURCE_ID,
                title = originalManga.title,
                favorite = true,
                lastUpdate = originalManga.lastUpdate,
                viewerFlags = originalManga.viewerFlags,
                chapterFlags = originalManga.chapterFlags,
                coverLastModified = originalManga.coverLastModified,
                dateAdded = originalManga.dateAdded,
            )

            val newId = mangaMergeRepository.insert(
                MergedMangaReference(
                    id = -1,
                    isInfoManga = false,
                    getChapterUpdates = false,
                    chapterSortMode = MergedMangaReference.CHAPTER_SORT_PRIORITY,
                    chapterPriority = -1,
                    downloadChapters = false,
                    mergeId = -1,
                    mergeUrl = newMergedManga.url,
                    mangaId = -1,
                    mangaUrl = newMergedManga.url,
                    mangaSourceId = MERGED_SOURCE_ID,
                )
            ) ?: throw Exception("Failed to create merged entry")

            val mangaReferences = listOf(
                MergedMangaReference(
                    id = -1,
                    isInfoManga = true,
                    getChapterUpdates = true,
                    chapterSortMode = 0,
                    chapterPriority = 0,
                    downloadChapters = true,
                    mergeId = newId,
                    mergeUrl = newMergedManga.url,
                    mangaId = originalManga.id,
                    mangaUrl = originalManga.url,
                    mangaSourceId = originalManga.source,
                ),
                MergedMangaReference(
                    id = -1,
                    isInfoManga = false,
                    getChapterUpdates = true,
                    chapterSortMode = 0,
                    chapterPriority = 1,
                    downloadChapters = true,
                    mergeId = newId,
                    mergeUrl = newMergedManga.url,
                    mangaId = manga.id,
                    mangaUrl = manga.url,
                    mangaSourceId = manga.source,
                ),
            )

            insertMergedReference.awaitAll(mangaReferences)

            return newMergedManga.copy(id = newId)
        }
    }
}

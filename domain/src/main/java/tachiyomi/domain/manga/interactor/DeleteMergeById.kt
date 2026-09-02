package tachiyomi.domain.manga.interactor

import eu.kanade.tachiyomi.source.mod.MERGED_SOURCE_ID
import tachiyomi.domain.manga.repository.MangaMergeRepository
import tachiyomi.domain.manga.repository.MangaRepository

sealed interface DeleteMergeResult {
    data object Success : DeleteMergeResult
    data object ParentDeleted : DeleteMergeResult
}

class DeleteMergeById(
    private val mangaMergeRepository: MangaMergeRepository,
    private val mangaRepository: MangaRepository,
) {

    suspend fun await(id: Long): DeleteMergeResult {
        val targetRef = mangaMergeRepository.getReferenceById(id) ?: return DeleteMergeResult.Success
        val mergeId = targetRef.mergeId ?: return DeleteMergeResult.Success

        mangaMergeRepository.deleteById(id)

        val remainingReferences = mangaMergeRepository.getReferencesById(mergeId)

        val hasNoChildSources = remainingReferences.none { it.mangaSourceId != MERGED_SOURCE_ID }

        if (hasNoChildSources) {
            mangaMergeRepository.deleteByMergeId(mergeId)

            val parentManga = mangaRepository.getMangaById(mergeId)
            if (parentManga.source == MERGED_SOURCE_ID) {
                mangaRepository.deleteManga(mergeId)
                return DeleteMergeResult.ParentDeleted
            }
        }
        return DeleteMergeResult.Success
    }
}

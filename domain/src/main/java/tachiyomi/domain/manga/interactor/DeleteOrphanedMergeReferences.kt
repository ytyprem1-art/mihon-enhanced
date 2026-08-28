package tachiyomi.domain.manga.interactor

import tachiyomi.domain.manga.repository.MangaMergeRepository

class DeleteOrphanedMergeReferences(
    private val mangaMergeRepository: MangaMergeRepository,
) {
    suspend fun await() {
        mangaMergeRepository.deleteOrphaned()
    }
}

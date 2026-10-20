package tachiyomi.domain.manga.interactor

import tachiyomi.domain.manga.repository.MangaMergeRepository

@dev.zacsweers.metro.Inject
class DeleteByMergeId(
    private val mangaMergeRepository: MangaMergeRepository,
) {

    suspend fun await(id: Long) {
        return mangaMergeRepository.deleteByMergeId(id)
    }
}

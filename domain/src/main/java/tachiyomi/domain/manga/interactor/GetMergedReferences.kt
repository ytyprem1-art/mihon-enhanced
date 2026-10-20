package tachiyomi.domain.manga.interactor

import kotlinx.coroutines.flow.Flow
import tachiyomi.domain.manga.model.MergedMangaReference
import tachiyomi.domain.manga.repository.MangaMergeRepository

@dev.zacsweers.metro.Inject
class GetMergedReferences(
    private val mangaMergeRepository: MangaMergeRepository,
) {
    suspend fun await(): List<MergedMangaReference> {
        return mangaMergeRepository.getAllReferences()
    }

    suspend fun subscribe(): Flow<List<MergedMangaReference>> {
        return mangaMergeRepository.subscribeAllReferences()
    }
}

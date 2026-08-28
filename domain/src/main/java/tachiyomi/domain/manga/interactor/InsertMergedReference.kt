package tachiyomi.domain.manga.interactor

import tachiyomi.domain.manga.model.MergedMangaReference
import tachiyomi.domain.manga.repository.MangaMergeRepository

class InsertMergedReference(
    private val mangaMergeRepository: MangaMergeRepository,
) {

    suspend fun await(reference: MergedMangaReference): Long? {
        return mangaMergeRepository.insert(reference)
    }

    suspend fun awaitAll(references: List<MergedMangaReference>) {
        mangaMergeRepository.insertAll(references)
    }
}

package tachiyomi.domain.manga.interactor

import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaMergeRepository

@dev.zacsweers.metro.Inject
class GetMergedMangaForDownloading(
    private val mangaMergeRepository: MangaMergeRepository,
) {

    suspend fun await(mergeId: Long): List<Manga> {
        return mangaMergeRepository.getMergeMangaForDownloading(mergeId)
    }
}

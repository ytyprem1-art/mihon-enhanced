package tachiyomi.domain.manga.interactor

import tachiyomi.domain.manga.repository.MangaRepository

@dev.zacsweers.metro.Inject
class DeleteMangaById(
    private val mangaRepository: MangaRepository,
) {
    suspend fun await(id: Long) {
        mangaRepository.deleteManga(id)
    }
}

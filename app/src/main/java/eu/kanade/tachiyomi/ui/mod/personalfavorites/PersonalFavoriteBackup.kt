package eu.kanade.tachiyomi.ui.mod.personalfavorites

import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository

object PersonalFavoriteBackup {
    /** Include unread, non-library starred manga when manga records are selected for backup. */
    suspend fun mangaRecords(repository: MangaRepository): List<Manga> =
        PersonalFavorites.store.snapshot().mapNotNull {
            repository.getMangaByUrlAndSourceId(it.url, it.source)
        }

    fun snapshot(enabled: Boolean, store: PersonalFavoriteStore = PersonalFavorites.store): List<PersonalFavorite> =
        if (enabled) store.snapshot().sortedWith(compareBy({ it.source }, { it.url })) else emptyList()

    fun restore(
        favorites: List<PersonalFavorite>,
        enabled: Boolean,
        store: PersonalFavoriteStore = PersonalFavorites.store,
    ) {
        if (enabled && favorites.isNotEmpty()) store.restore(favorites)
    }
}

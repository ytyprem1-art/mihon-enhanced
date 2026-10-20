package eu.kanade.tachiyomi.ui.mod.personalfavorites

import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber
import tachiyomi.domain.manga.model.Manga

/** Stable identity; intentionally independent of mangas.favorite and local database IDs. */
@Serializable
data class PersonalFavorite(
    @ProtoNumber(1) val source: Long,
    @ProtoNumber(2) val url: String,
) {
    init {
        require(url.isNotBlank())
    }
}

fun Manga.personalFavoriteIdentity() = PersonalFavorite(source, url)

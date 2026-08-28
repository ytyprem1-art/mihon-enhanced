package tachiyomi.domain.manga.model

data class MergeMangaSettingsUpdate(
    val id: Long,
    val isInfoManga: Boolean? = null,
    val getChapterUpdates: Boolean? = null,
    val chapterPriority: Int? = null,
    val downloadChapters: Boolean? = null,
    val chapterSortMode: Int? = null,
)

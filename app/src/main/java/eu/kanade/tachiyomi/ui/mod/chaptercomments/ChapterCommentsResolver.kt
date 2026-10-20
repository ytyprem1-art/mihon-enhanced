package eu.kanade.tachiyomi.ui.mod.chaptercomments

import eu.kanade.domain.chapter.model.toSChapter
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.ui.mod.di.EnhancedGraph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import tachiyomi.domain.chapter.model.Chapter

/** Resolve the actual chapter's manga/source, including chapters displayed through a merged manga. */
suspend fun resolveCommentTarget(graph: EnhancedGraph, chapter: Chapter): CommentTarget = withContext(Dispatchers.IO) {
    val manga = graph.enhancedMangaRepository.getMangaById(chapter.mangaId)
    val source = graph.enhancedSourceManager.get(manga.source) as? HttpSource
        ?: error("Komentar web tidak tersedia untuk source ini.")
    val url = normalizeCommentChapterUrl(source.baseUrl, chapter.url, source.getChapterUrl(chapter.toSChapter()))
    require(url.toHttpUrl().isHttps || url.toHttpUrl().scheme == "http")
    CommentTarget(url, chapter.name, source.name, source.id)
}

/** Older Shinigami extensions store bare UUIDs, while HttpSource's default URL merely concatenates. */
fun normalizeCommentChapterUrl(baseUrl: String, chapterUrl: String, resolvedUrl: String): String {
    val base = baseUrl.toHttpUrl()
    if (base.host == "shinigami.asia" || base.host.endsWith(".shinigami.asia") || base.host == "shinigami.id" || base.host.endsWith(".shinigami.id")) {
        val path = chapterUrl.toHttpUrlOrNull()?.pathSegments
            ?: chapterUrl.substringBefore('?').substringBefore('#').trim('/').split('/')
        val id = path.lastOrNull { it.isNotBlank() }.orEmpty()
        require(Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}").matches(id)) { "ID chapter Shinigami tidak dikenali." }
        return base.newBuilder().encodedPath("/chapter/$id").query(null).fragment(null).build().toString()
    }
    return resolvedUrl
}

fun chapterCommentsProvider(target: CommentTarget, transport: CommentsTransport): ChapterCommentsProvider {
    val host = target.url.toHttpUrl().host.lowercase()
    return when {
        host == "shinigami.asia" || host.endsWith(".shinigami.asia") || host == "shinigami.id" || host.endsWith(".shinigami.id") -> ShinigamiCommentsProvider(target, transport)
        host == "asurascans.com" || host.endsWith(".asurascans.com") || host == "asuracomic.net" || host.endsWith(".asuracomic.net") -> AsuraCommentsProvider(target, transport)
        host == "ikiru.wtf" || host.endsWith(".ikiru.wtf") || host == "ikiru.id" || host.endsWith(".ikiru.id") -> IkiruCommentsProvider(target, transport)
        host == "voratoon.com" || host.endsWith(".voratoon.com") || host == "voratoon.co" || host.endsWith(".voratoon.co") -> VoratoonCommentsProvider(target, transport)
        host == "kiryuu.to" || host.endsWith(".kiryuu.to") || host == "kiryuu.org" || host.endsWith(".kiryuu.org") -> KiryuuCommentsProvider(target, transport)
        host == "komiku.org" || host.endsWith(".komiku.org") || host == "komiku.id" || host.endsWith(".komiku.id") -> KomikuCommentsProvider(target, transport)
        host == "komiku.asia" || host.endsWith(".komiku.asia") -> KomikuAsiaCommentsProvider(target, transport)
        host == "doujin.desu.xxx" || host == "doujindesu.xxx" || host.endsWith(".doujindesu.xxx") -> DoujindesuCommentsProvider(target, transport)
        host == "mangadex.org" || host == "www.mangadex.org" -> MangaDexCommentsProvider(target, transport)
        host == "forums.mangadex.org" -> XenForoCommentsProvider(target, transport)
        else -> AutoCommentsProvider(target, transport)
    }
}

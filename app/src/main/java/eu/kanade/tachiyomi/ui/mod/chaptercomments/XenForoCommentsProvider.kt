package eu.kanade.tachiyomi.ui.mod.chaptercomments

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/** Public XenForo thread pages, in their actual chronological order. */
class XenForoCommentsProvider(
    private val target: CommentTarget,
    private val transport: CommentsTransport,
    private val initialHtml: String? = null,
    private val expectedTotal: Int? = null,
    private val excludeChapterUrl: String? = null,
) : ChapterCommentsProvider {
    override val discussionUrl = target.url
    override val supportedSorts = listOf(CommentSort.OLDEST)
    private val mutex = Mutex()
    private val pages = mutableMapOf<Int, Document>()

    override suspend fun comments(sort: CommentSort, page: Int): CommentsPage = mutex.withLock {
        require(sort in supportedSorts) { "Forum ini menampilkan urutan terlama saja." }
        require(page >= 1)
        var current = pages[1] ?: parse(initialHtml ?: transport.get(target.url, target.url)).also { pages[1] = it }
        for (number in 2..page) {
            current = pages[number] ?: run {
                val link = next(current) ?: return@withLock CommentsPage(emptyList(), total(current) ?: 0, false, totalKnown = total(current) != null)
                val url = target.url.toHttpUrl().resolve(link) ?: error("Alamat halaman forum tidak valid")
                val origin = target.url.toHttpUrl()
                val thread = current.selectFirst("html#XF")?.attr("data-content-key")?.removePrefix("thread-")
                require(url.scheme == origin.scheme && url.host == origin.host && url.port == origin.port)
                require(url.pathSegments.firstOrNull() == "threads" && url.pathSegments.getOrNull(1)?.substringAfterLast('.') == thread)
                parse(transport.get(url.toString(), target.url)).also { pages[number] = it }
            }
        }
        val comments = current.select("article.message--post[data-content^=post-]").mapNotNull { post ->
            val body = post.selectFirst(".message-body .bbWrapper") ?: return@mapNotNull null
            val author = post.attr("data-author").ifBlank { post.selectFirst(".message-name .username")?.text().orEmpty() }
            // MangaDex's opening card is a system post, not a reader comment.
            if (excludeChapterUrl != null && post.hasClass("is-first") && author == "MangaDex" &&
                body.select("a[href]").any { it.absUrl("href").trimEnd('/') == excludeChapterUrl.trimEnd('/') }
            ) return@mapNotNull null
            val reactions = post.selectFirst(".reactionsBar-link")?.let { link ->
                val named = link.select("bdi").size
                val others = Regex("\\b([0-9,]+) others\\b").find(link.text())?.groupValues?.get(1)?.replace(",", "")?.toIntOrNull() ?: 0
                if (named > 0) listOf(ChapterReaction("Reactions", named + others)) else emptyList()
            }.orEmpty()
            ChapterComment(
                id = post.attr("data-content"),
                author = author,
                content = body.html(),
                createdAt = post.selectFirst(".message-attribution time[datetime]")?.attr("datetime").orEmpty(),
                avatar = post.selectFirst(".message-avatar img[src]")?.absUrl("src"),
                media = body.select("img[src]:not(.smilie)").map { it.absUrl("src") }
                    .filter { it.startsWith("https://") || it.startsWith("http://") }.distinct(),
                reactionCounts = reactions,
                voteCountsAvailable = false,
            )
        }
        CommentsPage(comments, total(current) ?: comments.size, next(current) != null, totalKnown = total(current) != null)
    }

    override suspend fun reactions() = emptyList<ChapterReaction>()

    private fun parse(html: String): Document = Jsoup.parse(html, target.url).also {
        check(it.selectFirst("html#XF[data-template=thread_view]") != null && it.selectFirst("article.message--post") != null) {
            "Diskusi forum tidak dapat dibaca tanpa masuk atau sedang tidak tersedia."
        }
    }

    private fun next(document: Document): String? = document.selectFirst("a.pageNav-jump--next[href]")?.attr("href")

    private fun total(document: Document): Int? {
        expectedTotal?.let { return it }
        for (script in document.select("script[type=application/ld+json]")) {
            val data = runCatching { Json.parseToJsonElement(script.data()) as? JsonObject }.getOrNull() ?: continue
            val statistics = data["interactionStatistic"] as? JsonObject ?: continue
            val count = (statistics["userInteractionCount"] as? JsonPrimitive)?.intOrNull ?: continue
            return count + 1 // XenForo's ReplyAction count excludes the opening post.
        }
        return null
    }
}

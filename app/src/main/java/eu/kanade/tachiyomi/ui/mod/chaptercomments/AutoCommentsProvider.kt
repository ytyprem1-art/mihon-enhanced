package eu.kanade.tachiyomi.ui.mod.chaptercomments

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup

/** Detect supported public templates on other domains, without executing page scripts. */
class AutoCommentsProvider(private val target: CommentTarget, private val transport: CommentsTransport) : ChapterCommentsProvider {
    private val mutex = Mutex()
    private var delegate: ChapterCommentsProvider? = null

    private suspend fun provider(): ChapterCommentsProvider = mutex.withLock {
        delegate ?: run {
            val html = transport.get(target.url, target.url)
            val doc = Jsoup.parse(html, target.url)
            when {
                doc.selectFirst("#comment-wrapper[data-model][data-id]") != null && doc.selectFirst("#comment-root") != null && doc.selectFirst("#comments-list-template") != null -> QqCommentsProvider(target, transport, html)
                doc.selectFirst("html#XF[data-template=thread_view]") != null -> XenForoCommentsProvider(target, transport, html)
                DoujindesuCommentsProvider.recognizes(html) -> DoujindesuCommentsProvider(target, transport)
                doc.select("[hx-get]").any { it.attr("hx-get").toHttpUrlOrNull()?.queryParameter("action") == "get_comments" } -> KiryuuCommentsProvider(target, transport, html)
                doc.selectFirst("[data-series-id][data-chapter-id][data-load-id]") != null && doc.selectFirst("#commentOverlay, #commentList") != null -> KomikuCommentsProvider(target, transport, html)
                else -> HtmlCommentsProvider(target, transport, html)
            }
        }.also { delegate = it }
    }

    override suspend fun prepare() = provider().prepare()

    override suspend fun comments(sort: CommentSort, page: Int) = provider().comments(sort, page)
    override suspend fun reactions() = provider().reactions()
    override suspend fun replies(comment: ChapterComment) = provider().replies(comment)
}

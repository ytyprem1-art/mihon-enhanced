package eu.kanade.tachiyomi.ui.mod.chaptercomments

import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/** Public server-rendered comment templates. No scripts or page images execute. */
class HtmlCommentsProvider(private val target: CommentTarget, private val transport: CommentsTransport, private val initialHtml: String? = null) : ChapterCommentsProvider {
    override val supportedSorts = listOf(CommentSort.OLDEST, CommentSort.NEWEST)
    override suspend fun comments(sort: CommentSort, page: Int): CommentsPage {
        require(page >= 1)
        val doc = Jsoup.parse(initialHtml ?: transport.get(target.url, target.url), target.url)
        val list = doc.selectFirst("#comments .comment-list, #comments .commentlist, ol.comment-list, ol.commentlist")
            ?: doc.selectFirst("#comments.comment-list")?.takeIf {
                // A recognized empty server list is valid; an arbitrary #comments div is not.
                doc.selectFirst(".comment-box #mainForm.commentForm input[name=blog_id]") != null ||
                    it.selectFirst("article.comment-body[id^=div-comment-]") != null
            }
            ?: error("Sistem komentar source ini belum didukung. Kamu masih bisa membacanya lewat tombol Buka web.")
        val articleLayout = list.children().any { it.tagName() == "article" && it.hasClass("comment-body") } ||
            (list.id() == "comments" && doc.selectFirst("#mainForm.commentForm input[name=blog_id]") != null)
        fun parse(el: Element, parentId: String? = null): ChapterComment {
            val replyElements = if (articleLayout) {
                el.select("article.comment-body[id^=div-comment-]").filter { candidate ->
                    candidate !== el && candidate.parents().firstOrNull { it.tagName() == "article" && it.hasClass("comment-body") } === el
                }
            } else {
                el.selectFirst(".children")?.children().orEmpty().filter { it.hasClass("comment") }
            }
            val own = el.clone().apply {
                if (articleLayout) select("article.comment-body").filter { it !== this }.forEach { it.remove() }
                else select(".children").remove()
            }
            val content = own.selectFirst(".comment-content, .comment-text")
            val time = own.selectFirst("time") ?: own.selectFirst(".comment-metadata, .comment-date")
            val replies = replyElements.map { parse(it, el.id()) }
            return ChapterComment(
                id = el.id(), author = own.selectFirst(".fn, .comment-author")?.text().orEmpty(),
                content = content?.html().orEmpty(),
                createdAt = time?.attr("datetime")?.ifBlank { time.text() }.orEmpty(),
                avatar = own.selectFirst("img.avatar")?.absUrl("src")?.takeIf { it.startsWith("https://") || it.startsWith("http://") },
                media = content?.select("img[src]")?.map { it.absUrl("src") }
                    ?.filter { it.startsWith("https://") || it.startsWith("http://") }?.distinct().orEmpty(),
                replies = replies, replyCount = replies.size,
                replyTo = parentId,
                voteCountsAvailable = false,
            )
        }
        val comments = list.children().filter {
            if (articleLayout) it.tagName() == "article" && it.hasClass("comment-body") else it.hasClass("comment")
        }.map { parse(it) }
        // Never claim a single visible HTML page represents all pages of a paginated thread.
        if (doc.selectFirst(".comment-navigation .nav-previous a, .comment-navigation .nav-next a") != null) {
            error("Komentar source ini memakai halaman terpisah. Gunakan Buka web untuk membaca semuanya.")
        }
        return CommentsPage(if (page > 1) emptyList() else if (sort == CommentSort.NEWEST) comments.reversed() else comments, comments.size, false)
    }

    override suspend fun reactions() = emptyList<ChapterReaction>()
}

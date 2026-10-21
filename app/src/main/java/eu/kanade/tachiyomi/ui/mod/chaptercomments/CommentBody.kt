package eu.kanade.tachiyomi.ui.mod.chaptercomments

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup

data class CommentBody(val text: String, val media: List<String>, val spoiler: Boolean)

/** Render data as text/images; never execute website HTML, scripts, iframes or event handlers. */
fun commentBody(comment: ChapterComment): CommentBody {
    val doc = Jsoup.parseBodyFragment(comment.content)
    doc.select("script, style, iframe, object, embed").remove()
    val spoiler =
        comment.isSpoiler ||
            doc.select(".spoiler, .wl-spoiler, .bbCodeBlock--spoiler, [data-spoiler], details").isNotEmpty()
    val media = (comment.media + doc.select("img[src]").map { it.attr("src") })
        .mapNotNull { it.toHttpUrlOrNull()?.toString() }.distinct()
    doc.select("br").forEach { it.replaceWith(org.jsoup.nodes.TextNode("\n")) }
    doc.select("p, div, li").forEach { it.appendText("\n") }
    val text = doc.body().wholeText().replace(Regex("\n{3,}"), "\n\n").trim()
    return CommentBody(text, media, spoiler)
}

fun flattenCommentReplies(replies: List<ChapterComment>, parent: String): List<ChapterComment> {
    val seen = mutableSetOf<String>()
    val result = mutableListOf<ChapterComment>()
    fun visit(items: List<ChapterComment>, author: String, depth: Int) {
        if (depth > 32) return
        items.forEach { item ->
            if (seen.add(item.id)) {
                result.add(
                    item.copy(
                        replyTo = item.replyTo?.takeUnless {
                            it.toLongOrNull() != null
                        } ?: author,
                        replies = emptyList(),
                    ),
                )
                visit(item.replies, item.author, depth + 1)
            }
        }
    }
    visit(replies, parent, 0)
    return result
}

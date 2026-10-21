package eu.kanade.tachiyomi.ui.mod.chaptercomments

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/** The public HTMX comment template, discovered from page markup rather than a domain list. */
class KiryuuCommentsProvider(
    private val target: CommentTarget,
    private val transport: CommentsTransport,
    private val initialHtml: String? = null,
) : ChapterCommentsProvider, ChapterReactionsProvider {
    private val mutex = Mutex()
    private var initial: Document? = null
    private var endpoint: HttpUrl? = null
    private val pages = mutableMapOf<Pair<CommentSort, Int>, Document>()
    private var reactionDocument: Document? = null
    private data class ReactionCard(
        val key: String,
        val label: String,
        val image: String?,
        val url: HttpUrl,
        val chapter: String,
        val selected: Boolean,
        val count: Int?,
    )
    private var cards = emptyList<ReactionCard>()

    override val reactionOptions: List<ChapterReactionOption>
        get() = cards.map {
            ChapterReactionOption(
                it.key,
                when (it.label.lowercase()) {
                    "upvote" -> "👍"
                    "funny" -> "😂"
                    "love" -> "❤️"
                    "surprised" -> "😮"
                    "angry" -> "😡"
                    "sad" -> "😢"
                    else -> "⭐"
                },
                it.label,
                it.image,
            )
        }

    override suspend fun prepare(): ChapterCommentsProvider = mutex.withLock {
        initial()
        if (cards.isNotEmpty() && nonceLink() != null) {
            this
        } else {
            object : ChapterCommentsProvider {
                override suspend fun comments(
                    sort: CommentSort,
                    page: Int,
                ) = this@KiryuuCommentsProvider.comments(sort, page)
                override suspend fun reactions() = this@KiryuuCommentsProvider.reactions()
                override suspend fun replies(comment: ChapterComment) = comment.replies
            }
        }
    }

    override suspend fun chapterVoteIdentity(): String = mutex.withLock {
        initial()
        "htmx:${target.sourceId ?: endpoint!!.host}:${cards.firstOrNull()?.chapter ?: error("Reaksi tidak tersedia.") }"
    }

    override suspend fun setChapterUpvote(enabled: Boolean) =
        setChapterReaction(if (enabled) null else "1", if (enabled) "1" else null)

    override suspend fun setChapterReaction(previous: String?, next: String?) = mutex.withLock {
        initial()
        if (previous == next) return@withLock
        require(previous == null || cards.any { it.key == previous })
        require(next == null || cards.any { it.key == next })
        val choice = cards.firstOrNull { it.key == (next ?: previous) } ?: error("Reaksi tidak tersedia.")
        val nonceUrl = nonceLink() ?: error("Formulir reaksi tidak tersedia.")
        val nonce =
            Jsoup.parse(
                transport.get(nonceUrl.toString(), target.url),
            ).selectFirst("input[name=reaction_nonce]")?.attr("value")
                ?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,128}")) }
                ?: error("Tidak bisa menyiapkan reaksi situs. Coba lagi.")
        val response = Jsoup.parse(
            transport.postReactionForm(
                choice.url.toString(),
                mapOf(
                    "id" to choice.chapter,
                    "reaction" to choice.key,
                    "reaction_nonce" to nonce,
                ),
                target.url,
            ),
            target.url,
        )
        val updated = readCards(response)
        check(
            updated.map { it.key }.toSet() == cards.map { it.key }.toSet() &&
                updated.map { it.key }.distinct().size == updated.size &&
                updated.size == cards.size && updated.all { it.count != null } &&
                updated.map { it.chapter }.toSet() == setOf(choice.chapter),
        ) {
            "Situs belum mengonfirmasi reaksi. Coba lagi lewat WebView."
        }
        val selected = updated.filter { it.selected }.map { it.key }
        // Anonymous responses can render every card unselected even after accepting the vote.
        // Confirm the affected counters from the POST response; never invent an increment or retry.
        val countAcknowledged = selected.isEmpty() && listOfNotNull(previous, next).distinct().all { key ->
            val before = cards.firstOrNull { it.key == key }?.count
            val after = updated.firstOrNull { it.key == key }?.count
            before != null && after != null && after.toLong() - before ==
                (if (key == next) 1L else 0L) - (if (key == previous) 1L else 0L)
        }
        cards = updated
        reactionDocument = response
        if (!(selected.isNotEmpty() && selected == listOfNotNull(next)) &&
            !countAcknowledged
        ) {
            throw ChapterReactionUpdateException(
                selected.singleOrNull(),
                IllegalStateException("Situs mengembalikan pilihan reaksi yang berbeda. Muat ulang komentar."),
            )
        }
    }

    private fun nonceLink(): HttpUrl? = initial?.select("[hx-get]")?.mapNotNull {
        it.attr("hx-get").toHttpUrlOrNull()
    }?.firstOrNull {
        sameEndpoint(it) && it.queryParameter("action") == "get_nonce" && it.queryParameter("type") == "reaction_form"
    }

    private fun sameEndpoint(url: HttpUrl) = endpoint?.let {
        url.scheme == it.scheme && url.host == it.host && url.port == it.port && url.encodedPath == it.encodedPath
    } == true

    private fun readCards(document: Document): List<ReactionCard> = document.select(
        "#response-data [hx-post]",
    ).mapNotNull { card ->
        val url =
            card.attr("hx-post").toHttpUrlOrNull()?.takeIf {
                sameEndpoint(it) &&
                    it.queryParameter("action") == "set_my_reaction"
            }
                ?: return@mapNotNull null
        val data =
            runCatching { Json.parseToJsonElement(card.attr("hx-vals")) as? JsonObject }.getOrNull()
                ?: return@mapNotNull null
        fun text(key: String) = (data[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val id =
            text("id").takeIf {
                it.toLongOrNull()?.let { n -> n > 0 } == true &&
                    it == endpoint!!.queryParameter("chapter_id")
            }
                ?: return@mapNotNull null
        val key = text("reaction").takeIf { it.toIntOrNull()?.let { n -> n > 0 } == true } ?: return@mapNotNull null
        val image = card.selectFirst("img[alt]") ?: return@mapNotNull null
        ReactionCard(
            key,
            image.attr("alt"),
            image.absUrl("src").takeIf { it.startsWith("https://") },
            url,
            id,
            card.attr("data-selected") == "true",
            card.select("span").firstOrNull()?.text()?.trim()?.toIntOrNull()?.takeIf {
                it >=
                    0
            },
        )
    }

    private suspend fun initial(): Document {
        initial?.let { return it }
        val chapter = Jsoup.parse(initialHtml ?: transport.get(target.url, target.url), target.url)
        val marker = chapter.select("[hx-get]").firstOrNull {
            it.attr("hx-get").trim().toHttpUrlOrNull()?.queryParameter("action") == "get_comments"
        } ?: error("Sistem komentar HTMX tidak ditemukan")
        val url = marker.attr("hx-get").trim().toHttpUrl()
        require(url.host == target.url.toHttpUrl().host && url.scheme == "https")
        val response = Jsoup.parse(transport.get(url.toString(), target.url), target.url)
        check(response.selectFirst("#comment-area") != null) { "Data komentar HTMX tidak valid" }
        endpoint = url
        initial = response
        cards = readCards(response)
        pages[CommentSort.POPULAR to 1] = response
        return response
    }

    override suspend fun comments(sort: CommentSort, page: Int): CommentsPage = mutex.withLock {
        require(page >= 1)
        initial()
        val key = sort to page
        suspend fun firstPage(): Document {
            pages[sort to 1]?.let { return it }
            val order = when (sort) {
                CommentSort.POPULAR -> "best"
                CommentSort.NEWEST -> "newest"
                CommentSort.OLDEST -> "oldest"
            }
            val link = initial!!.select("[hx-get]").firstOrNull {
                val url = it.attr("hx-get").toHttpUrlOrNull()
                url?.queryParameter("action") == "get_comments_order" && url.queryParameter("order") == order
            } ?: error("Urutan komentar tidak tersedia")
            return fetch(link.attr("hx-get"), "get_comments_order").also { pages[sort to 1] = it }
        }
        val document = pages[key] ?: if (page == 1) {
            firstPage()
        } else {
            // Follow only server-advertised read links. Never infer write actions or execute HTMX.
            var current = firstPage()
            for (number in 2..page) {
                current = pages[sort to number] ?: run {
                    val next =
                        nextPage(current) ?: return@withLock CommentsPage(emptyList(), 0, false, totalKnown = false)
                    fetch(next, "get_comments_order_page").also { pages[sort to number] = it }
                }
            }
            current
        }
        val roots = commentElements(document).filter { parentComment(it) == null }
        val comments = roots.map { parseComment(it) }
        // This template does not consistently expose an authoritative comment total.
        CommentsPage(comments, comments.size, nextPage(document) != null, totalKnown = false)
    }

    override suspend fun reactions(): List<ChapterReaction> = mutex.withLock {
        (reactionDocument ?: initial()).select("#response-data [hx-post]").mapNotNull { card ->
            val action = card.attr("hx-post").toHttpUrlOrNull()?.queryParameter("action")
            if (action != "set_my_reaction") return@mapNotNull null
            val label = card.selectFirst("img[alt]")?.attr("alt") ?: return@mapNotNull null
            val count = card.select("span").firstOrNull()?.text()?.trim()?.toIntOrNull() ?: return@mapNotNull null
            ChapterReaction(label, count.coerceAtLeast(0))
        }
    }

    private suspend fun fetch(link: String, action: String): Document {
        val url = link.trim().toHttpUrl()
        val expected = endpoint!!
        require(url.scheme == expected.scheme && url.host == expected.host && url.port == expected.port)
        require(url.encodedPath == expected.encodedPath && url.queryParameter("action") == action)
        return Jsoup.parse(transport.get(url.toString(), target.url), target.url)
    }

    private fun nextPage(document: Document): String? = document.select("[hx-get]").firstOrNull {
        it.attr("hx-get").toHttpUrlOrNull()?.queryParameter("action") == "get_comments_order_page"
    }?.attr("hx-get")

    private fun parseComment(element: Element): ChapterComment {
        val id = element.id().removePrefix("comment-")
        val content = element.selectFirst("#content-$id")
        val replies = commentElements(element).filter { parentComment(it) === element }.map { parseComment(it) }
        fun count(type: String): Int = element.selectFirst("#like-dislike-$id [data-ltype=$type]")
            ?.select("span")?.lastOrNull()?.text()?.trim()?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        return ChapterComment(
            id = id,
            author = element.selectFirst("a[href*=/profile/]")?.text().orEmpty().ifBlank { "Pengguna" },
            content = content?.text().orEmpty(),
            createdAt = element.selectFirst("time")?.let { it.attr("datetime").ifBlank { it.text() } }
                ?: element.selectFirst("span.text-gray-400.text-xs")?.text().orEmpty(),
            avatar = element.selectFirst("img.avatar")?.absUrl("src")?.ifBlank { null },
            upvotes = count("like"),
            downvotes = count("dislike"),
            replyCount = replies.size,
            replies = replies,
            media = content?.select("img[src]")?.map {
                it.absUrl("src")
            }?.filter { it.startsWith("https://") }?.distinct().orEmpty(),
            replyTo = parentComment(element)?.id()?.removePrefix("comment-"),
        )
    }

    private fun commentElements(element: Element): List<Element> = element.select("[id^=comment-]")
        .filter { it !== element && COMMENT_ID.matches(it.id()) }

    private fun parentComment(element: Element): Element? = element.parents().firstOrNull {
        COMMENT_ID.matches(it.id())
    }

    private companion object {
        val COMMENT_ID = Regex("comment-\\d+")
    }
}

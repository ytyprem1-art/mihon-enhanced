package eu.kanade.tachiyomi.ui.mod.chaptercomments

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.Jsoup
import org.jsoup.nodes.TextNode

/** Public Komiku Plus comment protocol, selected by chapter markup rather than source name. */
class KomikuCommentsProvider(
    private val target: CommentTarget,
    private val transport: CommentsTransport,
    private val initialHtml: String? = null,
) : ChapterCommentsProvider {
    private data class Identity(val series: String, val chapter: String)
    private val identityLock = Mutex()
    private val pageLock = Mutex()
    private var identity: Identity? = null
    private val pages = mutableMapOf<Pair<CommentSort, Int>, JsonObject>()
    private var chapterData: JsonObject? = null

    private suspend fun identity(): Identity = identityLock.withLock {
        identity?.let { return@withLock it }
        val document = Jsoup.parse(initialHtml ?: transport.get(target.url, target.url), target.url)
        val marker = document.selectFirst("[data-series-id][data-chapter-id][data-load-id]")
            ?: error("Penanda komentar Komiku Plus tidak ditemukan")
        val series = marker.attr("data-series-id")
        val chapter = marker.attr("data-chapter-id")
        require(series.matches(Regex("[0-9]+")) && chapter.matches(Regex("[0-9]+"))) {
            "Identitas chapter Komiku Plus tidak valid"
        }
        Identity(series, chapter).also { identity = it }
    }

    private suspend fun load(sort: CommentSort, page: Int): JsonObject {
        val id = identity()
        val order = when (sort) {
            CommentSort.POPULAR -> "popular"
            CommentSort.NEWEST -> "latest"
            CommentSort.OLDEST -> "earliest"
        }
        val url = "$API/comment/list".toHttpUrl().newBuilder()
            .addQueryParameter("series_id", id.series)
            .addQueryParameter("chapter_id", id.chapter)
            .addQueryParameter("sort_type", order)
            .addQueryParameter("page", page.toString()).build()
        return request(url.toString()).also { root -> root.obj("chapter_data")?.let { chapterData = it } }
    }

    override suspend fun comments(sort: CommentSort, page: Int): CommentsPage = pageLock.withLock {
        require(page >= 1)
        val root = pages.getOrPutSuspending(sort to page) { load(sort, page) }
        val comments = root.array("data").mapNotNull { it as? JsonObject }.map { parseComment(it) }
        CommentsPage(
            comments = comments,
            total = chapterData?.number("comment_total") ?: comments.size,
            hasMore = root.obj("paging")?.number("next_page") != null,
            totalKnown = chapterData?.number("comment_total") != null,
        )
    }

    override suspend fun reactions(): List<ChapterReaction> = pageLock.withLock {
        if (chapterData == null) pages.getOrPutSuspending(CommentSort.NEWEST to 1) { load(CommentSort.NEWEST, 1) }
        chapterData?.array("reaction_emotes").orEmpty().mapNotNull { it as? JsonObject }.map {
            ChapterReaction(it.text("emote"), it.number("total") ?: 0)
        }
    }

    override suspend fun replies(comment: ChapterComment): List<ChapterComment> {
        if (comment.replies.isNotEmpty()) return comment.replies
        require(comment.id.matches(Regex("[A-Za-z0-9_-]+"))) { "Identitas balasan Komiku tidak valid" }
        val result = linkedMapOf<String, ChapterComment>()
        val visited = mutableSetOf<Int>()
        var page: Int? = 1
        // This protocol paginates replies independently; load all only after explicit expansion.
        while (page != null) {
            check(visited.add(page)) { "Halaman balasan Komiku berulang" }
            val url = "$API/reply/list".toHttpUrl().newBuilder()
                .addQueryParameter("comment_hash_id", comment.id)
                .addQueryParameter("use_parent", "true")
                .addQueryParameter("sort_type", "latest")
                .addQueryParameter("page", page.toString()).build()
            val root = request(url.toString())
            root.array("data").mapNotNull { it as? JsonObject }.forEach {
                val child = parseComment(it, comment.id)
                result[child.id] = child
            }
            page = root.obj("paging")?.number("next_page")
        }
        return result.values.toList()
    }

    private suspend fun request(url: String): JsonObject {
        val body = transport.getWithHeaders(url, target.url, PUBLIC_HEADERS)
        val root = Json.parseToJsonElement(body) as? JsonObject ?: error("Respons Komiku Plus tidak valid")
        check(root.text("code") == "OK") { root.text("message").ifBlank { "Gagal memuat komentar Komiku Plus" } }
        return root
    }

    private fun parseComment(item: JsonObject, parentId: String? = null): ChapterComment {
        val sticker = item.text("sticker_image").takeIf { it.isNotBlank() }
        val stickerUrl = sticker?.let { "$MEDIA/".toHttpUrl().resolve(it)?.toString() }
        val nested = item.text("nested_reply_hash_id").takeIf { it.isNotBlank() }
        return ChapterComment(
            id = item.text("hash_id"),
            author = item.text("member_display_name").ifBlank { "Anonim" },
            // The source renderer HTML-escapes body; preserve literal user text in our HTML-capable view.
            content = TextNode(item.text("body")).outerHtml(),
            createdAt = item.text("relative_time").ifBlank { item.text("created_at") },
            avatar = item.text("member_profile_image").takeIf { it.isNotBlank() },
            replyCount = item.number("reply_total") ?: 0,
            media = listOfNotNull(stickerUrl),
            replyTo = nested ?: parentId,
            reactionCounts = item.array("reaction_emotes").mapNotNull { it as? JsonObject }.map {
                ChapterReaction(it.text("emote"), it.number("total") ?: 0)
            },
        )
    }

    private suspend fun <K, V> MutableMap<K, V>.getOrPutSuspending(key: K, block: suspend () -> V): V =
        this[key] ?: block().also { put(key, it) }

    private fun JsonObject.obj(key: String) = this[key] as? JsonObject
    private fun JsonObject.array(key: String) = (this[key] as? JsonArray).orEmpty()
    private fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
    private fun JsonObject.number(key: String) = (this[key] as? JsonPrimitive)?.intOrNull

    private companion object {
        const val API = "https://plus.komiku.org/api/mobile/v1.0"
        const val MEDIA = "https://plus.komiku.org/assets/media/images"
        val PUBLIC_HEADERS = mapOf("x-package-name" to "id.komiku.plus", "x-fcm-token" to "null", "x-version-code" to "5")
    }
}

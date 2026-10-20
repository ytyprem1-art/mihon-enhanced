package eu.kanade.tachiyomi.ui.mod.chaptercomments

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.Jsoup

/** QQ Comment Service's public list API, discovered from its chapter wrapper. */
class QqCommentsProvider(
    private val target: CommentTarget,
    private val transport: CommentsTransport,
    private val initialHtml: String? = null,
) : ChapterCommentsProvider {
    override val supportedSorts = listOf(CommentSort.NEWEST, CommentSort.OLDEST)
    private val mutex = Mutex()
    private var identity: Pair<String, String>? = null

    private suspend fun identity(): Pair<String, String> = mutex.withLock {
        identity ?: run {
            val document = Jsoup.parse(initialHtml ?: transport.get(target.url, target.url), target.url)
            val wrapper = document.selectFirst("#comment-wrapper[data-model][data-id]")
                ?: error("Identitas komentar QQ tidak ditemukan")
            check(document.selectFirst("#comment-root") != null && document.selectFirst("#comments-list-template") != null) {
                "Template komentar QQ tidak dikenali"
            }
            val model = wrapper.attr("data-model").trim()
            val id = wrapper.attr("data-id").trim()
            require(model.isNotEmpty() && id.isNotEmpty()) { "Identitas komentar QQ tidak valid" }
            (model to id).also { identity = it }
        }
    }

    override suspend fun comments(sort: CommentSort, page: Int): CommentsPage {
        require(sort in supportedSorts) { "Komentar ini mendukung urutan terbaru dan terlama." }
        require(page >= 1)
        val (model, id) = identity()
        val url = target.url.toHttpUrl().newBuilder().encodedPath("/api/comments/list").query(null)
            .fragment(null)
            .addQueryParameter("commentable_type", model)
            .addQueryParameter("commentable_id", id)
            .addQueryParameter("page", page.toString())
            .addQueryParameter("direction", if (sort == CommentSort.NEWEST) "desc" else "asc")
            .build()
        val response = Json.parseToJsonElement(
            transport.getWithHeaders(url.toString(), target.url, mapOf("Accept" to "application/json")),
        ) as? JsonObject ?: error("Data komentar QQ tidak valid")
        check((response["success"] as? JsonPrimitive)?.booleanOrNull != false && response.text("status") != "error") {
            response.text("message") ?: "Komentar QQ gagal dimuat"
        }
        val comments = response["comments"] as? JsonArray ?: error("Daftar komentar QQ tidak tersedia")
        val pagination = response["pagination"] as? JsonObject ?: error("Halaman komentar QQ tidak valid")
        val hasMore = (pagination["has_more_pages"] as? JsonPrimitive)?.booleanOrNull
            ?: error("Halaman komentar QQ tidak valid")
        val parsed = comments.mapNotNull { parseComment(it) }
        val count = response.text("comment_count")?.let {
            Regex("^\\s*([0-9][0-9,]*)").find(it)?.groupValues?.get(1)?.replace(",", "")?.toIntOrNull()
        }
        return CommentsPage(parsed, count ?: parsed.sumOf { 1 + it.replies.size }, hasMore, totalKnown = count != null)
    }

    override suspend fun reactions() = emptyList<ChapterReaction>()

    private fun parseComment(element: JsonElement, parentId: String? = null): ChapterComment? {
        val comment = element as? JsonObject ?: return null
        if ((comment["is_hidden"] as? JsonPrimitive)?.booleanOrNull == true || comment.text("deleted_at") != null) return null
        val id = comment.text("id")?.takeIf { it.toLongOrNull()?.let { number -> number > 0 } == true } ?: return null
        val commenter = comment["commenter"] as? JsonObject
        val body = comment.text("content").orEmpty()
        val replies = (comment["children"] as? JsonArray).orEmpty().mapNotNull { parseComment(it, id) }
        return ChapterComment(
            id = id,
            author = commenter?.text("name").orEmpty().ifBlank { "Unknown User" },
            content = body,
            createdAt = comment.text("created_at").orEmpty(),
            avatar = publicUrl(commenter?.text("avata")),
            upvotes = comment.number("like_count"),
            downvotes = comment.number("dislike_count"),
            replyCount = replies.size,
            replies = replies,
            replyTo = parentId ?: comment.text("parent_id"),
            media = Jsoup.parseBodyFragment(body, target.url).select("img[src]")
                .mapNotNull { publicUrl(it.attr("src")) }.distinct(),
        )
    }

    private fun publicUrl(value: String?): String? = value?.takeIf { it.isNotBlank() }
        ?.let { target.url.toHttpUrl().resolve(it) }?.takeIf { it.scheme == "https" || it.scheme == "http" }?.toString()

    private fun JsonObject.text(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.number(key: String): Int = (get(key) as? JsonPrimitive)?.intOrNull?.coerceAtLeast(0) ?: 0
}

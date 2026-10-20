package eu.kanade.tachiyomi.ui.mod.chaptercomments

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.Jsoup
import java.io.IOException

class IkiruCommentsProvider(
    private val target: CommentTarget,
    private val transport: CommentsTransport,
) : ChapterCommentsProvider, ChapterReactionsProvider {
    private data class Identity(val origin: String, val chapterId: String)
    private val identityLock = Mutex()
    private val firstPageLock = Mutex()
    private val voteLock = Mutex()
    private var identity: Identity? = null
    private var firstPage: JsonObject? = null
    private var totalCount: Int? = null
    override val reactionOptions = listOf(
        ChapterReactionOption("UPVOTE", "👍", "👍"),
        ChapterReactionOption("FUNNY", "😂", "😂"),
        ChapterReactionOption("LOVE", "❤️", "❤️"),
        ChapterReactionOption("SURPRISED", "😮", "😮"),
        ChapterReactionOption("SAD", "😢", "😢"),
        ChapterReactionOption("ANGRY", "😡", "😡"),
    )

    override suspend fun chapterVoteIdentity(): String {
        val id = identity()
        return "ikiru:chapter:${id.chapterId}"
    }

    override suspend fun setChapterUpvote(enabled: Boolean) =
        setChapterReaction(if (enabled) null else "UPVOTE", if (enabled) "UPVOTE" else null)

    override suspend fun setChapterReaction(previous: String?, next: String?) = voteLock.withLock {
        val keys = reactionOptions.map { it.key }
        require(previous == null || previous in keys) { "Reaksi Ikiru tidak dikenali" }
        require(next == null || next in keys) { "Reaksi Ikiru tidak dikenali" }
        if (previous != next) {
            val id = identity()
            // This API toggles a clicked existing type off; a different type replaces it in one call.
            val type = next ?: previous ?: error("Reaksi Ikiru tidak tersedia")
            val payload = buildJsonObject {
                put("postId", id.chapterId)
                put("type", type)
                put("postType", "chapter")
            }.toString()
            val body = try {
                transport.write("POST", "${id.origin}/api/private/reaction", payload, target.url)
            } catch (error: IOException) {
                if (Regex("\\b(401|403)\\b").containsMatchIn(error.message.orEmpty())) {
                    throw IllegalStateException("Situs Ikiru meminta login untuk memberi reaksi.", error)
                }
                throw error
            }
            val root = Json.parseToJsonElement(body) as? JsonObject ?: error("Respons reaksi Ikiru tidak valid")
            val status = root.number("code") ?: root.number("statusCode") ?: root.number("status")
            val message = root.text("message").ifBlank { root.text("statusMessage") }
            if (status == 401 || status == 403 || Regex("unauthenticated|unauthorized|forbidden|login|log in", RegexOption.IGNORE_CASE).containsMatchIn(message)) {
                error("Situs Ikiru meminta login untuk memberi reaksi.")
            }
            check((root["success"] as? JsonPrimitive)?.booleanOrNull == true && (status == null || status in 200..299)) {
                message.ifBlank { "Gagal mengirim reaksi Ikiru" }
            }
            val data = root.obj("data") ?: error("Respons reaksi Ikiru tidak valid")
            val action = data.text("action")
            val selected = data.text("type")
            check(if (next == null) {
                action.isNotBlank() && action !in listOf("created", "updated") && selected.isBlank()
            } else {
                action in listOf("created", "updated") && selected == next
            }) { "Situs Ikiru belum mengonfirmasi perubahan reaksi. Muat ulang komentar." }
            firstPageLock.withLock { firstPage = null }
        }
    }

    private suspend fun identity(): Identity = identityLock.withLock {
        identity?.let { return@withLock it }
        val url = target.url.toHttpUrl()
        val segments = url.pathSegments
        require(segments.size >= 3 && segments[0] == "manga" && segments[2].startsWith("chapter-")) {
            "Perbarui daftar chapter Ikiru untuk memakai alamat chapter terbaru"
        }
        var origin = "${url.scheme}://${url.host}"
        if (url.host == "ikiru.id" || url.host == "ikiru.wtf") {
            val portal = Jsoup.parse(transport.get("$origin/", target.url))
            val reader = portal.select("a[href]").map { it.attr("href") }.firstOrNull {
                val host = runCatching { it.toHttpUrl().host }.getOrNull()
                host != null && host.endsWith(".ikiru.wtf")
            }
            if (reader != null) {
                val readerUrl = reader.toHttpUrl()
                origin = "${readerUrl.scheme}://${readerUrl.host}"
            }
        }
        val api = "$origin/api/public/chapter/${segments[2].removePrefix("chapter-")}".toHttpUrl()
            .newBuilder().addQueryParameter("mangaSlug", segments[1]).build()
        val data = response(transport.get(api.toString(), target.url)).obj("data")
            ?: error("Identitas chapter Ikiru tidak tersedia")
        Identity(origin, data.text("id").ifBlank { error("Identitas chapter Ikiru tidak tersedia") })
            .also { identity = it }
    }

    private suspend fun load(sort: CommentSort, page: Int): JsonObject {
        val id = identity()
        val order = when (sort) {
            CommentSort.POPULAR -> "best"
            CommentSort.NEWEST -> "newest"
            CommentSort.OLDEST -> "oldest"
        }
        return response(transport.get("${id.origin}/api/public/comment/${id.chapterId}?orderby=$order&page=$page", target.url))
            .obj("data") ?: error("Respons komentar Ikiru tidak valid")
    }

    private suspend fun initial(): JsonObject = firstPageLock.withLock {
        firstPage ?: load(CommentSort.POPULAR, 1).also { firstPage = it }
    }

    override suspend fun comments(sort: CommentSort, page: Int): CommentsPage {
        require(page >= 1)
        val data = if (page == 1 && sort == CommentSort.POPULAR) initial() else load(sort, page)
        val roots = data.array("comments").mapNotNull { it as? JsonObject }.map(::parseComment)
        val meta = data.obj("pageData")
        meta?.number("totalCount")?.let { totalCount = it }
        val pinned = meta?.obj("pinned")?.let(::parseComment)
        val result = if (page == 1 && pinned != null) listOf(pinned) + roots.filterNot { it.id == pinned.id } else roots
        // Ikiru intentionally omits pageData on later pages; page length is its load-more contract.
        return CommentsPage(result, (totalCount ?: result.size).coerceAtLeast(result.size), roots.size == 10, totalKnown = totalCount != null)
    }

    override suspend fun reactions(): List<ChapterReaction> {
        val counts = initial().obj("pageData")?.obj("reaction") ?: return emptyList()
        return listOf("UPVOTE" to "👍", "FUNNY" to "😂", "LOVE" to "❤️", "SURPRISED" to "😮", "SAD" to "😢", "ANGRY" to "😡")
            .map { (key, label) -> ChapterReaction(label, counts.number(key) ?: 0) }
    }

    override suspend fun replies(comment: ChapterComment): List<ChapterComment> {
        if (comment.replies.isNotEmpty()) return comment.replies
        val id = identity()
        val url = "${id.origin}/api/public/comment/${comment.id}/replies".toHttpUrl().newBuilder()
            .addQueryParameter("parentId", comment.id).build()
        return response(transport.get(url.toString(), target.url)).obj("data")?.array("replies")
            .orEmpty().mapNotNull { it as? JsonObject }.map(::parseComment)
    }

    private fun parseComment(item: JsonObject): ChapterComment {
        val user = item.obj("user")
        val content = item.text("content")
        return ChapterComment(
            id = item.text("id"),
            author = user?.text("username")?.ifBlank { null } ?: "Anonim",
            content = content,
            createdAt = item.text("createdAt"),
            avatar = user?.obj("profile")?.text("avatarUrl")?.takeIf { it.isNotBlank() },
            upvotes = item.number("likes") ?: 0,
            downvotes = item.number("dislikes") ?: 0,
            replyCount = item.obj("_count")?.number("replies") ?: item.number("replyCount") ?: 0,
            replies = item.array("replies").mapNotNull { it as? JsonObject }.map(::parseComment),
            media = Jsoup.parseBodyFragment(content).select("img[src]").map { it.attr("src") } +
                Regex("!\\[[^]]*]\\((https?://[^\\s)]+)\\)").findAll(content).map { it.groupValues[1] }.toList(),
            replyTo = item.text("repliedToId").takeIf { it.isNotBlank() },
            isSpoiler = (item["isSpoiler"] as? JsonPrimitive)?.booleanOrNull == true,
        )
    }

    private fun response(body: String): JsonObject {
        val root = Json.parseToJsonElement(body) as? JsonObject ?: error("Respons Ikiru tidak valid")
        check((root["success"] as? JsonPrimitive)?.booleanOrNull == true) {
            root.text("message").ifBlank { "Gagal memuat komentar Ikiru" }
        }
        return root
    }

    private fun JsonObject.obj(key: String) = this[key] as? JsonObject
    private fun JsonObject.array(key: String) = (this[key] as? JsonArray).orEmpty()
    private fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
    private fun JsonObject.number(key: String) = (this[key] as? JsonPrimitive)?.intOrNull
}

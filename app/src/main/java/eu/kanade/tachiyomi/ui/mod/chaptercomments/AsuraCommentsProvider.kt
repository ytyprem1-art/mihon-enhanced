package eu.kanade.tachiyomi.ui.mod.chaptercomments

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import org.jsoup.Jsoup

/** Public Asura reads. A provider instance belongs to one open chapter popup. */
class AsuraCommentsProvider(
    private val target: CommentTarget,
    private val transport: CommentsTransport,
) : ChapterCommentsProvider, ChapterReactionsProvider {
    private val chapterMutex = Mutex()
    private var resolvedChapterId: Long? = null

    @Volatile
    private var confirmedReactionCounts: List<ChapterReaction>? = null

    override val reactionOptions = listOf(
        ChapterReactionOption("upvote", "👍", "upvote"),
        ChapterReactionOption("funny", "😂", "funny"),
        ChapterReactionOption("love", "❤️", "love"),
        ChapterReactionOption("surprised", "😲", "surprised"),
        ChapterReactionOption("angry", "😡", "angry"),
        ChapterReactionOption("sad", "😢", "sad"),
    )

    override suspend fun chapterVoteIdentity(): String = "asura:${chapterId()}"

    override suspend fun setChapterUpvote(enabled: Boolean) =
        setChapterReaction(if (enabled) null else "upvote", if (enabled) "upvote" else null)

    override suspend fun setChapterReaction(previous: String?, next: String?) {
        require(previous == null || reactionOptions.any { it.key == previous })
        require(next == null || reactionOptions.any { it.key == next })
        if (previous == next) return
        val body = if (next == null) """{"type":null}""" else """{"type":"$next"}"""
        val response = Json.parseToJsonElement(
            transport.write("POST", "$API/chapters/${chapterId()}/reactions", body, target.url),
        ) as? JsonObject ?: error("Respons vote Asura tidak valid")
        val success = (response["success"] as? JsonPrimitive)?.booleanOrNull
        val errno = (response["errno"] as? JsonPrimitive)?.intOrNull
        val serverError = response["error"]?.takeUnless { it is JsonNull }
        check(success != false && (errno == null || errno == 0) && serverError == null) {
            response.text("message") ?: response.text("error") ?: "Vote Asura gagal disimpan"
        }
        val data = response["data"] as? JsonObject ?: error("Respons vote Asura tidak valid")
        val counts = reactionCounts(data)
        check(data.containsKey("user_reaction")) { "Konfirmasi reaksi Asura tidak tersedia" }
        val confirmed = data.text("user_reaction")
        check(
            confirmed == null || reactionOptions.any {
                it.key == confirmed
            },
        ) { "Konfirmasi reaksi Asura tidak valid" }
        // The web client uses these authoritative mutation counts directly. A following
        // anonymous GET can still contain the public cache's older counts.
        confirmedReactionCounts = counts
        if (confirmed != next) {
            throw ChapterReactionUpdateException(
                confirmed,
                IllegalStateException("Server Asura belum mengonfirmasi pilihan reaksi."),
            )
        }
    }

    private suspend fun chapterId(): Long = chapterMutex.withLock {
        resolvedChapterId ?: run {
            val document = Jsoup.parse(transport.get(target.url, target.url))
            val props = document.select("astro-island[props]")
                .firstOrNull { it.attr("component-url").contains("ChapterReader.") }
                ?.attr("props")
                ?: error("Identitas bab Asura tidak ditemukan")
            val encoded = Json.parseToJsonElement(props) as? JsonObject
                ?: error("Data bab Asura tidak valid")
            val id = ((encoded["chapterId"] as? JsonArray)?.getOrNull(1) as? JsonPrimitive)
                ?.longOrNull?.takeIf { it > 0 }
                ?: error("Identitas bab Asura tidak ditemukan")
            resolvedChapterId = id
            id
        }
    }

    override suspend fun comments(sort: CommentSort, page: Int): CommentsPage {
        require(page >= 1)
        val sortValue = when (sort) {
            CommentSort.POPULAR -> "top"
            CommentSort.NEWEST -> "newest"
            CommentSort.OLDEST -> "oldest"
        }
        val offset = (page.toLong() - 1) * PAGE_SIZE
        val response =
            readObject("$API/chapters/${chapterId()}/comments?sort=$sortValue&limit=$PAGE_SIZE&offset=$offset")
        val comments = response.array("data").mapNotNull { parseComment(it) }
        val meta = response["meta"] as? JsonObject
        return CommentsPage(
            comments = comments,
            total = meta?.number("total") ?: comments.size,
            hasMore = (meta?.get("has_more") as? JsonPrimitive)?.booleanOrNull ?: false,
            totalKnown = (meta?.get("total") as? JsonPrimitive)?.intOrNull != null,
        )
    }

    override suspend fun reactions(): List<ChapterReaction> {
        confirmedReactionCounts?.let { return it }
        val response = readObject("$API/chapters/${chapterId()}/reactions")
        val data = response["data"] as? JsonObject ?: error("Data reaksi Asura tidak valid")
        return confirmedReactionCounts ?: reactionCounts(data)
    }

    private fun reactionCounts(data: JsonObject): List<ChapterReaction> {
        val counts = data["reaction_counts"] as? JsonObject ?: error("Jumlah reaksi Asura tidak tersedia")
        val parsed = counts.mapNotNull { (label, value) ->
            (value as? JsonPrimitive)?.intOrNull?.takeIf { it >= 0 }?.let { ChapterReaction(label, it) }
        }
        check(parsed.isNotEmpty()) { "Jumlah reaksi Asura tidak valid" }
        return parsed
    }

    override suspend fun replies(comment: ChapterComment): List<ChapterComment> {
        if (comment.replies.isNotEmpty() || comment.replyCount == 0) return comment.replies
        require(comment.id.toLongOrNull()?.let { it > 0 } == true)
        // The public replies endpoint returns an array, unlike the comments envelope.
        val response = Json.parseToJsonElement(transport.get("$API/comments/${comment.id}/replies", target.url))
        val replies = response as? JsonArray ?: error("Data balasan Asura tidak valid")
        return replies.mapNotNull { parseComment(it, comment.id) }
    }

    private suspend fun readObject(url: String): JsonObject =
        Json.parseToJsonElement(transport.get(url, target.url)) as? JsonObject
            ?: error("Data komentar Asura tidak valid")

    private fun parseComment(element: JsonElement, parentId: String? = null): ChapterComment? {
        val comment = element as? JsonObject ?: return null
        val id = comment.text("id")?.takeIf { it.toLongOrNull()?.let { number -> number > 0 } == true }
            ?: return null
        val user = comment["user"] as? JsonObject
        val media = comment.array("media_urls").mapNotNull { (it as? JsonPrimitive)?.contentOrNull } +
            listOfNotNull(comment.text("gif_url"), comment.text("image_url"))
        return ChapterComment(
            id = id,
            author = user?.text("username") ?: "Pengguna",
            content = comment.text("content").orEmpty(),
            createdAt = comment.text("created_at").orEmpty(),
            avatar = user?.text("profile_picture_url"),
            upvotes = comment.number("upvotes"),
            downvotes = comment.number("downvotes"),
            replyCount = comment.number("reply_count"),
            replies = comment.array("replies").mapNotNull { parseComment(it, id) },
            media = media.filter { it.startsWith("https://") || it.startsWith("http://") }.distinct(),
            replyTo = parentId,
        )
    }

    private fun JsonObject.text(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.number(key: String): Int = (get(key) as? JsonPrimitive)?.intOrNull?.coerceAtLeast(0) ?: 0
    private fun JsonObject.array(key: String): JsonArray = get(key) as? JsonArray ?: JsonArray(emptyList())

    private companion object {
        const val API = "https://api.asurascans.com/api"
        const val PAGE_SIZE = 20
    }
}

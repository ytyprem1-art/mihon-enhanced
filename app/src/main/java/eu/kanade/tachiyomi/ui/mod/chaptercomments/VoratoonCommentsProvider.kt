package eu.kanade.tachiyomi.ui.mod.chaptercomments

import kotlinx.coroutines.CancellationException
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
import java.time.OffsetDateTime

class VoratoonCommentsProvider(
    private val target: CommentTarget,
    private val transport: CommentsTransport,
) : ChapterCommentsProvider, ChapterReactionsProvider {
    private data class Identity(val series: String, val chapter: String)
    private data class Collection(val records: LinkedHashMap<String, JsonObject> = linkedMapOf(), var page: Int = 0, var more: Boolean = true, var total: Int? = null)
    private val identityLock = Mutex()
    private val collectionLock = Mutex()
    private val voteLock = Mutex()
    private var identity: Identity? = null
    private val collections = mutableMapOf<CommentSort, Collection>()
    private val api = "https://api.voratoon.com"
    override val reactionOptions = listOf(
        ChapterReactionOption("like", "👍", "👍"),
        ChapterReactionOption("laugh", "😂", "😂"),
        ChapterReactionOption("love", "❤️", "❤️"),
        ChapterReactionOption("sad", "😢", "😢"),
        ChapterReactionOption("angry", "😡", "😡"),
    )

    override suspend fun chapterVoteIdentity(): String {
        val id = identity()
        return "$api/series/${id.series}/chapters/${id.chapter}"
    }

    override suspend fun setChapterUpvote(enabled: Boolean) = voteLock.withLock {
        writeReaction("like", enabled)
    }

    override suspend fun setChapterReaction(previous: String?, next: String?) = voteLock.withLock {
        val keys = reactionOptions.map { it.key }
        require(previous == null || previous in keys) { "Reaksi Voratoon tidak dikenali" }
        require(next == null || next in keys) { "Reaksi Voratoon tidak dikenali" }
        if (previous != next) {
            if (previous != null) writeReaction(previous, false)
            if (next != null) {
                try {
                    writeReaction(next, true)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    if (previous != null) throw ChapterReactionUpdateException(null, error)
                    throw error
                }
            }
        }
    }

    private suspend fun writeReaction(type: String, enabled: Boolean) {
        val id = identity()
        val seriesId = id.series.toLongOrNull() ?: error("Identitas seri Voratoon tidak valid")
        val chapterId = id.chapter.toLongOrNull() ?: error("Identitas chapter Voratoon tidak valid")
        val payload = buildJsonObject {
            put("seriesId", seriesId)
            put("chapterId", chapterId)
            put("reactionType", type)
            put("isAdd", enabled)
        }.toString()
        val body = try {
            transport.write("POST", "$api/reactions", payload, target.url)
        } catch (error: IOException) {
            if (Regex("\\b(401|403)\\b").containsMatchIn(error.message.orEmpty())) {
                throw IllegalStateException("Situs Voratoon meminta login untuk memberi reaksi.", error)
            }
            throw error
        }
        val root = Json.parseToJsonElement(body) as? JsonObject ?: error("Respons vote Voratoon tidak valid")
        val status = root.number("status") ?: root.number("statusCode")
        val message = root.text("message").ifBlank { root.text("error") }
        if (status == 401 || status == 403 || Regex("unauthenticated|unauthorized|forbidden|login|log in", RegexOption.IGNORE_CASE).containsMatchIn(message)) {
            error("Situs Voratoon meminta login untuk memberi reaksi.")
        }
        check(status == null || status in 200..299) { message.ifBlank { "Gagal mengirim reaksi Voratoon (HTTP $status)." } }
        val data = root.obj("data")
        check((root["success"] as? JsonPrimitive)?.booleanOrNull != false &&
            (root["error"] as? JsonPrimitive)?.booleanOrNull != true &&
            data != null && ("reactions" in data || "selectedReaction" in data)) {
            message.ifBlank { "Respons vote Voratoon tidak valid" }
        }
        if ("selectedReaction" in data) {
            val selected = data.text("selectedReaction")
            check(if (enabled) selected == type else selected != type) {
                "Situs Voratoon belum mengonfirmasi perubahan reaksi. Muat ulang komentar."
            }
        }
    }

    private suspend fun identity(): Identity = identityLock.withLock {
        identity?.let { return@withLock it }
        val path = target.url.toHttpUrl().pathSegments
        val (slug, index) = if (path.size >= 4 && path[0] == "series" && path[2] == "chapter") {
            path[1] to path[3]
        } else if (path.firstOrNull() == "chapter" && path.getOrNull(1)?.contains("-chapter-") == true) {
            path[1].substringBefore("-chapter-") to path[1].substringAfter("-chapter-").substringBefore("-bahasa-")
        } else {
            error("Alamat chapter Voratoon tidak dikenali")
        }
        val url = "$api/series/".toHttpUrl().newBuilder().addPathSegment(slug).addPathSegment("chapters").addPathSegment(index).build()
        val data = response(transport.get(url.toString(), target.url)).obj("data") ?: error("Identitas chapter Voratoon tidak tersedia")
        Identity(
            (data.text("seriesId").ifBlank { data.obj("data")?.text("seriesId").orEmpty() })
                .ifBlank { error("Identitas seri Voratoon tidak tersedia") },
            data.text("id").ifBlank { error("Identitas chapter Voratoon tidak tersedia") },
        ).also { identity = it }
    }

    override suspend fun comments(sort: CommentSort, page: Int): CommentsPage = collectionLock.withLock {
        require(page >= 1)
        val id = identity()
        val state = collections.getOrPut(sort) { Collection() }
        while (state.more && tree(state.records.values.toList()).size < page * PAGE_SIZE) {
            val requested = state.page + 1
            val order = when (sort) {
                CommentSort.POPULAR -> "top"
                CommentSort.NEWEST -> "new"
                CommentSort.OLDEST -> "old"
            }
            val root = response(transport.get("$api/series/${id.series}/comments?chapterId=${id.chapter}&sort=$order&take=$PAGE_SIZE&page=$requested&viewerId=0", target.url))
            val records = root.array("data").mapNotNull { it as? JsonObject }
            val before = state.records.size
            records.forEach { state.records[it.text("id")] = it }
            val meta = root.obj("meta")
            val actualPage = meta?.number("page") ?: requested
            val lastPage = meta?.number("lastPage") ?: meta?.number("totalPages")
            meta?.number("total")?.let { state.total = it }
            state.more = when {
                state.records.size == before || actualPage < requested -> false
                lastPage != null -> actualPage < lastPage
                meta?.get("hasNextPage") is JsonPrimitive -> (meta["hasNextPage"] as JsonPrimitive).booleanOrNull == true
                else -> records.size >= PAGE_SIZE
            }
            state.page = actualPage
        }
        val roots = tree(state.records.values.toList())
        val sorted = roots.sortedWith { a, b ->
            val pin = pinned(state.records[b.id]).compareTo(pinned(state.records[a.id]))
            if (pin != 0) pin else when (sort) {
                CommentSort.NEWEST -> timestamp(b).compareTo(timestamp(a))
                CommentSort.OLDEST -> timestamp(a).compareTo(timestamp(b))
                CommentSort.POPULAR -> (b.upvotes + 2 * descendants(b)).compareTo(a.upvotes + 2 * descendants(a))
                    .takeIf { it != 0 } ?: timestamp(b).compareTo(timestamp(a))
            }
        }
        val offset = (page - 1) * PAGE_SIZE
        CommentsPage(sorted.drop(offset).take(PAGE_SIZE), if (state.more) state.total ?: roots.size else roots.size,
            state.more || offset + PAGE_SIZE < roots.size, totalKnown = state.total != null || !state.more)
    }

    override suspend fun reactions(): List<ChapterReaction> {
        val id = identity()
        val root = response(transport.get("$api/series/${id.series}/chapters/${id.chapter}/reactions", target.url))
        val counts = mutableMapOf<String, Int>()
        when (val data = root["data"]) {
            is JsonArray -> data.forEach {
                val name = (it as? JsonPrimitive)?.contentOrNull ?: (it as? JsonObject)?.let { value -> value.text("reactionType").ifBlank { value.text("type") } }
                val count = (it as? JsonObject)?.number("count") ?: (it as? JsonObject)?.number("total") ?: 1
                if (name != null) counts[name.lowercase()] = counts.getOrDefault(name.lowercase(), 0) + count
            }
            is JsonObject -> data.forEach { (key, value) -> counts[key.lowercase()] = (value as? JsonPrimitive)?.intOrNull ?: 0 }
            else -> Unit
        }
        return listOf("👍" to listOf("like", "upvote", "1"), "😂" to listOf("laugh", "funny", "2"), "❤️" to listOf("love", "nice", "3"), "😢" to listOf("sad", "6"), "😡" to listOf("angry", "5"))
            .map { (label, keys) -> ChapterReaction(label, keys.sumOf { counts[it] ?: 0 }) }
    }

    private fun tree(records: List<JsonObject>): List<ChapterComment> {
        val all = linkedMapOf<String, JsonObject>()
        val parents = mutableMapOf<String, String>()
        fun collect(item: JsonObject, parent: String? = null) {
            val id = item.text("id")
            if (id.isBlank() || all.putIfAbsent(id, item) != null) return
            (item.text("parentCommentId").ifBlank { item.obj("data")?.text("parentCommentId").orEmpty() }.ifBlank { parent.orEmpty() })
                .takeIf { it.isNotBlank() }?.let { parents[id] = it }
            (item["replies"] as? JsonArray ?: item.obj("data")?.get("replies") as? JsonArray).orEmpty()
                .mapNotNull { it as? JsonObject }.forEach { collect(it, id) }
        }
        records.forEach { collect(it) }
        // Reject parent cycles and leave unknown parents as visible root comments.
        parents.keys.toList().forEach { id ->
            val seen = mutableSetOf(id)
            var ancestor = parents[id]
            while (ancestor != null && seen.add(ancestor)) ancestor = parents[ancestor]
            if (ancestor != null || parents[id] !in all) parents.remove(id)
        }
        fun build(id: String): ChapterComment {
            val item = all.getValue(id)
            val children = all.keys.filter { parents[it] == id }.map(::build).sortedBy(::timestamp)
            return comment(item).copy(replies = children, replyCount = children.size)
        }
        return all.keys.filter { it !in parents }.map(::build)
    }

    private fun comment(item: JsonObject): ChapterComment {
        val data = item.obj("data")
        val user = item.obj("user") ?: data?.obj("user") ?: item.obj("author") ?: data?.obj("author")
        val userData = user?.obj("data") ?: user
        val body = item.text("content").ifBlank { data?.text("content").orEmpty() }
        val votes = data?.obj("votes")
        return ChapterComment(
            id = item.text("id"),
            author = userData?.text("fullName")?.ifBlank { userData.text("username") }?.ifBlank { null } ?: "Anonim",
            content = body,
            createdAt = item.text("createdAt"),
            avatar = userData?.text("avatarUrl")?.ifBlank { userData.text("avatar") }?.ifBlank { userData.text("image") }?.takeIf { it.isNotBlank() },
            upvotes = item.number("upvotes") ?: data?.number("upvotes") ?: votes?.number("upvotes") ?: 0,
            downvotes = item.number("downvotes") ?: votes?.number("downvotes") ?: 0,
            replyTo = item.text("parentCommentId").ifBlank { data?.text("parentCommentId").orEmpty() }.takeIf { it.isNotBlank() },
            media = listOfNotNull(item.text("imageUrl").ifBlank { data?.text("imageUrl").orEmpty() }.takeIf { it.isNotBlank() }) +
                Jsoup.parseBodyFragment(body).select("img[src]").map { it.attr("src") } +
                Regex("!\\[[^]]*]\\((https?://[^\\s)]+)\\)").findAll(body).map { it.groupValues[1] }.toList(),
            isSpoiler = ((item["isSpoiler"] ?: data?.get("isSpoiler")) as? JsonPrimitive)?.booleanOrNull == true ||
                Regex("^\\s*\\[(spoiler|spoil)]|<spoiler\\b|class=[\"'][^\"']*spoiler", RegexOption.IGNORE_CASE).containsMatchIn(body),
        )
    }

    private fun descendants(comment: ChapterComment): Int = comment.replies.sumOf { 1 + descendants(it) }
    private fun timestamp(comment: ChapterComment): Long = runCatching { OffsetDateTime.parse(comment.createdAt).toInstant().toEpochMilli() }.getOrDefault(0L)
    private fun pinned(item: JsonObject?): Boolean = ((item?.get("isPinned") ?: item?.obj("data")?.get("pinned") ?: item?.obj("data")?.get("isPinned")) as? JsonPrimitive)?.booleanOrNull == true
    private fun response(body: String): JsonObject {
        val root = Json.parseToJsonElement(body) as? JsonObject ?: error("Respons Voratoon tidak valid")
        check((root.number("status") ?: 200) in 200..299) { root.text("message").ifBlank { "Gagal memuat komentar Voratoon" } }
        return root
    }
    private fun JsonObject.obj(key: String) = this[key] as? JsonObject
    private fun JsonObject.array(key: String) = (this[key] as? JsonArray).orEmpty()
    private fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
    private fun JsonObject.number(key: String) = (this[key] as? JsonPrimitive)?.intOrNull
    private companion object { const val PAGE_SIZE = 10 }
}

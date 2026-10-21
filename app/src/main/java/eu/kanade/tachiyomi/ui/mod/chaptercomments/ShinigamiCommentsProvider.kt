package eu.kanade.tachiyomi.ui.mod.chaptercomments

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl

class ShinigamiCommentsProvider(
    private val target: CommentTarget,
    private val transport: CommentsTransport,
) : ChapterCommentsProvider, ChapterReactionsProvider {
    override val reactionOptions = listOf(
        ChapterReactionOption(
            "reaction0",
            "👍",
            "Upvote",
            "https://cdn.jsdelivr.net/gh/shinigamiscans/static@1.0.0/emotes/2.webp",
        ),
        ChapterReactionOption(
            "reaction1",
            "❤️",
            "Suki",
            "https://cdn.jsdelivr.net/gh/shinigamiscans/static@1.0.0/emotes/3.webp",
        ),
        ChapterReactionOption(
            "reaction2",
            "😡",
            "Apsih",
            "https://cdn.jsdelivr.net/gh/shinigamiscans/static@1.0.0/emotes/4.webp",
        ),
    )
    private val path = target.url.toHttpUrl().pathSegments.let { segments ->
        val index = segments.indexOf("chapter")
        require(index >= 0 && segments.getOrNull(index + 1)?.isNotBlank() == true) {
            "Alamat chapter Shinigami tidak dikenali."
        }
        "chapter/${segments[index + 1]}"
    }

    override suspend fun chapterVoteIdentity(): String = "shinigami:${path.substringAfter("chapter/")}"

    override suspend fun setChapterUpvote(enabled: Boolean) =
        setChapterReaction(if (enabled) null else "reaction0", if (enabled) "reaction0" else null)

    override suspend fun setChapterReaction(previous: String?, next: String?) {
        require(previous == null || reactionOptions.any { it.key == previous })
        require(next == null || reactionOptions.any { it.key == next })
        if (previous == next) return
        if (previous != null) writeReaction(previous, decrement = true)
        if (next != null) {
            try {
                writeReaction(next, decrement = false)
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                if (previous != null) throw ChapterReactionUpdateException(null, exception)
                throw exception
            }
        }
    }

    private suspend fun writeReaction(key: String, decrement: Boolean) {
        val body = buildJsonObject {
            put("path", path)
            put("type", key)
            if (decrement) put("action", "desc")
        }.toString()
        val response = Json.parseToJsonElement(
            transport.write("POST", "https://commento.shngm.io/api/article?lang=en", body, target.url),
        ) as? JsonObject ?: error("Respons vote Shinigami tidak valid")
        val errno = (response["errno"] as? JsonPrimitive)?.intOrNull
        val success = (response["success"] as? JsonPrimitive)?.booleanOrNull
        val serverError = response["error"]?.takeUnless { it is JsonNull }
        check(errno == 0 && success != false && serverError == null) {
            response.string("errmsg").ifBlank { response.string("message").ifBlank { "Vote Shinigami gagal disimpan" } }
        }
    }

    override suspend fun comments(sort: CommentSort, page: Int): CommentsPage {
        val sortBy = when (sort) {
            CommentSort.POPULAR -> "like_desc"
            CommentSort.NEWEST -> "insertedAt_desc"
            CommentSort.OLDEST -> "insertedAt_asc"
        }
        val data = request(
            "comment",
            mapOf(
                "path" to path,
                "pageSize" to "10",
                "page" to page.toString(),
                "sortBy" to sortBy,
            ),
        ).jsonObject
        return CommentsPage(
            (data["data"] as? JsonArray).orEmpty().map { parseComment(it.jsonObject) },
            data.number("count"),
            page < data.number("totalPages"),
            totalKnown = (data["count"] as? JsonPrimitive)?.intOrNull != null,
        )
    }

    override suspend fun reactions(): List<ChapterReaction> {
        val data = request("article", mapOf("path" to path, "type" to "reaction0,reaction1,reaction2"))
            .jsonArray.firstOrNull()?.jsonObject ?: return emptyList()
        return listOf("Upvote", "Suki", "Apsih").mapIndexed { i, label ->
            ChapterReaction(label, data.number("reaction$i"))
        }
    }

    private suspend fun request(endpoint: String, params: Map<String, String>): JsonElement {
        val url = "https://commento.shngm.io/api/$endpoint".toHttpUrl().newBuilder().addQueryParameter("lang", "en")
        params.forEach { (name, value) -> url.addQueryParameter(name, value) }
        val response = Json.parseToJsonElement(transport.get(url.build().toString(), target.url)).jsonObject
        check(response.number("errno") == 0) { "Server komentar Shinigami sedang bermasalah." }
        return requireNotNull(response["data"])
    }

    private fun parseComment(value: JsonObject): ChapterComment {
        val replies = (value["children"] as? JsonArray).orEmpty().map { parseComment(it.jsonObject) }
        return ChapterComment(
            id = value.string("objectId"),
            author = value.string("nick").ifBlank { "Anonim" },
            content = value.string("comment"),
            createdAt = value.string("time"),
            avatar = value.string("avatar").takeIf { it.isNotBlank() },
            upvotes = value.number("like"),
            replyCount = replies.size,
            replies = replies,
            replyTo = (value["reply_user"] as? JsonObject)?.string("nick"),
        )
    }

    private fun JsonObject.string(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
    private fun JsonObject.number(key: String) = (get(key) as? JsonPrimitive)?.intOrNull ?: 0
}

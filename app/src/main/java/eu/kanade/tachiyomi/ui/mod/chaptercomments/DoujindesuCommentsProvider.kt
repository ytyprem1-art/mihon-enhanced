package eu.kanade.tachiyomi.ui.mod.chaptercomments

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.nodes.TextNode
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import kotlin.math.abs

/** Reader UUID / encrypted JSON engine used by the Doujin frontend; no reader images are needed. */
class DoujindesuCommentsProvider(
    private val target: CommentTarget,
    private val transport: CommentsTransport,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : ChapterCommentsProvider {
    private val chapterUrl = target.url.toHttpUrl()
    private val origin = "${chapterUrl.scheme}://${chapterUrl.host}"
    private val chapterId: String
        get() {
            val path = chapterUrl.pathSegments.filter { it.isNotBlank() }
            require(path.size == 2 && path[0] == "reader" && UUID.matches(path[1])) {
                "Alamat reader chapter tidak valid"
            }
            return path[1]
        }

    override suspend fun comments(sort: CommentSort, page: Int): CommentsPage {
        require(page >= 1)
        val order = when (sort) {
            CommentSort.POPULAR -> "Popular"
            CommentSort.NEWEST -> "Latest"
            CommentSort.OLDEST -> "Oldest"
        }
        val root = request("$origin/api/chapters/$chapterId/comments?sortBy=$order&page=$page&limit=15")
        check(root["comments"] is JsonArray) { "Respons komentar reader tidak valid" }
        val records = root.array("comments").mapNotNull { it as? JsonObject }
        return CommentsPage(
            tree(records),
            root.number("total") ?: records.size,
            root.flag("hasMore"),
            totalKnown =
            root.number("total") != null,
        )
    }

    override suspend fun reactions(): List<ChapterReaction> {
        val root = request("$origin/api/chapters/$chapterId/reactions")
        check(root["counts"] is JsonArray) { "Respons reaksi reader tidak valid" }
        return root.array("counts").mapNotNull { it as? JsonObject }.map {
            ChapterReaction(
                it.text("emoji").ifBlank { it.text("name").ifBlank { it.text("id") } },
                it.number("count") ?: 0,
            )
        }
    }

    override suspend fun replies(comment: ChapterComment): List<ChapterComment> {
        require(UUID.matches(comment.id)) { "Identitas balasan reader tidak valid" }
        val records = linkedMapOf<String, JsonObject>()
        var page = 1
        while (true) {
            val root = request("$origin/api/comments/${comment.id}/replies?page=$page&limit=10")
            check(root["replies"] is JsonArray) { "Respons balasan reader tidak valid" }
            val before = records.size
            root.array("replies").mapNotNull { it as? JsonObject }.forEach { records[it.text("id")] = it }
            if (!root.flag("hasMore")) break
            check(records.size > before) { "Situs mengulang halaman balasan. Coba lagi." }
            page++
        }
        return tree(records.values.toList()).map { if (it.replyTo == null) it.copy(replyTo = comment.id) else it }
    }

    private suspend fun request(url: String): JsonObject {
        currentCoroutineContext().ensureActive()
        val raw = transport.getWithHeaders(url, target.url, mapOf("x-app-secret" to APP_SECRET))
        val envelope = Json.parseToJsonElement(raw) as? JsonObject ?: error("Respons reader tidak valid")
        val root = envelope.text("_enc_resp_").takeIf { it.isNotBlank() }?.let(::decrypt) ?: envelope
        currentCoroutineContext().ensureActive()
        check(
            root.text("error") in listOf("", "false") && (root["success"] as? JsonPrimitive)?.booleanOrNull != false,
        ) {
            root.text("error").ifBlank { root.text("message").ifBlank { "Gagal memuat komentar reader" } }
        }
        return root
    }

    private fun decrypt(hex: String): JsonObject {
        require(
            hex.length % 2 == 0 && hex.all {
                it.digitToIntOrNull(16) != null
            },
        ) { "Data komentar terenkripsi tidak valid" }
        val hour = nowMillis() / 3_600_000L
        for (candidate in listOf(hour, hour - 1, hour + 1)) {
            val result = runCatching {
                val key = key(candidate)
                var rolling = 42
                val decoded = buildString(hex.length / 2) {
                    for (index in 0 until hex.length / 2) {
                        val value = hex.substring(index * 2, index * 2 + 2).toInt(16)
                        append(((value xor key[index % key.length].code xor (index * 13) xor rolling) and 255).toChar())
                        rolling = (rolling + value) % 256
                    }
                }
                // JS decodeURIComponent preserves plus signs; URLDecoder otherwise treats them as spaces.
                Json.parseToJsonElement(
                    URLDecoder.decode(decoded.replace("+", "%2B"), StandardCharsets.UTF_8.name()),
                ) as JsonObject
            }.getOrNull()
            if (result != null) return result
        }
        error("Tidak dapat membaca data komentar. Periksa waktu perangkat lalu coba lagi.")
    }

    private fun key(hour: Long): String {
        var hash = 0
        "${SALT}_$hour".forEach { hash = hash * 31 + it.code }
        var state = abs(hash.toLong()).takeIf { it != 0L } ?: 123456789L
        return buildString {
            repeat(32) {
                state = (state * 1664525L + 1013904223L) % 4294967296L
                append((33 + state % 93).toInt().toChar())
            }
        }
    }

    private fun tree(records: List<JsonObject>): List<ChapterComment> {
        val all = records.associateBy { it.text("id") }
        val parents = records.associate { item ->
            val parent = item.text("parent_id").takeIf { it in all }
                ?: item.text("root_id").takeIf { it in all && it != item.text("id") }
            item.text("id") to parent
        }.toMutableMap()
        parents.keys.forEach { id ->
            val seen = mutableSetOf(id)
            var ancestor = parents[id]
            while (ancestor != null && seen.add(ancestor)) ancestor = parents[ancestor]
            if (ancestor != null) parents[id] = null
        }
        fun build(id: String): ChapterComment {
            val item = all.getValue(id)
            val children = all.keys.filter { parents[it] == id }.map(::build)
            return parseComment(
                item,
            ).copy(replies = children, replyCount = maxOf(item.number("reply_count") ?: 0, children.size))
        }
        return all.keys.filter { parents[it] == null }.map(::build)
    }

    private fun parseComment(item: JsonObject): ChapterComment {
        val raw = item.text("content")
        val content = TextNode(raw).outerHtml()
            .replace(
                Regex("\\[b](.*?)\\[/b]", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)),
                "<b>$1</b>",
            )
            .replace(
                Regex("\\[i](.*?)\\[/i]", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)),
                "<i>$1</i>",
            )
            .replace(
                Regex("\\[s](.*?)\\[/s]", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)),
                "<s>$1</s>",
            )
            .replace(Regex("\\[/?spoiler]", RegexOption.IGNORE_CASE), "")
        return ChapterComment(
            id = item.text("id"),
            author = item.text("username").ifBlank { item.text("full_name").ifBlank { "Anonim" } },
            content = content,
            createdAt = item.text("created_at"),
            avatar = publicMediaUrl(item.text("avatar_url")),
            upvotes = item.number("likes_count") ?: 0,
            replyCount = item.number("reply_count") ?: 0,
            media = (
                listOf("image_url", "gif_url").mapNotNull { publicMediaUrl(item.text(it)) } +
                    Regex("!\\[[^]]*]\\((https?://[^\\s)]+)\\)").findAll(raw).mapNotNull {
                        publicMediaUrl(it.groupValues[1])
                    }.toList()
                ).distinct(),
            replyTo = item.text("reply_to_username").ifBlank { item.text("parent_id") }.takeIf { it.isNotBlank() },
            isSpoiler = Regex("\\[spoiler]", RegexOption.IGNORE_CASE).containsMatchIn(raw),
        )
    }

    private fun JsonObject.array(key: String) = (this[key] as? JsonArray).orEmpty()
    private fun publicMediaUrl(value: String): String? = value.takeIf { it.isNotBlank() }
        ?.let { "$origin/".toHttpUrl().resolve(it) }?.takeIf { it.scheme == "http" || it.scheme == "https" }?.toString()
    private fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
    private fun JsonObject.number(key: String) = (this[key] as? JsonPrimitive)?.intOrNull
    private fun JsonObject.flag(key: String) = (this[key] as? JsonPrimitive)?.booleanOrNull == true

    companion object {
        /** Recognizes the SPA shell on changed domains, or a retrieved engine bundle. */
        fun recognizes(html: String): Boolean =
            (html.contains("_enc_resp_") && html.contains("doujindesu_device_id")) ||
                (
                    html.contains("Doujindesu", ignoreCase = true) &&
                        Regex("(?:src|href)=[\"'][^\"']*/assets/index-[^\"']+\\.js").containsMatchIn(html)
                    )

        private val UUID = Regex("[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}")
        private const val APP_SECRET = "dfdf72051dbfdc7d76889ebd31324e74"
        private const val SALT = "doujindesu-scrapers-cannot-read-this-super-secret-salt-2026-v2"
    }
}

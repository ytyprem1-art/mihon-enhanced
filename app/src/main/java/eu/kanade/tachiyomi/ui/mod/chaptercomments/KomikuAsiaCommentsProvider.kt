package eu.kanade.tachiyomi.ui.mod.chaptercomments

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.Jsoup
import java.time.Instant

/** Public Komiku Asia v2 reads; unrelated to the Komiku.org HTMX template. */
class KomikuAsiaCommentsProvider(
    private val target: CommentTarget,
    private val transport: CommentsTransport,
) : ChapterCommentsProvider {
    private data class Identity(val comicId: Long, val chapter: String)
    private val identityMutex = Mutex()
    private val commentsMutex = Mutex()
    private var resolvedIdentity: Identity? = null
    private var loadedComments: List<JsonObject>? = null
    @Volatile
    private var canonicalDiscussionUrl: String? = null
    override val discussionUrl: String? get() = canonicalDiscussionUrl

    override suspend fun prepare(): ChapterCommentsProvider {
        identity()
        return this
    }

    private suspend fun identity(): Identity = identityMutex.withLock {
        resolvedIdentity ?: run {
            val storedRoute = Regex("^/([1-9][0-9]*)/([1-9][0-9]*)/?$").matchEntire(target.url.toHttpUrl().encodedPath)
            if (storedRoute != null) {
                val comicId = storedRoute.groupValues[1].toLong()
                val chapterId = storedRoute.groupValues[2].toLong()
                val url = apiUrl("/api/v2/comics/$comicId/chapters/id/$chapterId")
                val chapter = Json.parseToJsonElement(transport.get(url, target.url)) as? JsonObject
                    ?: error("Identitas chapter Komiku Asia tidak valid")
                check((chapter["id"] as? JsonPrimitive)?.longOrNull == chapterId &&
                    (chapter["comicId"] as? JsonPrimitive)?.longOrNull == comicId
                ) { "Identitas chapter Komiku Asia tidak cocok" }
                val number = chapter.text("n")?.takeIf { it.toDoubleOrNull()?.let { n -> n >= 0 && n.isFinite() } == true }
                    ?: error("Nomor chapter Komiku Asia tidak valid")
                // Browser URL is optional: comments still work if the separate slug lookup fails.
                try {
                    val comics = Json.parseToJsonElement(transport.get(apiUrl("/api/v2/comics?ids=$comicId&perPage=1"), target.url)) as? JsonObject
                    val comic = (comics?.get("items") as? JsonArray)?.mapNotNull { it as? JsonObject }
                        ?.firstOrNull { (it["id"] as? JsonPrimitive)?.longOrNull == comicId }
                    comic?.text("slug")?.takeIf { it.isNotBlank() }?.let { slug ->
                        canonicalDiscussionUrl = target.url.toHttpUrl().newBuilder().encodedPath("/").query(null).fragment(null)
                            .addPathSegment("read").addPathSegment("id").addPathSegment(slug)
                            .addPathSegment("ch$number-$chapterId").addPathSegment("").build().toString()
                    }
                } catch (exception: CancellationException) {
                    throw exception
                } catch (_: Exception) {
                    // Optional browser navigation metadata must not turn a valid thread into an error.
                }
                return@withLock Identity(comicId, number).also { resolvedIdentity = it }
            }
            val document = Jsoup.parse(transport.get(target.url, target.url))
            val flight = buildString {
                for (script in document.select("script")) {
                    val source = script.data()
                    if (!source.contains("self.__next_f.push([1,")) continue
                    val payload = source.substringAfter("self.__next_f.push(").substringBeforeLast(")")
                    val chunk = runCatching { Json.parseToJsonElement(payload) as? JsonArray }.getOrNull()
                    append((chunk?.getOrNull(1) as? JsonPrimitive)?.contentOrNull.orEmpty())
                }
            }
            val expectedId = Regex("ch[0-9.]+-([0-9]+)").find(target.url.toHttpUrl().encodedPath)?.groupValues?.get(1)
            fun findProps(element: JsonElement): JsonObject? {
                if (element is JsonObject) {
                    if (element["comic"] is JsonObject && element["chapter"] is JsonPrimitive &&
                        (expectedId == null || element.text("chapterId") == expectedId)
                    ) return element
                    return element.values.firstNotNullOfOrNull { findProps(it) }
                }
                return (element as? JsonArray)?.firstNotNullOfOrNull { findProps(it) }
            }
            val props = flight.lineSequence().mapNotNull { line ->
                runCatching { Json.parseToJsonElement(line.substringAfter(':')) }.getOrNull()?.let { findProps(it) }
            }.firstOrNull() ?: error("Identitas komentar Komiku Asia tidak ditemukan")
            val comicId = ((props["comic"] as JsonObject)["id"] as? JsonPrimitive)?.longOrNull?.takeIf { it > 0 }
                ?: error("Identitas komik tidak valid")
            val number = props.text("chapter")?.takeIf { it.toDoubleOrNull()?.let { n -> n >= 0 && n.isFinite() } == true }
                ?: error("Nomor chapter tidak valid")
            Identity(comicId, number).also { resolvedIdentity = it; canonicalDiscussionUrl = target.url }
        }
    }

    private fun apiUrl(path: String): String = target.url.toHttpUrl().resolve(path)?.toString()
        ?: error("Alamat API Komiku Asia tidak valid")

    private suspend fun endpoint(suffix: String): String {
        val identity = identity()
        return target.url.toHttpUrl().newBuilder()
            .encodedPath("/api/v2/comics/${identity.comicId}/chapters/${identity.chapter}/$suffix")
            .query(null).fragment(null).build().toString()
    }

    private suspend fun allComments(): List<JsonObject> = commentsMutex.withLock {
        loadedComments ?: run {
            val data = Json.parseToJsonElement(transport.get(endpoint("comments"), target.url)) as? JsonArray
                ?: error("Data komentar Komiku Asia tidak valid")
            data.mapNotNull { it as? JsonObject }.also { loadedComments = it }
        }
    }

    override suspend fun comments(sort: CommentSort, page: Int): CommentsPage {
        require(page >= 1)
        val comments = allComments()
        val sorted = when (sort) {
            CommentSort.POPULAR -> comments.sortedByDescending { it.number("up").toLong() - it.number("down") }
            CommentSort.NEWEST -> comments.sortedByDescending { it.time() }
            CommentSort.OLDEST -> comments.sortedBy { it.time() }
        }
        val start = ((page.toLong() - 1) * PAGE_SIZE).coerceAtMost(sorted.size.toLong()).toInt()
        val end = (start + PAGE_SIZE).coerceAtMost(sorted.size)
        val total = comments.sumOf { 1 + it.array("replies").size }
        return CommentsPage(sorted.subList(start, end).map { parseComment(it) }, total, end < sorted.size)
    }

    override suspend fun reactions(): List<ChapterReaction> {
        val data = Json.parseToJsonElement(transport.get(endpoint("reactions"), target.url)) as? JsonObject
            ?: error("Data reaksi Komiku Asia tidak valid")
        check(REACTIONS.any { (data[it] as? JsonPrimitive)?.intOrNull != null }) { "Data reaksi Komiku Asia tidak valid" }
        return REACTIONS.map { ChapterReaction(it, data.number(it)) }
    }

    private fun parseComment(comment: JsonObject, parent: String? = null): ChapterComment {
        val id = comment.text("id").orEmpty()
        val replies = comment.array("replies").mapNotNull { it as? JsonObject }
            .sortedWith(compareBy<JsonObject> { it.time() }.thenBy { it.text("id")?.toLongOrNull() ?: 0 })
            .map { parseComment(it, id) }
        return ChapterComment(
            id = id,
            author = comment.text("username") ?: comment.text("user") ?: "Pengguna",
            content = comment.text("text").orEmpty(),
            createdAt = runCatching { Instant.ofEpochMilli(comment.time()).toString() }.getOrDefault(""),
            avatar = publicUrl(comment.text("avatarUrl")),
            upvotes = comment.number("up"), downvotes = comment.number("down"),
            replies = replies, replyCount = replies.size, replyTo = parent,
            media = comment.array("media").mapNotNull { (it as? JsonObject)?.text("url") }
                .mapNotNull { publicUrl(it) }.distinct(),
        )
    }

    private fun publicUrl(value: String?): String? = value?.takeIf { it.isNotBlank() }
        ?.let { target.url.toHttpUrl().resolve(it) }?.toString()
    private fun JsonObject.text(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.number(key: String): Int = (get(key) as? JsonPrimitive)?.intOrNull?.coerceAtLeast(0) ?: 0
    private fun JsonObject.time(): Long = (get("at") as? JsonPrimitive)?.longOrNull ?: 0
    private fun JsonObject.array(key: String): JsonArray = get(key) as? JsonArray ?: JsonArray(emptyList())

    private companion object {
        const val PAGE_SIZE = 20
        val REACTIONS = listOf("upvote", "funny", "love", "surprised", "angry", "sad")
    }
}

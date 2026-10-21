package eu.kanade.tachiyomi.ui.mod.chaptercomments

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Resolve a chapter's official discussion identity without opening the manga reader. */
class MangaDexCommentsProvider(
    private val target: CommentTarget,
    private val transport: CommentsTransport,
) : ChapterCommentsProvider {
    override val supportedSorts = listOf(CommentSort.OLDEST)
    private val mutex = Mutex()
    private var resolved = false
    private var discussion: ChapterCommentsProvider? = null
    private val chapterId = target.url.toHttpUrlOrNull()?.pathSegments?.let {
        val index = it.indexOf("chapter")
        if (index >= 0) it.getOrNull(index + 1) else null
    }?.takeIf { UUID.matches(it) }?.lowercase()
        ?: target.url.takeIf { UUID.matches(it) }?.lowercase()
        ?: error("Identitas bab MangaDex tidak valid")

    override suspend fun prepare(): ChapterCommentsProvider = resolve() ?: this

    private suspend fun resolve(): ChapterCommentsProvider? = mutex.withLock {
        if (!resolved) {
            val root =
                Json.parseToJsonElement(
                    transport.get("https://api.mangadex.org/statistics/chapter/$chapterId", "https://mangadex.org/"),
                ) as? JsonObject
                    ?: error("Statistik MangaDex tidak valid")
            check((root["result"] as? JsonPrimitive)?.contentOrNull == "ok") { "Statistik MangaDex tidak tersedia" }
            val statistics = (root["statistics"] as? JsonObject)?.get(chapterId) as? JsonObject
                ?: error("Statistik bab MangaDex tidak ditemukan")
            check(statistics.containsKey("comments")) { "Identitas diskusi MangaDex tidak tersedia" }
            if (statistics["comments"] !is JsonNull) {
                val comments = statistics["comments"] as? JsonObject ?: error("Identitas diskusi MangaDex tidak valid")
                val id = (comments["threadId"] as? JsonPrimitive)?.longOrNull?.takeIf { it > 0 }
                    ?: error("Identitas diskusi MangaDex tidak valid")
                val count = (comments["repliesCount"] as? JsonPrimitive)?.intOrNull?.takeIf { it >= 0 }
                    ?: error("Jumlah komentar MangaDex tidak valid")
                discussion = XenForoCommentsProvider(
                    target.copy(url = "https://forums.mangadex.org/threads/$id"),
                    transport,
                    expectedTotal = count,
                    excludeChapterUrl = "https://mangadex.org/chapter/$chapterId",
                )
            }
            resolved = true
        }
        discussion
    }

    override suspend fun comments(sort: CommentSort, page: Int): CommentsPage {
        require(sort in supportedSorts)
        require(page >= 1)
        return resolve()?.comments(sort, page) ?: CommentsPage(emptyList(), 0, false)
    }

    override suspend fun reactions() = emptyList<ChapterReaction>()

    private companion object {
        val UUID = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
    }
}

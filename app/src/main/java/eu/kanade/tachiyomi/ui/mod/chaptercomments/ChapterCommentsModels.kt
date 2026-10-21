package eu.kanade.tachiyomi.ui.mod.chaptercomments

data class CommentTarget(val url: String, val chapterTitle: String, val sourceName: String, val sourceId: Long? = null)

enum class CommentSort(val label: String) { POPULAR("Populer"), NEWEST("Terbaru"), OLDEST("Terlama") }

data class ChapterComment(
    val id: String,
    val author: String,
    val content: String,
    val createdAt: String = "",
    val avatar: String? = null,
    val upvotes: Int = 0,
    val downvotes: Int = 0,
    val replyCount: Int = 0,
    val replies: List<ChapterComment> = emptyList(),
    val media: List<String> = emptyList(),
    val replyTo: String? = null,
    val isSpoiler: Boolean = false,
    val reactionCounts: List<ChapterReaction> = emptyList(),
    val voteCountsAvailable: Boolean = true,
)

data class ChapterReaction(val label: String, val count: Int)

data class CommentsPage(
    val comments: List<ChapterComment>,
    val total: Int,
    val hasMore: Boolean,
    /** False when total is only a page-local fallback, rather than the size of the thread. */
    val totalKnown: Boolean = true,
)

/** Providers expose public reads only. The caller owns one transport per open popup. */
interface ChapterCommentsProvider {
    val discussionUrl: String? get() = null
    val supportedSorts: List<CommentSort> get() = CommentSort.entries
    suspend fun prepare(): ChapterCommentsProvider = this
    suspend fun comments(sort: CommentSort, page: Int): CommentsPage
    suspend fun reactions(): List<ChapterReaction>
    suspend fun replies(comment: ChapterComment): List<ChapterComment> = comment.replies
}

interface CommentsTransport {
    suspend fun get(url: String, referer: String? = null): String
    suspend fun getWithHeaders(
        url: String,
        referer: String? = null,
        headers: Map<String, String>,
    ): String = get(url, referer)
    suspend fun write(
        method: String,
        url: String,
        json: String,
        referer: String? = null,
    ): String = error("Vote tidak tersedia.")
    suspend fun postReactionForm(
        url: String,
        values: Map<String, String>,
        referer: String,
    ): String = error("Reaksi formulir tidak tersedia.")
}

/** Implemented only when the site's public client exposes an anonymous chapter upvote. */
interface ChapterUpvoteProvider {
    suspend fun chapterVoteIdentity(): String
    suspend fun setChapterUpvote(enabled: Boolean)
}

data class ChapterReactionOption(
    val key: String,
    val emoji: String,
    val countLabel: String,
    val imageUrl: String? = null,
)

interface ChapterReactionsProvider : ChapterUpvoteProvider {
    val reactionOptions: List<ChapterReactionOption>
    suspend fun setChapterReaction(previous: String?, next: String?)
}

class ChapterReactionUpdateException(val confirmedSelection: String?, cause: Throwable) :
    Exception(cause.message ?: "Reaksi belum berhasil diganti.", cause)

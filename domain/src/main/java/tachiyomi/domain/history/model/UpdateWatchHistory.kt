package tachiyomi.domain.history.model

data class UpdateWatchHistory(
    val mangaId: Long,
    val timestamp: Long,
    val success: Boolean,
    val newChapters: Int,
    val category: FailureCategory,
    val detail: String?,
) {
    enum class FailureCategory {
        NONE,
        RATE_LIMITED,
        ACCESS_BLOCKED_OR_CLOUDFLARE,
        TRANSIENT_NETWORK,
        SOURCE_NOT_INSTALLED,
        SOURCE_OR_PARSING_ERROR,
        UNKNOWN,
    }
}

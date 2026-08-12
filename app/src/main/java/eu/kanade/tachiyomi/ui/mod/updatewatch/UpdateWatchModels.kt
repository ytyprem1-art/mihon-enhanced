package eu.kanade.tachiyomi.ui.mod.updatewatch

import androidx.compose.runtime.Immutable
import tachiyomi.domain.history.model.UpdateWatch
import tachiyomi.domain.history.model.UpdateWatchInboxItem
import tachiyomi.domain.history.model.UpdateWatchHistory
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.source.linked.model.LinkedSourceGroup

@Immutable
sealed interface UpdateWatchUiModel {
    data class Header(val title: String) : UpdateWatchUiModel
    data class Item(
        val group: LinkedSourceGroup?,
        val trackingManga: Manga,
        val latestChapter: Chapter,
        val daysSinceRelease: Long,
        val backgroundRefreshEnabled: Boolean,
        val expectedIntervalDays: Int,
        val refreshProfile: UpdateWatch.RefreshProfile,
        val lastBackgroundCheckAt: Long?,
        val refreshHistory: List<UpdateWatchHistory> = emptyList(),
        val isQueued: Boolean = false,
    ) : UpdateWatchUiModel
}

@Immutable
data class EnrichedUpdateWatchInboxItem(
    val item: UpdateWatchInboxItem,
    val manga: Manga?,
    val latestChapter: Chapter?,
)

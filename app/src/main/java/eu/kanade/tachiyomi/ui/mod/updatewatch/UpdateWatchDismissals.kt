package eu.kanade.tachiyomi.ui.mod.updatewatch

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.data.backup.models.BackupUpdateWatchInboxItem
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoNumber
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore

@Serializable
data class UpdateWatchDismissal(
    @ProtoNumber(1) val source: Long,
    @ProtoNumber(2) val url: String,
    @ProtoNumber(3) val chapterUrls: List<String> = emptyList(),
    @ProtoNumber(4) val throughChapterNumber: Double = 0.0,
    @ProtoNumber(5) val warningMilestone: Int = 0,
    @ProtoNumber(6) val warningFoundAt: Long = 0L,
) {
    fun excludes(url: String, number: Double): Boolean = url in chapterUrls ||
        (throughChapterNumber > 0 && number > 0 && number <= throughChapterNumber)
}

object UpdateWatchDismissals {
    fun merge(first: List<UpdateWatchDismissal>, second: List<UpdateWatchDismissal>): List<UpdateWatchDismissal> =
        (first + second).groupBy { it.source to it.url }.values.map { records ->
            records.first().copy(
                chapterUrls = records.flatMap { it.chapterUrls }.distinct().sorted(),
                throughChapterNumber = records.maxOf { it.throughChapterNumber },
                warningMilestone = records.maxBy { it.warningFoundAt }.warningMilestone,
                warningFoundAt = records.maxOf { it.warningFoundAt },
            )
        }.sortedWith(compareBy({ it.source }, { it.url }))

    fun filterBackupInbox(
        items: List<BackupUpdateWatchInboxItem>, dismissals: List<UpdateWatchDismissal>,
    ): List<BackupUpdateWatchInboxItem> {
        val byManga = dismissals.associateBy { it.source to it.url }
        return items.filter { item ->
            val dismissed = byManga[item.member.source to item.member.url]
            when {
                dismissed == null -> true
                item.type == 1 -> item.lastFoundAt > dismissed.warningFoundAt
                else -> !dismissed.excludes(item.latestChapterUrl, item.latestChapterNumber)
            }
        }
    }
}

@Inject
@SingleIn(AppScope::class)
class UpdateWatchDismissalStore(preferences: PreferenceStore) {
    private val preference = preferences.getStringSet(Preference.appStateKey("update_watch_dismissals_v1"))

    fun snapshot(): List<UpdateWatchDismissal> = preference.get().map {
        Json.decodeFromString(UpdateWatchDismissal.serializer(), it)
    }

    @Synchronized
    fun restore(records: List<UpdateWatchDismissal>) {
        if (records.isEmpty()) return
        val merged = UpdateWatchDismissals.merge(snapshot(), records)
        preference.set(merged.map { Json.encodeToString(UpdateWatchDismissal.serializer(), it) }.toSet())
    }
}

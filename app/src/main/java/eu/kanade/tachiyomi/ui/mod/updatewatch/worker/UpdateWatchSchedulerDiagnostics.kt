package eu.kanade.tachiyomi.ui.mod.updatewatch.worker

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import java.util.UUID
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

@Serializable
data class UpdateWatchSchedulerDiagnostic(
    val id: String = UUID.randomUUID().toString(),
    val type: RunType,
    val eventName: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val scheduledAt: Long? = null,
    val startedAt: Long? = null,
    val completedAt: Long? = null,
    val eligibleCount: Int = 0,
    val selectedCount: Int = 0,
    val refreshedCount: Int = 0,
    val updatedCount: Int = 0,
    val noUpdateCount: Int = 0,
    val failedCount: Int = 0,
    val sourceCount: Int = 0,
    val earliestNextEligibleManga: String? = null,
    val earliestNextEligibleAt: Long? = null,
    val wallClockBaseSlot: Long? = null,
    val nextWorkerTargetAt: Long? = null,
    val proposedTargetAt: Long? = null,
    val proposedBaseSlotAt: Long? = null,
    val proposedMarginMinutes: Int = 0,
    val isRecoveryRun: Boolean = false,
    val safetyMarginMinutes: Int = 0,
    val runOrigin: String? = null,
    val timezone: String? = null,
    val mangaDetails: List<MangaDiagnosticDetail> = emptyList()
) {
    enum class RunType { WORKER_RUN, SCHEDULER_EVENT, FOREGROUND_HOT_BURST }
}

@Serializable
data class MangaDiagnosticDetail(
    val mangaId: Long,
    val title: String,
    val sourceName: String,
    val result: String,
    val errorReason: String? = null,
    val checkedAt: Long,
    val lastCheckAt: Long?,
    val nextEligibleAt: Long?
)

@dev.zacsweers.metro.Inject
@dev.zacsweers.metro.SingleIn(dev.zacsweers.metro.AppScope::class)
class UpdateWatchDiagnosticsPreferences(val preferenceStore: PreferenceStore) {
    val diagnosticsJson: Preference<String> = preferenceStore.getString(
        Preference.appStateKey("update_watch_scheduler_diagnostics"),
        "[]"
    )
    val lastEarliestEligibleAt: Preference<Long> = preferenceStore.getLong(
        Preference.appStateKey("update_watch_last_earliest_eligible"),
        0L
    )
    val lastScheduledAt: Preference<Long> = preferenceStore.getLong(
        Preference.appStateKey("update_watch_last_scheduled_at"),
        0L
    )
    val lastMarginMinutes: Preference<Int> = preferenceStore.getInt(
        Preference.appStateKey("update_watch_last_margin_min"),
        0
    )
}

object UpdateWatchDiagnosticsManager {
    private val json = Json { ignoreUnknownKeys = true }
    private val prefs: UpdateWatchDiagnosticsPreferences by lazy { Injekt.get() }

    fun formatTimestamp(timestamp: Long?): String {
        if (timestamp == null || timestamp <= 0) return "N/A"
        return try {
            val formatter = java.time.format.DateTimeFormatter.ofLocalizedDateTime(java.time.format.FormatStyle.SHORT)
                .withZone(java.time.ZoneId.systemDefault())
            formatter.format(java.time.Instant.ofEpochMilli(timestamp))
        } catch (e: Exception) {
            timestamp.toString()
        }
    }

    fun logEvent(eventName: String, scheduledAt: Long? = null) {
        log(UpdateWatchSchedulerDiagnostic(
            type = UpdateWatchSchedulerDiagnostic.RunType.SCHEDULER_EVENT,
            eventName = eventName,
            scheduledAt = scheduledAt
        ))
        logcat(LogPriority.INFO) { "[UpdateWatchScheduler] Event: $eventName" }
    }

    fun logRun(run: UpdateWatchSchedulerDiagnostic) {
        log(run)
        logcat(LogPriority.INFO) { "[UpdateWatchScheduler] Worker Run: ${run.refreshedCount} refreshed, ${run.updatedCount} updates" }
    }

    fun log(entry: UpdateWatchSchedulerDiagnostic) {
        synchronized(this) {
            try {
                val current = getDiagnostics()
                val updated = (listOf(entry) + current).take(100)
                prefs.diagnosticsJson.set(json.encodeToString(updated))
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "Failed to log scheduler diagnostic" }
            }
        }
    }

    fun getDiagnostics(): List<UpdateWatchSchedulerDiagnostic> {
        return try {
            json.decodeFromString(prefs.diagnosticsJson.get())
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun clear() {
        prefs.diagnosticsJson.delete()
    }

    fun deleteSelected(ids: Set<String>) {
        synchronized(this) {
            try {
                val current = getDiagnostics()
                val updated = current.filter { it.id !in ids }
                prefs.diagnosticsJson.set(json.encodeToString(updated))
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "Failed to delete selected diagnostics" }
            }
        }
    }
}

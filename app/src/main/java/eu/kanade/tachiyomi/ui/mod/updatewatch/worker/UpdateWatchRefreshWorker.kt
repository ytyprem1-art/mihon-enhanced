package eu.kanade.tachiyomi.ui.mod.updatewatch.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import eu.kanade.tachiyomi.network.HttpException
import eu.kanade.tachiyomi.ui.mod.updatewatch.helper.UpdateWatchRefreshHelper
import eu.kanade.tachiyomi.ui.mod.util.dateUploadMillis
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import logcat.LogPriority
import mihon.domain.source.interactor.UpdateMangaFromRemote
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.history.interactor.ManageUpdateWatch
import tachiyomi.domain.history.interactor.ManageUpdateWatchHistory
import tachiyomi.domain.history.interactor.ManageUpdateWatchInbox
import tachiyomi.domain.history.model.UpdateWatch
import tachiyomi.domain.history.model.UpdateWatchHistory
import tachiyomi.domain.history.model.UpdateWatchInboxItem
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

class UpdateWatchRefreshWorker(context: Context, workerParams: WorkerParameters) :
    CoroutineWorker(context, workerParams) {

    private val manageUpdateWatch: ManageUpdateWatch = Injekt.get()
    private val manageUpdateWatchHistory: ManageUpdateWatchHistory = Injekt.get()
    private val getManga: GetManga = Injekt.get()
    private val chapterRepository: ChapterRepository = Injekt.get()
    private val sourceManager: SourceManager = Injekt.get()
    private val updateMangaFromRemote: UpdateMangaFromRemote = Injekt.get()
    private val manageUpdateWatchInbox: ManageUpdateWatchInbox = Injekt.get()

    private val simulationTriggered = AtomicBoolean(false)
    private val newlyFoundInboxItems = mutableListOf<UpdateWatchInboxItem>()
    private val mangaDiagnosticDetails = mutableListOf<MangaDiagnosticDetail>()

    override suspend fun doWork(): Result = withContext(kotlinx.coroutines.Dispatchers.IO) {
        UpdateWatchRefreshState.onRunStarted()
        val startTime = System.currentTimeMillis()
        val scheduledAt = inputData.getLong(UpdateWatchRefreshScheduler.KEY_SCHEDULED_AT, 0L).takeIf { it > 0 }
        val runOrigin =
            inputData.getString(UpdateWatchRefreshScheduler.KEY_RUN_ORIGIN)
                ?: UpdateWatchRefreshScheduler.ORIGIN_SCHEDULED
        var claimedIds: Set<Long> = emptySet()

        try {
            val simulationMode = if (eu.kanade.tachiyomi.BuildConfig.DEBUG) {
                inputData.getInt(KEY_SIMULATION_MODE, SIM_NONE)
            } else {
                SIM_NONE
            }
            simulationTriggered.set(false)
            newlyFoundInboxItems.clear()
            mangaDiagnosticDetails.clear()

            val trackedManga: List<UpdateWatch> = manageUpdateWatch.subscribeAll().first()
            val allCandidates = mutableListOf<RefreshCandidate>()

            trackedManga.filter { it.backgroundRefreshEnabled && !it.isPaused }.forEach { tracking ->
                val manga = getManga.await(tracking.mangaId) ?: return@forEach
                val chapters = chapterRepository.getChapterByMangaId(manga.id)
                val latestChapter =
                    chapters.filter { it.dateUploadMillis > 0 }.maxByOrNull { it.dateUploadMillis } ?: return@forEach

                val eligibility = UpdateWatchRefreshHelper.getEligibility(
                    enabled = tracking.backgroundRefreshEnabled,
                    expectedIntervalDays = tracking.expectedIntervalDays,
                    refreshProfile = tracking.refreshProfile,
                    latestChapterUploadDate = latestChapter.dateUploadMillis,
                    lastCheckAt = tracking.lastBackgroundCheckAt,
                    now = startTime,
                )

                if (eligibility.status == UpdateWatchRefreshHelper.RefreshStatus.ACTIVE) {
                    val nextEligible = eligibility.nextEligibleAt ?: 0L
                    val now = System.currentTimeMillis()

                    if (now >= nextEligible) {
                        allCandidates.add(
                            RefreshCandidate(
                                mangaId = manga.id,
                                title = manga.title,
                                sourceId = manga.source,
                                ageDays = eligibility.ageDays,
                                expectedIntervalDays = tracking.expectedIntervalDays,
                                refreshProfile = tracking.refreshProfile.name,
                                bucket = eligibility.bucket,
                                plannedCadence = eligibility.plannedCadenceLabel ?: "Unknown",
                                lastCheckAt = tracking.lastBackgroundCheckAt ?: 0L,
                            ),
                        )
                    }
                }

                // CHECK FOR INACTIVITY WARNING MILESTONES
                if (tracking.backgroundRefreshEnabled && eligibility.isStale) {
                    val daysOver = eligibility.ageDays - tracking.expectedIntervalDays
                    val milestone = ((daysOver / 28) * 28).toInt()
                    if (milestone >= 28 && milestone > tracking.lastWarnedMilestone) {
                        manageUpdateWatchInbox.insertOrMerge(
                            UpdateWatchInboxItem(
                                mangaId = manga.id,
                                mangaTitle = manga.title,
                                sourceId = manga.source,
                                sourceName = sourceManager.getOrStub(manga.source).name,
                                chapterCount = 0,
                                chapterRange = "",
                                firstFoundAt = System.currentTimeMillis(),
                                lastFoundAt = System.currentTimeMillis(),
                                latestChapterId = 0,
                                latestChapterNumber = 0.0,
                                chapterIds = emptyList(),
                                type = UpdateWatchInboxItem.TYPE_INACTIVITY_WARNING,
                                milestone = milestone,
                            ),
                        )
                        manageUpdateWatch.updateStaleMilestone(manga.id, milestone)
                    }
                }
            }

            if (allCandidates.isEmpty()) {
                logcat(LogPriority.INFO) { "Background refresh: No eligible candidates." }
                val finalTarget = UpdateWatchRefreshScheduler.setupTaskSuspend(applicationContext, skipRunCheck = true)
                recordRunDiagnostics(
                    allCandidates, trackedManga, processedCount = 0, updatedCount = 0,
                    failedCount = 0, startTime, scheduledAt, sourceCount = 0, finalTarget, runOrigin,
                )
                return@withContext Result.success()
            }

            logcat(LogPriority.INFO) {
                "Background refresh: Total potential eligible candidates " +
                    "found: ${allCandidates.size}"
            }

            val workBySource = allCandidates.groupBy { it.sourceId }
                .mapValues { (sourceId, list) ->
                    val selected = mutableListOf<RefreshCandidate>()
                    val hotEligible =
                        list.filter {
                            it.bucket == UpdateWatchRefreshHelper.PriorityBucket.HOT
                        }.sortedBy { it.lastCheckAt }
                    val warmEligible =
                        list.filter {
                            it.bucket == UpdateWatchRefreshHelper.PriorityBucket.WARM
                        }.sortedBy { it.lastCheckAt }
                    val coldEligible =
                        list.filter {
                            it.bucket == UpdateWatchRefreshHelper.PriorityBucket.COLD
                        }.sortedBy { it.lastCheckAt }
                    val staleEligible =
                        list.filter {
                            it.bucket == UpdateWatchRefreshHelper.PriorityBucket.STALE
                        }.sortedBy { it.lastCheckAt }

                    selected.addAll(hotEligible.take(UpdateWatchRefreshHelper.CAP_HOT))
                    val remainingTotal = UpdateWatchRefreshHelper.CAP_TOTAL - selected.size
                    if (remainingTotal > 0) {
                        selected.addAll(warmEligible.take(minOf(UpdateWatchRefreshHelper.CAP_WARM, remainingTotal)))
                    }
                    val remainingTotal2 = UpdateWatchRefreshHelper.CAP_TOTAL - selected.size
                    if (remainingTotal2 > 0) {
                        selected.addAll(coldEligible.take(minOf(UpdateWatchRefreshHelper.CAP_COLD, remainingTotal2)))
                    }
                    val remainingTotal3 = UpdateWatchRefreshHelper.CAP_TOTAL - selected.size
                    if (remainingTotal3 > 0) {
                        selected.addAll(staleEligible.take(minOf(UpdateWatchRefreshHelper.CAP_STALE, remainingTotal3)))
                    }
                    selected
                }

            val allSelectedIds = workBySource.values.flatten().map { it.mangaId }.toSet()
            claimedIds = UpdateWatchRefreshState.claim(allSelectedIds)

            if (allSelectedIds.isNotEmpty() && claimedIds.isEmpty()) {
                // CLAIM CONTENTION
                if (UpdateWatchRefreshState.getActiveRunnerCount() > 1) {
                    UpdateWatchDiagnosticsManager.logEvent(
                        "Worker skipped reschedule: all ${allSelectedIds.size} " +
                            "eligible candidates are already claimed by another " +
                            "active run.\nActive claims: ${UpdateWatchRefreshState.queuedMangaIds.value.size}",
                    )
                    recordRunDiagnostics(
                        allCandidates, trackedManga, processedCount = 0, updatedCount = 0,
                        failedCount = 0, startTime, scheduledAt, sourceCount = 0, finalTarget = null, runOrigin,
                    )
                    return@withContext Result.success()
                } else {
                    // STALE CLAIMS FALLBACK
                    UpdateWatchRefreshState.clear()
                    claimedIds = UpdateWatchRefreshState.claim(allSelectedIds)
                }
            }

            val sortedSourceQueues = workBySource.toList()
                .map { (sourceId, queue) ->
                    sourceId to queue.filter { it.mangaId in claimedIds }
                }
                .filter { it.second.isNotEmpty() }
                .sortedBy { (_, queue) -> queue.first().lastCheckAt }

            val processedCount = AtomicInteger(0)
            val updatedCount = AtomicInteger(0)
            val failedCount = AtomicInteger(0)

            val sourceSemaphore = Semaphore(UpdateWatchRefreshHelper.GLOBAL_CONCURRENCY)
            val sourcesStartedCount = AtomicInteger(0)

            coroutineScope {
                sortedSourceQueues.map { (sourceId, sourceQueue) ->
                    async {
                        sourceSemaphore.withPermit {
                            try {
                                val startedIndex = sourcesStartedCount.getAndIncrement()
                                if (startedIndex > 0) {
                                    val staggerMillis = if (eu.kanade.tachiyomi.BuildConfig.DEBUG) {
                                        Random.nextLong(
                                            UpdateWatchRefreshHelper.STAGGER_DEBUG_MIN_MS,
                                            UpdateWatchRefreshHelper.STAGGER_DEBUG_MAX_MS + 1,
                                        )
                                    } else {
                                        Random.nextLong(
                                            UpdateWatchRefreshHelper.STAGGER_RELEASE_MIN_MS,
                                            UpdateWatchRefreshHelper.STAGGER_RELEASE_MAX_MS + 1,
                                        )
                                    }
                                    delay(staggerMillis)
                                }

                                for (index in sourceQueue.indices) {
                                    val candidate = sourceQueue[index]
                                    if (index > 0) {
                                        val delayMillis = if (eu.kanade.tachiyomi.BuildConfig.DEBUG) {
                                            Random.nextLong(5, 11) * 1000
                                        } else {
                                            Random.nextLong(120, 301) * 1000
                                        }
                                        delay(delayMillis)
                                    }

                                    val status = processCandidate(candidate, simulationMode)
                                    processedCount.incrementAndGet()

                                    when (status) {
                                        RefreshStatus.UPDATED -> updatedCount.incrementAndGet()
                                        RefreshStatus.FAILED_ORDINARY, RefreshStatus.FAILED_TRANSIENT ->
                                            failedCount.incrementAndGet()
                                        RefreshStatus.FAILED_RATE_LIMITED -> {
                                            failedCount.incrementAndGet()
                                            return@withPermit
                                        }
                                        RefreshStatus.FAILED_BLOCKED -> {
                                            failedCount.incrementAndGet()
                                            return@withPermit
                                        }
                                        else -> {}
                                    }
                                }
                            } finally {
                            }
                        }
                    }
                }.awaitAll()
            }

            UpdateWatchPostRefreshHandler.showNotificationIfEnabled(applicationContext, newlyFoundInboxItems)

            val finalTarget = UpdateWatchRefreshScheduler.setupTaskSuspend(applicationContext, skipRunCheck = true)
            recordRunDiagnostics(
                allCandidates, trackedManga, processedCount.get(), updatedCount.get(),
                failedCount.get(), startTime, scheduledAt, workBySource.size, finalTarget, runOrigin,
            )

            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
            UpdateWatchRefreshScheduler.setupTask(applicationContext, skipRunCheck = true)
            Result.failure()
        } finally {
            UpdateWatchRefreshState.onRunFinished()
            UpdateWatchRefreshState.release(claimedIds)
        }
    }

    private suspend fun processCandidate(
        candidate: RefreshCandidate,
        simulationMode: Int = SIM_NONE,
    ): RefreshStatus {
        val manga = getManga.await(candidate.mangaId) ?: return RefreshStatus.SKIPPED
        val source = sourceManager.getOrStub(manga.source)
        val startTime = System.currentTimeMillis()

        manageUpdateWatch.updateLastBackgroundCheckAt(candidate.mangaId, startTime)

        if (simulationMode != SIM_NONE && !simulationTriggered.getAndSet(true)) {
            val simulatedException = when (simulationMode) {
                SIM_HTTP_429 -> HttpException(429)
                SIM_HTTP_403 -> HttpException(403)
                SIM_TRANSIENT -> java.net.SocketTimeoutException("Simulated timeout")
                SIM_ORDINARY -> Exception("Simulated ordinary failure")
                else -> null
            }

            if (simulatedException != null) {
                val type = classifyFailure(simulatedException)
                val status = when (type) {
                    FailureType.RATE_LIMITED -> {
                        recordHistory(
                            candidate,
                            false,
                            0,
                            UpdateWatchHistory.FailureCategory.RATE_LIMITED,
                            "Simulated 429",
                        )
                        RefreshStatus.FAILED_RATE_LIMITED
                    }
                    FailureType.BLOCKED -> {
                        recordHistory(
                            candidate,
                            false,
                            0,
                            UpdateWatchHistory.FailureCategory.ACCESS_BLOCKED_OR_CLOUDFLARE,
                            "Simulated 403",
                        )
                        RefreshStatus.FAILED_BLOCKED
                    }
                    FailureType.TRANSIENT -> {
                        recordHistory(
                            candidate,
                            false,
                            0,
                            UpdateWatchHistory.FailureCategory.TRANSIENT_NETWORK,
                            "Simulated timeout",
                        )
                        RefreshStatus.FAILED_TRANSIENT
                    }
                    FailureType.ORDINARY -> {
                        recordHistory(
                            candidate,
                            false,
                            0,
                            UpdateWatchHistory.FailureCategory.UNKNOWN,
                            "Simulated error",
                        )
                        RefreshStatus.FAILED_ORDINARY
                    }
                }
                recordMangaDiagnostic(candidate, source.name, status.name, simulatedException.message)
                return status
            }
        }

        return try {
            val oldChapters = chapterRepository.getChapterByMangaId(manga.id)
            val oldLatest = oldChapters.filter { it.dateUploadMillis > 0 }.maxByOrNull { it.dateUploadMillis }

            val refreshResult = updateMangaFromRemote(manga, fetchChapters = true)

            if (refreshResult.isSuccess) {
                val newChapters = refreshResult.getOrThrow().newChapters
                val currentChapters = chapterRepository.getChapterByMangaId(manga.id)
                val newLatest = currentChapters.filter { it.dateUploadMillis > 0 }.maxByOrNull { it.dateUploadMillis }

                UpdateWatchPostRefreshHandler.handleRefreshResult(
                    manga = manga,
                    oldLatestChapter = oldLatest,
                    newChapters = newChapters,
                    currentLatestChapter = newLatest,
                    startTime = startTime,
                    onInboxItemCreated = { synchronized(newlyFoundInboxItems) { newlyFoundInboxItems.add(it) } },
                )

                recordHistory(
                    candidate,
                    true,
                    if (newChapters.isNotEmpty()) newChapters.size else 0,
                    UpdateWatchHistory.FailureCategory.NONE,
                    null,
                )
                val finalStatus = if (newChapters.isNotEmpty() || (
                        newLatest != null &&
                            newLatest.id != oldLatest?.id
                        )
                ) {
                    RefreshStatus.UPDATED
                } else {
                    RefreshStatus.SUCCESS
                }
                recordMangaDiagnostic(
                    candidate,
                    source.name,
                    if (finalStatus == RefreshStatus.UPDATED) "Update found" else "No update",
                    null,
                )
                finalStatus
            } else {
                val exception = refreshResult.exceptionOrNull()
                val type = classifyFailure(exception)
                val error = exception?.message ?: "Unknown error"
                val (category, safeDetail) = mapFailureToHistory(type, exception)
                recordHistory(candidate, false, 0, category, safeDetail)
                val finalStatus = when (type) {
                    FailureType.RATE_LIMITED -> RefreshStatus.FAILED_RATE_LIMITED
                    FailureType.BLOCKED -> RefreshStatus.FAILED_BLOCKED
                    FailureType.TRANSIENT -> RefreshStatus.FAILED_TRANSIENT
                    FailureType.ORDINARY -> RefreshStatus.FAILED_ORDINARY
                }
                recordMangaDiagnostic(candidate, source.name, finalStatus.name, error)
                finalStatus
            }
        } catch (e: Exception) {
            recordHistory(candidate, false, 0, UpdateWatchHistory.FailureCategory.UNKNOWN, e.message?.take(100))
            recordMangaDiagnostic(candidate, source.name, "EXCEPTION", e.message)
            RefreshStatus.FAILED_ORDINARY
        }
    }

    private suspend fun recordMangaDiagnostic(
        candidate: RefreshCandidate,
        sourceName: String,
        result: String,
        error: String?,
    ) {
        try {
            val releaseDate = chapterRepository.getChapterByMangaId(candidate.mangaId)
                .filter { it.dateUploadMillis > 0 }.maxByOrNull { it.dateUploadMillis }?.dateUploadMillis ?: 0L

            val eligibility = UpdateWatchRefreshHelper.getEligibility(
                enabled = true,
                expectedIntervalDays = candidate.expectedIntervalDays,
                refreshProfile = UpdateWatch.RefreshProfile.valueOf(candidate.refreshProfile),
                latestChapterUploadDate = releaseDate,
                lastCheckAt = System.currentTimeMillis(),
            )

            synchronized(mangaDiagnosticDetails) {
                mangaDiagnosticDetails.add(
                    MangaDiagnosticDetail(
                        mangaId = candidate.mangaId,
                        title = candidate.title,
                        sourceName = sourceName,
                        result = result,
                        errorReason = error?.take(100),
                        checkedAt = System.currentTimeMillis(),
                        lastCheckAt = candidate.lastCheckAt,
                        nextEligibleAt = eligibility.nextEligibleAt,
                    ),
                )
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Failed to record manga diagnostic" }
        }
    }

    private suspend fun recordRunDiagnostics(
        allCandidates: List<RefreshCandidate>,
        trackedManga: List<UpdateWatch>,
        processedCount: Int,
        updatedCount: Int,
        failedCount: Int,
        startTime: Long,
        scheduledAt: Long?,
        sourceCount: Int,
        finalTarget: Long?,
        runOrigin: String,
    ) {
        try {
            val completionTime = System.currentTimeMillis()
            val latestChapterDates = trackedManga.associate { tracking ->
                val chapters = chapterRepository.getChapterByMangaId(tracking.mangaId)
                val latest = chapters.filter { it.dateUploadMillis > 0 }.maxByOrNull { it.dateUploadMillis }
                tracking.mangaId to (latest?.dateUploadMillis ?: 0L)
            }
            val earliestNext = UpdateWatchRefreshHelper.getEarliestNextEligibleAt(trackedManga, latestChapterDates)
            val marginMillis = Random.nextLong(5, 9) * 60 * 1000L
            val proposedTarget = UpdateWatchRefreshHelper.calculateRescheduleDelay(
                earliestNext,
                completionTime,
                marginMillis,
            )?.let { completionTime + it }

            UpdateWatchDiagnosticsManager.logRun(
                UpdateWatchSchedulerDiagnostic(
                    type = UpdateWatchSchedulerDiagnostic.RunType.WORKER_RUN,
                    scheduledAt = scheduledAt,
                    startedAt = startTime,
                    completedAt = completionTime,
                    eligibleCount = allCandidates.size,
                    selectedCount = processedCount,
                    refreshedCount = processedCount,
                    updatedCount = updatedCount,
                    noUpdateCount = processedCount - updatedCount - failedCount,
                    failedCount = failedCount,
                    sourceCount = sourceCount,
                    earliestNextEligibleAt = earliestNext,
                    proposedTargetAt = proposedTarget,
                    proposedBaseSlotAt = earliestNext,
                    proposedMarginMinutes = (marginMillis / (60 * 1000)).toInt(),
                    nextWorkerTargetAt = finalTarget,
                    mangaDetails = mangaDiagnosticDetails.toList(),
                    runOrigin = runOrigin,
                ),
            )
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Failed to record run diagnostics" }
        }
    }

    private suspend fun recordHistory(
        candidate: RefreshCandidate,
        success: Boolean,
        newChapters: Int,
        category: UpdateWatchHistory.FailureCategory,
        detail: String?,
    ) {
        try {
            manageUpdateWatchHistory.insert(
                UpdateWatchHistory(
                    candidate.mangaId,
                    System.currentTimeMillis(),
                    success,
                    newChapters,
                    category,
                    detail?.take(150),
                ),
            )
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Failed to record refresh history" }
        }
    }

    private fun mapFailureToHistory(
        type: FailureType,
        e: Throwable?,
    ): Pair<UpdateWatchHistory.FailureCategory, String?> {
        val category = when (type) {
            FailureType.RATE_LIMITED -> UpdateWatchHistory.FailureCategory.RATE_LIMITED
            FailureType.BLOCKED -> UpdateWatchHistory.FailureCategory.ACCESS_BLOCKED_OR_CLOUDFLARE
            FailureType.TRANSIENT -> UpdateWatchHistory.FailureCategory.TRANSIENT_NETWORK
            FailureType.ORDINARY -> {
                val msg = e?.message ?: ""
                if (msg.contains("Source not installed", ignoreCase = true)) {
                    UpdateWatchHistory.FailureCategory.SOURCE_NOT_INSTALLED
                } else {
                    UpdateWatchHistory.FailureCategory.SOURCE_OR_PARSING_ERROR
                }
            }
        }
        val safeDetail = e?.message?.replace(Regex("[?&][^= ]+=[^& ]+"), "")?.take(150)
        return category to safeDetail
    }

    private fun classifyFailure(e: Throwable?): FailureType {
        if (e == null) return FailureType.ORDINARY
        if (e is HttpException) {
            return when (e.code) {
                429 -> FailureType.RATE_LIMITED
                403 -> FailureType.BLOCKED
                in 500..599 -> FailureType.TRANSIENT
                else -> FailureType.ORDINARY
            }
        }
        val message = e.message ?: ""
        if (message.contains("Cloudflare", ignoreCase = true) || message.contains(
                "Access denied",
                ignoreCase = true,
            )
        ) {
            return FailureType.BLOCKED
        }
        if (e is java.net.SocketTimeoutException || e is java.net.ConnectException ||
            e is java.net.UnknownHostException || e is java.io.InterruptedIOException
        ) {
            return FailureType.TRANSIENT
        }
        return FailureType.ORDINARY
    }

    private enum class RefreshStatus {
        SUCCESS,
        UPDATED,
        FAILED_ORDINARY,
        FAILED_TRANSIENT,
        FAILED_RATE_LIMITED,
        FAILED_BLOCKED,
        SKIPPED,
    }
    private enum class FailureType { RATE_LIMITED, BLOCKED, TRANSIENT, ORDINARY }
    private data class RefreshCandidate(
        val mangaId: Long,
        val title: String,
        val sourceId: Long,
        val ageDays: Long,
        val expectedIntervalDays: Int,
        val refreshProfile: String,
        val bucket: UpdateWatchRefreshHelper.PriorityBucket,
        val plannedCadence: String,
        val lastCheckAt: Long,
    )

    companion object {
        const val KEY_SIMULATION_MODE = "simulation_mode"
        const val SIM_NONE = 0
        const val SIM_HTTP_429 = 1
        const val SIM_HTTP_403 = 2
        const val SIM_TRANSIENT = 3
        const val SIM_ORDINARY = 4
    }
}

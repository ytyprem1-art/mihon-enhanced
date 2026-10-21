package eu.kanade.tachiyomi.ui.mod.updatewatch.worker

import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import eu.kanade.tachiyomi.ui.mod.updatewatch.helper.UpdateWatchRefreshHelper
import eu.kanade.tachiyomi.ui.mod.util.dateUploadMillis
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import logcat.LogPriority
import mihon.domain.source.interactor.UpdateMangaFromRemote
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.history.interactor.ManageUpdateWatch
import tachiyomi.domain.history.interactor.ManageUpdateWatchHistory
import tachiyomi.domain.history.model.UpdateWatch
import tachiyomi.domain.history.model.UpdateWatchHistory
import tachiyomi.domain.history.model.UpdateWatchInboxItem
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

object UpdateWatchForegroundBurst : DefaultLifecycleObserver {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var burstJob: Job? = null
    private var lastBurstAt = 0L
    private const val BURST_COOLDOWN_MS = 5 * 60 * 1000L

    private val isAppInForeground = AtomicBoolean(false)
    private var applicationContext: Context? = null
    private var isInitialized = false

    fun init(context: Context) {
        if (isInitialized) return
        applicationContext = context.applicationContext
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        isInitialized = true
    }

    override fun onStart(owner: LifecycleOwner) {
        isAppInForeground.set(true)
        val now = System.currentTimeMillis()
        if (now - lastBurstAt > BURST_COOLDOWN_MS) {
            startBurst()
        }
    }

    override fun onStop(owner: LifecycleOwner) {
        isAppInForeground.set(false)
    }

    private fun startBurst() {
        val context = applicationContext
        if (context == null) {
            UpdateWatchDiagnosticsManager.logEvent("Foreground burst failed: Not initialized")
            return
        }

        burstJob?.cancel()
        burstJob = scope.launch {
            UpdateWatchRefreshState.onRunStarted()
            try {
                val manageUpdateWatch = Injekt.get<ManageUpdateWatch>()
                val getManga = Injekt.get<GetManga>()
                val chapterRepository = Injekt.get<ChapterRepository>()

                val startTime = System.currentTimeMillis()
                val trackedManga = manageUpdateWatch.subscribeAll().first()
                val dueHotCandidates = mutableListOf<RefreshCandidate>()

                for (tracking in trackedManga) {
                    if (!tracking.backgroundRefreshEnabled || tracking.isPaused) continue

                    val manga = getManga.await(tracking.mangaId) ?: continue
                    val chapters = chapterRepository.getChapterByMangaId(manga.id)
                    val latestChapter =
                        chapters.filter { it.dateUploadMillis > 0 }.maxByOrNull { it.dateUploadMillis } ?: continue

                    val eligibility = UpdateWatchRefreshHelper.getEligibility(
                        enabled = true,
                        expectedIntervalDays = tracking.expectedIntervalDays,
                        refreshProfile = tracking.refreshProfile,
                        latestChapterUploadDate = latestChapter.dateUploadMillis,
                        lastCheckAt = tracking.lastBackgroundCheckAt,
                        now = startTime,
                    )

                    if (eligibility.bucket == UpdateWatchRefreshHelper.PriorityBucket.HOT && eligibility.isDue) {
                        dueHotCandidates.add(
                            RefreshCandidate(
                                mangaId = manga.id,
                                title = manga.title,
                                sourceId = manga.source,
                                expectedIntervalDays = tracking.expectedIntervalDays,
                                refreshProfile = tracking.refreshProfile.name,
                                lastCheckAt = tracking.lastBackgroundCheckAt ?: 0L,
                            ),
                        )
                    }
                }

                if (dueHotCandidates.isEmpty()) return@launch

                lastBurstAt = System.currentTimeMillis()
                logcat(LogPriority.INFO) { "Foreground HOT burst started: ${dueHotCandidates.size} candidates found." }

                val candidatesBySource = dueHotCandidates.groupBy { it.sourceId }
                val sourceSemaphore = Semaphore(4)
                val processedCount = AtomicInteger(0)
                val updatedCount = AtomicInteger(0)
                val failedCount = AtomicInteger(0)
                val newlyFoundInboxItems = mutableListOf<UpdateWatchInboxItem>()
                val mangaDiagnosticDetails = mutableListOf<MangaDiagnosticDetail>()

                candidatesBySource.map { (sourceId, sourceQueue) ->
                    async {
                        sourceSemaphore.withPermit {
                            var burstCounter = 0
                            for (i in sourceQueue.indices) {
                                if (!isAppInForeground.get()) {
                                    logcat(LogPriority.INFO) {
                                        "Foreground HOT burst stopped for source $sourceId: " +
                                            "app left foreground."
                                    }
                                    break
                                }

                                val candidate = sourceQueue[i]
                                val claimed = UpdateWatchRefreshState.claim(setOf(candidate.mangaId))
                                if (claimed.isEmpty()) continue

                                try {
                                    if (burstCounter > 0 && burstCounter % 5 == 0) {
                                        delay(60000)
                                    } else if (burstCounter > 0) {
                                        delay(Random.nextLong(3000, 5001))
                                    }

                                    if (!isAppInForeground.get()) {
                                        UpdateWatchRefreshState.releaseOne(candidate.mangaId)
                                        break
                                    }

                                    val result = processCandidate(
                                        candidate,
                                        mangaDiagnosticDetails,
                                        newlyFoundInboxItems,
                                    )
                                    processedCount.incrementAndGet()
                                    burstCounter++

                                    when (result) {
                                        RefreshStatus.UPDATED -> updatedCount.incrementAndGet()
                                        RefreshStatus.FAILED_RATE_LIMITED, RefreshStatus.FAILED_BLOCKED -> {
                                            failedCount.incrementAndGet()
                                            notifySourceFailure(sourceId, result)
                                            break
                                        }
                                        RefreshStatus.FAILED_ORDINARY -> failedCount.incrementAndGet()
                                        else -> {}
                                    }
                                } finally {
                                    UpdateWatchRefreshState.releaseOne(candidate.mangaId)
                                }
                            }
                        }
                    }
                }.awaitAll()

                UpdateWatchPostRefreshHandler.showNotificationIfEnabled(context, newlyFoundInboxItems)

                recordDiagnostics(
                    startTime,
                    dueHotCandidates.size,
                    processedCount.get(),
                    updatedCount.get(),
                    failedCount.get(),
                    candidatesBySource.size,
                    mangaDiagnosticDetails,
                )

                UpdateWatchRefreshScheduler.setupTaskSuspend(context, skipRunCheck = true)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                logcat(LogPriority.ERROR, e) { "Foreground burst encountered unexpected error" }
                UpdateWatchDiagnosticsManager.logEvent("Foreground burst error: ${e.message}")
            } finally {
                UpdateWatchRefreshState.onRunFinished()
            }
        }
    }

    private suspend fun processCandidate(
        candidate: RefreshCandidate,
        mangaDiagnosticDetails: MutableList<MangaDiagnosticDetail>,
        newlyFoundInboxItems: MutableList<UpdateWatchInboxItem>,
    ): RefreshStatus {
        val getManga = Injekt.get<GetManga>()
        val sourceManager = Injekt.get<SourceManager>()
        val updateMangaFromRemote = Injekt.get<UpdateMangaFromRemote>()
        val manageUpdateWatch = Injekt.get<ManageUpdateWatch>()
        val chapterRepository = Injekt.get<ChapterRepository>()

        val manga = getManga.await(candidate.mangaId) ?: return RefreshStatus.SKIPPED
        val source = sourceManager.getOrStub(manga.source)
        val startTime = System.currentTimeMillis()

        manageUpdateWatch.updateLastBackgroundCheckAt(candidate.mangaId, startTime)

        return try {
            val oldChapters = chapterRepository.getChapterByMangaId(manga.id)
            val oldLatest = oldChapters.filter { it.dateUploadMillis > 0 }.maxByOrNull { it.dateUploadMillis }

            val refreshResult = updateMangaFromRemote(manga, fetchChapters = true)

            if (refreshResult.isSuccess) {
                val newChapters = refreshResult.getOrThrow().newChapters
                val currentChapters = chapterRepository.getChapterByMangaId(manga.id)
                val newLatest = currentChapters.filter { it.dateUploadMillis > 0 }
                    .maxByOrNull { it.dateUploadMillis }

                UpdateWatchPostRefreshHandler.handleRefreshResult(
                    manga = manga,
                    oldLatestChapter = oldLatest,
                    newChapters = newChapters,
                    currentLatestChapter = newLatest,
                    startTime = startTime,
                    onInboxItemCreated = { synchronized(newlyFoundInboxItems) { newlyFoundInboxItems.add(it) } },
                )

                recordHistory(candidate, true, newChapters.size, UpdateWatchHistory.FailureCategory.NONE, null)
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
                    mangaDiagnosticDetails,
                )
                finalStatus
            } else {
                val exception = refreshResult.exceptionOrNull()
                val type = classifyFailure(exception)
                val error = exception?.message ?: "Unknown error"
                recordHistory(candidate, false, 0, UpdateWatchHistory.FailureCategory.UNKNOWN, error)
                val finalStatus = when (type) {
                    FailureType.RATE_LIMITED -> RefreshStatus.FAILED_RATE_LIMITED
                    FailureType.BLOCKED -> RefreshStatus.FAILED_BLOCKED
                    FailureType.TRANSIENT -> RefreshStatus.FAILED_ORDINARY
                    FailureType.ORDINARY -> RefreshStatus.FAILED_ORDINARY
                }
                recordMangaDiagnostic(candidate, source.name, finalStatus.name, error, mangaDiagnosticDetails)
                finalStatus
            }
        } catch (e: Exception) {
            recordHistory(candidate, false, 0, UpdateWatchHistory.FailureCategory.UNKNOWN, e.message)
            recordMangaDiagnostic(candidate, source.name, "EXCEPTION", e.message, mangaDiagnosticDetails)
            RefreshStatus.FAILED_ORDINARY
        }
    }

    private suspend fun recordMangaDiagnostic(
        candidate: RefreshCandidate,
        sourceName: String,
        result: String,
        error: String?,
        mangaDiagnosticDetails: MutableList<MangaDiagnosticDetail>,
    ) {
        val chapterRepository = Injekt.get<ChapterRepository>()
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
    }

    private fun recordHistory(
        candidate: RefreshCandidate,
        success: Boolean,
        newChapters: Int,
        category: UpdateWatchHistory.FailureCategory,
        detail: String?,
    ) {
        val manageUpdateWatchHistory = Injekt.get<ManageUpdateWatchHistory>()
        scope.launch {
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
                logcat(LogPriority.ERROR, e)
            }
        }
    }

    private suspend fun notifySourceFailure(sourceId: Long, status: RefreshStatus) {
        val sourceManager = Injekt.get<SourceManager>()
        val sourceName = sourceManager.getOrStub(sourceId).name
        val title = "Auto Refresh paused for $sourceName"
        val body = when (status) {
            RefreshStatus.FAILED_BLOCKED ->
                "Fast refresh was stopped because $sourceName returned " +
                    "a Cloudflare challenge. Remaining manga will use " +
                    "the normal background queue."
            RefreshStatus.FAILED_RATE_LIMITED ->
                "Fast refresh was stopped because $sourceName temporarily " +
                    "rate-limited requests. Remaining manga will use " +
                    "the normal background queue."
            else -> "Fast refresh was stopped due to repeated errors from $sourceName."
        }
        logcat(LogPriority.WARN) { "NOTIFICATION: $title - $body" }
    }

    private fun recordDiagnostics(
        startTime: Long,
        eligibleCount: Int,
        processedCount: Int,
        updatedCount: Int,
        failedCount: Int,
        sourceCount: Int,
        mangaDetails: List<MangaDiagnosticDetail>,
    ) {
        val completionTime = System.currentTimeMillis()
        UpdateWatchDiagnosticsManager.log(
            UpdateWatchSchedulerDiagnostic(
                type = UpdateWatchSchedulerDiagnostic.RunType.FOREGROUND_HOT_BURST,
                startedAt = startTime,
                completedAt = completionTime,
                eligibleCount = eligibleCount,
                selectedCount = processedCount,
                refreshedCount = processedCount,
                updatedCount = updatedCount,
                noUpdateCount = processedCount - updatedCount - failedCount,
                failedCount = failedCount,
                sourceCount = sourceCount,
                mangaDetails = mangaDetails,
            ),
        )
    }

    private fun classifyFailure(e: Throwable?): FailureType {
        if (e == null) return FailureType.ORDINARY
        if (e is eu.kanade.tachiyomi.network.HttpException) {
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

    private enum class RefreshStatus { SUCCESS, UPDATED, FAILED_ORDINARY, FAILED_RATE_LIMITED, FAILED_BLOCKED, SKIPPED }
    private enum class FailureType { RATE_LIMITED, BLOCKED, TRANSIENT, ORDINARY }
    private data class RefreshCandidate(
        val mangaId: Long,
        val title: String,
        val sourceId: Long,
        val expectedIntervalDays: Int,
        val refreshProfile: String,
        val lastCheckAt: Long,
    )
}

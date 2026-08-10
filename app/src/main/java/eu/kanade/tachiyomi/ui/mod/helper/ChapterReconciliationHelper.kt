package eu.kanade.tachiyomi.ui.mod.helper

import tachiyomi.domain.chapter.model.Chapter

/**
 * Helper to reconcile chapters during source refresh.
 * Primarily handles cases where chapter URLs change but logical identity (number + scanlator) is preserved.
 */
object ChapterReconciliationHelper {

    fun findReconcilableCandidate(
        sourceChapter: Chapter,
        pool: List<Chapter>
    ): Chapter? {
        if (!sourceChapter.isRecognizedNumber) return null

        // 1. Precise match (number + scanlator)
        val preciseCandidates = pool.filter {
            it.isRecognizedNumber &&
                it.chapterNumber == sourceChapter.chapterNumber &&
                isScanlatorCompatible(it.scanlator, sourceChapter.scanlator)
        }
        if (preciseCandidates.size == 1) return preciseCandidates.first()

        // 2. Loose match (number only) IF unique for that number in the pool
        // This handles cases where scanlator name changed or became null.
        val numberCandidates = pool.filter {
            it.isRecognizedNumber && it.chapterNumber == sourceChapter.chapterNumber
        }
        if (numberCandidates.size == 1) return numberCandidates.first()

        return null
    }

    fun isScanlatorCompatible(s1: String?, s2: String?): Boolean {
        val n1 = s1?.trim()?.lowercase()?.filter { it.isLetterOrDigit() } ?: ""
        val n2 = s2?.trim()?.lowercase()?.filter { it.isLetterOrDigit() } ?: ""
        // If one is empty, we consider it compatible if it's the only candidate (handled in findReconcilableCandidate)
        return n1 == n2
    }
}

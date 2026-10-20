package eu.kanade.tachiyomi.ui.mod.util

import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga

/** Existing mod scheduling preferences store epoch milliseconds. */
val Chapter.dateUploadMillis: Long get() = dateUpload?.toEpochMilliseconds() ?: 0L
val Manga.lastUpdateMillis: Long get() = lastUpdate?.toEpochMilliseconds() ?: 0L
val Manga.nextUpdateMillis: Long get() = nextUpdate?.toEpochMilliseconds() ?: 0L

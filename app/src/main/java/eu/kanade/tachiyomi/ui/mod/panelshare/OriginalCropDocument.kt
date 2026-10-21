package eu.kanade.tachiyomi.ui.mod.panelshare

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.UUID
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt

data class CropSourcePage(val file: File, val width: Int, val height: Int, val top: Double) {
    val bottom get() = top + height.toDouble() / width
}

/** Retains the reader loader, but edits a separate document without changing reading progress. */
class OriginalCropDocument(context: Context, private val chapter: ReaderChapter, private val startIndex: Int) {
    private val directory = File(context.cacheDir, "panel-crop/${UUID.randomUUID()}").apply { mkdirs() }
    private val readerPages = requireNotNull(chapter.pages)
    private var nextIndex = startIndex.coerceIn(readerPages.indices)
    private var closed = false
    var pages by mutableStateOf<List<CropSourcePage>>(emptyList())
        private set
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    val height get() = pages.lastOrNull()?.bottom ?: 0.0
    val hasNext get() = nextIndex < readerPages.size

    init {
        chapter.ref()
    }

    suspend fun loadNext() {
        if (closed || loading || !hasNext) return
        loading = true
        error = null
        try {
            val readerPage = readerPages[nextIndex]
            val file = File(directory, "$nextIndex.image")
            val bounds = withContext(Dispatchers.IO) {
                coroutineScope {
                    val job = launch { chapter.pageLoader?.loadPage(readerPage) }
                    try {
                        withTimeout(60_000) {
                            readerPage.statusFlow.first { it is Page.State.Ready || it is Page.State.Error }
                        }
                        check(readerPage.status is Page.State.Ready) {
                            "Page could not load. Return to the reader and retry this page."
                        }
                        requireNotNull(readerPage.stream) { "Original image is unavailable" }.invoke().use { input ->
                            file.outputStream().use { output -> input.copyTo(output) }
                        }
                    } finally {
                        job.cancel()
                    }
                }
                BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                    BitmapFactory.decodeFile(file.absolutePath, this)
                    require(outWidth > 0 && outHeight > 0) { "This image format cannot be cropped" }
                }
            }
            currentCoroutineContext().ensureActive()
            if (!closed) {
                pages = pages + CropSourcePage(file, bounds.outWidth, bounds.outHeight, height)
                nextIndex++
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: "Could not load the next image"
        } finally {
            loading = false
        }
    }

    fun close() {
        if (closed) return
        closed = true
        chapter.unref()
        // Only this document's UUID directory is removed. Exported shares live elsewhere.
        directory.deleteRecursively()
    }

    fun dimensions(crop: DocumentCrop): Pair<Int, Int> {
        val sourceWidth = pages.filter { it.bottom > crop.top && it.top < crop.bottom }.minOf { it.width }
        return crop.dimensions(sourceWidth)
    }

    /** Region strips avoid decoding an entire long webtoon image into memory. */
    suspend fun render(crop: DocumentCrop, maxSide: Int? = null): Bitmap = withContext(Dispatchers.Default) {
        require(crop.width > 0 && crop.height > 0 && crop.bottom <= height + 0.0001)
        val (originalW, originalH) = dimensions(crop)
        val scale = maxSide?.let { min(1.0, it.toDouble() / maxOf(originalW, originalH)) } ?: 1.0
        val w = (originalW * scale).roundToInt().coerceAtLeast(1)
        val h = (originalH * scale).roundToInt().coerceAtLeast(1)
        val budget = min(96L * 1024 * 1024, Runtime.getRuntime().maxMemory() / 5)
        require(w.toLong() * h * 4 <= budget) { "Selection is too large for this device. Select a smaller area." }
        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(result)
            canvas.drawColor(Color.WHITE)
            val paint = Paint(Paint.FILTER_BITMAP_FLAG)
            for (page in pages) {
                if (page.bottom <= crop.top || page.top >= crop.bottom) continue
                currentCoroutineContext().ensureActive()
                @Suppress("DEPRECATION")
                val decoder = requireNotNull(BitmapRegionDecoder.newInstance(page.file.absolutePath, false))
                try {
                    val x0 = floor(crop.left * page.width).toInt().coerceIn(0, page.width - 1)
                    val x1 = ceil(crop.right * page.width).toInt().coerceIn(x0 + 1, page.width)
                    val y0 = floor((crop.top - page.top).coerceAtLeast(0.0) * page.width).toInt().coerceIn(
                        0,
                        page.height - 1,
                    )
                    val y1 = ceil((crop.bottom - page.top) * page.width).toInt().coerceIn(y0 + 1, page.height)
                    var sample = 1
                    while ((x1 - x0) / (sample * 2) >= w) sample *= 2
                    var y = y0
                    while (y < y1) {
                        currentCoroutineContext().ensureActive()
                        val end = min(y1, y + 512 * sample)
                        val tile =
                            requireNotNull(
                                decoder.decodeRegion(
                                    Rect(x0, y, x1, end),
                                    BitmapFactory.Options().apply {
                                        inSampleSize =
                                            sample
                                    },
                                ),
                            )
                        try {
                            val target = RectF(
                                ((x0.toDouble() / page.width - crop.left) / crop.width * w).toFloat(),
                                ((page.top + y.toDouble() / page.width - crop.top) / crop.height * h).toFloat(),
                                ((x1.toDouble() / page.width - crop.left) / crop.width * w).toFloat(),
                                ((page.top + end.toDouble() / page.width - crop.top) / crop.height * h).toFloat(),
                            )
                            canvas.drawBitmap(tile, null, target, paint)
                        } finally {
                            tile.recycle()
                        }
                        y = end
                    }
                } finally {
                    decoder.recycle()
                }
            }
            result
        } catch (e: Throwable) {
            result.recycle()
            throw e
        }
    }
}

/** Three source rows are enough for sharpening; extra memory is proportional to width, not height. */
suspend fun Bitmap.applyCropEnhancement(preset: CropEnhancement) {
    if (preset == CropEnhancement.Original) return
    withContext(Dispatchers.Default) {
        var previous = IntArray(width)
        var current = IntArray(width)
        var next = IntArray(width)
        val output = IntArray(width)
        getPixels(current, 0, width, 0, 0, width, 1)
        current.copyInto(previous)
        for (y in 0 until height) {
            currentCoroutineContext().ensureActive()
            if (y + 1 < height) getPixels(next, 0, width, 0, y + 1, width, 1) else current.copyInto(next)
            for (x in 0 until width) {
                output[x] =
                    preset.pixel(
                        current[x],
                        current[(x - 1).coerceAtLeast(0)],
                        current[(x + 1).coerceAtMost(width - 1)],
                        previous[x],
                        next[x],
                    )
            }
            setPixels(output, 0, width, 0, y, width, 1)
            val spare = previous
            previous = current
            current = next
            next = spare
        }
    }
}

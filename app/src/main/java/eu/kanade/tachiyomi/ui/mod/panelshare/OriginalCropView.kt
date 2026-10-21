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
import android.util.LruCache
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.hypot

/** Viewport tiles and crop coordinates stay independent: scrolling never moves the selected panel. */
class OriginalCropView(context: Context) : View(context) {
    private var document: OriginalCropDocument? = null
    private var selection = DocumentCrop(.1, .1, .9, .8)
    private var template = CropTemplate.Custom
    private var templateRatio = .45
    private var zoom = 1.0
    private var top = 0.0
    private var horizontal = 0.0
    private var lastX = 0f
    private var lastY = 0f
    private var dragMode = -1
    private var twoFinger = false
    private var initialized = false
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val pending = mutableSetOf<String>()
    private val failed = mutableSetOf<String>()
    private val tiles = object : LruCache<String, Bitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val imagePaint = Paint(Paint.FILTER_BITMAP_FLAG).apply { alpha = 255 }
    var onSelection: (DocumentCrop) -> Unit = {}
    var onNeedNext: () -> Unit = {}
    var onError: (String) -> Unit = {}
    private val pixelsPerUnit get() = width * zoom
    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val x = worldX(detector.focusX)
            val y = worldY(detector.focusY)
            val minimum = minimumZoom()
            zoom = (zoom * detector.scaleFactor).coerceIn(minimum, max(8.0, minimum * 8))
            horizontal = detector.focusX - x * pixelsPerUnit
            top = y - detector.focusY / pixelsPerUnit
            constrain()
            updateTemplateSelection()
            invalidate()
            return true
        }
    })

    fun update(doc: OriginalCropDocument, crop: DocumentCrop, mode: CropTemplate, deviceRatio: Double) {
        document = doc
        val changed = template != mode || templateRatio != (mode.ratio ?: deviceRatio)
        template = mode
        templateRatio = mode.ratio ?: deviceRatio
        selection = if (doc.height > 0) crop.clamped(doc.height) else crop
        // AndroidView.update can run before layout, especially when reopening Adjust crop.
        // A zero-width viewport would divide by zero and publish a NaN crop to Compose.
        if (width <= 0 || height <= 0 || doc.height <= 0) {
            invalidate()
            return
        }
        if (!initialized || changed) {
            initialized = true
            if (mode == CropTemplate.Custom) {
                val position = customCropViewport(selection, height.toDouble() / width, doc.height)
                zoom = position.zoom
                horizontal = position.horizontal
                top = position.top
            } else {
                val centerX = (selection.left + selection.right) / 2
                val centerY = (selection.top + selection.bottom) / 2
                zoom = minimumZoom()
                horizontal = frame().centerX() - centerX * pixelsPerUnit
                top = centerY - frame().centerY() / pixelsPerUnit
            }
            constrain()
            updateTemplateSelection()
        }
        invalidate()
    }

    private fun frame(): RectF {
        val w = min(width * .86f, (height * .82 * templateRatio).toFloat())
        val h = (w / templateRatio).toFloat()
        return RectF((width - w) / 2, (height - h) / 2, (width + w) / 2, (height + h) / 2)
    }

    private fun minimumZoom(): Double {
        val doc = document ?: return 1.0
        if (template == CropTemplate.Custom || doc.height <= 0 || width <= 0) return 1.0
        val frame = frame()
        return max(frame.width().toDouble() / width, frame.height() / (width * doc.height))
    }

    private fun worldX(x: Float) = (x - horizontal) / pixelsPerUnit
    private fun worldY(y: Float) = top + y / pixelsPerUnit
    private fun screenX(x: Double) = (horizontal + x * pixelsPerUnit).toFloat()
    private fun screenY(y: Double) = ((y - top) * pixelsPerUnit).toFloat()

    private fun handles() = listOf(
        selection.left to selection.top, selection.right to selection.top,
        selection.right to selection.bottom, selection.left to selection.bottom,
        (selection.left + selection.right) / 2 to selection.top,
        (selection.left + selection.right) / 2 to selection.bottom,
    )

    private fun constrain() {
        val doc = document ?: return
        if (width <= 0 || height <= 0 || doc.height <= 0) return
        if (template == CropTemplate.Custom) {
            horizontal = horizontal.coerceIn(min(0.0, width - pixelsPerUnit), 0.0)
            top = top.coerceIn(0.0, max(0.0, doc.height - height / pixelsPerUnit))
        } else {
            val f = frame()
            horizontal = boundedCropOffset(horizontal, f.right - pixelsPerUnit, f.left.toDouble())
            top = boundedCropOffset(top, -f.top / pixelsPerUnit, doc.height - f.bottom / pixelsPerUnit)
        }
    }

    private fun updateTemplateSelection() {
        val doc = document ?: return
        if (template == CropTemplate.Custom || doc.height <= 0 || width <= 0) return
        val f = frame()
        selection = DocumentCrop(worldX(f.left), worldY(f.top), worldX(f.right), worldY(f.bottom)).clamped(doc.height)
        onSelection(selection)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        document?.let { update(it, selection, template, templateRatio) }
        zoom = max(zoom, minimumZoom())
        constrain()
        updateTemplateSelection()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    }

    override fun onDetachedFromWindow() {
        scope.cancel()
        tiles.evictAll()
        pending.clear()
        failed.clear()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.save()
        canvas.clipRect(0, 0, width, height)
        canvas.drawColor(Color.rgb(25, 25, 25))
        val doc = document
        if (doc == null || doc.height <= 0 || width <= 0) { canvas.restore(); return }
        val bottom = top + height / pixelsPerUnit
        for ((index, page) in doc.pages.withIndex()) {
            if (page.bottom < top || page.top > bottom) continue
            var sample = 1
            while (page.width / (sample * 2) > min(pixelsPerUnit, 1400.0)) sample *= 2
            val strip = 512 * sample
            val firstY = (((top - page.top) * page.width).toInt().coerceAtLeast(0) / strip) * strip
            var y = firstY
            while (y < page.height && page.top + y.toDouble() / page.width < bottom) {
                val end = min(page.height, y + strip)
                val key = "$index:$y:$sample"
                val tile = tiles.get(key)
                val target = RectF(screenX(0.0), screenY(page.top + y.toDouble() / page.width), screenX(1.0), screenY(page.top + end.toDouble() / page.width))
                if (tile != null) {
                    canvas.drawBitmap(tile, null, target, imagePaint)
                } else {
                    paint.color = Color.DKGRAY
                    canvas.drawRect(target, paint)
                    if (key !in failed && pending.size < 4 && pending.add(key)) {
                        val region = Rect(0, y, page.width, end)
                        val tileSample = sample
                        scope.launch {
                            try {
                                val bitmap = withContext(Dispatchers.IO) {
                                    @Suppress("DEPRECATION")
                                    val decoder = BitmapRegionDecoder.newInstance(page.file.absolutePath, false)
                                    try { decoder.decodeRegion(region, BitmapFactory.Options().apply { inSampleSize = tileSample }) }
                                    finally { decoder.recycle() }
                                }
                                if (bitmap != null) tiles.put(key, bitmap) else {
                                    failed.add(key)
                                    onError("This image format could not be previewed. Try another page.")
                                }
                            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                            catch (_: Exception) {
                                failed.add(key)
                                onError("This image format could not be previewed. Try another page.")
                            }
                            finally { pending.remove(key); invalidate() }
                        }
                    }
                }
                y = end
            }
        }
        val box = if (template == CropTemplate.Custom) RectF(screenX(selection.left), screenY(selection.top), screenX(selection.right), screenY(selection.bottom)) else frame()
        // Exclude the crop from the dimming operation entirely, including while partially offscreen.
        canvas.save()
        canvas.clipOutRect(box)
        canvas.drawColor(0x99000000.toInt())
        canvas.restore()
        paint.color = Color.WHITE
        paint.strokeWidth = 2 * resources.displayMetrics.density
        paint.style = Paint.Style.STROKE
        canvas.drawRect(box, paint)
        paint.style = Paint.Style.FILL
        if (template == CropTemplate.Custom) {
            handles().forEach { (x, y) ->
                canvas.drawCircle(screenX(x), screenY(y), 7 * resources.displayMetrics.density, paint)
            }
        }
        if (bottom > doc.height - .3 && doc.hasNext && !doc.loading && doc.error == null) post { onNeedNext() }
        canvas.restore()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return true
        val doc = document ?: return true
        if (doc.height <= 0) return true
        scaleDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                twoFinger = false
                lastX = event.x
                lastY = event.y
                val threshold = 32 * resources.displayMetrics.density
                dragMode = handles().mapIndexedNotNull { index, (x, y) ->
                    val distance = hypot(screenX(x) - event.x, screenY(y) - event.y)
                    if (distance <= threshold) index to distance else null
                }.minByOrNull { it.second }?.first ?: -1
                if (dragMode < 0) {
                    val left = screenX(selection.left)
                    val right = screenX(selection.right)
                    val upper = screenY(selection.top)
                    val lower = screenY(selection.bottom)
                    val edge = 12 * resources.displayMetrics.density
                    val onHorizontalEdge = event.x in left..right && (abs(event.y - upper) < edge || abs(event.y - lower) < edge)
                    val onVerticalEdge = event.y in upper..lower && (abs(event.x - left) < edge || abs(event.x - right) < edge)
                    dragMode = if (onHorizontalEdge || onVerticalEdge) 6 else -1
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> { twoFinger = true; lastX = event.getX(0); lastY = event.getY(0) }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - lastX
                val dy = event.y - lastY
                if (template != CropTemplate.Custom || dragMode < 0 || twoFinger) {
                    if (!scaleDetector.isInProgress) {
                        horizontal += dx
                        top -= dy / pixelsPerUnit
                        constrain()
                        updateTemplateSelection()
                    }
                } else {
                    val s = selection
                    val x = worldX(event.x).coerceIn(0.0, 1.0)
                    val y = worldY(event.y).coerceIn(0.0, doc.height)
                    selection = if (dragMode in CropHandle.entries.indices) {
                        resizeCropHandle(s, CropHandle.entries[dragMode], x, y, doc.height)
                    } else {
                            val moveX = (dx / pixelsPerUnit).coerceIn(-s.left, 1 - s.right)
                            val moveY = (dy / pixelsPerUnit).coerceIn(-s.top, doc.height - s.bottom)
                            DocumentCrop(s.left + moveX, s.top + moveY, s.right + moveX, s.bottom + moveY)
                    }.clamped(doc.height)
                    onSelection(selection)
                    // Resize near an edge to keep extending the document-anchored crop.
                    if (dragMode in CropHandle.entries.indices && event.y > height - 40 * resources.displayMetrics.density) {
                        top += 18 / pixelsPerUnit
                        constrain()
                    } else if (dragMode in CropHandle.entries.indices && event.y < 40 * resources.displayMetrics.density) {
                        top -= 18 / pixelsPerUnit
                        constrain()
                    }
                }
                lastX = event.x
                lastY = event.y
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { parent?.requestDisallowInterceptTouchEvent(false); performClick() }
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()
}

package eu.kanade.tachiyomi.ui.mod.panelshare

import kotlin.math.roundToInt
import kotlin.math.min
import kotlin.math.max

enum class CropHandle { TopLeft, TopRight, BottomRight, BottomLeft, Top, Bottom }

fun resizeCropHandle(crop: DocumentCrop, handle: CropHandle, x: Double, y: Double, documentHeight: Double): DocumentCrop {
    val gap = min(.001, documentHeight / 2)
    return when (handle) {
        CropHandle.TopLeft -> crop.copy(left = min(x, crop.right - .001), top = min(y, crop.bottom - gap))
        CropHandle.TopRight -> crop.copy(right = max(x, crop.left + .001), top = min(y, crop.bottom - gap))
        CropHandle.BottomRight -> crop.copy(right = max(x, crop.left + .001), bottom = max(y, crop.top + gap))
        CropHandle.BottomLeft -> crop.copy(left = min(x, crop.right - .001), bottom = max(y, crop.top + gap))
        CropHandle.Top -> crop.copy(top = min(y, crop.bottom - gap))
        CropHandle.Bottom -> crop.copy(bottom = max(y, crop.top + gap))
    }.clamped(documentHeight)
}

/** Float frame edges can differ by a fraction of a pixel when converted to Double. */
fun boundedCropOffset(value: Double, minimum: Double, maximum: Double): Double =
    value.coerceIn(minimum, maximum.coerceAtLeast(minimum))

data class CropViewport(val zoom: Double, val horizontal: Double, val top: Double)

/** Leaving a wallpaper frame fits the comic width again while keeping the selected panel in view. */
fun customCropViewport(crop: DocumentCrop, viewportAspect: Double, documentHeight: Double): CropViewport =
    CropViewport(
        zoom = 1.0,
        horizontal = 0.0,
        top = boundedCropOffset((crop.top + crop.bottom - viewportAspect) / 2, 0.0, documentHeight - viewportAspect),
    )

enum class CropTemplate(val label: String, val ratio: Double?) {
    Custom("Custom", null), Device("Device", null), Phone20("9:20", 9.0 / 20),
    Phone195("9:19.5", 9.0 / 19.5), Phone16("9:16", 9.0 / 16),
}

enum class CropEnhancement(val label: String, val contrast: Double, val saturation: Double, val sharpness: Double) {
    Original("Original", 1.0, 1.0, 0.0),
    Light("Light Enhance", 1.04, 1.04, 0.18),
    Strong("Strong Enhance", 1.10, 1.08, 0.38),
    BlackWhite("B&W Enhance", 1.12, 0.0, 0.28),
    ;

    /** Neighbours always come from the unchanged source, so switching presets never stacks filters. */
    fun pixel(center: Int, left: Int, right: Int, above: Int, below: Int): Int {
        if (this == Original) return center
        fun component(shift: Int): Double {
            fun channel(pixel: Int) = (pixel ushr shift) and 255
            val c = channel(center).toDouble()
            return c + sharpness * (c - (channel(left) + channel(right) + channel(above) + channel(below)) / 4.0)
        }
        val r = component(16)
        val g = component(8)
        val b = component(0)
        val luminance = 0.2126 * r + 0.7152 * g + 0.0722 * b
        fun adjusted(value: Double) = (((luminance + (value - luminance) * saturation) - 127.5) * contrast + 127.5)
            .roundToInt().coerceIn(0, 255)
        return (center and -0x1000000) or (adjusted(r) shl 16) or (adjusted(g) shl 8) or adjusted(b)
    }
}

/** X and Y are measured in page widths, allowing a selection to cross any number of page files. */
data class DocumentCrop(val left: Double, val top: Double, val right: Double, val bottom: Double) {
    val width get() = right - left
    val height get() = bottom - top
    fun clamped(documentHeight: Double): DocumentCrop {
        require(documentHeight > 0)
        val minimumHeight = min(.001, documentHeight / 2)
        val l = left.coerceIn(0.0, 0.99)
        val t = top.coerceIn(0.0, documentHeight - minimumHeight)
        return DocumentCrop(l, t, right.coerceIn(l + 0.001, 1.0), bottom.coerceIn(t + minimumHeight, documentHeight))
    }

    fun dimensions(sourceWidth: Int): Pair<Int, Int> =
        (width * sourceWidth).roundToInt().coerceAtLeast(1) to (height * sourceWidth).roundToInt().coerceAtLeast(1)
}

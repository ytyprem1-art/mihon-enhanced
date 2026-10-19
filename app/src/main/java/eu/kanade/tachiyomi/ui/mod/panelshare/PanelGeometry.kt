package eu.kanade.tachiyomi.ui.mod.panelshare

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** Coordinates are relative to the displayed image, independent of screen density. */
data class PanelSelection(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    fun pixels(width: Int, height: Int): PanelPixels {
        require(width > 0 && height > 0)
        val x = floor(min(left, right).coerceIn(0f, 1f) * width).toInt().coerceAtMost(width - 1)
        val y = floor(min(top, bottom).coerceIn(0f, 1f) * height).toInt().coerceAtMost(height - 1)
        val endX = ceil(max(left, right).coerceIn(0f, 1f) * width).toInt().coerceIn(x + 1, width)
        val endY = ceil(max(top, bottom).coerceIn(0f, 1f) * height).toInt().coerceIn(y + 1, height)
        return PanelPixels(x, y, endX - x, endY - y)
    }

    companion object {
        val Initial = PanelSelection(0.15f, 0.2f, 0.85f, 0.65f)
    }
}

data class PanelPixels(val x: Int, val y: Int, val width: Int, val height: Int)

object PanelLimits {
    const val MAX_BYTES = 150_000
    const val MAX_SIDE = 800
    const val RETAINED_MESSAGES = 200L
    const val INITIAL_MESSAGES = 50L

    fun scaledSize(width: Int, height: Int): Pair<Int, Int> {
        require(width > 0 && height > 0)
        val scale = min(1.0, MAX_SIDE.toDouble() / max(width, height))
        return max(1, (width * scale).toInt()) to max(1, (height * scale).toInt())
    }
}

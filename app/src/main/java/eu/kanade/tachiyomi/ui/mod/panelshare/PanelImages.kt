package eu.kanade.tachiyomi.ui.mod.panelshare

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import java.io.ByteArrayOutputStream

data class CompressedPanel(val bytes: ByteArray, val width: Int, val height: Int)

object PanelImages {
    /** Work on the viewport crop only; never decode the full, potentially very tall chapter image. */
    fun compress(viewport: Bitmap, selection: PanelSelection): CompressedPanel {
        val rect = selection.pixels(viewport.width, viewport.height)
        val crop = Bitmap.createBitmap(viewport, rect.x, rect.y, rect.width, rect.height)
        val (width, height) = PanelLimits.scaledSize(crop.width, crop.height)
        var current = Bitmap.createScaledBitmap(crop, width, height, true)
        if (crop !== viewport && crop !== current) crop.recycle()
        try {
            val format = if (Build.VERSION.SDK_INT >= 30) {
                Bitmap.CompressFormat.WEBP_LOSSY
            } else {
                @Suppress("DEPRECATION")
                Bitmap.CompressFormat.WEBP
            }
            repeat(8) {
                for (quality in listOf(85, 75, 65, 55, 45)) {
                    val output = ByteArrayOutputStream()
                    check(current.compress(format, quality, output)) { "Image compression failed" }
                    if (output.size() <= PanelLimits.MAX_BYTES) {
                        return CompressedPanel(output.toByteArray(), current.width, current.height)
                    }
                }
                val smaller = Bitmap.createScaledBitmap(
                    current,
                    (current.width * 0.8f).toInt().coerceAtLeast(1),
                    (current.height * 0.8f).toInt().coerceAtLeast(1),
                    true,
                )
                if (current !== viewport && current !== smaller) current.recycle()
                current = smaller
            }
            error("Could not compress this crop below 150 KB. Try a smaller selection.")
        } finally {
            if (current !== viewport) current.recycle()
        }
    }

    fun decode(bytes: ByteArray): Bitmap {
        require(bytes.isNotEmpty() && bytes.size <= PanelLimits.MAX_BYTES)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outWidth in 1..PanelLimits.MAX_SIDE && bounds.outHeight in 1..PanelLimits.MAX_SIDE)
        return requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
    }
}

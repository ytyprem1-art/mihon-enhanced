package eu.kanade.tachiyomi.ui.mod.panelshare

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CropOptionsTest {
    @Test
    fun `middle top handle changes height without moving either horizontal edge`() {
        val crop = DocumentCrop(.1, 2.0, .9, 4.0)
        assertEquals(DocumentCrop(.1, 1.0, .9, 4.0), resizeCropHandle(crop, CropHandle.Top, .75, 1.0, 10.0))
    }

    @Test
    fun `middle bottom handle reaches chapter end without sideways drift`() {
        val crop = DocumentCrop(0.0, 2.0, 1.0, 4.0)
        assertEquals(DocumentCrop(0.0, 2.0, 1.0, 10.0), resizeCropHandle(crop, CropHandle.Bottom, .2, 20.0, 10.0))
    }

    @Test
    fun `top and bottom handles never cross the opposite side`() {
        val crop = DocumentCrop(.1, 2.0, .9, 4.0)
        assertTrue(resizeCropHandle(crop, CropHandle.Top, .5, 5.0, 10.0).height > 0)
        assertTrue(resizeCropHandle(crop, CropHandle.Bottom, .5, 0.0, 10.0).height > 0)
    }

    @Test
    fun `returning from wallpaper fits the full comic width with no blank side`() {
        val viewport = customCropViewport(DocumentCrop(.2, 5.0, .8, 7.0), 2.0, 10.0)
        assertEquals(CropViewport(1.0, 0.0, 5.0), viewport)
    }

    @Test
    fun `returning to custom stays inside short or end of document`() {
        assertEquals(0.0, customCropViewport(DocumentCrop(.1, 0.0, .9, .8), 2.0, 1.0).top)
        assertEquals(8.0, customCropViewport(DocumentCrop(.1, 9.0, .9, 10.0), 2.0, 10.0).top)
    }

    @Test
    fun `device wallpaper tolerates float frame rounding when image exactly fills width`() {
        assertEquals(267.768798828125, boundedCropOffset(0.0, 267.768798828125, 267.76873779296875))
    }

    @Test
    fun `offset clamps normally at both image edges`() {
        assertEquals(-5.0, boundedCropOffset(-10.0, -5.0, 5.0))
        assertEquals(5.0, boundedCropOffset(10.0, -5.0, 5.0))
        assertEquals(2.0, boundedCropOffset(2.0, -5.0, 5.0))
    }

    @Test
    fun `very thin source strip still produces valid selection`() {
        val crop = DocumentCrop(.1, .1, .9, .8).clamped(1.0 / 65535)
        assertTrue(crop.height > 0 && crop.bottom <= 1.0 / 65535)
    }

    @Test
    fun `original preserves all color and alpha bits`() {
        val pixel = 0x7F83A21C
        assertEquals(pixel, CropEnhancement.Original.pixel(pixel, 0, -1, 0, -1))
    }

    @Test
    fun `black and white produces equal channels and keeps alpha`() {
        val center = 0x7F2AA5E8
        val value = CropEnhancement.BlackWhite.pixel(center, center, center, center, center)
        assertEquals(0x7F, value ushr 24)
        assertEquals(value and 255, (value ushr 8) and 255)
        assertEquals(value and 255, (value ushr 16) and 255)
    }

    @Test
    fun `all presets clamp highlights and shadows without overflow`() {
        for (preset in CropEnhancement.entries) {
            assertEquals(-1, preset.pixel(-1, 0, 0, 0, 0))
            assertEquals(0xFF000000.toInt(), preset.pixel(0xFF000000.toInt(), -1, -1, -1, -1))
        }
    }

    @Test
    fun `strong enhances edge contrast more than light`() {
        val center = 0xFFB4B4B4.toInt()
        val neighbor = 0xFF646464.toInt()
        fun result(preset: CropEnhancement) = preset.pixel(center, neighbor, neighbor, neighbor, neighbor) and 255
        assertTrue(result(CropEnhancement.Strong) > result(CropEnhancement.Light))
        assertNotEquals(result(CropEnhancement.Original), result(CropEnhancement.Light))
    }

    @Test
    fun `custom selection crosses source image boundaries without viewport clipping`() {
        val crop = DocumentCrop(.1, 1.5, .9, 5.5).clamped(8.0)
        assertEquals(800 to 4000, crop.dimensions(1000))
        assertEquals(5.5, crop.bottom)
    }

    @Test
    fun `selection at document edge remains nonzero and inside source`() {
        val crop = DocumentCrop(-1.0, 100.0, 3.0, 120.0).clamped(4.0)
        assertTrue(crop.top >= 0 && crop.bottom <= 4.0 && crop.height > 0)
        assertTrue(crop.left >= 0 && crop.right <= 1 && crop.width > 0)
    }

    @Test
    fun `wallpaper ratios are portrait and distinct`() {
        assertEquals(.45, CropTemplate.Phone20.ratio)
        assertEquals(9.0 / 19.5, CropTemplate.Phone195.ratio)
        assertEquals(.5625, CropTemplate.Phone16.ratio)
        assertTrue(CropTemplate.entries.mapNotNull { it.ratio }.all { it > 0 && it < 1 })
    }
}

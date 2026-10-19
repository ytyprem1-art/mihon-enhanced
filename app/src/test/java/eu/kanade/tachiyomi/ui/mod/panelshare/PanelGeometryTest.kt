package eu.kanade.tachiyomi.ui.mod.panelshare

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class PanelGeometryTest {
    @Test
    fun `selection maps to bitmap coordinates instead of density pixels`() {
        assertEquals(PanelPixels(200, 600, 1000, 1500), PanelSelection(.1f, .2f, .6f, .7f).pixels(2000, 3000))
    }

    @Test
    fun `selection clamps offscreen and accepts reverse drag`() {
        assertEquals(PanelPixels(0, 0, 800, 600), PanelSelection(2f, 3f, -1f, -1f).pixels(800, 600))
    }

    @Test
    fun `selection at bottom right stays inside bitmap and is never zero sized`() {
        assertEquals(PanelPixels(799, 599, 1, 1), PanelSelection(1f, 1f, 1f, 1f).pixels(800, 600))
    }

    @Test
    fun `long panel scales both axes without changing aspect ratio`() {
        assertEquals(200 to 800, PanelLimits.scaledSize(500, 2000))
        assertEquals(800 to 200, PanelLimits.scaledSize(2000, 500))
    }

    @Test
    fun `small face crop is never enlarged`() {
        assertEquals(120 to 90, PanelLimits.scaledSize(120, 90))
    }

    @Test
    fun `extreme aspect ratio retains at least one pixel`() {
        assertEquals(1 to 800, PanelLimits.scaledSize(1, 100000))
        assertThrows(IllegalArgumentException::class.java) { PanelLimits.scaledSize(0, 100) }
    }

    @Test
    fun `retains two hundred messages while initial chat query remains fifty`() {
        assertEquals(200L, PanelLimits.RETAINED_MESSAGES)
        assertEquals(50L, PanelLimits.INITIAL_MESSAGES)
    }
}

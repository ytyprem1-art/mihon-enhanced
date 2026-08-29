package eu.kanade.tachiyomi.ui.mod.util

import androidx.compose.ui.graphics.Color
import kotlin.math.abs

object SourceColorUtil {
    private val palette = listOf(
        Color(0xFFE57373), // Red
        Color(0xFFF06292), // Pink
        Color(0xFFBA68C8), // Purple
        Color(0xFF9575CD), // Deep Purple
        Color(0xFF7986CB), // Indigo
        Color(0xFF64B5F6), // Blue
        Color(0xFF4FC3F7), // Light Blue
        Color(0xFF4DD0E1), // Cyan
        Color(0xFF4DB6AC), // Teal
        Color(0xFF81C784), // Green
        Color(0xFFAED581), // Light Green
        Color(0xFFFFB74D), // Orange
        Color(0xFFFF8A65), // Deep Orange
    )

    fun getColorForSource(sourceId: Long?): Color? {
        if (sourceId == null || sourceId == -1L) return null
        val index = abs(sourceId.hashCode()) % palette.size
        return palette[index]
    }

    fun getColorsForSources(sourceIds: List<Long>): Map<Long, Color> {
        val result = mutableMapOf<Long, Color>()
        val usedIndices = mutableSetOf<Int>()

        // Sort source IDs to keep color assignment deterministic for the same set of sources
        sourceIds.sorted().forEach { sourceId ->
            var index = abs(sourceId.hashCode()) % palette.size
            // Collision resolution: find next available color in palette
            while (usedIndices.contains(index) && usedIndices.size < palette.size) {
                index = (index + 1) % palette.size
            }
            result[sourceId] = palette[index]
            usedIndices.add(index)
        }
        return result
    }
}

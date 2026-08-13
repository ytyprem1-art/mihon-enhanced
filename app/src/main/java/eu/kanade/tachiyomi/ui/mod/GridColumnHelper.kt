package eu.kanade.tachiyomi.ui.mod

import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.ui.unit.dp

object GridColumnHelper {
    fun getColumns(enhancedGridColumns: Int, defaultColumns: Int): GridCells {
        return when (enhancedGridColumns) {
            0 -> if (defaultColumns == 0) GridCells.Adaptive(128.dp) else GridCells.Fixed(defaultColumns)
            else -> GridCells.Fixed(enhancedGridColumns)
        }
    }

    fun getColumnsInt(enhancedGridColumns: Int, defaultColumns: Int): Int {
        return if (enhancedGridColumns == 0) defaultColumns else enhancedGridColumns
    }
}

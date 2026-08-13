package eu.kanade.tachiyomi.ui.mod

import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore

class EnhancedPreferences(
    private val preferenceStore: PreferenceStore,
) {
    val gridColumns: Preference<Int> = preferenceStore.getInt("enhanced_grid_columns", 0)

    companion object {
        const val GRID_AUTO = 0
    }
}

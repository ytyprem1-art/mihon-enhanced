package eu.kanade.tachiyomi.ui.mod

import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore

class EnhancedPreferences(
    private val preferenceStore: PreferenceStore,
) {
    val gridColumns: Preference<Int> = preferenceStore.getInt("enhanced_grid_columns", 0)

    val lastDismissedWelcomeVersion: Preference<Int> = preferenceStore.getInt("enhanced_last_dismissed_welcome_version", 0)

    val globalChatUsername: Preference<String> = preferenceStore.getString("enhanced_global_chat_username", "")

    val avEnabled: Preference<Boolean> = preferenceStore.getBoolean("enhanced_av_enabled", false)
    val avCode: Preference<String> = preferenceStore.getString("enhanced_av_code", "")
    val avUnlocked: Preference<Boolean> = preferenceStore.getBoolean("enhanced_av_unlocked", false)

    companion object {
        const val GRID_AUTO = 0
    }
}

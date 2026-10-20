package eu.kanade.tachiyomi.ui.mod.chaptercomments

import kotlinx.serialization.json.Json
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore

/** Device-local selections, just like the public websites' local reaction choices. */
class ChapterReactionSelections(preferences: PreferenceStore) {
    private val stored = preferences.getString(Preference.appStateKey("chapter_comment_reactions_v1"), "{}")
    private val legacy = preferences.getStringSet(Preference.appStateKey("chapter_comment_upvotes_v1"))

    private fun selections(): Map<String, String> = runCatching { Json.decodeFromString<Map<String, String>>(stored.get()) }.getOrDefault(emptyMap())

    fun get(identity: String, options: List<ChapterReactionOption>): String? {
        val values = selections()
        val value = values[identity]
        if (identity in values) return value?.takeIf { saved -> options.any { it.key == saved } }
        return if (identity in legacy.get()) options.firstOrNull { it.key in setOf("reaction0", "upvote", "like", "UPVOTE") }?.key else null
    }

    fun set(identity: String, selection: String?) {
        // Keep explicit empty entries so old upvote records cannot revive after deselection.
        stored.set(Json.encodeToString(selections() + (identity to selection.orEmpty())))
        legacy.set(legacy.get() - identity)
    }
}

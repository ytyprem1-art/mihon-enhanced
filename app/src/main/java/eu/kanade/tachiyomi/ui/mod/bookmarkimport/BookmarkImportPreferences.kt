package eu.kanade.tachiyomi.ui.mod.bookmarkimport

import kotlinx.serialization.Serializable
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore

class BookmarkImportPreferences(
    val preferenceStore: PreferenceStore,
) {
    val importSession: Preference<String> = preferenceStore.getString(
        Preference.appStateKey("bookmark_import_session"),
        ""
    )
}

@Serializable
data class BookmarkImportSession(
    val sessionId: String,
    val fileName: String,
    val entries: List<ManganatoCsvParser.BookmarkEntry>,
    val timestamp: Long = System.currentTimeMillis()
)

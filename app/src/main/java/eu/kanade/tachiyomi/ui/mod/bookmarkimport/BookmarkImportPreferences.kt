package eu.kanade.tachiyomi.ui.mod.bookmarkimport

import kotlinx.serialization.Serializable
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore

@dev.zacsweers.metro.Inject
@dev.zacsweers.metro.SingleIn(dev.zacsweers.metro.AppScope::class)
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

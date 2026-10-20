package eu.kanade.tachiyomi.ui.mod.personalfavorites

import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore

class PersonalFavoriteStore(preferences: PreferenceStore) {
    // Exported explicitly as mod data, never as an ordinary setting.
    private val preference = preferences.getStringSet(Preference.appStateKey("personal_favorites_v1"))

    fun snapshot(): Set<PersonalFavorite> = decode(preference.get())

    fun changes() = preference.changes().map(::decode)

    @Synchronized
    fun toggle(identity: PersonalFavorite) {
        val current = snapshot()
        write(if (identity in current) current - identity else current + identity)
    }

    /** Restore merges marks without removing existing marks; old backups have no marks. */
    @Synchronized
    fun restore(favorites: Collection<PersonalFavorite>) = write(snapshot() + favorites)

    private fun write(favorites: Set<PersonalFavorite>) {
        preference.set(favorites.map { Json.encodeToString(PersonalFavorite.serializer(), it) }.toSet())
    }

    private fun decode(values: Set<String>): Set<PersonalFavorite> = values.map {
        Json.decodeFromString(PersonalFavorite.serializer(), it)
    }.toSet()
}

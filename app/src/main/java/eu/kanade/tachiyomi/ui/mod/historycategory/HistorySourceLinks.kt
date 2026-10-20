package eu.kanade.tachiyomi.ui.mod.historycategory

import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore

@dev.zacsweers.metro.Inject
@dev.zacsweers.metro.SingleIn(dev.zacsweers.metro.AppScope::class)
class HistorySourceLinks(preferenceStore: PreferenceStore) {
    // Category IDs belong to this installation; do not restore them as app settings on another device.
    private val preference = preferenceStore.getStringSet(Preference.appStateKey("history_category_source_links"))

    fun snapshot(): Map<Long, Long> = preference.get().mapNotNull { value ->
        val source = value.substringBefore(':').toLongOrNull()
        val category = value.substringAfter(':').toLongOrNull()
        if (source != null && category != null && category > 0) source to category else null
    }.toMap()

    @Synchronized
    fun save(categoryId: Long, sourceIds: Set<Long>) {
        require(categoryId > 0)
        val links = snapshot().toMutableMap()
        links.entries.removeAll { it.value == categoryId }
        sourceIds.forEach { sourceId ->
            if (sourceId !in links) links[sourceId] = categoryId
        }
        preference.set(links.map { (source, category) -> "$source:$category" }.toSet())
    }

    fun removeCategory(categoryId: Long) = save(categoryId, emptySet())
}

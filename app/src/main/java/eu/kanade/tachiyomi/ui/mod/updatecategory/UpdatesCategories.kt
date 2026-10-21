package eu.kanade.tachiyomi.ui.mod.updatecategory

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoNumber
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore

@Serializable
data class UpdatesCategory(val id: Long, val name: String, val sources: Set<Long> = emptySet())

@Serializable
data class UpdatesCategoryConfig(val categories: List<UpdatesCategory> = emptyList(), val selected: Long = 0) {
    fun includes(sourceId: Long): Boolean {
        val active = categories.firstOrNull { it.id == selected }
        return if (active == null) categories.none { sourceId in it.sources } else sourceId in active.sources
    }
}

/** Backup stores category names and stable source IDs, never installation-local category IDs. */
@Serializable
data class UpdatesCategoryBackup(
    @ProtoNumber(1) val name: String,
    @ProtoNumber(2) val sources: List<Long> = emptyList(),
)

@Inject
@SingleIn(AppScope::class)
class UpdatesCategories(store: PreferenceStore) {
    private val preference = store.getString(Preference.appStateKey("updates_source_categories"), "{}")
    private val json = Json { ignoreUnknownKeys = true }
    val changes = preference.changes().map { decode(it) }
    fun snapshot(): UpdatesCategoryConfig = decode(preference.get())
    private fun decode(
        value: String,
    ) = runCatching { json.decodeFromString<UpdatesCategoryConfig>(value) }.getOrDefault(UpdatesCategoryConfig())
    private fun write(config: UpdatesCategoryConfig) = preference.set(json.encodeToString(config))

    @Synchronized fun select(id: Long) {
        val current = snapshot()
        write(current.copy(selected = id.takeIf { value -> current.categories.any { it.id == value } } ?: 0))
    }

    @Synchronized fun save(id: Long?, name: String, sources: Set<Long>): Long {
        val current = snapshot()
        val trimmed = name.trim()
        require(trimmed.isNotEmpty()) { "Nama kategori tidak boleh kosong." }
        require(
            current.categories.none {
                it.id != id && it.name.equals(trimmed, true)
            },
        ) { "Nama kategori sudah dipakai." }
        require(id == null || current.categories.any { it.id == id }) { "Kategori sudah dihapus." }
        val categoryId = id ?: ((current.categories.maxOfOrNull { it.id } ?: 0) + 1)
        val assignedElsewhere = current.categories.filter { it.id != categoryId }.flatMap { it.sources }.toSet()
        val category = UpdatesCategory(categoryId, trimmed, sources - assignedElsewhere)
        write(
            current.copy(
                categories = if (id ==
                    null
                ) {
                    current.categories + category
                } else {
                    current.categories.map { if (it.id == id) category else it }
                },
            ),
        )
        return categoryId
    }

    @Synchronized fun delete(id: Long) {
        val current = snapshot()
        write(
            current.copy(
                categories = current.categories.filterNot { it.id == id },
                selected =
                current.selected.takeUnless { it == id } ?: 0,
            ),
        )
    }

    @Synchronized fun move(id: Long, offset: Int) {
        val current = snapshot()
        val list = current.categories.toMutableList()
        val index = list.indexOfFirst { it.id == id }
        if (index < 0 || index + offset !in list.indices) return
        list.add(index + offset, list.removeAt(index))
        write(current.copy(categories = list))
    }

    fun backup(enabled: Boolean) = if (enabled) {
        snapshot().categories.map {
            UpdatesCategoryBackup(it.name, it.sources.sorted())
        }
    } else {
        emptyList()
    }

    @Synchronized fun restore(categories: List<UpdatesCategoryBackup>, enabled: Boolean) {
        if (!enabled || categories.isEmpty()) return
        val current = snapshot()
        var nextId = current.categories.maxOfOrNull { it.id } ?: 0
        val merged = mergeBackups(backup(true), categories).map { category ->
            val existing = current.categories.firstOrNull { it.name.equals(category.name, true) }
            UpdatesCategory(existing?.id ?: ++nextId, category.name, category.sources.toSet())
        }
        write(current.copy(categories = merged))
    }

    companion object {
        /** Existing/local source ownership wins a conflict; no source is placed in two tabs. */
        fun mergeBackups(
            local: List<UpdatesCategoryBackup>,
            remote: List<UpdatesCategoryBackup>,
        ): List<UpdatesCategoryBackup> {
            val groups = mutableListOf<UpdatesCategoryBackup>()
            val owners = mutableSetOf<Long>()
            (local + remote).forEach { incoming ->
                val name = incoming.name.trim().takeIf { it.isNotEmpty() } ?: return@forEach
                val index = groups.indexOfFirst { it.name.equals(name, true) }
                val existing = groups.getOrNull(index)
                val additions = incoming.sources.distinct().filter { it !in owners }
                owners.addAll(additions)
                val value = UpdatesCategoryBackup(existing?.name ?: name, existing?.sources.orEmpty() + additions)
                if (index < 0) groups.add(value) else groups[index] = value
            }
            return groups
        }
    }
}

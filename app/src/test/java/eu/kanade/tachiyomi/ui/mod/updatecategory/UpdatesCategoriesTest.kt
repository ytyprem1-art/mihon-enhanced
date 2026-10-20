package eu.kanade.tachiyomi.ui.mod.updatecategory

import eu.kanade.tachiyomi.data.backup.create.BackupOptions
import eu.kanade.tachiyomi.data.backup.restore.RestoreOptions
import eu.kanade.tachiyomi.data.backup.models.Backup
import kotlinx.serialization.protobuf.ProtoBuf
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.core.common.preference.PreferenceStore

class UpdatesCategoriesTest {
    private val backing = InMemoryPreferenceStore.InMemoryPreference("categories", "{}", "{}")
    private val prefs = object : PreferenceStore by InMemoryPreferenceStore() {
        override fun getString(key: String, defaultValue: String) = backing
    }
    @Test fun `linked sources immediately route existing and future updates and general excludes them`() {
        val store = UpdatesCategories(prefs)
        assertTrue(store.snapshot().includes(10))
        val adult = store.save(null, "Manhwa 18+", setOf(10, 20))
        assertFalse(store.snapshot().includes(10))
        assertFalse(store.snapshot().includes(20))
        assertTrue(store.snapshot().includes(30))
        store.select(adult)
        val reopened = UpdatesCategories(prefs)
        assertTrue(reopened.snapshot().includes(10))
        assertTrue(reopened.snapshot().includes(20))
        assertFalse(reopened.snapshot().includes(30))
        assertEquals(adult, reopened.snapshot().selected)
        reopened.delete(adult)
        assertEquals(0L, reopened.snapshot().selected)
        assertTrue(reopened.snapshot().includes(10))
    }
    @Test fun `source ownership and naming are unique while rename reorder and unlink preserve membership`() {
        val store = UpdatesCategories(prefs)
        val a = store.save(null, "Adult", setOf(10))
        val b = store.save(null, "Other", setOf(10, 20))
        assertEquals(setOf(20L), store.snapshot().categories[1].sources)
        assertThrows(IllegalArgumentException::class.java) { store.save(null, " adult ", emptySet()) }
        store.save(a, "Manhwa 18+", setOf(10, 30))
        store.move(b, -1)
        assertEquals(listOf("Other", "Manhwa 18+"), store.snapshot().categories.map { it.name })
        store.save(a, "Manhwa 18+", emptySet())
        assertTrue(store.snapshot().includes(10))
    }
    @Test fun `protobuf backup restores name source links order to fresh local IDs including missing extensions`() {
        val store = UpdatesCategories(prefs)
        store.save(null, "Manhwa 18+", setOf(99, 100))
        store.save(null, "English", setOf(200))
        val bytes = ProtoBuf.encodeToByteArray(Backup.serializer(), Backup(emptyList(), updatesCategories = store.backup(true)))
        val decoded = ProtoBuf.decodeFromByteArray(Backup.serializer(), bytes)
        val target = UpdatesCategories(InMemoryPreferenceStore())
        target.save(null, "Existing", setOf(300))
        target.restore(decoded.updatesCategories, true)
        val restored = target.snapshot().categories
        assertEquals(listOf("Existing", "Manhwa 18+", "English"), restored.map { it.name })
        assertEquals(setOf(99L, 100L), restored[1].sources)
        assertNotEquals(store.snapshot().categories[0].id, restored[1].id)
        target.select(restored[1].id)
        assertTrue(target.snapshot().includes(99))
        assertFalse(target.snapshot().includes(200))
        val before = target.snapshot()
        target.restore(decoded.updatesCategories, true)
        assertEquals(before, target.snapshot())
    }
    @Test fun `old backups and disabled restore do not erase categories and backup option is independent`() {
        val store = UpdatesCategories(prefs)
        store.save(null, "Adult", setOf(10))
        assertTrue(store.backup(false).isEmpty())
        store.restore(listOf(UpdatesCategoryBackup("New", listOf(20))), false)
        val old = ProtoBuf.decodeFromByteArray(Backup.serializer(), ProtoBuf.encodeToByteArray(Backup.serializer(), Backup(emptyList())))
        store.restore(old.updatesCategories, true)
        assertEquals(listOf("Adult"), store.snapshot().categories.map { it.name })
        assertTrue(BackupOptions.fromBooleanArray(BooleanArray(15)).modUpdatesCategories)
        assertTrue(RestoreOptions.fromBooleanArray(BooleanArray(10)).modUpdatesCategories)
        assertFalse(BackupOptions.fromBooleanArray(BackupOptions(modUpdatesCategories = false).asBooleanArray()).modUpdatesCategories)
        assertFalse(RestoreOptions.fromBooleanArray(RestoreOptions(modUpdatesCategories = false).asBooleanArray()).modUpdatesCategories)
    }
    @Test fun `sync merge preserves local ownership while adding remote categories and links without duplicates`() {
        val merged = UpdatesCategories.mergeBackups(listOf(UpdatesCategoryBackup("Adult", listOf(10))), listOf(
            UpdatesCategoryBackup("adult", listOf(10, 20)), UpdatesCategoryBackup("Other", listOf(10, 30)),
        ))
        assertEquals(listOf(UpdatesCategoryBackup("Adult", listOf(10, 20)), UpdatesCategoryBackup("Other", listOf(30))), merged)
        assertEquals(merged, UpdatesCategories.mergeBackups(merged, merged))
    }
}

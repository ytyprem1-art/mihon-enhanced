package eu.kanade.tachiyomi.ui.mod.personalfavorites

import eu.kanade.tachiyomi.data.backup.create.BackupOptions
import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.backup.restore.RestoreOptions
import kotlinx.serialization.protobuf.ProtoBuf
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.core.common.preference.PreferenceStore

class PersonalFavoriteTest {
    private fun store() = PersonalFavoriteStore(InMemoryPreferenceStore())

    @Test
    fun `toggle preserves exact URLs and distinguishes sources`() {
        val store = store()
        val first = PersonalFavorite(1, "/manga/a:b?x=1&y=dua kata")
        val other = first.copy(source = 2)
        store.toggle(first)
        store.toggle(other)
        assertEquals(setOf(first, other), store.snapshot())
        store.toggle(first)
        assertEquals(setOf(other), store.snapshot())
    }

    @Test
    fun `backup restores marks into a clean store and is idempotent`() {
        val source = store()
        val mark = PersonalFavorite(Long.MIN_VALUE, "/manga/test")
        source.toggle(mark)
        val encoded = ProtoBuf.encodeToByteArray(
            Backup.serializer(),
            Backup(
                backupManga = emptyList(),
                personalFavorites = PersonalFavoriteBackup.snapshot(true, source),
            ),
        )
        val decoded = ProtoBuf.decodeFromByteArray(Backup.serializer(), encoded)
        val destination = store()
        PersonalFavoriteBackup.restore(decoded.personalFavorites, true, destination)
        PersonalFavoriteBackup.restore(decoded.personalFavorites, true, destination)
        assertEquals(setOf(mark), destination.snapshot())
    }

    @Test
    fun `old backup with no favorites preserves device marks`() {
        val encoded = ProtoBuf.encodeToByteArray(Backup.serializer(), Backup(emptyList()))
        val decoded = ProtoBuf.decodeFromByteArray(Backup.serializer(), encoded)
        assertTrue(decoded.personalFavorites.isEmpty())
        val destination = store()
        val local = PersonalFavorite(4, "/local")
        destination.toggle(local)
        PersonalFavoriteBackup.restore(decoded.personalFavorites, true, destination)
        assertEquals(setOf(local), destination.snapshot())
    }

    @Test
    fun `backup and restore honor unchecked favorite option`() {
        val source = store()
        source.toggle(PersonalFavorite(1, "/star"))
        assertTrue(PersonalFavoriteBackup.snapshot(false, source).isEmpty())
        val destination = store()
        PersonalFavoriteBackup.restore(source.snapshot().toList(), false, destination)
        assertTrue(destination.snapshot().isEmpty())
    }

    @Test
    fun `restore merges existing marks from different sources`() {
        val destination = store()
        val existing = PersonalFavorite(1, "/same")
        val incoming = PersonalFavorite(2, "/same")
        destination.toggle(existing)
        destination.restore(listOf(incoming))
        assertEquals(setOf(existing, incoming), destination.snapshot())
    }

    @Test
    fun `personal favorite serialization never adds manga to library`() {
        val manga = BackupManga(source = 1, url = "/test", title = "Test", favorite = false)
        val encoded = ProtoBuf.encodeToByteArray(
            Backup.serializer(),
            Backup(
                backupManga = listOf(manga),
                personalFavorites = listOf(PersonalFavorite(1, "/test")),
            ),
        )
        val decoded = ProtoBuf.decodeFromByteArray(Backup.serializer(), encoded)
        assertFalse(decoded.backupManga.single().favorite)
        assertEquals(PersonalFavorite(1, "/test"), decoded.personalFavorites.single())
    }

    @Test
    fun `option arrays keep old indices and roundtrip favorite flag`() {
        assertFalse(
            BackupOptions.fromBooleanArray(
                BackupOptions(modPersonalFavorites = false).asBooleanArray(),
            ).modPersonalFavorites,
        )
        assertFalse(
            RestoreOptions.fromBooleanArray(
                RestoreOptions(modPersonalFavorites = false).asBooleanArray(),
            ).modPersonalFavorites,
        )
        assertTrue(BackupOptions.fromBooleanArray(BooleanArray(14)).modPersonalFavorites)
        assertTrue(RestoreOptions.fromBooleanArray(BooleanArray(9)).modPersonalFavorites)
    }

    @Test
    fun `removed marks stay removed after reopening store and backing up again`() {
        val backing = InMemoryPreferenceStore.InMemoryPreference<Set<String>>("test", null, emptySet())
        val preferences = object : PreferenceStore by InMemoryPreferenceStore() {
            override fun getStringSet(key: String, defaultValue: Set<String>) = backing
        }
        val mark = PersonalFavorite(42, "/removed")
        PersonalFavoriteStore(preferences).toggle(mark)
        assertEquals(setOf(mark), PersonalFavoriteStore(preferences).snapshot())
        PersonalFavoriteStore(preferences).toggle(mark)
        val reopened = PersonalFavoriteStore(preferences)
        assertTrue(reopened.snapshot().isEmpty())
        val bytes = ProtoBuf.encodeToByteArray(
            Backup.serializer(),
            Backup(
                emptyList(),
                personalFavorites = PersonalFavoriteBackup.snapshot(true, reopened),
            ),
        )
        val destination = store()
        PersonalFavoriteBackup.restore(
            ProtoBuf.decodeFromByteArray(Backup.serializer(), bytes).personalFavorites,
            true,
            destination,
        )
        assertTrue(destination.snapshot().isEmpty())
    }

    @Test
    fun `restoring an earlier backup deliberately restores its saved star`() {
        val destination = store()
        val mark = PersonalFavorite(42, "/removed-later")
        destination.toggle(mark)
        val earlierBackup = PersonalFavoriteBackup.snapshot(true, destination)
        destination.toggle(mark)
        assertTrue(destination.snapshot().isEmpty())
        PersonalFavoriteBackup.restore(earlierBackup, true, destination)
        assertEquals(setOf(mark), destination.snapshot())
    }
}

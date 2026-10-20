package eu.kanade.tachiyomi.ui.mod.updatewatch

import eu.kanade.tachiyomi.data.backup.models.*
import kotlinx.serialization.protobuf.ProtoBuf
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore

class UpdateWatchDismissalsTest {
    private val dismissed = UpdateWatchDismissal(100, "/series", listOf("/ch/171"), 171.0)
    private fun inbox(number: Double, source: Long = 100) = BackupUpdateWatchInboxItem(
        BackupModMember(source, "/series"), "Series", "Source", 1, "Ch. $number", 1, 1,
        "/ch/${number.toInt()}", number,
    )

    @Test fun `same update remains dismissed after sync but next chapter is allowed`() {
        assertTrue(UpdateWatchDismissals.filterBackupInbox(listOf(inbox(171.0)), listOf(dismissed)).isEmpty())
        assertEquals(172.0, UpdateWatchDismissals.filterBackupInbox(listOf(inbox(172.0)), listOf(dismissed)).single().latestChapterNumber)
    }
    @Test fun `corrected chapter URL does not revive an already dismissed number`() {
        assertTrue(UpdateWatchDismissals.filterBackupInbox(listOf(inbox(171.0).copy(latestChapterUrl = "/renamed/171")), listOf(dismissed)).isEmpty())
        assertEquals(1, UpdateWatchDismissals.filterBackupInbox(listOf(inbox(171.0, 101)), listOf(dismissed)).size)
    }
    @Test fun `dismissals merge monotonically across devices`() {
        val newer = dismissed.copy(chapterUrls = listOf("/ch/172"), throughChapterNumber = 172.0)
        val merged = UpdateWatchDismissals.merge(listOf(newer), listOf(dismissed)).single()
        assertEquals(172.0, merged.throughChapterNumber)
        assertEquals(setOf("/ch/171", "/ch/172"), merged.chapterUrls.toSet())
    }
    @Test fun `store reopening and backup restore preserve dismissal without ordinary app settings`() {
        val backing = InMemoryPreferenceStore.InMemoryPreference<Set<String>>("dismissals", null, emptySet())
        val prefs = object : tachiyomi.core.common.preference.PreferenceStore by InMemoryPreferenceStore() {
            override fun getStringSet(key: String, defaultValue: Set<String>) = backing
        }
        val source = UpdateWatchDismissalStore(prefs)
        source.restore(listOf(dismissed))
        assertEquals(listOf(dismissed), UpdateWatchDismissalStore(prefs).snapshot())
        val bytes = ProtoBuf.encodeToByteArray(Backup.serializer(), Backup(emptyList(), updateWatchDismissals = source.snapshot()))
        val restored = UpdateWatchDismissalStore(InMemoryPreferenceStore())
        restored.restore(ProtoBuf.decodeFromByteArray(Backup.serializer(), bytes).updateWatchDismissals)
        assertEquals(source.snapshot(), restored.snapshot())
    }
    @Test fun `legacy backups default to no dismissals and do not suppress updates`() {
        val bytes = ProtoBuf.encodeToByteArray(Backup.serializer(), Backup(emptyList()))
        val decoded = ProtoBuf.decodeFromByteArray(Backup.serializer(), bytes)
        assertTrue(decoded.updateWatchDismissals.isEmpty())
        assertEquals(1, UpdateWatchDismissals.filterBackupInbox(listOf(inbox(171.0)), decoded.updateWatchDismissals).size)
    }
    @Test fun `unknown chapter numbers use stable URLs instead of blocking all specials`() {
        val mark = dismissed.copy(throughChapterNumber = 0.0)
        assertTrue(mark.excludes("/ch/171", -1.0))
        assertFalse(mark.excludes("/new-special", -1.0))
    }
    @Test fun `new inactivity cycle is allowed even when it reaches the same milestone`() {
        val mark = dismissed.copy(warningMilestone = 56, warningFoundAt = 100L)
        val nextCycle = inbox(0.0).copy(type = 1, milestone = 28, lastFoundAt = 200L)
        assertEquals(1, UpdateWatchDismissals.filterBackupInbox(listOf(nextCycle), listOf(mark)).size)
    }
    @Test fun `warning dismissal suppresses only covered inactivity milestones`() {
        val mark = dismissed.copy(warningMilestone = 28, warningFoundAt = 1L)
        val warning = inbox(0.0).copy(type = 1, milestone = 28)
        assertTrue(UpdateWatchDismissals.filterBackupInbox(listOf(warning), listOf(mark)).isEmpty())
        assertEquals(1, UpdateWatchDismissals.filterBackupInbox(listOf(warning.copy(milestone = 56, lastFoundAt = 2L)), listOf(mark)).size)
    }
}

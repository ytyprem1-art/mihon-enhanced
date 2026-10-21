package eu.kanade.tachiyomi.ui.mod.updatewatch

import eu.kanade.tachiyomi.ui.mod.updatewatch.helper.UpdateWatchRefreshHelper
import eu.kanade.tachiyomi.ui.mod.updatewatch.helper.UpdateWatchSlotHelper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import java.time.ZoneId

class UpdateWatchSlotTest {

    private val zoneId = ZoneId.of("UTC")

    @Test
    fun `test HOT slots`() {
        // at 01:30, current slot = 00:00 and next slot = 02:00
        val t0130 = LocalDateTime.of(2024, 5, 8, 1, 30).atZone(zoneId).toInstant().toEpochMilli()
        assertEquals(
            LocalDateTime.of(2024, 5, 8, 0, 0).atZone(zoneId).toInstant().toEpochMilli(),
            UpdateWatchSlotHelper.getCurrentSlot(UpdateWatchRefreshHelper.PriorityBucket.HOT, t0130, zoneId),
        )
        assertEquals(
            LocalDateTime.of(2024, 5, 8, 2, 0).atZone(zoneId).toInstant().toEpochMilli(),
            UpdateWatchSlotHelper.getNextSlot(UpdateWatchRefreshHelper.PriorityBucket.HOT, t0130, zoneId),
        )

        // at exactly 02:00, current slot = 02:00 and next slot = 04:00
        val t0200 = LocalDateTime.of(2024, 5, 8, 2, 0).atZone(zoneId).toInstant().toEpochMilli()
        assertEquals(
            t0200,
            UpdateWatchSlotHelper.getCurrentSlot(UpdateWatchRefreshHelper.PriorityBucket.HOT, t0200, zoneId),
        )
        assertEquals(
            LocalDateTime.of(2024, 5, 8, 4, 0).atZone(zoneId).toInstant().toEpochMilli(),
            UpdateWatchSlotHelper.getNextSlot(UpdateWatchRefreshHelper.PriorityBucket.HOT, t0200, zoneId),
        )

        // at 23:30, next slot = next day 00:00
        val t2330 = LocalDateTime.of(2024, 5, 8, 23, 30).atZone(zoneId).toInstant().toEpochMilli()
        assertEquals(
            LocalDateTime.of(2024, 5, 9, 0, 0).atZone(zoneId).toInstant().toEpochMilli(),
            UpdateWatchSlotHelper.getNextSlot(UpdateWatchRefreshHelper.PriorityBucket.HOT, t2330, zoneId),
        )
    }

    @Test
    fun `test WARM slots`() {
        // at 05:00, current slot = 04:00 and next slot = 08:00
        val t0500 = LocalDateTime.of(2024, 5, 8, 5, 0).atZone(zoneId).toInstant().toEpochMilli()
        assertEquals(
            LocalDateTime.of(2024, 5, 8, 4, 0).atZone(zoneId).toInstant().toEpochMilli(),
            UpdateWatchSlotHelper.getCurrentSlot(UpdateWatchRefreshHelper.PriorityBucket.WARM, t0500, zoneId),
        )
        assertEquals(
            LocalDateTime.of(2024, 5, 8, 8, 0).atZone(zoneId).toInstant().toEpochMilli(),
            UpdateWatchSlotHelper.getNextSlot(UpdateWatchRefreshHelper.PriorityBucket.WARM, t0500, zoneId),
        )
    }

    @Test
    fun `test COLD slots`() {
        // at 09:00, current slot = 00:00 and next slot = 12:00
        val t0900 = LocalDateTime.of(2024, 5, 8, 9, 0).atZone(zoneId).toInstant().toEpochMilli()
        assertEquals(
            LocalDateTime.of(2024, 5, 8, 0, 0).atZone(zoneId).toInstant().toEpochMilli(),
            UpdateWatchSlotHelper.getCurrentSlot(UpdateWatchRefreshHelper.PriorityBucket.COLD, t0900, zoneId),
        )
        assertEquals(
            LocalDateTime.of(2024, 5, 8, 12, 0).atZone(zoneId).toInstant().toEpochMilli(),
            UpdateWatchSlotHelper.getNextSlot(UpdateWatchRefreshHelper.PriorityBucket.COLD, t0900, zoneId),
        )

        // at 17:00, current slot = 12:00 and next slot = next day 00:00
        val t1700 = LocalDateTime.of(2024, 5, 8, 17, 0).atZone(zoneId).toInstant().toEpochMilli()
        assertEquals(
            LocalDateTime.of(2024, 5, 8, 12, 0).atZone(zoneId).toInstant().toEpochMilli(),
            UpdateWatchSlotHelper.getCurrentSlot(UpdateWatchRefreshHelper.PriorityBucket.COLD, t1700, zoneId),
        )
        assertEquals(
            LocalDateTime.of(2024, 5, 9, 0, 0).atZone(zoneId).toInstant().toEpochMilli(),
            UpdateWatchSlotHelper.getNextSlot(UpdateWatchRefreshHelper.PriorityBucket.COLD, t1700, zoneId),
        )
    }

    @Test
    fun `test STALE slots`() {
        // at 10:00, current slot = today 00:00 and next slot = next day 00:00
        val t1000 = LocalDateTime.of(2024, 5, 8, 10, 0).atZone(zoneId).toInstant().toEpochMilli()
        assertEquals(
            LocalDateTime.of(2024, 5, 8, 0, 0).atZone(zoneId).toInstant().toEpochMilli(),
            UpdateWatchSlotHelper.getCurrentSlot(UpdateWatchRefreshHelper.PriorityBucket.STALE, t1000, zoneId),
        )
        assertEquals(
            LocalDateTime.of(2024, 5, 9, 0, 0).atZone(zoneId).toInstant().toEpochMilli(),
            UpdateWatchSlotHelper.getNextSlot(UpdateWatchRefreshHelper.PriorityBucket.STALE, t1000, zoneId),
        )
    }

    @Test
    fun `test missed slots behavior`() {
        // if HOT was last processed at the 02:00 slot and now is 07:30,
        // only the latest elapsed slot 06:00 is due.
        val t0200Slot = LocalDateTime.of(2024, 5, 8, 2, 0).atZone(zoneId).toInstant().toEpochMilli()
        val t0730 = LocalDateTime.of(2024, 5, 8, 7, 30).atZone(zoneId).toInstant().toEpochMilli()

        assertTrue(UpdateWatchSlotHelper.isDue(UpdateWatchRefreshHelper.PriorityBucket.HOT, t0730, t0200Slot, zoneId))

        // After processing, lastProcessedSlotAt would be set to 06:00
        val t0600Slot = LocalDateTime.of(2024, 5, 8, 6, 0).atZone(zoneId).toInstant().toEpochMilli()
        assertFalse(UpdateWatchSlotHelper.isDue(UpdateWatchRefreshHelper.PriorityBucket.HOT, t0730, t0600Slot, zoneId))

        // Next slot should be 08:00
        assertEquals(
            LocalDateTime.of(2024, 5, 8, 8, 0).atZone(zoneId).toInstant().toEpochMilli(),
            UpdateWatchSlotHelper.getNextSlot(UpdateWatchRefreshHelper.PriorityBucket.HOT, t0730, zoneId),
        )
    }

    @Test
    fun `test next worker target base`() {
        val t0500 = LocalDateTime.of(2024, 5, 8, 5, 0).atZone(zoneId).toInstant().toEpochMilli()
        val buckets = setOf(UpdateWatchRefreshHelper.PriorityBucket.HOT, UpdateWatchRefreshHelper.PriorityBucket.WARM)

        // HOT next slot: 06:00
        // WARM next slot: 08:00
        // Earliest: 06:00
        assertEquals(
            LocalDateTime.of(2024, 5, 8, 6, 0).atZone(zoneId).toInstant().toEpochMilli(),
            UpdateWatchSlotHelper.getNextWorkerTargetBase(buckets, t0500, zoneId),
        )
    }
}

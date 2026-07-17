package dev.jmx.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import dev.jmx.client.core.api.DailyCheckInfo
import dev.jmx.client.core.api.DailyRecord
import java.util.Calendar

class AccountLogicTest {
    @Test
    fun dailyCalendarUsesMondayFirstAndCleansEventPrefix() {
        val now = Calendar.getInstance().apply { set(2026, Calendar.JULY, 16) }
        val month = currentMonthCalendar(
            DailyCheckInfo(
                dailyId = 70,
                eventName = "7月-同人祭",
                currentProgress = "3",
                records = listOf(DailyRecord("2026-07-16", signed = true, bonus = false)),
                raw = emptyMap(),
            ),
            now,
        )

        assertEquals(42, month.cells.size)
        assertEquals(2, month.cells.indexOfFirst { it.day == 1 })
        assertEquals(true, month.cells.first { it.day == 16 }.today)
        assertEquals(true, month.cells.first { it.day == 16 }.signed)
        assertEquals("同人祭", cleanDailyEventName("7月-同人祭", 7))
    }

    @Test
    fun avatarUrlSupportsRelativeAndAbsoluteSources() {
        assertEquals(
            "https://img.test/media/users/nopic-Male.gif?v=0",
            resolveUserAvatarUrl("https://img.test/", "nopic-Male.gif?v=0"),
        )
        assertEquals(
            "https://cdn.test/avatar.jpg",
            resolveUserAvatarUrl("https://img.test", "https://cdn.test/avatar.jpg"),
        )
        assertNull(resolveUserAvatarUrl("https://img.test", " "))
    }

    @Test
    fun accountProgressAcceptsPlatformPercentAndCounts() {
        assertEquals(0.7595f, accountProgress(3190, 4200, 75.95), 0.0001f)
        assertEquals(0.008f, accountProgress(8, 1000), 0.0001f)
        assertEquals(1f, accountProgress(10, 5), 0f)
    }

    @Test
    fun cacheSizeUsesReadableBinaryUnits() {
        assertEquals("0 B", formatByteCount(0))
        assertEquals("1 KB", formatByteCount(1_024))
        assertEquals("1.5 MB", formatByteCount(1_572_864))
        assertEquals("2 GB", formatByteCount(2_147_483_648))
        assertEquals(96L * 1024L * 1024L, IMAGE_DISK_CACHE_MAX_BYTES)
    }

    @Test
    fun signedTodayAcceptsFullDateAndDayOnlyRecords() {
        val now = Calendar.getInstance().apply { set(2026, Calendar.JULY, 18, 1, 0, 0) }.time
        val fullDate = DailyCheckInfo(
            dailyId = 70,
            eventName = null,
            currentProgress = null,
            records = listOf(DailyRecord("2026-07-18", signed = true, bonus = false)),
            raw = emptyMap(),
        )
        val dayOnly = fullDate.copy(records = listOf(DailyRecord("18", signed = true, bonus = false)))
        val monthAndDay = fullDate.copy(records = listOf(DailyRecord("07-18", signed = true, bonus = false)))
        val unsigned = fullDate.copy(records = listOf(DailyRecord("18", signed = false, bonus = false)))

        assertEquals(true, fullDate.isSignedToday(now))
        assertEquals(true, dayOnly.isSignedToday(now))
        assertEquals(true, monthAndDay.isSignedToday(now))
        assertEquals(false, unsigned.isSignedToday(now))
    }

    @Test
    fun dailyStreakComesFromSignedRecordsInsteadOfPlatformProgress() {
        val now = Calendar.getInstance().apply { set(2026, Calendar.JULY, 16) }
        val info = DailyCheckInfo(
            dailyId = 70,
            eventName = "同人祭",
            currentProgress = "28.6%",
            records = listOf(
                DailyRecord("13", signed = true, bonus = false),
                DailyRecord("14", signed = false, bonus = false),
                DailyRecord("15", signed = true, bonus = false),
                DailyRecord("16", signed = true, bonus = false),
            ),
            raw = emptyMap(),
        )

        assertEquals(2, currentDailyStreak(info, now))
    }

    @Test
    fun dailyRewardProgressStartsANewCycleAfterSevenDays() {
        assertEquals(0, dailyRewardCycleProgress(0))
        assertEquals(3, dailyRewardCycleProgress(3))
        assertEquals(7, dailyRewardCycleProgress(7))
        assertEquals(1, dailyRewardCycleProgress(8))
        assertEquals(7, dailyRewardCycleProgress(14))
    }
}

package dev.jmx.client

import dev.jmx.client.core.api.ActionResult
import dev.jmx.client.core.api.DailyCheckInfo
import dev.jmx.client.core.api.DailyRecord
import dev.jmx.client.core.result.JmxError
import dev.jmx.client.core.result.JmxResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class V2FiveWorkflowTest {
    private fun action(msg: String, status: String? = null) = ActionResult(status, msg, null, emptyMap())
    private fun info(signed: Boolean) = DailyCheckInfo(7, null, null,
        listOf(DailyRecord(todayDate(), signed, false)), raw = emptyMap())

    @Test fun checkInClassifiesMessagesWithoutDefaultingToSuccess() {
        assertEquals(CheckInAcceptance.SUBMITTED, action("Jcoin:10 EXP:10").checkInAcceptance())
        assertEquals(CheckInAcceptance.ALREADY_SIGNED, action("今天已经签到过了").checkInAcceptance())
        assertEquals(CheckInAcceptance.ALREADY_SIGNED, action("今天已經簽到過了").checkInAcceptance())
        assertEquals(CheckInAcceptance.REJECTED, action("账号异常").checkInAcceptance())
        assertEquals(CheckInAcceptance.REJECTED, action("签到未成功").checkInAcceptance())
        assertEquals(CheckInAcceptance.REJECTED, action("").checkInAcceptance())
    }

    @Test fun acceptedSubmissionNeedsFreshRecord() = runBlocking {
        var reads = 0
        var writes = 0
        val result = confirmedCheckIn({ JmxResult.Success(info(++reads == 2)) }) {
            writes++; action("Jcoin:10 EXP:10").let { JmxResult.Success(it) }
        }
        assertTrue(result is JmxResult.Success)
        assertEquals(2, reads)
        assertEquals(1, writes)
    }

    @Test fun acceptedButUnconfirmedDoesNotComplete() = runBlocking {
        val result = confirmedCheckIn({ JmxResult.Success(info(false)) }) { JmxResult.Success(action("签到成功")) }
        assertTrue(result is JmxResult.Failure)
    }

    @Test fun alreadyRecordedNeverPosts() = runBlocking {
        var writes = 0
        val result = confirmedCheckIn({ JmxResult.Success(info(true)) }) { writes++; JmxResult.Success(action("签到成功")) }
        assertTrue(result is JmxResult.Success)
        assertEquals(0, writes)
    }

    @Test fun ambiguousWriteIsReadBackAndNeverRepeated() = runBlocking {
        var reads = 0
        var writes = 0
        val result = confirmedCheckIn({ JmxResult.Success(info(++reads == 2)) }) {
            writes++; JmxResult.Failure(JmxError.EmptyData("ambiguous"))
        }
        assertTrue(result is JmxResult.Success)
        assertEquals(1, writes)
    }

    @Test fun emptyActivityDoesNotSubmit() = runBlocking {
        var writes = 0
        val result = confirmedCheckIn({ JmxResult.Failure(JmxError.EmptyData("ambiguous")) }) {
            writes++; JmxResult.Success(action("签到成功"))
        }
        assertTrue(result is JmxResult.Failure)
        assertEquals(0, writes)
    }

    @Test fun clearHistoryRequiresEmptyReadEvenAtExactBudget() = runBlocking {
        val items = mutableListOf("1", "2")
        val result = clearHistoryVerified({ JmxResult.Success(items.take(1)) }, { id -> items.remove(id); JmxResult.Success(Unit) }, maxBatches = 2)
        assertTrue(result is JmxResult.Success)
        assertTrue(items.isEmpty())
    }

    @Test fun successfulWriteWithUnchangedHistoryMustFail() = runBlocking {
        val result = clearHistoryVerified({ JmxResult.Success(listOf("1")) }, { JmxResult.Success(Unit) })
        assertTrue(result is JmxResult.Failure)
    }

    @Test fun ambiguousDeleteMayCompleteOnlyAfterReadBack() = runBlocking {
        var exists = true
        val result = clearHistoryVerified({ JmxResult.Success(if (exists) listOf("1") else emptyList()) }, {
            exists = false; JmxResult.Failure(JmxError.EmptyData("ambiguous"))
        })
        assertTrue(result is JmxResult.Success)
    }

    @Test fun historyCancellationPropagates() = runBlocking {
        try {
            clearHistoryVerified({ JmxResult.Success(listOf("1")) }, { throw CancellationException("stop") })
            fail("cancellation swallowed")
        } catch (_: CancellationException) { }
    }

    @Test fun updateAcknowledgementPreservesLaterChaptersAndOtherBooks() {
        val clicked = AlbumUpdateRecord("1", 10, 12, 1)
        val newer = clicked.copy(latestChapterCount = 13, checkedAt = 2)
        val other = AlbumUpdateRecord("2", 3, 4, 1)
        val result = acknowledgeAlbumUpdates(mapOf("1" to newer, "2" to other), mapOf("1" to clicked))
        assertEquals(1, result.getValue("1").pendingChapters)
        assertEquals(other, result["2"])
        assertEquals(2L, result.getValue("1").checkedAt)
        assertEquals(result, acknowledgeAlbumUpdates(result, mapOf("1" to clicked)))
    }

    @Test fun oldAcknowledgementCannotTouchRecreatedRecord() {
        val old = AlbumUpdateRecord("1", 2, 3, 1)
        val recreated = AlbumUpdateRecord("1", 4, 5, 10)
        assertEquals(recreated, acknowledgeAlbumUpdates(mapOf("1" to recreated), mapOf("1" to old))["1"])
    }

    @Test fun staleObservationCannotOverwriteNewDetailButFreshDeletionIsAllowed() {
        val current = AlbumUpdateRecord("1", 13, 13, 20)
        assertEquals(current, mergeAlbumObservation(current, "1", 12, 10))
        assertEquals(11, mergeAlbumObservation(current, "1", 11, 30).latestChapterCount)
    }

    @Test fun pendingUpdatesStayAheadInBothDirections() {
        val old = BookshelfEntry("1", "Old", "", "", "", 1)
        val newer = old.copy(albumId = "2", name = "New", addedAt = 10, updatedAt = 10)
        val record = mapOf("1" to AlbumUpdateRecord("1", 1, 2, 20))
        BookshelfSortDirection.entries.forEach { direction ->
            assertEquals("1", projectBookshelf(listOf(newer, old), ALL_BOOKSHELF_GROUP_ID, BookshelfSortOrder.UPDATED, direction, record).first().albumId)
        }
        assertEquals("2", projectBookshelf(listOf(newer, old), ALL_BOOKSHELF_GROUP_ID, BookshelfSortOrder.ADDED, BookshelfSortDirection.ASCENDING, record).first().albumId)
    }

    @Test fun selectedDeletesPreservePartialFailureAndRequireReadBack() = runBlocking {
        val result = deleteHistoryVerified(
            ids = linkedSetOf("1", "2"),
            remove = { if (it == "1") JmxResult.Success(Unit) else JmxResult.Failure(JmxError.Api(400, "synthetic rejection")) },
            loadPage = { JmxResult.Success(AlbumHistoryIds(if (it == 1) listOf("2") else emptyList())) },
        )
        assertEquals(setOf("1"), result.succeededIds)
        assertEquals(setOf("2"), result.failures.keys)
    }

    @Test fun selectedDeletesStopOnAccountSwitchAndDoNotReadAnotherAccount() = runBlocking {
        var current = true
        var reads = 0
        var writes = 0
        val result = deleteHistoryVerified(
            ids = linkedSetOf("1", "2"),
            remove = { writes++; current = false; JmxResult.Success(Unit) },
            loadPage = { reads++; JmxResult.Success(AlbumHistoryIds(emptyList())) },
            isCurrent = { current },
        )
        assertTrue(result.succeededIds.isEmpty())
        assertEquals(2, result.failures.size)
        assertEquals(1, writes)
        assertEquals(0, reads)
    }

    @Test fun repeatedVerificationPageCannotProveAbsence() = runBlocking {
        val result = deleteHistoryVerified(setOf("1"), { JmxResult.Success(Unit) }, { JmxResult.Success(AlbumHistoryIds(listOf("3"))) })
        assertTrue(result.succeededIds.isEmpty())
        assertEquals(setOf("1"), result.failures.keys)
    }

    @Test fun clearBudgetLeavesRemainingRecordsAndDoesNotClaimCompletion() = runBlocking {
        val items = mutableListOf("1", "2")
        val result = clearHistoryVerified({ JmxResult.Success(items.take(1)) }, { items.remove(it); JmxResult.Success(Unit) }, maxBatches = 1)
        assertTrue(result is JmxResult.Failure)
        assertEquals(listOf("2"), items)
    }

    @Test fun verificationFailureDoesNotBecomeDeleteSuccess() = runBlocking {
        val result = deleteHistoryVerified(setOf("1"), { JmxResult.Success(Unit) }, { JmxResult.Failure(JmxError.Schema("synthetic malformed read")) })
        assertTrue(result.succeededIds.isEmpty())
        assertTrue(result.failures.getValue("1") is JmxError.Schema)
    }

    @Test fun oldMonthFullDateCannotMatchOnlyTodaysDayNumber() {
        val now = java.util.Calendar.getInstance().apply { set(2026, java.util.Calendar.JULY, 18) }.time
        val stale = info(true).copy(records = listOf(DailyRecord("2026-06-18", true, false)))
        assertFalse(stale.isSignedToday(now))
    }

    @Test fun navigationRailUsesAvailableWidth() {
        assertFalse(shouldUseNavigationRail(500f, 300f))
        assertTrue(shouldUseNavigationRail(800f, 400f))
        assertTrue(shouldUseNavigationRail(900f, 1200f))
        assertFalse(shouldUseNavigationRail(400f, 800f))
    }
}

package app.prismia.plus.core.download

import app.prismia.plus.core.chapter.ChapterImageTransferReport
import app.prismia.plus.core.image.ImageDownloadRequest
import app.prismia.plus.core.image.ImageOutputKey
import app.prismia.plus.core.image.ImageOutputRecord
import app.prismia.plus.core.image.ImagePipeline
import app.prismia.plus.core.image.ImageRestoreBatchItem
import app.prismia.plus.core.image.ImageRestoreBatchResult
import app.prismia.plus.core.image.ImageRestoreResult
import app.prismia.plus.core.image.ImageStoreBatchItem
import app.prismia.plus.core.image.ImageStoreBatchResult
import app.prismia.plus.core.result.JmxError
import app.prismia.plus.core.result.JmxResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class ChapterDownloadResumeSupportTest {
    @Test
    fun doesNotTreatSiblingIndexStemAsCompletedViaContains() {

        val existing = listOf("00000_00010_66f3999eb1cf.webp")
        assertTrue(
            ChapterDownloadResumeSupport.isOutputPresentForImage(existing, "00010.webp")
        )
        assertFalse(
            ChapterDownloadResumeSupport.isOutputPresentForImage(existing, "00009.webp")
        )
        assertFalse(
            ChapterDownloadResumeSupport.isOutputPresentForImage(existing, "0001.webp")
        )
    }

    @Test
    fun existingCompletedNamesUsesExactSafeStemToken() {
        val dir = Files.createTempDirectory("jmx-resume-match")
        Files.writeString(dir.resolve("00000_00010_abcdef012345.webp"), "x")
        val found = ChapterDownloadResumeSupport.existingCompletedNames(
            dir,
            listOf("00009.webp", "00010.webp", "00011.webp")
        )
        assertEquals(setOf("00010.webp"), found)
    }

    @Test
    fun successfullyStoredRequiresBothRestoreAndStoreSuccess() {
        fun restore(file: String) = ImageRestoreResult(
            plan = ImagePipeline().plan("https://img.test/media/photos/1/$file", 100, 220980),
            download = DownloadResult("https://x/$file", 200, "image/webp", 3, 3),
            bytes = byteArrayOf(1, 2, 3),
            contentType = "image/webp",
            restored = true
        )
        val r0 = restore("00001.webp")
        val r1 = restore("00002.webp")
        val restoreResults = listOf(
            ImageRestoreBatchResult(
                item = ImageRestoreBatchItem(
                    index = 0,
                    request = ImageDownloadRequest("https://x/00001.webp", 100, 220980)
                ),
                result = JmxResult.Success(r0)
            ),
            ImageRestoreBatchResult(
                item = ImageRestoreBatchItem(
                    index = 1,
                    request = ImageDownloadRequest("https://x/00002.webp", 100, 220980)
                ),
                result = JmxResult.Success(r1)
            )
        )
        val storeResults = listOf(
            ImageStoreBatchResult(
                item = ImageStoreBatchItem(0, r0),
                result = JmxResult.Success(
                    ImageOutputRecord(
                        key = ImageOutputKey(0, "00001", r0.plan.cacheKey),
                        sourceUrl = r0.plan.sourceUrl,
                        contentType = "image/webp",
                        byteCount = 3,
                        restored = true
                    )
                )
            ),
            ImageStoreBatchResult(
                item = ImageStoreBatchItem(1, r1),
                result = JmxResult.Failure(JmxError.Schema("disk full", field = "image.output"))
            )
        )
        val report = ChapterImageTransferReport(restoreResults, storeResults)
        val done = ChapterDownloadResumeSupport.successfullyStoredFileNames(
            workImageFileNames = listOf("00001.webp", "00002.webp"),
            report = report
        )

        assertEquals(listOf("00001.webp"), done)
    }
}

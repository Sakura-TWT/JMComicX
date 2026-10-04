package app.prismia.data

import app.prismia.foundation.*
import app.prismia.video.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class VideoDetailRepositoryTest {
    private val key = ContentKey(ContentType.VIDEO, ContentSource.ORENO3D, "42")
    private val indexed = VideoWork(key, "Indexed title", author = "Author", tags = listOf("ja-tag"),
        sourceLinks = mapOf(ContentSource.ORENO3D to "42", ContentSource.IWARA to "w1"))

    @Test fun joinsByStableIdentityWhileKeepingTheEntryKey() = runBlocking {
        val repository = VideoDetailRepository(
            VideoDetailProvider { VideoDetail(indexed) },
            VideoDetailProvider { requested ->
                assertEquals(ContentKey(ContentType.VIDEO, ContentSource.IWARA, "w1"), requested)
                VideoDetail(VideoWork(requested, "Source title", durationMs = 1000, availability = VideoAvailability.PLAYABLE,
                    tags = listOf("en-tag"), sourceLinks = mapOf(ContentSource.IWARA to "w1")),
                    listOf(StreamVariant("720", "https://cdn.example.test/file")))
            },
        )
        val detail = repository.detailPage(key)
        assertEquals(key, detail.work.key)
        assertEquals("Indexed title", detail.work.title)
        assertEquals(1000L, detail.work.durationMs)
        assertEquals(listOf("ja-tag", "en-tag"), detail.work.tags)
        assertEquals(VideoAvailability.PLAYABLE, detail.work.availability)
        assertEquals(1, detail.variants.size)
    }

    @Test fun missingAssociationDoesNotGuessOrRequestAnotherVideo() = runBlocking {
        val repository = VideoDetailRepository(
            VideoDetailProvider { VideoDetail(indexed.copy(sourceLinks = emptyMap())) },
            VideoDetailProvider { error("must not request Iwara without an ID") },
        )
        assertEquals(VideoAvailability.NO_SOURCE, repository.detailPage(key).work.availability)
    }

    @Test fun deletedSourceRetainsIndexMetadataAndReportsDiagnostic() = runBlocking {
        val repository = VideoDetailRepository(VideoDetailProvider { VideoDetail(indexed) }, VideoDetailProvider {
            throw SourceFailureException(SourceFailure(ContentSource.IWARA, "get", SourceErrorCategory.HTTP,
                httpStatus = 404, message = "missing"))
        })
        val result = repository.detailPage(key)
        assertEquals("Indexed title", result.work.title)
        assertEquals(VideoAvailability.TOMBSTONED, result.work.availability)
        assertEquals(404, result.failures.single().httpStatus)
        assertTrue(result.variants.isEmpty())
    }

    @Test fun cancellationIsNeverReportedAsMissingContent() = runBlocking {
        val repository = VideoDetailRepository(VideoDetailProvider { VideoDetail(indexed) }, VideoDetailProvider { throw CancellationException() })
        assertTrue(runCatching { repository.detailPage(key) }.exceptionOrNull() is CancellationException)
    }
}

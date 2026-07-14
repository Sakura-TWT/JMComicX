package dev.jmx.client.core.security

import dev.jmx.client.core.cache.InMemoryKeyValueStore
import dev.jmx.client.core.download.ChapterDownloadTaskState
import dev.jmx.client.core.download.PersistedChapterDownloadTask
import dev.jmx.client.core.session.PersistentCookieStore
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.nio.file.Files

class EncryptingStoreTest {
    @Test
    fun encryptingKeyValueStoreRoundTripAndHidesCleartext() {
        val secret = "avs-super-secret-value-xyz"
        val memory = InMemoryKeyValueStore()
        val key = AesGcmByteCipher.randomKey()
        val cipher = AesGcmByteCipher(key)
        val encryptedKv = EncryptingKeyValueStore(memory, cipher)
        val cookies = PersistentCookieStore(encryptedKv)
        val url = "https://api.test/".toHttpUrl()
        cookies.save(
            url,
            listOf(
                Cookie.Builder()
                    .name("AVS")
                    .value(secret)
                    .hostOnlyDomain("api.test")
                    .path("/")
                    .build()
            )
        )
        val raw = memory.getString("session.cookies")
        requireNotNull(raw)
        assertFalse(raw.contains(secret))
        val loaded = cookies.load("https://api.test/album".toHttpUrl())
        assertEquals(1, loaded.size)
        assertEquals(secret, loaded.single().value)
    }

    @Test
    fun encryptingTaskStoreFileDoesNotContainCleartextIdsInCipherBlob() {
        val path = Files.createTempFile("enc-tasks-", ".bin")
        val marker = "chapter-secret-marker-778899"
        val cipher = AesGcmByteCipher(AesGcmByteCipher.randomKey())
        val store = EncryptingChapterDownloadTaskStore(path, cipher)
        store.save(
            PersistedChapterDownloadTask(
                id = "task-1",
                albumId = "1",
                chapterId = marker,
                shunt = "1",
                outputDirectory = "/tmp/out",
                maxImages = 2,
                maxImageBytes = 1000L,
                state = ChapterDownloadTaskState.Pending.name,
                progressTotal = null,
                progressCompleted = null,
                progressFailed = null,
                progressBytesWritten = null,
                errorMessage = null,
                completedImageFileNames = emptyList(),
                createdAtMillis = 1L,
                updatedAtMillis = 1L
            )
        )
        val disk = Files.readAllBytes(path)
        val asText = String(disk, StandardCharsets.ISO_8859_1)
        assertFalse(asText.contains(marker))
        val loaded = store.loadAll()
        assertEquals(1, loaded.size)
        assertEquals(marker, loaded.single().chapterId)
        assertTrue(Files.size(path) > 0)
    }
}

package dev.jmx.client

import java.io.File
import java.nio.file.Files
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineDirectoryTest {
    @Test
    fun oldCheckRejectsDirectoryCreatedByAnotherWorker() {
        val root = Files.createTempDirectory("jmx-directory-race").toFile()
        try {
            val directory = File(root, "10/11")
            val observedMissing = !directory.isDirectory
            assertTrue(directory.mkdirs())
            assertTrue(observedMissing)
            assertFalse(directory.mkdirs())
            assertTrue(directory.isDirectory)
        } finally { root.deleteRecursively() }
    }

    @Test
    fun simultaneousFirstPagesAndNextChapterAllPrepareSuccessfully() {
        val root = Files.createTempDirectory("jmx-directory-concurrent").toFile()
        val pool = Executors.newFixedThreadPool(4)
        try {
            for (chapter in listOf("11", "12")) {
                val directory = File(root, "10/$chapter")
                val barrier = CyclicBarrier(4)
                val results = (0..3).map { index ->
                    pool.submit(Callable {
                        barrier.await(5, TimeUnit.SECONDS)
                        OfflineFiles.ensureDirectory(directory)
                        File(directory, "$index.part").writeText("page $index")
                        true
                    })
                }
                assertTrue(results.all { it.get(5, TimeUnit.SECONDS) })
                assertEquals(4, directory.listFiles()!!.size)
            }
        } finally { pool.shutdownNow(); root.deleteRecursively() }
    }

    @Test
    fun directoryCreatedBetweenCheckAndMkdirIsAccepted() {
        val root = Files.createTempDirectory("jmx-directory-interleaving").toFile()
        try {
            val actual = File(root, "10/11")
            val raced = object : File(actual.path) {
                override fun mkdirs(): Boolean {
                    assertTrue(actual.mkdirs())
                    return false
                }
            }
            OfflineFiles.ensureDirectory(raced)
            assertTrue(actual.isDirectory)
        } finally { root.deleteRecursively() }
    }

    @Test(expected = OfflineStorageException::class)
    fun regularFileAtDirectoryPathIsNotAccepted() {
        val root = Files.createTempDirectory("jmx-directory-file").toFile()
        try {
            val target = File(root, "10").apply { writeText("preserve me") }
            try { OfflineFiles.ensureDirectory(target) }
            finally { assertEquals("preserve me", target.readText()) }
        } finally { root.deleteRecursively() }
    }
}

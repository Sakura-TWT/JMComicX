package dev.jmx.client.core.runtime

import dev.jmx.client.core.cache.InMemoryKeyValueStore
import dev.jmx.client.core.network.DefaultRetryPolicy
import dev.jmx.client.core.result.JmxError
import dev.jmx.client.core.result.JmxResult
import dev.jmx.client.core.session.SessionManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.zip.ZipInputStream

class JmxDiagnosticExportTest {
    @Test
    fun exportBytesIsRedactedAndContainsExpectedEntries() {
        val core = JmxCore.create(
            JmxCoreConfig(
                keyValueStore = InMemoryKeyValueStore(),
                retryPolicy = DefaultRetryPolicy(maxAttempts = 1),
                domainServerUrls = emptyList()
            )
        )

        core.sessionManager.installAvsCookie("https://api.test", "super-secret-avs-value-xyz")

        val issues = listOf(
            JmxDiagnosticIssue(
                step = "login",
                severity = JmxDiagnosticSeverity.Error,
                message = "password=hunter2 token=abc AVS=super-secret-avs-value-xyz",
                error = JmxError.Http(code = 401, message = "password=hunter2 AVS=super-secret-avs-value-xyz")
            )
        )
        val result = JmxDiagnosticExporter(core).exportBytes(
            JmxDiagnosticExportRequest(issues = issues)
        )
        assertTrue(result is JmxResult.Success)
        val (bytes, names) = (result as JmxResult.Success).value
        assertTrue(names.contains("summary.md"))
        assertTrue(names.contains("health.txt"))
        assertTrue(names.contains("cookies-meta.txt"))
        assertTrue(names.contains("download-tasks.txt"))
        assertTrue(names.contains("redaction-note.txt"))

        val texts = unzipToStrings(bytes)
        val joined = texts.values.joinToString("\n")
        assertFalse(joined.contains("super-secret-avs-value-xyz"))
        assertFalse(joined.contains("hunter2"))
        assertFalse(joined.contains("password=hunter2"))

        val cookieMeta = texts["cookies-meta.txt"].orEmpty()
        assertTrue(cookieMeta.contains("name=AVS"))
        assertTrue(cookieMeta.contains("valueLength="))
        assertFalse(cookieMeta.contains("super-secret"))
    }

    @Test
    fun exportToDirectoryWritesZipFile() {
        val core = JmxCore.create(
            JmxCoreConfig(domainServerUrls = emptyList(), retryPolicy = DefaultRetryPolicy(1))
        )
        val dir = Files.createTempDirectory("jmx-diag")
        val exported = core.exportDiagnostics(dir)
        assertTrue(exported is JmxResult.Success)
        val path = (exported as JmxResult.Success).value.archivePath
        assertTrue(Files.isRegularFile(path))
        assertTrue(Files.size(path) > 0)
        assertEquals(true, path.fileName.toString().startsWith("jmx-diagnostics-"))
    }

    private fun unzipToStrings(bytes: ByteArray): Map<String, String> {
        val out = linkedMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                out[entry.name] = zip.readBytes().toString(StandardCharsets.UTF_8)
                zip.closeEntry()
            }
        }
        return out
    }
}

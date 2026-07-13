package dev.jmx.client.core.chapter

import dev.jmx.client.core.result.JmxResult
import dev.jmx.client.core.result.JmxError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChapterTemplateParserTest {
    private val parser = ChapterTemplateParser()

    @Test
    fun parsesConstLetAndVarTemplate() {
        val html = """
            <script>
            let result = {"images":["00001.webp","00002.jpg"]};
            const config = {"imghost":"https://img.test","jmid":"123456","cache":"?v=1"};
            var aid = 654321;
            let scramble_id = 220980;
            const speed = '0';
            </script>
        """.trimIndent()

        val result = parser.parse(html)

        assertTrue(result is JmxResult.Success)
        val value = (result as JmxResult.Success).value
        assertEquals(654321, value.albumId)
        assertEquals(220980, value.scrambleId)
        assertEquals("0", value.speed)
        assertEquals("https://img.test/media/photos/123456/00001.webp?v=1", value.imageUrls.first())
    }

    @Test
    fun reportsSchemaFailureWhenImagesMissing() {
        val html = """
            const result = {"images":[]};
            const config = {"imghost":"https://img.test","jmid":"123456","cache":""};
            var aid = 1;
            var scramble_id = 1;
            var speed = '1';
        """.trimIndent()

        val result = parser.parse(html)

        assertTrue(result is JmxResult.Failure)
        val error = (result as JmxResult.Failure).error
        assertTrue(error is JmxError.Schema)
        assertEquals("result.images", (error as JmxError.Schema).field)
        assertTrue(error.message.contains("缺失字段：result.images"))
        assertTrue(error.message.contains("scramble_id"))
        assertTrue(error.message.contains("HTML 样本"))
    }

    @Test
    fun parsesNestedObjectsWithoutTruncatingResult() {
        val html = """
            var result = {"images":["00001.webp"],"meta":{"nested":true}};
            var config = {"imghost":"https://img.test","jmid":"654321","cache":""};
            var aid = 300000;
            var scramble_id = 1;
            var speed = "1";
        """.trimIndent()

        val result = parser.parse(html)

        assertTrue(result is JmxResult.Success)
        val value = (result as JmxResult.Success).value
        assertEquals(listOf("00001.webp"), value.imageFileNames)
        assertEquals("1", value.speed)
    }

    @Test
    fun parsesAssignmentsWithoutTrailingSemicolon() {
        val html = """
            const result = {"images":["00001.webp"]}
            const config = {"imghost":"https://img.test","jmid":"654321","cache":"?v=2"}
            let aid = 300000
            let scramble_id = 1
            let speed = "1"
        """.trimIndent()

        val result = parser.parse(html)

        assertTrue(result is JmxResult.Success)
        val value = (result as JmxResult.Success).value
        assertEquals(300000, value.albumId)
        assertEquals("https://img.test/media/photos/654321/00001.webp?v=2", value.imageUrls.single())
    }

    @Test
    fun inspectsMissingTemplateFields() {
        val html = """
            const result = {"images":[]};
            const config = {"imghost":"https://img.test"};
            var aid = 300000;
        """.trimIndent()

        val inspection = parser.inspect(html)

        assertTrue(inspection.hasResultObject)
        assertTrue(inspection.hasConfigObject)
        assertEquals(0, inspection.imageCount)
        assertEquals(
            listOf("result.images", "config.jmid", "config.cache", "scramble_id", "speed"),
            inspection.missingFields
        )
    }

    @Test
    fun diagnosticsCompactsHtmlSample() {
        val html = """
            <html>
              <script>
                const config = {"imghost":"https://img.test"};
              </script>
            </html>
        """.trimIndent()

        val diagnostics = parser.diagnostics(html)

        assertEquals(listOf("result", "result.images", "config.jmid", "config.cache", "aid", "scramble_id", "speed"), diagnostics.missingFields)
        assertTrue(!diagnostics.htmlSample.contains("\n"))
        assertTrue(diagnostics.htmlSample.contains("const config"))
    }

    @Test
    fun parseFailureIncludesDiagnosticsForMissingScalarField() {
        val html = """
            const result = {"images":["00001.webp"]};
            const config = {"imghost":"https://img.test","jmid":"123456","cache":""};
            var aid = 300000;
            var speed = "1";
        """.trimIndent()

        val result = parser.parse(html)

        assertTrue(result is JmxResult.Failure)
        val error = (result as JmxResult.Failure).error
        assertTrue(error is JmxError.Schema)
        assertEquals("scramble_id", (error as JmxError.Schema).field)
        assertTrue(error.message, error.message.contains("缺失字段：scramble_id"))
        assertTrue(error.message.contains("const result"))
    }

    @Test
    fun scalarVariableNamesDoNotMatchIdentifierSuffixes() {
        val html = """
            const result = {"images":["00001.webp"]};
            const config = {"imghost":"https://img.test","jmid":"123456","cache":""};
            var scramble_id = 1;
            var speed = "1";
        """.trimIndent()

        val result = parser.parse(html)

        assertTrue(result is JmxResult.Failure)
        val error = (result as JmxResult.Failure).error
        assertTrue(error is JmxError.Schema)
        assertEquals("aid", (error as JmxError.Schema).field)
        assertTrue(error.message, error.message.contains("缺失字段：aid"))
    }
}

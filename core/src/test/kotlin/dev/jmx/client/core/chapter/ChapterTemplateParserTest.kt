package dev.jmx.client.core.chapter

import dev.jmx.client.core.result.JmxResult
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
}

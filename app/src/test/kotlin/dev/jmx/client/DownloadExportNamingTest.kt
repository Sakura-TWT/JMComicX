package dev.jmx.client

import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadExportNamingTest {
    @Test
    fun exportNamesCannotCreatePaths() {
        assertEquals("a_b_c", downloadExportName("a/b\\c", "fallback"))
        assertEquals("fallback", downloadExportName(".. ", "fallback"))
        assertEquals("name", downloadExportName(" name. ", "fallback"))
    }

    @Test
    fun chapterOrderUsesPaddedIndexRatherThanTitleOrdering() {
        assertEquals("0001_最终章", downloadChapterExportName(0, "最终章"))
        assertEquals("0011_第十章", downloadChapterExportName(10, "第十章"))
    }
}

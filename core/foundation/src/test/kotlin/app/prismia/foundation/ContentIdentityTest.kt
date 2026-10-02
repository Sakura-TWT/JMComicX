package app.prismia.foundation

import org.junit.Assert.assertThrows
import org.junit.Test

class ContentIdentityTest {
    @Test
    fun rejectsBlankRemoteIds() {
        assertThrows(IllegalArgumentException::class.java) {
            ContentKey(ContentType.VIDEO, ContentSource.IWARA, " ")
        }
    }
}

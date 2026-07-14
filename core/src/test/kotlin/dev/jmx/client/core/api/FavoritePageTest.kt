package dev.jmx.client.core.api

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Test

class FavoritePageTest {
    @Test
    fun parsesFoldersAndListFromPythonShape() {
        val json = """
            {
              "list":[{"id":"1","name":"album","author":"a"}],
              "folder_list":[
                {"FID":"10","UID":"99","name":"默认收藏夹"}
              ],
              "total":"3",
              "count":20
            }
        """.trimIndent()
        val page = JsonParser.parseString(json).asJsonObject.toFavoritePage()
        assertEquals(3, page.total)
        assertEquals(1, page.content.size)
        assertEquals("1", page.content.single().id)
        assertEquals(1, page.folders.size)
        assertEquals("10", page.folders.single().id)
        assertEquals("默认收藏夹", page.folders.single().name)
        assertEquals("99", page.folders.single().ownerUserId)
    }
}

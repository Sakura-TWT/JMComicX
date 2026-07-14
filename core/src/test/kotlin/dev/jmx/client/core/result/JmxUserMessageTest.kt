package dev.jmx.client.core.result

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JmxUserMessageTest {
    @Test
    fun mapsHttp401ToReauthenticateMessage() {
        val msg = JmxError.Http(code = 401, message = "unauthorized").toUserMessage()
        assertEquals("需要登录", msg.title)
        assertTrue(msg.userMessage.contains("登录"))
        assertTrue(msg.actions.contains(JmxRecoveryAction.Reauthenticate))
    }

    @Test
    fun mapsDomainToSwitchEndpoint() {
        val msg = JmxError.Domain("no host").toUserMessage()
        assertEquals("线路不可用", msg.title)
        assertTrue(msg.actions.contains(JmxRecoveryAction.RefreshEndpoints))
    }

    @Test
    fun mapsLocalSchemaToCallerInput() {
        val msg = JmxError.Schema("albumId blank", field = "albumId").toUserMessage()
        assertEquals("参数错误", msg.title)
        assertTrue(msg.userMessage.contains("albumId"))
    }
}

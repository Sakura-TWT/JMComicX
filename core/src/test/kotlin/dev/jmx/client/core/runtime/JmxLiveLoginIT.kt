package dev.jmx.client.core.runtime

import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class JmxLiveLoginIT {
    @Test
    fun liveLoginAndAuthenticatedApis() {
        assumeTrue("Set JMX_LIVE=1 to enable", isLiveEnabled())
        val credentials = JmxLiveLoginCredentialsLoader.resolve()
        assumeTrue(
            "Set JMX_LIVE_USER/JMX_LIVE_PASS or local-credentials.properties for real login",
            credentials != null
        )
        val creds = requireNotNull(credentials)

        val core = JmxCore.create()
        val report = kotlinx.coroutines.runBlocking {
            core.loginRunner.run(
                JmxLiveLoginScenario(
                    username = creds.username,
                    password = creds.password
                )
            )
        }
        val markdown = JmxLiveLoginMarkdownRenderer().render(report)
        File("build/live-reports").mkdirs()
        File("build/live-reports/login.md").writeText(markdown, Charsets.UTF_8)

        println("===== LIVE LOGIN SUMMARY =====")
        println("meetsMinimum=${report.acceptance.meetsMinimum}")
        println("source=${report.credentialsSource}")
        println("loginOk=${report.acceptance.loginOk} avsOk=${report.acceptance.avsOk}")
        println("favoriteOk=${report.acceptance.favoriteOk} watchOk=${report.acceptance.watchListOk} dailyOk=${report.acceptance.dailyOk}")
        println("cookieCount=${report.health.cookieCount}")
        println("profile=${report.login.valueOrNull()?.profile?.username}")
        report.issues.forEach { println("  [${it.severity}] ${it.step}: ${it.message}") }
        println("==============================")

        assertNotNull(report.acceptance)

        org.junit.Assert.assertTrue(report.acceptance.meetsMinimum)

        org.junit.Assert.assertFalse(markdown.contains(creds.password))
    }

    private fun isLiveEnabled(): Boolean {
        val prop = System.getProperty("jmx.live")
        val env = System.getenv("JMX_LIVE")
        return prop.equals("true", ignoreCase = true) || prop == "1" ||
            env.equals("1", ignoreCase = true) || env.equals("true", ignoreCase = true)
    }
}

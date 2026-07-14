package dev.jmx.client.core.runtime

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class JmxLiveFullSuiteIT {
    @Test
    fun liveFullSuite() {
        assumeTrue(isLiveEnabled())
        val out = Files.createDirectories(File("build/live-reports/full-suite-images").toPath())
        val core = JmxCore.create()
        val report = kotlinx.coroutines.runBlocking {
            JmxLiveFullSuiteRunner(core).run(defaultFullSuiteScenario(out))
        }
        val md = JmxLiveFullSuiteMarkdownRenderer().render(report)
        File("build/live-reports").mkdirs()
        File("build/live-reports/full-suite.md").writeText(md, Charsets.UTF_8)

        println("===== LIVE FULL SUITE =====")
        println("overall=${report.isSuccessful} public=${report.publicOk} loginOk=${report.loginOk}")
        println("connectivity=${report.connectivity.acceptance.meetsMinimum}")
        println(
            "login present=${report.login.acceptance.credentialsPresent} " +
                "meet=${report.login.acceptance.meetsMinimum} " +
                "fav=${report.login.acceptance.favoriteOk} watch=${report.login.acceptance.watchListOk}"
        )
        println("reading=${report.reading.acceptance.meetsMinimum}")
        println("===========================")

        assertNotNull(report)
        assertTrue("public paths must pass", report.publicOk)
        if (report.login.acceptance.credentialsPresent) {
            assertTrue("login segment must pass when credentials present", report.loginOk)
        }
    }

    private fun isLiveEnabled(): Boolean {
        val prop = System.getProperty("jmx.live")
        val env = System.getenv("JMX_LIVE")
        return prop.equals("true", ignoreCase = true) || prop == "1" ||
            env.equals("1", ignoreCase = true) || env.equals("true", ignoreCase = true)
    }
}

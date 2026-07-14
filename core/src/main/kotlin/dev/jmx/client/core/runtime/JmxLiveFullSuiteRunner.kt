package dev.jmx.client.core.runtime

import java.nio.file.Path

data class JmxLiveFullSuiteScenario(
    val connectivity: JmxLiveConnectivityScenario = JmxLiveConnectivityScenario(),
    val login: JmxLiveLoginScenario = JmxLiveLoginScenario(),
    val reading: JmxLiveReadingScenario
)

data class JmxLiveFullSuiteReport(
    val connectivity: JmxLiveConnectivityReport,
    val login: JmxLiveLoginReport,
    val reading: JmxLiveReadingReport
) {
    val publicOk: Boolean = connectivity.acceptance.meetsMinimum && reading.acceptance.meetsMinimum
    val loginOk: Boolean = !login.acceptance.credentialsPresent || login.acceptance.meetsMinimum
    val isSuccessful: Boolean = publicOk && loginOk
}

class JmxLiveFullSuiteRunner(
    private val core: JmxCore
) {
    suspend fun run(scenario: JmxLiveFullSuiteScenario): JmxLiveFullSuiteReport {
        val connectivity = core.connectivityRunner.run(scenario.connectivity)
        val login = core.loginRunner.run(scenario.login)
        val reading = core.readingRunner.run(scenario.reading)
        return JmxLiveFullSuiteReport(
            connectivity = connectivity,
            login = login,
            reading = reading
        )
    }
}

class JmxLiveFullSuiteMarkdownRenderer {
    fun render(report: JmxLiveFullSuiteReport): String {
        return buildString {
            appendLine("# JMComicX Live Full Suite Report")
            appendLine()
            appendLine("- Overall success: `${report.isSuccessful}`")
            appendLine("- Public (connectivity+reading): `${report.publicOk}`")
            appendLine("- Login segment: `${report.loginOk}` (credentialsPresent=${report.login.acceptance.credentialsPresent})")
            appendLine()
            appendLine("## Connectivity")
            appendLine()
            appendLine(
                "- meetsMinimum=`${report.connectivity.acceptance.meetsMinimum}` " +
                    "gates=${report.connectivity.acceptance.passedCount}/${report.connectivity.acceptance.totalCount}"
            )
            appendLine()
            appendLine("## Login")
            appendLine()
            appendLine(
                "- meetsMinimum=`${report.login.acceptance.meetsMinimum}` " +
                    "login=${report.login.acceptance.loginOk} avs=${report.login.acceptance.avsOk} " +
                    "fav=${report.login.acceptance.favoriteOk} watch=${report.login.acceptance.watchListOk} " +
                    "daily=${report.login.acceptance.dailyOk}"
            )
            appendLine()
            appendLine("## Reading")
            appendLine()
            appendLine(
                "- meetsMinimum=`${report.reading.acceptance.meetsMinimum}` " +
                    "gates=${report.reading.acceptance.passedCount}/${report.reading.acceptance.totalCount}"
            )
            appendLine("- output=`${sanitizeDiagnosticText(report.reading.outputDirectory)}`")
            appendLine()
        }
    }
}

fun defaultFullSuiteScenario(readingOutput: Path): JmxLiveFullSuiteScenario {
    return JmxLiveFullSuiteScenario(
        reading = JmxLiveReadingScenario(
            maxImages = 2,
            outputDirectory = readingOutput
        )
    )
}

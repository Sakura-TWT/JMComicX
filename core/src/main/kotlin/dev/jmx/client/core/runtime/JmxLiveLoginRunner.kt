package dev.jmx.client.core.runtime

import dev.jmx.client.core.api.AlbumPage
import dev.jmx.client.core.api.DailyCheckInfo
import dev.jmx.client.core.api.LoginSession
import dev.jmx.client.core.protocol.JmxMagicConstants
import dev.jmx.client.core.result.JmxError
import dev.jmx.client.core.result.JmxResult
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

data class JmxLiveLoginScenario(
    val username: String? = null,
    val password: String? = null,
    val credentialsFile: Path? = null,
    val initialize: Boolean = true,
    val probeFavorite: Boolean = true,
    val probeWatchList: Boolean = true,
    val probeDaily: Boolean = true
)

data class JmxLiveLoginCredentials(
    val username: String,
    val password: String,
    val source: String
)

data class JmxLiveLoginStep<T>(
    val name: String,
    val result: JmxResult<T>? = null,
    val skippedReason: String? = null
) {
    val isSuccessful: Boolean = result is JmxResult.Success
    val isSkipped: Boolean = skippedReason != null
    fun valueOrNull(): T? = (result as? JmxResult.Success)?.value
    fun errorOrNull(): JmxError? = (result as? JmxResult.Failure)?.error

    companion object {
        fun <T> success(name: String, value: T): JmxLiveLoginStep<T> =
            JmxLiveLoginStep(name = name, result = JmxResult.Success(value))

        fun <T> failure(name: String, error: JmxError): JmxLiveLoginStep<T> =
            JmxLiveLoginStep(name = name, result = JmxResult.Failure(error))

        fun <T> skipped(name: String, reason: String): JmxLiveLoginStep<T> =
            JmxLiveLoginStep(name = name, skippedReason = reason)
    }
}

data class JmxLiveLoginAcceptance(
    val credentialsPresent: Boolean,
    val loginOk: Boolean,
    val avsOk: Boolean,
    val favoriteOk: Boolean,
    val watchListOk: Boolean,
    val dailyOk: Boolean
) {

    val meetsMinimum: Boolean =
        credentialsPresent && loginOk && avsOk && favoriteOk && watchListOk && dailyOk

    val passedCount: Int = listOf(
        credentialsPresent,
        loginOk,
        avsOk,
        favoriteOk,
        watchListOk,
        dailyOk
    ).count { it }

    val totalCount: Int = 6
}

data class JmxLiveLoginReport(
    val scenario: JmxLiveLoginScenario,
    val credentialsSource: String?,
    val initialization: JmxLiveLoginStep<JmxCoreInitResult>,
    val login: JmxLiveLoginStep<LoginSession>,
    val avsCheck: JmxLiveLoginStep<Boolean>,
    val favorite: JmxLiveLoginStep<AlbumPage>,
    val watchList: JmxLiveLoginStep<AlbumPage>,
    val daily: JmxLiveLoginStep<DailyCheckInfo>,
    val acceptance: JmxLiveLoginAcceptance,
    val issues: List<JmxDiagnosticIssue>,
    val health: JmxCoreHealth
) {
    val isSuccessful: Boolean = acceptance.meetsMinimum
}

object JmxLiveLoginCredentialsLoader {
    fun resolve(
        username: String? = null,
        password: String? = null,
        credentialsFile: Path? = null
    ): JmxLiveLoginCredentials? {
        if (!username.isNullOrBlank() && !password.isNullOrBlank()) {
            return JmxLiveLoginCredentials(username.trim(), password, "argument")
        }
        val envUser = System.getenv("JMX_LIVE_USER") ?: System.getenv("JMX_USER")
        val envPass = System.getenv("JMX_LIVE_PASS") ?: System.getenv("JMX_PASS")
        if (!envUser.isNullOrBlank() && !envPass.isNullOrBlank()) {
            return JmxLiveLoginCredentials(envUser.trim(), envPass, "env")
        }
        val propUser = System.getProperty("jmx.live.user")
        val propPass = System.getProperty("jmx.live.pass")
        if (!propUser.isNullOrBlank() && !propPass.isNullOrBlank()) {
            return JmxLiveLoginCredentials(propUser.trim(), propPass, "systemProperty")
        }
        val file = credentialsFile ?: Path.of("local-credentials.properties")
        if (!Files.isRegularFile(file)) return null
        val props = Properties()
        Files.newInputStream(file).use { props.load(it) }
        val u = props.getProperty("username") ?: props.getProperty("user")
        val p = props.getProperty("password") ?: props.getProperty("pass")
        if (u.isNullOrBlank() || p.isNullOrBlank()) return null
        return JmxLiveLoginCredentials(u.trim(), p, "file:${file.fileName}")
    }
}

fun interface JmxLiveLoginCredentialsResolver {
    fun resolve(scenario: JmxLiveLoginScenario): JmxLiveLoginCredentials?
}

class JmxLiveLoginRunner(
    private val core: JmxCore,
    private val credentialsResolver: JmxLiveLoginCredentialsResolver = JmxLiveLoginCredentialsResolver { scenario ->
        JmxLiveLoginCredentialsLoader.resolve(
            username = scenario.username,
            password = scenario.password,
            credentialsFile = scenario.credentialsFile
        )
    }
) {
    suspend fun run(scenario: JmxLiveLoginScenario = JmxLiveLoginScenario()): JmxLiveLoginReport {
        val credentials = credentialsResolver.resolve(scenario)

        if (credentials == null) {
            return missingCredentialsReport(scenario)
        }

        val initialization = if (scenario.initialize) {
            runStep("initialize") { core.initializer.initialize() }
        } else {
            JmxLiveLoginStep.success(
                "initialize",
                JmxCoreInitResult(
                    domainRefresh = InitStepResult.Success(emptyList()),
                    settingFetch = InitStepResult.Success(
                        dev.jmx.client.core.api.RemoteSetting(
                            apiVersion = core.apiVersionProvider.current(),
                            imageHost = null,
                            shunts = emptyList()
                        )
                    )
                )
            )
        }

        val login = runResultStep("login") {
            core.userApi.login(credentials.username, credentials.password)
        }

        val avsCheck = if (login.isSuccessful) {
            val hasAvs = core.sessionManager.cookies()
                .any { it.name.equals("AVS", ignoreCase = true) } ||
                !login.valueOrNull()?.avs.isNullOrBlank()
            if (hasAvs) {
                JmxLiveLoginStep.success("avs_check", true)
            } else {
                JmxLiveLoginStep.failure(
                    "avs_check",
                    JmxError.Schema("登录成功但未安装 AVS", field = "AVS")
                )
            }
        } else {
            JmxLiveLoginStep.skipped("avs_check", "login failed")
        }

        val userId = login.valueOrNull()?.profile?.id?.toString()

        val favorite = when {
            !scenario.probeFavorite -> JmxLiveLoginStep.skipped("favorite", "probeFavorite=false")
            !login.isSuccessful -> JmxLiveLoginStep.skipped("favorite", "login failed")
            else -> runResultStep("favorite") {
                core.libraryApi.favoriteAlbums(
                    page = 1,
                    order = JmxMagicConstants.ORDER_BY_LATEST,
                    folderId = 0
                )
            }
        }

        val watchList = when {
            !scenario.probeWatchList -> JmxLiveLoginStep.skipped("watch_list", "probeWatchList=false")
            !login.isSuccessful -> JmxLiveLoginStep.skipped("watch_list", "login failed")
            else -> runResultStep("watch_list") {
                core.libraryApi.watchList(page = 1)
            }
        }

        val daily = when {
            !scenario.probeDaily -> JmxLiveLoginStep.skipped("daily", "probeDaily=false")
            !login.isSuccessful -> JmxLiveLoginStep.skipped("daily", "login failed")
            userId.isNullOrBlank() -> JmxLiveLoginStep.skipped("daily", "userId missing from login profile")
            else -> runResultStep("daily") {
                core.libraryApi.dailyInfo(userId)
            }
        }

        val acceptance = JmxLiveLoginAcceptance(
            credentialsPresent = true,
            loginOk = login.isSuccessful,
            avsOk = avsCheck.isSuccessful,

            favoriteOk = when {
                favorite.isSkipped -> true
                else -> favorite.isSuccessful
            },
            watchListOk = when {
                watchList.isSkipped -> true
                else -> watchList.isSuccessful
            },
            dailyOk = when {
                daily.isSkipped -> true
                else -> daily.isSuccessful
            }
        )

        val issues = collectIssues(
            listOf(initialization, login, avsCheck, favorite, watchList, daily)
        )

        return JmxLiveLoginReport(
            scenario = scenario,
            credentialsSource = credentials.source,
            initialization = initialization,
            login = login,
            avsCheck = avsCheck,
            favorite = favorite,
            watchList = watchList,
            daily = daily,
            acceptance = acceptance,
            issues = issues,
            health = core.healthSnapshot()
        )
    }

    private fun missingCredentialsReport(scenario: JmxLiveLoginScenario): JmxLiveLoginReport {
        val skip = "no credentials (set JMX_LIVE_USER/JMX_LIVE_PASS or local-credentials.properties)"
        return JmxLiveLoginReport(
            scenario = scenario,
            credentialsSource = null,
            initialization = JmxLiveLoginStep.skipped("initialize", skip),
            login = JmxLiveLoginStep.skipped("login", skip),
            avsCheck = JmxLiveLoginStep.skipped("avs_check", skip),
            favorite = JmxLiveLoginStep.skipped("favorite", skip),
            watchList = JmxLiveLoginStep.skipped("watch_list", skip),
            daily = JmxLiveLoginStep.skipped("daily", skip),
            acceptance = JmxLiveLoginAcceptance(
                credentialsPresent = false,
                loginOk = false,
                avsOk = false,
                favoriteOk = false,
                watchListOk = false,
                dailyOk = false
            ),
            issues = listOf(
                JmxDiagnosticIssue(
                    step = "credentials",
                    severity = JmxDiagnosticSeverity.Info,
                    message = skip
                )
            ),
            health = core.healthSnapshot()
        )
    }

    private fun collectIssues(steps: List<JmxLiveLoginStep<*>>): List<JmxDiagnosticIssue> {
        return buildList {
            steps.forEach { step ->
                step.errorOrNull()?.let {
                    add(
                        JmxDiagnosticIssue(
                            step = step.name,
                            severity = it.diagnosticSeverity(),
                            message = it.message,
                            error = it
                        )
                    )
                }
                step.skippedReason?.let {
                    add(
                        JmxDiagnosticIssue(
                            step = step.name,
                            severity = JmxDiagnosticSeverity.Info,
                            message = it
                        )
                    )
                }
            }
        }
    }

    private suspend fun <T> runStep(name: String, block: suspend () -> T): JmxLiveLoginStep<T> {
        return try {
            JmxLiveLoginStep.success(name, block())
        } catch (t: Throwable) {
            JmxLiveLoginStep.failure(name, JmxError.Unknown(t.message ?: "login step failed", t))
        }
    }

    private suspend fun <T> runResultStep(
        name: String,
        block: suspend () -> JmxResult<T>
    ): JmxLiveLoginStep<T> {
        return try {
            JmxLiveLoginStep(name = name, result = block())
        } catch (t: Throwable) {
            JmxLiveLoginStep.failure(name, JmxError.Unknown(t.message ?: "login step failed", t))
        }
    }
}

class JmxLiveLoginMarkdownRenderer {
    fun render(report: JmxLiveLoginReport): String {
        return buildString {
            appendLine("# JMComicX Live Login Report")
            appendLine()
            appendLine("- Meets minimum: `${report.acceptance.meetsMinimum}`")
            appendLine("- Credentials present: `${report.acceptance.credentialsPresent}`")
            appendLine("- Credentials source: `${report.credentialsSource ?: "-"}`")
            appendLine("- Cookie count: `${report.health.cookieCount}`")
            appendLine()
            appendLine("| Gate | Pass |")
            appendLine("| --- | --- |")
            appendLine("| credentials | `${report.acceptance.credentialsPresent}` |")
            appendLine("| login | `${report.acceptance.loginOk}` |")
            appendLine("| avs | `${report.acceptance.avsOk}` |")
            appendLine("| favorite | `${report.acceptance.favoriteOk}` |")
            appendLine("| watch_list | `${report.acceptance.watchListOk}` |")
            appendLine("| daily | `${report.acceptance.dailyOk}` |")
            appendLine()
            report.login.valueOrNull()?.profile?.let {
                appendLine("- Profile uid=`${it.id}` username=`${sanitizeDiagnosticText(it.username ?: "-")}`")
            }

            appendLine("- AVS present: `${report.avsCheck.valueOrNull() == true}`")
            report.favorite.valueOrNull()?.let {
                appendLine("- Favorite total=`${it.total ?: "-"}` content=${it.content.size}")
            }
            report.watchList.valueOrNull()?.let {
                appendLine("- WatchList total=`${it.total ?: "-"}` content=${it.content.size}")
            }
            report.daily.valueOrNull()?.let {
                appendLine("- Daily id=`${it.dailyId ?: "-"}` event=`${sanitizeDiagnosticText(it.eventName ?: "-")}`")
            }
            if (report.issues.isNotEmpty()) {
                appendLine()
                appendLine("## Issues")
                report.issues.forEach {
                    appendLine("- `[${it.severity}] ${it.step}`: ${sanitizeDiagnosticText(it.message)}")
                }
            }
            appendLine()
        }
    }
}

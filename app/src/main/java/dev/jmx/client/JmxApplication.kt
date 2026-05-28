package dev.jmx.client

import android.app.Application
import dev.jmx.client.di.appModule
import dev.jmx.client.di.coilModule
import dev.jmx.client.di.albumModule
import dev.jmx.client.di.databaseModule
import dev.jmx.client.di.remoteDataModule
import dev.jmx.client.di.userModule
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.workmanager.koin.workManagerFactory
import org.koin.core.context.startKoin
import org.koin.core.context.GlobalContext
import dev.jmx.client.store.DiagnosticLogManager
import dev.jmx.client.store.JmxDiagnostics

private val moduleList = listOf(
    appModule,
    coilModule,
    albumModule,
    remoteDataModule,
    userModule,
    databaseModule
)

class JmxApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        startKoin {
            androidContext(this@JmxApplication)
            workManagerFactory()
            modules(moduleList)
        }
        val diagnosticLogManager = GlobalContext.get().get<DiagnosticLogManager>()
        diagnosticLogManager.initialize()
        JmxDiagnostics.i("Application", "JMX application created")
    }
}

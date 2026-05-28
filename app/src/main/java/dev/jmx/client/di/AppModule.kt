package dev.jmx.client.di

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.Strictness
import dev.jmx.client.repository.RemoteSettingRepository
import dev.jmx.client.repository.impl.RemoteSettingRepositoryImpl
import dev.jmx.client.storage.CookieStorage
import dev.jmx.client.storage.HistorySearchStorage
import dev.jmx.client.storage.LocalSettingStorage
import dev.jmx.client.storage.SecureStorage
import dev.jmx.client.storage.UpdatePreferenceStorage
import dev.jmx.client.storage.UserStorage
import dev.jmx.client.store.AppUpdateManager
import dev.jmx.client.store.DiagnosticLogManager
import dev.jmx.client.store.HistorySearchManager
import dev.jmx.client.store.InitManager
import dev.jmx.client.store.LocalSettingManager
import dev.jmx.client.store.RemoteSettingManager
import dev.jmx.client.store.ToastManager
import dev.jmx.client.store.UserManager
import dev.jmx.client.task.AppInitTask
import dev.jmx.client.ui.viewModel.GlobalViewModel
import dev.jmx.client.utils.log
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.bind
import org.koin.dsl.module

val appModule = module {
    single {
        CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, throwable ->
            log("全局协程捕获到了异常: $throwable")
        })
    }

    single { SecureStorage(get()) }
    single { UserStorage(get()) }
    single { CookieStorage(get()) }
    single { LocalSettingStorage(get()) }
    single { HistorySearchStorage(get()) }
    single { UpdatePreferenceStorage(get()) }

    single { RemoteSettingRepositoryImpl(get(), get()) } bind RemoteSettingRepository::class

    single { UserManager(get(), get(), get(), get()) } bind AppInitTask::class
    single { RemoteSettingManager(get()) } bind AppInitTask::class
    single { LocalSettingManager(get()) } bind AppInitTask::class
    single { HistorySearchManager(get()) } bind AppInitTask::class
    single { ToastManager() }
    single { AppUpdateManager(get(), get(), get()) }
    single { DiagnosticLogManager(get(), get(), get()) }
    single { InitManager() }

    single<Gson> { GsonBuilder().setStrictness(Strictness.LENIENT).serializeNulls().create() }

    viewModel { GlobalViewModel(getAll(), get()) }
}

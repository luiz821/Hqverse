package com.hqverse.app

import android.app.Application
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import com.hqverse.app.data.db.AppDatabase
import com.hqverse.app.data.files.CacheManager
import com.hqverse.app.data.reader.ComicOpener
import com.hqverse.app.data.repo.*
import com.hqverse.app.data.scan.LibraryIndexer
import com.hqverse.app.data.scan.ScanCoordinator
import com.hqverse.app.data.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Injeção de dependências manual: simples e suficiente para o tamanho do projeto. */
class AppContainer(val context: Context) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val db: AppDatabase by lazy { AppDatabase.build(context) }
    val settings = SettingsRepository(context)
    val cache = CacheManager(context)
    val library by lazy { LibraryRepository(context, db, cache) }
    val progress by lazy { ProgressRepository(db) }
    val stats by lazy { StatsRepository(db) }
    val backup by lazy { BackupRepository(context, db, settings) }
    val opener by lazy { ComicOpener(context, cache) { settings.current().cacheLimitMb * 1024L * 1024L } }
    val scanCoordinator = ScanCoordinator()
    val indexer by lazy { LibraryIndexer(context, db, cache, settings, opener, scanCoordinator) }

    /** Ao abrir o app: fecha sessões deixadas abertas por encerramento inesperado. */
    fun onAppStart() { appScope.launch { runCatching { progress.recoverOpenSessions() } } }
}

class HQVerseApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.onAppStart()
    }
}

/** Cria ViewModels que recebem o [AppContainer]. */
inline fun <reified VM : ViewModel> containerFactory(crossinline create: (AppContainer) -> VM) = object : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
        val app = extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as HQVerseApp
        return create(app.container) as T
    }
}

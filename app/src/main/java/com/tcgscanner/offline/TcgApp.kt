package com.tcgscanner.offline

import android.app.Application
import com.tcgscanner.offline.work.SyncScheduler
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class TcgApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.packs // create the pack repositories now so installed engines load without opening Settings
        // Keep the daily price sync in line with the user's preferences.
        container.scope.launch {
            container.settingsState.filterNotNull()
                .map { it.autoSync to it.wifiOnlySync }
                .distinctUntilChanged()
                .collect { (auto, wifiOnly) -> SyncScheduler.apply(this@TcgApp, auto, wifiOnly) }
        }
        // Engines + the catalog of every active game are downloaded right away (and again when a game is added).
        container.setup.start()
    }
}

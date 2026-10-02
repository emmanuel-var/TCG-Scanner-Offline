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
        // Keep the daily price sync in line with the user's preferences.
        container.scope.launch {
            container.settingsState.filterNotNull()
                .map { it.autoSync to it.wifiOnlySync }
                .distinctUntilChanged()
                .collect { (auto, wifiOnly) -> SyncScheduler.apply(this@TcgApp, auto, wifiOnly) }
        }
        // Initial sync: any activated game that has no catalog yet is downloaded in the background.
        container.scope.launch {
            val settings = container.settingsState.filterNotNull().first()
            val empty = settings.activeGames.filter { container.db.cards().count(it.code) == 0 }
            SyncScheduler.enqueueNow(this@TcgApp, empty, settings.wifiOnlySync, manual = false)
        }
    }
}

package com.tcgscanner.offline

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.tcgscanner.offline.core.GameId
import com.tcgscanner.offline.core.Games
import com.tcgscanner.offline.core.OcrScript
import com.tcgscanner.offline.data.db.AppDatabase
import com.tcgscanner.offline.data.prefs.AppSettings
import com.tcgscanner.offline.data.prefs.SettingsStore
import com.tcgscanner.offline.data.remote.CatalogUrls
import com.tcgscanner.offline.data.repo.CatalogRepository
import com.tcgscanner.offline.data.repo.SyncCoordinator
import com.tcgscanner.offline.data.repo.SyncResult
import com.tcgscanner.offline.scanner.EnginePack
import com.tcgscanner.offline.scanner.ModelRepository
import com.tcgscanner.offline.scanner.ModelState
import com.tcgscanner.offline.scanner.SetupItem
import com.tcgscanner.offline.scanner.SetupKind
import com.tcgscanner.offline.scanner.SetupResolver
import com.tcgscanner.offline.scanner.SetupSummary
import com.tcgscanner.offline.scanner.aggregateModelStates
import com.tcgscanner.offline.work.SyncScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * First-run (and "new game added") preparation. As soon as the app opens it queues, once per process:
 *  - the scan engines (OCR [+ Japanese OCR when a Japanese game is active]) and the artwork engine,
 *  - the catalog of every active game that has a source and no cards yet,
 * so a user never opens the scanner to find nothing installed. [summary] feeds the small progress strip.
 *
 * Downloads honour the Wi-Fi-only preference; [useMobileData] lets the user override it for what is pending.
 * Failures are surfaced (with [retry]) and never block the app: scanning still works through ML Kit.
 */
class AppSetup(
    private val app: Application,
    private val scope: CoroutineScope,
    private val settings: SettingsStore,
    private val db: AppDatabase,
    private val catalog: CatalogRepository,
    private val sync: SyncCoordinator,
    private val packs: Map<EnginePack, ModelRepository>
) {
    private val requestedPacks = HashSet<EnginePack>()
    private val requestedCatalogs = HashSet<GameId>()

    /** Call once, from Application.onCreate. */
    fun start() {
        scope.launch {
            settings.settings.map { it.activeGames }.distinctUntilChanged().collect { ensure(it) }
        }
    }

    private suspend fun ensure(games: Set<GameId>, manual: Boolean = false) {
        val cfg = settings.current()
        val wifiOnly = cfg.wifiOnlySync && !manual
        for (pack in neededPacks(games)) {
            val repo = packs[pack] ?: continue
            val state = repo.state.value
            val startable = state is ModelState.Missing || state is ModelState.Failed
            if (startable && (manual || requestedPacks.add(pack))) repo.download(unmeteredOnly = wifiOnly, replace = manual)
        }
        val empty = games.filter { hasSource(it, cfg) && db.cards().count(it.code) == 0 && (manual || requestedCatalogs.add(it)) }
        if (empty.isNotEmpty()) {
            SyncScheduler.enqueueNow(app, empty, wifiOnly = cfg.wifiOnlySync, manual = manual, requireBatteryNotLow = false)
        }
    }

    /** The user pressed "Retry": everything that is missing or failed is started again. */
    fun retry() = scope.launch {
        requestedPacks.clear(); requestedCatalogs.clear()
        ensure(settings.current().activeGames)
    }

    /** The user pressed "Use mobile data": pending downloads stop waiting for Wi-Fi. */
    fun useMobileData() = scope.launch {
        ensure(settings.current().activeGames, manual = true)
    }

    private fun neededPacks(games: Set<GameId>): List<EnginePack> = buildList {
        add(EnginePack.OCR); add(EnginePack.EMBEDDER)
        if (games.any { Games[it].ocrScript == OcrScript.JAPANESE }) add(EnginePack.OCR_JA)
    }

    private fun hasSource(game: GameId, cfg: AppSettings) = CatalogUrls.default(game) != null || cfg.catalogUrls.containsKey(game)

    // ---- progress ------------------------------------------------------------------------------------------------

    private val packStates: Flow<Map<EnginePack, ModelState>> =
        combine(packs.map { (pack, repo) -> repo.state.map { pack to it } }) { it.toMap() }

    @OptIn(ExperimentalCoroutinesApi::class)
    private val counts: Flow<Map<GameId, Int>> = settings.settings.map { it.activeGames }.distinctUntilChanged().flatMapLatest { games ->
        if (games.isEmpty()) flowOf(emptyMap())
        else combine(games.map { g -> catalog.observeCount(g).map { g to it } }) { it.toMap() }
    }

    /** Everything the progress strip shows. Cheap to collect: no work happens in the flow itself. */
    val summary: StateFlow<SetupSummary> = combine(settings.settings, packStates, counts, sync.state, unmeteredNetwork()) { cfg, states, count, syncState, unmetered ->
        val games = cfg.activeGames.sortedBy { it.ordinal }
        val scanPacks = buildList {
            add(EnginePack.OCR)
            if (games.any { Games[it].ocrScript == OcrScript.JAPANESE }) add(EnginePack.OCR_JA)
        }
        val items = ArrayList<SetupItem>()
        items += SetupResolver.packItem(SetupKind.SCAN_ENGINE, aggregateModelStates(scanPacks.map { states[it] ?: ModelState.Missing }))
        items += SetupResolver.packItem(SetupKind.ARTWORK_ENGINE, states[EnginePack.EMBEDDER] ?: ModelState.Missing)
        games.filter { hasSource(it, cfg) }.forEach { g ->
            val failed = syncState.results[g]?.let { !it.success && it.error != SyncResult.NO_SOURCE } == true
            items += SetupResolver.catalogItem(
                g.code, count[g] ?: 0, syncingThis = syncState.running && syncState.currentGame == g,
                syncRunning = syncState.running, failed = failed, progress = syncState.fraction
            )
        }
        SetupResolver.summarize(items, cfg.wifiOnlySync, unmetered)
    }.stateIn(scope, SharingStarted.Eagerly, SetupSummary.Done)

    /** true while the device is on an unmetered (Wi-Fi-like) network. */
    private fun unmeteredNetwork(): Flow<Boolean> = callbackFlow {
        val cm = app.getSystemService(ConnectivityManager::class.java)
        fun current(): Boolean {
            val net = cm.activeNetwork ?: return false
            return cm.getNetworkCapabilities(net)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true
        }
        trySend(current())
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { trySend(current()) }
            override fun onLost(network: Network) { trySend(current()) }
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) { trySend(current()) }
        }
        cm.registerDefaultNetworkCallback(callback)
        awaitClose { cm.unregisterNetworkCallback(callback) }
    }.distinctUntilChanged()
}

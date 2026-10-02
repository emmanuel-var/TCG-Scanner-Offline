package com.tcgscanner.offline.data.repo

import com.tcgscanner.offline.core.GameId
import com.tcgscanner.offline.core.Games
import com.tcgscanner.offline.data.remote.SyncProgress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

data class SyncUiState(
    val running: Boolean = false,
    val currentGame: GameId? = null,
    val message: String = "",
    val fraction: Float? = null,
    val results: Map<GameId, SyncResult> = emptyMap()
)

/**
 * Single entry point for price/catalog refreshes, shared by the daily WorkManager job and the
 * "Sync prices" button. A mutex guarantees only one sync runs at a time.
 */
class SyncCoordinator(
    private val scope: CoroutineScope,
    private val catalog: CatalogRepository,
    private val portfolio: PortfolioRepository
) {
    private val mutex = Mutex()
    private var job: Job? = null
    private val _state = MutableStateFlow(SyncUiState())
    val state: StateFlow<SyncUiState> = _state.asStateFlow()

    /** Fire-and-forget for UI buttons; ignored if a sync is already running. */
    fun start(games: Collection<GameId>) {
        if (mutex.isLocked) return
        job = scope.launch { syncAll(games) }
    }

    fun cancel() {
        job?.cancel()
    }

    suspend fun syncAll(games: Collection<GameId>): List<SyncResult> {
        if (!mutex.tryLock()) return emptyList()
        val results = ArrayList<SyncResult>()
        try {
            _state.value = SyncUiState(running = true)
            for (id in games) {
                val def = Games[id]
                _state.update { it.copy(currentGame = id, message = "", fraction = null) }
                val result = try {
                    catalog.sync(def) { p: SyncProgress -> _state.update { s -> s.copy(message = p.message, fraction = p.fraction) } }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    SyncResult(id, false, null, 0, e.message)
                }
                results.add(result)
                _state.update { it.copy(results = it.results + (id to result)) }
                if (result.success) portfolio.recordSnapshot(id)
            }
        } finally {
            _state.update { it.copy(running = false, currentGame = null, message = "", fraction = null) }
            mutex.unlock()
        }
        return results
    }
}

package com.tcgscanner.offline.ui.screens

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tcgscanner.offline.AppContainer
import com.tcgscanner.offline.core.GameDef
import com.tcgscanner.offline.core.GameId
import com.tcgscanner.offline.core.Games
import com.tcgscanner.offline.core.Text
import com.tcgscanner.offline.data.db.CardWithPrices
import com.tcgscanner.offline.data.repo.AddSpec
import com.tcgscanner.offline.scanner.CardTextParser
import com.tcgscanner.offline.scanner.OcrLine
import com.tcgscanner.offline.ui.currentGameFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

data class SheetData(val chosen: CardWithPrices, val others: List<CardWithPrices>, val confidencePercent: Int?)

data class ScannerUi(
    val sheet: SheetData? = null,
    val visualCandidates: List<CardWithPrices>? = null,
    val artworkBusy: Boolean = false,
    val addedName: String? = null,
    val liveGuess: String? = null
)

class ScannerViewModel(private val c: AppContainer) : ViewModel() {
    /** Shared with the camera analyzer: when true no OCR runs (a sheet is open). */
    val paused = AtomicBoolean(false)
    val captureRequested = AtomicBoolean(false)

    private val matching = AtomicBoolean(false)
    private val _ui = MutableStateFlow(ScannerUi())
    val ui: StateFlow<ScannerUi> = _ui

    val game: StateFlow<GameDef?> = c.settingsState.map { s -> s?.currentGame?.let { Games[it] } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    @OptIn(ExperimentalCoroutinesApi::class)
    val catalogCount: StateFlow<Int> = c.currentGameFlow().flatMapLatest { c.catalog.observeCount(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    private var streakId: String? = null
    private var streak = 0
    private var cooldownUntil = 0L

    // ---- OCR path ---------------------------------------------------------------------------------

    fun onLines(lines: List<OcrLine>) {
        val g = game.value ?: return
        if (paused.get() || lines.isEmpty() || System.currentTimeMillis() < cooldownUntil) return
        if (!matching.compareAndSet(false, true)) return
        viewModelScope.launch(Dispatchers.Default) {
            try {
                val parsed = CardTextParser.parse(lines, g)
                val matches = c.scanMatcher.match(g.id, parsed)
                val top = matches.firstOrNull()
                _ui.update { it.copy(liveGuess = top?.takeIf { m -> m.score >= 0.6 }?.card?.card?.name) }
                if (top == null || top.score < LOCK_SCORE) {
                    streakId = null; streak = 0
                } else {
                    if (top.card.card.id == streakId) streak++ else { streakId = top.card.card.id; streak = 1 }
                    if (streak >= 2 || top.score >= 0.97) {
                        open(top.card, (top.score * 100).toInt().coerceAtMost(100))
                    }
                }
            } finally {
                matching.set(false)
            }
        }
    }

    // ---- artwork path -----------------------------------------------------------------------------

    fun identifyByArtwork() {
        if (_ui.value.artworkBusy) return
        _ui.update { it.copy(artworkBusy = true) }
        captureRequested.set(true)
    }

    fun onCapture(bitmap: Bitmap) {
        val g = game.value ?: return
        viewModelScope.launch(Dispatchers.Default) {
            val matches = c.visualMatcher.identify(g.id, bitmap)
            val cards = c.db.cards().getManyWithPrices(matches.map { it.cardId }).associateBy { it.card.id }
            val ordered = matches.mapNotNull { cards[it.cardId] }
            _ui.update { it.copy(artworkBusy = false, visualCandidates = ordered) }
            paused.set(true)
        }
    }

    fun dismissVisual() {
        _ui.update { it.copy(visualCandidates = null) }
        paused.set(_ui.value.sheet != null)
    }

    // ---- selection / adding ------------------------------------------------------------------------

    /** Opens the variant sheet for [card] (from OCR, artwork or manual search). */
    fun open(card: CardWithPrices, confidencePercent: Int? = null) {
        paused.set(true)
        viewModelScope.launch(Dispatchers.Default) {
            val g = card.card.gameId
            val same = c.db.cards().findByNameKey(g, card.card.nameKey).filter { it.id != card.card.id }
            val others = if (same.isEmpty()) emptyList() else c.db.cards().getManyWithPrices(same.map { it.id })
            _ui.update { it.copy(sheet = SheetData(card, others, confidencePercent), visualCandidates = null) }
        }
    }

    fun add(spec: AddSpec) {
        val sheet = _ui.value.sheet ?: return
        val gameId = GameId.fromCode(sheet.chosen.card.gameId) ?: return
        viewModelScope.launch {
            c.collection.add(gameId, spec)
            _ui.update { it.copy(sheet = null, addedName = sheet.chosen.card.name) }
            finishSheet()
        }
    }

    fun dismissSheet() {
        _ui.update { it.copy(sheet = null) }
        finishSheet()
    }

    fun consumeAdded() = _ui.update { it.copy(addedName = null) }

    private fun finishSheet() {
        streakId = null; streak = 0
        cooldownUntil = System.currentTimeMillis() + 1500
        paused.set(false)
    }

    // ---- manual search -----------------------------------------------------------------------------------

    private val query = MutableStateFlow("")
    fun setQuery(q: String) { query.value = q }

    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    val results: StateFlow<List<CardWithPrices>> = combine(c.currentGameFlow(), query.debounce(200)) { g, q -> g to q }
        .flatMapLatest { (g, q) ->
            val key = Text.nameKey(q)
            if (key.length < 2) flowOf(emptyList())
            else kotlinx.coroutines.flow.flow {
                val ids = c.db.cards().search(g.code, key, 30).map { it.id }
                val byId = c.db.cards().getManyWithPrices(ids).associateBy { it.card.id }
                emit(ids.mapNotNull { byId[it] })
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private companion object {
        const val LOCK_SCORE = 0.8
    }
}

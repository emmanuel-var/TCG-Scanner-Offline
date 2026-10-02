package com.tcgscanner.offline.ui.screens

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tcgscanner.offline.AppContainer
import com.tcgscanner.offline.core.GameDef
import com.tcgscanner.offline.core.GameId
import com.tcgscanner.offline.core.Games
import com.tcgscanner.offline.core.OcrScript
import com.tcgscanner.offline.core.Text
import com.tcgscanner.offline.data.db.CardWithPrices
import com.tcgscanner.offline.data.repo.AddSpec
import com.tcgscanner.offline.scanner.CardTextParser
import com.tcgscanner.offline.scanner.EnginePack
import com.tcgscanner.offline.scanner.ModelState
import com.tcgscanner.offline.scanner.aggregateModelStates
import com.tcgscanner.offline.scanner.pipeline.PipelineFrame
import com.tcgscanner.offline.scanner.pipeline.Quad
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
    val liveGuess: String? = null,
    /** Card outline from the YOLO detector, normalised to the preview; null when the guide frame is in use. */
    val outline: Quad? = null,
    val detected: Boolean = false,
    val engine: String = "",
    /** The OCR keeps failing to find a valid set number: the "identify by artwork" fallback is suggested. */
    val needsArtwork: Boolean = false
)

/**
 * Drives the hybrid scan pipeline. Per frame (already detected, rectified and OCR-read by the PipelineAnalyzer):
 * Regex parsing -> Room lookup (indexed, game-scoped) -> stability check -> variant bottom sheet. The artwork
 * fallback uses a vector index that this ViewModel loads into RAM as soon as it starts for a game.
 */
class ScannerViewModel(private val c: AppContainer) : ViewModel() {
    /** Shared with the camera analyzer: when true no frames are processed (a sheet is open). */
    val paused = AtomicBoolean(false)

    private val matching = AtomicBoolean(false)
    private val _ui = MutableStateFlow(ScannerUi())
    val ui: StateFlow<ScannerUi> = _ui

    val game: StateFlow<GameDef?> = c.settingsState.map { s -> s?.currentGame?.let { Games[it] } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    @OptIn(ExperimentalCoroutinesApi::class)
    val catalogCount: StateFlow<Int> = c.currentGameFlow().flatMapLatest { c.catalog.observeCount(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    // ---- engine packs ---------------------------------------------------------------------------------------

    /** Packs the scan pipeline uses for [game]: detector + the matching OCR. */
    private fun scanPacks(game: GameId): List<EnginePack> =
        if (Games[game].ocrScript == OcrScript.JAPANESE) listOf(EnginePack.DETECTOR, EnginePack.OCR, EnginePack.OCR_JA)
        else listOf(EnginePack.DETECTOR, EnginePack.OCR)

    /** Detector + OCR packs of the active game, folded into one state: Missing -> Downloading(progress) -> Ready. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val scanEngineState: StateFlow<ModelState> = c.currentGameFlow().flatMapLatest { g ->
        combine(scanPacks(g).map { c.packs.getValue(it).state }) { aggregateModelStates(it.toList()) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ModelState.Ready)

    /** EfficientNet-Lite0 ("visual engine"): gates the artwork button. */
    val visualEngineState: StateFlow<ModelState> = c.models.state

    val identifyEnabled: StateFlow<Boolean> = c.models.state.map { it is ModelState.Ready }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), c.models.state.value is ModelState.Ready)

    fun downloadScanEngine() {
        val g = game.value?.id ?: return
        scanPacks(g).map { c.packs.getValue(it) }.filter { it.state.value !is ModelState.Ready }.forEach { it.download() }
    }

    fun downloadVisualEngine() = c.models.download()

    // ---- artwork index in RAM -----------------------------------------------------------------------------------

    private val _indexSize = MutableStateFlow(0)

    /** How many cards of the active game are in the in-memory embedding index. */
    val indexSize: StateFlow<Int> = _indexSize

    init {
        // Load the embeddings of the active game into RAM once (and again if the index is rebuilt).
        viewModelScope.launch {
            combine(c.currentGameFlow(), c.visualMatcher.invalidations) { g, _ -> g }.collect { g ->
                _indexSize.value = c.visualMatcher.prepare(g)
            }
        }
    }

    private var streakId: String? = null
    private var streak = 0
    private var cooldownUntil = 0L
    private var misses = 0
    @Volatile private var lastCard: Bitmap? = null

    // ---- frames from the pipeline -------------------------------------------------------------------------------

    fun onFrame(frame: PipelineFrame) {
        val g = game.value ?: return
        lastCard = frame.card
        _ui.update { it.copy(outline = frame.outline, detected = frame.detected, engine = frame.engine) }
        if (paused.get() || System.currentTimeMillis() < cooldownUntil) return
        if (!matching.compareAndSet(false, true)) return
        viewModelScope.launch(Dispatchers.Default) {
            try {
                val parsed = CardTextParser.parse(frame.lines, g)
                if (frame.lines.isNotEmpty()) {
                    misses = if (parsed.hasNumber) 0 else misses + 1
                    _ui.update { it.copy(needsArtwork = misses >= MISSES_BEFORE_ART_HINT) }
                }
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

    // ---- artwork fallback (phase 3) -------------------------------------------------------------------------------

    /** Phase 3: embeds the rectified card with EfficientNet-Lite0 and compares it with the in-RAM index. */
    fun identifyByArtwork() {
        val g = game.value ?: return
        val card = lastCard ?: return
        if (_ui.value.artworkBusy || !identifyEnabled.value) return
        _ui.update { it.copy(artworkBusy = true) }
        viewModelScope.launch(Dispatchers.Default) {
            val matches = c.visualMatcher.identify(g.id, card)
            val cards = c.db.cards().getManyWithPrices(matches.map { it.cardId }).associateBy { it.card.id }
            val ordered = matches.mapNotNull { cards[it.cardId] }
            paused.set(true)
            _ui.update { it.copy(artworkBusy = false, visualCandidates = ordered) }
        }
    }

    fun dismissVisual() {
        _ui.update { it.copy(visualCandidates = null) }
        paused.set(_ui.value.sheet != null)
    }

    // ---- selection / adding (phase 4: the human picks the variant) -------------------------------------------------

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
        streakId = null; streak = 0; misses = 0
        _ui.update { it.copy(needsArtwork = false) }
        cooldownUntil = System.currentTimeMillis() + 1500
        paused.set(false)
    }

    // ---- manual search -----------------------------------------------------------------------------------------

    private val query = MutableStateFlow("")
    fun setQuery(q: String) { query.value = q }

    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    val results: StateFlow<List<CardWithPrices>> = combine(c.currentGameFlow(), query.debounce(200)) { g, q -> g to q }
        .flatMapLatest { (g, q) ->
            val key = Text.nameKey(q)
            if (key.length < 2) flowOf(emptyList())
            else kotlinx.coroutines.flow.flow {
                // Prefix range scan on the (gameId, nameKey) index first; "contains" only if the prefix finds nothing.
                var ids = c.db.cards().findByNameRange(g.code, key, Text.prefixUpperBound(key), 30).map { it.id }
                if (ids.isEmpty()) ids = c.db.cards().search(g.code, key, 30).map { it.id }
                val byId = c.db.cards().getManyWithPrices(ids).associateBy { it.card.id }
                emit(ids.mapNotNull { byId[it] })
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    override fun onCleared() {
        lastCard = null
    }

    private companion object {
        const val LOCK_SCORE = 0.8
        const val MISSES_BEFORE_ART_HINT = 5
    }
}

package com.tcgscanner.offline

import android.app.Application
import com.tcgscanner.offline.ads.AdsConsent
import com.tcgscanner.offline.core.GameId
import com.tcgscanner.offline.data.db.AppDatabase
import com.tcgscanner.offline.data.prefs.AppSettings
import com.tcgscanner.offline.data.prefs.SettingsStore
import com.tcgscanner.offline.data.remote.Http
import com.tcgscanner.offline.data.remote.sources.LorcastSource
import com.tcgscanner.offline.data.remote.sources.MtgJsonSource
import com.tcgscanner.offline.data.remote.sources.ScryfallSource
import com.tcgscanner.offline.data.remote.sources.OptcgSource
import com.tcgscanner.offline.data.remote.sources.TcgdexSource
import com.tcgscanner.offline.data.remote.sources.UrlCatalogSource
import com.tcgscanner.offline.data.repo.BackupRepository
import com.tcgscanner.offline.data.repo.CardRelinker
import com.tcgscanner.offline.data.repo.CatalogRepository
import com.tcgscanner.offline.data.repo.CollectionRepository
import com.tcgscanner.offline.data.repo.CsvExporter
import com.tcgscanner.offline.data.repo.DeckRepository
import com.tcgscanner.offline.data.repo.PortfolioRepository
import com.tcgscanner.offline.data.repo.SyncCoordinator
import com.tcgscanner.offline.scanner.EnginePack
import com.tcgscanner.offline.scanner.EnginePacks
import com.tcgscanner.offline.scanner.ModelRepository
import com.tcgscanner.offline.scanner.paddle.PaddleOcrEngine
import com.tcgscanner.offline.scanner.pipeline.YoloCardDetector
import com.tcgscanner.offline.scanner.ScanMatcher
import com.tcgscanner.offline.scanner.TfliteEmbedder
import com.tcgscanner.offline.scanner.VisualIndexer
import com.tcgscanner.offline.scanner.VisualMatcher
import com.tcgscanner.offline.trade.TradeManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import java.io.File

/** Hand-rolled dependency container: no DI framework, no reflection, nothing to configure. */
class AppContainer(val app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val db: AppDatabase = AppDatabase.create(app)
    val settings = SettingsStore(app)
    /** Ad consent (UMP) and AdMob start-up. */
    val ads = AdsConsent(app)
    val http = Http()

    /** null until DataStore has delivered its first value. */
    val settingsState: StateFlow<AppSettings?> =
        settings.settings.stateIn(scope, SharingStarted.Eagerly, null)

    private val sources = listOf(
        UrlCatalogSource(http, { game: GameId -> settings.catalogUrlOnce(game) }),
        UrlCatalogSource(http, { game: GameId -> settings.catalogUrlOnce(game) }, backup = true),
        ScryfallSource(http, { game: GameId -> settings.catalogUrlOnce(game) }),
        MtgJsonSource(http), OptcgSource(http), LorcastSource(http), TcgdexSource(http)
    ).associateBy { it.id }

    val relinker = CardRelinker(db)
    val catalog = CatalogRepository(db, settings, sources, relinker) { game -> visualMatcher.invalidate(game) }
    val portfolio = PortfolioRepository(db)
    val collection = CollectionRepository(db, portfolio)
    val decks = DeckRepository(db)
    val sync = SyncCoordinator(scope, catalog, portfolio)
    val csv = CsvExporter(db)
    val backup = BackupRepository(db, relinker)

    // ---- scan pipeline engines (each loads from filesDir once its pack is installed) --------------------------------
    private val files get() = app.filesDir
    val detector by lazy { YoloCardDetector(File(files, EnginePacks.DETECTOR_FILE)) }
    val paddleOcr by lazy { PaddleOcrEngine(File(files, EnginePacks.OCR_DET_FILE), File(files, EnginePacks.OCR_REC_FILE), File(files, EnginePacks.OCR_DICT_FILE)) }
    val paddleOcrJa by lazy { PaddleOcrEngine(File(files, EnginePacks.OCR_DET_FILE), File(files, EnginePacks.OCR_REC_JA_FILE), File(files, EnginePacks.OCR_DICT_JA_FILE)) }
    val embedder by lazy { TfliteEmbedder(File(files, EnginePacks.EMBEDDER_FILE)) }

    /** One repository per downloadable pack; created together so every engine reloads itself when a download ends. */
    val packs: Map<EnginePack, ModelRepository> by lazy {
        EnginePack.entries.associateWith { pack ->
            ModelRepository(
                app, scope, settings, pack,
                onInstalled = {
                    when (pack) {
                        EnginePack.DETECTOR -> detector.reload()
                        EnginePack.OCR -> paddleOcr.reload().also { if (EnginePacks.isInstalled(files, EnginePack.OCR_JA)) paddleOcrJa.reload() }
                        EnginePack.OCR_JA -> paddleOcrJa.reload()
                        EnginePack.EMBEDDER -> embedder.reload()
                    }
                },
                onRemoved = {
                    when (pack) {
                        EnginePack.DETECTOR -> detector.unload()
                        EnginePack.OCR -> { paddleOcr.unload(); paddleOcrJa.unload() }
                        EnginePack.OCR_JA -> paddleOcrJa.unload()
                        EnginePack.EMBEDDER -> embedder.unload()
                    }
                }
            )
        }
    }

    /** The artwork pack ("visual engine"): kept as a shortcut because the scanner gates "identify by artwork" on it. */
    val models: ModelRepository get() = packs.getValue(EnginePack.EMBEDDER)

    val scanMatcher = ScanMatcher(db)
    val visualMatcher by lazy { VisualMatcher(db, embedder) }
    val indexer by lazy { VisualIndexer(scope, db, http, embedder, visualMatcher) }

    val trade by lazy { TradeManager(app, db, settings, scope) }
}

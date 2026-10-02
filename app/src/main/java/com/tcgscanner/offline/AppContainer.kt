package com.tcgscanner.offline

import android.app.Application
import com.tcgscanner.offline.core.GameId
import com.tcgscanner.offline.data.db.AppDatabase
import com.tcgscanner.offline.data.prefs.AppSettings
import com.tcgscanner.offline.data.prefs.SettingsStore
import com.tcgscanner.offline.data.remote.Http
import com.tcgscanner.offline.data.remote.sources.LorcastSource
import com.tcgscanner.offline.data.remote.sources.MtgJsonSource
import com.tcgscanner.offline.data.remote.sources.OptcgSource
import com.tcgscanner.offline.data.remote.sources.TcgdexSource
import com.tcgscanner.offline.data.remote.sources.UrlCatalogSource
import com.tcgscanner.offline.data.repo.BackupRepository
import com.tcgscanner.offline.data.repo.CatalogRepository
import com.tcgscanner.offline.data.repo.CollectionRepository
import com.tcgscanner.offline.data.repo.CsvExporter
import com.tcgscanner.offline.data.repo.DeckRepository
import com.tcgscanner.offline.data.repo.PortfolioRepository
import com.tcgscanner.offline.data.repo.SyncCoordinator
import com.tcgscanner.offline.scanner.ModelConfig
import com.tcgscanner.offline.scanner.ModelRepository
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
    val http = Http()

    /** null until DataStore has delivered its first value. */
    val settingsState: StateFlow<AppSettings?> =
        settings.settings.stateIn(scope, SharingStarted.Eagerly, null)

    private val sources = listOf(
        UrlCatalogSource(http) { game: GameId -> settings.catalogUrlOnce(game) },
        MtgJsonSource(http), OptcgSource(http), LorcastSource(http), TcgdexSource(http)
    ).associateBy { it.id }

    val catalog = CatalogRepository(db, settings, sources)
    val portfolio = PortfolioRepository(db)
    val collection = CollectionRepository(db, portfolio)
    val decks = DeckRepository(db)
    val sync = SyncCoordinator(scope, catalog, portfolio)
    val csv = CsvExporter(db)
    val backup = BackupRepository(db)

    val embedder by lazy { TfliteEmbedder(File(app.filesDir, ModelConfig.FILE_NAME)) }
    val models by lazy { ModelRepository(app, scope, settings, embedder) }
    val scanMatcher = ScanMatcher(db)
    val visualMatcher by lazy { VisualMatcher(db, embedder) }
    val indexer by lazy { VisualIndexer(scope, db, http, embedder, visualMatcher) }

    val trade by lazy { TradeManager(app, db, settings, scope) }
}

@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class
)

package com.tcgscanner.offline.ui.screens

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import com.tcgscanner.offline.AppContainer
import com.tcgscanner.offline.BuildConfig
import com.tcgscanner.offline.R
import com.tcgscanner.offline.core.GameDef
import com.tcgscanner.offline.core.GameId
import com.tcgscanner.offline.core.Games
import com.tcgscanner.offline.data.remote.CatalogUrls
import com.tcgscanner.offline.data.remote.UrlNormalizer
import com.tcgscanner.offline.scanner.EnginePack
import com.tcgscanner.offline.scanner.EnginePacks
import com.tcgscanner.offline.scanner.ModelState
import com.tcgscanner.offline.work.SyncScheduler
import com.tcgscanner.offline.data.prefs.AppSettings
import com.tcgscanner.offline.data.db.SyncStateEntity
import com.tcgscanner.offline.data.repo.SyncUiState
import com.tcgscanner.offline.scanner.IndexState
import com.tcgscanner.offline.ui.appViewModel
import com.tcgscanner.offline.ui.nav.Routes
import com.tcgscanner.offline.ui.relativeTime
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SettingsUi(
    val settings: AppSettings = AppSettings(),
    val syncStates: Map<String, SyncStateEntity> = emptyMap()
)

class SettingsViewModel(private val c: AppContainer) : ViewModel() {
    val ui: StateFlow<SettingsUi> = kotlinx.coroutines.flow.combine(
        c.settingsState, c.catalog.observeSyncStates()
    ) { s, states -> SettingsUi(s ?: AppSettings(), states.associateBy { it.gameId }) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUi())

    val sync: StateFlow<SyncUiState> = c.sync.state
    val index: StateFlow<IndexState> get() = c.indexer.state

    private val _messages = MutableSharedFlow<Int>(extraBufferCapacity = 4)
    val messages: SharedFlow<Int> = _messages
    private val _text = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val text: SharedFlow<String> = _text

    /** Everything goes through WorkManager: the same worker serves the daily job, first runs and manual syncs. */
    fun syncAll() = SyncScheduler.enqueueNow(c.app, ui.value.settings.activeGames, wifiOnly = false, manual = true)
    fun syncGame(game: GameId) = SyncScheduler.enqueueNow(c.app, listOf(game), wifiOnly = false, manual = true)
    fun cancelSync() { SyncScheduler.cancelNow(c.app); c.sync.cancel() }
    fun setWifiOnly(v: Boolean) { viewModelScope.launch { c.settings.setWifiOnly(v) } }
    fun setAutoSync(v: Boolean) { viewModelScope.launch { c.settings.setAutoSync(v) } }
    fun setNickname(v: String) { viewModelScope.launch { c.settings.setNickname(v) } }
    fun setActive(g: Set<GameId>) { viewModelScope.launch { c.settings.setActiveGames(g) } }

    /**
     * Saves the user's database URL for [game] (blank restores the default) and queues a WorkManager sync so
     * Room is refreshed from the new link. Returns a string resource when the link is not acceptable.
     */
    fun saveCatalogUrl(game: GameId, raw: String): Int? {
        val clean = raw.trim()
        if (clean.isNotEmpty() && !UrlNormalizer.isValid(clean)) return R.string.catalog_url_invalid
        viewModelScope.launch {
            val before = UrlNormalizer.normalize(c.settings.catalogUrlOnce(game))
            val after = UrlNormalizer.normalize(clean)
            c.settings.setCatalogUrl(game, after)
            // A real source change: the worker purges this game's catalog rows before inserting the new data
            // (collection, decks and trade binder are untouched and re-linked afterwards).
            SyncScheduler.enqueueNow(c.app, listOf(game), wifiOnly = false, manual = true, purge = if (before != after) listOf(game) else emptyList())
            _messages.emit(R.string.catalog_url_saved)
        }
        return null
    }

    // ---- scan engine packs -----------------------------------------------------------------------------
    /** State of every downloadable pack (YOLO detector, PaddleOCR, Japanese OCR, EfficientNet-Lite0). */
    val packStates: StateFlow<Map<EnginePack, ModelState>> = kotlinx.coroutines.flow.combine(
        EnginePack.entries.map { pack -> c.packs.getValue(pack).state }
    ) { states -> EnginePack.entries.zip(states.toList()).toMap() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    fun downloadPack(pack: EnginePack) = c.packs.getValue(pack).download()
    fun deletePack(pack: EnginePack) = c.packs.getValue(pack).delete()
    fun saveModelBaseUrl(raw: String): Int? {
        val clean = raw.trim()
        if (clean.isNotEmpty() && !UrlNormalizer.isValid(clean)) return R.string.catalog_url_invalid
        viewModelScope.launch { c.settings.setModelBaseUrl(UrlNormalizer.normalize(clean)); _messages.emit(R.string.saved) }
        return null
    }

    fun exportCsv(context: Context, uri: Uri, games: Set<GameId>?) {
        viewModelScope.launch {
            val n = runCatching {
                context.contentResolver.openOutputStream(uri, "wt")!!.use { c.csv.export(games, it) }
            }
            if (n.isSuccess) _text.emit(context.getString(R.string.export_done, n.getOrDefault(0))) else _messages.emit(R.string.export_failed)
        }
    }

    fun backup(context: Context, uri: Uri) {
        viewModelScope.launch {
            val ok = runCatching {
                val json = c.backup.createBackup()
                context.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(json.toByteArray(Charsets.UTF_8)) }
            }.isSuccess
            _messages.emit(if (ok) R.string.backup_done else R.string.backup_failed)
        }
    }

    fun restore(context: Context, uri: Uri) {
        viewModelScope.launch {
            val ok = runCatching {
                val text = context.contentResolver.openInputStream(uri)!!.use { it.readBytes().toString(Charsets.UTF_8) }
                c.backup.restore(text)
            }.isSuccess
            _messages.emit(if (ok) R.string.restore_done else R.string.restore_failed)
        }
    }

    fun importCatalog(context: Context, uri: Uri, game: GameDef) {
        viewModelScope.launch {
            val n = runCatching {
                val text = context.contentResolver.openInputStream(uri)!!.use { it.readBytes().toString(Charsets.UTF_8) }
                c.catalog.importCatalogJson(game, text)
            }
            if (n.isSuccess) _text.emit(context.getString(R.string.import_done, n.getOrDefault(0))) else _messages.emit(R.string.import_failed)
        }
    }

    fun buildIndex(game: GameId) = c.indexer.start(game)
    fun cancelIndex() = c.indexer.cancel()
    fun clearIndex(game: GameId) { viewModelScope.launch { c.indexer.clear(game) } }
    fun indexedCounts(game: GameId) = c.db.cards().observeSignatureCount(game.code)
    fun imageCounts(game: GameId) = c.db.cards().observeImageCardCount(game.code)

    fun eraseAll(then: () -> Unit) {
        viewModelScope.launch {
            c.backup.eraseEverything()
            c.settings.clearAll()
            then()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(nav: NavController, onBack: () -> Unit) {
    val vm = appViewModel { SettingsViewModel(it) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val sync by vm.sync.collectAsStateWithLifecycle()
    val index by vm.index.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val s = ui.settings
    val current = s.currentGame?.let { Games[it] }

    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(context.getString(it)) } }
    LaunchedEffect(Unit) { vm.text.collect { snackbar.showSnackbar(it) } }

    var exportScope by remember { mutableStateOf<Set<GameId>?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) vm.exportCsv(context, uri, exportScope)
    }
    val backupLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri -> if (uri != null) vm.backup(context, uri) }
    val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) vm.restore(context, uri) }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null && current != null) vm.importCatalog(context, uri, current) }

    var nickname by remember(s.nickname) { mutableStateOf(s.nickname) }
    var confirmErase by remember { mutableStateOf(false) }
    var confirmRestore by remember { mutableStateOf(false) }
    val packStates by vm.packStates.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back)) } }
            )
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ---- Sync ---------------------------------------------------------------------------------------
            Section(stringResource(R.string.section_sync)) {
                Text(stringResource(R.string.sync_explainer), style = MaterialTheme.typography.bodyMedium)
                Button(onClick = vm::syncAll, enabled = !sync.running && s.activeGames.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.sync_prices))
                }
                if (sync.running) {
                    val fraction = sync.fraction
                    if (fraction != null) LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                    else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text("${sync.currentGame?.let { stringResource(Games[it].nameRes) }.orEmpty()} ${sync.message}", style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = vm::cancelSync) { Text(stringResource(R.string.cancel)) }
                }
                s.activeGames.sortedBy { it.ordinal }.forEach { id ->
                    val st = ui.syncStates[id.code]
                    Column {
                        Text(stringResource(Games[id].nameRes), style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (st == null) stringResource(R.string.never_synced)
                            else stringResource(R.string.sync_state_line, relativeTime(st.lastSyncAt), st.source, st.cardCount),
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        val failure = sync.results[id]?.takeIf { !it.success && it.error != com.tcgscanner.offline.data.repo.SyncResult.NO_SOURCE }
                        if (failure != null) {
                            Text(
                                stringResource(R.string.sync_failed, failure.error.orEmpty()),
                                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
                SwitchRow(stringResource(R.string.auto_sync), stringResource(R.string.auto_sync_hint), s.autoSync, vm::setAutoSync)
                SwitchRow(stringResource(R.string.wifi_only), stringResource(R.string.wifi_only_hint), s.wifiOnlySync, vm::setWifiOnly)
            }

            // ---- Export -----------------------------------------------------------------------------------
            Section(stringResource(R.string.section_export)) {
                Text(stringResource(R.string.export_explainer), style = MaterialTheme.typography.bodyMedium)
                Button(
                    onClick = { exportScope = s.currentGame?.let { setOf(it) }; exportLauncher.launch("tcg-collection-${s.currentGame?.code ?: "all"}.csv") },
                    enabled = s.currentGame != null, modifier = Modifier.fillMaxWidth()
                ) { Text(stringResource(R.string.export_csv_game)) }
                OutlinedButton(
                    onClick = { exportScope = null; exportLauncher.launch("tcg-collection-all.csv") },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(stringResource(R.string.export_csv_all)) }
                HorizontalDivider()
                Text(stringResource(R.string.backup_explainer), style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { backupLauncher.launch("tcg-backup.json") }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.backup)) }
                    OutlinedButton(onClick = { confirmRestore = true }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.restore)) }
                }
            }

            // ---- Trading ----------------------------------------------------------------------------------
            Section(stringResource(R.string.section_trade)) {
                OutlinedTextField(
                    nickname, { nickname = it.take(32) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.trade_nickname)) }
                )
                OutlinedButton(onClick = { vm.setNickname(nickname) }) { Text(stringResource(R.string.save)) }
            }

            // ---- Scanner artwork index ------------------------------------------------------------------------
            if (current != null) {
                val indexed by vm.indexedCounts(current.id).collectAsStateWithLifecycle(initialValue = 0)
                val withImages by vm.imageCounts(current.id).collectAsStateWithLifecycle(initialValue = 0)
                Section(stringResource(R.string.section_artwork)) {
                    Text(stringResource(R.string.artwork_explainer, withImages * 20 / 1024 + 1), style = MaterialTheme.typography.bodyMedium)
                    Text(stringResource(R.string.artwork_progress, indexed, withImages), style = MaterialTheme.typography.titleMedium)
                    if (index.running) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        TextButton(onClick = vm::cancelIndex) { Text(stringResource(R.string.cancel)) }
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { vm.buildIndex(current.id) }, enabled = withImages > indexed) { Text(stringResource(R.string.artwork_build)) }
                            OutlinedButton(onClick = { vm.clearIndex(current.id) }, enabled = indexed > 0) { Text(stringResource(R.string.artwork_clear)) }
                        }
                    }
                }

                Section(stringResource(R.string.section_catalog_import, stringResource(current.nameRes))) {
                    Text(stringResource(R.string.catalog_import_explainer), style = MaterialTheme.typography.bodyMedium)
                    OutlinedButton(onClick = { importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.import_catalog_file))
                    }
                }
            }

            // ---- Card database URLs (community sources, user-overridable) ------------------------------------
            Section(stringResource(R.string.section_catalog_urls)) {
                Text(stringResource(R.string.catalog_urls_explainer), style = MaterialTheme.typography.bodyMedium)
                s.activeGames.sortedBy { it.ordinal }.forEach { id ->
                    CatalogUrlRow(
                        game = Games[id],
                        saved = s.catalogUrls[id].orEmpty(),
                        default = CatalogUrls.default(id),
                        busy = sync.running,
                        onSave = { vm.saveCatalogUrl(id, it) },
                        onSyncNow = { vm.syncGame(id) }
                    )
                }
                if (s.activeGames.isEmpty()) Text(stringResource(R.string.hub_empty_message), style = MaterialTheme.typography.bodyMedium)
            }

            // ---- Scan engine packs (YOLO11n + PaddleOCR + EfficientNet-Lite0) -----------------------------------------
            Section(stringResource(R.string.section_scan_engine)) {
                Text(stringResource(R.string.scan_engine_explainer), style = MaterialTheme.typography.bodyMedium)
                EnginePack.entries.forEach { pack ->
                    PackRow(
                        pack = pack,
                        state = packStates[pack] ?: ModelState.Missing,
                        onDownload = { vm.downloadPack(pack) },
                        onDelete = { vm.deletePack(pack) }
                    )
                }
                var base by remember(s.modelBaseUrl) { mutableStateOf(s.modelBaseUrl) }
                var baseError by remember { mutableStateOf<Int?>(null) }
                OutlinedTextField(
                    base, { base = it.take(400); baseError = null }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.model_url_label)) },
                    placeholder = { Text(EnginePacks.DEFAULT_BASE_URL) },
                    isError = baseError != null,
                    supportingText = { baseError?.let { Text(stringResource(it)) } }
                )
                OutlinedButton(onClick = { baseError = vm.saveModelBaseUrl(base) }, enabled = base != s.modelBaseUrl) { Text(stringResource(R.string.save)) }
            }

            // ---- About / data ---------------------------------------------------------------------------------
            Section(stringResource(R.string.section_about)) {
                OutlinedButton(onClick = { nav.navigate(Routes.ABOUT) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.about_privacy)) }
                OutlinedButton(onClick = { confirmErase = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.erase_all), color = MaterialTheme.colorScheme.error)
                }
                Text(stringResource(R.string.version_line, BuildConfig.VERSION_NAME), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    if (confirmErase) {
        AlertDialog(
            onDismissRequest = { confirmErase = false },
            title = { Text(stringResource(R.string.erase_all)) },
            text = { Text(stringResource(R.string.erase_all_confirm)) },
            confirmButton = {
                TextButton(onClick = { confirmErase = false; vm.eraseAll { nav.navigate(Routes.HUB) { popUpTo(Routes.HUB) { inclusive = true } } } }) {
                    Text(stringResource(R.string.erase), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmErase = false }) { Text(stringResource(R.string.cancel)) } }
        )
    }
    if (confirmRestore) {
        AlertDialog(
            onDismissRequest = { confirmRestore = false },
            title = { Text(stringResource(R.string.restore)) },
            text = { Text(stringResource(R.string.restore_confirm)) },
            confirmButton = { TextButton(onClick = { confirmRestore = false; restoreLauncher.launch(arrayOf("application/json", "*/*")) }) { Text(stringResource(R.string.restore)) } },
            dismissButton = { TextButton(onClick = { confirmRestore = false }) { Text(stringResource(R.string.cancel)) } }
        )
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            content()
        }
    }
}

@Composable
private fun SwitchRow(title: String, hint: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(hint, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun CatalogUrlRow(
    game: GameDef,
    saved: String,
    default: String?,
    busy: Boolean,
    onSave: (String) -> Int?,
    onSyncNow: () -> Unit
) {
    var text by remember(saved) { mutableStateOf(saved) }
    var error by remember { mutableStateOf<Int?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(game.nameRes), style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = text,
            onValueChange = { text = it.take(500); error = null },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.catalog_url_label)) },
            placeholder = { Text(default ?: "https://…") },
            isError = error != null,
            supportingText = {
                Text(
                    error?.let { stringResource(it) }
                        ?: if (default != null) stringResource(R.string.catalog_url_default, default) else stringResource(R.string.catalog_url_needed)
                )
            }
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { error = onSave(text) }, enabled = text.trim() != saved) { Text(stringResource(R.string.save)) }
            TextButton(onClick = { text = ""; error = onSave("") }, enabled = saved.isNotEmpty()) { Text(stringResource(R.string.catalog_url_reset)) }
            TextButton(onClick = onSyncNow, enabled = !busy) { Text(stringResource(R.string.catalog_url_sync)) }
        }
    }
}

@Composable
private fun PackRow(pack: EnginePack, state: ModelState, onDownload: () -> Unit, onDelete: () -> Unit) {
    val name = stringResource(
        when (pack) {
            EnginePack.DETECTOR -> R.string.pack_detector_name
            EnginePack.OCR -> R.string.pack_ocr_name
            EnginePack.OCR_JA -> R.string.pack_ocr_ja_name
            EnginePack.EMBEDDER -> R.string.pack_embedder_name
        }
    )
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.pack_size_mb, EnginePacks.approxMb(pack)), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            when (state) {
                is ModelState.Ready -> OutlinedButton(onClick = onDelete) { Text(stringResource(R.string.model_delete)) }
                is ModelState.Downloading -> Text(state.progress?.let { stringResource(R.string.model_percent, (it * 100).toInt()) } ?: stringResource(R.string.model_downloading))
                is ModelState.Failed -> Button(onClick = onDownload) { Text(stringResource(R.string.model_retry)) }
                is ModelState.Missing -> Button(onClick = onDownload) { Text(stringResource(R.string.model_download)) }
            }
        }
        if (state is ModelState.Downloading) {
            val p = state.progress
            if (p != null) LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth()) else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        if (state is ModelState.Failed) Text(stringResource(R.string.model_failed), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        if (state is ModelState.Ready) Text(stringResource(R.string.model_ready), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    }
}

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
import androidx.compose.ui.text.input.PasswordVisualTransformation
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
import com.tcgscanner.offline.data.prefs.ApiKeys
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

    fun syncAll() = c.sync.start(ui.value.settings.activeGames)
    fun cancelSync() = c.sync.cancel()
    fun setWifiOnly(v: Boolean) { viewModelScope.launch { c.settings.setWifiOnly(v) } }
    fun setAutoSync(v: Boolean) { viewModelScope.launch { c.settings.setAutoSync(v) } }
    fun setNickname(v: String) { viewModelScope.launch { c.settings.setNickname(v) } }
    fun setKeys(k: ApiKeys) { viewModelScope.launch { c.settings.setKeys(k); _messages.emit(R.string.saved) } }
    fun setActive(g: Set<GameId>) { viewModelScope.launch { c.settings.setActiveGames(g) } }

    fun customUrl(game: GameId) = c.settings.customUrl(game)
    fun setCustomUrl(game: GameId, url: String) {
        viewModelScope.launch {
            if (url.isNotBlank() && !url.startsWith("https://")) { _messages.emit(R.string.custom_url_https); return@launch }
            c.settings.setCustomUrl(game, url); _messages.emit(R.string.saved)
        }
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

    var keys by remember(s.keys) { mutableStateOf(s.keys) }
    var nickname by remember(s.nickname) { mutableStateOf(s.nickname) }
    var confirmErase by remember { mutableStateOf(false) }
    var confirmRestore by remember { mutableStateOf(false) }
    var customUrl by remember(current?.id) { mutableStateOf("") }
    val storedCustom = current?.let { vm.customUrl(it.id).collectAsStateWithLifecycle(initialValue = "").value }.orEmpty()
    LaunchedEffect(storedCustom) { customUrl = storedCustom }

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
                        val failure = sync.results[id]?.takeIf { !it.success }
                        if (failure != null) {
                            Text(
                                if (failure.error == "NO_SOURCE") stringResource(R.string.sync_no_source) else stringResource(R.string.sync_failed, failure.error.orEmpty()),
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
                    OutlinedTextField(
                        customUrl, { customUrl = it.take(300) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.custom_catalog_url)) }
                    )
                    OutlinedButton(onClick = { vm.setCustomUrl(current.id, customUrl) }) { Text(stringResource(R.string.save)) }
                }
            }

            // ---- API keys ------------------------------------------------------------------------------------
            Section(stringResource(R.string.section_keys)) {
                Text(stringResource(R.string.keys_explainer), style = MaterialTheme.typography.bodyMedium)
                KeyField(stringResource(R.string.key_pokemon), keys.pokemonTcgKey) { keys = keys.copy(pokemonTcgKey = it) }
                KeyField(stringResource(R.string.key_tcgplayer_id), keys.tcgplayerClientId) { keys = keys.copy(tcgplayerClientId = it) }
                KeyField(stringResource(R.string.key_tcgplayer_secret), keys.tcgplayerClientSecret) { keys = keys.copy(tcgplayerClientSecret = it) }
                KeyField(stringResource(R.string.key_pricecharting), keys.priceChartingToken) { keys = keys.copy(priceChartingToken = it) }
                Button(onClick = { vm.setKeys(keys) }) { Text(stringResource(R.string.save)) }
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
private fun KeyField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value, { onChange(it.take(200)) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        label = { Text(label) }, visualTransformation = PasswordVisualTransformation()
    )
}

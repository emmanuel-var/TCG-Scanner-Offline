package com.tcgscanner.offline.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.tcgscanner.offline.core.GameId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

data class AppSettings(
    val activeGames: Set<GameId> = emptySet(),
    val currentGame: GameId? = null,
    val wifiOnlySync: Boolean = true,
    val autoSync: Boolean = true,
    /** Per-game override of the catalog URL; a game without an entry uses CatalogUrls.default. */
    val catalogUrls: Map<GameId, String> = emptyMap(),
    /** Override of the visual-model download URL; blank = the built-in default. */
    val modelUrl: String = "",
    val nickname: String = ""
)

class SettingsStore(private val context: Context) {
    private object K {
        val active = stringSetPreferencesKey("active_games")
        val current = stringPreferencesKey("current_game")
        val wifiOnly = booleanPreferencesKey("wifi_only")
        val autoSync = booleanPreferencesKey("auto_sync")
        val nickname = stringPreferencesKey("nickname")
        val modelUrl = stringPreferencesKey("model_url")
        fun catalogUrl(game: GameId) = stringPreferencesKey("catalog_url_${game.code}")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { p -> p.toSettings() }

    private fun Preferences.toSettings() = AppSettings(
        activeGames = (this[K.active] ?: emptySet()).mapNotNull { GameId.fromCode(it) }.toSet(),
        currentGame = GameId.fromCode(this[K.current]),
        wifiOnlySync = this[K.wifiOnly] ?: true,
        autoSync = this[K.autoSync] ?: true,
        catalogUrls = GameId.entries.mapNotNull { g -> this[K.catalogUrl(g)]?.takeIf { it.isNotBlank() }?.let { g to it } }.toMap(),
        modelUrl = this[K.modelUrl].orEmpty(),
        nickname = this[K.nickname].orEmpty()
    )

    suspend fun current(): AppSettings = context.dataStore.data.map { it.toSettings() }.first()

    suspend fun setActiveGames(games: Set<GameId>) = context.dataStore.edit { p ->
        p[K.active] = games.map { it.code }.toSet()
        val cur = GameId.fromCode(p[K.current])
        if (cur != null && cur !in games) p.remove(K.current)
    }

    suspend fun setCurrentGame(game: GameId?) = context.dataStore.edit { p ->
        if (game == null) p.remove(K.current) else p[K.current] = game.code
    }

    suspend fun setWifiOnly(v: Boolean) = context.dataStore.edit { it[K.wifiOnly] = v }
    suspend fun setAutoSync(v: Boolean) = context.dataStore.edit { it[K.autoSync] = v }
    suspend fun setNickname(v: String) = context.dataStore.edit { it[K.nickname] = v.take(32) }

    suspend fun setModelUrl(url: String) = context.dataStore.edit {
        val clean = url.trim()
        if (clean.isEmpty()) it.remove(K.modelUrl) else it[K.modelUrl] = clean
    }

    /** The user's override for [game] ("" when none). */
    suspend fun catalogUrlOnce(game: GameId): String =
        context.dataStore.data.map { it[K.catalogUrl(game)].orEmpty() }.first()

    /** Blank [url] removes the override so the game goes back to its default source. */
    suspend fun setCatalogUrl(game: GameId, url: String) = context.dataStore.edit {
        val clean = url.trim()
        if (clean.isEmpty()) it.remove(K.catalogUrl(game)) else it[K.catalogUrl(game)] = clean
    }

    suspend fun clearAll() = context.dataStore.edit { it.clear() }
}

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

/** Optional credentials the user may paste in. They never leave the device except to the API they belong to. */
data class ApiKeys(
    val pokemonTcgKey: String = "",
    val tcgplayerClientId: String = "",
    val tcgplayerClientSecret: String = "",
    val priceChartingToken: String = ""
)

data class AppSettings(
    val activeGames: Set<GameId> = emptySet(),
    val currentGame: GameId? = null,
    val wifiOnlySync: Boolean = true,
    val autoSync: Boolean = true,
    val keys: ApiKeys = ApiKeys(),
    val nickname: String = ""
)

class SettingsStore(private val context: Context) {
    private object K {
        val active = stringSetPreferencesKey("active_games")
        val current = stringPreferencesKey("current_game")
        val wifiOnly = booleanPreferencesKey("wifi_only")
        val autoSync = booleanPreferencesKey("auto_sync")
        val pokemonKey = stringPreferencesKey("key_pokemon_tcg")
        val tcgId = stringPreferencesKey("key_tcgplayer_id")
        val tcgSecret = stringPreferencesKey("key_tcgplayer_secret")
        val pcToken = stringPreferencesKey("key_pricecharting")
        val nickname = stringPreferencesKey("nickname")
        fun customUrl(game: GameId) = stringPreferencesKey("custom_url_${game.code}")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { p -> p.toSettings() }

    private fun Preferences.toSettings() = AppSettings(
        activeGames = (this[K.active] ?: emptySet()).mapNotNull { GameId.fromCode(it) }.toSet(),
        currentGame = GameId.fromCode(this[K.current]),
        wifiOnlySync = this[K.wifiOnly] ?: true,
        autoSync = this[K.autoSync] ?: true,
        keys = ApiKeys(
            pokemonTcgKey = this[K.pokemonKey].orEmpty(),
            tcgplayerClientId = this[K.tcgId].orEmpty(),
            tcgplayerClientSecret = this[K.tcgSecret].orEmpty(),
            priceChartingToken = this[K.pcToken].orEmpty()
        ),
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

    suspend fun setKeys(keys: ApiKeys) = context.dataStore.edit {
        it[K.pokemonKey] = keys.pokemonTcgKey.trim()
        it[K.tcgId] = keys.tcgplayerClientId.trim()
        it[K.tcgSecret] = keys.tcgplayerClientSecret.trim()
        it[K.pcToken] = keys.priceChartingToken.trim()
    }

    fun customUrl(game: GameId): Flow<String> = context.dataStore.data.map { it[K.customUrl(game)].orEmpty() }

    suspend fun customUrlOnce(game: GameId): String = customUrl(game).first()

    suspend fun setCustomUrl(game: GameId, url: String) = context.dataStore.edit { it[K.customUrl(game)] = url.trim() }

    suspend fun clearAll() = context.dataStore.edit { it.clear() }
}

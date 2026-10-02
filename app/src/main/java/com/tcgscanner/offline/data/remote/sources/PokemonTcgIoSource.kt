package com.tcgscanner.offline.data.remote.sources

import com.tcgscanner.offline.core.CardCategory
import com.tcgscanner.offline.core.CardVariant
import com.tcgscanner.offline.core.GameDef
import com.tcgscanner.offline.core.SourceId
import com.tcgscanner.offline.data.prefs.ApiKeys
import com.tcgscanner.offline.data.remote.BatchedSink
import com.tcgscanner.offline.data.remote.CatalogSink
import com.tcgscanner.offline.data.remote.CatalogSource
import com.tcgscanner.offline.data.remote.Http
import com.tcgscanner.offline.data.remote.RemoteCard
import com.tcgscanner.offline.data.remote.SyncProgress
import com.tcgscanner.offline.data.remote.a
import com.tcgscanner.offline.data.remote.d
import com.tcgscanner.offline.data.remote.int
import com.tcgscanner.offline.data.remote.o
import com.tcgscanner.offline.data.remote.obj
import com.tcgscanner.offline.data.remote.s
import kotlinx.serialization.json.JsonObject

/** Pokémon TCG API (pokemontcg.io): full English catalog with TCGplayer market prices per finish. */
class PokemonTcgIoSource(private val http: Http) : CatalogSource {
    override val id = SourceId.POKEMON_TCG_IO

    override suspend fun sync(game: GameDef, keys: ApiKeys, sink: CatalogSink, progress: (SyncProgress) -> Unit) {
        val headers = if (keys.pokemonTcgKey.isNotBlank()) mapOf("X-Api-Key" to keys.pokemonTcgKey) else emptyMap()
        val batched = BatchedSink(sink)
        var page = 1
        var total = Int.MAX_VALUE
        while ((page - 1) * PAGE_SIZE < total) {
            val url = "https://api.pokemontcg.io/v2/cards?page=$page&pageSize=$PAGE_SIZE" +
                "&select=id,name,supertype,rarity,number,set,images,tcgplayer"
            val root = http.getWithRetry(url, headers, attempts = 5) { body ->
                kotlinx.serialization.json.Json.parseToJsonElement(body.string())
            }.obj() ?: break
            total = root["totalCount"].int() ?: 0
            val data = root.a("data") ?: break
            if (data.isEmpty()) break
            data.forEach { el -> el.obj()?.let { toCard(it) }?.let { batched.add(it) } }
            progress(SyncProgress("Pokémon TCG API · $page/${(total + PAGE_SIZE - 1) / PAGE_SIZE}", minOf(1f, page * PAGE_SIZE.toFloat() / maxOf(total, 1))))
            page++
        }
        batched.flush()
    }

    private fun toCard(o: JsonObject): RemoteCard? {
        val id = o.s("id") ?: return null
        val set = o.o("set")
        val prices = HashMap<CardVariant, Double>()
        o.o("tcgplayer")?.o("prices")?.forEach { (key, value) ->
            val p = value.obj() ?: return@forEach
            val usd = p.d("market") ?: p.d("mid") ?: p.d("low") ?: return@forEach
            val variant = when (key) {
                "normal", "unlimited" -> CardVariant.NORMAL
                "holofoil", "unlimitedHolofoil" -> CardVariant.HOLO
                "reverseHolofoil" -> CardVariant.REVERSE_HOLO
                "1stEditionNormal" -> CardVariant.FIRST_EDITION
                "1stEditionHolofoil" -> CardVariant.FIRST_ED_HOLO
                else -> CardVariant.NORMAL
            }
            prices.putIfAbsent(variant, usd)
        }
        val category = when (o.s("supertype")) {
            "Trainer" -> CardCategory.TRAINER
            "Energy" -> CardCategory.RESOURCE
            "Pokémon", "Pokemon" -> CardCategory.CREATURE
            else -> CardCategory.OTHER
        }
        return RemoteCard(
            sourceId = id,
            setCode = set?.s("id") ?: id.substringBefore('-'),
            setName = set?.s("name") ?: "?",
            setReleaseDate = set?.s("releaseDate")?.replace('/', '-'),
            setTotal = set?.get("printedTotal").int() ?: set?.get("total").int(),
            number = o.s("number") ?: id.substringAfter('-'),
            name = o.s("name") ?: return null,
            rarity = o.s("rarity"),
            category = category,
            imageUrl = o.o("images")?.s("small"),
            prices = prices
        )
    }

    private companion object {
        const val PAGE_SIZE = 250
    }
}

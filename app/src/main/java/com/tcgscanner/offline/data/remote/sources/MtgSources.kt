package com.tcgscanner.offline.data.remote.sources

import com.tcgscanner.offline.core.CardCategory
import com.tcgscanner.offline.core.CardVariant
import com.tcgscanner.offline.core.GameDef
import com.tcgscanner.offline.core.SourceId
import com.tcgscanner.offline.data.remote.BatchedSink
import com.tcgscanner.offline.data.remote.CatalogSink
import com.tcgscanner.offline.data.remote.CatalogSource
import com.tcgscanner.offline.data.remote.Http
import com.tcgscanner.offline.data.remote.JsonStream
import com.tcgscanner.offline.data.remote.RemoteCard
import com.tcgscanner.offline.data.remote.SyncProgress
import com.tcgscanner.offline.data.remote.a
import com.tcgscanner.offline.data.remote.d
import com.tcgscanner.offline.data.remote.mtgCategory
import com.tcgscanner.offline.data.remote.int
import com.tcgscanner.offline.data.remote.o
import com.tcgscanner.offline.data.remote.obj
import com.tcgscanner.offline.data.remote.s
import com.tcgscanner.offline.data.remote.str
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** MTGJSON fallback: set list + one file per set, prices from AllPricesToday (TCGplayer retail). */
class MtgJsonSource(private val http: Http) : CatalogSource {
    override val id = SourceId.MTGJSON

    private class Price(var normal: Double? = null, var foil: Double? = null, var etched: Double? = null)

    override suspend fun sync(game: GameDef, sink: CatalogSink, progress: (SyncProgress) -> Unit) {
        val prices = HashMap<String, Price>()
        progress(SyncProgress("MTGJSON · prices"))
        try {
            http.getWithRetry("https://mtgjson.com/api/v5/AllPricesToday.json", attempts = 2) { body ->
                JsonStream.forEachObjectEntry(body, "data") { uuid, entry ->
                    val retail = entry.o("paper")?.o("tcgplayer")?.o("retail") ?: return@forEachObjectEntry
                    val p = Price()
                    p.normal = latest(retail.o("normal"))
                    p.foil = latest(retail.o("foil"))
                    p.etched = latest(retail.o("etched"))
                    if (p.normal != null || p.foil != null || p.etched != null) prices[uuid] = p
                }
            }
        } catch (e: java.io.IOException) {
            // Catalog without prices is still useful; carry on.
        }

        val sets = ArrayList<JsonObject>()
        http.getWithRetry("https://mtgjson.com/api/v5/SetList.json") { body ->
            JsonStream.forEachArrayElement(body, "data") { sets.add(it) }
        }
        val batched = BatchedSink(sink, 1000)
        sets.forEachIndexed { index, set ->
            val code = set.s("code") ?: return@forEachIndexed
            progress(SyncProgress("MTGJSON · $code (${index + 1}/${sets.size})", (index + 1f) / sets.size))
            val root = try {
                http.getWithRetry("https://mtgjson.com/api/v5/$code.json", attempts = 2) { Json.parseToJsonElement(it.string()) }.obj()
            } catch (e: java.io.IOException) {
                null
            } ?: return@forEachIndexed
            val data = root.o("data") ?: return@forEachIndexed
            val setName = data.s("name") ?: set.s("name") ?: code
            val release = data.s("releaseDate")
            data.a("cards")?.forEach { el ->
                val c = el.obj() ?: return@forEach
                if (c["isOnlineOnly"].str() == "true") return@forEach
                val uuid = c.s("uuid") ?: return@forEach
                val sf = c.o("identifiers")?.s("scryfallId")
                val pr = prices[uuid]
                val map = HashMap<CardVariant, Double>()
                pr?.normal?.let { map[CardVariant.NORMAL] = it }
                pr?.foil?.let { map[CardVariant.FOIL] = it }
                pr?.etched?.let { map[CardVariant.ETCHED] = it }
                batched.add(
                    RemoteCard(
                        sourceId = sf ?: uuid,
                        setCode = code.uppercase(),
                        setName = setName,
                        setReleaseDate = release,
                        number = c.s("number") ?: return@forEach,
                        name = c.s("name") ?: return@forEach,
                        rarity = c.s("rarity"),
                        category = mtgCategory(c.s("type")),
                        imageUrl = sf?.let { "https://api.scryfall.com/cards/$it?format=image&version=small" },
                        prices = map
                    )
                )
            }
        }
        batched.flush()
    }

    private fun latest(dates: JsonObject?): Double? =
        dates?.entries?.maxByOrNull { it.key }?.value?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content?.toDoubleOrNull() }
}

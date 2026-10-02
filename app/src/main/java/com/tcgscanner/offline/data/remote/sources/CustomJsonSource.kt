package com.tcgscanner.offline.data.remote.sources

import com.tcgscanner.offline.core.CardCategory
import com.tcgscanner.offline.core.CardVariant
import com.tcgscanner.offline.core.GameDef
import com.tcgscanner.offline.core.GameId
import com.tcgscanner.offline.core.SourceId
import com.tcgscanner.offline.data.prefs.ApiKeys
import com.tcgscanner.offline.data.remote.BatchedSink
import com.tcgscanner.offline.data.remote.CatalogSink
import com.tcgscanner.offline.data.remote.CatalogSource
import com.tcgscanner.offline.data.remote.Http
import com.tcgscanner.offline.data.remote.RemoteCard
import com.tcgscanner.offline.data.remote.RemoteGradedPrice
import com.tcgscanner.offline.data.remote.SourceNotConfigured
import com.tcgscanner.offline.data.remote.SyncProgress
import com.tcgscanner.offline.data.remote.a
import com.tcgscanner.offline.data.remote.d
import com.tcgscanner.offline.data.remote.int
import com.tcgscanner.offline.data.remote.o
import com.tcgscanner.offline.data.remote.obj
import com.tcgscanner.offline.data.remote.s
import kotlinx.serialization.json.JsonObject

/**
 * Open catalog format, usable for any game (community dumps, your own spreadsheets converted to JSON):
 *
 * { "cards": [ { "id": "x", "setCode": "S1", "setName": "Set 1", "number": "001", "name": "Card",
 *               "rarity": "Rare", "category": "creature", "imageUrl": "https://...",
 *               "releaseDate": "2025-01-31", "setTotal": 120,
 *               "prices": { "normal": 1.5, "foil": 4.0 },
 *               "graded": [ { "company": "PSA", "grade": 10, "usd": 120.0 } ] } ] }
 *
 * A bare top-level array of cards is accepted too. The same parser backs "Import catalog from file".
 */
object CustomCatalogParser {
    /** Parses a whole catalog document (array of cards, or an object with a "cards" array). */
    suspend fun parseText(text: String, sink: CatalogSink) {
        val root = kotlinx.serialization.json.Json.parseToJsonElement(text)
        val arr = (root as? kotlinx.serialization.json.JsonArray) ?: root.obj()?.a("cards")
            ?: error("Expected a JSON array or an object with a \"cards\" array")
        val batched = BatchedSink(sink)
        arr.forEach { el -> el.obj()?.let { toCard(it) }?.let { batched.add(it) } }
        batched.flush()
    }

    fun toCard(o: JsonObject): RemoteCard? {
        val name = o.s("name") ?: return null
        val number = o.s("number") ?: return null
        val setCode = o.s("setCode") ?: "CUSTOM"
        val prices = HashMap<CardVariant, Double>()
        o.o("prices")?.forEach { (k, v) ->
            (v as? kotlinx.serialization.json.JsonPrimitive)?.content?.toDoubleOrNull()?.let { prices[CardVariant.fromCode(k)] = it }
        }
        val graded = o.a("graded")?.mapNotNull { it.obj() }?.mapNotNull { g ->
            val usd = g.d("usd") ?: return@mapNotNull null
            val grade = g.d("grade") ?: return@mapNotNull null
            RemoteGradedPrice(g.s("company") ?: "ANY", (grade * 10).toInt(), usd)
        }.orEmpty()
        return RemoteCard(
            sourceId = o.s("id") ?: "$setCode:$number:$name",
            setCode = setCode,
            setName = o.s("setName") ?: setCode,
            setReleaseDate = o.s("releaseDate"),
            setTotal = o["setTotal"].int(),
            number = number,
            name = name,
            rarity = o.s("rarity"),
            category = CardCategory.fromCode(o.s("category").orEmpty()),
            imageUrl = o.s("imageUrl"),
            prices = prices,
            graded = graded
        )
    }
}

/** Per-game user-supplied catalog URL (e.g. a GitHub raw JSON dump). Always the last fallback. */
class CustomJsonSource(
    private val http: Http,
    private val urlFor: suspend (GameId) -> String
) : CatalogSource {
    override val id = SourceId.CUSTOM

    override suspend fun sync(game: GameDef, keys: ApiKeys, sink: CatalogSink, progress: (SyncProgress) -> Unit) {
        val url = urlFor(game.id)
        if (url.isBlank()) throw SourceNotConfigured("No custom catalog URL")
        require(url.startsWith("https://")) { "Custom catalog URL must use https" }
        progress(SyncProgress("Custom catalog · downloading"))
        http.getWithRetry(url, attempts = 3) { body -> CustomCatalogParser.parseText(body.string(), sink) }
    }
}

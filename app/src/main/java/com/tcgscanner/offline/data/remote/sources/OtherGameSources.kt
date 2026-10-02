package com.tcgscanner.offline.data.remote.sources

import com.tcgscanner.offline.core.CardCategory
import com.tcgscanner.offline.core.CardVariant
import com.tcgscanner.offline.core.GameDef
import com.tcgscanner.offline.core.SourceId
import com.tcgscanner.offline.data.remote.BatchedSink
import com.tcgscanner.offline.data.remote.CatalogSink
import com.tcgscanner.offline.data.remote.CatalogSource
import com.tcgscanner.offline.data.remote.Http
import com.tcgscanner.offline.data.remote.RemoteCard
import com.tcgscanner.offline.data.remote.SyncProgress
import com.tcgscanner.offline.data.remote.a
import com.tcgscanner.offline.data.remote.arr
import com.tcgscanner.offline.data.remote.d
import com.tcgscanner.offline.data.remote.int
import com.tcgscanner.offline.data.remote.o
import com.tcgscanner.offline.data.remote.obj
import com.tcgscanner.offline.data.remote.s
import com.tcgscanner.offline.data.remote.str
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

private fun JsonObject.first(vararg keys: String): String? = keys.firstNotNullOfOrNull { s(it)?.takeIf { v -> v.isNotBlank() } }

/** The array of cards may be returned bare or wrapped in {"results"|"data": [...]}. */
private fun JsonElement.cardArray() = arr() ?: obj()?.let { it.a("results") ?: it.a("data") }

/** OPTCG API (community project): One Piece set, starter-deck and promo cards with market prices. */
class OptcgSource(private val http: Http) : CatalogSource {
    override val id = SourceId.OPTCG

    override suspend fun sync(game: GameDef, sink: CatalogSink, progress: (SyncProgress) -> Unit) {
        val batched = BatchedSink(sink)
        val endpoints = listOf("allSetCards", "allSTCards", "allPromoCards")
        endpoints.forEachIndexed { i, ep ->
            progress(SyncProgress("OPTCG API · $ep", i / endpoints.size.toFloat()))
            val arr = http.getJson("https://optcgapi.com/api/$ep/").cardArray() ?: return@forEachIndexed
            arr.forEach { el ->
                val o = el.obj() ?: return@forEach
                val code = o.first("card_set_id", "card_id") ?: return@forEach
                val imageId = o.first("card_image_id") ?: code
                val parallel = imageId.contains("_p", ignoreCase = true)
                val price = o.d("market_price") ?: o.d("inventory_price")
                val type = o.first("card_type").orEmpty()
                batched.add(
                    RemoteCard(
                        sourceId = imageId,
                        setCode = code.substringBefore('-'),
                        setName = o.first("set_name") ?: code.substringBefore('-'),
                        number = code,
                        name = o.first("card_name", "name") ?: return@forEach,
                        rarity = o.first("rarity"),
                        suffix = if (parallel) "Alt Art" else null,
                        category = when {
                            type.contains("Character", true) -> CardCategory.CREATURE
                            type.contains("Event", true) || type.contains("Stage", true) -> CardCategory.SPELL
                            type.contains("Leader", true) -> CardCategory.CREATURE
                            else -> CardCategory.OTHER
                        },
                        imageUrl = o.first("card_image"),
                        prices = if (price != null && price > 0) mapOf((if (parallel) CardVariant.PARALLEL else CardVariant.NORMAL) to price) else emptyMap()
                    )
                )
            }
        }
        batched.flush()
    }
}

/** Lorcast: public Lorcana API with USD prices (normal + foil). Polite 100 ms spacing between calls. */
class LorcastSource(private val http: Http) : CatalogSource {
    override val id = SourceId.LORCAST

    override suspend fun sync(game: GameDef, sink: CatalogSink, progress: (SyncProgress) -> Unit) {
        val sets = http.getJson("https://api.lorcast.com/v0/sets").cardArray()?.mapNotNull { it.obj() } ?: error("No sets")
        val batched = BatchedSink(sink)
        sets.forEachIndexed { i, set ->
            val code = set.s("code") ?: return@forEachIndexed
            progress(SyncProgress("Lorcast · ${set.s("name") ?: code}", (i + 1f) / sets.size))
            delay(100)
            val cards = http.getJson("https://api.lorcast.com/v0/sets/$code/cards").cardArray() ?: return@forEachIndexed
            cards.forEach { el ->
                val c = el.obj() ?: return@forEach
                val prices = HashMap<CardVariant, Double>()
                c.o("prices")?.let { p ->
                    p.d("usd")?.let { prices[CardVariant.NORMAL] = it }
                    p.d("usd_foil")?.let { prices[CardVariant.FOIL] = it }
                }
                val types = c["type"].arr()?.mapNotNull { it.str() }.orEmpty()
                val version = c.s("version")
                batched.add(
                    RemoteCard(
                        sourceId = c.s("id") ?: return@forEach,
                        setCode = code,
                        setName = set.s("name") ?: code,
                        setReleaseDate = set.s("released_at")?.take(10),
                        setTotal = set["total_cards"].int(),
                        number = c.s("collector_number") ?: return@forEach,
                        name = c.s("name")?.let { n -> if (version.isNullOrBlank()) n else "$n - $version" } ?: return@forEach,
                        rarity = c.s("rarity"),
                        category = when {
                            types.any { it.equals("Character", true) } -> CardCategory.CREATURE
                            types.any { it.equals("Action", true) || it.equals("Song", true) } -> CardCategory.SPELL
                            else -> CardCategory.OTHER
                        },
                        imageUrl = c.o("image_uris")?.o("digital")?.s("small"),
                        prices = prices
                    )
                )
            }
        }
        batched.flush()
    }
}

/** TCGdex: free multilingual catalog (English + Japanese). Catalog and images only. */
class TcgdexSource(private val http: Http) : CatalogSource {
    override val id = SourceId.TCGDEX

    override suspend fun sync(game: GameDef, sink: CatalogSink, progress: (SyncProgress) -> Unit) {
        val lang = game.languageTag
        val base = "https://api.tcgdex.net/v2/$lang"
        val sets = http.getJson("$base/sets").arr()?.mapNotNull { it.obj() } ?: error("No sets")
        val batched = BatchedSink(sink)
        var skipped = 0
        sets.forEachIndexed { i, brief ->
            val setId = brief.s("id") ?: return@forEachIndexed
            progress(SyncProgress("TCGdex · ${brief.s("name") ?: setId}", (i + 1f) / sets.size))
            // One broken set (404 for ids such as "SM1+", timeouts) must not abort the whole download.
            val set = fetchSet(base, setId) ?: run { skipped++; return@forEachIndexed }
            val total = set.o("cardCount")?.get("official").int() ?: set.o("cardCount")?.get("total").int()
            set.a("cards")?.forEach { el ->
                val c = el.obj() ?: return@forEach
                val image = c.s("image")
                batched.add(
                    RemoteCard(
                        sourceId = c.s("id") ?: return@forEach,
                        setCode = setId,
                        setName = set.s("name") ?: setId,
                        setReleaseDate = set.s("releaseDate"),
                        setTotal = total,
                        number = c.s("localId") ?: return@forEach,
                        name = c.s("name") ?: return@forEach,
                        category = CardCategory.OTHER,
                        imageUrl = image?.let { "$it/low.webp" }
                    )
                )
            }
        }
        batched.flush()
        if (batched.total == 0) throw java.io.IOException("TCGdex returned no cards ($skipped of ${sets.size} sets failed)")
    }

    /** Set ids can contain characters like "+" ("SM1+"): try the percent-encoded path first, then the raw one. */
    private suspend fun fetchSet(base: String, setId: String): kotlinx.serialization.json.JsonObject? {
        val encoded = java.net.URLEncoder.encode(setId, "UTF-8").replace("+", "%2B")
        for (candidate in listOf(encoded, setId).distinct()) {
            try {
                return http.getJson("$base/sets/$candidate").obj() ?: continue
            } catch (e: java.io.IOException) {
                // 404 / timeout: try the next spelling, then give up on this set
            }
        }
        return null
    }
}

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
import com.tcgscanner.offline.data.remote.JsonStream
import com.tcgscanner.offline.data.remote.RemoteCard
import com.tcgscanner.offline.data.remote.SyncProgress
import com.tcgscanner.offline.data.remote.a
import com.tcgscanner.offline.data.remote.d
import com.tcgscanner.offline.data.remote.obj
import com.tcgscanner.offline.data.remote.s

/** YGOPRODeck v7: one entry per card with every TCG printing (set code + rarity + price). */
class YgoProDeckSource(private val http: Http) : CatalogSource {
    override val id = SourceId.YGOPRODECK

    override suspend fun sync(game: GameDef, keys: ApiKeys, sink: CatalogSink, progress: (SyncProgress) -> Unit) {
        val batched = BatchedSink(sink, 1000)
        progress(SyncProgress("YGOPRODeck · downloading"))
        http.getWithRetry("https://db.ygoprodeck.com/api/v7/cardinfo.php", attempts = 3) { body ->
            JsonStream.forEachArrayElement(body, "data") { card ->
                val cardId = card["id"].toString().trim('"')
                val name = card.s("name") ?: return@forEachArrayElement
                val type = card.s("type").orEmpty()
                val category = when {
                    type.contains("Monster") -> CardCategory.CREATURE
                    type.contains("Spell") || type.contains("Trap") -> CardCategory.SPELL
                    else -> CardCategory.OTHER
                }
                val image = card.a("card_images")?.firstOrNull()?.obj()?.let { it.s("image_url_small") ?: it.s("image_url") }
                card.a("card_sets")?.forEach { setEl ->
                    val set = setEl.obj() ?: return@forEach
                    val code = set.s("set_code") ?: return@forEach
                    val rarity = set.s("set_rarity")
                    val rarityCode = set.s("set_rarity_code") ?: rarity.orEmpty()
                    val price = set.d("set_price")?.takeIf { it > 0.0 }
                    batched.add(
                        RemoteCard(
                            sourceId = "$cardId:$code:$rarityCode",
                            setCode = code.substringBefore('-'),
                            setName = set.s("set_name") ?: code.substringBefore('-'),
                            number = code,
                            name = name,
                            rarity = rarity,
                            category = category,
                            imageUrl = image,
                            prices = if (price != null) mapOf(CardVariant.NORMAL to price) else emptyMap()
                        )
                    )
                    if (batched.total % 5000 == 0) progress(SyncProgress("YGOPRODeck · ${batched.total} printings"))
                }
            }
        }
        batched.flush()
    }
}

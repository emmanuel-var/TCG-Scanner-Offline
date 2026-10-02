package com.tcgscanner.offline.data.remote.sources

import com.tcgscanner.offline.core.CardCategory
import com.tcgscanner.offline.core.CardVariant
import com.tcgscanner.offline.data.remote.RemoteCard
import com.tcgscanner.offline.data.remote.RemoteGradedPrice
import com.tcgscanner.offline.data.remote.d
import com.tcgscanner.offline.data.remote.int
import com.tcgscanner.offline.data.remote.o
import com.tcgscanner.offline.data.remote.s
import com.tcgscanner.offline.data.remote.a
import com.tcgscanner.offline.data.remote.obj
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Reader for the app's own documented catalog format (docs/CATALOG_FORMAT.md), with explicit
 * `setCode`, `prices` and `graded` fields. Other community formats go through CatalogAdapters.generic.
 */
object CustomCatalogParser {
    fun toCard(o: JsonObject): RemoteCard? {
        val name = o.s("name") ?: return null
        val number = o.s("number") ?: return null
        val setCode = o.s("setCode") ?: "CUSTOM"
        val prices = HashMap<CardVariant, Double>()
        o.o("prices")?.forEach { (k, v) ->
            (v as? JsonPrimitive)?.content?.toDoubleOrNull()?.let { prices[CardVariant.fromCode(k)] = it }
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

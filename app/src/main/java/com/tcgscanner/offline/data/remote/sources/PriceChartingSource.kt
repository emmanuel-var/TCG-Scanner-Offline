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
import com.tcgscanner.offline.data.remote.RemoteGradedPrice
import com.tcgscanner.offline.data.remote.SourceNotConfigured
import com.tcgscanner.offline.data.remote.SyncProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * PriceCharting price-guide CSV (needs the user's own paid token). It is the only source that carries
 * graded prices (PSA / BGS / CGC / SGC 10 plus generic grades 7 to 9.5), and it covers Japanese Pokémon.
 */
class PriceChartingSource(private val http: Http) : CatalogSource {
    override val id = SourceId.PRICECHARTING

    override fun isConfigured(keys: ApiKeys) = keys.priceChartingToken.isNotBlank()

    override suspend fun sync(game: GameDef, keys: ApiKeys, sink: CatalogSink, progress: (SyncProgress) -> Unit) {
        if (!isConfigured(keys)) throw SourceNotConfigured("PriceCharting token missing")
        val token = java.net.URLEncoder.encode(keys.priceChartingToken, "UTF-8")
        val url = "https://www.pricecharting.com/price-guide/download-custom?t=$token&category=pokemon-cards"
        val batched = BatchedSink(sink, 1000)
        progress(SyncProgress("PriceCharting · downloading price guide"))
        http.getWithRetry(url, mapOf("Accept" to "text/csv"), attempts = 2) { body ->
            withContext(Dispatchers.IO) {
                body.charStream().buffered().use { reader ->
                    val header = Csv.parseLine(reader.readLine() ?: return@use).map { it.trim().lowercase(Locale.ROOT) }
                    val col = header.withIndex().associate { it.value to it.index }
                    fun cell(row: List<String>, name: String): String? = col[name]?.let { row.getOrNull(it) }?.trim()
                    fun money(row: List<String>, name: String): Double? =
                        cell(row, name)?.removePrefix("$")?.replace(",", "")?.toDoubleOrNull()?.takeIf { it > 0.0 }

                    while (true) {
                        val line = reader.readLine() ?: break
                        val row = Csv.parseLine(line)
                        val console = cell(row, "console-name").orEmpty()
                        if (!console.startsWith("Pokemon Japanese", ignoreCase = true)) continue
                        val product = cell(row, "product-name") ?: continue
                        val pcId = cell(row, "id") ?: continue
                        val setName = console.removePrefix("Pokemon Japanese").trim().ifEmpty { console }
                        val number = product.substringAfterLast('#', "").trim().takeWhile { !it.isWhitespace() }
                        if (number.isEmpty()) continue
                        val bracket = Regex("\\[(.+?)]").find(product)?.groupValues?.get(1).orEmpty().lowercase(Locale.ROOT)
                        val name = product.substringBefore(" [").substringBefore(" #").trim()
                        val variant = when {
                            bracket.contains("reverse") -> CardVariant.REVERSE_HOLO
                            bracket.contains("1st") -> CardVariant.FIRST_EDITION
                            bracket.contains("holo") -> CardVariant.HOLO
                            else -> CardVariant.NORMAL
                        }
                        val graded = ArrayList<RemoteGradedPrice>()
                        money(row, "cib-price")?.let { graded.add(RemoteGradedPrice("ANY", 70, it)) }
                        money(row, "new-price")?.let { graded.add(RemoteGradedPrice("ANY", 80, it)) }
                        money(row, "graded-price")?.let { graded.add(RemoteGradedPrice("ANY", 90, it)) }
                        money(row, "box-only-price")?.let { graded.add(RemoteGradedPrice("ANY", 95, it)) }
                        money(row, "manual-only-price")?.let { graded.add(RemoteGradedPrice("PSA", 100, it)) }
                        money(row, "bgs-10-price")?.let { graded.add(RemoteGradedPrice("BGS", 100, it)) }
                        money(row, "condition-17-price")?.let { graded.add(RemoteGradedPrice("CGC", 100, it)) }
                        money(row, "condition-18-price")?.let { graded.add(RemoteGradedPrice("SGC", 100, it)) }
                        val raw = money(row, "loose-price")
                        batched.add(
                            RemoteCard(
                                sourceId = pcId,
                                setCode = setName.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), "-").trim('-'),
                                setName = setName,
                                setReleaseDate = cell(row, "release-date"),
                                number = number,
                                name = name,
                                category = CardCategory.OTHER,
                                prices = if (raw != null) mapOf(variant to raw) else emptyMap(),
                                graded = graded
                            )
                        )
                        if (batched.total % 5000 == 0) progress(SyncProgress("PriceCharting · ${batched.total} cards"))
                    }
                }
            }
        }
        batched.flush()
    }
}

/** Minimal RFC 4180 line parser (quoted fields, doubled quotes). */
object Csv {
    fun parseLine(line: String): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                inQuotes && c == '"' && i + 1 < line.length && line[i + 1] == '"' -> { sb.append('"'); i++ }
                c == '"' -> inQuotes = !inQuotes
                c == ',' && !inQuotes -> { out.add(sb.toString()); sb.setLength(0) }
                else -> sb.append(c)
            }
            i++
        }
        out.add(sb.toString())
        return out
    }
}

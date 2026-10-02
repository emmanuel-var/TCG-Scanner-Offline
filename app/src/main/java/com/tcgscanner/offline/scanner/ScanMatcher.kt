package com.tcgscanner.offline.scanner

import com.tcgscanner.offline.core.GameId
import com.tcgscanner.offline.core.Text
import com.tcgscanner.offline.data.db.AppDatabase
import com.tcgscanner.offline.data.db.CardEntity
import com.tcgscanner.offline.data.db.CardWithPrices

data class ScanMatch(val card: CardWithPrices, val score: Double)

/**
 * Resolves OCR output against the local Room catalog of the ACTIVE game only.
 *
 * Every query starts with `gameId = ?` and then walks a B-tree index, so a lookup is O(log N) however large
 * the catalog is:
 *  1. set + number   -> index (gameId, setKey, numberKey)           exact print
 *  2. number only    -> index (gameId, numberKey)
 *  3. exact name     -> index (gameId, nameKey)
 *  4. name prefix    -> range scan on (gameId, nameKey)
 * The only unindexed query (name CONTAINS) is a last resort used when nothing above produced a candidate.
 */
class ScanMatcher(private val db: AppDatabase) {

    suspend fun match(game: GameId, parsed: ParsedCard, limit: Int = 8): List<ScanMatch> {
        if (parsed.isEmpty) return emptyList()
        val dao = db.cards()
        val candidates = LinkedHashMap<String, CardEntity>()
        val numberHits = HashSet<String>()
        val totalHits = HashSet<String>()

        for (hint in parsed.numbers) {
            val found = if (hint.setKey != null) {
                dao.findBySetAndNumber(game.code, hint.setKey, hint.key).ifEmpty { dao.findByNumberKey(game.code, hint.key) }
            } else {
                dao.findByNumberKey(game.code, hint.key)
            }
            for (c in found) {
                candidates.putIfAbsent(c.id, c)
                numberHits.add(c.id)
                if (hint.total != null && c.setTotal == hint.total) totalHits.add(c.id)
            }
        }

        val lineKeys = parsed.names.map { Text.nameKey(it) }.filter { it.length >= 3 }
        for (key in lineKeys.take(4)) {
            dao.findByNameKey(game.code, key).forEach { candidates.putIfAbsent(it.id, it) }
            if (key.length >= 4) {
                for (len in intArrayOf(minOf(key.length, 8), 4).distinct()) {
                    val prefix = key.take(len)
                    dao.findByNameRange(game.code, prefix, Text.prefixUpperBound(prefix), 200).forEach { candidates.putIfAbsent(it.id, it) }
                }
            }
        }
        if (candidates.isEmpty()) {
            // Unindexed fallback, only for badly misread names.
            for (line in parsed.names.take(3)) {
                line.split(' ').map { Text.nameKey(it) }.filter { it.length >= 4 }.take(2).forEach { token ->
                    dao.findByNameContaining(game.code, token, 200).forEach { candidates.putIfAbsent(it.id, it) }
                }
            }
        }

        val scored = candidates.values.map { c ->
            val ns = lineKeys.maxOfOrNull { nameScore(it, c.nameKey) } ?: 0.0
            val numHit = c.id in numberHits
            val score = when {
                numHit && lineKeys.isNotEmpty() -> 0.45 + 0.5 * ns + if (c.id in totalHits) 0.05 else 0.0
                numHit -> 0.7 + if (c.id in totalHits) 0.05 else 0.0
                else -> ns * 0.92
            }
            Triple(c, score.coerceAtMost(1.0), numHit)
        }.filter { it.second >= 0.5 }
            .sortedWith(compareByDescending<Triple<CardEntity, Double, Boolean>> { it.second }
                .thenByDescending { it.third }
                .thenByDescending { it.first.setReleaseDate ?: "" })
            .take(limit)

        if (scored.isEmpty()) return emptyList()
        val withPrices = dao.getManyWithPrices(scored.map { it.first.id }).associateBy { it.card.id }
        return scored.mapNotNull { (c, s, _) -> withPrices[c.id]?.let { ScanMatch(it, s) } }
    }

    private fun nameScore(lineKey: String, cardKey: String): Double {
        var s = Text.similarity(lineKey, cardKey)
        // The OCR line may contain extra words around the card name ("Pikachu ex 60 HP").
        if (cardKey.length >= 4 && lineKey.contains(cardKey)) {
            s = maxOf(s, 0.8 + 0.15 * cardKey.length / lineKey.length)
        }
        return s
    }
}

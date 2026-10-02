package com.tcgscanner.offline.scanner

import com.tcgscanner.offline.core.GameId
import com.tcgscanner.offline.core.Text
import com.tcgscanner.offline.data.db.AppDatabase
import com.tcgscanner.offline.data.db.CardEntity
import com.tcgscanner.offline.data.db.CardWithPrices

data class ScanMatch(val card: CardWithPrices, val score: Double)

/** Resolves OCR output against the local Room catalog of the active game only (fast, fully offline). */
class ScanMatcher(private val db: AppDatabase) {

    suspend fun match(game: GameId, parsed: ParsedCard, limit: Int = 8): List<ScanMatch> {
        if (parsed.isEmpty) return emptyList()
        val dao = db.cards()
        val candidates = LinkedHashMap<String, CardEntity>()
        val numberHits = HashSet<String>()
        val totalHits = HashSet<String>()

        for (hint in parsed.numbers) {
            for (c in dao.findByNumberKey(game.code, hint.key)) {
                candidates.putIfAbsent(c.id, c)
                numberHits.add(c.id)
                if (hint.total != null && c.setTotal == hint.total) totalHits.add(c.id)
            }
        }

        val lineKeys = parsed.names.map { Text.nameKey(it) }.filter { it.length >= 3 }
        val needles = LinkedHashSet<String>()
        for (line in parsed.names.take(4)) {
            val key = Text.nameKey(line)
            if (key.length >= 3) needles.add(key)
            line.split(' ').map { Text.nameKey(it) }.filter { it.length >= 4 }.forEach { needles.add(it) }
        }
        for (needle in needles.take(8)) {
            var found = dao.findByNameContaining(game.code, needle, 300)
            if (found.isEmpty() && needle.length >= 7) {
                // OCR slips inside a long name: try its stable ends.
                found = dao.findByNameContaining(game.code, needle.take(4), 300) + dao.findByNameContaining(game.code, needle.takeLast(4), 300)
            }
            found.forEach { candidates.putIfAbsent(it.id, it) }
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

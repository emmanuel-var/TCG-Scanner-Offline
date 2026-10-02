package com.tcgscanner.offline.data.repo

import androidx.room.withTransaction
import com.tcgscanner.offline.core.GameId
import com.tcgscanner.offline.core.Text
import com.tcgscanner.offline.data.db.AppDatabase
import com.tcgscanner.offline.data.db.CardEntity
import com.tcgscanner.offline.data.db.DeckCardEntity

data class RelinkReport(val linked: Int = 0, val merged: Int = 0, val unresolved: Int = 0)

/**
 * Re-attaches collection rows and deck lines whose card id no longer exists (for example after the user pointed
 * a game at a different catalog URL and the catalog was purged) to the new catalog rows, using the identity
 * snapshot stored on every user row and [RelinkMatcher]. Nothing here ever deletes a user's cards: rows that
 * cannot be matched confidently stay as they are.
 */
class CardRelinker(private val db: AppDatabase) {

    suspend fun relink(game: GameId): RelinkReport {
        val collection = db.collection()
        val decks = db.decks()
        collection.backfillSnapshots(game.code)
        decks.backfillSnapshots(game.code)

        val orphanItems = collection.orphans(game.code)
        val orphanDeckCards = decks.orphanDeckCards(game.code)
        if (orphanItems.isEmpty() && orphanDeckCards.isEmpty()) return RelinkReport()

        val candidateCache = HashMap<String, List<RelinkCandidate>>()
        suspend fun candidates(number: String): List<RelinkCandidate> {
            val key = Text.numberKey(number)
            return candidateCache.getOrPut(key) { db.cards().findByNumberKey(game.code, key).map { it.toCandidate() } }
        }

        var linked = 0
        var merged = 0
        var unresolved = 0

        for (item in orphanItems) {
            val snap = CardSnapshot(item.cardName, item.setCode, item.setName, item.cardNumber, item.printTag)
            val targetId = if (snap.number.isBlank()) null else RelinkMatcher.best(snap, candidates(snap.number))
            val target = targetId?.let { db.cards().get(it) }
            if (target == null) { unresolved++; continue }
            db.withTransaction {
                val existing = collection.findMatch(game.code, target.id, item.variant, item.condition, item.gradeCompany, item.gradeX10)
                if (existing != null && existing.id != item.id) {
                    collection.update(
                        existing.copy(
                            quantity = existing.quantity + item.quantity,
                            tradeQuantity = (existing.tradeQuantity + item.tradeQuantity).coerceAtMost(existing.quantity + item.quantity),
                            updatedAt = System.currentTimeMillis()
                        )
                    )
                    collection.delete(item.id)
                    merged++
                } else {
                    collection.update(
                        item.copy(
                            cardId = target.id, cardName = target.name, setCode = target.setCode, setName = target.setName,
                            cardNumber = target.number, printTag = target.printTag
                        )
                    )
                    linked++
                }
            }
        }

        for (line in orphanDeckCards) {
            val snap = CardSnapshot(line.cardName, line.setCode, line.setName, line.cardNumber, line.printTag)
            val targetId = if (snap.number.isBlank()) null else RelinkMatcher.best(snap, candidates(snap.number))
            val target = targetId?.let { db.cards().get(it) }
            if (target == null) { unresolved++; continue }
            db.withTransaction {
                val existing = decks.getDeckCard(line.deckId, target.id, line.zone)
                decks.removeDeckCard(line.deckId, line.cardId, line.zone)
                decks.putDeckCard(
                    DeckCardEntity(
                        deckId = line.deckId, cardId = target.id, zone = line.zone,
                        quantity = (existing?.quantity ?: 0) + line.quantity,
                        cardName = target.name, setCode = target.setCode, setName = target.setName,
                        cardNumber = target.number, printTag = target.printTag
                    )
                )
                if (existing != null) merged++ else linked++
            }
        }
        return RelinkReport(linked, merged, unresolved)
    }

    private fun CardEntity.toCandidate() = RelinkCandidate(id, name, setCode, setName, number, printTag)
}

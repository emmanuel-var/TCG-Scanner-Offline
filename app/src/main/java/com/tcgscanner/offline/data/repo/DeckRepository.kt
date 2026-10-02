package com.tcgscanner.offline.data.repo

import com.tcgscanner.offline.core.CardCategory
import com.tcgscanner.offline.core.DeckZone
import com.tcgscanner.offline.core.GameId
import com.tcgscanner.offline.data.db.AppDatabase
import com.tcgscanner.offline.data.db.CardEntity
import com.tcgscanner.offline.data.db.DeckCardEntity
import com.tcgscanner.offline.data.db.DeckEntity
import com.tcgscanner.offline.data.db.DeckSize
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

data class DeckLine(
    val card: CardEntity,
    val zone: DeckZone,
    val needed: Int,
    val owned: Int,
    val unitUsd: Double
) {
    val missing: Int get() = (needed - owned).coerceAtLeast(0)
    val missingUsd: Double get() = missing * unitUsd
}

data class DeckView(
    val deck: DeckEntity,
    val lines: List<DeckLine>
) {
    val grouped: Map<DeckZone, Map<CardCategory, List<DeckLine>>>
        get() = lines.groupBy { it.zone }.mapValues { (_, v) ->
            v.groupBy { it.card.category.let(CardCategory::fromCode) }
                .toSortedMap(compareBy { it.ordinal })
                .mapValues { (_, l) -> l.sortedBy { it.card.name } }
        }
    val totalCards: Int get() = lines.sumOf { it.needed }
    val wishlist: List<DeckLine> get() = lines.filter { it.missing > 0 }
    val wishlistUsd: Double get() = wishlist.sumOf { it.missingUsd }
}

class DeckRepository(private val db: AppDatabase) {
    private val dao get() = db.decks()

    fun observeDecks(game: GameId): Flow<List<DeckEntity>> = dao.observeDecks(game.code)
    fun observeSizes(): Flow<List<DeckSize>> = dao.observeDeckSizes()

    suspend fun create(game: GameId, name: String): Long {
        val now = System.currentTimeMillis()
        return dao.insertDeck(DeckEntity(gameId = game.code, name = name.trim().ifEmpty { "Deck" }, notes = null, createdAt = now, updatedAt = now))
    }

    suspend fun rename(deck: DeckEntity, name: String) = dao.updateDeck(deck.copy(name = name.trim().ifEmpty { deck.name }, updatedAt = System.currentTimeMillis()))

    suspend fun delete(deckId: Long) {
        dao.deleteDeckCards(deckId)
        dao.deleteDeck(deckId)
    }

    /** Deck contents joined with how many copies the user owns: the shortfall is the wishlist. */
    fun observeDeck(deckId: Long): Flow<DeckView?> =
        combine(
            dao.observeDeck(deckId),
            dao.observeDeckCards(deckId),
            // owned quantities for the deck's own game
            db.collection().observeOwnedQuantitiesAll()
        ) { deck, cards, owned ->
            if (deck == null) return@combine null
            val ownedMap = owned.filter { it.gameId == deck.gameId }.associate { it.cardId to it.qty }
            val lines = cards.mapNotNull { dc ->
                val card = dc.card ?: return@mapNotNull null
                DeckLine(
                    card = card,
                    zone = DeckZone.fromCode(dc.deckCard.zone),
                    needed = dc.deckCard.quantity,
                    owned = ownedMap[card.id] ?: 0,
                    unitUsd = Valuation.rawPrice(com.tcgscanner.offline.core.CardVariant.NORMAL, dc.prices).usd
                )
            }
            DeckView(deck, lines)
        }

    suspend fun changeQuantity(deckId: Long, cardId: String, zone: DeckZone, delta: Int) {
        val existing = dao.getDeckCard(deckId, cardId, zone.code)
        val next = (existing?.quantity ?: 0) + delta
        if (next <= 0) {
            dao.removeDeckCard(deckId, cardId, zone.code)
        } else {
            val card = db.cards().get(cardId)
            dao.putDeckCard(
                DeckCardEntity(
                    deckId, cardId, zone.code, next,
                    cardName = existing?.cardName?.ifBlank { card?.name.orEmpty() } ?: card?.name.orEmpty(),
                    setCode = existing?.setCode?.ifBlank { card?.setCode.orEmpty() } ?: card?.setCode.orEmpty(),
                    setName = existing?.setName?.ifBlank { card?.setName.orEmpty() } ?: card?.setName.orEmpty(),
                    cardNumber = existing?.cardNumber?.ifBlank { card?.number.orEmpty() } ?: card?.number.orEmpty(),
                    printTag = existing?.printTag?.ifBlank { card?.printTag.orEmpty() } ?: card?.printTag.orEmpty()
                )
            )
        }
        touch(deckId)
    }

    suspend fun moveZone(deckId: Long, cardId: String, from: DeckZone, to: DeckZone) {
        if (from == to) return
        val src = dao.getDeckCard(deckId, cardId, from.code) ?: return
        dao.removeDeckCard(deckId, cardId, from.code)
        val dst = dao.getDeckCard(deckId, cardId, to.code)
        dao.putDeckCard((dst ?: src).copy(zone = to.code, quantity = (dst?.quantity ?: 0) + src.quantity))
        touch(deckId)
    }

    private suspend fun touch(deckId: Long) {
        // Deck metadata is re-read through the flow, so just bump the timestamp.
        dao.allDecks().firstOrNull { it.id == deckId }?.let { dao.updateDeck(it.copy(updatedAt = System.currentTimeMillis())) }
    }
}

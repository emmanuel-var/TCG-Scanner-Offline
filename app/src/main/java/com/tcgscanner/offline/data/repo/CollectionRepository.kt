package com.tcgscanner.offline.data.repo

import androidx.room.withTransaction
import com.tcgscanner.offline.core.CardCondition
import com.tcgscanner.offline.core.CardVariant
import com.tcgscanner.offline.core.GameId
import com.tcgscanner.offline.core.GradingCompany
import com.tcgscanner.offline.data.db.AppDatabase
import com.tcgscanner.offline.data.db.CollectionItemEntity

/** What the user chose in the "which version?" sheet. */
data class AddSpec(
    val cardId: String,
    val variant: CardVariant,
    val condition: CardCondition = CardCondition.NM,
    val company: GradingCompany = GradingCompany.NONE,
    val gradeX10: Int = 0,
    val quantity: Int = 1,
    val toBinder: Boolean = false,
    val manualPriceUsd: Double? = null
)

class CollectionRepository(private val db: AppDatabase, private val portfolio: PortfolioRepository) {
    private val dao get() = db.collection()

    /** Adds copies; identical (card, variant, condition, grade) rows are merged. */
    suspend fun add(game: GameId, spec: AddSpec) {
        db.withTransaction {
            val now = System.currentTimeMillis()
            val graded = spec.company.isGraded
            val condition = if (graded) CardCondition.NM else spec.condition
            val grade = if (graded) spec.gradeX10 else 0
            val existing = dao.findMatch(game.code, spec.cardId, spec.variant.code, condition.code, spec.company.code, grade)
            val qty = spec.quantity.coerceAtLeast(1)
            val trade = if (spec.toBinder) qty else 0
            if (existing != null) {
                dao.update(
                    existing.copy(
                        quantity = existing.quantity + qty,
                        tradeQuantity = (existing.tradeQuantity + trade).coerceAtMost(existing.quantity + qty),
                        manualPriceUsd = spec.manualPriceUsd ?: existing.manualPriceUsd,
                        updatedAt = now
                    )
                )
            } else {
                dao.insert(
                    CollectionItemEntity(
                        gameId = game.code, cardId = spec.cardId, variant = spec.variant.code,
                        condition = condition.code, gradeCompany = spec.company.code, gradeX10 = grade,
                        quantity = qty, tradeQuantity = trade, manualPriceUsd = spec.manualPriceUsd,
                        notes = null, addedAt = now, updatedAt = now
                    )
                )
            }
        }
        portfolio.recordSnapshot(game)
    }

    suspend fun setQuantity(itemId: Long, quantity: Int) {
        val item = dao.get(itemId) ?: return
        if (quantity <= 0) dao.delete(itemId)
        else dao.update(item.copy(quantity = quantity, tradeQuantity = item.tradeQuantity.coerceAtMost(quantity), updatedAt = System.currentTimeMillis()))
        GameId.fromCode(item.gameId)?.let { portfolio.recordSnapshot(it) }
    }

    suspend fun setTradeQuantity(itemId: Long, tradeQuantity: Int) {
        val item = dao.get(itemId) ?: return
        dao.update(item.copy(tradeQuantity = tradeQuantity.coerceIn(0, item.quantity), updatedAt = System.currentTimeMillis()))
    }

    suspend fun setManualPrice(itemId: Long, usd: Double?) {
        val item = dao.get(itemId) ?: return
        dao.update(item.copy(manualPriceUsd = usd?.takeIf { it >= 0.0 }, updatedAt = System.currentTimeMillis()))
        GameId.fromCode(item.gameId)?.let { portfolio.recordSnapshot(it) }
    }

    suspend fun delete(itemId: Long) {
        val item = dao.get(itemId) ?: return
        dao.delete(itemId)
        GameId.fromCode(item.gameId)?.let { portfolio.recordSnapshot(it) }
    }

    /** Removes [count] copies, used after a completed P2P trade. Silently ignores unknown ids. */
    suspend fun removeCopies(itemId: Long, count: Int) {
        val item = dao.get(itemId) ?: return
        setQuantity(itemId, item.quantity - count)
    }
}

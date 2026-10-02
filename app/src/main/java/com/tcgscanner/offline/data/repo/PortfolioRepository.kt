package com.tcgscanner.offline.data.repo

import com.tcgscanner.offline.core.GameId
import com.tcgscanner.offline.core.GradingCompany
import com.tcgscanner.offline.data.db.AppDatabase
import com.tcgscanner.offline.data.db.CardEntity
import com.tcgscanner.offline.data.db.CollectionItemEntity
import com.tcgscanner.offline.data.db.CollectionWithCard
import com.tcgscanner.offline.data.db.PortfolioSnapshotEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** A collection row together with its computed value. */
data class CollectionEntry(val row: CollectionWithCard, val unit: UnitValue) {
    val item: CollectionItemEntity get() = row.item
    val card: CardEntity? get() = row.card
    val isGraded: Boolean get() = GradingCompany.fromCode(item.gradeCompany).isGraded
    val totalUsd: Double get() = unit.usd * item.quantity
    val tradeUsd: Double get() = unit.usd * item.tradeQuantity
}

fun CollectionWithCard.toEntry() = CollectionEntry(this, Valuation.unitValue(item, prices, graded))

data class PortfolioSummary(
    val totalUsd: Double = 0.0,
    val rawUsd: Double = 0.0,
    val gradedUsd: Double = 0.0,
    val copies: Int = 0,
    val uniqueCards: Int = 0,
    val estimatedEntries: Int = 0,
    val unpricedEntries: Int = 0,
    val topEntries: List<CollectionEntry> = emptyList()
)

class PortfolioRepository(private val db: AppDatabase) {

    fun summarize(entries: List<CollectionEntry>): PortfolioSummary {
        var raw = 0.0
        var graded = 0.0
        var copies = 0
        var estimated = 0
        var unpriced = 0
        for (e in entries) {
            if (e.isGraded) graded += e.totalUsd else raw += e.totalUsd
            copies += e.item.quantity
            if (e.unit.estimated) estimated++
            if (!e.unit.hasPrice) unpriced++
        }
        return PortfolioSummary(
            totalUsd = raw + graded,
            rawUsd = raw,
            gradedUsd = graded,
            copies = copies,
            uniqueCards = entries.map { it.item.cardId }.toSet().size,
            estimatedEntries = estimated,
            unpricedEntries = unpriced,
            topEntries = entries.sortedByDescending { it.unit.usd }.take(100)
        )
    }

    fun observeEntries(game: GameId): Flow<List<CollectionEntry>> =
        db.collection().observeGame(game.code).map { rows -> rows.map { it.toEntry() } }

    fun observeSummary(game: GameId): Flow<PortfolioSummary> = observeEntries(game).map { summarize(it) }

    fun observeSnapshots(game: GameId): Flow<List<PortfolioSnapshotEntity>> = db.portfolio().observe(game.code)

    /** Stores the current value; at most one snapshot per hour per game (the latest wins). */
    suspend fun recordSnapshot(game: GameId, now: Long = System.currentTimeMillis()) {
        val entries = db.collection().getGame(game.code).map { it.toEntry() }
        if (entries.isEmpty() && db.portfolio().all().none { it.gameId == game.code }) return
        val s = summarize(entries)
        db.portfolio().put(
            PortfolioSnapshotEntity(
                gameId = game.code,
                bucket = now / 3_600_000L,
                timestamp = now,
                totalUsd = s.totalUsd,
                rawUsd = s.rawUsd,
                gradedUsd = s.gradedUsd,
                itemCount = s.copies
            )
        )
    }

    suspend fun recordAllSnapshots() {
        db.collection().gamesWithItems().mapNotNull { GameId.fromCode(it) }.forEach { recordSnapshot(it) }
    }
}

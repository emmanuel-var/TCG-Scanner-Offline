package com.tcgscanner.offline.data.db

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation
import kotlinx.serialization.Serializable

/**
 * One physical print of a card (set + number). There is deliberately NO foreign key from user data
 * to this table: refreshing the catalog (REPLACE) must never cascade-delete the user's collection.
 */
@Entity(
    tableName = "card",
    indices = [Index("gameId", "numberKey"), Index("gameId", "nameKey"), Index("gameId", "setCode")]
)
@Serializable
data class CardEntity(
    @PrimaryKey val id: String,
    val gameId: String,
    val setCode: String,
    val setName: String,
    val setReleaseDate: String?,
    /** Printed total of the set (the "198" of "025/198"). */
    val setTotal: Int?,
    val number: String,
    val numberKey: String,
    val name: String,
    val nameKey: String,
    val rarity: String?,
    val category: String,
    val imageUrl: String?,
    val isCustom: Boolean = false
)

@Entity(tableName = "card_price", primaryKeys = ["cardId", "variant"], indices = [Index("cardId")])
@Serializable
data class CardPriceEntity(
    val cardId: String,
    val variant: String,
    val usd: Double
)

/** Market price of a graded copy. company == "ANY" means a generic grade not tied to a grader. */
@Entity(tableName = "graded_price", primaryKeys = ["cardId", "company", "gradeX10"], indices = [Index("cardId")])
data class GradedPriceEntity(
    val cardId: String,
    val company: String,
    val gradeX10: Int,
    val usd: Double
)

@Entity(
    tableName = "collection_item",
    indices = [Index("gameId"), Index("cardId"), Index("gameId", "tradeQuantity")]
)
@Serializable
data class CollectionItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val gameId: String,
    val cardId: String,
    val variant: String,
    val condition: String,
    val gradeCompany: String,
    val gradeX10: Int,
    val quantity: Int,
    /** How many of [quantity] are placed in the Trade Binder. Always <= quantity. */
    val tradeQuantity: Int,
    val manualPriceUsd: Double?,
    val notes: String?,
    val addedAt: Long,
    val updatedAt: Long
)

@Entity(tableName = "deck", indices = [Index("gameId")])
@Serializable
data class DeckEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val gameId: String,
    val name: String,
    val notes: String?,
    val createdAt: Long,
    val updatedAt: Long
)

@Entity(tableName = "deck_card", primaryKeys = ["deckId", "cardId", "zone"], indices = [Index("deckId")])
@Serializable
data class DeckCardEntity(
    val deckId: Long,
    val cardId: String,
    val zone: String,
    val quantity: Int
)

@Entity(tableName = "portfolio_snapshot", primaryKeys = ["gameId", "bucket"])
@Serializable
data class PortfolioSnapshotEntity(
    val gameId: String,
    /** Hour bucket (epoch millis / 3_600_000) so the latest value in an hour overwrites earlier ones. */
    val bucket: Long,
    val timestamp: Long,
    val totalUsd: Double,
    val rawUsd: Double,
    val gradedUsd: Double,
    val itemCount: Int
)

@Entity(tableName = "sync_state")
data class SyncStateEntity(
    @PrimaryKey val gameId: String,
    val lastSyncAt: Long,
    val source: String,
    val cardCount: Int,
    val lastError: String?
)

@Entity(tableName = "card_signature", indices = [Index("gameId")])
data class CardSignatureEntity(
    @PrimaryKey val cardId: String,
    val gameId: String,
    val dHash: Long,
    val aHash: Long,
    /** Optional TFLite embedding (float32 little-endian) when a model is bundled. */
    val embedding: ByteArray?
)

// ---- Query result shapes -------------------------------------------------------------------

data class CardWithPrices(
    @Embedded val card: CardEntity,
    @Relation(parentColumn = "id", entityColumn = "cardId") val prices: List<CardPriceEntity>,
    @Relation(parentColumn = "id", entityColumn = "cardId") val graded: List<GradedPriceEntity>
)

data class CollectionWithCard(
    @Embedded val item: CollectionItemEntity,
    @Relation(parentColumn = "cardId", entityColumn = "id") val card: CardEntity?,
    @Relation(parentColumn = "cardId", entityColumn = "cardId") val prices: List<CardPriceEntity>,
    @Relation(parentColumn = "cardId", entityColumn = "cardId") val graded: List<GradedPriceEntity>
)

data class DeckCardWithCard(
    @Embedded val deckCard: DeckCardEntity,
    @Relation(parentColumn = "cardId", entityColumn = "id") val card: CardEntity?,
    @Relation(parentColumn = "cardId", entityColumn = "cardId") val prices: List<CardPriceEntity>
)

data class SetRow(val code: String, val name: String, val total: Int, val releaseDate: String?)
data class SetCount(val code: String, val n: Int)
data class CardQty(val cardId: String, val qty: Int)
data class GameCardQty(val gameId: String, val cardId: String, val qty: Int)
data class CardVariantRef(val cardId: String, val variant: String)

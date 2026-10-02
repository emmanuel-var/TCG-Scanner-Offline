package com.tcgscanner.offline.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface CardDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCards(cards: List<CardEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPrices(prices: List<CardPriceEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertGraded(prices: List<GradedPriceEntity>)

    @Query("SELECT COUNT(*) FROM card WHERE gameId = :game")
    suspend fun count(game: String): Int

    @Query("SELECT COUNT(*) FROM card WHERE gameId = :game")
    fun observeCount(game: String): Flow<Int>

    @Query("SELECT * FROM card WHERE id = :id")
    suspend fun get(id: String): CardEntity?

    @Transaction
    @Query("SELECT * FROM card WHERE id = :id")
    suspend fun getWithPrices(id: String): CardWithPrices?

    @Transaction
    @Query("SELECT * FROM card WHERE id IN (:ids)")
    suspend fun getManyWithPrices(ids: List<String>): List<CardWithPrices>

    // ---- scanner lookups: all start with gameId, all hit an index (see CardEntity) ------------------------------

    /** Exact print by set + number: composite index (gameId, setKey, numberKey). */
    @Query("SELECT * FROM card WHERE gameId = :game AND setKey = :setKey AND numberKey = :key LIMIT 20")
    suspend fun findBySetAndNumber(game: String, setKey: String, key: String): List<CardEntity>

    @Query("SELECT * FROM card WHERE gameId = :game AND numberKey = :key LIMIT 400")
    suspend fun findByNumberKey(game: String, key: String): List<CardEntity>

    /** Names starting with a prefix: a range scan on index (gameId, nameKey). [hi] is the prefix with its last char + 1. */
    @Query("SELECT * FROM card WHERE gameId = :game AND nameKey >= :lo AND nameKey < :hi LIMIT :limit")
    suspend fun findByNameRange(game: String, lo: String, hi: String, limit: Int): List<CardEntity>

    @Query("SELECT * FROM card WHERE gameId = :game AND nameKey LIKE '%' || :needle || '%' LIMIT :limit")
    suspend fun findByNameContaining(game: String, needle: String, limit: Int): List<CardEntity>

    @Query("SELECT * FROM card WHERE gameId = :game AND nameKey = :key ORDER BY setReleaseDate DESC LIMIT 40")
    suspend fun findByNameKey(game: String, key: String): List<CardEntity>

    @Query(
        "SELECT * FROM card WHERE gameId = :game AND (nameKey LIKE '%' || :needle || '%' OR numberKey LIKE '%' || :needle || '%') " +
            "ORDER BY nameKey, setReleaseDate DESC LIMIT :limit"
    )
    suspend fun search(game: String, needle: String, limit: Int): List<CardEntity>

    @Query(
        "SELECT c.setCode AS code, MIN(c.setName) AS name, COUNT(*) AS total, MAX(c.setReleaseDate) AS releaseDate " +
            "FROM card c WHERE c.gameId = :game GROUP BY c.setCode ORDER BY COALESCE(MAX(c.setReleaseDate), '') DESC, name"
    )
    fun observeSets(game: String): Flow<List<SetRow>>

    /** Number of "slots" in a master set: one per available price variant (at least 1 per card). */
    @Query(
        "SELECT c.setCode AS code, SUM(CASE WHEN v.n IS NULL THEN 1 ELSE v.n END) AS n FROM card c " +
            "LEFT JOIN (SELECT cardId, COUNT(*) AS n FROM card_price GROUP BY cardId) v ON v.cardId = c.id " +
            "WHERE c.gameId = :game GROUP BY c.setCode"
    )
    fun observeMasterTotals(game: String): Flow<List<SetCount>>

    @Transaction
    @Query(
        "SELECT * FROM card WHERE gameId = :game AND setCode = :setCode ORDER BY LENGTH(numberKey), numberKey"
    )
    fun observeSetCards(game: String, setCode: String): Flow<List<CardWithPrices>>

    @Query("SELECT lastSyncAt FROM sync_state WHERE gameId = :game")
    fun observeLastSync(game: String): Flow<Long?>

    @Query("DELETE FROM card WHERE gameId = :game AND isCustom = 0")
    suspend fun deleteCatalog(game: String)

    @Query("DELETE FROM card_signature WHERE cardId IN (SELECT id FROM card WHERE gameId = :game AND isCustom = 0)")
    suspend fun deleteCatalogSignatures(game: String)

    @Query("SELECT id FROM card WHERE gameId = :game")
    suspend fun allIds(game: String): List<String>

    @Query("DELETE FROM card_price WHERE cardId IN (SELECT id FROM card WHERE gameId = :game AND isCustom = 0)")
    suspend fun deleteCatalogPrices(game: String)

    @Query("DELETE FROM graded_price WHERE cardId IN (SELECT id FROM card WHERE gameId = :game AND isCustom = 0)")
    suspend fun deleteCatalogGraded(game: String)

    @Query("SELECT * FROM sync_state")
    fun observeSyncStates(): Flow<List<SyncStateEntity>>

    @Query("SELECT * FROM sync_state")
    suspend fun observeSyncStatesOnce(): List<SyncStateEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putSyncState(state: SyncStateEntity)

    @Query("SELECT * FROM card WHERE gameId = :game AND imageUrl IS NOT NULL AND id NOT IN (SELECT cardId FROM card_signature) LIMIT :limit")
    suspend fun cardsWithoutSignature(game: String, limit: Int): List<CardEntity>

    @Query("SELECT COUNT(*) FROM card WHERE gameId = :game AND imageUrl IS NOT NULL")
    fun observeImageCardCount(game: String): Flow<Int>

    @Query("SELECT COUNT(*) FROM card_signature WHERE gameId = :game")
    fun observeSignatureCount(game: String): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putSignatures(items: List<CardSignatureEntity>)

    @Query("SELECT * FROM card_signature WHERE gameId = :game")
    suspend fun signatures(game: String): List<CardSignatureEntity>

    @Query("SELECT * FROM card WHERE isCustom = 1")
    suspend fun customCards(): List<CardEntity>

    @Query("SELECT * FROM card_price WHERE cardId IN (SELECT id FROM card WHERE isCustom = 1)")
    suspend fun customPrices(): List<CardPriceEntity>

    @Query("DELETE FROM card WHERE isCustom = 1")
    suspend fun deleteCustomCards()

    @Query("DELETE FROM card_price WHERE cardId IN (SELECT id FROM card WHERE isCustom = 1)")
    suspend fun deleteCustomPrices()

    @Query("DELETE FROM card_signature WHERE gameId = :game")
    suspend fun clearSignatures(game: String)
}

@Dao
interface CollectionDao {
    @Insert
    suspend fun insert(item: CollectionItemEntity): Long

    @Update
    suspend fun update(item: CollectionItemEntity)

    @Query("DELETE FROM collection_item WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM collection_item WHERE id = :id")
    suspend fun get(id: Long): CollectionItemEntity?

    @Query(
        "SELECT * FROM collection_item WHERE gameId = :game AND cardId = :cardId AND variant = :variant AND condition = :condition " +
            "AND gradeCompany = :company AND gradeX10 = :grade LIMIT 1"
    )
    suspend fun findMatch(game: String, cardId: String, variant: String, condition: String, company: String, grade: Int): CollectionItemEntity?

    @Transaction
    @Query("SELECT * FROM collection_item WHERE gameId = :game ORDER BY addedAt DESC")
    fun observeGame(game: String): Flow<List<CollectionWithCard>>

    @Transaction
    @Query("SELECT * FROM collection_item ORDER BY gameId, addedAt DESC")
    suspend fun getAll(): List<CollectionWithCard>

    @Transaction
    @Query("SELECT * FROM collection_item WHERE gameId = :game")
    suspend fun getGame(game: String): List<CollectionWithCard>

    @Query("SELECT cardId, SUM(quantity) AS qty FROM collection_item WHERE gameId = :game GROUP BY cardId")
    fun observeOwnedQuantities(game: String): Flow<List<CardQty>>

    @Query("SELECT gameId, cardId, SUM(quantity) AS qty FROM collection_item GROUP BY gameId, cardId")
    fun observeOwnedQuantitiesAll(): Flow<List<GameCardQty>>

    @Query(
        "SELECT c.setCode AS code, COUNT(DISTINCT i.cardId) AS n FROM collection_item i JOIN card c ON c.id = i.cardId " +
            "WHERE i.gameId = :game GROUP BY c.setCode"
    )
    fun observeOwnedBaseBySet(game: String): Flow<List<SetCount>>

    @Query(
        "SELECT c.setCode AS code, COUNT(*) AS n FROM (SELECT DISTINCT cardId, variant FROM collection_item WHERE gameId = :game) i " +
            "JOIN card c ON c.id = i.cardId GROUP BY c.setCode"
    )
    fun observeOwnedMasterBySet(game: String): Flow<List<SetCount>>

    @Query("SELECT DISTINCT cardId, variant FROM collection_item WHERE gameId = :game")
    fun observeOwnedVariants(game: String): Flow<List<CardVariantRef>>

    @Query("SELECT DISTINCT gameId FROM collection_item")
    suspend fun gamesWithItems(): List<String>

    /** Fills the identity snapshot of rows that were created before snapshots existed and still have a card. */
    @Query(
        "UPDATE collection_item SET " +
            "cardName = (SELECT name FROM card WHERE card.id = collection_item.cardId), " +
            "setCode = (SELECT setCode FROM card WHERE card.id = collection_item.cardId), " +
            "setName = (SELECT setName FROM card WHERE card.id = collection_item.cardId), " +
            "cardNumber = (SELECT number FROM card WHERE card.id = collection_item.cardId), " +
            "printTag = (SELECT printTag FROM card WHERE card.id = collection_item.cardId) " +
            "WHERE gameId = :game AND cardName = '' AND cardId IN (SELECT id FROM card WHERE gameId = :game)"
    )
    suspend fun backfillSnapshots(game: String)

    /** Items whose card no longer exists in the catalog (e.g. right after a source change). */
    @Query("SELECT * FROM collection_item WHERE gameId = :game AND cardId NOT IN (SELECT id FROM card WHERE gameId = :game)")
    suspend fun orphans(game: String): List<CollectionItemEntity>

    @Query("DELETE FROM collection_item WHERE gameId = :game")
    suspend fun deleteGame(game: String)

    @Query("DELETE FROM collection_item")
    suspend fun deleteAll()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<CollectionItemEntity>)

    @Query("SELECT * FROM collection_item")
    suspend fun rawAll(): List<CollectionItemEntity>
}

@Dao
interface DeckDao {
    @Insert
    suspend fun insertDeck(deck: DeckEntity): Long

    @Update
    suspend fun updateDeck(deck: DeckEntity)

    @Query("DELETE FROM deck WHERE id = :id")
    suspend fun deleteDeck(id: Long)

    @Query("DELETE FROM deck_card WHERE deckId = :id")
    suspend fun deleteDeckCards(id: Long)

    @Query("SELECT * FROM deck WHERE id = :id")
    fun observeDeck(id: Long): Flow<DeckEntity?>

    @Query("SELECT * FROM deck WHERE gameId = :game ORDER BY updatedAt DESC")
    fun observeDecks(game: String): Flow<List<DeckEntity>>

    @Query("SELECT deckId, SUM(quantity) AS n FROM deck_card GROUP BY deckId")
    fun observeDeckSizes(): Flow<List<DeckSize>>

    @Transaction
    @Query("SELECT * FROM deck_card WHERE deckId = :id")
    fun observeDeckCards(id: Long): Flow<List<DeckCardWithCard>>

    @Query("SELECT * FROM deck_card WHERE deckId = :deckId AND cardId = :cardId AND zone = :zone")
    suspend fun getDeckCard(deckId: Long, cardId: String, zone: String): DeckCardEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putDeckCard(card: DeckCardEntity)

    @Query("DELETE FROM deck_card WHERE deckId = :deckId AND cardId = :cardId AND zone = :zone")
    suspend fun removeDeckCard(deckId: Long, cardId: String, zone: String)

    @Query(
        "SELECT dc.* FROM deck_card dc JOIN deck d ON d.id = dc.deckId WHERE d.gameId = :game " +
            "AND dc.cardId NOT IN (SELECT id FROM card WHERE gameId = :game)"
    )
    suspend fun orphanDeckCards(game: String): List<DeckCardEntity>

    @Query(
        "UPDATE deck_card SET " +
            "cardName = (SELECT name FROM card WHERE card.id = deck_card.cardId), " +
            "setCode = (SELECT setCode FROM card WHERE card.id = deck_card.cardId), " +
            "setName = (SELECT setName FROM card WHERE card.id = deck_card.cardId), " +
            "cardNumber = (SELECT number FROM card WHERE card.id = deck_card.cardId), " +
            "printTag = (SELECT printTag FROM card WHERE card.id = deck_card.cardId) " +
            "WHERE cardName = '' AND deckId IN (SELECT id FROM deck WHERE gameId = :game) " +
            "AND cardId IN (SELECT id FROM card WHERE gameId = :game)"
    )
    suspend fun backfillSnapshots(game: String)

    @Query("SELECT * FROM deck")
    suspend fun allDecks(): List<DeckEntity>

    @Query("SELECT * FROM deck_card")
    suspend fun allDeckCards(): List<DeckCardEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun restoreDecks(decks: List<DeckEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun restoreDeckCards(cards: List<DeckCardEntity>)

    @Query("DELETE FROM deck")
    suspend fun deleteAllDecks()

    @Query("DELETE FROM deck_card")
    suspend fun deleteAllDeckCards()
}

data class DeckSize(val deckId: Long, val n: Int)

@Dao
interface PortfolioDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(snapshot: PortfolioSnapshotEntity)

    @Query("SELECT * FROM portfolio_snapshot WHERE gameId = :game ORDER BY timestamp ASC")
    fun observe(game: String): Flow<List<PortfolioSnapshotEntity>>

    @Query("SELECT * FROM portfolio_snapshot ORDER BY timestamp ASC")
    suspend fun all(): List<PortfolioSnapshotEntity>

    @Query("DELETE FROM portfolio_snapshot WHERE gameId = :game")
    suspend fun deleteGame(game: String)

    @Query("DELETE FROM portfolio_snapshot")
    suspend fun deleteAll()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun restore(items: List<PortfolioSnapshotEntity>)
}

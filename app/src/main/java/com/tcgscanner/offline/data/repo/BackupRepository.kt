package com.tcgscanner.offline.data.repo

import androidx.room.withTransaction
import com.tcgscanner.offline.data.db.AppDatabase
import com.tcgscanner.offline.data.db.CardEntity
import com.tcgscanner.offline.data.db.CardPriceEntity
import com.tcgscanner.offline.data.db.CollectionItemEntity
import com.tcgscanner.offline.data.db.DeckCardEntity
import com.tcgscanner.offline.data.db.DeckEntity
import com.tcgscanner.offline.data.db.PortfolioSnapshotEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class BackupFile(
    val format: Int = 1,
    val collection: List<CollectionItemEntity>,
    val decks: List<DeckEntity>,
    val deckCards: List<DeckCardEntity>,
    val snapshots: List<PortfolioSnapshotEntity>,
    val customCards: List<CardEntity>,
    val customPrices: List<CardPriceEntity>
)

/** Local JSON backup / restore of everything the user created (no cloud involved). */
class BackupRepository(private val db: AppDatabase) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    suspend fun createBackup(): String = withContext(Dispatchers.IO) {
        json.encodeToString(
            BackupFile(
                collection = db.collection().rawAll(),
                decks = db.decks().allDecks(),
                deckCards = db.decks().allDeckCards(),
                snapshots = db.portfolio().all(),
                customCards = db.cards().customCards(),
                customPrices = db.cards().customPrices()
            )
        )
    }

    /** Replaces the user's data with the backup. The downloaded catalog is untouched. */
    suspend fun restore(text: String) = withContext(Dispatchers.IO) {
        val file = json.decodeFromString<BackupFile>(text)
        require(file.format == 1) { "Unsupported backup format" }
        db.withTransaction {
            db.collection().deleteAll()
            db.decks().deleteAllDecks()
            db.decks().deleteAllDeckCards()
            db.portfolio().deleteAll()
            db.cards().deleteCustomPrices()
            db.cards().deleteCustomCards()
            db.cards().upsertCards(file.customCards)
            db.cards().upsertPrices(file.customPrices)
            db.collection().insertAll(file.collection)
            db.decks().restoreDecks(file.decks)
            db.decks().restoreDeckCards(file.deckCards)
            db.portfolio().restore(file.snapshots)
        }
    }

    /** "Erase all my data": user data, catalog and signatures. */
    suspend fun eraseEverything() = withContext(Dispatchers.IO) {
        db.clearAllTables()
    }
}

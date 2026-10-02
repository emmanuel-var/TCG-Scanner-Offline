package com.tcgscanner.offline.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        CardEntity::class, CardPriceEntity::class, GradedPriceEntity::class, CollectionItemEntity::class,
        DeckEntity::class, DeckCardEntity::class, PortfolioSnapshotEntity::class, SyncStateEntity::class,
        CardSignatureEntity::class
    ],
    version = 3,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun cards(): CardDao
    abstract fun collection(): CollectionDao
    abstract fun decks(): DeckDao
    abstract fun portfolio(): PortfolioDao

    companion object {
        /**
         * v1 -> v2: card ids become stable composite keys and user rows carry an identity snapshot.
         *
         * The downloaded catalog is disposable, so it is emptied (its ids were volatile network ids). The user's
         * collection and decks are kept: their snapshot columns are back-filled from the catalog BEFORE it is
         * emptied, and CardRelinker re-attaches them to the re-downloaded cards after the next sync.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE card ADD COLUMN printTag TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE sync_state ADD COLUMN sourceUrl TEXT")
                for (table in listOf("collection_item", "deck_card")) {
                    for (col in listOf("cardName", "setCode", "setName", "cardNumber", "printTag")) {
                        db.execSQL("ALTER TABLE $table ADD COLUMN $col TEXT NOT NULL DEFAULT ''")
                    }
                    db.execSQL(
                        "UPDATE $table SET " +
                            "cardName = COALESCE((SELECT name FROM card WHERE card.id = $table.cardId), ''), " +
                            "setCode = COALESCE((SELECT setCode FROM card WHERE card.id = $table.cardId), ''), " +
                            "setName = COALESCE((SELECT setName FROM card WHERE card.id = $table.cardId), ''), " +
                            "cardNumber = COALESCE((SELECT number FROM card WHERE card.id = $table.cardId), '')"
                    )
                }
                db.execSQL("DELETE FROM card_price WHERE cardId IN (SELECT id FROM card WHERE isCustom = 0)")
                db.execSQL("DELETE FROM graded_price WHERE cardId IN (SELECT id FROM card WHERE isCustom = 0)")
                db.execSQL("DELETE FROM card_signature WHERE cardId IN (SELECT id FROM card WHERE isCustom = 0)")
                db.execSQL("DELETE FROM card WHERE isCustom = 0")
                db.execSQL("DELETE FROM sync_state")
            }
        }

        /** v2 -> v3: normalised set key + the composite (gameId, setKey, numberKey) index used by the scanner. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE card ADD COLUMN setKey TEXT NOT NULL DEFAULT ''")
                db.execSQL("UPDATE card SET setKey = lower(replace(replace(trim(setCode), ' ', ''), char(9), ''))")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_card_gameId_setKey_numberKey ON card (gameId, setKey, numberKey)")
            }
        }

        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "tcg_scanner.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build()
    }
}

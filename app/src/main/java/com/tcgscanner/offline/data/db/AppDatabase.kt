package com.tcgscanner.offline.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        CardEntity::class, CardPriceEntity::class, GradedPriceEntity::class, CollectionItemEntity::class,
        DeckEntity::class, DeckCardEntity::class, PortfolioSnapshotEntity::class, SyncStateEntity::class,
        CardSignatureEntity::class
    ],
    version = 1,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun cards(): CardDao
    abstract fun collection(): CollectionDao
    abstract fun decks(): DeckDao
    abstract fun portfolio(): PortfolioDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "tcg_scanner.db").build()
    }
}

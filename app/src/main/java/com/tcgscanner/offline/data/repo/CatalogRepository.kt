package com.tcgscanner.offline.data.repo

import androidx.room.withTransaction
import com.tcgscanner.offline.core.CardKeys
import com.tcgscanner.offline.core.GameDef
import com.tcgscanner.offline.core.GameId
import com.tcgscanner.offline.core.SourceId
import com.tcgscanner.offline.core.Text
import com.tcgscanner.offline.data.db.AppDatabase
import com.tcgscanner.offline.data.db.CardEntity
import com.tcgscanner.offline.data.db.CardPriceEntity
import com.tcgscanner.offline.data.db.GradedPriceEntity
import com.tcgscanner.offline.data.db.SetRow
import com.tcgscanner.offline.data.db.SyncStateEntity
import com.tcgscanner.offline.data.prefs.SettingsStore
import com.tcgscanner.offline.data.remote.BatchedSink
import com.tcgscanner.offline.data.remote.CatalogAdapters
import com.tcgscanner.offline.data.remote.CatalogSink
import com.tcgscanner.offline.data.remote.CatalogSource
import com.tcgscanner.offline.data.remote.CatalogUrls
import com.tcgscanner.offline.data.remote.RemoteCard
import com.tcgscanner.offline.data.remote.SourceNotConfigured
import com.tcgscanner.offline.data.remote.SyncProgress
import com.tcgscanner.offline.data.remote.UrlNormalizer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow

data class SyncResult(val game: GameId, val success: Boolean, val source: SourceId?, val cards: Int, val error: String?) {
}

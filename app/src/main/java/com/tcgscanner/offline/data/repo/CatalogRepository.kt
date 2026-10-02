package com.tcgscanner.offline.data.repo

import androidx.room.withTransaction
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
import com.tcgscanner.offline.data.remote.RemoteCard
import com.tcgscanner.offline.data.remote.SyncProgress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow

data class SyncResult(val game: GameId, val success: Boolean, val source: SourceId?, val cards: Int, val error: String?)

data class SetProgress(val code: String, val name: String, val total: Int, val owned: Int, val releaseDate: String?) {
    val fraction: Float get() = if (total == 0) 0f else (owned.toFloat() / total).coerceIn(0f, 1f)
    val percent: Int get() = (fraction * 100).toInt()
}

class CatalogRepository(
    private val db: AppDatabase,
    private val settings: SettingsStore,
    private val sources: Map<SourceId, CatalogSource>
) {
    private val cards get() = db.cards()

    fun observeLastSync(game: GameId): Flow<Long?> = cards.observeLastSync(game.code)
    fun observeSyncStates() = cards.observeSyncStates()
    fun observeCount(game: GameId): Flow<Int> = cards.observeCount(game.code)

    /**
     * Tries every configured source for [game] in order (primary first, then fallbacks).
     * Rows are upserted as they stream in, so an interrupted sync still keeps what was fetched.
     */
    suspend fun sync(game: GameDef, progress: (SyncProgress) -> Unit): SyncResult {
        var lastError: String? = null
        var tried = false
        for (sourceId in game.sources) {
            val source = sources[sourceId] ?: continue
            tried = true
            var count = 0
            val sink = CatalogSink { batch ->
                persist(game.id, batch)
                count += batch.size
            }
            try {
                source.sync(game, sink, progress)
                if (count == 0) {
                    lastError = "${source.label}: empty response"
                    continue
                }
                val total = cards.count(game.id.code)
                cards.putSyncState(SyncStateEntity(game.id.code, System.currentTimeMillis(), source.label, total, null))
                return SyncResult(game.id, true, source.id, count, null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = "${source.label}: ${e.message ?: e.javaClass.simpleName}"
            }
        }
        val message = lastError ?: if (tried) "No data" else "NO_SOURCE"
        val prev = db.cards().observeSyncStatesOnce().firstOrNull { it.gameId == game.id.code }
        if (prev != null) cards.putSyncState(prev.copy(lastError = message))
        return SyncResult(game.id, false, null, 0, message)
    }

    /** Imports a catalog JSON document picked by the user (works fully offline). */
    suspend fun importCatalogJson(game: GameDef, text: String): Int {
        val root = kotlinx.serialization.json.Json.parseToJsonElement(text)
        val batched = BatchedSink(CatalogSink { persist(game.id, it) }, 1000)
        CatalogAdapters.findCardObjects(root).forEach { o -> CatalogAdapters.generic(o)?.let { batched.add(it) } }
        batched.flush()
        if (batched.total > 0) {
            cards.putSyncState(SyncStateEntity(game.id.code, System.currentTimeMillis(), "File", cards.count(game.id.code), null))
        }
        return batched.total
    }

    private suspend fun persist(game: GameId, batch: List<RemoteCard>) {
        val cardRows = ArrayList<CardEntity>(batch.size)
        val priceRows = ArrayList<CardPriceEntity>()
        val gradedRows = ArrayList<GradedPriceEntity>()
        for (r in batch) {
            val id = "${game.code}:${r.sourceId}"
            cardRows.add(
                CardEntity(
                    id = id, gameId = game.code, setCode = r.setCode, setName = r.setName,
                    setReleaseDate = r.setReleaseDate, setTotal = r.setTotal,
                    number = r.number, numberKey = Text.numberKey(r.number),
                    name = if (r.suffix.isNullOrBlank()) r.name else "${r.name} (${r.suffix})",
                    nameKey = Text.nameKey(r.name),
                    rarity = r.rarity, category = r.category.code, imageUrl = r.imageUrl
                )
            )
            r.prices.forEach { (variant, usd) -> priceRows.add(CardPriceEntity(id, variant.code, usd)) }
            r.graded.forEach { g -> gradedRows.add(GradedPriceEntity(id, g.company, g.gradeX10, g.usd)) }
        }
        db.withTransaction {
            cards.upsertCards(cardRows)
            if (priceRows.isNotEmpty()) cards.upsertPrices(priceRows)
            if (gradedRows.isNotEmpty()) cards.upsertGraded(gradedRows)
        }
    }

    fun observeSets(game: GameId): Flow<List<SetRow>> = cards.observeSets(game.code)

    suspend fun addCustomCard(game: GameId, name: String, setName: String, number: String, usd: Double?): String {
        val id = "custom:${java.util.UUID.randomUUID()}"
        val setCode = Text.nameKey(setName).ifEmpty { "custom" }.take(12).uppercase()
        cards.upsertCards(
            listOf(
                CardEntity(
                    id = id, gameId = game.code, setCode = setCode, setName = setName.ifBlank { "Custom" },
                    setReleaseDate = null, setTotal = null, number = number.ifBlank { "0" },
                    numberKey = Text.numberKey(number.ifBlank { "0" }), name = name.trim(), nameKey = Text.nameKey(name),
                    rarity = null, category = "other", imageUrl = null, isCustom = true
                )
            )
        )
        if (usd != null && usd > 0) cards.upsertPrices(listOf(CardPriceEntity(id, "normal", usd)))
        return id
    }
}

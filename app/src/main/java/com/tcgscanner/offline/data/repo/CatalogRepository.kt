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
    companion object {
        /** The game has no default URL and the user has not provided one yet. Not an error. */
        const val NO_SOURCE = "NO_SOURCE"
    }
}

data class SetProgress(val code: String, val name: String, val total: Int, val owned: Int, val releaseDate: String?) {
    val fraction: Float get() = if (total == 0) 0f else (owned.toFloat() / total).coerceIn(0f, 1f)
    val percent: Int get() = (fraction * 100).toInt()
}

/**
 * Keeps one game's catalog in Room in sync with its source.
 *
 * Identity: every card row is keyed by [CardKeys] (game + set + number [+ print tag]), never by a network id,
 * so re-downloading the same data (or the same data from another source that uses the same set codes) upserts
 * in place instead of creating duplicates.
 *
 * Source change: when the effective catalog URL differs from the one whose data is stored (user override
 * saved, cleared or changed), the game's catalog rows are purged in the same transaction as the first batch of
 * new data. Collection, decks and the trade binder are never touched; [CardRelinker] re-attaches them afterwards.
 */
class CatalogRepository(
    private val db: AppDatabase,
    private val settings: SettingsStore,
    private val sources: Map<SourceId, CatalogSource>,
    private val relinker: CardRelinker,
    private val onCatalogPurged: (GameId) -> Unit = {}
) {
    private val cards get() = db.cards()

    fun observeLastSync(game: GameId): Flow<Long?> = cards.observeLastSync(game.code)
    fun observeSyncStates() = cards.observeSyncStates()
    fun observeCount(game: GameId): Flow<Int> = cards.observeCount(game.code)

    /** The URL this game is currently configured to use ("" when it has no default and no override). */
    suspend fun effectiveUrl(game: GameId): String {
        val override = settings.catalogUrlOnce(game)
        return UrlNormalizer.normalize(override.ifBlank { CatalogUrls.default(game).orEmpty() })
    }

    /**
     * Tries every source for [game] in order (primary URL first, then public fallbacks). Rows are upserted as they
     * stream in. [forcePurge] (set by the worker right after the user changed the URL) purges even if the stored
     * URL bookkeeping is missing; otherwise a purge happens when the stored and the effective URL differ.
     */
    suspend fun sync(game: GameDef, forcePurge: Boolean = false, progress: (SyncProgress) -> Unit): SyncResult {
        val effective = effectiveUrl(game.id)
        val previous = cards.observeSyncStatesOnce().firstOrNull { it.gameId == game.id.code }
        val previousUrl = previous?.sourceUrl
        var purgePending = forcePurge || (previousUrl != null && previousUrl != effective)
        val seen = HashMap<String, Int>()

        var lastError: String? = null
        for (sourceId in game.sources) {
            val source = sources[sourceId] ?: continue
            var count = 0
            val sink = CatalogSink { batch ->
                val purge = purgePending
                persist(game.id, batch, seen, purgeFirst = purge)
                if (purge) {
                    purgePending = false
                    onCatalogPurged(game.id)
                }
                count += batch.size
            }
            try {
                source.sync(game, sink, progress)
                if (count == 0) {
                    lastError = "${source.label}: empty response"
                    continue
                }
                val total = cards.count(game.id.code)
                cards.putSyncState(SyncStateEntity(game.id.code, System.currentTimeMillis(), source.label, total, null, effective))
                runCatching { relinker.relink(game.id) } // best effort: never fails the sync
                return SyncResult(game.id, true, source.id, count, null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: SourceNotConfigured) {
                continue
            } catch (e: Exception) {
                lastError = "${source.label}: ${e.message ?: e.javaClass.simpleName}"
            }
        }
        val error = lastError ?: return SyncResult(game.id, false, null, 0, SyncResult.NO_SOURCE)
        // Keep the old catalog and remember why the refresh failed.
        if (previous != null) cards.putSyncState(previous.copy(lastError = error))
        return SyncResult(game.id, false, null, 0, error)
    }

    /** Imports a catalog JSON file picked by the user (works fully offline). Additive: keys are upserted. */
    suspend fun importCatalogJson(game: GameDef, text: String): Int {
        val root = kotlinx.serialization.json.Json.parseToJsonElement(text)
        val seen = HashMap<String, Int>()
        val batched = BatchedSink(CatalogSink { persist(game.id, it, seen, purgeFirst = false) }, 1000)
        CatalogAdapters.findCardObjects(root).forEach { o -> CatalogAdapters.generic(o)?.let { batched.add(it) } }
        batched.flush()
        if (batched.total > 0) {
            val previous = cards.observeSyncStatesOnce().firstOrNull { it.gameId == game.id.code }
            cards.putSyncState(
                SyncStateEntity(game.id.code, System.currentTimeMillis(), "File", cards.count(game.id.code), null, previous?.sourceUrl)
            )
            runCatching { relinker.relink(game.id) }
        }
        return batched.total
    }

    /** Removes the game's downloaded catalog (prices, graded prices and artwork signatures included). User data stays. */
    suspend fun purgeCatalog(game: GameId) {
        db.withTransaction { purgeInTransaction(game) }
        onCatalogPurged(game)
    }

    private suspend fun purgeInTransaction(game: GameId) {
        // Dependents first: they are selected through the card rows.
        cards.deleteCatalogPrices(game.code)
        cards.deleteCatalogGraded(game.code)
        cards.deleteCatalogSignatures(game.code)
        cards.deleteCatalog(game.code)
    }

    private suspend fun persist(game: GameId, batch: List<RemoteCard>, seen: MutableMap<String, Int>, purgeFirst: Boolean) {
        val cardRows = ArrayList<CardEntity>(batch.size)
        val priceRows = ArrayList<CardPriceEntity>()
        val gradedRows = ArrayList<GradedPriceEntity>()
        for (r in batch) {
            val (id, tag) = CardKeys.unique(game, r.setCode, r.number, r.printTag, seen)
            cardRows.add(
                CardEntity(
                    id = id, gameId = game.code, setCode = r.setCode, setKey = CardKeys.setKey(r.setCode), setName = r.setName,
                    setReleaseDate = r.setReleaseDate, setTotal = r.setTotal,
                    number = r.number, numberKey = Text.numberKey(r.number),
                    name = if (r.suffix.isNullOrBlank()) r.name else "${r.name} (${r.suffix})",
                    nameKey = Text.nameKey(r.name),
                    rarity = r.rarity, category = r.category.code, imageUrl = r.imageUrl, printTag = tag
                )
            )
            r.prices.forEach { (variant, usd) -> priceRows.add(CardPriceEntity(id, variant.code, usd)) }
            r.graded.forEach { g -> gradedRows.add(GradedPriceEntity(id, g.company, g.gradeX10, g.usd)) }
        }
        db.withTransaction {
            if (purgeFirst) purgeInTransaction(game)
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
                    id = id, gameId = game.code, setCode = setCode, setKey = CardKeys.setKey(setCode), setName = setName.ifBlank { "Custom" },
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

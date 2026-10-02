package com.tcgscanner.offline.data.remote

import com.tcgscanner.offline.core.CardCategory
import com.tcgscanner.offline.core.CardVariant
import com.tcgscanner.offline.core.GameDef
import com.tcgscanner.offline.core.SourceId
import com.tcgscanner.offline.data.prefs.ApiKeys

data class RemoteGradedPrice(val company: String, val gradeX10: Int, val usd: Double)

/** A card print as delivered by any remote source, already normalised. */
data class RemoteCard(
    val sourceId: String,
    val setCode: String,
    val setName: String,
    val setReleaseDate: String? = null,
    val setTotal: Int? = null,
    val number: String,
    val name: String,
    val rarity: String? = null,
    /** Appended to the display name only (e.g. "Alt Art"); name matching ignores it. */
    val suffix: String? = null,
    val category: CardCategory = CardCategory.OTHER,
    val imageUrl: String? = null,
    val prices: Map<CardVariant, Double> = emptyMap(),
    val graded: List<RemoteGradedPrice> = emptyList()
)

/** Receives batches while a source streams its catalog; the repository persists them. */
fun interface CatalogSink {
    suspend fun accept(cards: List<RemoteCard>)
}

data class SyncProgress(val message: String, val fraction: Float? = null)

class SourceNotConfigured(message: String) : Exception(message)

interface CatalogSource {
    val id: SourceId

    /** False when the source needs credentials the user has not entered yet. */
    fun isConfigured(keys: ApiKeys): Boolean = true

    suspend fun sync(game: GameDef, keys: ApiKeys, sink: CatalogSink, progress: (SyncProgress) -> Unit)
}

/** Buffers cards and flushes them to the sink in fixed-size batches. */
class BatchedSink(private val sink: CatalogSink, private val size: Int = 500) {
    private val buffer = ArrayList<RemoteCard>(size)
    var total = 0
        private set

    suspend fun add(card: RemoteCard) {
        buffer.add(card)
        total++
        if (buffer.size >= size) flush()
    }

    suspend fun flush() {
        if (buffer.isEmpty()) return
        sink.accept(ArrayList(buffer))
        buffer.clear()
    }
}

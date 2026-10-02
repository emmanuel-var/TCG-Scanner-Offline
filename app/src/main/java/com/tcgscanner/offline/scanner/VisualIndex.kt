package com.tcgscanner.offline.scanner

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.tcgscanner.offline.core.GameId
import com.tcgscanner.offline.data.db.AppDatabase
import com.tcgscanner.offline.data.db.CardSignatureEntity
import com.tcgscanner.offline.data.remote.Http
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.Request

data class VisualMatch(val cardId: String, val distance: Int, val cosine: Float?)

data class IndexState(val running: Boolean = false, val game: GameId? = null, val done: Int = 0, val failed: Int = 0, val error: String? = null)

/**
 * Downloads reference artwork once (opt-in, Wi-Fi recommended) and stores compact signatures, so the
 * scanner can later identify cards by artwork with no connection at all.
 */
class VisualIndexer(
    private val scope: CoroutineScope,
    private val db: AppDatabase,
    private val http: Http,
    private val embedder: TfliteEmbedder,
    private val matcher: VisualMatcher
) {
    private val _state = MutableStateFlow(IndexState())
    val state: StateFlow<IndexState> = _state.asStateFlow()
    private var job: Job? = null

    fun start(game: GameId) {
        if (job?.isActive == true) return
        job = scope.launch {
            _state.value = IndexState(running = true, game = game)
            try {
                val gate = Semaphore(4)
                while (true) {
                    val batch = db.cards().cardsWithoutSignature(game.code, 48)
                    if (batch.isEmpty()) break
                    val results = coroutineScope {
                        batch.map { card ->
                            async(Dispatchers.IO) {
                                gate.withPermit { runCatching { sign(card.id, game, card.imageUrl!!) }.getOrNull() }
                            }
                        }.awaitAll()
                    }
                    val ok = results.filterNotNull()
                    // Cards whose image failed get a zero signature so we never loop on them forever.
                    val failedIds = batch.map { it.id }.toSet() - ok.map { it.cardId }.toSet()
                    val placeholders = failedIds.map { CardSignatureEntity(it, game.code, 0L, 0L, null) }
                    db.cards().putSignatures(ok + placeholders)
                    _state.value = _state.value.copy(done = _state.value.done + ok.size, failed = _state.value.failed + failedIds.size)
                }
                matcher.invalidate(game)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = _state.value.copy(error = e.message)
            } finally {
                _state.value = _state.value.copy(running = false)
            }
        }
    }

    fun cancel() {
        job?.cancel()
    }

    suspend fun clear(game: GameId) {
        db.cards().clearSignatures(game.code)
        matcher.invalidate(game)
    }

    private fun sign(cardId: String, game: GameId, url: String): CardSignatureEntity? {
        val request = Request.Builder().url(url).header("User-Agent", Http.USER_AGENT).build()
        val bitmap: Bitmap = http.client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val bytes = resp.body?.bytes() ?: return null
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        }
        val sig = VisualSignature.compute(bitmap)
        val emb = embedder.embed(bitmap)?.let(TfliteEmbedder::toBytes)
        bitmap.recycle()
        return CardSignatureEntity(cardId, game.code, sig.dHash, sig.aHash, emb)
    }
}

/** Nearest-neighbour search over the stored signatures of one game. */
class VisualMatcher(private val db: AppDatabase, private val embedder: TfliteEmbedder) {
    private class Entry(val cardId: String, val sig: Signature, val embedding: FloatArray?)

    private val cache = HashMap<GameId, List<Entry>>()

    fun invalidate(game: GameId) {
        synchronized(cache) { cache.remove(game) }
    }

    private suspend fun entries(game: GameId): List<Entry> {
        synchronized(cache) { cache[game] }?.let { return it }
        val loaded = db.cards().signatures(game.code)
            .filter { it.dHash != 0L || it.aHash != 0L }
            .map { Entry(it.cardId, Signature(it.dHash, it.aHash), it.embedding?.let(TfliteEmbedder::fromBytes)) }
        synchronized(cache) { cache[game] = loaded }
        return loaded
    }

    suspend fun indexedCount(game: GameId): Int = entries(game).size

    suspend fun identify(game: GameId, card: Bitmap, limit: Int = 5): List<VisualMatch> = withContext(Dispatchers.Default) {
        val all = entries(game)
        if (all.isEmpty()) return@withContext emptyList()
        val query = embedder.embed(card)
        if (query != null && all.any { it.embedding != null }) {
            return@withContext all.filter { it.embedding != null }
                .map { VisualMatch(it.cardId, 0, TfliteEmbedder.cosine(query, it.embedding!!)) }
                .sortedByDescending { it.cosine }
                .take(limit)
        }
        val sig = VisualSignature.compute(card)
        all.map { VisualMatch(it.cardId, VisualSignature.distance(sig, it.sig), null) }
            .sortedBy { it.distance }
            .take(limit)
    }
}

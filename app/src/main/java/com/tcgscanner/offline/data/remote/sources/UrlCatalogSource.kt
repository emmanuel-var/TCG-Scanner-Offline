package com.tcgscanner.offline.data.remote.sources

import com.tcgscanner.offline.core.GameDef
import com.tcgscanner.offline.core.GameId
import com.tcgscanner.offline.core.SourceId
import com.tcgscanner.offline.data.remote.BatchedSink
import com.tcgscanner.offline.data.remote.CatalogAdapters
import com.tcgscanner.offline.data.remote.CatalogFormat
import com.tcgscanner.offline.data.remote.CatalogSink
import com.tcgscanner.offline.data.remote.CatalogSource
import com.tcgscanner.offline.data.remote.CatalogUrls
import com.tcgscanner.offline.data.remote.Http
import com.tcgscanner.offline.data.remote.JsonStream
import com.tcgscanner.offline.data.remote.RemoteCard
import com.tcgscanner.offline.data.remote.SyncProgress
import com.tcgscanner.offline.data.remote.UrlNormalizer
import com.tcgscanner.offline.data.remote.a
import com.tcgscanner.offline.data.remote.int
import com.tcgscanner.offline.data.remote.obj
import com.tcgscanner.offline.data.remote.s
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.ResponseBody
import java.io.IOException

/**
 * Primary catalog source for every game: downloads the game's catalog URL (the community default from
 * [CatalogUrls] or the user's override), detects the document format from its first bytes and streams the
 * cards into Room. No API keys are involved.
 */
class UrlCatalogSource(
    private val http: Http,
    /** The user's override for [game], or blank to use the default. */
    private val overrideFor: suspend (GameId) -> String
) : CatalogSource {
    override val id = SourceId.CATALOG_URL

    @Volatile private var host: String? = null
    override val label: String get() = host ?: id.label

    override suspend fun sync(game: GameDef, sink: CatalogSink, progress: (SyncProgress) -> Unit) {
        val configured = overrideFor(game.id).ifBlank { CatalogUrls.default(game.id) }
        val url = UrlNormalizer.normalize(configured)
        val parsed = url.toHttpUrlOrNull()
        require(parsed != null && parsed.isHttps) { "Invalid catalog URL (https required): $url" }
        host = parsed.host

        val batched = BatchedSink(sink, 1000)
        if (parsed.host == POKEMON_TCG_HOST) {
            pokemonPaged(parsed, batched, progress)
        } else {
            var next: String? = url
            var hops = 0
            while (next != null) {
                require(hops++ < 3) { "Too many redirects between catalog descriptors" }
                progress(SyncProgress("${parsed.host} · downloading"))
                next = fetchOnce(next, batched, progress)
            }
        }
        batched.flush()
        if (batched.total == 0) throw IOException("No cards recognised at ${parsed.host}")
    }

    /** Returns another URL to follow (Scryfall's bulk descriptor), or null once cards were read. */
    private suspend fun fetchOnce(url: String, out: BatchedSink, progress: (SyncProgress) -> Unit): String? =
        http.getWithRetry(url, attempts = 3) { body ->
            val head = peekHead(body)
            val streamKey = if (head.trimStart().startsWith("[")) null else "data"
            when (CatalogFormat.sniff(head)) {
                CatalogFormat.BULK_INDEX -> Json.parseToJsonElement(body.string()).obj()?.s("download_uri")
                    ?: throw IOException("Bulk descriptor without download_uri")
                CatalogFormat.SCRYFALL_CARDS -> {
                    JsonStream.forEachArrayElement(body, streamKey) { o -> CatalogAdapters.scryfall(o)?.let { out.add(it); tick(out, progress) } }
                    null
                }
                CatalogFormat.POKEMON_TCG -> {
                    JsonStream.forEachArrayElement(body, streamKey) { o -> CatalogAdapters.pokemon(o)?.let { out.add(it); tick(out, progress) } }
                    null
                }
                CatalogFormat.YGOPRODECK -> {
                    JsonStream.forEachArrayElement(body, streamKey) { o -> CatalogAdapters.ygo(o).forEach { out.add(it); tick(out, progress) } }
                    null
                }
                CatalogFormat.LORCANA_API -> {
                    JsonStream.forEachArrayElement(body, streamKey) { o -> CatalogAdapters.lorcanaApi(o)?.let { out.add(it) } }
                    null
                }
                CatalogFormat.DIGIMON_CARD_IO -> {
                    JsonStream.forEachArrayElement(body, streamKey) { o -> CatalogAdapters.digimon(o)?.let { out.add(it) } }
                    null
                }
                CatalogFormat.GENERIC -> {
                    val root = Json.parseToJsonElement(body.string())
                    CatalogAdapters.findCardObjects(root).forEach { o -> CatalogAdapters.generic(o)?.let { out.add(it) } }
                    null
                }
            }
        }

    private suspend fun tick(out: BatchedSink, progress: (SyncProgress) -> Unit) {
        if (out.total % 5000 == 0) progress(SyncProgress("${host.orEmpty()} · ${out.total} cards"))
    }

    /** api.pokemontcg.io pages results (max 250 per page); no key needed for a daily sync. */
    private suspend fun pokemonPaged(base: HttpUrl, out: BatchedSink, progress: (SyncProgress) -> Unit) {
        var page = 1
        var total = Int.MAX_VALUE
        while ((page - 1) * PAGE_SIZE < total) {
            val builder = base.newBuilder()
            if (base.queryParameter("pageSize") == null) builder.setQueryParameter("pageSize", PAGE_SIZE.toString())
            if (base.queryParameter("select") == null) builder.setQueryParameter("select", POKEMON_SELECT)
            builder.setQueryParameter("page", page.toString())
            val root = http.getWithRetry(builder.build().toString(), attempts = 5) { Json.parseToJsonElement(it.string()) }.obj() ?: break
            total = root["totalCount"].int() ?: 0
            val data = root.a("data") ?: break
            if (data.isEmpty()) break
            data.forEach { el -> el.obj()?.let { CatalogAdapters.pokemon(it) }?.let { out.add(it) } }
            progress(SyncProgress("$POKEMON_TCG_HOST · $page/${(total + PAGE_SIZE - 1) / PAGE_SIZE}", minOf(1f, page * PAGE_SIZE.toFloat() / maxOf(total, 1))))
            page++
        }
    }

    private fun peekHead(body: ResponseBody): String {
        val src = body.source()
        src.request(HEAD_BYTES)
        val n = minOf(src.buffer.size, HEAD_BYTES).toInt()
        return src.buffer.snapshot(n).utf8()
    }

    private companion object {
        const val POKEMON_TCG_HOST = "api.pokemontcg.io"
        const val POKEMON_SELECT = "id,name,supertype,rarity,number,set,images,tcgplayer"
        const val PAGE_SIZE = 250
        const val HEAD_BYTES = 16L * 1024
    }
}

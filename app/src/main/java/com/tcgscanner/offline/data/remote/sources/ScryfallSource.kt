package com.tcgscanner.offline.data.remote.sources

import android.util.JsonReader
import android.util.JsonToken
import com.tcgscanner.offline.core.GameDef
import com.tcgscanner.offline.core.SourceId
import com.tcgscanner.offline.data.remote.BatchedSink
import com.tcgscanner.offline.data.remote.CatalogAdapters
import com.tcgscanner.offline.data.remote.CatalogSink
import com.tcgscanner.offline.data.remote.CatalogSource
import com.tcgscanner.offline.data.remote.Http
import com.tcgscanner.offline.data.remote.JsonStream
import com.tcgscanner.offline.data.remote.SourceNotConfigured
import com.tcgscanner.offline.data.remote.SyncProgress
import com.tcgscanner.offline.data.remote.obj
import com.tcgscanner.offline.data.remote.s
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.IOException
import java.io.InputStreamReader

/**
 * Magic: The Gathering from Scryfall's bulk data, as two explicit, sequential HTTP requests. No format detection:
 *
 *  1. GET [DESCRIPTOR_URL] (a few hundred bytes), read whole, take its `"download_uri"`.
 *  2. A NEW GET on that URI; the body is a JSON array at the root (`[ {card}, {card}, ... ]`), streamed with a
 *     [JsonReader] that starts with `beginArray()` and hands every card object to [CatalogAdapters.scryfall].
 *
 * Used only while the user has no custom Magic URL; a custom URL goes through [UrlCatalogSource].
 */
class ScryfallSource(
    private val http: Http,
    private val overrideFor: suspend (com.tcgscanner.offline.core.GameId) -> String,
    private val descriptorUrl: String = DESCRIPTOR_URL
) : CatalogSource {
    override val id = SourceId.SCRYFALL
    override val label: String get() = "Scryfall"

    override suspend fun sync(game: GameDef, sink: CatalogSink, progress: (SyncProgress) -> Unit) {
        if (overrideFor(game.id).isNotBlank()) throw SourceNotConfigured("Magic uses the user's catalog URL")

        // Request 1: the descriptor, fully in memory.
        progress(SyncProgress("Scryfall · bulk data index"))
        val descriptor = http.getWithRetry(descriptorUrl, attempts = 3) { it.string() }
        val downloadUri = downloadUri(descriptor) ?: throw IOException("Scryfall descriptor has no download_uri")

        // Request 2: a new connection to the file itself.
        progress(SyncProgress("Scryfall · downloading cards"))
        val out = BatchedSink(sink, 1000)
        http.getWithRetry(downloadUri, attempts = 3) { body ->
            JsonReader(InputStreamReader(body.byteStream(), Charsets.UTF_8)).use { reader ->
                if (reader.peek() != JsonToken.BEGIN_ARRAY) throw IOException("Scryfall bulk file is not a JSON array")
                reader.beginArray()
                while (reader.hasNext()) {
                    val card = JsonStream.readElement(reader)
                    if (card is JsonObject) {
                        CatalogAdapters.scryfall(card)?.let {
                            out.add(it)
                            if (out.total % 5000 == 0) progress(SyncProgress("Scryfall · ${out.total} cards"))
                        }
                    }
                }
                reader.endArray()
            }
        }
        out.flush()
        if (out.total == 0) throw IOException("Scryfall bulk file contained no cards")
    }

    /** `download_uri` of the descriptor; a `/bulk-data` list is also accepted (its `default_cards` entry). */
    internal fun downloadUri(json: String): String? {
        val root = Json.parseToJsonElement(json).obj() ?: return null
        root.s("download_uri")?.let { return it }
        val entries = root["data"].let { (it as? kotlinx.serialization.json.JsonArray)?.mapNotNull { e -> e.obj() } }.orEmpty()
        return (entries.firstOrNull { it.s("type") == "default_cards" } ?: entries.firstOrNull())?.s("download_uri")
    }

    companion object {
        const val DESCRIPTOR_URL = "https://api.scryfall.com/bulk-data/default-cards"
    }
}

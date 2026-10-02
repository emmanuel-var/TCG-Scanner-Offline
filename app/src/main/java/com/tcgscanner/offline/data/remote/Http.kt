package com.tcgscanner.offline.data.remote

import android.util.JsonReader
import android.util.JsonToken
import com.tcgscanner.offline.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody
import java.io.IOException
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

class HttpStatusException(val code: Int, val url: String) : IOException("HTTP $code for $url")

class Http(val client: OkHttpClient = defaultClient()) {

    /** GET [url] and hand the body to [block]; cancelling the coroutine cancels the call. */
    suspend fun <T> get(
        url: String,
        headers: Map<String, String> = emptyMap(),
        block: suspend (ResponseBody) -> T
    ): T = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).apply {
            header("User-Agent", USER_AGENT)
            header("Accept", "application/json")
            headers.forEach { (k, v) -> header(k, v) }
        }.build()
        execute(request, block)
    }

    suspend fun <T> postForm(
        url: String,
        form: Map<String, String>,
        headers: Map<String, String> = emptyMap(),
        block: suspend (ResponseBody) -> T
    ): T = withContext(Dispatchers.IO) {
        val body = FormBody.Builder().apply { form.forEach { (k, v) -> add(k, v) } }.build()
        val request = Request.Builder().url(url).post(body).apply {
            header("User-Agent", USER_AGENT)
            headers.forEach { (k, v) -> header(k, v) }
        }.build()
        execute(request, block)
    }

    private suspend fun <T> execute(request: Request, block: suspend (ResponseBody) -> T): T {
        val call = client.newCall(request)
        val handle = coroutineContext[Job]?.invokeOnCompletion { call.cancel() }
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) throw HttpStatusException(response.code, request.url.toString())
                val body = response.body ?: throw IOException("Empty body for ${request.url}")
                return block(body)
            }
        } finally {
            handle?.dispose()
        }
    }

    /** Retries transient failures (5xx, 429, IO errors) with exponential back-off. */
    suspend fun <T> getWithRetry(
        url: String,
        headers: Map<String, String> = emptyMap(),
        attempts: Int = 4,
        block: suspend (ResponseBody) -> T
    ): T {
        var wait = 1500L
        var last: Exception? = null
        repeat(attempts) { n ->
            try {
                return get(url, headers, block)
            } catch (e: HttpStatusException) {
                if (e.code in 400..499 && e.code != 429 && e.code != 408) throw e
                last = e
            } catch (e: IOException) {
                last = e
            }
            if (n < attempts - 1) {
                delay(wait)
                wait *= 2
            }
        }
        throw last ?: IOException("Request failed: $url")
    }

    suspend fun getString(url: String, headers: Map<String, String> = emptyMap()): String =
        getWithRetry(url, headers) { it.string() }

    suspend fun getJson(url: String, headers: Map<String, String> = emptyMap()): JsonElement =
        getWithRetry(url, headers) { Json.parseToJsonElement(it.string()) }

    companion object {
        val USER_AGENT = "TCGScannerOffline/${BuildConfig.VERSION_NAME} (Android; open-source collection tracker)"

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}

/** Low-memory streaming over very large JSON documents (Scryfall bulk data, MTGJSON prices...). */
object JsonStream {
    /**
     * Streams every object of the catalog array. The root decides how it is read: a root JSON array (Scryfall's
     * bulk file: `[ {card}, {card}, ... ]`) is iterated element by element whatever [key] says; a root object
     * is scanned for the array under [key] (or, when [key] is null, the first array it contains).
     */
    suspend fun forEachArrayElement(body: ResponseBody, key: String?, onElement: suspend (JsonObject) -> Unit) {
        withContext(Dispatchers.IO) {
            JsonReader(InputStreamReader(body.byteStream(), Charsets.UTF_8)).use { reader ->
                when (reader.peek()) {
                    JsonToken.BEGIN_ARRAY -> readArray(reader, onElement)
                    JsonToken.BEGIN_OBJECT -> {
                        reader.beginObject()
                        var done = false
                        while (reader.hasNext()) {
                            val name = reader.nextName()
                            if (!done && (key == null || name == key) && reader.peek() == JsonToken.BEGIN_ARRAY) {
                                readArray(reader, onElement)
                                done = true
                            } else {
                                reader.skipValue()
                            }
                        }
                    }
                    else -> Unit
                }
            }
        }
    }

    /** Streams every (name, value) entry of the object found under [key] in the root object. */
    suspend fun forEachObjectEntry(body: ResponseBody, key: String, onEntry: suspend (String, JsonObject) -> Unit) {
        withContext(Dispatchers.IO) {
            JsonReader(InputStreamReader(body.byteStream(), Charsets.UTF_8)).use { reader ->
                reader.beginObject()
                while (reader.hasNext()) {
                    if (reader.nextName() == key && reader.peek() == JsonToken.BEGIN_OBJECT) {
                        reader.beginObject()
                        while (reader.hasNext()) {
                            val name = reader.nextName()
                            val el = readElement(reader)
                            if (el is JsonObject) onEntry(name, el)
                        }
                        reader.endObject()
                    } else {
                        reader.skipValue()
                    }
                }
            }
        }
    }

    private suspend fun readArray(reader: JsonReader, onElement: suspend (JsonObject) -> Unit) {
        reader.beginArray()
        while (reader.hasNext()) {
            val el = readElement(reader)
            if (el is JsonObject) onElement(el)
        }
        reader.endArray()
    }

    internal fun readElement(reader: JsonReader): JsonElement = when (reader.peek()) {
        JsonToken.BEGIN_OBJECT -> {
            val map = LinkedHashMap<String, JsonElement>()
            reader.beginObject()
            while (reader.hasNext()) map[reader.nextName()] = readElement(reader)
            reader.endObject()
            JsonObject(map)
        }
        JsonToken.BEGIN_ARRAY -> {
            val list = ArrayList<JsonElement>()
            reader.beginArray()
            while (reader.hasNext()) list.add(readElement(reader))
            reader.endArray()
            JsonArray(list)
        }
        JsonToken.STRING -> JsonPrimitive(reader.nextString())
        JsonToken.NUMBER -> JsonPrimitive(reader.nextString().toBigDecimal())
        JsonToken.BOOLEAN -> JsonPrimitive(reader.nextBoolean())
        JsonToken.NULL -> { reader.nextNull(); JsonNull }
        else -> { reader.skipValue(); JsonNull }
    }
}

// ---- small JsonElement helpers used by every source ----------------------------------------------

fun JsonElement?.obj(): JsonObject? = this as? JsonObject
fun JsonElement?.arr(): JsonArray? = this as? JsonArray
fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
fun JsonElement?.dbl(): Double? = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.replace(",", "")?.removePrefix("$")?.trim()?.toDoubleOrNull()
fun JsonElement?.int(): Int? = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.toDoubleOrNull()?.toInt()
fun JsonObject.s(key: String): String? = this[key].str()
fun JsonObject.d(key: String): Double? = this[key].dbl()
fun JsonObject.o(key: String): JsonObject? = this[key].obj()
fun JsonObject.a(key: String): JsonArray? = this[key].arr()

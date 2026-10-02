package com.tcgscanner.offline

import com.tcgscanner.offline.core.GameId
import com.tcgscanner.offline.core.Games
import com.tcgscanner.offline.data.remote.*
import com.tcgscanner.offline.data.remote.sources.*
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class TcgdexScratchTest {
    private fun http(routes: Map<String, String>): Http = Http(OkHttpClient.Builder().addInterceptor { chain ->
        val url = chain.request().url.toString()
        val hit = routes.entries.firstOrNull { url == it.key }
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(if (hit == null) 404 else 200).message("x")
            .body((hit?.value ?: "{}").toResponseBody("application/json".toMediaType())).build()
    }.build())

    @Test fun aBrokenSetIsSkippedNotFatal() {
        val base = "https://api.tcgdex.net/v2/ja"
        val h = http(mapOf(
            "$base/sets" to """[{"id":"SM1+","name":"Strength"},{"id":"SV1S","name":"Scarlet ex"}]""",
            "$base/sets/SV1S" to """{"name":"Scarlet ex","cardCount":{"official":78},"cards":[{"id":"SV1S-001","localId":"001","name":"Sprigatito","image":"https://assets.tcgdex.net/ja/SV/SV1S/001"}]}"""
        ))
        val out = ArrayList<RemoteCard>()
        runBlocking { TcgdexSource(h).sync(Games[GameId.POKEMON_JP], { out.addAll(it) }, {}) }
        assertEquals(1, out.size); assertEquals("Sprigatito", out[0].name)
    }

    @Test fun plusIdsAreTriedPercentEncoded() {
        val base = "https://api.tcgdex.net/v2/ja"
        val h = http(mapOf(
            "$base/sets" to """[{"id":"SM1+","name":"Strength"}]""",
            "$base/sets/SM1%2B" to """{"name":"Strength","cards":[{"id":"SM1+-001","localId":"001","name":"Venusaur"}]}"""
        ))
        val out = ArrayList<RemoteCard>()
        runBlocking { TcgdexSource(h).sync(Games[GameId.POKEMON_JP], { out.addAll(it) }, {}) }
        assertEquals(1, out.size)
    }

    @Test fun allSetsFailingStillReportsAnError() {
        val base = "https://api.tcgdex.net/v2/ja"
        try { runBlocking { TcgdexSource(http(mapOf("$base/sets" to """[{"id":"SM1+","name":"x"}]"""))).sync(Games[GameId.POKEMON_JP], { }, {}) }; fail() } catch (e: java.io.IOException) { }
    }
}

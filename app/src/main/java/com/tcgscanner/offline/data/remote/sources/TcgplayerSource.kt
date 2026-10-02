package com.tcgscanner.offline.data.remote.sources

import com.tcgscanner.offline.core.CardCategory
import com.tcgscanner.offline.core.CardVariant
import com.tcgscanner.offline.core.GameDef
import com.tcgscanner.offline.core.SourceId
import com.tcgscanner.offline.core.Text
import com.tcgscanner.offline.data.prefs.ApiKeys
import com.tcgscanner.offline.data.remote.BatchedSink
import com.tcgscanner.offline.data.remote.CatalogSink
import com.tcgscanner.offline.data.remote.CatalogSource
import com.tcgscanner.offline.data.remote.Http
import com.tcgscanner.offline.data.remote.RemoteCard
import com.tcgscanner.offline.data.remote.SourceNotConfigured
import com.tcgscanner.offline.data.remote.SyncProgress
import com.tcgscanner.offline.data.remote.a
import com.tcgscanner.offline.data.remote.d
import com.tcgscanner.offline.data.remote.int
import com.tcgscanner.offline.data.remote.obj
import com.tcgscanner.offline.data.remote.s
import kotlinx.serialization.json.JsonObject

/**
 * TCGplayer catalog + pricing API. Requires the user's own API credentials (client id / secret),
 * which are entered in Settings. The category is resolved by name, so it works for any game TCGplayer lists.
 */
class TcgplayerSource(private val http: Http) : CatalogSource {
    override val id = SourceId.TCGPLAYER
    private val api = "https://api.tcgplayer.com"

    override fun isConfigured(keys: ApiKeys) = keys.tcgplayerClientId.isNotBlank() && keys.tcgplayerClientSecret.isNotBlank()

    override suspend fun sync(game: GameDef, keys: ApiKeys, sink: CatalogSink, progress: (SyncProgress) -> Unit) {
        if (!isConfigured(keys)) throw SourceNotConfigured("TCGplayer credentials missing")
        val wanted = game.tcgplayerCategory ?: throw SourceNotConfigured("No TCGplayer category for ${game.id}")
        progress(SyncProgress("TCGplayer · authenticating"))
        val token = http.postForm(
            "$api/token",
            mapOf("grant_type" to "client_credentials", "client_id" to keys.tcgplayerClientId, "client_secret" to keys.tcgplayerClientSecret)
        ) { kotlinx.serialization.json.Json.parseToJsonElement(it.string()) }.obj()?.s("access_token")
            ?: error("TCGplayer rejected the credentials")
        val auth = mapOf("Authorization" to "bearer $token")

        val categoryId = resolveCategory(wanted, auth) ?: error("TCGplayer category '$wanted' not found")
        val groups = pageAll("$api/catalog/categories/$categoryId/groups", auth)
        val batched = BatchedSink(sink)

        groups.forEachIndexed { gi, group ->
            val groupId = group["groupId"].int() ?: return@forEachIndexed
            val groupName = group.s("name") ?: "Group $groupId"
            progress(SyncProgress("TCGplayer · $groupName", (gi + 1f) / groups.size))
            val prices = loadGroupPrices(groupId, auth)
            val release = group.s("publishedOn")?.take(10)
            val code = group.s("abbreviation")?.takeIf { it.isNotBlank() } ?: groupName
            val products = pageAll("$api/catalog/products?categoryId=$categoryId&groupId=$groupId&productTypes=Cards&getExtendedFields=true", auth)
            products.forEach { p ->
                val productId = p["productId"].int() ?: return@forEach
                val ext = p["extendedData"].let { it as? kotlinx.serialization.json.JsonArray }
                    ?.mapNotNull { it.obj() }
                    ?.associate { (it.s("name") ?: "") to (it.s("value") ?: "") }
                    .orEmpty()
                val rawNumber = ext["Number"].orEmpty()
                if (rawNumber.isBlank()) return@forEach // sealed product, accessories...
                val number = rawNumber.substringBefore('/').trim()
                val total = rawNumber.substringAfter('/', "").trim().toIntOrNull()
                val type = (ext["Card Type"] ?: ext["CardType"]).orEmpty()
                batched.add(
                    RemoteCard(
                        sourceId = productId.toString(),
                        setCode = code,
                        setName = groupName,
                        setReleaseDate = release,
                        setTotal = total,
                        number = number,
                        name = p.s("name")?.substringBefore(" - ")?.trim() ?: return@forEach,
                        rarity = ext["Rarity"],
                        category = when {
                            type.contains("Monster", true) || type.contains("Character", true) || type.contains("Pok", true) || type.contains("Unit", true) -> CardCategory.CREATURE
                            type.contains("Trainer", true) || type.contains("Supporter", true) -> CardCategory.TRAINER
                            type.contains("Energy", true) || type.contains("Land", true) -> CardCategory.RESOURCE
                            type.contains("Spell", true) || type.contains("Trap", true) || type.contains("Event", true) || type.contains("Action", true) -> CardCategory.SPELL
                            else -> CardCategory.OTHER
                        },
                        imageUrl = p.s("imageUrl"),
                        prices = prices[productId].orEmpty()
                    )
                )
            }
        }
        batched.flush()
    }

    private suspend fun resolveCategory(name: String, auth: Map<String, String>): Int? {
        val key = Text.nameKey(name)
        val all = pageAll("$api/catalog/categories", auth)
        return all.firstOrNull { Text.nameKey(it.s("name").orEmpty()) == key }?.get("categoryId").int()
            ?: all.firstOrNull { Text.nameKey(it.s("name").orEmpty()).contains(key) }?.get("categoryId").int()
    }

    private suspend fun loadGroupPrices(groupId: Int, auth: Map<String, String>): Map<Int, Map<CardVariant, Double>> {
        val out = HashMap<Int, MutableMap<CardVariant, Double>>()
        try {
            val root = http.getWithRetry("$api/pricing/group/$groupId", auth) { kotlinx.serialization.json.Json.parseToJsonElement(it.string()) }.obj()
            root?.a("results")?.forEach { el ->
                val r = el.obj() ?: return@forEach
                val pid = r["productId"].int() ?: return@forEach
                val usd = r.d("marketPrice") ?: r.d("midPrice") ?: r.d("lowPrice") ?: return@forEach
                out.getOrPut(pid) { HashMap() }.putIfAbsent(variantOf(r.s("subTypeName").orEmpty()), usd)
            }
        } catch (e: java.io.IOException) {
            // Group without pricing: keep going, the catalog is still valuable.
        }
        return out
    }

    private fun variantOf(sub: String): CardVariant {
        val s = sub.lowercase()
        return when {
            s.contains("reverse") -> CardVariant.REVERSE_HOLO
            s.contains("1st") && s.contains("holo") -> CardVariant.FIRST_ED_HOLO
            s.contains("1st") -> CardVariant.FIRST_EDITION
            s.contains("holo") -> CardVariant.HOLO
            s.contains("foil") -> CardVariant.FOIL
            else -> CardVariant.NORMAL
        }
    }

    /** TCGplayer lists are paged with limit/offset (max 100 per call). */
    private suspend fun pageAll(baseUrl: String, auth: Map<String, String>): List<JsonObject> {
        val out = ArrayList<JsonObject>()
        var offset = 0
        val sep = if (baseUrl.contains('?')) "&" else "?"
        while (true) {
            val root = http.getWithRetry("$baseUrl${sep}limit=100&offset=$offset", auth) {
                kotlinx.serialization.json.Json.parseToJsonElement(it.string())
            }.obj() ?: break
            val results = root.a("results")?.mapNotNull { it.obj() }.orEmpty()
            out.addAll(results)
            val total = root["totalItems"].int() ?: results.size
            offset += 100
            if (results.isEmpty() || offset >= total) break
        }
        return out
    }
}

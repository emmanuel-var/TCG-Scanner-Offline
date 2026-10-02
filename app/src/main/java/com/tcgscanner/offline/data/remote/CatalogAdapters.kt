package com.tcgscanner.offline.data.remote

import com.tcgscanner.offline.core.CardCategory
import com.tcgscanner.offline.core.CardVariant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Shapes of catalog documents the app can read; detected from the first bytes of a download. */
enum class CatalogFormat {
    /** Scryfall's "bulk-data" descriptor: a small object whose `download_uri` points at the real file. */
    BULK_INDEX,
    POKEMON_TCG,
    SCRYFALL_CARDS,
    YGOPRODECK,
    LORCANA_API,
    DIGIMON_CARD_IO,
    /** Any other JSON: community dumps, Tabletop Simulator mod data, our own documented format. */
    GENERIC;

    companion object {
        /** [head] is the first few KB of the document. */
        fun sniff(head: String): CatalogFormat {
            val h = head.trimStart()
            return when {
                !h.startsWith("[") && h.contains("\"download_uri\"") -> BULK_INDEX
                h.contains("\"card_sets\"") -> YGOPRODECK
                h.contains("\"supertype\"") || h.contains("\"tcgplayer\"") -> POKEMON_TCG
                h.contains("\"collector_number\"") && h.contains("\"set_name\"") -> SCRYFALL_CARDS
                h.contains("\"Card_Num\"") || h.contains("\"Set_ID\"") -> LORCANA_API
                h.contains("\"digi_type\"") || h.contains("\"play_cost\"") || h.contains("\"evolution_cost\"") -> DIGIMON_CARD_IO
                else -> GENERIC
            }
        }
    }
}

internal fun mtgCategory(typeLine: String?): CardCategory {
    val t = typeLine.orEmpty()
    return when {
        t.contains("Creature") -> CardCategory.CREATURE
        t.contains("Land") -> CardCategory.RESOURCE
        t.contains("Instant") || t.contains("Sorcery") || t.contains("Enchantment") ||
            t.contains("Artifact") || t.contains("Planeswalker") -> CardCategory.SPELL
        else -> CardCategory.OTHER
    }
}

/** Pure JSON -> [RemoteCard] mappers, one per known API shape plus a tolerant generic one. */
object CatalogAdapters {

    // ---- Pokémon TCG API ---------------------------------------------------------------------------------

    fun pokemon(o: JsonObject): RemoteCard? {
        val id = o.s("id") ?: return null
        val set = o.o("set")
        val prices = HashMap<CardVariant, Double>()
        o.o("tcgplayer")?.o("prices")?.forEach { (key, value) ->
            val p = value.obj() ?: return@forEach
            val usd = p.d("market") ?: p.d("mid") ?: p.d("low") ?: return@forEach
            val variant = when (key) {
                "normal", "unlimited" -> CardVariant.NORMAL
                "holofoil", "unlimitedHolofoil" -> CardVariant.HOLO
                "reverseHolofoil" -> CardVariant.REVERSE_HOLO
                "1stEditionNormal" -> CardVariant.FIRST_EDITION
                "1stEditionHolofoil" -> CardVariant.FIRST_ED_HOLO
                else -> CardVariant.NORMAL
            }
            prices.putIfAbsent(variant, usd)
        }
        val category = when (o.s("supertype")) {
            "Trainer" -> CardCategory.TRAINER
            "Energy" -> CardCategory.RESOURCE
            "Pokémon", "Pokemon" -> CardCategory.CREATURE
            else -> CardCategory.OTHER
        }
        return RemoteCard(
            sourceId = id,
            setCode = set?.s("id") ?: id.substringBefore('-'),
            setName = set?.s("name") ?: "?",
            setReleaseDate = set?.s("releaseDate")?.replace('/', '-'),
            setTotal = set?.get("printedTotal").int() ?: set?.get("total").int(),
            number = o.s("number") ?: id.substringAfter('-'),
            name = o.s("name") ?: return null,
            rarity = o.s("rarity"),
            category = category,
            imageUrl = o.o("images")?.s("small"),
            prices = prices
        )
    }

    // ---- Scryfall ---------------------------------------------------------------------------------------

    private val scryfallSkipped = setOf("token", "double_faced_token", "art_series", "emblem", "vanguard", "scheme", "planar")

    fun scryfall(o: JsonObject): RemoteCard? {
        if (o["digital"].str() == "true") return null
        if (o.s("layout") in scryfallSkipped) return null
        val id = o.s("id") ?: return null
        val prices = HashMap<CardVariant, Double>()
        o.o("prices")?.let { p ->
            p.d("usd")?.let { prices[CardVariant.NORMAL] = it }
            p.d("usd_foil")?.let { prices[CardVariant.FOIL] = it }
            p.d("usd_etched")?.let { prices[CardVariant.ETCHED] = it }
        }
        val image = o.o("image_uris")?.s("small")
            ?: o.a("card_faces")?.firstOrNull()?.obj()?.o("image_uris")?.s("small")
        val setCode = (o.s("set") ?: return null).uppercase()
        return RemoteCard(
            sourceId = id,
            setCode = setCode,
            setName = o.s("set_name") ?: setCode,
            setReleaseDate = o.s("released_at"),
            number = o.s("collector_number") ?: return null,
            name = o.s("name") ?: return null,
            rarity = o.s("rarity"),
            category = mtgCategory(o.s("type_line")),
            imageUrl = image,
            prices = prices
        )
    }

    // ---- YGOPRODeck -------------------------------------------------------------------------------------

    /** One entry per card, one [RemoteCard] per printing (set code + rarity + price). */
    fun ygo(card: JsonObject): List<RemoteCard> {
        val cardId = card["id"].str() ?: return emptyList()
        val name = card.s("name") ?: return emptyList()
        val type = card.s("type").orEmpty()
        val category = when {
            type.contains("Monster") -> CardCategory.CREATURE
            type.contains("Spell") || type.contains("Trap") -> CardCategory.SPELL
            else -> CardCategory.OTHER
        }
        val image = card.a("card_images")?.firstOrNull()?.obj()?.let { it.s("image_url_small") ?: it.s("image_url") }
        return card.a("card_sets")?.mapNotNull { setEl ->
            val set = setEl.obj() ?: return@mapNotNull null
            val code = set.s("set_code") ?: return@mapNotNull null
            val rarity = set.s("set_rarity")
            val rarityCode = set.s("set_rarity_code") ?: rarity.orEmpty()
            val price = set.d("set_price")?.takeIf { it > 0.0 }
            RemoteCard(
                sourceId = "$cardId:$code:$rarityCode",
                setCode = code.substringBefore('-'),
                setName = set.s("set_name") ?: code.substringBefore('-'),
                number = code,
                name = name,
                rarity = rarity,
                category = category,
                imageUrl = image,
                prices = if (price != null) mapOf(CardVariant.NORMAL to price) else emptyMap()
            )
        }.orEmpty()
    }

    // ---- lorcana-api.com -------------------------------------------------------------------------------

    fun lorcanaApi(c: JsonObject): RemoteCard? {
        val setId = c.first("Set_ID", "set_id") ?: "LORCANA"
        val num = c.first("Card_Num", "Card_Number", "card_number") ?: c["Card_Num"].int()?.toString() ?: return null
        val type = c.first("Type").orEmpty()
        return RemoteCard(
            sourceId = "lcapi:$setId:$num:${c.first("Name")}",
            setCode = setId,
            setName = c.first("Set_Name", "set_name") ?: setId,
            number = num,
            name = c.first("Name") ?: return null,
            rarity = c.first("Rarity"),
            category = when {
                type.contains("Character", true) -> CardCategory.CREATURE
                type.contains("Action", true) -> CardCategory.SPELL
                else -> CardCategory.OTHER
            },
            imageUrl = c.first("Image")
        )
    }

    // ---- DigimonCard.io ---------------------------------------------------------------------------------

    private val digimonCode = Regex("^[A-Za-z0-9]+-\\d+$")

    fun digimon(c: JsonObject): RemoteCard? {
        val number = listOf("id", "cardnumber", "card_number").mapNotNull { c.s(it) }.firstOrNull { digimonCode.matches(it) } ?: return null
        val setName = c["set_name"].let { it.arr()?.firstOrNull().str() ?: it.str() } ?: number.substringBefore('-')
        val type = c.first("type").orEmpty()
        val price = c.d("market_price")
        return RemoteCard(
            sourceId = "digi:$number:${c.first("name")}:${c.first("image_url")?.hashCode() ?: 0}",
            setCode = number.substringBefore('-'),
            setName = setName,
            number = number,
            name = c.first("name") ?: return null,
            rarity = c.first("rarity"),
            category = when {
                type.contains("Digimon", true) || type.contains("Tamer", true) || type.contains("Egg", true) -> CardCategory.CREATURE
                type.contains("Option", true) -> CardCategory.SPELL
                else -> CardCategory.OTHER
            },
            imageUrl = c.first("image_url"),
            prices = if (price != null && price > 0) mapOf(CardVariant.NORMAL to price) else emptyMap()
        )
    }

    // ---- generic community dumps / Tabletop Simulator mods ------------------------------------------------

    // "nickname" first: Tabletop Simulator stores the real card name there and "Name" is just the object type ("Card").
    private val nameKeys = listOf("nickname", "name", "cardname", "title", "nameen", "englishname")
    private val numberKeys = listOf("cardnumber", "number", "collectornumber", "setnumber", "printedid", "localid", "cardid", "code", "id")
    private val setCodeKeys = listOf("setcode", "setid", "set", "series", "seriescode", "expansion", "expansioncode", "packid", "pack")
    private val setNameKeys = listOf("setname", "expansionname", "packname", "setfullname")
    private val rarityKeys = listOf("rarity", "rare")
    private val imageKeys = listOf("image", "imageurl", "img", "imgurl", "cardimage", "faceurl", "picture", "thumbnail")
    private val typeKeys = listOf("type", "cardtype", "category", "supertype", "kind")
    private val priceKeys = listOf("marketprice", "price", "usd", "priceusd", "market", "midprice")
    private val idKeys = listOf("id", "uuid", "guid", "cardid", "cardcode", "code")
    private const val MAX_DEPTH = 7
    private const val MAX_CARDS = 300_000

    private fun normKey(k: String) = k.lowercase().filter { it.isLetterOrDigit() }

    private fun JsonObject.lookup(keys: List<String>): JsonElement? {
        val m = HashMap<String, JsonElement>(size * 2)
        for ((k, v) in this) m.putIfAbsent(normKey(k), v)
        for (key in keys) m[key]?.let { v -> if (!(v is JsonPrimitive && v.content.isBlank())) return v }
        return null
    }

    private fun JsonElement?.text(): String? = (this as? JsonPrimitive)?.content?.trim()?.takeIf { it.isNotEmpty() && it != "null" }

    private fun looksLikeCard(o: JsonObject): Boolean {
        if (o.lookup(nameKeys).text() == null) return false
        return o.lookup(numberKeys) != null || o.lookup(imageKeys) != null
    }

    /** Finds every card-like object in an arbitrary JSON tree (arrays, id-keyed maps, nested sets/decks). */
    fun findCardObjects(root: JsonElement): List<JsonObject> {
        val out = ArrayList<JsonObject>()
        fun walk(el: JsonElement, depth: Int) {
            if (depth > MAX_DEPTH || out.size >= MAX_CARDS) return
            when (el) {
                is JsonArray -> el.forEach { walk(it, depth + 1) }
                is JsonObject -> if (looksLikeCard(el)) out.add(el) else el.values.forEach { walk(it, depth + 1) }
                else -> Unit
            }
        }
        walk(root, 0)
        return out
    }

    fun generic(o: JsonObject): RemoteCard? {
        // Our documented format has explicit fields; use it when present.
        if (o.containsKey("setCode") || o["prices"] is JsonObject) {
            com.tcgscanner.offline.data.remote.sources.CustomCatalogParser.toCard(o)?.let { return it }
        }
        val name = o.lookup(nameKeys).text() ?: return null
        val numberEl = o.lookup(numberKeys)
        val number = numberEl.text() ?: "0"

        var setCode: String? = null
        var setName: String? = null
        when (val setEl = o.lookup(setCodeKeys)) {
            is JsonObject -> {
                setCode = setEl.lookup(listOf("id", "code", "abbreviation")).text()
                setName = setEl.lookup(listOf("name", "title")).text()
            }
            else -> setCode = setEl.text()
        }
        setName = setName ?: o.lookup(setNameKeys).text()
        val code = setCode ?: number.substringBefore('-', "").takeIf { it.isNotBlank() } ?: "GEN"

        val image = when (val img = o.lookup(imageKeys)) {
            is JsonObject -> img.lookup(listOf("small", "thumbnail", "normal", "large", "png"))?.text()
            else -> img.text()
        } ?: o.lookup(listOf("images", "imageuris"))?.obj()?.let { it.lookup(listOf("small", "normal", "large", "png")).text() }

        val prices = HashMap<CardVariant, Double>()
        when (val p = o.lookup(listOf("prices"))) {
            is JsonObject -> p.forEach { (k, v) ->
                val usd = v.dbl() ?: return@forEach
                val nk = normKey(k)
                val variant = when {
                    nk.contains("reverse") -> CardVariant.REVERSE_HOLO
                    nk.contains("foil") && !nk.contains("holo") -> CardVariant.FOIL
                    nk.contains("holo") -> CardVariant.HOLO
                    nk.contains("parallel") -> CardVariant.PARALLEL
                    else -> CardVariant.NORMAL
                }
                prices.putIfAbsent(variant, usd)
            }
            else -> Unit
        }
        if (prices.isEmpty()) o.lookup(priceKeys)?.dbl()?.takeIf { it > 0 }?.let { prices[CardVariant.NORMAL] = it }

        val type = o.lookup(typeKeys).text().orEmpty().lowercase()
        val category = when {
            listOf("monster", "character", "creature", "digimon", "unit", "leader", "pokemon", "pokémon", "champion", "tamer", "pilot", "base").any { type.contains(it) } -> CardCategory.CREATURE
            listOf("trainer", "supporter", "stadium").any { type.contains(it) } -> CardCategory.TRAINER
            listOf("energy", "land", "resource", "don").any { type == it || type.contains(it) } -> CardCategory.RESOURCE
            listOf("spell", "event", "action", "trap", "instant", "sorcery", "command", "option", "song", "enchantment", "artifact", "item", "battle", "gear").any { type.contains(it) } -> CardCategory.SPELL
            else -> CardCategory.OTHER
        }

        val rawId = o.lookup(idKeys).text()
        val sourceId = (rawId ?: "$code:$number:$name") + if (rawId != null && rawId == number) ":${name.hashCode()}" else ""
        return RemoteCard(
            sourceId = sourceId,
            setCode = code.take(24),
            setName = (setName ?: code).take(80),
            setReleaseDate = o.lookup(listOf("releasedate", "released", "releasedat"))?.text()?.take(10),
            setTotal = o.lookup(listOf("settotal", "printedtotal"))?.int(),
            number = number.take(32),
            name = name.take(120),
            rarity = o.lookup(rarityKeys).text(),
            category = category,
            imageUrl = image?.takeIf { it.startsWith("https://") || it.startsWith("http://") }?.replaceFirst("http://", "https://"),
            prices = prices
        )
    }

    private fun JsonObject.first(vararg keys: String): String? = keys.firstNotNullOfOrNull { s(it)?.takeIf { v -> v.isNotBlank() } }
}

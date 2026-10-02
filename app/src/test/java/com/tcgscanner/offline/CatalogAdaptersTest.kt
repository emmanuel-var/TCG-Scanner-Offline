package com.tcgscanner.offline

import com.tcgscanner.offline.core.CardVariant
import com.tcgscanner.offline.data.remote.CatalogAdapters
import com.tcgscanner.offline.data.remote.CatalogFormat
import com.tcgscanner.offline.data.remote.CatalogUrls
import com.tcgscanner.offline.data.remote.UrlNormalizer
import com.tcgscanner.offline.core.GameId
import com.tcgscanner.offline.core.CardKeys
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogAdaptersTest {
    private fun cards(json: String) =
        CatalogAdapters.findCardObjects(Json.parseToJsonElement(json)).mapNotNull { CatalogAdapters.generic(it) }

    @Test fun defaultUrlsAreHttpsAndIndieGamesHaveNone() {
        GameId.entries.forEach { g -> CatalogUrls.default(g)?.let { assertTrue(g.name, it.startsWith("https://")) } }
        listOf(GameId.GUNDAM, GameId.RIFTBOUND, GameId.DBS_FW).forEach {
            assertNull(it.name, CatalogUrls.default(it)); assertTrue(CatalogUrls.requiresUserSource(it))
        }
        assertEquals("https://api.pokemontcg.io/v2/cards?q=language:japanese", CatalogUrls.default(GameId.POKEMON_JP))
        assertTrue(CatalogUrls.default(GameId.ONE_PIECE)!!.contains("vegapull-records"))
    }

    @Test fun mapsVegapullPacksAndCards() {
        val packs = Json.parseToJsonElement("""[{"id":"569101","raw_title":"ROMANCE DAWN [OP-01]","title_parts":{"prefix":"BOOSTER PACK","title":"ROMANCE DAWN","label":"OP-01"}}]""")
        val pack = CatalogAdapters.vegapullPacks(packs).single()
        assertEquals("569101", pack.id); assertEquals("ROMANCE DAWN", pack.title)
        val base = CatalogAdapters.vegapullCard(Json.parseToJsonElement("""{"id":"OP01-001","pack_id":"569101","name":"Roronoa Zoro","rarity":"L","category":"Leader","img_url":"https://i/z.png"}""").jsonObject, pack)!!
        val alt = CatalogAdapters.vegapullCard(Json.parseToJsonElement("""{"id":"OP01-001_p1","pack_id":"569101","name":"Roronoa Zoro","rarity":"L","category":"Leader","img_url":"https://i/z2.png"}""").jsonObject, pack)!!
        assertEquals("OP01", base.setCode); assertEquals("OP01-001", base.number); assertEquals("ROMANCE DAWN", base.setName)
        assertEquals("", base.printTag); assertEquals("p1", alt.printTag); assertEquals("OP01-001", alt.number)
        assertNotEquals(
            CardKeys.of(GameId.ONE_PIECE, base.setCode, base.number, base.printTag),
            CardKeys.of(GameId.ONE_PIECE, alt.setCode, alt.number, alt.printTag)
        )
    }

    @Test fun sniffsVegapullAndCgs() {
        assertEquals(CatalogFormat.VEGAPULL_PACKS, CatalogFormat.sniff("""[{"id":"1","raw_title":"X","title_parts":{"title":"X"}}]"""))
        assertEquals(CatalogFormat.VEGAPULL_CARDS, CatalogFormat.sniff("""[{"id":"OP01-001","pack_id":"1","img_url":"https://x"}]"""))
        assertEquals(CatalogFormat.CGS_GAME, CatalogFormat.sniff("""{"name":"Fusion World","allCardsUrl":"AllCards.json","allSetsUrl":"AllSets.json"}"""))
    }

    @Test fun readsACardGameSimulatorGame() {
        val d = CatalogAdapters.cgsDescriptor(
            Json.parseToJsonElement("""{"name":"FW","allCardsUrl":"AllCards.json","allCardsUrlWrapper":"cards","allSetsUrl":"AllSets.json","cardIdIdentifier":"cardId","cardNameIdentifier":"name","cardSetIdentifier":"set","cardImageIdentifier":"imageUrl"}""").jsonObject
        )!!
        assertEquals("AllCards.json", d.allCardsUrl); assertEquals("cards", d.allCardsWrapper)
        val names = CatalogAdapters.cgsSetNames(Json.parseToJsonElement("""[{"code":"FB01","name":"Awakened Pulse"}]"""), d)
        assertEquals("Awakened Pulse", names["FB01"])
        val c = CatalogAdapters.cgsCard(
            Json.parseToJsonElement("""{"cardId":"FB01-001","name":"Son Goku","set":"FB01","imageUrl":"http://i/g.png","rarity":"L","cardType":"Leader"}""").jsonObject, d, names
        )!!
        assertEquals("FB01", c.setCode); assertEquals("FB01-001", c.number); assertEquals("Awakened Pulse", c.setName)
        assertEquals("https://i/g.png", c.imageUrl)
    }

    @Test fun ygoRaritiesBecomePrintTags() {
        val card = Json.parseToJsonElement("""{"id":1,"name":"A","type":"Normal Monster","card_sets":[{"set_name":"S","set_code":"LOB-EN005","set_rarity":"Ultra Rare","set_rarity_code":"(UR)"},{"set_name":"S","set_code":"LOB-EN005","set_rarity":"Super Rare","set_rarity_code":"(SR)"}]}""").jsonObject
        val keys = CatalogAdapters.ygo(card).map { CardKeys.of(GameId.YGO, it.setCode, it.number, it.printTag) }
        assertEquals(2, keys.toSet().size)
    }

    @Test fun githubBlobLinksBecomeRawLinks() {
        assertEquals(
            "https://raw.githubusercontent.com/me/repo/main/data/cards.json",
            UrlNormalizer.normalize("  https://github.com/me/repo/blob/main/data/cards.json ")
        )
        assertEquals("https://example.com/a.json", UrlNormalizer.normalize("https://example.com/a.json"))
    }

    @Test fun onlyHttpsLinksAreAccepted() {
        assertTrue(UrlNormalizer.isValid("https://raw.githubusercontent.com/a/b/main/c.json"))
        assertFalse(UrlNormalizer.isValid("http://example.com/c.json"))
        assertFalse(UrlNormalizer.isValid("https://"))
        assertFalse(UrlNormalizer.isValid("not a url"))
    }

    @Test fun sniffsKnownFormats() {
        assertEquals(CatalogFormat.BULK_INDEX, CatalogFormat.sniff("""{"object":"bulk_data","type":"default_cards","download_uri":"https://x"}"""))
        assertEquals(CatalogFormat.YGOPRODECK, CatalogFormat.sniff("""{"data":[{"id":1,"name":"A","card_sets":[]}]}"""))
        assertEquals(CatalogFormat.POKEMON_TCG, CatalogFormat.sniff("""{"data":[{"id":"a-1","supertype":"Trainer"}]}"""))
        assertEquals(CatalogFormat.SCRYFALL_CARDS, CatalogFormat.sniff("""[{"id":"x","collector_number":"1","set_name":"S"}]"""))
        assertEquals(CatalogFormat.LORCANA_API, CatalogFormat.sniff("""[{"Name":"Elsa","Card_Num":42}]"""))
        assertEquals(CatalogFormat.DIGIMON_CARD_IO, CatalogFormat.sniff("""[{"name":"Agumon","play_cost":3}]"""))
        assertEquals(CatalogFormat.GENERIC, CatalogFormat.sniff("""{"cards":[{"name":"X"}]}"""))
    }

    @Test fun genericReadsAPlainCommunityArray() {
        val out = cards("""[{"id":"OP01-001","name":"Roronoa Zoro","set":"OP01","set_name":"Romance Dawn","rarity":"L","image":"http://i/z.png","price":2.5}]""")
        assertEquals(1, out.size)
        val c = out[0]
        assertEquals("OP01", c.setCode); assertEquals("OP01-001", c.number); assertEquals("Romance Dawn", c.setName)
        assertEquals(2.5, c.prices.getValue(CardVariant.NORMAL), 1e-9)
        assertEquals("https://i/z.png", c.imageUrl) // upgraded to https
    }

    @Test fun genericReadsAnIdKeyedMapAndDerivesSetFromNumber() {
        val out = cards("""{"GD01-001":{"name":"Gundam","card_number":"GD01-001","type":"Unit"},"GD01-002":{"name":"Zaku","card_number":"GD01-002","type":"Command"}}""")
        assertEquals(2, out.size)
        assertEquals("GD01", out[0].setCode)
        assertEquals(com.tcgscanner.offline.core.CardCategory.CREATURE, out[0].category)
        assertEquals(com.tcgscanner.offline.core.CardCategory.SPELL, out[1].category)
    }

    @Test fun genericWalksTabletopSimulatorStyleNesting() {
        val tts = """{"ObjectStates":[{"Name":"DeckCustom","Nickname":"","ContainedObjects":[
            {"Name":"Card","Nickname":"Annie, Fiery","CardID":100,"CustomDeck":{"1":{"FaceURL":"https://i/a.png"}}},
            {"Name":"Card","Nickname":"Jinx","CardID":101}]}]}"""
        val out = cards(tts)
        assertEquals(listOf("Annie, Fiery", "Jinx"), out.map { it.name })
        assertEquals("100", out[0].number)
    }

    @Test fun genericPrefersTheDocumentedFormat() {
        val out = cards("""{"cards":[{"id":"x1","setCode":"GD01","setName":"Newtype Rising","number":"GD01-001","name":"Gundam",
            "prices":{"normal":1.5,"foil":4},"graded":[{"company":"PSA","grade":10,"usd":120.0}]}]}""")
        assertEquals(100, out[0].graded.first().gradeX10)
        assertEquals(4.0, out[0].prices.getValue(CardVariant.FOIL), 1e-9)
    }

    @Test fun ignoresObjectsThatAreNotCards() {
        assertTrue(cards("""{"meta":{"version":3,"generated":"today"},"cards":[]}""").isEmpty())
    }

    @Test fun mapsYgoPrintings() {
        val card = Json.parseToJsonElement(
            """{"id":46986414,"name":"Dark Magician","type":"Normal Monster","card_sets":[
              {"set_name":"LOB","set_code":"LOB-EN005","set_rarity":"Ultra Rare","set_rarity_code":"(UR)","set_price":"25.30"},
              {"set_name":"SDY","set_code":"SDY-006","set_rarity":"Common","set_price":"0.00"}]}"""
        ).jsonObject
        val out = CatalogAdapters.ygo(card)
        assertEquals(2, out.size)
        assertEquals(25.3, out[0].prices.getValue(CardVariant.NORMAL), 1e-9)
        assertTrue(out[1].prices.isEmpty())
        assertNotNull(out[0].sourceId)
    }
}

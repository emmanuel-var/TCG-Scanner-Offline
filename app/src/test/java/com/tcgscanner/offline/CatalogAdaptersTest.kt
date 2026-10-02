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

    @Test fun defaultUrlsAreHttpsAndEveryGameHasOne() {
        GameId.entries.forEach { g ->
            assertTrue(g.name, CatalogUrls.default(g)!!.startsWith("https://"))
            CatalogUrls.backup(g)?.let { assertTrue(it.startsWith("https://")) }
        }
        assertEquals("https://api.pokemontcg.io/v2/cards?q=language:japanese", CatalogUrls.default(GameId.POKEMON_JP))
        assertTrue(CatalogUrls.default(GameId.ONE_PIECE)!!.contains("vegapull-records"))
        assertEquals("https://limitlesstcg.s3.us-east-2.amazonaws.com/dbs/fw/db/cards.json", CatalogUrls.default(GameId.DBS_FW))
        assertEquals("https://raw.githubusercontent.com/TheSench/CGS-DBS-Fusion-World/main/cards.json", CatalogUrls.backup(GameId.DBS_FW))
    }

    @Test fun removedGamesAreGone() {
        assertTrue(GameId.entries.none { it.code == "gundam" || it.code == "riftbound" })
        assertNull(GameId.fromCode("gundam")); assertNull(GameId.fromCode("riftbound"))
    }

    @Test fun scryfallCardsAreNotMistakenForPokemon() {
        // Real Scryfall cards carry purchase_uris.tcgplayer as a STRING; Pokemon's "tcgplayer" is an object.
        val scryfallCard = """[{"object":"card","id":"abc","name":"Lightning Bolt","scryfall_uri":"https://scryfall.com/card/lea/161","set":"lea","set_name":"Limited Edition Alpha","collector_number":"161","prices":{"usd":"350.00"},"purchase_uris":{"tcgplayer":"https://tcgplayer.com/x","cardmarket":"https://cm.com/x"}}]"""
        assertEquals(CatalogFormat.SCRYFALL_CARDS, CatalogFormat.sniff(scryfallCard))
        assertEquals(CatalogFormat.POKEMON_TCG, CatalogFormat.sniff("""{"data":[{"id":"a-1","name":"X","tcgplayer":{"url":"https://x","prices":{}}}]}"""))
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

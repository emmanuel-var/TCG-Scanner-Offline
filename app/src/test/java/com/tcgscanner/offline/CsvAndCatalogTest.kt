package com.tcgscanner.offline

import com.tcgscanner.offline.core.CardVariant
import com.tcgscanner.offline.data.remote.sources.Csv
import com.tcgscanner.offline.data.remote.sources.CustomCatalogParser
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class CsvAndCatalogTest {
    @Test fun parsesQuotedCsvLine() {
        assertEquals(listOf("1", "Pokemon Japanese Base Set", "Charizard, Holo", "say \"hi\""), Csv.parseLine("1,Pokemon Japanese Base Set,\"Charizard, Holo\",\"say \"\"hi\"\"\""))
    }

    @Test fun parsesCustomCatalogCard() {
        val json = """{"id":"x1","setCode":"GD01","setName":"Newtype Rising","number":"GD01-001","name":"Gundam","rarity":"R",
            "prices":{"normal":1.5,"foil":4},"graded":[{"company":"PSA","grade":10,"usd":120.0}]}"""
        val card = CustomCatalogParser.toCard(Json.parseToJsonElement(json).jsonObject)
        assertNotNull(card)
        assertEquals(1.5, card!!.prices.getValue(CardVariant.NORMAL), 1e-9)
        assertEquals(100, card.graded.first().gradeX10)
        assertEquals("GD01-001", card.number)
    }
}

package com.tcgscanner.offline

import com.tcgscanner.offline.core.GameId
import com.tcgscanner.offline.core.Games
import com.tcgscanner.offline.core.Text
import com.tcgscanner.offline.scanner.CardTextParser
import com.tcgscanner.offline.scanner.OcrLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextAndParserTest {
    @Test fun nameKeyStripsAccentsAndPunctuation() {
        assertEquals("pokemonex", Text.nameKey("Pokémon-ex"))
        assertEquals("blueeyeswhitedragon", Text.nameKey("Blue-Eyes White Dragon"))
    }

    @Test fun numberKeyDropsLeadingZeros() {
        assertEquals("25", Text.numberKey("025"))
        assertEquals("op1-120", Text.numberKey("OP01-120"))
        assertEquals("lob-en1", Text.numberKey("LOB-EN001"))
        assertEquals("100", Text.numberKey("100"))
    }

    @Test fun similarityToleratesOcrNoise() {
        assertTrue(Text.similarity("charizardex", "charizardex") == 1.0)
        assertTrue(Text.similarity("charizardex", "charizardox") > 0.85)
        assertTrue(Text.similarity("pikachu", "bulbasaur") < 0.4)
        // OCR line containing extra words around the name
        assertTrue(Text.similarity("pikachuex", "pikachuex") >= 0.99)
    }

    @Test fun parsesOnePieceCode() {
        val lines = listOf(OcrLine("Roronoa Zoro", 0.1f, 0.05f), OcrLine("OP01 - 025", 0.9f, 0.03f), OcrLine("COST 3", 0.2f, 0.02f))
        val parsed = CardTextParser.parse(lines, Games[GameId.ONE_PIECE])
        assertEquals("op1-25", parsed.numbers.first().key)
        assertEquals("Roronoa Zoro", parsed.names.first())
    }

    @Test fun parsesPokemonFraction() {
        val lines = listOf(OcrLine("Charizard ex  HP 330", 0.08f, 0.05f), OcrLine("125/197", 0.95f, 0.02f))
        val parsed = CardTextParser.parse(lines, Games[GameId.POKEMON])
        assertEquals("125", parsed.numbers.first().key)
        assertEquals(197, parsed.numbers.first().total)
        assertEquals("Charizard ex", parsed.names.first())
    }

    @Test fun parsesYugiohSetCode() {
        val lines = listOf(OcrLine("Dark Magician", 0.07f, 0.05f), OcrLine("LOB-EN005", 0.62f, 0.02f))
        val parsed = CardTextParser.parse(lines, Games[GameId.YGO])
        assertEquals("lob-en5", parsed.numbers.first().key)
    }

    @Test fun ignoresNoiseLines() {
        val parsed = CardTextParser.parse(listOf(OcrLine("12", 0.1f, 0.02f), OcrLine("Basic", 0.1f, 0.02f)), Games[GameId.MTG])
        assertTrue(parsed.names.isEmpty())
    }
}

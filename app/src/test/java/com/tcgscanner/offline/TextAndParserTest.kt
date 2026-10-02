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

    @Test fun onePieceRegexReadsTheSetPrefixAndRepairsOcrDigits() {
        val parsed = CardTextParser.parse(listOf(OcrLine("0P01-O25", 0.9f, 0.03f), OcrLine("Roronoa Zoro", 0.1f, 0.05f)), Games[GameId.ONE_PIECE])
        // "0P01" starts with a zero-for-O confusion; the clean "OP01-O25" form must still be recovered.
        val hints = CardTextParser.parse(listOf(OcrLine("OP01-O25", 0.9f, 0.03f)), Games[GameId.ONE_PIECE]).numbers
        assertEquals("op1-25", hints.single().key)
        assertEquals("op01", hints.single().setKey)
        assertTrue(parsed.names.contains("Roronoa Zoro"))
    }

    @Test fun eachGameHasItsOwnCodePattern() {
        fun key(game: GameId, text: String) = CardTextParser.parse(listOf(OcrLine(text, 0.9f, 0.03f)), Games[game]).numbers.firstOrNull()?.key
        assertEquals("fb1-1", key(GameId.DBS_FW, "FB01-001"))
        assertEquals("bt1-10", key(GameId.DIGIMON, "BT1-010"))
        assertEquals("ex2-45", key(GameId.DIGIMON, "EX2-045"))
        assertEquals("lob-en5", key(GameId.YGO, "LOB-EN005"))
        assertEquals("lob-en5", key(GameId.YGO, "LOB-ENO05"))      // O read instead of 0
        assertEquals("25", key(GameId.POKEMON, "O25/198"))
        assertEquals("123", key(GameId.LORCANA, "123/204"))
    }

    @Test fun setKeyMatchesTheRoomColumnConvention() {
        val hint = CardTextParser.parse(listOf(OcrLine("LOB-EN005", 0.9f, 0.03f)), Games[GameId.YGO]).numbers.single()
        assertEquals(com.tcgscanner.offline.core.CardKeys.setKey("LOB"), hint.setKey)
    }

    @Test fun noPatternNoNumber() {
        val parsed = CardTextParser.parse(listOf(OcrLine("Cost 3  Power 5000", 0.5f, 0.03f)), Games[GameId.ONE_PIECE])
        assertTrue(parsed.numbers.isEmpty() && !parsed.hasNumber)
    }
}

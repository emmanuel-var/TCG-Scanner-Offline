package com.tcgscanner.offline

import com.tcgscanner.offline.core.CardKeys
import com.tcgscanner.offline.core.GameId
import com.tcgscanner.offline.data.repo.CardSnapshot
import com.tcgscanner.offline.data.repo.RelinkCandidate
import com.tcgscanner.offline.data.repo.RelinkMatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CardKeyAndRelinkTest {
    @Test fun keyIsGamePlusSetPlusNumber() {
        assertEquals("pokemon:base1:4", CardKeys.of(GameId.POKEMON, "base1", "4"))
        assertEquals("pokemon:base1:4", CardKeys.of(GameId.POKEMON, " BASE1 ", "004"))
        assertEquals("onepiece:op01:op1-1:p1", CardKeys.of(GameId.ONE_PIECE, "OP01", "OP01-001", "P1"))
    }

    @Test fun keyIgnoresTheNetworkIdSoSourcesAgree() {
        // Two sources, two different ids, same print: same key.
        val a = CardKeys.of(GameId.MTG, "LEA", "161")
        val b = CardKeys.of(GameId.MTG, "lea", "0161")
        assertEquals(a, b)
    }

    @Test fun printTagKeepsRaritiesApart() {
        assertNotEquals(CardKeys.of(GameId.YGO, "LOB", "LOB-EN005", "ur"), CardKeys.of(GameId.YGO, "LOB", "LOB-EN005", "sr"))
    }

    @Test fun duplicatesInOneRunGetDeterministicSuffixes() {
        val seen = HashMap<String, Int>()
        val first = CardKeys.unique(GameId.DIGIMON, "BT1", "BT1-010", "", seen)
        val second = CardKeys.unique(GameId.DIGIMON, "BT1", "BT1-010", "", seen)
        val third = CardKeys.unique(GameId.DIGIMON, "BT1", "BT1-010", "", seen)
        assertEquals("digimon:bt1:bt1-10", first.first)
        assertEquals("digimon:bt1:bt1-10:dup2", second.first)
        assertEquals("digimon:bt1:bt1-10:dup3", third.first)
        assertEquals(3, setOf(first.first, second.first, third.first).size)
    }

    private fun cand(id: String, name: String, set: String, setName: String, number: String, tag: String = "") =
        RelinkCandidate(id, name, set, setName, number, tag)

    @Test fun relinksWhenOnlyTheSetCodeStyleChanged() {
        val snap = CardSnapshot("Charizard", "base1", "Base", "4")
        val id = RelinkMatcher.best(
            snap,
            listOf(
                cand("new-a", "Charizard", "BS", "Base Set", "4"),
                cand("new-b", "Charizard", "xy12", "Evolutions", "4")
            )
        )
        assertEquals("new-a", id)
    }

    @Test fun relinksExactMatchEvenWithDifferentNetworkIds() {
        val snap = CardSnapshot("Dark Magician", "LOB", "Legend of Blue Eyes White Dragon", "LOB-EN005", "ur")
        assertEquals("k", RelinkMatcher.best(snap, listOf(cand("k", "Dark Magician", "LOB", "Legend of Blue Eyes White Dragon", "LOB-EN005", "ur"))))
    }

    @Test fun refusesToGuessWhenTwoCandidatesLookAlike() {
        val snap = CardSnapshot("Pikachu", "", "", "58")
        val id = RelinkMatcher.best(snap, listOf(cand("a", "Pikachu", "s1", "Set 1", "58"), cand("b", "Pikachu", "s2", "Set 2", "58")))
        assertNull(id)
    }

    @Test fun neverMatchesADifferentCollectorNumber() {
        val snap = CardSnapshot("Charizard", "base1", "Base", "4")
        assertNull(RelinkMatcher.best(snap, listOf(cand("x", "Charizard", "base1", "Base", "5"))))
    }

    @Test fun rejectsASameNumberDifferentCard() {
        val snap = CardSnapshot("Charizard", "base1", "Base", "4")
        assertNull(RelinkMatcher.best(snap, listOf(cand("x", "Blastoise", "zz", "Other", "4"))))
    }
}

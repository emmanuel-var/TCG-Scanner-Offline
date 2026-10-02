package com.tcgscanner.offline

import com.tcgscanner.offline.core.CardVariant
import com.tcgscanner.offline.core.GradingCompany
import com.tcgscanner.offline.data.db.CardPriceEntity
import com.tcgscanner.offline.data.db.CollectionItemEntity
import com.tcgscanner.offline.data.db.GradedPriceEntity
import com.tcgscanner.offline.data.repo.CsvFormat
import com.tcgscanner.offline.data.repo.Valuation
import com.tcgscanner.offline.trade.TradeBalance
import com.tcgscanner.offline.trade.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ValuationTest {
    private fun item(variant: String = "normal", condition: String = "NM", company: String = "none", grade: Int = 0, manual: Double? = null) =
        CollectionItemEntity(
            gameId = "pokemon", cardId = "c", variant = variant, condition = condition, gradeCompany = company, gradeX10 = grade,
            quantity = 1, tradeQuantity = 0, manualPriceUsd = manual, notes = null, addedAt = 0, updatedAt = 0
        )

    private val prices = listOf(CardPriceEntity("c", "normal", 2.0), CardPriceEntity("c", "holo", 10.0))

    @Test fun rawUsesVariantPrice() {
        val v = Valuation.unitValue(item("holo"), prices, emptyList())
        assertEquals(10.0, v.usd, 1e-9); assertFalse(v.estimated)
    }

    @Test fun missingVariantFallsBackToNormalAndIsMarkedEstimated() {
        val v = Valuation.unitValue(item("reverse_holo"), prices, emptyList())
        assertEquals(2.0, v.usd, 1e-9); assertTrue(v.estimated)
    }

    @Test fun conditionScalesRawPrice() {
        val v = Valuation.unitValue(item("holo", condition = "LP"), prices, emptyList())
        assertEquals(8.5, v.usd, 1e-9)
    }

    @Test fun gradedPrefersExactThenGenericThenEstimate() {
        val graded = listOf(GradedPriceEntity("c", "PSA", 100, 300.0), GradedPriceEntity("c", "ANY", 90, 40.0))
        assertEquals(300.0, Valuation.unitValue(item("holo", company = "PSA", grade = 100), prices, graded).usd, 1e-9)
        assertEquals(40.0, Valuation.unitValue(item("holo", company = "BGS", grade = 90), prices, graded).usd, 1e-9)
        val est = Valuation.unitValue(item("holo", company = "CGC", grade = 100), prices, emptyList())
        assertTrue(est.estimated); assertTrue(est.usd > 10.0)
    }

    @Test fun manualPriceWins() {
        val v = Valuation.unitValue(item(company = "PSA", grade = 100, manual = 5.5), prices, emptyList())
        assertEquals(5.5, v.usd, 1e-9); assertFalse(v.estimated)
    }

    @Test fun noPricesMeansNoPrice() {
        assertFalse(Valuation.unitValue(item(), emptyList(), emptyList()).hasPrice)
    }

    @Test fun gradeListsAreSane() {
        assertEquals(10, GradingCompany.PSA.grades.size)
        assertTrue(95 in GradingCompany.BGS.grades)
        assertTrue(CardVariant.fromCode("nope") == CardVariant.NORMAL)
    }

    @Test fun csvEscapesAndBlocksFormulaInjection() {
        assertEquals("plain", CsvFormat.escape("plain"))
        assertEquals("\"a,b\"", CsvFormat.escape("a,b"))
        assertEquals("\"say \"\"hi\"\"\"", CsvFormat.escape("say \"hi\""))
        assertEquals("'=SUM(A1)", CsvFormat.escape("=SUM(A1)"))
        assertEquals("-5", CsvFormat.escape("-5"))
    }

    @Test fun tradeVerdicts() {
        assertEquals(Verdict.EMPTY, TradeBalance(0.0, 0.0).verdict)
        assertEquals(Verdict.FAIR, TradeBalance(40.0, 38.0).verdict)
        assertEquals(Verdict.YOU_GIVE_MORE, TradeBalance(100.0, 60.0).verdict)
        assertEquals(Verdict.YOU_GET_MORE, TradeBalance(10.0, 60.0).verdict)
    }
}

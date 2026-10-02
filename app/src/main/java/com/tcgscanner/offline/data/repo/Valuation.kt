package com.tcgscanner.offline.data.repo

import com.tcgscanner.offline.core.CardCondition
import com.tcgscanner.offline.core.CardVariant
import com.tcgscanner.offline.core.GradingCompany
import com.tcgscanner.offline.data.db.CardPriceEntity
import com.tcgscanner.offline.data.db.CollectionItemEntity
import com.tcgscanner.offline.data.db.GradedPriceEntity

/**
 * @param estimated true when the number is derived (variant fallback or grade multiplier) instead of
 * being a published market price; the UI marks these with "≈".
 */
data class UnitValue(val usd: Double, val estimated: Boolean, val hasPrice: Boolean)

/** Raw vs graded valuation. Raw and graded money are always reported separately in the portfolio. */
object Valuation {

    fun rawPrice(variant: CardVariant, prices: List<CardPriceEntity>): UnitValue {
        if (prices.isEmpty()) return UnitValue(0.0, estimated = false, hasPrice = false)
        prices.firstOrNull { it.variant == variant.code }?.let { return UnitValue(it.usd, false, true) }
        val fallback = prices.firstOrNull { it.variant == CardVariant.NORMAL.code } ?: prices.minByOrNull { it.usd }!!
        return UnitValue(fallback.usd, estimated = true, hasPrice = true)
    }

    fun unitValue(
        item: CollectionItemEntity,
        prices: List<CardPriceEntity>,
        graded: List<GradedPriceEntity>
    ): UnitValue {
        item.manualPriceUsd?.let { return UnitValue(it, estimated = false, hasPrice = true) }
        val company = GradingCompany.fromCode(item.gradeCompany)
        val raw = rawPrice(CardVariant.fromCode(item.variant), prices)
        if (!company.isGraded) {
            val factor = CardCondition.fromCode(item.condition).multiplier
            return raw.copy(usd = raw.usd * factor)
        }
        val exact = graded.firstOrNull { it.company == company.code && it.gradeX10 == item.gradeX10 }
            ?: graded.firstOrNull { it.company == "ANY" && it.gradeX10 == item.gradeX10 }
        if (exact != null) return UnitValue(exact.usd, false, true)
        if (!raw.hasPrice) return UnitValue(0.0, estimated = false, hasPrice = false)
        return UnitValue(raw.usd * gradeMultiplier(company, item.gradeX10), estimated = true, hasPrice = true)
    }

    /** Rough multiple of the raw near-mint price, used only when no published graded price exists. */
    fun gradeMultiplier(company: GradingCompany, gradeX10: Int): Double {
        val base = when {
            gradeX10 >= 100 -> 3.0
            gradeX10 >= 95 -> 2.0
            gradeX10 >= 90 -> 1.5
            gradeX10 >= 85 -> 1.2
            gradeX10 >= 80 -> 1.05
            gradeX10 >= 70 -> 0.9
            gradeX10 >= 50 -> 0.7
            else -> 0.45
        }
        val factor = when (company) {
            GradingCompany.PSA -> 1.0
            GradingCompany.BGS -> if (gradeX10 >= 100) 1.25 else 1.0
            GradingCompany.CGC -> 0.9
            GradingCompany.SGC -> 0.85
            GradingCompany.NONE -> 1.0
        }
        return base * factor
    }
}

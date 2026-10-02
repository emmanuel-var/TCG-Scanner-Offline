package com.tcgscanner.offline.core

import androidx.annotation.StringRes
import com.tcgscanner.offline.R

/** A printing finish / edition of a card. Prices are tracked per variant. */
enum class CardVariant(val code: String, @StringRes val labelRes: Int) {
    NORMAL("normal", R.string.variant_normal),
    HOLO("holo", R.string.variant_holo),
    REVERSE_HOLO("reverse_holo", R.string.variant_reverse_holo),
    FIRST_EDITION("1st_edition", R.string.variant_first_edition),
    FIRST_ED_HOLO("1st_ed_holo", R.string.variant_first_ed_holo),
    FOIL("foil", R.string.variant_foil),
    ETCHED("etched", R.string.variant_etched),
    PARALLEL("parallel", R.string.variant_parallel),
    PROMO("promo", R.string.variant_promo),
    LIMITED("limited", R.string.variant_limited);

    companion object {
        fun fromCode(code: String): CardVariant = entries.firstOrNull { it.code == code } ?: NORMAL
    }
}

enum class GradingCompany(val code: String, val label: String) {
    NONE("none", "Raw"),
    PSA("PSA", "PSA"),
    BGS("BGS", "BGS"),
    CGC("CGC", "CGC"),
    SGC("SGC", "SGC");

    val isGraded: Boolean get() = this != NONE

    /** Selectable grades, expressed x10 (95 = 9.5). */
    val grades: List<Int>
        get() = when (this) {
            NONE -> emptyList()
            PSA -> (1..10).map { it * 10 }
            else -> (2..20).map { it * 5 }
        }

    companion object {
        fun fromCode(code: String): GradingCompany = entries.firstOrNull { it.code == code } ?: NONE
    }
}

/** Raw-card condition. The multiplier scales the near-mint market price. */
enum class CardCondition(val code: String, val multiplier: Double, @StringRes val labelRes: Int) {
    NM("NM", 1.0, R.string.cond_nm),
    LP("LP", 0.85, R.string.cond_lp),
    MP("MP", 0.65, R.string.cond_mp),
    HP("HP", 0.45, R.string.cond_hp),
    DMG("DMG", 0.25, R.string.cond_dmg);

    companion object {
        fun fromCode(code: String): CardCondition = entries.firstOrNull { it.code == code } ?: NM
    }
}

/** Game-agnostic card grouping used by the deck builder. */
enum class CardCategory(val code: String, @StringRes val labelRes: Int) {
    CREATURE("creature", R.string.cat_creature),
    SPELL("spell", R.string.cat_spell),
    TRAINER("trainer", R.string.cat_trainer),
    RESOURCE("resource", R.string.cat_resource),
    OTHER("other", R.string.cat_other);

    companion object {
        fun fromCode(code: String): CardCategory = entries.firstOrNull { it.code == code } ?: OTHER
    }
}

enum class DeckZone(val code: String, @StringRes val labelRes: Int) {
    MAIN("main", R.string.zone_main),
    SIDE("side", R.string.zone_side);

    companion object {
        fun fromCode(code: String): DeckZone = entries.firstOrNull { it.code == code } ?: MAIN
    }
}

enum class SourceId(val label: String) {
    /** The per-game catalog URL (community default or the user's override); format is auto-detected. */
    CATALOG_URL("Catalog URL"),
    MTGJSON("MTGJSON"),
    OPTCG("OPTCG API"),
    LORCAST("Lorcast"),
    TCGDEX("TCGdex")
}

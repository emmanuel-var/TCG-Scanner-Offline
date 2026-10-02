package com.tcgscanner.offline.core

import androidx.annotation.StringRes
import com.tcgscanner.offline.R

enum class OcrScript { LATIN, JAPANESE }

/** How printed card numbers look, so the OCR parser knows what to look for. */
enum class NumberStyle {
    /** "025/198" */ FRACTION,
    /** "OP01-120", "BT1-001", "LOB-EN001" */ CODE,
    /** MTG-style collector number, name is the main signal */ PLAIN
}

enum class GameId(val code: String) {
    POKEMON("pokemon"),
    ONE_PIECE("onepiece"),
    MTG("mtg"),
    YGO("ygo"),
    LORCANA("lorcana"),
    RIFTBOUND("riftbound"),
    POKEMON_JP("pokemon_jp"),
    GUNDAM("gundam"),
    DBS_FW("dbs_fw"),
    DIGIMON("digimon");

    companion object {
        fun fromCode(code: String?): GameId? = entries.firstOrNull { it.code == code }
    }
}

data class GameDef(
    val id: GameId,
    @StringRes val nameRes: Int,
    @StringRes val collectionNameRes: Int,
    /** ARGB accent colour used for the generated emblem (no trademarked logos are bundled). */
    val accent: Long,
    val emblem: String,
    val variants: List<CardVariant>,
    val ocrScript: OcrScript,
    val numberStyle: NumberStyle,
    /** Ordered: first configured source wins, the next ones are fallbacks. */
    val sources: List<SourceId>,
    val languageTag: String = "en"
)

object Games {
    val all: List<GameDef> = listOf(
        GameDef(
            GameId.POKEMON, R.string.game_pokemon, R.string.collection_pokemon, 0xFFE3350D, "P",
            listOf(CardVariant.NORMAL, CardVariant.HOLO, CardVariant.REVERSE_HOLO, CardVariant.FIRST_EDITION, CardVariant.FIRST_ED_HOLO),
            OcrScript.LATIN, NumberStyle.FRACTION,
            listOf(SourceId.CATALOG_URL, SourceId.TCGDEX)
        ),
        GameDef(
            GameId.ONE_PIECE, R.string.game_onepiece, R.string.collection_onepiece, 0xFFC62828, "OP",
            listOf(CardVariant.NORMAL, CardVariant.PARALLEL, CardVariant.PROMO),
            OcrScript.LATIN, NumberStyle.CODE,
            listOf(SourceId.CATALOG_URL, SourceId.OPTCG)
        ),
        GameDef(
            GameId.MTG, R.string.game_mtg, R.string.collection_mtg, 0xFF6D4C41, "M",
            listOf(CardVariant.NORMAL, CardVariant.FOIL, CardVariant.ETCHED),
            OcrScript.LATIN, NumberStyle.PLAIN,
            listOf(SourceId.CATALOG_URL, SourceId.MTGJSON)
        ),
        GameDef(
            GameId.YGO, R.string.game_ygo, R.string.collection_ygo, 0xFF5E35B1, "YGO",
            listOf(CardVariant.NORMAL, CardVariant.FIRST_EDITION, CardVariant.LIMITED),
            OcrScript.LATIN, NumberStyle.CODE,
            listOf(SourceId.CATALOG_URL)
        ),
        GameDef(
            GameId.LORCANA, R.string.game_lorcana, R.string.collection_lorcana, 0xFF1565C0, "L",
            listOf(CardVariant.NORMAL, CardVariant.FOIL),
            OcrScript.LATIN, NumberStyle.FRACTION,
            listOf(SourceId.CATALOG_URL, SourceId.LORCAST)
        ),
        GameDef(
            GameId.RIFTBOUND, R.string.game_riftbound, R.string.collection_riftbound, 0xFF00897B, "RB",
            listOf(CardVariant.NORMAL, CardVariant.FOIL),
            OcrScript.LATIN, NumberStyle.CODE,
            listOf(SourceId.CATALOG_URL)
        ),
        GameDef(
            GameId.POKEMON_JP, R.string.game_pokemon_jp, R.string.collection_pokemon_jp, 0xFFF9A825, "PJ",
            listOf(CardVariant.NORMAL, CardVariant.HOLO, CardVariant.REVERSE_HOLO, CardVariant.FIRST_EDITION),
            OcrScript.JAPANESE, NumberStyle.FRACTION,
            listOf(SourceId.CATALOG_URL, SourceId.TCGDEX),
            languageTag = "ja"
        ),
        GameDef(
            GameId.GUNDAM, R.string.game_gundam, R.string.collection_gundam, 0xFF283593, "GD",
            listOf(CardVariant.NORMAL, CardVariant.PARALLEL, CardVariant.PROMO),
            OcrScript.LATIN, NumberStyle.CODE,
            listOf(SourceId.CATALOG_URL)
        ),
        GameDef(
            GameId.DBS_FW, R.string.game_dbs_fw, R.string.collection_dbs_fw, 0xFFEF6C00, "DB",
            listOf(CardVariant.NORMAL, CardVariant.PARALLEL, CardVariant.PROMO),
            OcrScript.LATIN, NumberStyle.CODE,
            listOf(SourceId.CATALOG_URL)
        ),
        GameDef(
            GameId.DIGIMON, R.string.game_digimon, R.string.collection_digimon, 0xFF0288D1, "DG",
            listOf(CardVariant.NORMAL, CardVariant.PARALLEL, CardVariant.PROMO),
            OcrScript.LATIN, NumberStyle.CODE,
            listOf(SourceId.CATALOG_URL)
        )
    )

    private val byId = all.associateBy { it.id }
    operator fun get(id: GameId): GameDef = byId.getValue(id)
    fun byCode(code: String?): GameDef? = GameId.fromCode(code)?.let { byId[it] }
}

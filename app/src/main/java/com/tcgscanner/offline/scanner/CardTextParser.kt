package com.tcgscanner.offline.scanner

import com.tcgscanner.offline.core.GameDef
import com.tcgscanner.offline.core.GameId
import com.tcgscanner.offline.core.NumberStyle
import com.tcgscanner.offline.core.Text
import java.util.Locale

/** One OCR text line; positions are normalised (0..1) to the rectified card image. */
data class OcrLine(val text: String, val centerY: Float, val height: Float)

/**
 * A collector number found on the card.
 * @param key normalised number ([Text.numberKey]); matches `numberKey` in Room
 * @param setKey lower-case set prefix when the printed code has one ("op01" of "OP01-120"); matches `setKey` in Room
 * @param total printed set size for "025/198" style numbers
 */
data class NumberHint(val key: String, val total: Int?, val setKey: String? = null)

data class ParsedCard(val names: List<String>, val numbers: List<NumberHint>) {
    val isEmpty: Boolean get() = names.isEmpty() && numbers.isEmpty()
    val hasNumber: Boolean get() = numbers.isNotEmpty()
}

/** Per-game patterns for the printed set code / collector number (the Regex step of the pipeline). */
object CardPatterns {
    // OCR confuses O/o with 0 and I/l with 1; the digit slots of every pattern accept those and are repaired afterwards.
    private const val D = "[0-9OoIl]"

    /** "OP01-120", "ST01-005", "EB01-012", "PRB01-001", "P-001": two to three letters + 2 digits, dash, 3 digits. */
    val onePiece = Regex("\\b([A-Z]{1,3}(?:$D{2})?)\\s?[-‐-―−]\\s?($D{3})\\b")

    /** "FB01-001", "FS01-001", "SB01-001", "P-001" (Dragon Ball Super Fusion World). */
    val fusionWorld = onePiece

    /** "BT1-010", "EX2-045", "ST10-01", "P-012", "RB1-001". */
    val digimon = Regex("\\b([A-Z]{1,2}$D{1,2})\\s?[-‐-―−]\\s?($D{2,3})\\b")

    /** "LOB-EN005", "SDY-006", "RA01-JP012", "LOB-E005". */
    val yugioh = Regex("\\b([A-Z0-9]{3,5})\\s?[-‐-―−]\\s?([A-Z]{1,2}$D{3})\\b")

    /** "025/198", "SV-P 045" is handled by [promo]. */
    val fraction = Regex("(?<![0-9])($D{1,3})\\s*/\\s*($D{2,3})(?![0-9])")

    /** Promo numbers such as "SVP-045", "SWSH123", "TG05". */
    val promo = Regex("\\b([A-Z]{2,5})\\s?[-‐-―−]?\\s?($D{2,3})\\b")

    val plain = Regex("(?<![0-9])($D{3,4})(?![0-9])")

    fun codeRegex(game: GameId): Regex? = when (game) {
        GameId.ONE_PIECE -> onePiece
        GameId.DBS_FW -> fusionWorld
        GameId.DIGIMON -> digimon
        GameId.YGO -> yugioh
        else -> null
    }

    /** Repairs letters that were read in place of digits: "OP0l-O25" -> "OP01-025". Only call on digit groups. */
    fun fixDigits(s: String): String = buildString {
        for (c in s) append(when (c) { 'O', 'o' -> '0'; 'I', 'l' -> '1'; else -> c })
    }
}

/** Turns raw OCR lines into name candidates and collector-number hints, tuned per game. */
object CardTextParser {
    private val hp = Regex("\\bHP\\s*\\d+\\b|\\b\\d+\\s*HP\\b", RegexOption.IGNORE_CASE)
    private val dashes = Regex("[\\u2010-\\u2015\\u2212]")
    private val noise = setOf("basic", "stage", "illus", "trainer", "energy", "pokemon", "item", "supporter", "creature", "instant", "sorcery")

    fun parse(lines: List<OcrLine>, game: GameDef): ParsedCard {
        val numbers = LinkedHashMap<String, NumberHint>()
        for (line in lines) {
            val t = line.text.replace(dashes, "-")
            when (game.numberStyle) {
                NumberStyle.CODE -> addCodes(t, game.id, numbers)
                NumberStyle.FRACTION -> {
                    addFractions(t, numbers)
                    addPromo(t, numbers)
                }
                NumberStyle.PLAIN -> {
                    addFractions(t, numbers)
                    CardPatterns.plain.findAll(t).forEach { m ->
                        val k = Text.numberKey(CardPatterns.fixDigits(m.groupValues[1]))
                        numbers.putIfAbsent(k, NumberHint(k, null))
                    }
                }
            }
        }
        return ParsedCard(nameCandidates(lines), numbers.values.take(6))
    }

    private fun addCodes(text: String, game: GameId, out: MutableMap<String, NumberHint>) {
        val regex = CardPatterns.codeRegex(game) ?: CardPatterns.onePiece
        val upper = text.uppercase(Locale.ROOT)
        regex.findAll(upper).forEach { m ->
            val prefix = m.groupValues[1]
            val number = m.groupValues[2]
            // The prefix may hold OCR-confused digits too ("OPO1" -> "OP01"): fix only trailing digit-like chars.
            val fixedPrefix = fixPrefix(prefix)
            val fixedNumber = if (game == GameId.YGO) number.take(number.length - 3) + CardPatterns.fixDigits(number.takeLast(3)) else CardPatterns.fixDigits(number)
            val code = "$fixedPrefix-$fixedNumber"
            val key = Text.numberKey(code)
            out.putIfAbsent(key, NumberHint(key, null, setKey = fixedPrefix.lowercase(Locale.ROOT)))
        }
    }

    /** "OPO1" -> "OP01": letters directly followed by digit-like characters are treated as set letters + digits. */
    private fun fixPrefix(prefix: String): String {
        val m = Regex("^([A-Z]+?)([0-9OIl]{1,2})$").find(prefix) ?: return prefix
        val letters = m.groupValues[1]
        val tail = CardPatterns.fixDigits(m.groupValues[2])
        // If the "letters" swallowed a digit-like O (e.g. OPO1), shift it back: only when the tail is a single digit.
        return if (tail.length == 1 && letters.length >= 2 && letters.last() in "OIl") {
            letters.dropLast(1) + CardPatterns.fixDigits(letters.last().toString()) + tail
        } else letters + tail
    }

    private fun addFractions(text: String, out: MutableMap<String, NumberHint>) {
        CardPatterns.fraction.findAll(text.uppercase(Locale.ROOT)).forEach { m ->
            val key = Text.numberKey(CardPatterns.fixDigits(m.groupValues[1]))
            out.putIfAbsent(key, NumberHint(key, CardPatterns.fixDigits(m.groupValues[2]).toIntOrNull()))
        }
    }

    private fun addPromo(text: String, out: MutableMap<String, NumberHint>) {
        val upper = text.uppercase(Locale.ROOT)
        // Only lines that look like a promo code ("SVP 045"): letters then a short number, nothing else on the line.
        if (!Regex("^[A-Z]{2,5}\\s?-?\\s?[0-9OIl]{2,3}$").matches(upper.trim())) return
        CardPatterns.promo.find(upper)?.let { m ->
            val code = m.groupValues[1] + "-" + CardPatterns.fixDigits(m.groupValues[2])
            val key = Text.numberKey(code)
            out.putIfAbsent(key, NumberHint(key, null, setKey = m.groupValues[1].lowercase(Locale.ROOT)))
        }
    }

    private fun nameCandidates(lines: List<OcrLine>): List<String> {
        fun clean(s: String) = s.replace(hp, " ").replace(Regex("[0-9/]+"), " ").replace(Regex("\\s+"), " ").trim()
        fun usable(s: String): Boolean {
            val letters = s.count { it.isLetter() }
            return letters >= 3 && s.lowercase(Locale.ROOT) !in noise
        }
        val top = lines.filter { it.centerY < 0.35f }.sortedBy { it.centerY }
        val rest = lines.filter { it.centerY >= 0.35f }.sortedByDescending { it.height }.take(4)
        return (top + rest).map { clean(it.text) }.filter(::usable).distinct().take(6)
    }
}

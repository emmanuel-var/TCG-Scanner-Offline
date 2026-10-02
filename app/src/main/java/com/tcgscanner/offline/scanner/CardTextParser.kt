package com.tcgscanner.offline.scanner

import com.tcgscanner.offline.core.GameDef
import com.tcgscanner.offline.core.NumberStyle
import com.tcgscanner.offline.core.Text
import java.util.Locale

/** One OCR text line; positions are normalised (0..1) to the cropped card image. */
data class OcrLine(val text: String, val centerY: Float, val height: Float)

data class NumberHint(val key: String, val total: Int?)

data class ParsedCard(val names: List<String>, val numbers: List<NumberHint>) {
    val isEmpty: Boolean get() = names.isEmpty() && numbers.isEmpty()
}

/** Turns raw OCR lines into name candidates and collector-number hints, tuned per game. */
object CardTextParser {
    private val fraction = Regex("(?<![0-9])(\\d{1,3})\\s*/\\s*(\\d{2,3})(?![0-9])")
    private val code = Regex("\\b([A-Z]{1,5}\\d{0,2})\\s?-\\s?([A-Z]{0,3}\\d{2,3})\\b")
    private val plainNumber = Regex("(?<![0-9])(\\d{3,4})(?![0-9])")
    private val hp = Regex("\\bHP\\s*\\d+\\b|\\b\\d+\\s*HP\\b", RegexOption.IGNORE_CASE)
    private val dashes = Regex("[\\u2010-\\u2015\\u2212]")
    private val noise = setOf("basic", "stage", "illus", "trainer", "energy", "pokemon", "item", "supporter", "creature", "instant", "sorcery")

    fun parse(lines: List<OcrLine>, game: GameDef): ParsedCard {
        val numbers = LinkedHashMap<String, NumberHint>()
        for (line in lines) {
            val t = line.text.replace(dashes, "-")
            when (game.numberStyle) {
                NumberStyle.CODE -> addCodes(t, numbers)
                NumberStyle.FRACTION -> {
                    addFractions(t, numbers)
                    addCodes(t, numbers) // promo cards such as "SVP-045"
                }
                NumberStyle.PLAIN -> {
                    addFractions(t, numbers)
                    plainNumber.findAll(t).forEach { m ->
                        val k = Text.numberKey(m.value)
                        numbers.putIfAbsent(k, NumberHint(k, null))
                    }
                }
            }
        }
        return ParsedCard(nameCandidates(lines), numbers.values.take(6))
    }

    private fun addCodes(text: String, out: MutableMap<String, NumberHint>) {
        code.findAll(text.uppercase(Locale.ROOT)).forEach { m ->
            val key = Text.numberKey(m.groupValues[1] + "-" + m.groupValues[2])
            out.putIfAbsent(key, NumberHint(key, null))
        }
    }

    private fun addFractions(text: String, out: MutableMap<String, NumberHint>) {
        fraction.findAll(text).forEach { m ->
            val key = Text.numberKey(m.groupValues[1])
            out.putIfAbsent(key, NumberHint(key, m.groupValues[2].toIntOrNull()))
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

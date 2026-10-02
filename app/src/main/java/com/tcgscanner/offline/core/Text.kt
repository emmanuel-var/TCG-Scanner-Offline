package com.tcgscanner.offline.core

import java.text.Normalizer
import java.util.Locale

/** Text helpers shared by catalog import, OCR matching and CSV export. */
object Text {
    private val diacritics = Regex("\\p{M}+")
    private val nonAlnum = Regex("[^\\p{L}\\p{N}]+")
    private val zeroRun = Regex("(?<![0-9])0+(?=[0-9])")

    /** Lower-case, accent-free, alphanumerics only: the key used for fuzzy name search. */
    fun nameKey(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD)
            .replace(diacritics, "")
            .lowercase(Locale.ROOT)
            .replace(nonAlnum, "")

    /**
     * Normalised collector number: "025" -> "25", "OP01-120" -> "op1-120", "LOB-EN001" -> "lob-en1".
     * Used both when importing catalogs and when parsing OCR text so they always agree.
     */
    fun numberKey(s: String): String =
        s.trim().lowercase(Locale.ROOT).replace(Regex("[\\s_]+"), "").replace(zeroRun, "")

    /** Smallest string greater than every string starting with [prefix]: bounds a `>= lo AND < hi` index range scan. */
    fun prefixUpperBound(prefix: String): String =
        if (prefix.isEmpty()) "\uffff" else prefix.dropLast(1) + (prefix.last() + 1)

    fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
            }
            val t = prev; prev = cur; cur = t
        }
        return prev[b.length]
    }

    /** 0..1 similarity between two already-normalised keys. */
    fun similarity(a: String, b: String): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        if (a == b) return 1.0
        val longer = maxOf(a.length, b.length)
        var score = 1.0 - levenshtein(a, b).toDouble() / longer
        // OCR often returns only part of a long name, or extra words.
        val shorter = minOf(a.length, b.length)
        if (shorter >= 4 && (a.contains(b) || b.contains(a))) {
            score = maxOf(score, 0.82 + 0.15 * shorter / longer)
        }
        return score.coerceIn(0.0, 1.0)
    }
}

object Money {
    fun format(usd: Double): String = String.format(Locale.US, "$%,.2f", usd)
    fun plain(usd: Double): String = String.format(Locale.US, "%.2f", usd)
}

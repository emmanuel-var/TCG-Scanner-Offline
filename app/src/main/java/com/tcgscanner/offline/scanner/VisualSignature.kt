package com.tcgscanner.offline.scanner

import android.graphics.Bitmap
import android.graphics.Color
import androidx.core.graphics.get

data class Signature(val dHash: Long, val aHash: Long)

/**
 * Perceptual signature of the card ARTWORK (not the text), so full-art cards whose text OCR cannot
 * read can still be recognised. Computed identically for reference images and camera frames.
 */
object VisualSignature {
    // Artwork window of a portrait card, as fractions of the card image.
    private const val X0 = 0.08f
    private const val X1 = 0.92f
    private const val Y0 = 0.12f
    private const val Y1 = 0.58f

    fun artCrop(card: Bitmap): Bitmap {
        val x = (card.width * X0).toInt()
        val y = (card.height * Y0).toInt()
        val w = (card.width * (X1 - X0)).toInt().coerceAtLeast(1)
        val h = (card.height * (Y1 - Y0)).toInt().coerceAtLeast(1)
        return Bitmap.createBitmap(card, x, y, w.coerceAtMost(card.width - x), h.coerceAtMost(card.height - y))
    }

    fun compute(card: Bitmap): Signature {
        val art = artCrop(card)
        val wide = Bitmap.createScaledBitmap(art, 9, 8, true)
        var d = 0L
        for (row in 0 until 8) {
            for (col in 0 until 8) {
                d = d shl 1
                if (luma(wide[col, row]) < luma(wide[col + 1, row])) d = d or 1L
            }
        }
        val small = Bitmap.createScaledBitmap(art, 8, 8, true)
        val values = IntArray(64) { luma(small[it % 8, it / 8]) }
        val mean = values.average()
        var a = 0L
        for (v in values) {
            a = a shl 1
            if (v > mean) a = a or 1L
        }
        if (wide !== art) wide.recycle()
        if (small !== art) small.recycle()
        if (art !== card) art.recycle()
        return Signature(d, a)
    }

    fun distance(x: Signature, y: Signature): Int =
        java.lang.Long.bitCount(x.dHash xor y.dHash) + java.lang.Long.bitCount(x.aHash xor y.aHash)

    private fun luma(c: Int): Int = (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000
}

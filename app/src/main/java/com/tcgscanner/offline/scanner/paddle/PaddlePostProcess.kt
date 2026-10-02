package com.tcgscanner.offline.scanner.paddle

import kotlin.math.max
import kotlin.math.min

/** An axis-aligned text region found by the DB detector, in the pixel space of the detector input. */
data class TextBox(val left: Int, val top: Int, val right: Int, val bottom: Int, val score: Float) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

/** Post-processing of PP-OCR's DB (differentiable binarisation) text detector output. */
object DbPostProcessor {

    /**
     * @param prob probability map, row-major [height] x [width], values 0..1
     * @param threshold binarisation threshold (PaddleOCR default 0.3)
     * @param boxThreshold minimum mean probability inside a region (default 0.6)
     * @param unclipRatio how far a region is expanded to recover the text border the network shrinks (default 1.6)
     */
    fun boxes(
        prob: FloatArray,
        width: Int,
        height: Int,
        threshold: Float = 0.3f,
        boxThreshold: Float = 0.6f,
        unclipRatio: Float = 1.6f,
        minSide: Int = 3
    ): List<TextBox> {
        val visited = BooleanArray(width * height)
        val stack = IntArray(width * height)
        val out = ArrayList<TextBox>()
        for (start in prob.indices) {
            if (visited[start] || prob[start] < threshold) continue
            // Flood fill (4-connected) of one text blob.
            var sp = 0
            stack[sp++] = start
            visited[start] = true
            var minX = width; var minY = height; var maxX = 0; var maxY = 0
            var sum = 0f; var count = 0
            while (sp > 0) {
                val p = stack[--sp]
                val x = p % width; val y = p / width
                minX = min(minX, x); maxX = max(maxX, x); minY = min(minY, y); maxY = max(maxY, y)
                sum += prob[p]; count++
                if (x > 0 && !visited[p - 1] && prob[p - 1] >= threshold) { visited[p - 1] = true; stack[sp++] = p - 1 }
                if (x < width - 1 && !visited[p + 1] && prob[p + 1] >= threshold) { visited[p + 1] = true; stack[sp++] = p + 1 }
                if (y > 0 && !visited[p - width] && prob[p - width] >= threshold) { visited[p - width] = true; stack[sp++] = p - width }
                if (y < height - 1 && !visited[p + width] && prob[p + width] >= threshold) { visited[p + width] = true; stack[sp++] = p + width }
            }
            val score = sum / count
            val w = maxX - minX + 1
            val h = maxY - minY + 1
            if (score < boxThreshold || min(w, h) < minSide) continue
            // Same expansion distance PaddleOCR uses: area * ratio / perimeter.
            val d = (w.toFloat() * h * unclipRatio / (2f * (w + h))).toInt()
            out.add(
                TextBox(
                    max(0, minX - d), max(0, minY - d), min(width - 1, maxX + d), min(height - 1, maxY + d), score
                )
            )
        }
        return sortReadingOrder(out)
    }

    /** Top to bottom, then left to right, treating boxes whose tops are within ~half a line as one row. */
    fun sortReadingOrder(boxes: List<TextBox>): List<TextBox> {
        val sorted = boxes.sortedWith(compareBy({ it.top }, { it.left })).toMutableList()
        for (i in 0 until sorted.size - 1) {
            for (j in i downTo 0) {
                val a = sorted[j]; val b = sorted[j + 1]
                if (kotlin.math.abs(b.top - a.top) < max(4, min(a.height, b.height) / 2) && b.left < a.left) {
                    sorted[j] = b; sorted[j + 1] = a
                } else break
            }
        }
        return sorted
    }
}

/** Character set of a PP-OCR recogniser: index 0 is the CTC blank, then the dictionary, then the space. */
class PaddleCharset(dictionaryLines: List<String>, useSpace: Boolean = true) {
    val chars: List<String> = buildList {
        add("") // blank
        dictionaryLines.forEach { add(it.trimEnd('\r', '\n')) }
        if (useSpace) add(" ")
    }
    val size: Int get() = chars.size
}

object CtcDecoder {
    data class Result(val text: String, val confidence: Float)

    /**
     * Greedy CTC decoding of recogniser logits/probabilities.
     * @param logits row-major [steps] x [classes]
     */
    fun decode(logits: FloatArray, steps: Int, classes: Int, charset: PaddleCharset): Result {
        val sb = StringBuilder()
        var confSum = 0f
        var confCount = 0
        var previous = -1
        for (t in 0 until steps) {
            var best = 0
            var bestV = logits[t * classes]
            for (c in 1 until classes) {
                val v = logits[t * classes + c]
                if (v > bestV) { bestV = v; best = c }
            }
            if (best != 0 && best != previous && best < charset.size) {
                sb.append(charset.chars[best])
                confSum += bestV
                confCount++
            }
            previous = best
        }
        val conf = if (confCount == 0) 0f else confSum / confCount
        return Result(sb.toString(), conf)
    }
}

package com.tcgscanner.offline.scanner.pipeline

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Finds the card's four corners inside the box YOLO returned, so a card held at an angle can be rectified.
 * Pure Kotlin on a luminance array (no OpenCV): Sobel edges -> Otsu threshold -> convex hull of strong edge
 * pixels -> extreme points along both diagonals.
 */
object QuadRefiner {

    /**
     * @param gray luminance 0..255, row-major, [width] x [height]
     * @param box search window inside the image (the detector box, slightly enlarged by the caller)
     * @return corners in image coordinates, or null when no card-shaped quadrilateral is found
     */
    fun refine(gray: IntArray, width: Int, height: Int, box: FloatArray): Quad? {
        val x0 = max(1, box[0].toInt()); val y0 = max(1, box[1].toInt())
        val x1 = min(width - 2, box[2].toInt()); val y1 = min(height - 2, box[3].toInt())
        if (x1 - x0 < 16 || y1 - y0 < 16) return null

        val w = x1 - x0 + 1
        val h = y1 - y0 + 1
        val mag = IntArray(w * h)
        val hist = IntArray(256)
        for (y in y0..y1) for (x in x0..x1) {
            val gx = -gray[(y - 1) * width + x - 1] - 2 * gray[y * width + x - 1] - gray[(y + 1) * width + x - 1] +
                gray[(y - 1) * width + x + 1] + 2 * gray[y * width + x + 1] + gray[(y + 1) * width + x + 1]
            val gy = -gray[(y - 1) * width + x - 1] - 2 * gray[(y - 1) * width + x] - gray[(y - 1) * width + x + 1] +
                gray[(y + 1) * width + x - 1] + 2 * gray[(y + 1) * width + x] + gray[(y + 1) * width + x + 1]
            val m = min(255, (sqrt((gx * gx + gy * gy).toDouble()) / 4.0).toInt())
            mag[(y - y0) * w + (x - x0)] = m
            hist[m]++
        }
        val threshold = max(24, otsu(hist, w * h))

        val points = ArrayList<Pt>()
        // Subsample so the hull stays cheap on large boxes.
        val step = max(1, min(w, h) / 160)
        var y = 0
        while (y < h) {
            var x = 0
            while (x < w) {
                if (mag[y * w + x] >= threshold) points.add(Pt((x + x0).toFloat(), (y + y0).toFloat()))
                x += step
            }
            y += step
        }
        val quad = CardGeometry.cornersOf(points) ?: return null
        return quad.takeIf { CardGeometry.isCardShaped(CardGeometry.portrait(it), tolerance = 0.2f) }
    }

    fun otsu(hist: IntArray, total: Int): Int {
        var sum = 0.0
        for (i in hist.indices) sum += i * hist[i].toDouble()
        var sumB = 0.0; var wB = 0.0; var best = 0.0; var threshold = 0
        for (t in hist.indices) {
            wB += hist[t]
            if (wB == 0.0) continue
            val wF = total - wB
            if (wF == 0.0) break
            sumB += t * hist[t].toDouble()
            val mB = sumB / wB
            val mF = (sum - sumB) / wF
            val between = wB * wF * (mB - mF) * (mB - mF)
            if (between > best) { best = between; threshold = t }
        }
        return threshold
    }
}

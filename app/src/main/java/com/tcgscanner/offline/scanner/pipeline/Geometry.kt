package com.tcgscanner.offline.scanner.pipeline

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

data class Pt(val x: Float, val y: Float)

/** Corners in reading order for an upright card: top-left, top-right, bottom-right, bottom-left. */
data class Quad(val tl: Pt, val tr: Pt, val br: Pt, val bl: Pt) {
    val points: List<Pt> get() = listOf(tl, tr, br, bl)

    val topEdge: Float get() = dist(tl, tr)
    val bottomEdge: Float get() = dist(bl, br)
    val leftEdge: Float get() = dist(tl, bl)
    val rightEdge: Float get() = dist(tr, br)

    /** Average card width / height as the quad is currently labelled. */
    val width: Float get() = (topEdge + bottomEdge) / 2f
    val height: Float get() = (leftEdge + rightEdge) / 2f

    /** short side / long side, so 63 x 88 mm cards are ~0.716 whatever the orientation. */
    val aspect: Float get() = min(width, height) / max(width, height).coerceAtLeast(1e-6f)

    fun scaled(sx: Float, sy: Float) = Quad(
        Pt(tl.x * sx, tl.y * sy), Pt(tr.x * sx, tr.y * sy), Pt(br.x * sx, br.y * sy), Pt(bl.x * sx, bl.y * sy)
    )

    /** Same card turned 180 degrees (labels rotated two steps): used when the first read finds no text. */
    fun rotated180() = Quad(br, bl, tl, tr)

    /** Labels rotated one step clockwise. */
    fun rotated90() = Quad(bl, tl, tr, br)

    companion object {
        private fun dist(a: Pt, b: Pt) = hypot(a.x - b.x, a.y - b.y)
    }
}

object CardGeometry {
    /** Physical TCG card: 63 x 88 mm. */
    const val CARD_ASPECT = 63f / 88f

    /** Size of the rectified card handed to OCR (multiples of 32 for the text detector, ratio ~0.727). */
    const val CROP_WIDTH = 512
    const val CROP_HEIGHT = 704

    fun quadOfBox(left: Float, top: Float, right: Float, bottom: Float) =
        Quad(Pt(left, top), Pt(right, top), Pt(right, bottom), Pt(left, bottom))

    /** Orders four arbitrary points as tl, tr, br, bl using the classic sum / difference rule. */
    fun orderCorners(p: List<Pt>): Quad {
        require(p.size == 4) { "need exactly 4 points" }
        val tl = p.minBy { it.x + it.y }
        val br = p.maxBy { it.x + it.y }
        val tr = p.maxBy { it.x - it.y }
        val bl = p.minBy { it.x - it.y }
        return Quad(tl, tr, br, bl)
    }

    /**
     * Turns a quad into an upright (portrait) one: if the card lies on its side the labels are rotated so that the
     * rectified crop is portrait. Which of the two portrait directions is right is decided by OCR (see rotated180).
     */
    fun portrait(q: Quad): Quad = if (q.width > q.height) q.rotated90() else q

    /** Convex hull (monotone chain), counter-clockwise, no repeated first point. */
    fun convexHull(points: List<Pt>): List<Pt> {
        if (points.size <= 2) return points
        val sorted = points.sortedWith(compareBy({ it.x }, { it.y }))
        fun cross(o: Pt, a: Pt, b: Pt) = (a.x - o.x) * (b.y - o.y) - (a.y - o.y) * (b.x - o.x)
        val lower = ArrayList<Pt>()
        for (pt in sorted) {
            while (lower.size >= 2 && cross(lower[lower.size - 2], lower[lower.size - 1], pt) <= 0f) lower.removeAt(lower.size - 1)
            lower.add(pt)
        }
        val upper = ArrayList<Pt>()
        for (pt in sorted.asReversed()) {
            while (upper.size >= 2 && cross(upper[upper.size - 2], upper[upper.size - 1], pt) <= 0f) upper.removeAt(upper.size - 1)
            upper.add(pt)
        }
        lower.removeAt(lower.size - 1)
        upper.removeAt(upper.size - 1)
        return lower + upper
    }

    /**
     * Four corners of the dominant quadrilateral of a point cloud (card edge pixels): the hull points that are
     * extreme along the two diagonals. Works for cards rotated up to roughly 40 degrees and for perspective skew.
     */
    fun cornersOf(points: List<Pt>): Quad? {
        if (points.size < 8) return null
        val hull = convexHull(points)
        if (hull.size < 4) return null
        return orderCorners(listOf(hull.minBy { it.x + it.y }, hull.maxBy { it.x - it.y }, hull.maxBy { it.x + it.y }, hull.minBy { it.x - it.y }))
    }

    /** Area by the shoelace formula (always positive). */
    fun area(q: Quad): Float {
        val p = q.points
        var s = 0f
        for (i in p.indices) {
            val a = p[i]
            val b = p[(i + 1) % p.size]
            s += a.x * b.y - b.x * a.y
        }
        return abs(s) / 2f
    }

    /** True when [q] looks like a TCG card: right proportions, convex, not degenerate. */
    fun isCardShaped(q: Quad, tolerance: Float = 0.16f): Boolean {
        if (q.width < 8f || q.height < 8f) return false
        if (abs(q.aspect - CARD_ASPECT) > tolerance) return false
        // Opposite edges must be of comparable length (rules out slivers and self-intersecting orderings).
        if (min(q.topEdge, q.bottomEdge) / max(q.topEdge, q.bottomEdge) < 0.6f) return false
        if (min(q.leftEdge, q.rightEdge) / max(q.leftEdge, q.rightEdge) < 0.6f) return false
        // Convexity: all cross products of consecutive edges share one sign.
        val p = q.points
        var sign = 0
        for (i in p.indices) {
            val a = p[i]; val b = p[(i + 1) % 4]; val c = p[(i + 2) % 4]
            val cross = (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x)
            val s = if (cross > 0) 1 else if (cross < 0) -1 else 0
            if (s != 0) { if (sign == 0) sign = s else if (sign != s) return false }
        }
        return true
    }
}

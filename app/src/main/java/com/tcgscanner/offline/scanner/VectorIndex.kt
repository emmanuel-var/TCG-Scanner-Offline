package com.tcgscanner.offline.scanner

import kotlin.math.sqrt

data class VectorHit(val id: String, val score: Float)

/**
 * All reference embeddings of ONE game, held in RAM as a single contiguous float array so a query is a tight
 * loop of dot products (cosine similarity, vectors are normalised on insertion). Nothing here touches SQLite:
 * 20,000 cards x 1280 dims scans in a few tens of milliseconds, which is what keeps "identify by artwork" instant.
 */
class VectorIndex private constructor(
    val dim: Int,
    private val ids: Array<String>,
    private val data: FloatArray
) {
    val size: Int get() = ids.size

    /** Cosine similarity of [query] against every stored vector; returns the best [k] with score >= [minScore]. */
    fun topK(query: FloatArray, k: Int, minScore: Float = -1f): List<VectorHit> {
        if (query.size != dim || size == 0 || k <= 0) return emptyList()
        val q = normalized(query)
        // Keep the best k in a small sorted array (k is tiny: 3 to 10).
        val topScores = FloatArray(k) { Float.NEGATIVE_INFINITY }
        val topIdx = IntArray(k) { -1 }
        var base = 0
        for (row in 0 until size) {
            var dot = 0f
            for (d in 0 until dim) dot += data[base + d] * q[d]
            base += dim
            if (dot > topScores[k - 1] && dot >= minScore) {
                var pos = k - 1
                while (pos > 0 && topScores[pos - 1] < dot) {
                    topScores[pos] = topScores[pos - 1]; topIdx[pos] = topIdx[pos - 1]; pos--
                }
                topScores[pos] = dot; topIdx[pos] = row
            }
        }
        return (0 until k).filter { topIdx[it] >= 0 }.map { VectorHit(ids[topIdx[it]], topScores[it]) }
    }

    companion object {
        val EMPTY = VectorIndex(0, emptyArray(), FloatArray(0))

        /**
         * Builds an index from (id, vector) pairs. Vectors whose length differs from the most common length
         * (a leftover from an older model) are skipped.
         */
        fun build(entries: List<Pair<String, FloatArray>>): VectorIndex {
            if (entries.isEmpty()) return EMPTY
            val dim = entries.groupingBy { it.second.size }.eachCount().maxByOrNull { it.value }!!.key
            val usable = entries.filter { it.second.size == dim && dim > 0 }
            val data = FloatArray(usable.size * dim)
            usable.forEachIndexed { row, (_, vec) ->
                val n = normalized(vec)
                System.arraycopy(n, 0, data, row * dim, dim)
            }
            return VectorIndex(dim, Array(usable.size) { usable[it].first }, data)
        }

        fun normalized(v: FloatArray): FloatArray {
            var s = 0.0
            for (x in v) s += x.toDouble() * x
            val norm = sqrt(s).toFloat().coerceAtLeast(1e-9f)
            return FloatArray(v.size) { v[it] / norm }
        }
    }
}

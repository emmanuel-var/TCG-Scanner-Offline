package com.tcgscanner.offline.scanner.pipeline

import kotlin.math.max
import kotlin.math.min

data class Detection(val left: Float, val top: Float, val right: Float, val bottom: Float, val score: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val area: Float get() = max(0f, width) * max(0f, height)
}

/** Aspect-preserving resize of a frame into a square model input, plus the inverse mapping for boxes. */
class Letterbox(val srcWidth: Int, val srcHeight: Int, val inputSize: Int) {
    val scale: Float = min(inputSize.toFloat() / srcWidth, inputSize.toFloat() / srcHeight)
    val scaledWidth: Int = (srcWidth * scale).toInt().coerceAtLeast(1)
    val scaledHeight: Int = (srcHeight * scale).toInt().coerceAtLeast(1)
    val padX: Float = (inputSize - scaledWidth) / 2f
    val padY: Float = (inputSize - scaledHeight) / 2f

    fun toSourceX(x: Float): Float = ((x - padX) / scale).coerceIn(0f, srcWidth.toFloat())
    fun toSourceY(y: Float): Float = ((y - padY) / scale).coerceIn(0f, srcHeight.toFloat())
}

/**
 * Decodes the raw output tensor of an Ultralytics YOLO11 / YOLOv8 detector exported to TFLite.
 *
 * Layout is `[1, 4 + classes, anchors]` (channels first) as exported by Ultralytics, or the transposed
 * `[1, anchors, 4 + classes]`; both are detected from the shape. Boxes are `cx, cy, w, h` either in input pixels
 * or normalised to 0..1 (depends on the exporter), also detected. There is no objectness column in YOLO11/v8:
 * the confidence is the best class score.
 */
object YoloDecoder {

    fun decode(
        output: FloatArray,
        shape: IntArray,
        letterbox: Letterbox,
        scoreThreshold: Float = 0.35f,
        iouThreshold: Float = 0.45f,
        maxDetections: Int = 5
    ): List<Detection> {
        require(shape.size == 3) { "expected a rank-3 output, got ${shape.toList()}" }
        val channelsFirst = shape[1] < shape[2]
        val channels = if (channelsFirst) shape[1] else shape[2]
        val anchors = if (channelsFirst) shape[2] else shape[1]
        require(channels >= 5) { "output needs at least 4 box values and one class score" }
        fun v(c: Int, i: Int) = if (channelsFirst) output[c * anchors + i] else output[i * channels + c]

        val candidates = ArrayList<IntArray>() // anchor indices above the threshold
        val scores = ArrayList<Float>()
        for (i in 0 until anchors) {
            var best = 0f
            for (c in 4 until channels) best = max(best, v(c, i))
            if (best >= scoreThreshold) { candidates.add(intArrayOf(i)); scores.add(best) }
        }
        if (candidates.isEmpty()) return emptyList()

        // Normalised boxes have w, h <= ~1; pixel boxes are far larger.
        var maxSide = 0f
        for (k in 0 until min(candidates.size, 50)) {
            val i = candidates[k][0]
            maxSide = max(maxSide, max(v(2, i), v(3, i)))
        }
        val unit = if (maxSide <= 2f) letterbox.inputSize.toFloat() else 1f

        val raw = candidates.indices.map { k ->
            val i = candidates[k][0]
            val cx = v(0, i) * unit; val cy = v(1, i) * unit
            val w = v(2, i) * unit; val h = v(3, i) * unit
            Detection(
                letterbox.toSourceX(cx - w / 2f), letterbox.toSourceY(cy - h / 2f),
                letterbox.toSourceX(cx + w / 2f), letterbox.toSourceY(cy + h / 2f), scores[k]
            )
        }.filter { it.width > 2f && it.height > 2f }
        return nms(raw, iouThreshold).take(maxDetections)
    }

    fun nms(boxes: List<Detection>, iouThreshold: Float): List<Detection> {
        val sorted = boxes.sortedByDescending { it.score }
        val kept = ArrayList<Detection>()
        for (b in sorted) if (kept.none { iou(it, b) > iouThreshold }) kept.add(b)
        return kept
    }

    fun iou(a: Detection, b: Detection): Float {
        val l = max(a.left, b.left); val t = max(a.top, b.top)
        val r = min(a.right, b.right); val bt = min(a.bottom, b.bottom)
        val inter = max(0f, r - l) * max(0f, bt - t)
        val union = a.area + b.area - inter
        return if (union <= 0f) 0f else inter / union
    }
}

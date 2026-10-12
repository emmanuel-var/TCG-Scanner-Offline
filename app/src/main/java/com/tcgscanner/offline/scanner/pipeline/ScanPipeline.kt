package com.tcgscanner.offline.scanner.pipeline

import android.graphics.Bitmap
import android.graphics.RectF
import com.tcgscanner.offline.scanner.OcrEngine
import com.tcgscanner.offline.scanner.OcrLine
import kotlin.math.max
import kotlin.math.min

/** Everything one camera frame produced. */
data class PipelineFrame(
    /** Card outline normalised to the frame (0..1), for the on-screen overlay; null when the guide frame was used. */
    val outline: Quad?,
    /** True when the card edges were found inside the guide area; false when the guide frame itself was cropped. */
    val detected: Boolean,
    val lines: List<OcrLine>,
    val engine: String,
    /** The rectified, upright card (kept for the "identify by artwork" fallback). */
    val card: Bitmap
)

/**
 * The on-device pipeline for ONE frame:
 *  1. The card edges are searched inside the on-screen guide area with plain image processing ([QuadRefiner]:
 *     Sobel edges, Otsu threshold, convex hull; no neural network and no OpenCV), and the card is rectified
 *     (perspective correction). When no card-shaped outline is found, the guide frame itself is cropped.
 *  2. The OCR engine (PaddleOCR when installed, ML Kit otherwise) reads the rectified card.
 * Regex parsing, the Room lookup and the variant bottom sheet happen in the ViewModel.
 */
class ScanPipeline(
    private val ocr: () -> OcrEngine
) {
    /** The card may be upside down or turned 90 degrees; when a read finds almost no text the next frame tries 180 degrees. */
    private var flipped = false

    fun process(frame: Bitmap, guide: RectF): PipelineFrame? {
        val fw = frame.width.toFloat()
        val fh = frame.height.toFloat()
        val guideQuad = CardGeometry.quadOfBox(guide.left * fw, guide.top * fh, guide.right * fw, guide.bottom * fh)

        val found = refine(frame, guide.left * fw, guide.top * fh, guide.right * fw, guide.bottom * fh)
        val outline: Quad = found ?: guideQuad
        val upright = CardGeometry.portrait(outline)
        val oriented = if (flipped) upright.rotated180() else upright
        val card = PerspectiveCropper.crop(frame, oriented)

        val engine = ocr()
        val lines = engine.recognize(card)
        if (lines.sumOf { it.text.length } < MIN_CHARS) flipped = !flipped

        return PipelineFrame(
            outline = outline.scaled(1f / fw, 1f / fh).takeIf { found != null },
            detected = found != null,
            lines = lines,
            engine = engine.name,
            card = card
        )
    }

    /**
     * Looks for the real card corners inside (a slightly larger copy of) the guide area. The result is only trusted
     * when it is card-shaped and about as large as the guide: a card held in the guide fills most of it, a hand or
     * a table edge does not.
     */
    private fun refine(frame: Bitmap, left: Float, top: Float, right: Float, bottom: Float): Quad? {
        val padX = (right - left) * 0.08f
        val padY = (bottom - top) * 0.08f
        val l = max(0, (left - padX).toInt()); val t = max(0, (top - padY).toInt())
        val r = min(frame.width, (right + padX).toInt()); val b = min(frame.height, (bottom + padY).toInt())
        if (r - l < 32 || b - t < 32) return null
        val roi = Bitmap.createBitmap(frame, l, t, r - l, b - t)
        // Work at a modest size: the corner search does not need full resolution.
        val scale = min(1f, 240f / max(roi.width, roi.height))
        val small = if (scale < 1f) Bitmap.createScaledBitmap(roi, (roi.width * scale).toInt(), (roi.height * scale).toInt(), true) else roi
        val gray = PerspectiveCropper.luminance(small)
        val quad = QuadRefiner.refine(gray, small.width, small.height, floatArrayOf(0f, 0f, small.width - 1f, small.height - 1f)) ?: return null
        val full = quad.scaled(1f / scale, 1f / scale).let { q ->
            Quad(Pt(q.tl.x + l, q.tl.y + t), Pt(q.tr.x + l, q.tr.y + t), Pt(q.br.x + l, q.br.y + t), Pt(q.bl.x + l, q.bl.y + t))
        }
        val guideArea = (right - left) * (bottom - top)
        val ratio = CardGeometry.area(full) / guideArea.coerceAtLeast(1f)
        return full.takeIf { ratio in MIN_GUIDE_FILL..MAX_GUIDE_FILL }
    }

    private companion object {
        const val MIN_CHARS = 6
        const val MIN_GUIDE_FILL = 0.55f
        const val MAX_GUIDE_FILL = 1.35f
    }
}

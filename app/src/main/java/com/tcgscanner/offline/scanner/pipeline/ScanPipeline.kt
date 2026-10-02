package com.tcgscanner.offline.scanner.pipeline

import android.graphics.Bitmap
import android.graphics.RectF
import com.tcgscanner.offline.scanner.OcrEngine
import com.tcgscanner.offline.scanner.OcrLine
import kotlin.math.max
import kotlin.math.min

/** Everything one camera frame produced. */
data class PipelineFrame(
    /** Card outline normalised to the frame (0..1), for the on-screen overlay; null when no card was found. */
    val outline: Quad?,
    /** True when the YOLO detector found the card; false when the guide frame was used instead. */
    val detected: Boolean,
    val lines: List<OcrLine>,
    val engine: String,
    /** The rectified, upright card (kept for the "identify by artwork" fallback). */
    val card: Bitmap
)

/**
 * The on-device hybrid pipeline for ONE frame:
 *  1. YOLO11n finds the card; the corners are refined and the card is rectified (perspective correction).
 *     Without the detector pack the on-screen guide frame is cropped instead.
 *  2. The OCR engine (PaddleOCR when installed, ML Kit otherwise) reads the rectified card.
 * Regex parsing, the Room lookup and the variant bottom sheet happen in the ViewModel.
 */
class ScanPipeline(
    private val detector: YoloCardDetector,
    private val ocr: () -> OcrEngine
) {
    /** The card may be upside down or turned 90 degrees; when a read finds almost no text the next frame tries 180 degrees. */
    private var flipped = false

    fun process(frame: Bitmap, guide: RectF): PipelineFrame? {
        val fw = frame.width.toFloat()
        val fh = frame.height.toFloat()

        val box = if (detector.isAvailable) detector.detect(frame) else null
        val outline: Quad = if (box != null) {
            refine(frame, box) ?: CardGeometry.quadOfBox(box.left, box.top, box.right, box.bottom)
        } else {
            CardGeometry.quadOfBox(guide.left * fw, guide.top * fh, guide.right * fw, guide.bottom * fh)
        }
        val upright = CardGeometry.portrait(outline)
        val oriented = if (flipped) upright.rotated180() else upright
        val card = PerspectiveCropper.crop(frame, oriented)

        val engine = ocr()
        val lines = engine.recognize(card)
        if (lines.sumOf { it.text.length } < MIN_CHARS) flipped = !flipped

        return PipelineFrame(
            outline = outline.scaled(1f / fw, 1f / fh).takeIf { box != null },
            detected = box != null,
            lines = lines,
            engine = engine.name,
            card = card
        )
    }

    /** Looks for the real card corners inside (a slightly larger copy of) the detector box. */
    private fun refine(frame: Bitmap, box: Detection): Quad? {
        val padX = box.width * 0.04f
        val padY = box.height * 0.04f
        val l = max(0, (box.left - padX).toInt()); val t = max(0, (box.top - padY).toInt())
        val r = min(frame.width, (box.right + padX).toInt()); val b = min(frame.height, (box.bottom + padY).toInt())
        if (r - l < 32 || b - t < 32) return null
        val roi = Bitmap.createBitmap(frame, l, t, r - l, b - t)
        // Work at a modest size: the corner search does not need full resolution.
        val scale = min(1f, 240f / max(roi.width, roi.height))
        val small = if (scale < 1f) Bitmap.createScaledBitmap(roi, (roi.width * scale).toInt(), (roi.height * scale).toInt(), true) else roi
        val gray = PerspectiveCropper.luminance(small)
        val quad = QuadRefiner.refine(gray, small.width, small.height, floatArrayOf(0f, 0f, small.width - 1f, small.height - 1f))
        return quad?.scaled(1f / scale, 1f / scale)?.let { q ->
            Quad(
                Pt(q.tl.x + l, q.tl.y + t), Pt(q.tr.x + l, q.tr.y + t), Pt(q.br.x + l, q.br.y + t), Pt(q.bl.x + l, q.bl.y + t)
            )
        }
    }

    private companion object {
        const val MIN_CHARS = 6
    }
}

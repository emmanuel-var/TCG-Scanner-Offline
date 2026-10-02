package com.tcgscanner.offline.scanner

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RectF
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognizer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Runs on every camera frame (throttled): crops the on-screen card guide, runs ML Kit OCR on-device and
 * reports lines. When [captureRequested] is set, it also hands over the cropped card bitmap so the
 * artwork matcher can work on it.
 */
class CardAnalyzer(
    private val recognizer: TextRecognizer,
    /** Card guide as fractions of the preview (left, top, right, bottom). */
    private val guide: AtomicReference<RectF>,
    private val paused: AtomicBoolean,
    private val captureRequested: AtomicBoolean,
    private val onLines: (List<OcrLine>) -> Unit,
    private val onCapture: (Bitmap) -> Unit,
    private val minIntervalMs: Long = 400L
) : ImageAnalysis.Analyzer {

    @Volatile private var lastRun = 0L

    override fun analyze(image: ImageProxy) {
        val capture = captureRequested.get()
        val now = System.currentTimeMillis()
        if ((paused.get() && !capture) || (!capture && now - lastRun < minIntervalMs)) {
            image.close()
            return
        }
        lastRun = now
        val card: Bitmap = try {
            cropToGuide(image)
        } catch (e: Exception) {
            image.close()
            return
        }
        image.close()

        if (capture && captureRequested.compareAndSet(true, false)) onCapture(card)
        if (paused.get()) return

        recognizer.process(InputImage.fromBitmap(card, 0))
            .addOnSuccessListener { text ->
                val h = card.height.toFloat()
                val lines = text.textBlocks.flatMap { it.lines }.mapNotNull { line ->
                    val box = line.boundingBox ?: return@mapNotNull null
                    OcrLine(line.text, box.centerY() / h, box.height() / h)
                }
                onLines(lines)
            }
    }

    private fun cropToGuide(image: ImageProxy): Bitmap {
        val raw = image.toBitmap()
        val matrix = Matrix().apply { postRotate(image.imageInfo.rotationDegrees.toFloat()) }
        // Respect the view-port crop so the guide fractions line up with what the user sees.
        val crop = image.cropRect
        val visible = Bitmap.createBitmap(
            raw, crop.left.coerceAtLeast(0), crop.top.coerceAtLeast(0),
            crop.width().coerceAtMost(raw.width - crop.left.coerceAtLeast(0)),
            crop.height().coerceAtMost(raw.height - crop.top.coerceAtLeast(0)),
            matrix, true
        )
        val g = guide.get()
        val x = (visible.width * g.left).toInt().coerceIn(0, visible.width - 1)
        val y = (visible.height * g.top).toInt().coerceIn(0, visible.height - 1)
        val w = (visible.width * (g.right - g.left)).toInt().coerceIn(1, visible.width - x)
        val h = (visible.height * (g.bottom - g.top)).toInt().coerceIn(1, visible.height - y)
        return Bitmap.createBitmap(visible, x, y, w, h)
    }
}

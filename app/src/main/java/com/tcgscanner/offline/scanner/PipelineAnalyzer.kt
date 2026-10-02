package com.tcgscanner.offline.scanner

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RectF
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.tcgscanner.offline.scanner.pipeline.PipelineFrame
import com.tcgscanner.offline.scanner.pipeline.ScanPipeline
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * CameraX analyzer that runs the hybrid pipeline on the latest frame (throttled). The frame is cropped to the
 * visible viewport so the outline coordinates it reports line up with what the user sees on screen.
 */
class PipelineAnalyzer(
    private val pipeline: ScanPipeline,
    /** Card guide as fractions of the preview; only used when the YOLO pack is not installed. */
    private val guide: AtomicReference<RectF>,
    private val paused: AtomicBoolean,
    private val onFrame: (PipelineFrame) -> Unit,
    private val minIntervalMs: Long = 250L
) : ImageAnalysis.Analyzer {

    @Volatile private var lastRun = 0L

    override fun analyze(image: ImageProxy) {
        val now = System.currentTimeMillis()
        if (paused.get() || now - lastRun < minIntervalMs) {
            image.close()
            return
        }
        lastRun = now
        val frame: Bitmap = try {
            visibleBitmap(image)
        } catch (e: Exception) {
            image.close()
            return
        }
        image.close()

        val result = try {
            pipeline.process(frame, guide.get())
        } catch (e: Exception) {
            null
        } finally {
            frame.recycle()
        } ?: return

        if (!paused.get()) onFrame(result)
    }

    private fun visibleBitmap(image: ImageProxy): Bitmap {
        val raw = image.toBitmap()
        val crop = image.cropRect
        val l = crop.left.coerceAtLeast(0)
        val t = crop.top.coerceAtLeast(0)
        val matrix = Matrix().apply { postRotate(image.imageInfo.rotationDegrees.toFloat()) }
        return Bitmap.createBitmap(
            raw, l, t, crop.width().coerceAtMost(raw.width - l), crop.height().coerceAtMost(raw.height - t), matrix, true
        )
    }
}

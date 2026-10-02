package com.tcgscanner.offline.scanner.pipeline

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * Phase 1 of the scan pipeline: finds the card in the camera frame with a YOLO11n (or YOLOv8n) detector exported to
 * TensorFlow Lite. The detector is used ONLY for the bounding box; reading the card is the OCR's job.
 *
 * The model file (`card_detector.tflite`, see tools/model) is a downloadable pack, not part of the APK. Until it is
 * installed [isAvailable] is false and the pipeline falls back to the on-screen guide frame.
 *
 * Expected export: `yolo export format=tflite imgsz=640` -> float16/float32 model with input `[1, S, S, 3]` (RGB 0..1)
 * and output `[1, 4 + classes, anchors]`.
 */
class YoloCardDetector(private val modelFile: File) : AutoCloseable {
    @Volatile private var interpreter: Interpreter? = null
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)

    val isAvailable: Boolean get() = interpreter != null || load()

    @Synchronized
    fun reload(): Boolean {
        unload()
        return load()
    }

    @Synchronized
    fun unload() {
        interpreter?.close()
        interpreter = null
    }

    override fun close() = unload()

    @Synchronized
    private fun load(): Boolean {
        if (interpreter != null) return true
        if (!modelFile.isFile || modelFile.length() == 0L) return false
        interpreter = try {
            FileInputStream(modelFile).channel.use { ch ->
                Interpreter(ch.map(FileChannel.MapMode.READ_ONLY, 0, ch.size()), Interpreter.Options().apply { setNumThreads(3) })
            }
        } catch (e: Exception) {
            null
        }
        return interpreter != null
    }

    /** The best card in [frame] (source pixel coordinates) or null when none is visible. */
    @Synchronized
    fun detect(frame: Bitmap, scoreThreshold: Float = 0.35f): Detection? {
        if (!isAvailable) return null
        val tflite = interpreter ?: return null
        val inShape = tflite.getInputTensor(0).shape()          // [1, S, S, 3]
        val size = inShape[1]
        val letterbox = Letterbox(frame.width, frame.height, size)

        val boxed = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        Canvas(boxed).apply {
            drawColor(Color.rgb(114, 114, 114)) // the grey Ultralytics pads with
            drawBitmap(frame, null, RectF(letterbox.padX, letterbox.padY, letterbox.padX + letterbox.scaledWidth, letterbox.padY + letterbox.scaledHeight), paint)
        }
        val pixels = IntArray(size * size)
        boxed.getPixels(pixels, 0, size, 0, 0, size, size)
        boxed.recycle()

        val quantized = tflite.getInputTensor(0).dataType() == DataType.UINT8
        val input = ByteBuffer.allocateDirect(size * size * 3 * (if (quantized) 1 else 4)).order(ByteOrder.nativeOrder())
        for (p in pixels) {
            val r = (p shr 16) and 0xFF; val g = (p shr 8) and 0xFF; val b = p and 0xFF
            if (quantized) {
                input.put(r.toByte()); input.put(g.toByte()); input.put(b.toByte())
            } else {
                input.putFloat(r / 255f); input.putFloat(g / 255f); input.putFloat(b / 255f)
            }
        }
        input.rewind()

        val outShape = tflite.getOutputTensor(0).shape()
        var count = 1
        for (d in outShape) count *= d
        val outBuffer = ByteBuffer.allocateDirect(count * 4).order(ByteOrder.nativeOrder())
        tflite.run(input, outBuffer)
        outBuffer.rewind()
        val out = FloatArray(count)
        outBuffer.asFloatBuffer().get(out)

        val detections = YoloDecoder.decode(out, outShape, letterbox, scoreThreshold)
        // Prefer big, confident boxes: the card the user is pointing at, not a second card in the background.
        return detections.maxByOrNull { it.score * it.area }
    }
}

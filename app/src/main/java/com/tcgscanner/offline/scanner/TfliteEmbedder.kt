package com.tcgscanner.offline.scanner

import android.content.Context
import android.graphics.Bitmap
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.sqrt

/**
 * Optional on-device image classifier head (e.g. MobileNetV2 feature extractor) turning a card image into an
 * embedding vector. It activates only when `assets/models/card_embedder.tflite` is bundled; otherwise the
 * app transparently falls back to perceptual hashes. See docs/SCANNER_MODEL.md.
 */
class TfliteEmbedder(context: Context) {
    private val interpreter: Interpreter? = try {
        context.assets.openFd(MODEL_ASSET).use { fd ->
            FileInputStream(fd.fileDescriptor).channel.use { ch ->
                Interpreter(ch.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength), Interpreter.Options().apply { setNumThreads(2) })
            }
        }
    } catch (e: Exception) {
        null // model not bundled
    }

    val isAvailable: Boolean get() = interpreter != null

    @Synchronized
    fun embed(bitmap: Bitmap): FloatArray? {
        val it = interpreter ?: return null
        val shape = it.getInputTensor(0).shape() // [1, h, w, 3]
        val h = shape[1]
        val w = shape[2]
        val type = it.getInputTensor(0).dataType()
        val scaled = Bitmap.createScaledBitmap(VisualSignature.artCrop(bitmap), w, h, true)
        val pixels = IntArray(w * h)
        scaled.getPixels(pixels, 0, w, 0, 0, w, h)
        val input = ByteBuffer.allocateDirect(w * h * 3 * (if (type == DataType.FLOAT32) 4 else 1)).order(ByteOrder.nativeOrder())
        for (p in pixels) {
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            if (type == DataType.FLOAT32) {
                input.putFloat(r / 127.5f - 1f); input.putFloat(g / 127.5f - 1f); input.putFloat(b / 127.5f - 1f)
            } else {
                input.put(r.toByte()); input.put(g.toByte()); input.put(b.toByte())
            }
        }
        input.rewind()
        val outShape = it.getOutputTensor(0).shape()
        val n = outShape.last()
        val out = Array(1) { FloatArray(n) }
        it.run(input, out)
        val v = out[0]
        val norm = sqrt(v.sumOf { x -> (x * x).toDouble() }).toFloat().coerceAtLeast(1e-6f)
        for (i in v.indices) v[i] /= norm
        return v
    }

    companion object {
        const val MODEL_ASSET = "models/card_embedder.tflite"

        fun toBytes(v: FloatArray): ByteArray {
            val b = ByteBuffer.allocate(v.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            v.forEach { b.putFloat(it) }
            return b.array()
        }

        fun fromBytes(bytes: ByteArray): FloatArray {
            val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            return FloatArray(bytes.size / 4) { b.getFloat() }
        }

        fun cosine(a: FloatArray, b: FloatArray): Float {
            if (a.size != b.size) return 0f
            var s = 0f
            for (i in a.indices) s += a[i] * b[i]
            return s
        }
    }
}

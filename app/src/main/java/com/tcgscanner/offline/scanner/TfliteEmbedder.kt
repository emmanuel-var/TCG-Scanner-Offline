package com.tcgscanner.offline.scanner

import android.graphics.Bitmap
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.sqrt

/**
 * Phase 3 of the pipeline: optional on-device image embedder, EfficientNet-Lite0 feature vector (any [1,H,W,3] -> [1,D] TFLite model works). The model is NOT bundled in the
 * APK: it is downloaded on demand into filesDir ([EnginePacks.EMBEDDER_FILE]). Until it is there, everything
 * returns null and the scanner keeps working with OCR and perceptual hashes. See docs/SCANNER_MODEL.md.
 */
class TfliteEmbedder(private val modelFile: File) {
    @Volatile private var interpreter: Interpreter? = null

    val isAvailable: Boolean get() = interpreter != null || load()

    /** Drops the current interpreter and loads the file again; true when a usable model is in place. */
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

    @Synchronized
    private fun load(): Boolean {
        if (interpreter != null) return true
        if (!modelFile.isFile || modelFile.length() == 0L) return false
        interpreter = try {
            FileInputStream(modelFile).channel.use { ch ->
                Interpreter(ch.map(FileChannel.MapMode.READ_ONLY, 0, ch.size()), Interpreter.Options().apply { setNumThreads(2) })
            }
        } catch (e: Exception) {
            null // corrupt or incompatible file
        }
        return interpreter != null
    }

    @Synchronized
    fun embed(bitmap: Bitmap): FloatArray? {
        if (!isAvailable) return null
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
        val n = it.getOutputTensor(0).shape().last()
        val out = Array(1) { FloatArray(n) }
        it.run(input, out)
        val v = out[0]
        val norm = sqrt(v.sumOf { x -> (x * x).toDouble() }).toFloat().coerceAtLeast(1e-6f)
        for (i in v.indices) v[i] /= norm
        return v
    }

    companion object {
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

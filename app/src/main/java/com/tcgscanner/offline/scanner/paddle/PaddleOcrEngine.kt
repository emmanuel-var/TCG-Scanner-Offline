package com.tcgscanner.offline.scanner.paddle

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.graphics.Bitmap
import android.graphics.Matrix
import com.tcgscanner.offline.scanner.OcrEngine
import com.tcgscanner.offline.scanner.OcrLine
import com.tcgscanner.offline.scanner.pipeline.CardGeometry
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * PaddleOCR mobile (PP-OCR detector + recogniser) running on-device through ONNX Runtime Mobile.
 *
 * The three files of the OCR pack are plain downloads (see tools/model): `ocr_det.onnx` (DB text detector),
 * `ocr_rec.onnx` (CRNN/SVTR recogniser) and `ocr_dict.txt` (its character list). They are converted once from the
 * official PaddleOCR inference models with paddle2onnx; nothing is trained.
 *
 * Pipeline per card image: DB detector -> text boxes ([DbPostProcessor]) -> recogniser on every box -> CTC decode
 * ([CtcDecoder]). Both networks were trained on OpenCV images, hence the BGR channel order.
 */
class PaddleOcrEngine(
    private val detFile: File,
    private val recFile: File,
    private val dictFile: File
) : OcrEngine {
    override val name = "PaddleOCR"

    @Volatile private var det: OrtSession? = null
    @Volatile private var rec: OrtSession? = null
    @Volatile private var charset: PaddleCharset? = null
    private val env: OrtEnvironment by lazy { OrtEnvironment.getEnvironment() }

    val isAvailable: Boolean get() = (det != null && rec != null && charset != null) || load()

    @Synchronized
    fun reload(): Boolean {
        unload()
        return load()
    }

    @Synchronized
    fun unload() {
        det?.close(); rec?.close()
        det = null; rec = null; charset = null
    }

    override fun close() = unload()

    @Synchronized
    private fun load(): Boolean {
        if (det != null && rec != null && charset != null) return true
        if (!detFile.isFile || !recFile.isFile || !dictFile.isFile) return false
        return try {
            val options = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(2)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }
            val d = env.createSession(detFile.absolutePath, options)
            val r = env.createSession(recFile.absolutePath, options)
            charset = PaddleCharset(dictFile.readLines(Charsets.UTF_8))
            det = d; rec = r
            true
        } catch (e: Exception) {
            unload()
            false
        }
    }

    @Synchronized
    override fun recognize(card: Bitmap): List<OcrLine> {
        if (!isAvailable) return emptyList()
        val detector = det ?: return emptyList()
        val recogniser = rec ?: return emptyList()
        val chars = charset ?: return emptyList()

        val w = CardGeometry.CROP_WIDTH
        val h = CardGeometry.CROP_HEIGHT
        val image = if (card.width == w && card.height == h) card else Bitmap.createScaledBitmap(card, w, h, true)

        val boxes = try {
            DbPostProcessor.boxes(runDetector(detector, image, w, h), w, h)
        } catch (e: Exception) {
            return emptyList()
        }
        val lines = ArrayList<OcrLine>(boxes.size)
        for (box in boxes.take(MAX_BOXES)) {
            if (box.width < 4 || box.height < 4) continue
            val result = try { runRecogniser(recogniser, chars, crop(image, box)) } catch (e: Exception) { null } ?: continue
            if (result.text.isBlank() || result.confidence < MIN_CONFIDENCE) continue
            lines.add(OcrLine(result.text.trim(), (box.top + box.bottom) / 2f / h, box.height / h.toFloat()))
        }
        return lines
    }

    // ---- detector ---------------------------------------------------------------------------------------

    private fun runDetector(session: OrtSession, image: Bitmap, w: Int, h: Int): FloatArray {
        val px = IntArray(w * h)
        image.getPixels(px, 0, w, 0, 0, w, h)
        val data = FloatArray(3 * w * h)
        val plane = w * h
        for (i in px.indices) {
            val c = px[i]
            val b = (c and 0xFF) / 255f; val g = ((c shr 8) and 0xFF) / 255f; val r = ((c shr 16) and 0xFF) / 255f
            data[i] = (b - MEAN[0]) / STD[0]
            data[plane + i] = (g - MEAN[1]) / STD[1]
            data[2 * plane + i] = (r - MEAN[2]) / STD[2]
        }
        OnnxTensor.createTensor(env, FloatBuffer.wrap(data), longArrayOf(1, 3, h.toLong(), w.toLong())).use { input ->
            session.run(mapOf(session.inputNames.first() to input)).use { result ->
                val out = result[0] as OnnxTensor
                val fb = out.floatBuffer
                val prob = FloatArray(fb.remaining())
                fb.get(prob)
                return prob
            }
        }
    }

    // ---- recogniser -------------------------------------------------------------------------------------

    private fun crop(image: Bitmap, box: TextBox): Bitmap {
        val l = max(0, box.left); val t = max(0, box.top)
        val bm = Bitmap.createBitmap(image, l, t, min(image.width - l, box.width), min(image.height - t, box.height))
        // Tall regions are vertical text: turn them so the recogniser sees a horizontal line.
        return if (bm.height >= bm.width * 1.5f) Bitmap.createBitmap(bm, 0, 0, bm.width, bm.height, Matrix().apply { postRotate(-90f) }, true) else bm
    }

    private fun runRecogniser(session: OrtSession, chars: PaddleCharset, line: Bitmap): CtcDecoder.Result? {
        val targetH = REC_HEIGHT
        val targetW = ceil(targetH * line.width / line.height.toFloat()).toInt().coerceIn(16, MAX_REC_WIDTH)
        val scaled = Bitmap.createScaledBitmap(line, targetW, targetH, true)
        val px = IntArray(targetW * targetH)
        scaled.getPixels(px, 0, targetW, 0, 0, targetW, targetH)
        val data = FloatArray(3 * targetW * targetH)
        val plane = targetW * targetH
        for (i in px.indices) {
            val c = px[i]
            data[i] = ((c and 0xFF) / 255f - 0.5f) / 0.5f
            data[plane + i] = (((c shr 8) and 0xFF) / 255f - 0.5f) / 0.5f
            data[2 * plane + i] = (((c shr 16) and 0xFF) / 255f - 0.5f) / 0.5f
        }
        OnnxTensor.createTensor(env, FloatBuffer.wrap(data), longArrayOf(1, 3, targetH.toLong(), targetW.toLong())).use { input ->
            session.run(mapOf(session.inputNames.first() to input)).use { result ->
                val out = result[0] as OnnxTensor
                val shape = out.info.shape                  // [1, steps, classes]
                val fb = out.floatBuffer
                val logits = FloatArray(fb.remaining())
                fb.get(logits)
                if (shape.size != 3) return null
                return CtcDecoder.decode(logits, shape[1].toInt(), shape[2].toInt(), chars)
            }
        }
    }

    private companion object {
        val MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
        val STD = floatArrayOf(0.229f, 0.224f, 0.225f)
        const val REC_HEIGHT = 48
        const val MAX_REC_WIDTH = 640
        const val MAX_BOXES = 24
        const val MIN_CONFIDENCE = 0.5f
    }
}

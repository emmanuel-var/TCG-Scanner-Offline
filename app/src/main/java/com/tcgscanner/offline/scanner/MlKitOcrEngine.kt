package com.tcgscanner.offline.scanner

import android.graphics.Bitmap
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognizer

/** Google ML Kit text recognition: bundled with the app, so it works before any download. */
class MlKitOcrEngine(private val recognizer: TextRecognizer) : OcrEngine {
    override val name = "ML Kit"

    override fun recognize(card: Bitmap): List<OcrLine> {
        val text = try {
            Tasks.await(recognizer.process(InputImage.fromBitmap(card, 0)))
        } catch (e: Exception) {
            return emptyList()
        }
        val h = card.height.toFloat()
        return text.textBlocks.flatMap { it.lines }.mapNotNull { line ->
            val box = line.boundingBox ?: return@mapNotNull null
            OcrLine(line.text, box.centerY() / h, box.height() / h)
        }
    }

    override fun close() = recognizer.close()
}

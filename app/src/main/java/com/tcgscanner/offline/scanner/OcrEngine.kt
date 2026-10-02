package com.tcgscanner.offline.scanner

import android.graphics.Bitmap

/**
 * Phase 2: reads the text of a RECTIFIED card image. Implementations block, so call them off the main thread
 * (the camera analyzer already runs on its own executor).
 */
interface OcrEngine {
    val name: String

    /** Lines with positions normalised to the card image. Empty when nothing was read. */
    fun recognize(card: Bitmap): List<OcrLine>

    fun close() {}
}

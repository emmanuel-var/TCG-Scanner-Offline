package com.tcgscanner.offline.scanner.pipeline

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Color

/** Warps the four corners of a card in the camera frame onto an upright rectangle (perspective correction). */
object PerspectiveCropper {
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    fun crop(frame: Bitmap, quad: Quad, outWidth: Int = CardGeometry.CROP_WIDTH, outHeight: Int = CardGeometry.CROP_HEIGHT): Bitmap {
        val out = Bitmap.createBitmap(outWidth, outHeight, Bitmap.Config.ARGB_8888)
        val src = floatArrayOf(quad.tl.x, quad.tl.y, quad.tr.x, quad.tr.y, quad.br.x, quad.br.y, quad.bl.x, quad.bl.y)
        val dst = floatArrayOf(0f, 0f, outWidth.toFloat(), 0f, outWidth.toFloat(), outHeight.toFloat(), 0f, outHeight.toFloat())
        val matrix = Matrix()
        if (!matrix.setPolyToPoly(src, 0, dst, 0, 4)) {
            // Degenerate quad: just scale the bounding box.
            val l = src.filterIndexed { i, _ -> i % 2 == 0 }.min(); val t = src.filterIndexed { i, _ -> i % 2 == 1 }.min()
            val r = src.filterIndexed { i, _ -> i % 2 == 0 }.max(); val b = src.filterIndexed { i, _ -> i % 2 == 1 }.max()
            matrix.setRectToRect(android.graphics.RectF(l, t, r, b), android.graphics.RectF(0f, 0f, outWidth.toFloat(), outHeight.toFloat()), Matrix.ScaleToFit.FILL)
        }
        Canvas(out).apply {
            drawColor(Color.BLACK)
            drawBitmap(frame, matrix, paint)
        }
        return out
    }

    /** Luminance 0..255 of [bitmap], row-major, for [QuadRefiner]. */
    fun luminance(bitmap: Bitmap): IntArray {
        val w = bitmap.width; val h = bitmap.height
        val px = IntArray(w * h)
        bitmap.getPixels(px, 0, w, 0, 0, w, h)
        return IntArray(px.size) { i ->
            val c = px[i]
            (((c shr 16) and 0xFF) * 299 + ((c shr 8) and 0xFF) * 587 + (c and 0xFF) * 114) / 1000
        }
    }
}

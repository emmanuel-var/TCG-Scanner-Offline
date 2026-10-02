package com.tcgscanner.offline

import com.tcgscanner.offline.scanner.VectorIndex
import com.tcgscanner.offline.scanner.paddle.CtcDecoder
import com.tcgscanner.offline.scanner.paddle.DbPostProcessor
import com.tcgscanner.offline.scanner.paddle.PaddleCharset
import com.tcgscanner.offline.scanner.pipeline.CardGeometry
import com.tcgscanner.offline.scanner.pipeline.Detection
import com.tcgscanner.offline.scanner.pipeline.Letterbox
import com.tcgscanner.offline.scanner.pipeline.Pt
import com.tcgscanner.offline.scanner.pipeline.Quad
import com.tcgscanner.offline.scanner.pipeline.QuadRefiner
import com.tcgscanner.offline.scanner.pipeline.YoloDecoder
import com.tcgscanner.offline.core.Text
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

class PipelineMathTest {
    // ---- geometry ------------------------------------------------------------------------------------------

    @Test fun ordersShuffledCorners() {
        val q = CardGeometry.orderCorners(listOf(Pt(100f, 140f), Pt(0f, 0f), Pt(100f, 0f), Pt(0f, 140f)))
        assertEquals(Pt(0f, 0f), q.tl); assertEquals(Pt(100f, 0f), q.tr); assertEquals(Pt(100f, 140f), q.br); assertEquals(Pt(0f, 140f), q.bl)
    }

    @Test fun recognisesCardProportions() {
        assertTrue(CardGeometry.isCardShaped(CardGeometry.quadOfBox(0f, 0f, 63f * 4, 88f * 4)))
        assertTrue(CardGeometry.isCardShaped(CardGeometry.quadOfBox(0f, 0f, 88f * 4, 63f * 4)))   // on its side
        assertFalse(CardGeometry.isCardShaped(CardGeometry.quadOfBox(0f, 0f, 300f, 300f)))        // square
        assertFalse(CardGeometry.isCardShaped(CardGeometry.quadOfBox(0f, 0f, 50f, 400f)))         // sliver
    }

    @Test fun landscapeQuadBecomesPortrait() {
        val landscape = CardGeometry.quadOfBox(0f, 0f, 352f, 252f)
        val p = CardGeometry.portrait(landscape)
        assertTrue(p.height > p.width)
        assertEquals(CardGeometry.CARD_ASPECT, p.aspect, 0.01f)
    }

    @Test fun hullAndCornersOfARotatedRectangle() {
        val angle = Math.toRadians(20.0)
        fun rot(x: Float, y: Float) = Pt((x * cos(angle) - y * sin(angle)).toFloat() + 200f, (x * sin(angle) + y * cos(angle)).toFloat() + 200f)
        val corners = listOf(rot(0f, 0f), rot(126f, 0f), rot(126f, 176f), rot(0f, 176f))
        val cloud = ArrayList<Pt>()
        for (i in 0..20) for (j in 0..20) cloud.add(rot(126f * i / 20, 176f * j / 20)) // dense interior + border
        val q = CardGeometry.cornersOf(cloud)!!
        assertEquals(CardGeometry.CARD_ASPECT, q.aspect, 0.02f)
        // Each true corner has an estimated corner within 2 px.
        corners.forEach { c -> assertTrue(q.points.any { abs(it.x - c.x) < 2f && abs(it.y - c.y) < 2f }) }
    }

    @Test fun quadRefinerFindsTheCardInsideADetectorBox() {
        val w = 240; val h = 320
        val gray = IntArray(w * h) { 30 }                      // dark table
        val angle = Math.toRadians(12.0)
        val cx = 120.0; val cy = 160.0; val halfW = 63.0; val halfH = 88.0
        for (y in 0 until h) for (x in 0 until w) {                 // bright card, rotated 12 degrees
            val dx = x - cx; val dy = y - cy
            val u = dx * cos(angle) + dy * sin(angle); val v = -dx * sin(angle) + dy * cos(angle)
            if (abs(u) <= halfW && abs(v) <= halfH) gray[y * w + x] = 200 + ((x / 6 + y / 6) % 3) * 10
        }
        val q = QuadRefiner.refine(gray, w, h, floatArrayOf(10f, 10f, w - 10f, h - 10f))
        assertNotNull(q)
        val p = CardGeometry.portrait(q!!)
        assertEquals(CardGeometry.CARD_ASPECT, p.aspect, 0.05f)
        val expectedTl = Pt((cx + (-halfW) * cos(angle) - (-halfH) * sin(angle)).toFloat(), (cy + (-halfW) * sin(angle) + (-halfH) * cos(angle)).toFloat())
        assertTrue(q.points.any { abs(it.x - expectedTl.x) < 5f && abs(it.y - expectedTl.y) < 5f })
    }

    @Test fun quadRefinerRejectsNoise() {
        val rnd = java.util.Random(3)
        val gray = IntArray(200 * 200) { rnd.nextInt(256) }
        // Random speckle has no card-shaped hull of strong edges that is not just the box; must not crash.
        QuadRefiner.refine(gray, 200, 200, floatArrayOf(5f, 5f, 195f, 195f))
    }

    // ---- YOLO decoding ---------------------------------------------------------------------------------------

    private fun channelsFirst(anchors: Int, vararg boxes: FloatArray): FloatArray {
        val out = FloatArray(5 * anchors)           // [cx, cy, w, h, score] x anchors
        boxes.forEachIndexed { i, b -> for (c in 0 until 5) out[c * anchors + i] = b[c] }
        return out
    }

    @Test fun decodesNormalisedChannelsFirstOutputThroughLetterbox() {
        // Frame 1080x1920 letterboxed into 640: scale 1/3, pad x = 140. Card box centred at frame (540, 960), 600x840.
        val lb = Letterbox(1080, 1920, 640)
        val cx = (540f * lb.scale + lb.padX) / 640f; val cy = (960f * lb.scale + lb.padY) / 640f
        val w = 600f * lb.scale / 640f; val h = 840f * lb.scale / 640f
        val out = channelsFirst(100, floatArrayOf(cx, cy, w, h, 0.9f), floatArrayOf(0.1f, 0.1f, 0.05f, 0.05f, 0.1f))
        val d = YoloDecoder.decode(out, intArrayOf(1, 5, 100), lb)
        assertEquals(1, d.size)
        assertEquals(240f, d[0].left, 1.5f); assertEquals(840f, d[0].right, 1.5f)
        assertEquals(540f, d[0].top, 1.5f); assertEquals(1380f, d[0].bottom, 1.5f)
    }

    @Test fun decodesPixelUnitTransposedOutput() {
        val lb = Letterbox(640, 640, 640)
        val anchors = 50
        val out = FloatArray(anchors * 5)                    // [anchors, 5]
        out[7 * 5 + 0] = 320f; out[7 * 5 + 1] = 320f; out[7 * 5 + 2] = 200f; out[7 * 5 + 3] = 280f; out[7 * 5 + 4] = 0.8f
        val d = YoloDecoder.decode(out, intArrayOf(1, anchors, 5), lb)
        assertEquals(1, d.size); assertEquals(220f, d[0].left, 0.5f); assertEquals(180f, d[0].top, 0.5f)
    }

    @Test fun nonMaxSuppressionKeepsTheBestOfOverlappingBoxes() {
        val a = Detection(0f, 0f, 100f, 140f, 0.9f)
        val b = Detection(5f, 5f, 105f, 145f, 0.6f)
        val c = Detection(300f, 300f, 400f, 440f, 0.7f)
        val kept = YoloDecoder.nms(listOf(b, c, a), 0.45f)
        assertEquals(listOf(a, c), kept)
    }

    @Test fun nothingBelowTheThresholdIsReturned() {
        val out = channelsFirst(20, floatArrayOf(0.5f, 0.5f, 0.3f, 0.4f, 0.2f))
        assertTrue(YoloDecoder.decode(out, intArrayOf(1, 5, 20), Letterbox(640, 640, 640)).isEmpty())
    }

    // ---- PaddleOCR post-processing -----------------------------------------------------------------------------

    @Test fun dbPostProcessorFindsTextBlobsInReadingOrder() {
        val w = 120; val h = 80
        val prob = FloatArray(w * h)
        fun blob(x0: Int, y0: Int, x1: Int, y1: Int) { for (y in y0..y1) for (x in x0..x1) prob[y * w + x] = 0.9f }
        blob(60, 50, 100, 60)   // second line, right
        blob(10, 10, 50, 20)    // first line
        blob(60, 10, 100, 20)   // first line, right of the other
        blob(5, 70, 6, 71)      // speck, too small
        val boxes = DbPostProcessor.boxes(prob, w, h)
        assertEquals(3, boxes.size)
        assertTrue(boxes[0].left < boxes[1].left && boxes[0].top <= boxes[2].top)
        assertTrue(boxes[0].left <= 10 && boxes[0].right >= 50)    // unclip expanded the box
    }

    @Test fun ctcGreedyDecodeCollapsesRepeatsAndBlanks() {
        val charset = PaddleCharset(listOf("O", "P", "0", "1", "-"))      // indices: 0 blank, 1 O, 2 P, 3 '0', 4 '1', 5 '-', 6 ' '
        val seq = intArrayOf(1, 1, 0, 2, 2, 3, 0, 4, 5, 0, 5)           // O O _ P P 0 _ 1 - _ -   -> "OP01--"
        val classes = charset.size
        val logits = FloatArray(seq.size * classes)
        seq.forEachIndexed { t, c -> logits[t * classes + c] = 0.9f }
        val r = CtcDecoder.decode(logits, seq.size, classes, charset)
        assertEquals("OP01--", r.text)
        assertEquals(0.9f, r.confidence, 1e-6f)
    }

    // ---- RAM vector index -------------------------------------------------------------------------------------

    @Test fun cosineTopKOverTheInMemoryIndex() {
        val index = VectorIndex.build(
            listOf(
                "a" to floatArrayOf(1f, 0f, 0f), "b" to floatArrayOf(0f, 1f, 0f),
                "c" to floatArrayOf(0.9f, 0.1f, 0f), "d" to floatArrayOf(0f, 0f, 5f)
            )
        )
        val hits = index.topK(floatArrayOf(2f, 0.1f, 0f), 2)
        assertEquals(listOf("a", "c"), hits.map { it.id })
        assertTrue(hits[0].score > hits[1].score && hits[0].score <= 1.0001f)
    }

    @Test fun indexIgnoresVectorsOfAnotherModelAndMismatchedQueries() {
        val index = VectorIndex.build(listOf("a" to floatArrayOf(1f, 0f), "b" to floatArrayOf(0f, 1f), "old" to floatArrayOf(1f, 0f, 0f)))
        assertEquals(2, index.size)
        assertTrue(index.topK(floatArrayOf(1f, 0f, 0f), 3).isEmpty())
        assertTrue(VectorIndex.EMPTY.topK(floatArrayOf(1f), 1).isEmpty())
    }

    @Test fun indexScalesToTwentyThousandCardsQuickly() {
        val rnd = java.util.Random(1)
        val entries = (0 until 20_000).map { "c$it" to FloatArray(256) { rnd.nextFloat() - 0.5f } }
        val index = VectorIndex.build(entries)
        val start = System.nanoTime()
        val hit = index.topK(entries[1234].second, 1).single()
        val ms = (System.nanoTime() - start) / 1e6
        assertEquals("c1234", hit.id)
        assertTrue("took $ms ms", ms < 1500)   // generous: CI machines vary; typical is well under 100 ms
    }

    // ---- index range scans -------------------------------------------------------------------------------------

    @Test fun prefixUpperBoundBracketsAllPrefixedNames() {
        val lo = "pikachu"; val hi = Text.prefixUpperBound(lo)
        listOf("pikachu", "pikachuex", "pikachuvmax").forEach { assertTrue(it >= lo && it < hi) }
        listOf("pikachs", "pikachv", "pikacht").forEach { assertTrue(!(it >= lo && it < hi)) }
    }
}

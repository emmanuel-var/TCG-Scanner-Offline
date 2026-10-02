@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class
)

package com.tcgscanner.offline.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

data class ChartPoint(val timeMs: Long, val value: Double)

/**
 * Locally drawn line chart (no chart library, no network). Touch or drag to scrub: [onSelect] reports the
 * point under the finger (or null when released) so the screen can show its value and date.
 */
@Composable
fun ValueChart(
    points: List<ChartPoint>,
    lineColor: Color,
    gridColor: Color,
    description: String,
    modifier: Modifier = Modifier,
    onSelect: (ChartPoint?) -> Unit = {}
) {
    var selected by remember(points) { mutableIntStateOf(-1) }

    fun indexAt(x: Float, width: Float): Int {
        if (points.size < 2 || width <= 0f) return 0
        val t0 = points.first().timeMs
        val span = (points.last().timeMs - t0).coerceAtLeast(1L)
        val target = t0 + (x / width).coerceIn(0f, 1f) * span
        return points.indices.minByOrNull { kotlin.math.abs(points[it].timeMs - target) } ?: 0
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(200.dp)
            .semantics { contentDescription = description }
            .pointerInput(points) {
                detectTapGestures(
                    onPress = { offset ->
                        selected = indexAt(offset.x, size.width.toFloat())
                        onSelect(points.getOrNull(selected))
                        tryAwaitRelease()
                        selected = -1
                        onSelect(null)
                    }
                )
            }
            .pointerInput(points) {
                detectHorizontalDragGestures(
                    onDragStart = { o -> selected = indexAt(o.x, size.width.toFloat()); onSelect(points.getOrNull(selected)) },
                    onDragEnd = { selected = -1; onSelect(null) },
                    onDragCancel = { selected = -1; onSelect(null) },
                    onHorizontalDrag = { change, _ ->
                        selected = indexAt(change.position.x, size.width.toFloat())
                        onSelect(points.getOrNull(selected))
                    }
                )
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val padTop = 12.dp.toPx()
            val padBottom = 12.dp.toPx()
            // Light horizontal grid
            repeat(4) { i ->
                val y = padTop + (h - padTop - padBottom) * i / 3f
                drawLine(gridColor, Offset(0f, y), Offset(w, y), strokeWidth = 1.dp.toPx())
            }
            if (points.isEmpty()) return@Canvas
            val minV = points.minOf { it.value }
            val maxV = points.maxOf { it.value }
            val range = (maxV - minV).takeIf { it > 1e-9 } ?: 1.0
            val t0 = points.first().timeMs
            val span = (points.last().timeMs - t0).coerceAtLeast(1L)

            fun px(p: ChartPoint): Offset {
                val x = if (points.size == 1) w / 2f else ((p.timeMs - t0).toFloat() / span) * w
                val y = padTop + (h - padTop - padBottom) * (1f - ((p.value - minV) / range).toFloat())
                return Offset(x, y)
            }

            if (points.size == 1) {
                drawCircle(lineColor, 5.dp.toPx(), px(points[0]))
                return@Canvas
            }
            val line = Path()
            val area = Path()
            points.forEachIndexed { i, p ->
                val o = px(p)
                if (i == 0) { line.moveTo(o.x, o.y); area.moveTo(o.x, h); area.lineTo(o.x, o.y) } else { line.lineTo(o.x, o.y); area.lineTo(o.x, o.y) }
            }
            area.lineTo(px(points.last()).x, h)
            area.close()
            drawPath(area, Brush.verticalGradient(listOf(lineColor.copy(alpha = 0.30f), lineColor.copy(alpha = 0f))))
            drawPath(line, lineColor, style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))

            val sel = points.getOrNull(selected)
            if (sel != null) {
                val o = px(sel)
                drawLine(lineColor.copy(alpha = 0.6f), Offset(o.x, 0f), Offset(o.x, h), strokeWidth = 1.dp.toPx())
                drawCircle(lineColor, 6.dp.toPx(), o)
                drawCircle(Color.White, 2.5.dp.toPx(), o)
            } else {
                drawCircle(lineColor, 4.dp.toPx(), px(points.last()))
            }
        }
    }
}

package com.goosepaware.biliaudio.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/**
 * 波形 + 可拖分割点。
 */
@Composable
fun WaveformCanvas(
  peaks: List<Pair<Float, Float>>,
  markers: List<Double>,
  duration: Double,
  editable: Boolean,
  onMarkersChange: (List<Double>) -> Unit,
  modifier: Modifier = Modifier,
) {
  val waveColor = MaterialTheme.colorScheme.primary
  val markerColor = MaterialTheme.colorScheme.error
  val bg = MaterialTheme.colorScheme.surfaceVariant
  var dragIndex by remember { mutableIntStateOf(-1) }
  var widthPx by remember { mutableIntStateOf(1) }

  fun xOf(t: Double): Float = if (duration <= 0) 0f else (t / duration).toFloat() * widthPx

  fun nearestIndex(x: Float): Int {
    if (markers.isEmpty()) return -1
    var best = -1
    var bestDist = 40f
    markers.forEachIndexed { i, t ->
      val d = abs(xOf(t) - x)
      if (d < bestDist) {
        bestDist = d
        best = i
      }
    }
    return best
  }

  fun moveTo(index: Int, x: Float) {
    if (duration <= 0) return
    val t = (x / widthPx).coerceIn(0f, 1f) * duration
    if (t < 0.5 || t > duration - 0.5) return
    val list = markers.toMutableList()
    list[index] = t
    onMarkersChange(list.sorted())
  }

  Canvas(
    modifier
      .fillMaxWidth()
      .height(120.dp)
      .pointerInput(markers, duration, editable) {
        if (!editable) return@pointerInput
        detectDragGestures(
          onDragStart = { offset -> dragIndex = nearestIndex(offset.x) },
          onDragEnd = { dragIndex = -1 },
          onDragCancel = { dragIndex = -1 },
          onDrag = { change, _ ->
            change.consume()
            if (dragIndex >= 0) moveTo(dragIndex, change.position.x)
          },
        )
      }
      .pointerInput(markers, duration, editable) {
        detectTapGestures { offset ->
          if (!editable || duration <= 0) return@detectTapGestures
          val hit = nearestIndex(offset.x)
          if (hit >= 0) return@detectTapGestures
          val t = (offset.x / widthPx).coerceIn(0f, 1f) * duration
          if (t > 0.5 && t < duration - 0.5) onMarkersChange((markers + t).sorted())
        }
      },
  ) {
    widthPx = size.width.toInt().coerceAtLeast(1)
    drawRect(bg)

    if (peaks.isNotEmpty()) {
      val mid = size.height / 2
      val step = size.width / peaks.size
      val bar = maxOf(1f, step * 0.8f)
      peaks.forEachIndexed { i, (lo, hi) ->
        val x = i * step + (step - bar) / 2
        val top = mid - hi * mid * 0.95f
        val bottom = mid - lo * mid * 0.95f
        drawRect(
          color = waveColor,
          topLeft = Offset(x, top),
          size = androidx.compose.ui.geometry.Size(bar, maxOf(1f, bottom - top)),
        )
      }
      drawLine(
        color = waveColor.copy(alpha = 0.35f),
        start = Offset(0f, mid),
        end = Offset(size.width, mid),
        strokeWidth = 1f,
      )
    }

    markers.forEach { t ->
      val x = xOf(t)
      drawLine(markerColor, Offset(x, 0f), Offset(x, size.height), strokeWidth = 2.5f)
      drawCircle(markerColor, radius = 5f, center = Offset(x, 8f), style = Stroke(2f))
    }
  }
}

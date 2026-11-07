package com.goosepaware.biliaudio.ui.ipod

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import kotlinx.coroutines.delay
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 单色像素屏跑马灯：只按字体像素步进，约 8fps，不做 60fps 平滑滑动。
 */
@Composable
fun PixelMarqueeText(
  text: String,
  style: TextStyle,
  modifier: Modifier = Modifier,
  stepMillis: Long = 120,
  initialDelayMillis: Long = 1200,
  endPauseMillis: Long = 1500,
) {
  val density = LocalDensity.current
  val measurer = rememberTextMeasurer()
  val layout = remember(text, style) {
    measurer.measure(
      text = AnnotatedString(text),
      style = style,
      maxLines = 1,
      softWrap = false,
    )
  }
  val textWidth = layout.size.width
  val textHeightDp = with(density) { layout.size.height.toDp() }
  val stepPx = max(1, (with(density) { style.fontSize.toPx() } / 12f).roundToInt())
  val gapPx = stepPx * 8
  var offset by remember(text) { mutableIntStateOf(0) }

  BoxWithConstraints(
    modifier
      .fillMaxWidth()
      .height(textHeightDp)
      .clipToBounds(),
  ) {
    val widthPx = with(density) { maxWidth.toPx() }.toInt()
    LaunchedEffect(text, widthPx, textWidth) {
      offset = 0
      if (textWidth <= widthPx || widthPx <= 0) return@LaunchedEffect
      delay(initialDelayMillis)
      val maxOffset = textWidth + gapPx
      while (true) {
        if (offset >= maxOffset) {
          delay(endPauseMillis)
          offset = 0
          delay(initialDelayMillis)
        } else {
          delay(stepMillis)
          offset = (offset + stepPx).coerceAtMost(maxOffset)
        }
      }
    }

    Canvas(Modifier.fillMaxWidth().height(textHeightDp)) {
      val y = 0f
      if (textWidth <= size.width.toInt()) {
        drawText(
          textLayoutResult = layout,
          topLeft = Offset((size.width - textWidth) / 2f, y),
        )
      } else {
        drawText(
          textLayoutResult = layout,
          topLeft = Offset(-offset.toFloat(), y),
        )
      }
    }
  }
}

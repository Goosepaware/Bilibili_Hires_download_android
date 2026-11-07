package com.goosepaware.biliaudio.ui.ipod

import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.goosepaware.biliaudio.R
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * iPod 滚轮：贴图来自用户提供的实物照片。
 *
 * 触摸区域：
 * - 中心键：选择
 * - 内圈：拖动滚动（点按不触发按钮）
 * - 外圈：四个方向键（MENU / 上一首 / 下一首 / 播放暂停）
 */
@Composable
fun ClickWheel(
  wheelSize: Dp,
  onUp: () -> Unit,
  onDown: () -> Unit,
  onMenu: () -> Unit,
  onPrev: () -> Unit,
  onNext: () -> Unit,
  onPlayPause: () -> Unit,
  onSelect: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val haptic = LocalHapticFeedback.current
  val density = LocalDensity.current
  val sizePx = with(density) { wheelSize.toPx() }
  val centerPx = sizePx * 0.138f
  val outerPx = sizePx * 0.33f
  val radiusPx = sizePx / 2f
  val touchSlop = with(density) { 8.dp.toPx() }

  val onUpNow by rememberUpdatedState(onUp)
  val onDownNow by rememberUpdatedState(onDown)
  val onMenuNow by rememberUpdatedState(onMenu)
  val onPrevNow by rememberUpdatedState(onPrev)
  val onNextNow by rememberUpdatedState(onNext)
  val onPlayPauseNow by rememberUpdatedState(onPlayPause)
  val onSelectNow by rememberUpdatedState(onSelect)

  fun angleOf(p: Offset, center: Offset): Float =
    (atan2(p.y - center.y, p.x - center.x) * 180f / PI.toFloat() + 360f) % 360f

  Box(
    modifier = modifier.size(wheelSize).clip(CircleShape),
    contentAlignment = Alignment.Center,
  ) {
    Image(
      painter = painterResource(R.drawable.ipod_wheel),
      contentDescription = "iPod 滚轮",
      contentScale = ContentScale.FillBounds,
      modifier = Modifier.fillMaxSize(),
    )

    Box(
      Modifier
        .fillMaxSize()
        .pointerInput(sizePx) {
          val center = Offset(sizePx / 2f, sizePx / 2f)
          awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            down.consume()
            var lastAngle = angleOf(down.position, center)
            var accumulated = 0f
            var moved = false
            while (true) {
              val event = awaitPointerEvent()
              val change = event.changes.firstOrNull { it.id == down.id } ?: break
              if (change.pressed) {
                change.consume()
                if (hypot(
                    change.position.x - down.position.x,
                    change.position.y - down.position.y,
                  ) > touchSlop
                ) moved = true
                val r = hypot(change.position.x - center.x, change.position.y - center.y)
                if (r > centerPx * 1.05f) {
                  val a = angleOf(change.position, center)
                  var delta = a - lastAngle
                  if (delta > 180f) delta -= 360f
                  if (delta < -180f) delta += 360f
                  accumulated += delta
                  val step = 13f
                  while (accumulated > step) {
                    onDownNow()
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    accumulated -= step
                  }
                  while (accumulated < -step) {
                    onUpNow()
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    accumulated += step
                  }
                }
                lastAngle = angleOf(change.position, center)
              } else {
                change.consume()
                if (!moved) {
                  val p = change.position
                  val r = hypot(p.x - center.x, p.y - center.y)
                  if (r <= centerPx) {
                    onSelectNow()
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                  } else if (r <= radiusPx && r >= outerPx) {
                    val a = angleOf(p, center)
                    when {
                      a >= 240f && a < 300f -> onMenuNow()
                      a >= 120f && a < 240f -> onPrevNow()
                      a < 60f || a >= 300f -> onNextNow()
                      else -> onPlayPauseNow()
                    }
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                  }
                }
                break
              }
            }
          }
        },
    )
  }
}

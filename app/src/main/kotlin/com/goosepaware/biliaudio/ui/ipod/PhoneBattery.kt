package com.goosepaware.biliaudio.ui.ipod

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import kotlin.math.ceil

private fun batteryLevelFrom(intent: Intent?): Int? {
  val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
  val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
  if (level < 0 || scale <= 0) return null
  return (level * 100 / scale).coerceIn(0, 100)
}

@Composable
fun rememberPhoneBatteryLevel(): Int {
  val context = LocalContext.current
  var level by remember { mutableIntStateOf(100) }
  DisposableEffect(context) {
    val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
    val receiver = object : BroadcastReceiver() {
      override fun onReceive(c: Context?, intent: Intent?) {
        batteryLevelFrom(intent)?.let { level = it }
      }
    }
    val sticky = ContextCompat.registerReceiver(
      context,
      receiver,
      filter,
      ContextCompat.RECEIVER_NOT_EXPORTED,
    )
    batteryLevelFrom(sticky)?.let { level = it }
    onDispose {
      runCatching { context.unregisterReceiver(receiver) }
    }
  }
  return level
}

/** 参考原版 iPod 的四格电池：边框 + 4 个竖格 + 右侧凸点。 */
@Composable
fun PhoneBattery(
  level: Int,
  modifier: Modifier = Modifier,
  strokePx: Float,
) {
  val ink = IpodColors.screenInk
  Canvas(modifier) {
    val stroke = strokePx.coerceAtLeast(1f)
    val nubW = size.width * 0.065f
    val bodyW = (size.width - nubW - stroke * 0.4f).coerceAtLeast(stroke * 4)
    val bodyH = size.height

    drawRect(
      color = ink,
      topLeft = Offset(stroke / 2f, stroke / 2f),
      size = Size((bodyW - stroke).coerceAtLeast(stroke * 2), (bodyH - stroke).coerceAtLeast(stroke * 2)),
      style = Stroke(width = stroke),
    )
    drawRect(
      color = ink,
      topLeft = Offset(bodyW + stroke * 0.1f, bodyH * 0.32f),
      size = Size(nubW, bodyH * 0.36f),
    )

    val pad = stroke * 2.2f
    val innerW = (bodyW - pad * 2).coerceAtLeast(stroke * 4)
    val innerH = (bodyH - pad * 2).coerceAtLeast(stroke * 2)
    val gap = stroke
    val segW = ((innerW - gap * 3) / 4f).coerceAtLeast(stroke)
    val filled = ceil(level / 25f).toInt().coerceIn(0, 4)
    for (i in 0 until 4) {
      val x = pad + i * (segW + gap)
      drawRect(
        color = if (i < filled) ink else ink.copy(alpha = 0.10f),
        topLeft = Offset(x, pad),
        size = Size(segW, innerH),
      )
    }
  }
}

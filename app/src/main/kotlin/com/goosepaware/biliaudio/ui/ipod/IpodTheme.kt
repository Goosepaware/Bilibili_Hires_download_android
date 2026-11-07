package com.goosepaware.biliaudio.ui.ipod

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.goosepaware.biliaudio.R

val IpodFont = FontFamily(Font(R.font.fusion_pixel_12, FontWeight.Normal))

object IpodColors {
  val bodyGradient1 = Color(0xFFD4D4D4)
  val bodyGradient2 = Color(0xFFFEFEFE)
  val bodyEdge = Color(0xFFB9B9B9)

  val screenGradient1 = Color(0xFFA5A59B)
  val screenGradient2 = Color(0xFFD6D5D0)
  val screenPaper = Color(0xFFC9C8C2)
  val screenFrame1 = Color(0xFF6B6A66)
  val screenFrame2 = Color(0xFF474643)
  val screenFrameEdge = Color(0xFF2F2E2C)
  val progressTrack = Color(0xFFD2D1CB)
  val progressBorder = Color(0xFF7A7874)
  val bodyRim = Color(0xFFB8B8B8)
  val screenInk = Color(0xFF484647)
  val screenInkInverse = Color(0xFFAFAEA7)
  val screenSelected = Color(0xFF484647)
  val screenScrim = Color(0x1A484647)

  val backlightGlow = Color(0xFF0F69FF)

  val wheel1 = Color(0xFFEAEAEA)
  val wheel2 = Color(0xFFF6F6F6)
  val wheelEdge = Color(0xFF7E7E7E)
  val wheelDivider = Color(0xFF888888)

  val bezelBackground = Color(0xFF0B0C0E)
  val darkBody1 = Color(0xFF2A2A2C)
  val darkBody2 = Color(0xFF4A4A4C)
}

fun ipodTextStyle(size: Float, bold: Boolean = false): TextStyle = TextStyle(
  fontFamily = IpodFont,
  fontSize = size.sp,
  fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
  color = IpodColors.screenInk,
  lineHeight = (size * 1.5f).sp,
)

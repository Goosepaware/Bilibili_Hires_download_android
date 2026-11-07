package com.goosepaware.biliaudio.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class AnalyzerTest {

  private fun tones(sr: Int, seconds: Int, freqs: List<Double>): ShortArray {
    val n = sr * seconds
    val out = ShortArray(n)
    for (i in 0 until n) {
      var v = 0.0
      for (f in freqs) v += sin(2 * PI * f * i / sr)
      v /= freqs.size
      out[i] = (v * 20000).toInt().toShort()
    }
    return out
  }

  @Test
  fun cutoffFollowsHighestTone() {
    val sr = 48000
    val samples = tones(sr, 4, listOf(1000.0, 5000.0, 10000.0, 15000.0))
    val power = Analyzer.powerOf(samples)
    val (cutoff, _) = Analyzer.cutoff(power, sr / 2)
    assertTrue("cutoff=$cutoff", cutoff in 13000.0..17500.0)
  }

  @Test
  fun wideBandReachesNyquist() {
    val sr = 48000
    val samples = tones(sr, 4, listOf(1000.0, 6000.0, 12000.0, 18000.0, 22000.0))
    val power = Analyzer.powerOf(samples)
    val (cutoff, _) = Analyzer.cutoff(power, sr / 2)
    assertTrue("cutoff=$cutoff", cutoff >= 21000.0)
  }

  @Test
  fun nearestRateSnapsToCommon() {
    assertEquals(44100, Analyzer.nearestRate(22050.0))
    assertEquals(96000, Analyzer.nearestRate(47000.0))
  }
}

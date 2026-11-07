package com.goosepaware.biliaudio.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackListParserTest {

  private val sample = """
    1. 忘れられないの  0:00
    2. マッチとピーナッツ  3:58
    3. 陽炎  8:28
  """.trimIndent()

  @Test
  fun parsesJapaneseTrackList() {
    val r = TrackListParser.parse(sample, 600.0)
    assertEquals(3, r.tracks.size)
    assertEquals("忘れられないの", r.tracks[0].title)
    assertEquals(0.0, r.tracks[0].start, 0.001)
    assertEquals(238.0, r.tracks[1].start, 0.001)
    assertEquals(508.0, r.tracks[2].start, 0.001)
    assertEquals(listOf(1, 2, 3), r.tracks.map { it.index })
  }

  @Test
  fun skipsLinesBeyondDuration() {
    val r = TrackListParser.parse(sample, 300.0)
    assertTrue(r.tracks.size < 3)
    assertTrue(r.warnings.isNotEmpty())
  }

  @Test
  fun handlesFullWidthAndTrackPrefix() {
    val text = "０１　夜を駆ける　０：００\nTrack 02 雨 4:20"
    val r = TrackListParser.parse(text, 600.0)
    assertEquals(2, r.tracks.size)
    assertEquals("夜を駆ける", r.tracks[0].title)
    assertEquals(260.0, r.tracks[1].start, 0.001)
  }
}

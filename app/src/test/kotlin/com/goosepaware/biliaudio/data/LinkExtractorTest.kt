package com.goosepaware.biliaudio.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LinkExtractorTest {

  @Test
  fun extractsUrlFromFullShareText() {
    val text = "臻品母带 | 这才是日式爵士天花板！！BLU-SWING 十周年精选专 | 循环播放 | 双语翻译 | 工作学习休闲随身听！ https://b23.tv/px00Eoy"
    assertEquals("https://b23.tv/px00Eoy", LinkExtractor.extract(text))
  }

  @Test
  fun stripsChineseTrailingPunctuation() {
    val text = "看这个 https://www.bilibili.com/video/BV1xx411c7mD，很好听"
    assertEquals("https://www.bilibili.com/video/BV1xx411c7mD", LinkExtractor.extract(text))
  }

  @Test
  fun extractsBvFromText() {
    val text = "【测试】BV1xx411c7mD 分享"
    assertEquals("BV1xx411c7mD", LinkExtractor.extract(text))
  }

  @Test
  fun addsSchemeToBareShortLink() {
    assertEquals("https://b23.tv/abc123", LinkExtractor.extract("b23.tv/abc123"))
  }

  @Test
  fun handlesFullWidthSeparators() {
    assertEquals(
      "https://b23.tv/abc123",
      LinkExtractor.extract("标题 https：／／b23.tv/abc123"),
    )
  }

  @Test
  fun ignoresCookieText() {
    assertNull(LinkExtractor.extract("SESSDATA=xxx; bili_jct=yyy; DedeUserID=123"))
  }
}

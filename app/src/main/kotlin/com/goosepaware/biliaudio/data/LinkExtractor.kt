package com.goosepaware.biliaudio.data

/**
 * 从分享文本 / 粘贴板内容里提取 B 站视频链接。
 *
 * 手机分享出来的文本通常是「标题 + 空格 + 短链」，甚至还会带中文标点，
 * 这里统一只保留真正能用的链接 / BV 号 / av 号。
 */
object LinkExtractor {

  // B 站自己的链接优先，避免分享文本里混进别的站外链接。
  private val biliUrl = Regex(
    """https?://(?:[A-Za-z0-9-]+\.)*(?:bilibili\.com|b23\.tv|bili2233\.cn)/[A-Za-z0-9\-._~:/?#\[\]@!$&'()*+,;=%]*""",
    RegexOption.IGNORE_CASE,
  )

  // 没有写 https:// 的短链 / 视频页。
  private val bareBili = Regex(
    """(?:[A-Za-z0-9-]+\.)*(?:bilibili\.com/video|b23\.tv|bili2233\.cn)/[A-Za-z0-9\-._~:/?#\[\]@!$&'()*+,;=%]+""",
    RegexOption.IGNORE_CASE,
  )

  private val bv = Regex("""BV[0-9A-Za-z]{10}""")
  private val av = Regex("""(?i)\bav\d{1,12}\b""")

  private val genericUrl = Regex(
    """https?://[A-Za-z0-9\-._~:/?#\[\]@!$&'()*+,;=%]+""",
    RegexOption.IGNORE_CASE,
  )

  private const val TRAILING = ".,;:!?)]}，。；：！？、）】》”’\"'"

  fun extract(raw: String?): String? {
    var text = raw?.trim().orEmpty()
    if (text.isEmpty()) return null
    text = normalize(text)

    biliUrl.find(text)?.value?.let { return clean(it) }
    bareBili.find(text)?.value?.let { return "https://" + clean(it) }
    bv.find(text)?.value?.let { return it }
    av.find(text)?.value?.let { return it.lowercase() }
    genericUrl.find(text)?.value?.let { return clean(it) }
    return null
  }

  /** 全角冒号 / 斜杠、零宽字符在手机分享里很常见。 */
  private fun normalize(s: String): String = s
    .replace('\u3000', ' ')
    .replace('\uFF1A', ':')
    .replace('\uFF0F', '/')
    .replace('\u200B', ' ')
    .replace('\uFEFF', ' ')
    .replace('\u00A0', ' ')

  private fun clean(url: String): String {
    var s = url.trim()
    while (s.isNotEmpty() && TRAILING.indexOf(s.last()) >= 0) {
      s = s.dropLast(1)
    }
    return s
  }
}

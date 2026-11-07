package com.goosepaware.biliaudio.media

import com.goosepaware.biliaudio.data.ParseOutcome
import com.goosepaware.biliaudio.data.ParsedTrack
import kotlin.math.roundToInt

/**
 * 从自由文本里识别曲目表（序号 / 歌名 / 时间）。
 * 规则与桌面端 tracklist.py 一致。
 */
object TrackListParser {

  private val TS = Regex("(?<!\\d)(?:(\\d{1,2}):)?(\\d{1,2}):(\\d{2})(?!\\d)")

  private val INDEX = Regex(
    "^\\s*(?:#|no\\.?|track\\s*)?" +
      "(?:[\\[【(（]\\s*(\\d{1,3})\\s*[\\]】)）]|(\\d{1,3})(?=[.、,，:：\\-–—|)\\s]))" +
      "\\s*(?:[.、,，:：\\-–—|)\\]}】）]+\\s*)?",
    RegexOption.IGNORE_CASE,
  )

  private val URL_RE = Regex("https?://|www\\.", RegexOption.IGNORE_CASE)

  private const val FW_DIGITS = "０１２３４５６７８９"

  fun normalize(text: String): String {
    val sb = StringBuilder(text.length)
    for (ch in text) {
      val di = FW_DIGITS.indexOf(ch)
      sb.append(
        when {
          di >= 0 -> '0' + di
          ch == '：' -> ':'
          ch == '．' -> '.'
          ch == '－' -> '-'
          ch == '—' -> '-'
          ch == '–' -> '-'
          ch == '　' -> ' '
          else -> ch
        }
      )
    }
    return sb.toString()
  }

  private fun toSeconds(h: String?, mm: String, ss: String): Double =
    (h?.toIntOrNull() ?: 0) * 3600.0 + mm.toInt() * 60.0 + ss.toInt()

  private fun cleanTitle(text: String): String {
    var t = TS.replace(text, " ")
    t = t.replace(Regex("[\\t\\r]+"), " ")
    t = t.replace(Regex("\\s{2,}"), " ")
    t = t.trim()
    t = t.replace(Regex("^[\\-–—|:：,，、\\s]+"), "")
    t = t.replace(Regex("[\\-–—|:：,，、\\s]+$"), "")
    t = t.replace(Regex("[\\(（]\\s*[\\)）]"), "")
    return t.trim()
  }

  fun parse(text: String, duration: Double? = null): ParseOutcome {
    val tracks = mutableListOf<ParsedTrack>()
    val warnings = mutableListOf<String>()
    val seen = mutableSetOf<Int>()

    for (raw in normalize(text).split('\n')) {
      val line = raw.trim()
      if (line.isEmpty() || URL_RE.containsMatchIn(line)) continue
      val matches = TS.findAll(line).toList()
      if (matches.isEmpty()) continue
      val first = matches.first()
      val start = toSeconds(first.groupValues[1].takeIf { it.isNotEmpty() }, first.groupValues[2], first.groupValues[3])
      if (start > 24 * 3600) continue
      if (duration != null && start >= duration) {
        warnings += "跳过超出时长的行：${line.take(40)}"
        continue
      }
      val key = start.roundToInt()
      if (!seen.add(key)) continue

      val before = line.substring(0, first.range.first).trim()
      val after = line.substring(first.range.last + 1).trim()
      val m = INDEX.find(before)
      val idx = m?.let {
        (it.groupValues[1].takeIf { s -> s.isNotEmpty() } ?: it.groupValues[2].takeIf { s -> s.isNotEmpty() })?.toIntOrNull()
      }
      val rest = if (m != null) before.substring(m.range.last + 1) else before
      var title = cleanTitle(listOf(rest, after).filter { it.isNotBlank() }.joinToString(" "))
      if (title.isEmpty()) title = "—"
      tracks += ParsedTrack(index = idx ?: (tracks.size + 1), title = title, start = start)
    }

    if (tracks.isEmpty()) return ParseOutcome(emptyList(), listOf("没有识别到「歌名 + 时间」的行"))

    val times = tracks.map { it.start }
    if (times != times.sorted()) {
      warnings += "时间不是递增的，已按时间重新排序"
      tracks.sortBy { it.start }
    }

    if (tracks.map { it.index }.toSet().size != tracks.size) {
      tracks.forEachIndexed { i, t -> tracks[i] = t.copy(index = i + 1) }
      warnings += "序号有重复，已按时间顺序重新编号"
    }

    if (tracks.size == 1) warnings += "只识别到 1 首，请检查文本格式"
    return ParseOutcome(tracks, warnings)
  }
}

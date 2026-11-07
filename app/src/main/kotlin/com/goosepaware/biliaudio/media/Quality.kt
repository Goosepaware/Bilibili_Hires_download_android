package com.goosepaware.biliaudio.media

import com.goosepaware.biliaudio.data.Analysis
import com.goosepaware.biliaudio.data.BiliApi
import com.goosepaware.biliaudio.data.BiliConst
import com.goosepaware.biliaudio.data.DownloadOptions
import com.goosepaware.biliaudio.data.QualityReport
import com.goosepaware.biliaudio.data.SegmentQuality
import com.goosepaware.biliaudio.data.Track
import com.goosepaware.biliaudio.data.TrackQuality
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 音源质量检测：远程分段采样 + FFT + 1~100 评分（与桌面端 quality.py 一致）。
 */
object Quality {

  private val HEADERS = mapOf("Referer" to BiliConst.REFERER, "User-Agent" to BiliConst.UA)

  private val GRADE = listOf(
    90 to "优秀 · 真无损",
    80 to "良好 · 基本无损",
    65 to "一般 · 带宽受限",
    45 to "存疑 · 疑似有损/升频",
    0 to "差 · 假无损或有损",
  )

  fun grade(score: Int): String = GRADE.first { score >= it.first }.second

  private fun mmss(t: Double): String {
    val s = t.toInt().coerceAtLeast(0)
    return "%02d:%02d".format(s / 60, s % 60)
  }

  private fun segVerdict(s: SegmentQuality): String = when {
    !s.valid -> "该段几乎无音频内容（静音或读取失败）"
    s.cutoffHz >= 22000 -> "高频完整，达到 Hi-Res 水平"
    s.cutoffHz >= 20000 -> "高频基本完整，接近 CD/44.1kHz 上限"
    s.cutoffHz >= 18000 -> "高频偏窄，疑似有损来源或升频"
    s.cutoffHz >= 15000 -> "高频明显缺失，大概率来自有损音源"
    else -> "高频严重缺失，可以确定是有损音源"
  }

  private fun segmentsFor(duration: Double, count: Int): List<Pair<Double, Double>> {
    if (duration <= 0) return listOf(0.0 to 0.0)
    val n = max(1, min(count, (duration / 5).toInt().coerceAtLeast(1)))
    val len = duration / n
    return (0 until n).map { it * len to it * len + len }
  }

  private suspend fun analyseSegment(
    src: String,
    sr: Int,
    start: Double,
    end: Double,
    index: Int,
    workDir: File,
  ): SegmentQuality {
    val nyquist = sr / 2
    var seg = SegmentQuality(index = index, start = start, end = end)
    val span = max(0.0, end - start)
    val offsets = if (span > 3) listOf(0.15, 0.5, 0.85) else listOf(0.5)
    val pcm = File(workDir, "q_${System.nanoTime()}_$index.pcm")
    var best: DoubleArray? = null
    var bestPeak = 0.0
    var bestClip = 0.0
    try {
      for (frac in offsets) {
        val at = start + span * frac
        val ok = Ffmpeg.decodeToPcm(src, pcm, at, Analyzer.N.toDouble() / sr + 0.15, sr, true, HEADERS)
        if (!ok) continue
        val samples = Analyzer.readPcm(pcm)
        if (samples.size < Analyzer.N / 6) continue
        var peak = 0
        var clipped = 0
        for (v in samples) {
          val a = abs(v.toInt())
          if (a > peak) peak = a
          if (a >= 32760) clipped++
        }
        val peakNorm = peak / 32768.0
        if (peakNorm <= bestPeak) continue
        val power = Analyzer.powerOf(samples)
        val (c, s) = Analyzer.cutoff(power, nyquist)
        best = doubleArrayOf(c, s)
        bestPeak = peakNorm
        bestClip = clipped.toDouble() / samples.size
      }
      if (best == null) {
        seg = seg.copy(valid = false)
        return seg
      }
      val peakDb = if (bestPeak > 0) 20 * kotlin.math.log10(bestPeak) else -120.0
      seg = seg.copy(
        cutoffHz = best[0],
        steepness = best[1],
        ratio = if (nyquist > 0) best[0] / nyquist else 0.0,
        peakDb = peakDb,
        clipped = bestClip,
        valid = peakDb >= -45,
      )
    } catch (e: Throwable) {
      seg = seg.copy(valid = false)
    } finally {
      pcm.delete()
    }
    return seg
  }

  private fun median(values: List<Double>): Double {
    if (values.isEmpty()) return 0.0
    val s = values.sorted()
    val n = s.size
    return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2
  }

  private fun losslessScore(segs: List<SegmentQuality>, info: AudioInfo, nyquist: Double): Pair<Int, String> {
    val valid = segs.filter { it.valid }
    if (valid.isEmpty()) return 5 to "拿不到有效的音频段，无法判断"

    var score = 100.0
    val cutoff = median(valid.map { it.cutoffHz })
    val steep = median(valid.map { it.steepness })
    val clip = valid.maxOf { it.clipped }

    if (cutoff < 22000) {
      score -= min(62.0, (22000 - cutoff) / 6000.0 * 55.0)
      if (cutoff < 16000) score -= (16000 - cutoff) / 1000.0 * 4.0
    }
    if (steep >= 0.85) score -= 18 else if (steep >= 0.7) score -= 8
    if (info.bitDepth != null && info.bitDepth <= 16) score -= 3
    if ((info.sampleRate ?: 0) >= 96000 && cutoff >= 30000) score += 3
    if (clip > 0.001) score -= 10 else if (clip > 0.00005) score -= 5

    val cvals = valid.map { it.cutoffHz }
    if (cvals.size >= 3 && cvals.max() - cvals.min() > 8000) score -= 6
    val low = cvals.sorted().getOrElse(cvals.size / 5) { 22000.0 }
    if (low < 16000) score -= 5

    val finalScore = score.roundToInt().coerceIn(1, 100)
    return finalScore to grade(finalScore)
  }

  private fun lossyScore(track: Track): Pair<Int, String> = when {
    track.kind == "dolby" -> 52 to "杜比全景声：有损编码，但码率高、环绕声，观影优先"
    track.kbps >= 180 -> 42 to "AAC ${track.kbps}K：有损编码，日常够用，但不能称为无损"
    track.kbps >= 120 -> 34 to "AAC ${track.kbps}K：有损编码，高频和细节有损失"
    else -> 24 to "AAC ${track.kbps}K：有损编码，音质较差"
  }

  suspend fun analyseTrack(
    track: Track,
    duration: Double,
    segmentCount: Int,
    workDir: File,
  ): TrackQuality {
    val src = track.urls.firstOrNull() ?: throw IllegalStateException("没有可用的流地址")
    val info = Ffmpeg.probe(src, HEADERS)
    val sr = info.sampleRate ?: 44100
    val nyquist = sr / 2.0
    val isLossless = info.lossless || track.kind == "flac"

    if (!isLossless) {
      val (score, g) = lossyScore(track)
      return TrackQuality(
        track = track, codec = info.codec ?: track.codec, sampleRate = sr,
        bitDepth = info.bitDepth, channels = info.channels, duration = duration,
        cutoffHz = 0.0, ratio = 0.0, steepness = 0.0,
        score = score, grade = g, summary = g,
      )
    }

    var count = segmentCount
    if (duration > 0) count = max(count, min(12, (duration / 180).toInt()))

    val spans = segmentsFor(duration, count)
    val segs = withContext(Dispatchers.Default) {
      coroutineScope {
        spans.mapIndexed { i, (a, b) ->
          async { analyseSegment(src, sr, a, b, i, workDir) }
        }.awaitAll()
      }
    }

    val (score, g) = losslessScore(segs, info, nyquist)
    val valid = segs.filter { it.valid }
    val medCut = median(valid.map { it.cutoffHz })
    val medSteep = median(valid.map { it.steepness })

    val head = when {
      medCut >= 22000 -> "高频完整，达到 Hi-Res 水平"
      medCut >= 20000 -> "高频基本完整，接近 44.1kHz 上限"
      medCut >= 18000 -> "高频偏窄，疑似有损来源或升频"
      else -> "高频明显缺失，大概率来自有损音源"
    }
    val summary = "%s（中位有效带宽 %.1f kHz，陡峭度 %.2f）".format(head, medCut / 1000, medSteep)

    val descriptions = segs.map { s ->
      if (!s.valid) {
        "${mmss(s.start)}–${mmss(s.end)}  该段几乎无声，未参与判断。"
      } else {
        val extra = mutableListOf<String>()
        if (s.steepness >= 0.8) extra += "存在硬截断"
        if (s.clipped > 0.001) extra += "出现削波"
        else if (s.clipped > 0.00005) extra += "个别采样接近满刻度"
        val tail = if (extra.isEmpty()) "" else "；" + extra.joinToString("，")
        "%s–%s  有效带宽 %.1f kHz（Nyquist 的 %.0f%%），陡峭度 %.2f%s。%s。".format(
          mmss(s.start), mmss(s.end), s.cutoffHz / 1000, s.ratio * 100, s.steepness,
          tail, segVerdict(s),
        )
      }
    }

    return TrackQuality(
      track = track, codec = info.codec ?: track.codec, sampleRate = sr,
      bitDepth = info.bitDepth, channels = info.channels, duration = duration,
      cutoffHz = medCut, ratio = if (nyquist > 0) medCut / nyquist else 0.0,
      steepness = medSteep, score = score, grade = g, summary = summary,
      segments = segs, descriptions = descriptions,
    )
  }

  /** 检测一个链接（取第一 P，分析全部可用音轨）。 */
  suspend fun inspect(
    api: BiliApi,
    url: String,
    segmentCount: Int,
    workDir: File,
    onLog: (String) -> Unit = {},
  ): QualityReport {
    val vid = api.resolveUrl(url)
    val video = api.getVideo(vid)
    val page = video.pages.first()
    onLog("■ ${video.title}  (${video.bvid}, ${video.owner})")
    val dash = api.getPlayurl(video.bvid, page.cid)
    val tracks = api.selectTracks(dash)

    val picked = listOfNotNull(
      tracks.firstOrNull { it.kind == "flac" },
      tracks.firstOrNull { it.kind == "dolby" },
      tracks.firstOrNull { it.kind == "aac" },
    )
    if (picked.isEmpty()) throw IllegalStateException("这个视频没有可取用的音轨")

    val results = mutableListOf<TrackQuality>()
    for (t in picked) {
      onLog("分析 ${t.label} (${t.kbps} kb/s)…")
      results += try {
        analyseTrack(t, page.duration.toDouble(), segmentCount, workDir)
          .also { onLog("  → ${it.score}/100 ${it.grade}") }
      } catch (e: Throwable) {
        TrackQuality(
          track = t, codec = t.codec, sampleRate = 0, bitDepth = null, channels = null,
          duration = page.duration.toDouble(), cutoffHz = 0.0, ratio = 0.0, steepness = 0.0,
          score = 0, grade = "无法检测", summary = e.message ?: "检测失败",
        )
      }
    }

    val flacQ = results.firstOrNull { it.track.kind == "flac" && it.score > 0 }
    val best = flacQ ?: results.maxByOrNull { it.score }!!
    val hasFlac = tracks.any { it.kind == "flac" }
    val c = best.cutoffHz
    var summary = when {
      !hasFlac -> "该视频没有 Hi-Res 无损音轨，最高只有 AAC；以下评分按有损音轨给出。"
      c >= 30000 -> "Hi-Res 母带级带宽，高频完整，可以放心收藏。"
      c >= 22000 -> "带宽达到 Hi-Res 水平（22kHz 以上），可作为无损收藏。"
      c >= 20000 -> "有效带宽约 CD 级（20~22kHz），是无损封装，但不算真正的 Hi-Res。"
      c >= 18000 -> "高频偏窄（18~20kHz），疑似有损来源或升频，建议试听后决定是否收藏。"
      else -> "高频明显缺失（低于 18kHz），基本可以判定是有损转码或升频，不建议当无损收藏。"
    }
    if (picked.size > 1) summary += " 已同时分析 ${picked.size} 条音轨。"

    return QualityReport(
      videoTitle = video.title, bvid = video.bvid, owner = video.owner,
      duration = page.duration.toDouble(), tracks = results,
      score = best.score, grade = best.grade, summary = summary,
    )
  }

  fun reportText(r: QualityReport): String {
    val sb = StringBuilder()
    sb.appendLine("■ ${r.videoTitle}")
    sb.appendLine("  ${r.bvid} · ${r.owner} · ${mmss(r.duration)}")
    sb.appendLine()
    sb.appendLine("综合评分: ${r.score}/100  [${r.grade}]")
    sb.appendLine(r.summary)
    for (tq in r.tracks) {
      sb.appendLine()
      sb.appendLine("─".repeat(40))
      sb.appendLine("${tq.track.label}  (${tq.track.kbps} kb/s)")
      if (tq.segments.isNotEmpty()) {
        sb.appendLine("得分 ${tq.score}/100 · ${tq.grade}")
        sb.appendLine("${tq.codec} · ${tq.sampleRate} Hz · ${tq.bitDepth ?: "-"}-bit · ${tq.channels ?: "-"}")
        sb.appendLine()
        tq.descriptions.forEach { sb.appendLine(it) }
      } else {
        sb.appendLine(tq.summary)
      }
    }
    return sb.toString()
  }

  fun asAnalysis(tq: TrackQuality): Analysis = Analysis(
    codec = tq.codec, sampleRate = tq.sampleRate, bitDepth = tq.bitDepth,
    channels = tq.channels, bitrateKbps = tq.track.kbps, duration = tq.duration,
    nyquist = tq.sampleRate / 2, losslessCodec = tq.segments.isNotEmpty(),
    cutoffHz = tq.cutoffHz, cutoffRatio = tq.ratio, steepness = tq.steepness,
    estimatedSourceRate = Analyzer.nearestRate(tq.cutoffHz),
    verdict = "SCORE", verdictText = "${tq.score}/100 ${tq.grade}",
  )
}

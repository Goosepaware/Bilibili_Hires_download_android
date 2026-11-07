package com.goosepaware.biliaudio.media

import com.goosepaware.biliaudio.data.ParsedTrack
import com.goosepaware.biliaudio.data.SplitSegment
import java.io.File
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 整轨分轨：静音检测、波形峰值、按时间轴导出（含标签）。
 */
object Splitter {

  const val ANALYSIS_SR = 2000

  private val SAFE = Regex("[\\\\/:*?\"<>|\\r\\n\\t]")

  fun safeName(name: String, limit: Int = 100): String {
    var s = SAFE.replace(name, "_").trim().trim('.')
    s = s.replace(Regex("\\s+"), " ")
    return s.take(limit).ifBlank { "untitled" }
  }

  /** 解码成 2kHz 单声道 PCM，用于波形与静音检测。 */
  suspend fun decodeAnalysisPcm(
    source: String,
    workDir: File,
    headers: Map<String, String>? = null,
  ): ShortArray {
    val pcm = File(workDir, "wave_${System.nanoTime()}.pcm")
    val ok = Ffmpeg.decodeToPcm(source, pcm, 0.0, 0.0, ANALYSIS_SR, true, headers)
    if (!ok) throw IllegalStateException("解码失败，无法生成波形")
    val data = Analyzer.readPcm(pcm)
    pcm.delete()
    if (data.isEmpty()) throw IllegalStateException("解码结果为空")
    return data
  }

  fun waveformPeaks(samples: ShortArray, buckets: Int = 2000): List<Pair<Float, Float>> {
    val n = samples.size
    if (n == 0) return emptyList()
    val b = max(1, min(buckets, n))
    val size = max(1, n / b)
    val out = ArrayList<Pair<Float, Float>>(b)
    for (i in 0 until b) {
      val a = i * size
      val e = if (i == b - 1) n else min(n, a + size)
      if (a >= e) {
        out += 0f to 0f
        continue
      }
      var lo = 0
      var hi = 0
      for (k in a until e) {
        val v = samples[k].toInt()
        if (v < lo) lo = v
        if (v > hi) hi = v
      }
      out += (lo / 32768f) to (hi / 32768f)
    }
    return out
  }

  /** 返回静音区间（秒）。 */
  fun detectSilence(
    samples: ShortArray,
    thresholdDb: Double = -35.0,
    minSilence: Double = 1.5,
    window: Double = 0.05,
  ): List<Pair<Double, Double>> {
    val win = max(1, (ANALYSIS_SR * window).toInt())
    val thr = 32768.0 * Math.pow(10.0, thresholdDb / 20.0)
    val nwin = samples.size / win
    val flags = BooleanArray(nwin)
    for (i in 0 until nwin) {
      var sum = 0.0
      val base = i * win
      for (k in base until base + win) {
        val v = samples[k].toDouble()
        sum += v * v
      }
      flags[i] = sqrt(sum / win) < thr
    }
    val gaps = mutableListOf<Pair<Double, Double>>()
    var i = 0
    while (i < nwin) {
      if (flags[i]) {
        var j = i
        while (j < nwin && flags[j]) j++
        val dur = (j - i) * window
        if (dur >= minSilence) gaps += (i * window) to (j * window)
        i = j
      } else i++
    }
    return gaps
  }

  fun splitsFromGaps(gaps: List<Pair<Double, Double>>, duration: Double, minGap: Double = 10.0): List<Double> {
    val pts = gaps.map { (a, b) -> (a + b) / 2 to (b - a) }
      .filter { (mid, _) -> mid > 3.0 && mid < duration - 3.0 }
      .sortedBy { it.first }
    val merged = mutableListOf<Pair<Double, Double>>()
    for (p in pts) {
      val last = merged.lastOrNull()
      if (last != null && p.first - last.first < minGap) {
        if (p.second > last.second) merged[merged.size - 1] = p
      } else merged += p
    }
    return merged.map { it.first }
  }

  fun segmentsFromPoints(
    points: List<Double>,
    duration: Double,
    titles: Map<Int, String> = emptyMap(),
  ): List<SplitSegment> {
    val pts = points.filter { it > 0 && it < duration }.sorted()
    val starts = listOf(0.0) + pts
    val ends = pts + duration
    val out = mutableListOf<SplitSegment>()
    for ((i, pair) in starts.zip(ends).withIndex()) {
      val (a, b) = pair
      if (b - a < 0.5) continue
      out += SplitSegment(index = i + 1, start = a, end = b, title = titles[i + 1].orEmpty())
    }
    return out
  }

  fun segmentsFromTracks(tracks: List<ParsedTrack>, duration: Double): List<SplitSegment> {
    val out = mutableListOf<SplitSegment>()
    for ((i, t) in tracks.withIndex()) {
      val end = if (i + 1 < tracks.size) tracks[i + 1].start else duration
      if (end - t.start < 0.5) continue
      out += SplitSegment(index = t.index, start = t.start, end = end, title = t.title)
    }
    return out
  }

  /**
   * 逐个导出。onFile 收到（临时文件, 显示名），由调用方发布到媒体库。
   */
  suspend fun exportSegments(
    source: String,
    segments: List<SplitSegment>,
    workDir: File,
    ext: String,
    fmt: String,
    preset: String,
    album: String,
    artist: String,
    cover: ByteArray?,
    headers: Map<String, String>?,
    titles: Boolean,
    refresh: (suspend () -> String)? = null,
    onFile: suspend (File, String) -> Unit,
    onProgress: (Int, Int, String) -> Unit = { _, _, _ -> },
  ): Int {
    val outDir = File(workDir, "split_${System.nanoTime()}").apply { mkdirs() }
    var curSource = LocalProxy.localize(source)
    var realHeaders = if (curSource != source) null else headers
    var currentExt = ext
    if (fmt != "source") currentExt = Formats.get(fmt).ext.trimStart('.')

    var done = 0
    for (seg in segments) {
      val label = if (titles && seg.title.isNotBlank()) safeName(seg.title) else ""
      val name = if (label.isNotEmpty()) "%02d %s.%s".format(seg.index, label, currentExt)
      else "%02d.%s".format(seg.index, currentExt)
      val dest = File(outDir, name)

      val args = mutableListOf(
        "-y", "-hide_banner", "-v", "error",
        "-ss", String.format(java.util.Locale.US, "%.3f", seg.start),
        "-t", String.format(java.util.Locale.US, "%.3f", seg.duration),
      )
      args += Ffmpeg.headersArgs(realHeaders)
      args += listOf("-i", curSource, "-map", "0:a:0", "-map_metadata", "-1", "-vn", "-sn", "-dn")
      if (fmt != "source") {
        args += Formats.ffmpegArgs(fmt, preset)
      } else {
        when (currentExt) {
          "flac" -> args += listOf("-c:a", "flac", "-compression_level", "8", "-f", "flac")
          "m4a" -> args += listOf("-c:a", "copy", "-movflags", "+faststart", "-f", "mp4")
          "eac3" -> args += listOf("-c:a", "copy", "-f", "eac3")
          else -> args += listOf("-c:a", "copy")
        }
      }
      args += dest.absolutePath

      var ok = false
      var lastOut = ""
      for (attempt in 0..1) {
        val r = Ffmpeg.run(*args.toTypedArray())
        lastOut = r.output
        if (r.success && dest.exists() && dest.length() > 0) {
          ok = true
          break
        }
        if (attempt == 0 && refresh != null) {
          runCatching {
            val fresh = refresh()
            if (fresh.isNotBlank()) {
              curSource = LocalProxy.localize(fresh)
              realHeaders = if (curSource != fresh) null else headers
              val i = args.indexOf("-i")
              if (i >= 0) args[i + 1] = curSource
              val hIdx = args.indexOf("-headers")
              if (hIdx >= 0) {
                args.removeAt(hIdx)
                args.removeAt(hIdx)
                val newHeaders = Ffmpeg.headersArgs(realHeaders)
                if (newHeaders.isNotEmpty()) args.addAll(hIdx, newHeaders)
              }
            }
          }
        }
      }
      if (!ok) throw IllegalStateException("导出第 ${seg.index} 轨失败: ${lastOut.takeLast(300)}")

      Tagger.tag(
        dest,
        title = seg.title.ifBlank { "Track %02d".format(seg.index) },
        artist = artist,
        album = album,
        track = seg.index,
        total = segments.size,
        cover = cover,
      )
      onFile(dest, name)
      dest.delete()
      done++
      onProgress(done, segments.size, name)
    }
    return done
  }
}

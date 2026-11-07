package com.goosepaware.biliaudio.media

import com.goosepaware.biliaudio.data.Analysis
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 真实音质核验：解码 PCM → 32k 点 FFT → 有效带宽 / 截断陡峭度 → 结论。
 * 算法与桌面端 analyze.py 一致（不使用第三方 FFT 库）。
 */
object Analyzer {

  const val N = 1 shl 15
  private const val RELDROP_DB = 55.0

  val COMMON_RATES = intArrayOf(8000, 11025, 16000, 22050, 32000, 44100, 48000, 88200, 96000, 176400, 192000)

  val VERDICT_TEXT = mapOf(
    "LOSSLESS" to "无损（带宽充足，未见明显截断）",
    "LIMITED" to "无损封装，但高频带宽受限（源头可能是 44.1kHz 或经过降频处理）",
    "UPSAMPLED" to "无损封装，但高频有硬截断：源头很可能是 44.1kHz 有损音源或被升频，不是真正的 Hi-Res",
    "SUSPECT" to "疑似假无损：有效带宽明显低于当前采样率，高频被截断，很可能来自有损音源或升频",
    "TRANSCODE" to "疑似假无损：高频存在明显截断，源头很可能是有损编码",
    "LOSSY_CODEC" to "非无损编码（AAC / EC-3 等），不能称为无损",
  )

  /** 读 s16le PCM 文件。 */
  fun readPcm(file: File): ShortArray {
    val bytes = file.readBytes()
    val n = bytes.size / 2
    val out = ShortArray(n)
    var i = 0
    var j = 0
    while (i + 1 < bytes.size && j < n) {
      val lo = bytes[i].toInt() and 0xFF
      val hi = bytes[i + 1].toInt()
      out[j] = ((hi shl 8) or lo).toShort()
      i += 2
      j++
    }
    return out
  }

  /** 原地 radix-2 FFT（长度必须是 2 的幂）。 */
  fun fft(re: DoubleArray, im: DoubleArray) {
    val n = re.size
    var j = 0
    for (i in 1 until n) {
      var bit = n shr 1
      while (j and bit != 0) {
        j = j xor bit
        bit = bit shr 1
      }
      j = j or bit
      if (i < j) {
        val tr = re[i]; re[i] = re[j]; re[j] = tr
        val ti = im[i]; im[i] = im[j]; im[j] = ti
      }
    }
    var length = 2
    while (length <= n) {
      val ang = -2.0 * PI / length
      val wlr = cos(ang)
      val wli = kotlin.math.sin(ang)
      val half = length shr 1
      var i = 0
      while (i < n) {
        var wr = 1.0
        var wi = 0.0
        for (k in i until i + half) {
          val ur = re[k]; val ui = im[k]
          val vr = re[k + half] * wr - im[k + half] * wi
          val vi = re[k + half] * wi + im[k + half] * wr
          re[k] = ur + vr; im[k] = ui + vi
          re[k + half] = ur - vr; im[k + half] = ui - vi
          val nwr = wr * wlr - wi * wli
          wi = wr * wli + wi * wlr
          wr = nwr
        }
        i += length
      }
      length = length shl 1
    }
  }

  /** 单窗口功率谱（dB，长度 N/2）。 */
  fun powerOf(samples: ShortArray): DoubleArray {
    val re = DoubleArray(N)
    val im = DoubleArray(N)
    val count = min(samples.size, N)
    for (i in 0 until count) {
      val w = 0.5 - 0.5 * cos(2.0 * PI * i / (N - 1))
      re[i] = samples[i] * w
    }
    fft(re, im)
    val out = DoubleArray(N / 2)
    for (i in out.indices) {
      out[i] = 10.0 * log10((re[i] * re[i] + im[i] * im[i]) * 4.0 / (N.toDouble() * N) + 1e-12)
    }
    return out
  }

  fun smooth(vals: DoubleArray, k: Int = 15): DoubleArray {
    if (k <= 1) return vals
    val half = k / 2
    val out = DoubleArray(vals.size)
    for (i in vals.indices) {
      val lo = max(0, i - half)
      val hi = min(vals.size, i + half + 1)
      var sum = 0.0
      for (x in lo until hi) sum += vals[x]
      out[i] = sum / (hi - lo)
    }
    return out
  }

  /** 返回 (有效带宽 Hz, 截断陡峭度 0~1)。 */
  fun cutoff(powerDb: DoubleArray, nyquist: Int): Pair<Double, Double> {
    val sm = smooth(powerDb, 15)
    val peak = sm.max()
    val thr = peak - RELDROP_DB
    val bins = sm.size
    val hzPerBin = nyquist.toDouble() / bins
    var idx = bins - 1
    while (idx > 0 && sm[idx] < thr) idx--
    val cutoff = idx * hzPerBin

    val span = max(4, (2000.0 / hzPerBin).toInt())
    val pbFrom = max(0, idx - 3 * span)
    val pbTo = max(1, idx - 2)
    if (pbTo <= pbFrom) return cutoff to 0.0
    var pbSum = 0.0
    for (i in pbFrom until pbTo) pbSum += sm[i]
    val pbAvg = pbSum / (pbTo - pbFrom)
    val sbFrom = idx + 2
    val sbTo = min(bins, idx + 2 + span)
    val sbAvg = if (sbTo > sbFrom) {
      var s = 0.0
      for (i in sbFrom until sbTo) s += sm[i]
      s / (sbTo - sbFrom)
    } else pbAvg
    val drop = pbAvg - sbAvg
    return cutoff to (drop / 40.0).coerceIn(0.0, 1.0)
  }

  fun nearestRate(hz: Double): Int? {
    if (hz <= 0) return null
    val rate = (hz * 2 / 1000.0).roundToInt() * 1000
    return COMMON_RATES.minByOrNull { abs(it - rate) }
  }

  /** 完整核验一个本地文件（多个时间窗口逐频点取最大值）。 */
  suspend fun analyzeFile(path: File, workDir: File): Analysis {
    val info = Ffmpeg.probe(path.absolutePath)
    val nyquist = info.nyquist
    if (!info.lossless) {
      return Analysis(
        codec = info.codec, sampleRate = info.sampleRate, bitDepth = info.bitDepth,
        channels = info.channels, bitrateKbps = info.bitrateKbps, duration = info.duration,
        nyquist = nyquist, losslessCodec = false,
        cutoffHz = null, cutoffRatio = null, steepness = null, estimatedSourceRate = null,
        verdict = "LOSSY_CODEC", verdictText = VERDICT_TEXT.getValue("LOSSY_CODEC"),
      )
    }

    val sr = info.sampleRate ?: 44100
    val duration = info.duration ?: 0.0
    val starts = if (duration > 10) listOf(0.1, 0.3, 0.5, 0.7, 0.9).map { duration * it } else listOf(0.0)

    val pcm = File(workDir, "spectrum_${System.nanoTime()}.pcm")
    var best: DoubleArray? = null
    try {
      for (st in starts) {
        if (!Ffmpeg.decodeToPcm(path.absolutePath, pcm, st, N.toDouble() / sr + 0.2, sr)) continue
        val samples = readPcm(pcm)
        if (samples.size < N / 4) continue
        val cur = powerOf(samples)
        if (best == null) best = cur
        else for (i in cur.indices) if (cur[i] > best!![i]) best!![i] = cur[i]
      }
    } finally {
      pcm.delete()
    }
    if (best == null) {
      return Analysis(
        codec = info.codec, sampleRate = info.sampleRate, bitDepth = info.bitDepth,
        channels = info.channels, bitrateKbps = info.bitrateKbps, duration = info.duration,
        nyquist = nyquist, losslessCodec = true,
        cutoffHz = null, cutoffRatio = null, steepness = null, estimatedSourceRate = null,
        verdict = "UNKNOWN", verdictText = "频谱分析失败：无法解码音频",
      )
    }

    val (cutoffHz, steep) = cutoff(best!!, nyquist)
    val ratio = if (nyquist > 0) cutoffHz / nyquist else 0.0
    val est = nearestRate(cutoffHz) ?: 0
    val sampleRate = info.sampleRate ?: 0
    val verdict = when {
      steep >= 0.85 -> when {
        est >= sampleRate * 0.95 || (est >= 44100 && cutoffHz >= 0.90 * nyquist) -> "LOSSLESS"
        est >= 44100 -> "UPSAMPLED"
        else -> "TRANSCODE"
      }
      cutoffHz >= 0.88 * nyquist -> "LOSSLESS"
      cutoffHz >= 22000 -> "LOSSLESS"
      cutoffHz >= 19000 -> "LIMITED"
      cutoffHz >= 16000 -> "SUSPECT"
      else -> "TRANSCODE"
    }

    return Analysis(
      codec = info.codec, sampleRate = info.sampleRate, bitDepth = info.bitDepth,
      channels = info.channels, bitrateKbps = info.bitrateKbps, duration = info.duration,
      nyquist = nyquist, losslessCodec = true,
      cutoffHz = cutoffHz, cutoffRatio = ratio, steepness = steep, estimatedSourceRate = est,
      verdict = verdict, verdictText = VERDICT_TEXT[verdict] ?: verdict,
    )
  }

  fun reportText(a: Analysis): String {
    val bits = if (a.losslessCodec && a.bitDepth != null) "${a.bitDepth}-bit" else "-"
    val lines = mutableListOf(
      "编码: ${a.codec} | 采样率: ${a.sampleRate} Hz | 位深: $bits | 声道: ${a.channels ?: "-"}",
      "码率: ${a.bitrateKbps} kb/s | 时长: ${"%.1f".format(a.duration ?: 0.0)}s",
    )
    if (a.cutoffHz != null && a.cutoffHz > 0) {
      lines += "有效带宽: ${"%.1f".format(a.cutoffHz / 1000)} kHz / Nyquist ${"%.1f".format(a.nyquist / 1000.0)} kHz " +
        "(${((a.cutoffRatio ?: 0.0) * 100).roundToInt()}%) | 截断陡峭度 ${"%.2f".format(a.steepness ?: 0.0)}"
      a.estimatedSourceRate?.let { lines += "推测原始采样率: ≥ ${"%.1f".format(it / 1000.0)} kHz" }
    }
    lines += "结论: ${a.verdictText}"
    return lines.joinToString("\n")
  }
}

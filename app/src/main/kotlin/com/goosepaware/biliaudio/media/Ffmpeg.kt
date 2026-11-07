package com.goosepaware.biliaudio.media

import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

data class AudioInfo(
  val codec: String?,
  val sampleRate: Int?,
  val channels: String?,
  val bitDepth: Int?,
  val bitrateKbps: Int?,
  val duration: Double?,
) {
  val lossless: Boolean get() = codec in LOSSLESS
  val nyquist: Int get() = (sampleRate ?: 44100) / 2

  companion object {
    val LOSSLESS = setOf("flac", "alac", "wav", "ape", "wavpack")
    private val ALIASES = mapOf(
      "flac" to "flac", "alac" to "alac", "aac" to "aac", "mp3" to "mp3",
      "eac3" to "eac3", "ac3" to "ac3", "opus" to "opus",
    )

    fun normalize(codec: String?): String? = codec?.lowercase()?.let { ALIASES[it] ?: it }
  }
}

/**
 * ffmpeg 封装：全部在进程内执行（ffmpeg-kit），参数与桌面端一致。
 */
object Ffmpeg {

  private val gate = Semaphore(2)
  private val active = CopyOnWriteArrayList<Long>()

  data class ExecResult(val success: Boolean, val output: String)

  private suspend fun exec(args: List<String>): ExecResult = withContext(Dispatchers.IO) {
    val session = FFmpegKit.executeWithArguments(args.toTypedArray())
    active.add(session.sessionId)
    try {
      ExecResult(ReturnCode.isSuccess(session.returnCode), session.output ?: "")
    } finally {
      active.remove(session.sessionId)
    }
  }

  /** 最多 2 个 ffmpeg 并发（避免把手机烤熟，同时让音质检测不至于太慢）。 */
  suspend fun run(vararg args: String): ExecResult = gate.withPermit { exec(args.toList()) }

  fun cancelAll() {
    for (id in active) runCatching { FFmpegKit.cancel(id) }
    active.clear()
  }

  fun headersArgs(headers: Map<String, String>?): List<String> {
    if (headers.isNullOrEmpty()) return emptyList()
    return listOf("-headers", headers.entries.joinToString("") { "${it.key}: ${it.value}\r\n" })
  }

  suspend fun probe(pathOrUrl: String, headers: Map<String, String>? = null): AudioInfo {
    val target = LocalProxy.localize(pathOrUrl)
    val hdrs = if (target != pathOrUrl) null else headers
    val r = run("-hide_banner", *headersArgs(hdrs).toTypedArray(), "-i", target)
    return parseProbe(r.output, pathOrUrl)
  }

  fun parseProbe(text: String, pathOrUrl: String = ""): AudioInfo {
    val audioLine = Regex("Stream #\\d+:\\d+.*?Audio:.*").find(text)?.value ?: text
    val codec = Regex("Audio:\\s*([A-Za-z0-9_]+)").find(audioLine)?.groupValues?.get(1)
    val sr = Regex("(\\d+)\\s*Hz").find(audioLine)?.groupValues?.get(1)?.toIntOrNull()
    val ch = Regex("\\bHz,\\s*([^,]+),").find(audioLine)?.groupValues?.get(1)?.trim()
    val bits = Regex("\\((\\d+)\\s*bit\\)").find(audioLine)?.groupValues?.get(1)?.toIntOrNull()
      ?: Regex(",\\s*(s16|s32|s64|flt|dbl|u8|fltp|dblp)(?:p)?\\b").find(audioLine)
        ?.groupValues?.get(1)?.let {
          mapOf("s16" to 16, "s32" to 32, "s64" to 64, "flt" to 32, "fltp" to 32, "dbl" to 64, "dblp" to 64, "u8" to 8)[it]
        }
    val br = Regex("(\\d+)\\s*kb/s").find(audioLine)?.groupValues?.get(1)?.toIntOrNull()
    val dur = Regex("Duration:\\s*(\\d+):(\\d+):(\\d+(?:\\.\\d+)?)").find(text)?.let {
      val (h, m, s) = it.destructured
      h.toInt() * 3600 + m.toInt() * 60 + s.toDouble()
    }
    if (codec == null) throw IllegalStateException("无法识别音频流：${text.takeLast(300)}")
    return AudioInfo(
      codec = AudioInfo.normalize(codec),
      sampleRate = sr,
      channels = ch,
      bitDepth = bits,
      bitrateKbps = br ?: runCatching {
        val size = File(pathOrUrl).length()
        if (dur != null && size > 0) (size * 8 / dur / 1000).toInt() else null
      }.getOrNull(),
      duration = dur,
    )
  }

  /** 只换封装，不重新编码。 */
  suspend fun remux(src: String, dest: File, ext: String?) {
    dest.parentFile?.mkdirs()
    val fmt = when (ext?.lowercase()) {
      "flac" -> "flac"
      "eac3" -> "eac3"
      "m4a" -> "mp4"
      else -> null
    }
    val args = mutableListOf(
      "-y", "-hide_banner", "-v", "error",
      "-i", src, "-map", "0:a:0", "-c:a", "copy", "-vn", "-sn", "-dn",
    )
    if (fmt != null) args += listOf("-f", fmt)
    args += dest.absolutePath
    val r = run(*args.toTypedArray())
    if (!r.success || !dest.exists() || dest.length() == 0L) {
      throw IllegalStateException("转封装失败: ${r.output.takeLast(400)}")
    }
  }

  /** 转成目标格式（含抽样解码验证）。 */
  suspend fun convert(src: String, dest: File, fmt: String, preset: String?) {
    dest.parentFile?.mkdirs()
    val args = mutableListOf(
      "-y", "-hide_banner", "-v", "error",
      "-i", src, "-map", "0:a:0", "-map_metadata", "-1", "-vn", "-sn", "-dn",
    )
    args += Formats.ffmpegArgs(fmt, preset)
    args += dest.absolutePath
    val r = run(*args.toTypedArray())
    if (!r.success || !dest.exists() || dest.length() == 0L) {
      throw IllegalStateException("转码失败（$fmt）: ${r.output.takeLast(400)}")
    }
    val check = run("-v", "error", "-t", "3", "-i", dest.absolutePath, "-f", "null", "-")
    if (!check.success) throw IllegalStateException("输出文件无法解码（$fmt）: ${check.output.takeLast(300)}")
  }

  /** 解码成 s16le PCM 文件（ffmpeg-kit 无法直接拿 stdout）。 */
  suspend fun decodeToPcm(
    source: String,
    out: File,
    start: Double = 0.0,
    duration: Double = 0.0,
    sampleRate: Int = 2000,
    mono: Boolean = true,
    headers: Map<String, String>? = null,
  ): Boolean {
    out.parentFile?.mkdirs()
    val target = LocalProxy.localize(source)
    val hdrs = if (target != source) null else headers
    val args = mutableListOf("-y", "-v", "error")
    if (start > 0) args += listOf("-ss", String.format(java.util.Locale.US, "%.3f", start))
    if (duration > 0) args += listOf("-t", String.format(java.util.Locale.US, "%.3f", duration))
    args += headersArgs(hdrs)
    args += listOf("-i", target, "-map", "0:a:0")
    if (mono) args += listOf("-ac", "1")
    args += listOf("-ar", sampleRate.toString(), "-f", "s16le", "-acodec", "pcm_s16le", out.absolutePath)
    val r = run(*args.toTypedArray())
    if (!r.success) {
      android.util.Log.w("Ffmpeg", "decodeToPcm 失败: ${r.output.takeLast(600)}")
    }
    return r.success && out.exists() && out.length() > 0
  }
}

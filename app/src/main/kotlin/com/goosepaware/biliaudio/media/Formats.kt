package com.goosepaware.biliaudio.media

data class Preset(val id: String, val label: String, val desc: String)

data class FormatSpec(
  val id: String,
  val label: String,
  val ext: String,
  val lossless: Boolean,
  val presets: List<Preset>,
) {
  fun preset(pid: String?): Preset =
    presets.firstOrNull { it.id == pid } ?: presets.firstOrNull() ?: Preset("default", "默认", "")
}

/**
 * 输出格式与有损参数预设（与桌面端 formats.py 完全一致）。
 */
object Formats {

  val FORMATS: LinkedHashMap<String, FormatSpec> = linkedMapOf(
    "flac" to FormatSpec(
      "flac", "FLAC 无损", ".flac", true,
      listOf(Preset("original", "原样", "保持源采样率与位深，转封装不重编码")),
    ),
    "ipod" to FormatSpec(
      "ipod", "iPod 专用（ALAC 16bit/44.1k）", ".m4a", true,
      listOf(Preset("fixed", "兼容预设", "Apple Lossless · 44.1kHz · 16bit · 立体声 · 高质量抖动")),
    ),
    "alac" to FormatSpec(
      "alac", "ALAC 无损", ".m4a", true,
      listOf(
        Preset("original", "原样", "保持源采样率与位深"),
        Preset("cd", "CD 兼容", "44.1kHz / 16bit，带抖动"),
      ),
    ),
    "wav" to FormatSpec(
      "wav", "WAV (PCM)", ".wav", true,
      listOf(
        Preset("cd", "16bit / 44.1kHz", "兼容性最好，体积大"),
        Preset("hires", "24bit / 原采样率", "保留高解析源的信息量"),
      ),
    ),
    "mp3" to FormatSpec(
      "mp3", "MP3 (LAME VBR)", ".mp3", false,
      listOf(
        Preset("low", "低 · V5", "约 130 kbps，体积最小"),
        Preset("medium", "中 · V2", "约 190 kbps，推荐"),
        Preset("high", "高 · V0", "约 245 kbps，接近透明"),
      ),
    ),
    "aac" to FormatSpec(
      "aac", "AAC (m4a)", ".m4a", false,
      listOf(
        Preset("low", "低 · 128k", "适合手机外放/播客"),
        Preset("medium", "中 · 192k", "推荐，日常听歌"),
        Preset("high", "高 · 256k", "高码率，体积较大"),
      ),
    ),
  )

  val SOURCE_FORMAT = FormatSpec("source", "跟随源格式（推荐）", "", true, emptyList())

  fun get(fmt: String): FormatSpec = if (fmt == "source") SOURCE_FORMAT else FORMATS[fmt] ?: FORMATS["flac"]!!

  fun presetChoices(fmt: String): List<Preset> = FORMATS[fmt]?.presets.orEmpty()

  fun ffmpegArgs(fmt: String, presetId: String?): List<String> {
    val spec = FORMATS[fmt] ?: return listOf("-c:a", "copy")
    val p = spec.preset(presetId)
    return when (fmt) {
      "flac" -> listOf("-c:a", "flac", "-compression_level", "8", "-f", "flac")
      "ipod" -> listOf(
        "-af", "aresample=44100:osf=s16:dither_method=triangular_hp",
        "-ar", "44100", "-sample_fmt", "s16p",
        "-c:a", "alac", "-movflags", "+faststart", "-f", "mp4",
      )
      "alac" -> if (p.id == "cd") listOf(
        "-af", "aresample=44100:osf=s16:dither_method=triangular_hp",
        "-ar", "44100", "-sample_fmt", "s16p",
        "-c:a", "alac", "-movflags", "+faststart", "-f", "mp4",
      ) else listOf("-c:a", "alac", "-movflags", "+faststart", "-f", "mp4")
      "wav" -> {
        if (p.id == "cd") listOf("-c:a", "pcm_s16le", "-ar", "44100", "-f", "wav")
        else listOf("-c:a", "pcm_s24le", "-f", "wav")
      }
      "mp3" -> listOf(
        "-c:a", "libmp3lame",
        "-q:a", mapOf("low" to "5", "medium" to "2", "high" to "0").getOrDefault(p.id, "2"),
        "-f", "mp3",
      )
      "aac" -> listOf(
        "-c:a", "aac",
        "-b:a", mapOf("low" to "128k", "medium" to "192k", "high" to "256k").getOrDefault(p.id, "192k"),
        "-f", "mp4",
      )
      else -> listOf("-c:a", "copy")
    }
  }

  fun describe(fmt: String, presetId: String?): String {
    if (fmt == "source") return "跟随源格式"
    val spec = get(fmt)
    val p = spec.preset(presetId)
    return "${spec.label} · ${p.label}（${p.desc}）"
  }

  val MIME = mapOf(
    "flac" to "audio/flac",
    "m4a" to "audio/mp4",
    "mp3" to "audio/mpeg",
    "wav" to "audio/wav",
    "eac3" to "audio/eac3",
  )

  fun mime(ext: String): String = MIME[ext.lowercase()] ?: "audio/*"
}

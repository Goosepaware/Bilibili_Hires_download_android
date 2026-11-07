package com.goosepaware.biliaudio.data

data class Page(
  val index: Int,
  val cid: Long,
  val title: String,
  val duration: Int = 0,
)

data class Video(
  val bvid: String,
  val aid: Long,
  val title: String,
  val owner: String,
  val cover: String,
  val pages: List<Page>,
  val pubdate: Long = 0,
  val desc: String = "",
)

data class Track(
  val kind: String,          // flac / dolby / aac
  val id: Int,
  val codec: String,
  val bandwidth: Int,
  val urls: List<String>,
  val label: String,
  val ext: String,           // flac / eac3 / m4a
) {
  val kbps: Int get() = Math.round(bandwidth / 1000.0).toInt()
}

data class DownloadOptions(
  val atmos: Boolean = false,
  val aacFallback: Boolean = true,
  val verify: Boolean = true,
  val tag: Boolean = true,
  val threads: Int = 4,
  val pages: String = "all",
  val fmt: String = "flac",
  val preset: String = "medium",
  val outputDir: String = "",      // SAF tree uri 或空（默认 Music/BiliAudio）
  val customTitle: String = "",
  val customArtist: String = "",
  val customAlbum: String = "",
  val coverUri: String = "",
)

data class DownloadResult(
  val video: Video,
  val page: Page,
  val track: Track,
  val savedName: String,
  val savedUri: String,
  val bytes: Long,
  val analysis: Analysis?,
)

data class Analysis(
  val codec: String?,
  val sampleRate: Int?,
  val bitDepth: Int?,
  val channels: String?,
  val bitrateKbps: Int?,
  val duration: Double?,
  val nyquist: Int,
  val losslessCodec: Boolean,
  val cutoffHz: Double?,
  val cutoffRatio: Double?,
  val steepness: Double?,
  val estimatedSourceRate: Int?,
  val verdict: String,
  val verdictText: String,
)

data class SegmentQuality(
  val index: Int,
  val start: Double,
  val end: Double,
  val cutoffHz: Double = 0.0,
  val ratio: Double = 0.0,
  val steepness: Double = 0.0,
  val peakDb: Double = -120.0,
  val clipped: Double = 0.0,
  val valid: Boolean = true,
)

data class TrackQuality(
  val track: Track,
  val codec: String,
  val sampleRate: Int,
  val bitDepth: Int?,
  val channels: String?,
  val duration: Double,
  val cutoffHz: Double,
  val ratio: Double,
  val steepness: Double,
  val score: Int,
  val grade: String,
  val summary: String,
  val segments: List<SegmentQuality> = emptyList(),
  val descriptions: List<String> = emptyList(),
)

data class QualityReport(
  val videoTitle: String,
  val bvid: String,
  val owner: String,
  val duration: Double,
  val tracks: List<TrackQuality>,
  val score: Int,
  val grade: String,
  val summary: String,
)

data class SplitSegment(
  val index: Int,
  val start: Double,
  val end: Double,
  val title: String = "",
) {
  val duration: Double get() = (end - start).coerceAtLeast(0.0)
}

data class ParsedTrack(
  val index: Int,
  val title: String,
  val start: Double,
)

data class ParseOutcome(
  val tracks: List<ParsedTrack>,
  val warnings: List<String>,
)

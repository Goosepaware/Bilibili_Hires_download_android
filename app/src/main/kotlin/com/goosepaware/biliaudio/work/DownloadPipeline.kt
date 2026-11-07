package com.goosepaware.biliaudio.work

import android.content.Context
import com.goosepaware.biliaudio.data.Analysis
import com.goosepaware.biliaudio.data.BiliApi
import com.goosepaware.biliaudio.data.BiliConst
import com.goosepaware.biliaudio.data.BiliHttp
import com.goosepaware.biliaudio.data.DownloadOptions
import com.goosepaware.biliaudio.data.DownloadResult
import com.goosepaware.biliaudio.data.Page
import com.goosepaware.biliaudio.data.RangedDownloader
import com.goosepaware.biliaudio.data.Track
import com.goosepaware.biliaudio.data.Video
import com.goosepaware.biliaudio.media.Analyzer
import com.goosepaware.biliaudio.media.Ffmpeg
import com.goosepaware.biliaudio.media.Formats
import com.goosepaware.biliaudio.media.OutputStore
import com.goosepaware.biliaudio.media.Splitter
import com.goosepaware.biliaudio.media.Tagger
import org.json.JSONObject
import java.io.File

/**
 * 单条链接 / 多 P 的完整下载流水线（对应桌面端 pipeline.py）。
 */
class DownloadPipeline(
  private val context: Context,
  private val http: BiliHttp,
  private val api: BiliApi,
  private val downloader: RangedDownloader,
) {

  private fun readCover(uri: String): ByteArray? = runCatching {
    context.contentResolver.openInputStream(android.net.Uri.parse(uri))?.use { it.readBytes() }
  }.getOrNull()?.takeIf { it.size > 1000 }

  private fun fetchCover(url: String): ByteArray? {
    if (url.isBlank()) return null
    // B 站封面常给 http:// 或 //，Android 默认禁止明文，统一改 https
    val fixed = when {
      url.startsWith("//") -> "https:$url"
      url.startsWith("http://") -> "https://" + url.removePrefix("http://")
      else -> url
    }
    return runCatching {
      val req = http.newRequest(fixed).get().build()
      http.client.newCall(req).execute().use { r ->
        if (!r.isSuccessful) return null
        val bytes = r.body?.bytes() ?: return null
        if (bytes.size > 1000) bytes else null
      }
    }.getOrNull()
  }

  suspend fun process(
    url: String,
    opts: DownloadOptions,
    onLog: (String) -> Unit = {},
    onProgress: (WorkBus.Progress) -> Unit = {},
  ): List<DownloadResult> {
    val vid = api.resolveUrl(url)
    val video = api.getVideo(vid)
    val pages = api.parsePages(opts.pages, video.pages)
    onLog("■ ${video.title}  (${video.bvid}, ${video.owner}, ${video.pages.size}P)")

    val cover = if (opts.tag && opts.coverUri.isNotBlank()) readCover(opts.coverUri) else null
    val workRoot = File(context.cacheDir, "work/${video.bvid}").apply { mkdirs() }
    val results = mutableListOf<DownloadResult>()
    var index = 0
    val totalJobs = pages.size

    for (page in pages) {
      index++
      onLog("P${page.index}：取流…")
      val dash = api.getPlayurl(video.bvid, page.cid)
      val tracks = api.selectTracks(dash)
      val (chosen, note) = api.chooseTracks(tracks, opts)
      note?.let(onLog)
      if (chosen.isEmpty()) continue

      val multi = video.pages.size > 1
      val subDir = if (multi) Splitter.safeName(video.title) else ""
      val pretty = opts.customTitle.ifBlank { if (multi) page.title else video.title }
      val stem = if (multi) "%02d %s".format(page.index, Splitter.safeName(pretty))
      else Splitter.safeName(pretty)

      for (track in chosen) {
        val tagTxt = when (track.kind) {
          "flac" -> "无损"
          "dolby" -> "全景声"
          else -> "AAC"
        }
        val targetExt = if (opts.fmt == "source") track.ext else Formats.get(opts.fmt).ext.trimStart('.')
        val finalName = stem + (if (track.kind == "dolby") " [Atmos]" else "") + "." + targetExt
        onLog("↓ P${page.index} ${track.label} [$tagTxt] ${track.kbps} kb/s → ${Formats.describe(opts.fmt, opts.preset)}")

        val stage = "P${page.index} · ${track.label}"
        val freshUrls = { api.selectTracks(api.getPlayurl(video.bvid, page.cid))
          .firstOrNull { it.kind == track.kind }?.urls.orEmpty() }

        val temp = File(workRoot, "P${page.index}_${track.kind}.m4s")
        val rendered = File(workRoot, finalName)
        var lastEmit = 0L
        try {
          downloader.download(
            urls = track.urls,
            dest = temp,
            threads = opts.threads,
            progress = { done, total, speed ->
              val now = System.currentTimeMillis()
              if (now - lastEmit > 200 || done >= total) {
                lastEmit = now
                val eta = if (speed > 0) (total - done) / speed else 0.0
                onProgress(
                  WorkBus.Progress(
                    title = video.title, stage = "$stage · 下载中",
                    done = done, total = total, speed = speed, eta = eta,
                    index = index, count = totalJobs,
                  )
                )
              }
            },
            refresh = { freshUrls() },
            replace = true,
          )

          onProgress(
            WorkBus.Progress(
              title = video.title, stage = "$stage · 转换中",
              index = index, count = totalJobs,
            )
          )
          if (opts.fmt == "source" || (opts.fmt == "flac" && track.kind == "flac")) {
            Ffmpeg.remux(temp.absolutePath, rendered, targetExt)
          } else {
            if (Formats.get(opts.fmt).lossless && track.kind in listOf("aac", "dolby")) {
              onLog("  ! 源本身是有损音轨，转成无损格式只是换容器，不会恢复音质")
            }
            Ffmpeg.convert(temp.absolutePath, rendered, opts.fmt, opts.preset)
          }

          val info = Ffmpeg.probe(rendered.absolutePath)
          val analysis: Analysis = if (opts.verify && opts.fmt in listOf("source", "flac")) {
            onProgress(
              WorkBus.Progress(
                title = video.title, stage = "$stage · 核验音质",
                index = index, count = totalJobs,
              )
            )
            Analyzer.analyzeFile(rendered, workRoot)
          } else {
            Analysis(
              codec = info.codec, sampleRate = info.sampleRate, bitDepth = info.bitDepth,
              channels = info.channels, bitrateKbps = info.bitrateKbps, duration = info.duration,
              nyquist = info.nyquist, losslessCodec = Formats.get(opts.fmt).lossless,
              cutoffHz = null, cutoffRatio = null, steepness = null, estimatedSourceRate = null,
              verdict = "CONVERTED",
              verdictText = "已转换为 ${Formats.describe(opts.fmt, opts.preset)}",
            )
          }

          if (opts.tag) {
            Tagger.tag(
              rendered,
              title = opts.customTitle.ifBlank { if (multi) page.title else video.title },
              artist = opts.customArtist.ifBlank { video.owner },
              album = opts.customAlbum,
              track = page.index,
              total = video.pages.size,
              comment = "bilibili ${video.bvid} P${page.index} | ${track.label} | ${Formats.describe(opts.fmt, opts.preset)}",
              cover = cover,
            )
          }

          val saved = OutputStore.publish(context, rendered, targetExt, subDir, opts.outputDir)
          writeReport(video, page, track, analysis)
          val size = rendered.length()
          onLog("  ✓ ${saved.name}  ${humanSize(size)}")
          if (analysis.cutoffHz != null) {
            onLog("    有效带宽 ${"%.1f".format(analysis.cutoffHz!! / 1000)} kHz · ${analysis.verdictText}")
          }

          results += DownloadResult(
            video = video, page = page, track = track,
            savedName = saved.name, savedUri = saved.uri, bytes = size, analysis = analysis,
          )
        } finally {
          temp.delete()
          File(temp.parentFile, temp.name + ".part").delete()
          File(temp.parentFile, temp.name + ".part.json").delete()
          rendered.delete()
        }
      }
    }
    return results
  }

  private fun writeReport(video: Video, page: Page, track: Track, analysis: Analysis) {
    runCatching {
      val dir = File(context.filesDir, "reports").apply { mkdirs() }
      val payload = JSONObject()
        .put("video", JSONObject().put("bvid", video.bvid).put("title", video.title).put("owner", video.owner))
        .put("page", JSONObject().put("index", page.index).put("cid", page.cid).put("title", page.title))
        .put(
          "track",
          JSONObject().put("kind", track.kind).put("id", track.id)
            .put("codec", track.codec).put("bandwidth", track.bandwidth).put("label", track.label)
        )
        .put(
          "audio",
          JSONObject()
            .put("codec", analysis.codec).put("sample_rate", analysis.sampleRate)
            .put("bit_depth", analysis.bitDepth).put("channels", analysis.channels)
            .put("bitrate_kbps", analysis.bitrateKbps).put("duration", analysis.duration)
            .put("cutoff_hz", analysis.cutoffHz).put("steepness", analysis.steepness)
            .put("verdict", analysis.verdict).put("verdict_text", analysis.verdictText)
        )
      File(dir, "${video.bvid}_P${page.index}_${track.kind}.json").writeText(payload.toString(2))
    }
  }

  private fun humanSize(n: Long): String {
    var v = n.toDouble()
    for (unit in listOf("B", "KiB", "MiB", "GiB")) {
      if (v < 1024 || unit == "GiB") {
        return if (unit == "B") "${v.toInt()} B" else "%.1f %s".format(v, unit)
      }
      v /= 1024
    }
    return "%.1f GiB".format(v)
  }

  @Suppress("unused")
  private fun coverHeaders(): Map<String, String> =
    mapOf("Referer" to BiliConst.REFERER, "User-Agent" to BiliConst.UA)
}

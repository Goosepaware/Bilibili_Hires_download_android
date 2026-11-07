package com.goosepaware.biliaudio.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.goosepaware.biliaudio.BiliApp
import com.goosepaware.biliaudio.data.DownloadOptions
import com.goosepaware.biliaudio.data.ParsedTrack
import com.goosepaware.biliaudio.data.Settings
import com.goosepaware.biliaudio.data.SplitSegment
import com.goosepaware.biliaudio.media.Ffmpeg
import com.goosepaware.biliaudio.media.Formats
import com.goosepaware.biliaudio.media.OutputStore
import com.goosepaware.biliaudio.media.SavedFile
import com.goosepaware.biliaudio.media.Splitter
import com.goosepaware.biliaudio.media.TrackListParser
import com.goosepaware.biliaudio.work.DownloadService
import com.goosepaware.biliaudio.work.WorkBus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

private data class TransferUi(
  val active: Boolean = false,
  val stage: String = "",
  val done: Long = 0,
  val total: Long = 0,
  val speed: Double = 0.0,
  val eta: Double = 0.0,
)

private fun fmtSpeed(bytesPerSec: Double): String {
  if (bytesPerSec <= 0 || bytesPerSec.isNaN() || bytesPerSec.isInfinite()) return "—"
  return String.format(Locale.US, "%.1f MB/s", bytesPerSec / 1048576.0)
}

private fun fmtEta(seconds: Double): String {
  if (seconds <= 0 || seconds.isNaN() || seconds.isInfinite()) return "—"
  return fmtDuration(seconds)
}

@Composable
fun SplitScreen(modifier: Modifier = Modifier) {
  val context = LocalContext.current
  val app = context.applicationContext as BiliApp

  var mode by rememberSaveable { mutableStateOf("list") }
  var url by rememberSaveable { mutableStateOf("") }
  var localPath by remember { mutableStateOf("") }
  var localLabel by remember { mutableStateOf("") }

  var title by remember { mutableStateOf("") }
  var owner by remember { mutableStateOf("") }
  var duration by remember { mutableStateOf(0.0) }
  var source by remember { mutableStateOf("") }
  var isLocal by remember { mutableStateOf(false) }
  var sourceExt by remember { mutableStateOf("flac") }
  var bvid by remember { mutableStateOf("") }
  var cid by remember { mutableStateOf(0L) }
  var kind by remember { mutableStateOf("flac") }
  var cachedAudio by remember { mutableStateOf("") }

  var samples by remember { mutableStateOf<ShortArray?>(null) }
  var peaks by remember { mutableStateOf<List<Pair<Float, Float>>>(emptyList()) }
  var markers by remember { mutableStateOf<List<Double>>(emptyList()) }
  var loaded by remember { mutableStateOf(false) }
  var busy by remember { mutableStateOf(false) }
  var busyText by remember { mutableStateOf("") }
  val transfer = remember { MutableStateFlow(TransferUi()) }
  val tr by transfer.collectAsStateWithLifecycle()

  var threshold by rememberSaveable { mutableStateOf(-35f) }
  var minSilence by rememberSaveable { mutableStateOf(1.5f) }

  var listText by rememberSaveable { mutableStateOf("") }
  var parsed by remember { mutableStateOf<List<ParsedTrack>>(emptyList()) }
  var parseWarnings by remember { mutableStateOf<List<String>>(emptyList()) }

  var artist by rememberSaveable { mutableStateOf("") }
  var album by rememberSaveable { mutableStateOf("") }
  var coverBytes by remember { mutableStateOf<ByteArray?>(null) }
  var coverName by remember { mutableStateOf("") }
  var useVideoCover by rememberSaveable { mutableStateOf(false) }
  var videoCoverUrl by remember { mutableStateOf("") }
  val segmentTitles = remember { mutableStateMapOf<Int, String>() }
  var splitFmt by rememberSaveable { mutableStateOf("source") }
  var splitPreset by rememberSaveable { mutableStateOf("medium") }
  var treeUri by rememberSaveable { mutableStateOf("") }

  var error by remember { mutableStateOf<String?>(null) }
  var doneMessage by remember { mutableStateOf<String?>(null) }
  var lastSaved by remember { mutableStateOf<SavedFile?>(null) }

  val running by WorkBus.running.collectAsStateWithLifecycle()
  val progress by WorkBus.progress.collectAsStateWithLifecycle()
  val logs by WorkBus.logs.collectAsStateWithLifecycle()
  val showLog by com.goosepaware.biliaudio.data.UiPrefs.showLog.collectAsStateWithLifecycle()

  LaunchedEffect(Unit) {
    val s = withContext(Dispatchers.IO) { app.settingsStore.load() }
    treeUri = s.outputTreeUri
    if (url.isBlank()) {
      com.goosepaware.biliaudio.data.ShareInbox.last?.let { url = it }
    }
  }

  fun baseSegments(): List<SplitSegment> =
    if (mode == "list") Splitter.segmentsFromTracks(parsed, duration)
    else if (samples == null) emptyList()
    else Splitter.segmentsFromPoints(markers, duration)

  fun segments(): List<SplitSegment> =
    baseSegments().map { seg -> seg.copy(title = segmentTitles[seg.index] ?: seg.title) }

  LaunchedEffect(mode, parsed, markers, duration, samples) {
    baseSegments().forEach { seg ->
      if (segmentTitles[seg.index].isNullOrBlank() && seg.title.isNotBlank()) {
        segmentTitles[seg.index] = seg.title
      }
    }
  }

  fun refreshSilence() {
    val s = samples ?: return
    val gaps = Splitter.detectSilence(s, threshold.toDouble(), minSilence.toDouble())
    val pts = Splitter.splitsFromGaps(gaps, duration)
    markers = pts
    WorkBus.log("检测到 ${gaps.size} 处静音，生成 ${pts.size} 个分割点")
  }

  suspend fun ensureSource(): Boolean {
    if (source.isNotBlank()) return true
    if (isLocal) return false
    val b = bvid
    val c = cid
    if (b.isBlank() || c == 0L) return false
    val track = withContext(Dispatchers.IO) {
      val tracks = app.api.selectTracks(app.api.getPlayurl(b, c))
      val (chosen, _) = app.api.chooseTracks(tracks, DownloadOptions())
      chosen.firstOrNull() ?: throw IllegalStateException("没有可用音轨")
    }
    withContext(Dispatchers.Main) {
      source = track.urls.first()
      sourceExt = track.ext
      kind = track.kind
    }
    return true
  }

  suspend fun prepareLocalAudio(): File {
    if (isLocal && source.isNotBlank()) return File(source)
    val cached = cachedAudio.takeIf { it.isNotBlank() }?.let { File(it) }
    if (cached != null && cached.exists() && cached.length() > 0) return cached

    ensureSource()
    val audioUrl = source
    if (audioUrl.isBlank()) throw IllegalStateException("没有可用音轨")
    val ext = sourceExt.ifBlank { "flac" }
    val work = File(context.cacheDir, "split").apply { mkdirs() }
    val dest = File(work, "audio_${bvid}_${cid}.$ext")
    if (dest.exists() && dest.length() > 0) {
      withContext(Dispatchers.Main) { cachedAudio = dest.absolutePath }
      return dest
    }

    withContext(Dispatchers.Main) {
      transfer.value = TransferUi(active = true, stage = "下载完整音频…")
    }
    val lastEmit = AtomicLong(0)
    app.downloader.download(
      urls = listOf(audioUrl),
      dest = dest,
      threads = 4,
      progress = { done, total, speed ->
        val now = System.currentTimeMillis()
        if (now - lastEmit.get() > 200 || done >= total) {
          lastEmit.set(now)
          val eta = if (speed > 0) (total - done) / speed else 0.0
          transfer.value = transfer.value.copy(
            active = true,
            stage = "下载完整音频…",
            done = done,
            total = total,
            speed = speed,
            eta = eta,
          )
          WorkBus.update {
            it.copy(
              stage = "下载完整音频…",
              done = done,
              total = total,
              speed = speed,
              eta = eta,
            )
          }
        }
      },
      refresh = {
        val tracks = app.api.selectTracks(app.api.getPlayurl(bvid, cid))
        tracks.firstOrNull { it.kind == kind }?.urls ?: emptyList()
      },
    )
    withContext(Dispatchers.Main) { cachedAudio = dest.absolutePath }
    return dest
  }

  suspend fun prepareWaveformAudio(): File {
    // 已经下载过完整音频时直接复用，不再额外下载代理
    val cached = cachedAudio.takeIf { it.isNotBlank() }?.let { File(it) }
    if (cached != null && cached.exists() && cached.length() > 0) return cached
    if (isLocal) return prepareLocalAudio()
    if (bvid.isBlank() || cid == 0L) return prepareLocalAudio()

    val proxy = withContext(Dispatchers.IO) {
      runCatching {
        val tracks = app.api.selectTracks(app.api.getPlayurl(bvid, cid))
        tracks.firstOrNull { it.kind == "aac" && it.urls.isNotEmpty() }
      }.getOrNull()
    } ?: return prepareLocalAudio()

    val ext = proxy.ext.ifBlank { "m4a" }
    val work = File(context.cacheDir, "split").apply { mkdirs() }
    val dest = File(work, "proxy_${bvid}_${cid}.$ext")
    if (dest.exists() && dest.length() > 0) return dest

    withContext(Dispatchers.Main) {
      WorkBus.log("使用 AAC 代理载入波形：${proxy.label}（下载更快，仅用于波形/静音检测）")
      transfer.value = TransferUi(active = true, stage = "下载 AAC 代理…")
    }
    val lastEmit = AtomicLong(0)
    val ok = runCatching {
      app.downloader.download(
        urls = proxy.urls,
        dest = dest,
        threads = 4,
        progress = { done, total, speed ->
          val now = System.currentTimeMillis()
          if (now - lastEmit.get() > 200 || done >= total) {
            lastEmit.set(now)
            val eta = if (speed > 0) (total - done) / speed else 0.0
            transfer.value = transfer.value.copy(
              active = true,
              stage = "下载 AAC 代理…",
              done = done,
              total = total,
              speed = speed,
              eta = eta,
            )
            WorkBus.update {
              it.copy(
                stage = "下载 AAC 代理…",
                done = done,
                total = total,
                speed = speed,
                eta = eta,
              )
            }
          }
        },
        refresh = {
          val tracks = app.api.selectTracks(app.api.getPlayurl(bvid, cid))
          tracks.firstOrNull { it.kind == "aac" }?.urls ?: emptyList()
        },
      )
    }.isSuccess
    if (!ok) {
      withContext(Dispatchers.Main) { WorkBus.log("AAC 代理下载失败，改用原始音轨") }
      runCatching { dest.delete() }
      return prepareLocalAudio()
    }
    return dest
  }

  fun load() {
    if (busy || running) return
    busy = true
    busyText = "读取视频信息…"
    transfer.value = TransferUi(active = true, stage = busyText)
    error = null
    loaded = false
    samples = null
    peaks = emptyList()
    markers = emptyList()
    parsed = emptyList()
    parseWarnings = emptyList()
    source = ""
    sourceExt = "flac"
    kind = ""
    cachedAudio = ""
    WorkBus.clearLogs()
    app.scope.launch {
      try {
        if (localPath.isNotBlank()) {
          val path = localPath
          val label = localLabel
          val pi = withContext(Dispatchers.IO) { Ffmpeg.probe(path) }
          val dur = pi.duration ?: 0.0
          withContext(Dispatchers.Main) {
            title = label.substringBeforeLast('.')
            owner = "未知歌手"
            duration = dur
            source = path
            isLocal = true
            sourceExt = path.substringAfterLast('.', "flac")
            bvid = ""
            cid = 0
            kind = "local"
            artist = owner
            album = title
            videoCoverUrl = ""
            loaded = true
            busy = false
            transfer.value = TransferUi()
            WorkBus.log("已载入本地音频：${title} · ${fmtDuration(dur)}")
          }
        } else {
          val vid = withContext(Dispatchers.IO) { app.api.resolveUrl(url) }
          val v = withContext(Dispatchers.IO) { app.api.getVideo(vid) }
          val page = v.pages.firstOrNull() ?: throw IllegalStateException("视频没有可用的分 P")
          val dur = page.duration.toDouble()
          withContext(Dispatchers.Main) {
            title = v.title
            owner = v.owner
            duration = dur
            source = ""
            isLocal = false
            sourceExt = "flac"
            bvid = v.bvid
            cid = page.cid
            kind = ""
            artist = v.owner
            album = v.title
            videoCoverUrl = v.cover
            loaded = true
            busy = false
            transfer.value = TransferUi()
            WorkBus.log("已载入视频信息：${v.title} · ${fmtDuration(dur)}")
            WorkBus.log("默认按时间列表分轨，音频只在导出时下载")
          }
        }
      } catch (e: Throwable) {
        withContext(Dispatchers.Main) {
          busy = false
          transfer.value = TransferUi()
          error = e.message ?: "载入失败"
        }
      }
    }
  }

  fun copyLocal(uri: Uri) {
    busy = true
    busyText = "读取本地音频…"
    transfer.value = TransferUi(active = true, stage = "读取本地音频…")
    app.scope.launch {
      try {
        val name = queryDisplayName(context, uri) ?: "local.audio"
        val dest = File(context.cacheDir, "split/local_${System.nanoTime()}_${Splitter.safeName(name)}")
        dest.parentFile?.mkdirs()
        withContext(Dispatchers.IO) {
          context.contentResolver.openInputStream(uri)?.use { input ->
            dest.outputStream().use { input.copyTo(it) }
          } ?: throw IllegalStateException("无法读取所选文件")
        }
        withContext(Dispatchers.Main) {
          localPath = dest.absolutePath
          localLabel = name
          busy = false
          transfer.value = TransferUi()
          load()
        }
      } catch (e: Throwable) {
        withContext(Dispatchers.Main) {
          busy = false
          transfer.value = TransferUi()
          error = e.message
        }
      }
    }
  }

  fun loadWaveform() {
    if (busy || running) return
    busy = true
    busyText = "解析音轨…"
    transfer.value = TransferUi(active = true, stage = "解析音轨…")
    error = null
    WorkBus.start(title.ifBlank { "载入波形" }, clearLogs = false)
    WorkBus.update { it.copy(stage = "解析音轨…") }
    DownloadService.start(context)
    app.scope.launch {
      try {
        val audio = if (com.goosepaware.biliaudio.data.UiPrefs.waveformAacProxy.value) {
          prepareWaveformAudio()
        } else {
          prepareLocalAudio()
        }
        withContext(Dispatchers.Main) {
          busyText = "解码波形（2 kHz 单声道）…"
          transfer.value = TransferUi(active = true, stage = "解码波形（2 kHz 单声道）…")
          WorkBus.update {
            it.copy(stage = "解码波形（2 kHz 单声道）…", done = 0, total = 0, speed = 0.0, eta = 0.0)
          }
        }
        val work = File(context.cacheDir, "split").apply { mkdirs() }
        val pcm = withContext(Dispatchers.IO) {
          Splitter.decodeAnalysisPcm(audio.absolutePath, work, null)
        }
        val p = withContext(Dispatchers.Default) { Splitter.waveformPeaks(pcm) }
        val gaps = withContext(Dispatchers.Default) {
          Splitter.detectSilence(pcm, threshold.toDouble(), minSilence.toDouble())
        }
        val pts = Splitter.splitsFromGaps(gaps, duration)
        withContext(Dispatchers.Main) {
          samples = pcm
          peaks = p
          markers = pts
          busy = false
          transfer.value = TransferUi()
          WorkBus.log("波形载入完成：${fmtDuration(duration)} · ${pcm.size / Splitter.ANALYSIS_SR} 秒")
          WorkBus.log("检测到 ${gaps.size} 处静音，生成 ${pts.size} 个分割点")
        }
      } catch (e: Throwable) {
        withContext(Dispatchers.Main) {
          busy = false
          transfer.value = TransferUi()
          error = e.message ?: "载入波形失败"
        }
      } finally {
        WorkBus.finish()
        DownloadService.stop(context)
      }
    }
  }

  fun parseList() {
    if (!loaded) {
      error = "请先载入音轨"
      return
    }
    val result = TrackListParser.parse(listText, duration)
    parsed = result.tracks
    parseWarnings = result.warnings
    if (result.tracks.isEmpty()) error = "没有识别到曲目"
  }

  suspend fun fetchVideoCover(url: String): ByteArray? = withContext(Dispatchers.IO) {
    if (url.isBlank()) return@withContext null
    val fixed = when {
      url.startsWith("//") -> "https:$url"
      url.startsWith("http://") -> "https://" + url.removePrefix("http://")
      else -> url
    }
    runCatching {
      val req = app.http.newRequest(fixed).get().build()
      app.http.client.newCall(req).execute().use { r ->
        if (!r.isSuccessful) return@runCatching null
        val bytes = r.body?.bytes() ?: return@runCatching null
        if (bytes.size > 1000) bytes else null
      }
    }.getOrNull()
  }

  fun export() {
    val segs = segments()
    if (segs.isEmpty()) {
      error = "没有可导出的分段"
      return
    }
    val work = File(context.cacheDir, "split").apply { mkdirs() }
    val albumName = album.ifBlank { title }
    val artistName = artist.ifBlank { owner }
    lastSaved = null

    app.scope.launch {
      WorkBus.start(albumName)
      DownloadService.start(context)
      try {
        withContext(Dispatchers.Main) {
          busy = true
          busyText = "准备音频…"
          transfer.value = TransferUi(active = true, stage = "准备音频…")
        }
        WorkBus.update { it.copy(stage = "准备音频…") }
        val audio = prepareLocalAudio()
        val cover = coverBytes ?: if (useVideoCover) fetchVideoCover(videoCoverUrl) else null
        withContext(Dispatchers.Main) {
          busy = false
          transfer.value = TransferUi()
        }
        WorkBus.log("—— 开始导出 ${segs.size} 轨 ——")
        WorkBus.update {
          it.copy(
            stage = "导出中…", index = 0, count = segs.size,
            done = 0, total = 0, speed = 0.0, eta = 0.0,
          )
        }
        val n = Splitter.exportSegments(
          source = audio.absolutePath,
          segments = segs,
          workDir = work,
          ext = sourceExt,
          fmt = splitFmt,
          preset = splitPreset,
          album = albumName,
          artist = artistName,
          cover = cover,
          headers = null,
          titles = true,
          refresh = null,
          onFile = { file, name ->
            val ext = name.substringAfterLast('.', sourceExt)
            val saved = OutputStore.publish(context, file, ext, Splitter.safeName(albumName), treeUri)
            if (lastSaved == null) lastSaved = saved
            WorkBus.log("  ✓ ${saved.name}")
          },
          onProgress = { i, total, name ->
            WorkBus.update {
              it.copy(
                stage = "导出 $i/$total",
                index = i, count = total,
                done = i.toLong(), total = total.toLong(),
              )
            }
          },
        )
        WorkBus.log("—— 完成，共 $n 个文件 ——")
        withContext(Dispatchers.Main) { doneMessage = "已导出 $n 个文件" }
      } catch (e: Throwable) {
        WorkBus.log("✗ ${e.message}")
        withContext(Dispatchers.Main) { error = e.message ?: "导出失败" }
      } finally {
        withContext(Dispatchers.Main) {
          busy = false
          transfer.value = TransferUi()
        }
        DownloadService.stop(context)
        WorkBus.finish()
      }
    }
  }

  val audioPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
    if (uri != null) {
      runCatching {
        context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
      }
      localPath = ""
      copyLocal(uri)
    }
  }

  val coverPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
    if (uri != null) {
      runCatching {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        if (bytes != null && bytes.isNotEmpty()) {
          coverBytes = bytes
          coverName = queryDisplayName(context, uri) ?: "cover"
        }
      }
    }
  }

  val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
    if (uri != null) {
      runCatching {
        context.contentResolver.takePersistableUriPermission(
          uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
      }
      treeUri = uri.toString()
      app.scope.launch {
        val s = withContext(Dispatchers.IO) { app.settingsStore.load() }
        withContext(Dispatchers.IO) { app.settingsStore.save(s.copy(outputTreeUri = treeUri)) }
      }
    }
  }

  Column(modifier.fillMaxSize()) {
    Column(
      Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(12.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      SectionCard("音源") {
        PasteField(
          label = "视频链接 / BV 号",
          value = url,
          onValueChange = { url = it },
          placeholder = "https://www.bilibili.com/video/BV...",
          linkOnly = true,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          Button(onClick = { localPath = ""; load() }, enabled = !busy && !running && url.isNotBlank()) {
            Text("载入视频信息")
          }
          OutlinedButton(onClick = { audioPicker.launch(arrayOf("*/*")) }, enabled = !busy && !running) {
            Text("本地文件")
          }
        }
        if (localLabel.isNotBlank()) {
          Text("本地：$localLabel", style = MaterialTheme.typography.labelSmall)
        }
        if (loaded) {
          Text(title, style = MaterialTheme.typography.bodyMedium)
          Text(
            "${owner} · ${fmtDuration(duration)} · ${duration.toInt()} 秒",
            style = MaterialTheme.typography.labelSmall,
          )
          Text(
            if (mode == "list") "已读取视频时长，未下载音频" else "已读取时长，载入波形时才下载音频",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
          )
        }
      }

      if (loaded) {
        SectionCard("分轨方式") {
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
              selected = mode == "silence",
              onClick = { mode = "silence" },
              label = { Text("静音检测") },
            )
            FilterChip(
              selected = mode == "list",
              onClick = { mode = "list" },
              label = { Text("时间列表") },
            )
          }
          Text(
            if (mode == "list") "默认：按视频时长 + 曲目表时间切分，不下载音频"
            else "静音检测需要先载入波形，会下载完整音频",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          if (mode == "silence") {
            Text("静音阈值：${threshold.toInt()} dB", style = MaterialTheme.typography.labelSmall)
            Slider(
              value = threshold, onValueChange = { threshold = it },
              valueRange = -60f..-10f, steps = 49,
            )
            Text("最短静音：${"%.1f".format(minSilence)} 秒", style = MaterialTheme.typography.labelSmall)
            Slider(
              value = minSilence, onValueChange = { minSilence = it },
              valueRange = 0.3f..10f, steps = 96,
            )
            if (samples != null) {
              Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { refreshSilence() }) { Text("重新检测") }
                OutlinedButton(onClick = {
                  if (markers.isEmpty()) {
                    return@OutlinedButton
                  }
                  markers = emptyList()
                }) { Text("清除分割点") }
              }
            }
          } else {
            PasteField(
              label = "曲目表（整段粘贴）",
              value = listText,
              onValueChange = { listText = it },
              placeholder = "把简介/评论里的曲目表整段粘贴进来，例如：\n1. 忘れられないの  0:00\n2. マッチとピーナッツ  3:58\n3. 陽炎  8:28\n软件会自动识别序号、歌名和时间，不要求格式统一。",
              minLines = 4,
              maxLines = 8,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              OutlinedButton(onClick = { parseList() }) { Text("解析曲目表") }
              if (parsed.isNotEmpty()) {
                Text("${parsed.size} 首", Modifier.align(androidx.compose.ui.Alignment.CenterVertically))
              }
            }
            parseWarnings.forEach {
              Text("· $it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            }
          }
        }

        if (mode == "silence") {
          SectionCard("波形与静音检测") {
            if (samples == null) {
              Text(
                "尚未载入波形。静音检测需要下载完整音频，载入后可拖动红线调整分割点。",
                style = MaterialTheme.typography.bodySmall,
              )
              Button(
                onClick = { loadWaveform() },
                enabled = !busy && !running,
                modifier = Modifier.fillMaxWidth(),
              ) { Text("载入波形") }
            } else {
              WaveformCanvas(
                peaks = peaks,
                markers = markers,
                duration = duration,
                editable = true,
                onMarkersChange = { markers = it },
              )
              Text(
                "拖动红线调整分割点；点空白处新增",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }
        } else {
          SectionCard("时间轴（视频坐标）") {
            WaveformCanvas(
              peaks = emptyList(),
              markers = parsed.map { it.start },
              duration = duration,
              editable = false,
              onMarkersChange = {},
            )
            Text(
              "未下载音频。按曲目表时间切分，导出时才下载完整音频。",
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }

        val segs = segments()
        SectionCard("分段（${segs.size} 段）") {
          if (segs.isEmpty()) {
            Text("没有分段", style = MaterialTheme.typography.bodySmall)
          } else {
            segs.take(60).forEach { s ->
              Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                  "%02d  %s".format(s.index, fmtDuration(s.start)),
                  style = MaterialTheme.typography.labelSmall,
                  modifier = Modifier.width(92.dp),
                )
                OutlinedTextField(
                  value = segmentTitles[s.index] ?: s.title,
                  onValueChange = { segmentTitles[s.index] = it },
                  modifier = Modifier.weight(1f),
                  singleLine = true,
                  textStyle = MaterialTheme.typography.bodySmall,
                  placeholder = { Text("歌名", style = MaterialTheme.typography.bodySmall) },
                )
              }
            }
            if (segs.size > 60) Text("… 共 ${segs.size} 段", style = MaterialTheme.typography.labelSmall)
          }
        }

        SectionCard("元数据") {
          PasteField(label = "歌手", value = artist, onValueChange = { artist = it })
          PasteField(label = "专辑（同时作为文件夹名）", value = album, onValueChange = { album = it })
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { coverPicker.launch(arrayOf("image/*")) }) { Text("选择封面") }
            if (coverBytes != null) {
              Text(coverName, style = MaterialTheme.typography.labelSmall)
              TextButton(onClick = { coverBytes = null; coverName = "" }) { Text("清除") }
            }
          }
          Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = useVideoCover, onCheckedChange = { useVideoCover = it })
            Text("将视频封面作为专辑封面", style = MaterialTheme.typography.bodySmall)
          }
        }

        SectionCard("导出") {
          Dropdown(
            label = "格式",
            options = listOf("跟随源格式" to "source") + Formats.FORMATS.map { it.value.label to it.key },
            selected = splitFmt,
            onSelect = {
              splitFmt = it
              val ps = Formats.presetChoices(it)
              if (ps.isNotEmpty()) splitPreset = ps.first().id
            },
          )
          val ps = Formats.presetChoices(splitFmt)
          if (ps.isNotEmpty()) {
            Dropdown(
              label = "预设",
              options = ps.map { "${it.label} — ${it.desc}" to it.id },
              selected = splitPreset,
              onSelect = { splitPreset = it },
            )
          }
          Text(
            "输出：${if (treeUri.isBlank()) "内部存储/Music/BiliAudio" else "已选文件夹"}",
            style = MaterialTheme.typography.labelSmall,
          )
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { folderPicker.launch(null) }) { Text("选择文件夹") }
            if (treeUri.isNotBlank()) {
              TextButton(onClick = { treeUri = "" }) { Text("恢复默认") }
            }
          }
          Text(
            if (mode == "list") "导出前会先下载完整音频，再按视频坐标逐轨切分"
            else "导出前会先下载完整音频，再按静音分割点逐轨切分",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          Button(
            onClick = { export() },
            enabled = !busy && !running && segs.isNotEmpty(),
            modifier = Modifier.fillMaxWidth(),
          ) { Text(if (running) "任务进行中…" else "开始导出（${segs.size} 轨）") }

          if (running && !tr.active) {
            progress?.let { p ->
              Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(p.stage, style = MaterialTheme.typography.labelSmall)
                if (p.total > 0) {
                  LinearProgressIndicator(
                    progress = { (p.done.toFloat() / p.total).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                  )
                } else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
              }
            }
          }
        }
      }
    }

    if (busy && tr.active) {
      TransferCard(tr)
    }
    if (showLog) {
      LogPanel(logs, Modifier.height(130.dp).fillMaxWidth())
    }
  }

  doneMessage?.let { msg ->
    DoneDialog(
      title = "导出完成",
      message = msg + (lastSaved?.let { "\n${it.name}" } ?: ""),
      onOpen = {
        lastSaved?.let { saved ->
          OutputStore.openFile(context, saved, Formats.mime(saved.name.substringAfterLast('.', "")))
        }
      },
      onDismiss = { doneMessage = null },
    )
  }

  error?.let { msg ->
    AlertDialog(
      onDismissRequest = { error = null },
      title = { Text("提示") },
      text = { Text(msg) },
      confirmButton = { TextButton(onClick = { error = null }) { Text("确定") } },
    )
  }
}


@Composable
private fun TransferCard(t: TransferUi) {
  Column(
    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
    verticalArrangement = Arrangement.spacedBy(3.dp),
  ) {
    if (t.total > 0) {
      val pct = ((t.done * 100) / t.total).coerceIn(0, 100)
      LinearProgressIndicator(
        progress = { (t.done.toFloat() / t.total).coerceIn(0f, 1f) },
        modifier = Modifier.fillMaxWidth(),
      )
      Text("${t.stage} · $pct%", style = MaterialTheme.typography.labelSmall)
      Text(
        "${fmtSize(t.done)} / ${fmtSize(t.total)} · ${fmtSpeed(t.speed)} · 剩余 ${fmtEta(t.eta)}",
        style = MaterialTheme.typography.labelSmall,
      )
    } else {
      LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
      Text(t.stage, style = MaterialTheme.typography.labelSmall)
    }
  }
}

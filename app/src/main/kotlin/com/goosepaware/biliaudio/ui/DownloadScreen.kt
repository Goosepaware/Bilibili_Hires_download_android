package com.goosepaware.biliaudio.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.goosepaware.biliaudio.BiliApp
import com.goosepaware.biliaudio.data.Account
import com.goosepaware.biliaudio.data.DownloadOptions
import com.goosepaware.biliaudio.data.DownloadResult
import com.goosepaware.biliaudio.data.Settings
import com.goosepaware.biliaudio.data.Track
import com.goosepaware.biliaudio.data.Video
import com.goosepaware.biliaudio.media.Formats
import com.goosepaware.biliaudio.media.OutputStore
import com.goosepaware.biliaudio.media.SavedFile
import com.goosepaware.biliaudio.work.DownloadService
import com.goosepaware.biliaudio.work.WorkBus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun DownloadScreen(modifier: Modifier = Modifier) {
  val context = LocalContext.current
  val app = context.applicationContext as BiliApp
  val scope = rememberCoroutineScope()

  var url by rememberSaveable { mutableStateOf("") }
  var video by remember { mutableStateOf<Video?>(null) }
  var tracks by remember { mutableStateOf<List<Track>>(emptyList()) }
  var note by remember { mutableStateOf<String?>(null) }
  var parsing by remember { mutableStateOf(false) }
  var parseError by remember { mutableStateOf<String?>(null) }

  var fmt by rememberSaveable { mutableStateOf("flac") }
  var preset by rememberSaveable { mutableStateOf("medium") }
  var atmos by rememberSaveable { mutableStateOf(false) }
  var aacFallback by rememberSaveable { mutableStateOf(true) }
  var verify by rememberSaveable { mutableStateOf(true) }
  var tag by rememberSaveable { mutableStateOf(true) }
  var pages by rememberSaveable { mutableStateOf("all") }
  var treeUri by rememberSaveable { mutableStateOf("") }
  var loaded by remember { mutableStateOf(false) }

  var customTitle by rememberSaveable { mutableStateOf("") }
  var customArtist by rememberSaveable { mutableStateOf("") }
  var customAlbum by rememberSaveable { mutableStateOf("") }
  var coverUri by rememberSaveable { mutableStateOf("") }
  var coverName by remember { mutableStateOf("") }

  var lastSaved by remember { mutableStateOf<DownloadResult?>(null) }
  var doneMessage by remember { mutableStateOf<String?>(null) }
  var errorMessage by remember { mutableStateOf<String?>(null) }

  val running by WorkBus.running.collectAsStateWithLifecycle()
  val progress by WorkBus.progress.collectAsStateWithLifecycle()
  val logs by WorkBus.logs.collectAsStateWithLifecycle()
  val showLog by com.goosepaware.biliaudio.data.UiPrefs.showLog.collectAsStateWithLifecycle()

  val incoming by com.goosepaware.biliaudio.data.ShareInbox.pending.collectAsStateWithLifecycle()
  LaunchedEffect(incoming) {
    val value = incoming
    if (!value.isNullOrBlank()) {
      url = value
      com.goosepaware.biliaudio.data.ShareInbox.consume()
    }
  }

  LaunchedEffect(Unit) {
    val s = withContext(Dispatchers.IO) { app.settingsStore.load() }
    fmt = s.fmt
    preset = s.preset
    atmos = s.atmos
    aacFallback = s.aacFallback
    verify = s.verify
    tag = s.tag
    pages = s.pages
    treeUri = s.outputTreeUri
    loaded = true
  }

  fun persist() {
    if (!loaded) return
    app.scope.launch {
      app.settingsStore.save(
        Settings(
          fmt = fmt, preset = preset, atmos = atmos, aacFallback = aacFallback,
          verify = verify, tag = tag, pages = pages, outputTreeUri = treeUri,
          showLog = com.goosepaware.biliaudio.data.UiPrefs.showLog.value,
          waveformAacProxy = com.goosepaware.biliaudio.data.UiPrefs.waveformAacProxy.value,
        )
      )
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
      persist()
    }
  }

  val coverPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
    if (uri != null) {
      runCatching {
        context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
      }
      coverUri = uri.toString()
      coverName = queryDisplayName(context, uri) ?: "cover"
    }
  }

  fun parse() {
    if (url.isBlank()) return
    parsing = true
    parseError = null
    note = null
    scope.launch {
      try {
        val (v, t, n) = withContext(Dispatchers.IO) {
          val vid = app.api.resolveUrl(url)
          val v = app.api.getVideo(vid)
          val dash = app.api.getPlayurl(v.bvid, v.pages.first().cid)
          val all = app.api.selectTracks(dash)
          val (chosen, n) = app.api.chooseTracks(all, DownloadOptions(atmos = atmos, aacFallback = aacFallback, fmt = fmt))
          Triple(v, all, n ?: chosen.firstOrNull()?.let { "将下载：${it.label}" })
        }
        video = v
        tracks = t
        note = n
        pages = "all"
        customTitle = v.title
        customArtist = v.owner
      } catch (e: Throwable) {
        parseError = e.message
        video = null
        tracks = emptyList()
      } finally {
        parsing = false
      }
    }
  }

  fun startDownload() {
    val v = video ?: return
    lastSaved = null
    doneMessage = null
    val opts = DownloadOptions(
      atmos = atmos, aacFallback = aacFallback, verify = verify, tag = tag,
      threads = 4, pages = pages, fmt = fmt, preset = preset, outputDir = treeUri,
      customTitle = customTitle, customArtist = customArtist, customAlbum = customAlbum,
      coverUri = coverUri,
    )
    app.scope.launch {
      WorkBus.start(v.title)
      DownloadService.start(context)
      try {
        val results = app.pipeline.process(
          url = url,
          opts = opts,
          onLog = { WorkBus.log(it) },
          onProgress = { WorkBus.setProgress(it) },
        )
        WorkBus.log("—— 完成，共 ${results.size} 个音频 ——")
        withContext(Dispatchers.Main) {
          lastSaved = results.lastOrNull()
          doneMessage = if (results.isEmpty()) "没有可下载的音轨" else "已下载 ${results.size} 个音频"
        }
      } catch (e: Throwable) {
        WorkBus.log("✗ ${e.message}")
        withContext(Dispatchers.Main) { errorMessage = e.message ?: "下载失败" }
      } finally {
        DownloadService.stop(context)
        WorkBus.finish()
      }
    }
  }

  Column(modifier.fillMaxSize()) {
    Column(
      Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(12.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      PasteField(
        label = "视频链接 / BV 号 / 短链",
        value = url,
        onValueChange = { url = it },
        placeholder = "https://www.bilibili.com/video/BV...",
        linkOnly = true,
      )
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { parse() }, enabled = !parsing && !running) {
          Text(if (parsing) "解析中…" else "解析")
        }
        if (video != null) {
          Text("已解析", style = MaterialTheme.typography.bodySmall, modifier = Modifier.align(Alignment.CenterVertically))
        }
      }
      parseError?.let { Text("✗ $it", color = MaterialTheme.colorScheme.error) }

      video?.let { v ->
        SectionCard("视频信息") {
          Text(v.title, style = MaterialTheme.typography.bodyMedium)
          Text(
            "${v.owner} · ${v.bvid} · ${v.pages.size} P" + if (v.pages.size == 1) " · ${fmtDuration(v.pages[0].duration.toDouble())}" else "",
            style = MaterialTheme.typography.bodySmall,
          )
          if (tracks.isNotEmpty()) {
            Text("可用音轨：", style = MaterialTheme.typography.labelMedium)
            tracks.forEach { t ->
              Text(
                "· ${t.label}  ${t.kbps} kb/s" + if (t.kind == "flac") "  （无损）" else "",
                style = MaterialTheme.typography.bodySmall,
              )
            }
          }
          note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
        }
      }

      SectionCard("输出设置") {
        Dropdown(
          label = "格式",
          options = listOf("跟随源格式" to "source") + Formats.FORMATS.map { it.value.label to it.key },
          selected = fmt,
          onSelect = {
            fmt = it
            val presets = Formats.presetChoices(it)
            if (presets.isNotEmpty()) preset = presets.first().id
            persist()
          },
        )
        val presets = Formats.presetChoices(fmt)
        if (presets.isNotEmpty()) {
          Dropdown(
            label = "预设",
            options = presets.map { "${it.label} — ${it.desc}" to it.id },
            selected = preset,
            onSelect = { preset = it; persist() },
          )
        }
        Text(
          Formats.describe(fmt, preset),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (video != null && video!!.pages.size > 1) {
          PasteField(
            label = "分 P（all 或 1,3-5）",
            value = pages,
            onValueChange = { pages = it; persist() },
          )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
          Text("输出位置", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
          Text(
            if (treeUri.isBlank()) "内部存储/Music/BiliAudio" else "已选文件夹",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          OutlinedButton(onClick = { folderPicker.launch(null) }) { Text("选择文件夹") }
          if (treeUri.isNotBlank()) {
            TextButton(onClick = { treeUri = ""; persist() }) { Text("恢复默认") }
          }
        }
      }

      SectionCard("元数据（可选）") {
        PasteField(
          label = "歌名（同时作为文件名）",
          value = customTitle,
          onValueChange = { customTitle = it },
          placeholder = video?.title ?: "默认视频标题",
        )
        PasteField(
          label = "歌手",
          value = customArtist,
          onValueChange = { customArtist = it },
          placeholder = video?.owner ?: "默认 UP 主",
        )
        PasteField(
          label = "专辑（留空不写入）",
          value = customAlbum,
          onValueChange = { customAlbum = it },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
          OutlinedButton(onClick = { coverPicker.launch(arrayOf("image/*")) }) { Text("选择封面") }
          if (coverUri.isNotBlank()) {
            Text(coverName, style = MaterialTheme.typography.labelSmall)
            TextButton(onClick = { coverUri = ""; coverName = "" }) { Text("清除") }
          } else {
            Text("未选择（不写入封面）", style = MaterialTheme.typography.labelSmall)
          }
        }
      }

      SectionCard("选项") {
        ToggleRow("包含杜比全景声", atmos) { atmos = it; persist() }
        ToggleRow("无 Hi-Res 时回退到 AAC", aacFallback) { aacFallback = it; persist() }
        ToggleRow("下载后核验真实音质", verify) { verify = it; persist() }
        ToggleRow("写入标签与封面", tag) { tag = it; persist() }
      }

      Button(
        onClick = { startDownload() },
        enabled = video != null && !running,
        modifier = Modifier.fillMaxWidth(),
      ) { Text(if (running) "任务进行中…" else "开始下载") }

      if (running) {
        val p = progress
        if (p != null) {
          Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
              "${p.title}  [${p.index}/${p.count}]",
              style = MaterialTheme.typography.bodySmall,
              maxLines = 1,
            )
            Text(p.stage, style = MaterialTheme.typography.labelSmall)
            if (p.total > 0) {
              LinearProgressIndicator(
                progress = { (p.done.toFloat() / p.total).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
              )
              Text(
                "${fmtSize(p.done)} / ${fmtSize(p.total)} · ${"%.2f".format(p.speed / 1048576)} MB/s · 剩余 ${fmtDuration(p.eta)}",
                style = MaterialTheme.typography.labelSmall,
              )
            } else {
              LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
          }
        }
      }
    }

    if (showLog) {
      LogPanel(logs, Modifier.height(150.dp).fillMaxWidth())
    }
  }

  doneMessage?.let { msg ->
    DoneDialog(
      title = "完成",
      message = msg + (lastSaved?.let { "\n${it.savedName}" } ?: ""),
      onOpen = {
        lastSaved?.let { r ->
          OutputStore.openFile(
            context,
            SavedFile(r.savedUri, r.savedName, r.savedName),
            Formats.mime(r.savedName.substringAfterLast('.', "")),
          )
        }
      },
      onDismiss = { doneMessage = null },
    )
  }

  errorMessage?.let { msg ->
    androidx.compose.material3.AlertDialog(
      onDismissRequest = { errorMessage = null },
      title = { Text("失败") },
      text = { Text(msg) },
      confirmButton = { TextButton(onClick = { errorMessage = null }) { Text("确定") } },
    )
  }
}

@Composable
fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
  Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
    Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
    Switch(checked = checked, onCheckedChange = onChange)
  }
}

@Composable
fun LogPanel(logs: List<String>, modifier: Modifier = Modifier) {
  Column(modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
    Text("日志", style = MaterialTheme.typography.labelMedium)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
      Text(
        logs.takeLast(120).joinToString("\n"),
        fontFamily = FontFamily.Monospace,
        fontSize = 10.sp,
        lineHeight = 13.sp,
      )
    }
  }
}

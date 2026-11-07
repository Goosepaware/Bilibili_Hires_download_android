package com.goosepaware.biliaudio.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.goosepaware.biliaudio.BiliApp
import com.goosepaware.biliaudio.data.QualityReport
import com.goosepaware.biliaudio.media.Analyzer
import com.goosepaware.biliaudio.media.Quality
import com.goosepaware.biliaudio.work.WorkBus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun QualityScreen(modifier: Modifier = Modifier) {
  val context = LocalContext.current
  val app = context.applicationContext as BiliApp

  var url by rememberSaveable { mutableStateOf("") }
  var segments by rememberSaveable { mutableStateOf(8f) }
  var busy by remember { mutableStateOf(false) }
  var report by remember { mutableStateOf<QualityReport?>(null) }
  var localResult by remember { mutableStateOf<com.goosepaware.biliaudio.data.Analysis?>(null) }
  var localName by remember { mutableStateOf("") }
  var log by remember { mutableStateOf("") }
  var error by remember { mutableStateOf<String?>(null) }
  val running by WorkBus.running.collectAsStateWithLifecycle()

  LaunchedEffect(Unit) {
    if (url.isBlank()) {
      com.goosepaware.biliaudio.data.ShareInbox.last?.let { url = it }
    }
  }

  val localPicker = androidx.activity.compose.rememberLauncherForActivityResult(
    androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
  ) { uri ->
    if (uri == null) return@rememberLauncherForActivityResult
    busy = true
    error = null
    localResult = null
    app.scope.launch {
      try {
        val work = File(context.cacheDir, "quality").apply { mkdirs() }
        val name = System.nanoTime().toString()
        val dest = File(work, "local_$name.audio")
        withContext(Dispatchers.IO) {
          context.contentResolver.openInputStream(uri)?.use { input ->
            dest.outputStream().use { input.copyTo(it) }
          } ?: throw IllegalStateException("无法读取文件")
        }
        val a = Analyzer.analyzeFile(dest, work)
        dest.delete()
        withContext(Dispatchers.Main) {
          localResult = a
          localName = queryDisplayName(context, uri) ?: "本地文件"
        }
      } catch (e: Throwable) {
        withContext(Dispatchers.Main) { error = e.message ?: "检测失败" }
      } finally {
        withContext(Dispatchers.Main) { busy = false }
      }
    }
  }

  fun inspect() {
    if (url.isBlank()) return
    busy = true
    error = null
    report = null
    log = ""
    app.scope.launch {
      try {
        val work = File(context.cacheDir, "quality").apply { mkdirs() }
        val r = Quality.inspect(
          app.api, url, segments.toInt(), work,
          onLog = { line ->
            log = (log + line + "\n").takeLast(4000)
          },
        )
        withContext(Dispatchers.Main) { report = r }
      } catch (e: Throwable) {
        withContext(Dispatchers.Main) { error = e.message ?: "检测失败" }
      } finally {
        withContext(Dispatchers.Main) { busy = false }
      }
    }
  }

  Column(modifier.fillMaxSize()) {
    Column(
      Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(12.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      PasteField(
        label = "视频链接 / BV 号",
        value = url,
        onValueChange = { url = it },
        placeholder = "https://www.bilibili.com/video/BV...",
        linkOnly = true,
      )

      SectionCard("检测参数") {
        Text("采样分段：${segments.toInt()} 段", style = MaterialTheme.typography.bodySmall)
        Slider(
          value = segments,
          onValueChange = { segments = it },
          valueRange = 4f..12f,
          steps = 7,
        )
        Text(
          "每段会取 1~3 个时间点做 FFT，综合判断有效带宽与截断陡峭度。",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }

      Button(
        onClick = { inspect() },
        enabled = !busy && !running,
        modifier = Modifier.fillMaxWidth(),
      ) { Text(if (busy) "检测中…" else "开始检测") }

      androidx.compose.material3.OutlinedButton(
        onClick = { localPicker.launch(arrayOf("*/*")) },
        enabled = !busy && !running,
        modifier = Modifier.fillMaxWidth(),
      ) { Text("检测本地音频文件") }

      localResult?.let { a ->
        SectionCard("本地文件：$localName") {
          Text(a.verdictText, style = MaterialTheme.typography.titleSmall)
          Text(
            com.goosepaware.biliaudio.media.Analyzer.reportText(a),
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            lineHeight = 15.sp,
          )
        }
      }

      if (busy) {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
      }

      error?.let { Text("✗ $it", color = MaterialTheme.colorScheme.error) }

      report?.let { r ->
        SectionCard("综合评分") {
          Text(
            "${r.score} / 100",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.primary,
          )
          Text(r.grade, style = MaterialTheme.typography.titleSmall)
          Text(r.summary, style = MaterialTheme.typography.bodySmall)
          Text(
            "${r.videoTitle}\n${r.bvid} · ${r.owner} · ${fmtDuration(r.duration)}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }

        r.tracks.forEach { tq ->
          SectionCard("${tq.track.label}（${tq.track.kbps} kb/s）") {
            Text(
              "得分 ${tq.score}/100 · ${tq.grade}",
              style = MaterialTheme.typography.titleSmall,
            )
            if (tq.segments.isNotEmpty()) {
              Text(
                "${tq.codec} · ${tq.sampleRate} Hz · ${tq.bitDepth ?: "-"}-bit · ${tq.channels ?: "-"}",
                style = MaterialTheme.typography.labelSmall,
              )
              Text(tq.summary, style = MaterialTheme.typography.bodySmall)
            } else {
              Text(tq.summary, style = MaterialTheme.typography.bodySmall)
            }
            tq.descriptions.forEach { d ->
              Text(d, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
          }
        }
      }

      if (log.isNotBlank()) {
        SectionCard("检测日志") {
          Text(log, fontFamily = FontFamily.Monospace, fontSize = 10.sp, lineHeight = 13.sp)
        }
      }
    }
  }

  error?.let { msg ->
    AlertDialog(
      onDismissRequest = { error = null },
      title = { Text("检测失败") },
      text = { Text(msg) },
      confirmButton = { TextButton(onClick = { error = null }) { Text("确定") } },
    )
  }
}

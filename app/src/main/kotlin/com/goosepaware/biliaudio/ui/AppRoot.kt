package com.goosepaware.biliaudio.ui

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.goosepaware.biliaudio.BiliApp
import com.goosepaware.biliaudio.R
import com.goosepaware.biliaudio.data.Account
import com.goosepaware.biliaudio.player.IpodSettingsStore
import com.goosepaware.biliaudio.player.PlaybackController
import com.goosepaware.biliaudio.ui.ipod.IpodPlayerScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val HOME_URL = "https://space.bilibili.com/521582772?spm_id_from=333.788.0.0"

@Composable
fun AppRoot() {
  val context = LocalContext.current
  val app = context.applicationContext as BiliApp
  val scope = rememberCoroutineScope()

  var tab by remember { mutableIntStateOf(0) }
  var account by remember { mutableStateOf<Account?>(null) }
  var showLogin by remember { mutableStateOf(false) }
  var showPlayer by remember { mutableStateOf(false) }
  var confirmLogout by remember { mutableStateOf(false) }
  var showMore by remember { mutableStateOf(false) }
  var showAppSettings by remember { mutableStateOf(false) }
  var showHelp by remember { mutableStateOf(false) }
  var showReward by remember { mutableStateOf(false) }
  var folderUris by remember { mutableStateOf<List<String>>(emptyList()) }
  var hasAudioPermission by remember {
    mutableStateOf(
      context.checkSelfPermission(
        if (android.os.Build.VERSION.SDK_INT >= 33) android.Manifest.permission.READ_MEDIA_AUDIO
        else android.Manifest.permission.READ_EXTERNAL_STORAGE,
      ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    )
  }

  val playerController = remember { PlaybackController(context) }
  val ipodStore = remember { IpodSettingsStore(context) }

  androidx.compose.runtime.DisposableEffect(Unit) {
    onDispose { playerController.release() }
  }

  val audioPermLauncher = rememberLauncherForActivityResult(
    ActivityResultContracts.RequestPermission()
  ) { granted -> hasAudioPermission = granted }

  LaunchedEffect(showPlayer) {
    if (showPlayer && !hasAudioPermission) {
      audioPermLauncher.launch(
        if (android.os.Build.VERSION.SDK_INT >= 33) android.Manifest.permission.READ_MEDIA_AUDIO
        else android.Manifest.permission.READ_EXTERNAL_STORAGE
      )
    }
  }

  LaunchedEffect(Unit) {
    folderUris = withContext(Dispatchers.IO) { ipodStore.load().folderUris }
    val s = withContext(Dispatchers.IO) { app.settingsStore.load() }
    com.goosepaware.biliaudio.data.UiPrefs.showLog.value = s.showLog
    com.goosepaware.biliaudio.data.UiPrefs.waveformAacProxy.value = s.waveformAacProxy
  }

  fun setFolders(next: List<String>) {
    folderUris = next.distinct()
    scope.launch {
      val s = withContext(Dispatchers.IO) { ipodStore.load() }
      withContext(Dispatchers.IO) { ipodStore.save(s.copy(folderUris = next.distinct())) }
    }
  }

  val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
    if (uri != null) {
      runCatching {
        context.contentResolver.takePersistableUriPermission(
          uri,
          Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
      }
      val key = uri.toString()
      if (key !in folderUris) setFolders(folderUris + key)
    }
  }

  if (showPlayer) {
    androidx.activity.compose.BackHandler { showPlayer = false }
    IpodPlayerScreen(
      controller = playerController,
      onExit = { showPlayer = false },
      folderUris = folderUris,
      onAddFolder = { folderPicker.launch(null) },
      onRemoveFolder = { uri -> setFolders(folderUris.filterNot { it == uri }) },
      hasAudioPermission = hasAudioPermission,
    )
    return
  }

  fun refreshAccount() {
    scope.launch {
      account = withContext(Dispatchers.IO) { runCatching { app.http.fetchAccount() }.getOrNull() }
    }
  }

  LaunchedEffect(Unit) {
    account = withContext(Dispatchers.IO) { runCatching { app.http.fetchAccount() }.getOrNull() }
  }

  Scaffold(
    topBar = {
      Column(Modifier.fillMaxWidth().statusBarsPadding()) {
        Row(
          Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          TextButton(onClick = { showMore = true }) { Text("更多") }
          Text("账号:", style = MaterialTheme.typography.bodySmall)
          Text(
            account?.let { " ${it.name}${if (it.vip) " · 大会员" else ""}" } ?: " 未登录",
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier
              .weight(1f)
              .clickable(enabled = account != null) { confirmLogout = true },
          )
          TextButton(onClick = { showPlayer = true }) { Text("进入播放器") }
          if (account == null) {
            TextButton(onClick = { showLogin = true }) { Text("登录") }
          }
        }
        TabRow(selectedTabIndex = tab) {
          Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("下载") })
          Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("音质检测") })
          Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text("自动分轨") })
        }
      }
    },
  ) { pad ->
    when (tab) {
      0 -> DownloadScreen(Modifier.fillMaxSize().padding(pad))
      1 -> QualityScreen(Modifier.fillMaxSize().padding(pad))
      else -> SplitScreen(Modifier.fillMaxSize().padding(pad))
    }
  }

  if (showLogin) {
    LoginDialog(
      onDismiss = { showLogin = false },
      onSuccess = {
        showLogin = false
        refreshAccount()
      },
    )
  }

  if (confirmLogout) {
    AlertDialog(
      onDismissRequest = { confirmLogout = false },
      title = { Text("退出登录") },
      text = { Text("确定要退出当前账号吗？") },
      confirmButton = {
        TextButton(onClick = {
          app.sessionStore.clear()
          account = null
          confirmLogout = false
        }) { Text("退出") }
      },
      dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text("取消") } },
    )
  }

  if (showMore) {
    AlertDialog(
      onDismissRequest = { showMore = false },
      title = { Text("更多") },
      text = {
        Column(Modifier.fillMaxWidth()) {
          MoreRow("设置") { showMore = false; showAppSettings = true }
          MoreRow("说明") { showMore = false; showHelp = true }
          MoreRow("作者主页") {
            showMore = false
            runCatching {
              context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(HOME_URL)))
            }
          }
          MoreRow("赞赏码") { showMore = false; showReward = true }
        }
      },
      confirmButton = {},
      dismissButton = { TextButton(onClick = { showMore = false }) { Text("关闭") } },
    )
  }

  if (showAppSettings) AppSettingsDialog(onDismiss = { showAppSettings = false })
  if (showHelp) HelpDialog(onDismiss = { showHelp = false })
  if (showReward) RewardDialog(onDismiss = { showReward = false })
}

@Composable
private fun MoreRow(label: String, onClick: () -> Unit) {
  Row(
    Modifier
      .fillMaxWidth()
      .clickable(onClick = onClick)
      .padding(horizontal = 8.dp, vertical = 14.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(label, style = MaterialTheme.typography.bodyLarge)
  }
}

@Composable
private fun AppSettingsDialog(onDismiss: () -> Unit) {
  val context = LocalContext.current
  val app = context.applicationContext as BiliApp
  var showLog by remember { mutableStateOf(com.goosepaware.biliaudio.data.UiPrefs.showLog.value) }
  var aacProxy by remember { mutableStateOf(com.goosepaware.biliaudio.data.UiPrefs.waveformAacProxy.value) }

  fun persist() {
    app.scope.launch {
      val s = withContext(Dispatchers.IO) { app.settingsStore.load() }
      withContext(Dispatchers.IO) {
        app.settingsStore.save(s.copy(showLog = showLog, waveformAacProxy = aacProxy))
      }
    }
  }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("设置") },
    text = {
      Column {
        ToggleRow("显示日志", showLog) {
          showLog = it
          com.goosepaware.biliaudio.data.UiPrefs.showLog.value = it
          persist()
        }
        Spacer(Modifier.height(10.dp))
        ToggleRow("静音检测载入波形时采用 AAC 代理", aacProxy) {
          aacProxy = it
          com.goosepaware.biliaudio.data.UiPrefs.waveformAacProxy.value = it
          persist()
        }
        Text(
          "开启后载入波形会改用体积更小的 AAC 音频，静音检测和波形更快；导出仍使用原始无损音轨。",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(top = 8.dp),
        )
      }
    },
    confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } },
  )
}

@Composable
private fun HelpDialog(onDismiss: () -> Unit) {
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("说明") },
    text = {
      Column(
        Modifier
          .fillMaxWidth()
          .heightIn(max = 520.dp)
          .verticalScroll(rememberScrollState()),
      ) {
        Text(HELP_TEXT, style = MaterialTheme.typography.bodyMedium)
      }
    },
    confirmButton = { TextButton(onClick = onDismiss) { Text("知道了") } },
  )
}

@Composable
private fun RewardDialog(onDismiss: () -> Unit) {
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("赞赏码") },
    text = {
      Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Image(
          painter = androidx.compose.ui.res.painterResource(R.drawable.reward_qrcode),
          contentDescription = "赞赏码",
          modifier = Modifier.fillMaxWidth().aspectRatio(1f),
        )
        Text(
          "感谢支持正版音乐",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(top = 8.dp),
        )
      }
    },
    confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
  )
}

private val HELP_TEXT = """
B站无损音频下载器（Android）

功能：
· 下载：粘贴 B 站视频链接、BV 号或分享文本，自动提取链接并下载 Hi-Res FLAC、杜比全景声或 AAC。
· 音质检测：解析音轨并给出 1–100 的参考评分。
· 自动分轨：支持按曲目表时间或静音检测分轨，可先载入波形预览再导出。
· 播放器：iPod 初代风格，滚轮操作，支持均衡器、睡眠定时和后台播放。

操作：
1. 在「下载」页粘贴链接，点「解析」，确认格式后开始下载。
2. 在「自动分轨」页载入音轨，选择曲目表或静音检测，载入波形后导出。
3. 点右上角「进入播放器」打开 iPod 播放器；左上角「更多」里可以打开设置和本说明。
4. 在「更多 → 设置」里可以打开日志，或让波形载入使用 AAC 代理以加快速度。

播放器的设计灵感来自Apple的产品ipod初代，向Steve Jobs致敬；音质打分仅供参考，不代表实际音质表现；内测Q群1131014558；作者：Goosepa&deepseek 2026；请支持正版音乐，实体专辑最高
""".trimIndent()

@Composable
private fun LoginDialog(onDismiss: () -> Unit, onSuccess: () -> Unit) {
  val context = LocalContext.current
  val app = context.applicationContext as BiliApp
  var qr by remember { mutableStateOf<Bitmap?>(null) }
  var status by remember { mutableStateOf("正在生成二维码…") }
  var error by remember { mutableStateOf<String?>(null) }
  var showCookie by remember { mutableStateOf(false) }
  var showWeb by remember { mutableStateOf(false) }
  var cookieText by remember { mutableStateOf("") }
  var cookieBusy by remember { mutableStateOf(false) }

  fun applyCookie(text: String) {
    cookieBusy = true
    error = null
    app.scope.launch {
      val ok = app.loginRepository.importCookieText(text)
      if (!ok) {
        withContext(Dispatchers.Main) {
          error = "没有找到 SESSDATA"
          cookieBusy = false
        }
        return@launch
      }
      val account = withContext(Dispatchers.IO) { runCatching { app.http.fetchAccount() }.getOrNull() }
      withContext(Dispatchers.Main) {
        cookieBusy = false
        if (account != null) onSuccess() else error = "Cookie 无效或已过期"
      }
    }
  }

  val cookieFilePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
    if (uri != null) {
      app.scope.launch {
        val text = withContext(Dispatchers.IO) {
          runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
          }.getOrNull()
        }
        if (text.isNullOrBlank()) {
          withContext(Dispatchers.Main) { error = "无法读取文件" }
        } else {
          applyCookie(text)
        }
      }
    }
  }

  LaunchedEffect(showCookie) {
    if (showCookie) return@LaunchedEffect
    runCatching {
      withContext(Dispatchers.IO) {
        app.loginRepository.login(
          onQr = { qr = it },
          onStatus = { status = it },
        )
      }
    }.onSuccess { onSuccess() }
      .onFailure {
        android.util.Log.e("BiliLogin", "login failed", it)
        error = "${it::class.simpleName}: ${it.message ?: "无详细信息"}"
      }
  }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(if (showCookie) "用 Cookie 登录" else "扫码登录") },
    text = {
      Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        if (showCookie) {
          PasteField(
            label = "Cookie",
            value = cookieText,
            onValueChange = { cookieText = it },
            placeholder = "SESSDATA=xxx; bili_jct=yyy; DedeUserID=123",
            minLines = 3,
            maxLines = 4,
          )
          Text(
            "从浏览器开发者工具复制 bilibili.com 的 Cookie 整段粘贴即可。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          Button(
            enabled = !cookieBusy,
            onClick = { applyCookie(cookieText) },
          ) { Text(if (cookieBusy) "验证中…" else "用 Cookie 登录") }
          androidx.compose.material3.OutlinedButton(
            enabled = !cookieBusy,
            onClick = { cookieFilePicker.launch(arrayOf("*/*")) },
          ) { Text("从 cookies.txt 导入") }
          TextButton(onClick = { showWeb = true }) { Text("网页登录（手机上最方便）") }
        } else {
          val bmp = qr
          if (bmp != null) {
            Image(
              bitmap = bmp.asImageBitmap(),
              contentDescription = "登录二维码",
              modifier = Modifier.size(220.dp),
            )
          } else {
            CircularProgressIndicator()
          }
          Text(status, style = MaterialTheme.typography.bodySmall)
          TextButton(onClick = { showCookie = true }) { Text("改用 Cookie 登录") }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
      }
    },
    confirmButton = { TextButton(onClick = onDismiss) { Text("取消") } },
  )

  if (showWeb) {
    WebLoginDialog(
      onDone = { cookies ->
        showWeb = false
        applyCookie(cookies)
      },
      onCancel = { showWeb = false },
    )
  }
}


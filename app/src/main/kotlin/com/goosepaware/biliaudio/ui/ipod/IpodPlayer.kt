package com.goosepaware.biliaudio.ui.ipod

import android.content.Context
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.goosepaware.biliaudio.player.EqPresets
import com.goosepaware.biliaudio.player.EqProcessor
import com.goosepaware.biliaudio.player.EqState
import com.goosepaware.biliaudio.player.IpodSettings
import com.goosepaware.biliaudio.player.IpodSettingsStore
import com.goosepaware.biliaudio.player.LibraryRepository
import com.goosepaware.biliaudio.player.PlaybackController
import com.goosepaware.biliaudio.player.PlayerUiState
import com.goosepaware.biliaudio.player.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// ---------------- 菜单数据 ----------------

class MenuEntry(
  val label: String,
  val value: (() -> String?)? = null,
  val chevron: Boolean = false,
  val action: () -> Unit = {},
)

class MenuPage(
  val title: String,
  val entries: List<MenuEntry>,
) {
  var selected by mutableIntStateOf(0)
}

sealed interface IpodView {
  class Menu(val page: MenuPage) : IpodView
  data object Playing : IpodView
  class Info(val title: String, val text: String) : IpodView
  class Adjust(
    val title: String,
    value: Float,
    val min: Float,
    val max: Float,
    val step: Float,
    val unit: String = "dB",
    val onChange: (Float) -> Unit,
    val onConfirm: () -> Unit,
  ) : IpodView {
    var value by mutableFloatStateOf(value)
  }
}

private data class NavFrame(
  val view: IpodView,
  val seq: Int,
  val forward: Boolean,
  val animate: Boolean,
)

private fun mmss(ms: Long): String {
  val s = (ms / 1000).coerceAtLeast(0)
  return String.format(Locale.US, "%d:%02d", s / 60, s % 60)
}

private fun dbLabel(v: Float): String = (if (v > 0) "+" else "") + v.roundToInt() + " dB"

private data class AddedFolder(
  val uri: String,
  val name: String,
  val tracks: List<Track>,
)

// ---------------- 主界面 ----------------

@Composable
fun IpodPlayerScreen(
  controller: PlaybackController,
  onExit: () -> Unit,
  folderUris: List<String>,
  onAddFolder: () -> Unit,
  onRemoveFolder: (String) -> Unit,
  hasAudioPermission: Boolean,
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val store = remember { IpodSettingsStore(context) }

  var settings by remember { mutableStateOf(IpodSettings()) }
  var settingsLoaded by remember { mutableStateOf(false) }

  var downloaded by remember { mutableStateOf<List<Track>>(emptyList()) }
  var addedFolders by remember { mutableStateOf<List<AddedFolder>>(emptyList()) }
  var libraryLoading by remember { mutableStateOf(true) }
  var folderLoading by remember { mutableStateOf(false) }

  // 默认曲库只包含 App 下载的音频；从手机添加的文件夹会合并进来
  val libraryTracks = remember { mutableStateOf<List<Track>>(emptyList()) }
  LaunchedEffect(downloaded, addedFolders) {
    libraryTracks.value = (downloaded + addedFolders.flatMap { it.tracks })
      .distinctBy { it.uri.toString() }
      // 同一个文件从媒体库和文件夹各扫到一次时按标签去重，优先保留「已下载」那份
      .distinctBy { "${it.title}\u0000${it.artist}\u0000${it.album}\u0000${it.durationMs}" }
      .sortedWith(compareBy({ it.artist.lowercase() }, { it.album.lowercase() }, { it.trackNo }))
  }

  val stack = remember { mutableStateListOf<IpodView>() }
  val playerState by controller.state.collectAsStateWithLifecycle()

  var navSeq by remember { mutableIntStateOf(0) }
  var navForward by remember { mutableStateOf(true) }
  var navAnimate by remember { mutableStateOf(false) }
  var backlightOn by remember { mutableStateOf(true) }
  var inputTick by remember { mutableIntStateOf(0) }

  fun bumpBacklight() {
    if (settings.backlightTimer != 0) backlightOn = true
    inputTick++
  }

  LaunchedEffect(settings.backlightTimer, inputTick, settingsLoaded) {
    if (!settingsLoaded) return@LaunchedEffect
    when {
      settings.backlightTimer == 0 -> backlightOn = false
      settings.backlightTimer < 0 -> backlightOn = true
      else -> {
        backlightOn = true
        delay(settings.backlightTimer * 1000L)
        backlightOn = false
      }
    }
  }

  fun persist(next: IpodSettings) {
    settings = next
    scope.launch { store.save(next) }
  }

  // 初始化：设置 + 播放服务 + 曲库
  LaunchedEffect(Unit) {
    val s = withContext(Dispatchers.IO) { store.load() }
    settings = s
    settingsLoaded = true
    EqState.set(s.eqGains.toFloatArray(), s.eqEnabled, s.eqPreset)
    controller.connect()
    controller.setSleepMinutes(0)
  }

  LaunchedEffect(hasAudioPermission) {
    libraryLoading = true
    downloaded = withContext(Dispatchers.IO) {
      runCatching { LibraryRepository.scanMediaStore(context, true) }.getOrDefault(emptyList())
    }
    libraryLoading = false
  }

  LaunchedEffect(folderUris) {
    if (folderUris.isEmpty()) {
      addedFolders = emptyList()
      folderLoading = false
      return@LaunchedEffect
    }
    folderLoading = true
    addedFolders = withContext(Dispatchers.IO) {
      folderUris.map { raw ->
        val uri = android.net.Uri.parse(raw)
        AddedFolder(
          uri = raw,
          name = runCatching { LibraryRepository.folderName(context, uri) }.getOrDefault("文件夹"),
          tracks = runCatching { LibraryRepository.scanTree(context, uri) }.getOrDefault(emptyList()),
        )
      }
    }
    folderLoading = false
  }

  // ---------------- 页面构建 ----------------

  fun push(view: IpodView) {
    stack.add(view)
    navForward = true
    navAnimate = true
    navSeq++
  }

  fun popCurrent(onEmpty: () -> Unit = {}) {
    if (stack.size > 1) {
      stack.removeAt(stack.lastIndex)
      navForward = false
      navAnimate = true
      navSeq++
    } else {
      onEmpty()
    }
  }

  fun play(tracks: List<Track>, index: Int) {
    if (tracks.isEmpty()) return
    controller.setQueue(tracks, index)
    if (stack.lastOrNull() !is IpodView.Playing) push(IpodView.Playing)
  }

  fun trackMenu(title: String, tracks: List<Track>): MenuPage {
    val entries = tracks.mapIndexed { i, t ->
      MenuEntry(t.title, value = { t.artist }, chevron = false) {
        play(tracks, i)
      }
    }
    return MenuPage(title, entries.ifEmpty { listOf(MenuEntry("（空）") { }) })
  }

  fun albumMenu(): MenuPage {
    val albums = libraryTracks.value.groupBy { it.albumKey }
      .map { (key, list) ->
        val first = list.first()
        Triple(first.album, first.artist, list.sortedBy { it.trackNo })
      }
      .sortedBy { it.first.lowercase() }
    return MenuPage(
      "专辑",
      albums.map { (album, artist, tracks) ->
        MenuEntry(album, value = { artist }) { push(IpodView.Menu(trackMenu(album, tracks))) }
      }.ifEmpty { listOf(MenuEntry("（空）") { }) },
    )
  }

  fun artistMenu(): MenuPage {
    val artists = libraryTracks.value.groupBy { it.artistKey }.toSortedMap(String.CASE_INSENSITIVE_ORDER)
    return MenuPage(
      "歌手",
      artists.map { (artist, tracks) ->
        MenuEntry(artist, value = { "${tracks.size} 首" }) {
          val albums = tracks.groupBy { it.album }.toSortedMap(String.CASE_INSENSITIVE_ORDER)
          push(
            IpodView.Menu(
              MenuPage(
                artist,
                albums.map { (album, list) ->
                  MenuEntry(album, value = { "${list.size} 首" }) {
                    push(IpodView.Menu(trackMenu(album, list.sortedBy { it.trackNo })))
                  }
                },
              )
            )
          )
        }
      }.ifEmpty { listOf(MenuEntry("（空）") { }) },
    )
  }

  fun presetMenu(): MenuPage {
    val entries = mutableListOf<MenuEntry>()
    EqPresets.builtin.forEach { p ->
      entries += MenuEntry(p.name, value = { if (p.name == settings.eqPreset) "✓" else null }) {
        val next = settings.copy(eqGains = p.gains, eqPreset = p.name, eqEnabled = true)
        persist(next)
        EqState.set(p.gains.toFloatArray(), true, p.name)
        popCurrent { onExit() }
      }
    }
    settings.customPresets.forEach { (name, gains) ->
      entries += MenuEntry(name, value = { if (name == settings.eqPreset) "✓" else null }) {
        val next = settings.copy(eqGains = gains, eqPreset = name, eqEnabled = true)
        persist(next)
        EqState.set(gains.toFloatArray(), true, name)
        popCurrent { onExit() }
      }
    }
    return MenuPage("均衡器预设", entries)
  }

  fun eqMenu(): MenuPage {
    val entries = mutableListOf<MenuEntry>()
    entries += MenuEntry("均衡器", value = { if (settings.eqEnabled) "开" else "关" }) {
      val next = settings.copy(eqEnabled = !settings.eqEnabled)
      EqState.set(settings.eqGains.toFloatArray(), next.eqEnabled, settings.eqPreset)
      persist(next)
    }
    entries += MenuEntry("预设", value = { settings.eqPreset }) {
      push(IpodView.Menu(presetMenu()))
    }
    EqProcessor.BANDS.forEachIndexed { i, hz ->
      val label = if (hz >= 1000) "${hz / 1000} kHz" else "$hz Hz"
      entries += MenuEntry(label, value = { dbLabel(settings.eqGains[i]) }) {
        push(
          IpodView.Adjust(
            title = "均衡器 · $label",
            value = settings.eqGains[i],
            min = -12f, max = 12f, step = 1f,
            onChange = { v ->
              val gains = settings.eqGains.toMutableList()
              gains[i] = v
              val next = settings.copy(eqGains = gains, eqPreset = "自定义")
              settings = next
              EqState.set(gains.toFloatArray(), next.eqEnabled, "自定义")
            },
            onConfirm = {
              scope.launch { store.save(settings) }
              popCurrent { onExit() }
            },
          )
        )
      }
    }
    entries += MenuEntry("保存为预设") {
      val name = "自定义 ${settings.customPresets.size + 1}"
      val next = settings.copy(eqPreset = name, customPresets = settings.customPresets + (name to settings.eqGains))
      persist(next)
    }
    entries += MenuEntry("重置") {
      val gains = List(EqProcessor.BAND_COUNT) { 0f }
      val next = settings.copy(eqGains = gains, eqPreset = "平直")
      persist(next)
      EqState.set(gains.toFloatArray(), next.eqEnabled, "平直")
    }
    return MenuPage("均衡器", entries)
  }

  fun outputMenu(): MenuPage = MenuPage(
    "输出格式",
    listOf(
      MenuEntry("解码", value = { playerState.spec?.label?.ifBlank { null } ?: "—" }),
      MenuEntry("说明", value = { "PCM 直通" }),
      MenuEntry("采样率", value = { playerState.spec?.sampleRate?.let { "$it Hz" } ?: "—" }),
    ),
  )

  fun aboutMenu(): MenuPage = MenuPage(
    "关于",
    listOf(
      MenuEntry("B站无损音频", value = { com.goosepaware.biliaudio.BuildConfig.VERSION_NAME }),
      MenuEntry("播放器", value = { "Media3 / ExoPlayer" }),
      MenuEntry("解码", value = { "FLAC / ALAC / WAV / MP3 / AAC" }),
      MenuEntry("字体", value = { "Fusion Pixel (OFL)" }),
      MenuEntry("设计灵感", chevron = true) {
        push(
          IpodView.Info(
            "致敬",
            "播放器的设计灵感来自Apple的产品ipod初代，向Steve Jobs致敬",
          )
        )
      },
    ),
  )

  fun sleepMenu(): MenuPage {
    val options = listOf(
      0 to "关闭",
      15 to "15 分钟",
      30 to "30 分钟",
      45 to "45 分钟",
      60 to "1 小时",
      90 to "1.5 小时",
    )
    val entries = options.map { (min, label) ->
      MenuEntry(label) {
        controller.setSleepMinutes(min)
        popCurrent {}
      }
    }.toMutableList()
    entries += MenuEntry("自定义…", chevron = true) {
      lateinit var adj: IpodView.Adjust
      adj = IpodView.Adjust(
        title = "睡眠定时（分钟）",
        value = sleepMinutes(playerState.sleepAtMs).coerceIn(1, 180).toFloat(),
        min = 1f, max = 180f, step = 5f,
        unit = "分钟",
        onChange = {},
        onConfirm = {
          controller.setSleepMinutes(adj.value.toInt())
          popCurrent {}
        },
      )
      push(adj)
    }
    return MenuPage("睡眠定时", entries)
  }

  fun backlightLabel(timer: Int): String = when (timer) {
    0 -> "关闭"
    1 -> "1 秒"
    2 -> "2 秒"
    3 -> "3 秒"
    5 -> "5 秒"
    else -> "始终打开"
  }

  fun backlightMenu(): MenuPage {
    val options = listOf(
      0 to "关闭",
      1 to "1 秒",
      2 to "2 秒",
      3 to "3 秒",
      5 to "5 秒",
      -1 to "始终打开",
    )
    return MenuPage(
      "背光计时器",
      options.map { (value, label) ->
        MenuEntry(label, value = { if (settings.backlightTimer == value) "✓" else null }) {
          persist(settings.copy(backlightTimer = value))
          popCurrent {}
        }
      },
    )
  }

  fun settingsMenu(): MenuPage {
    return MenuPage(
      "设置",
      listOf(
        MenuEntry("显示模式", value = { if (settings.mode == "full") "全屏" else "边框" }) {
          persist(settings.copy(mode = if (settings.mode == "full") "bezel" else "full"))
        },
        MenuEntry("背光计时器", value = { backlightLabel(settings.backlightTimer) }, chevron = true) {
          push(IpodView.Menu(backlightMenu()))
        },
        MenuEntry("LCD 残影", value = { if (settings.lcdGhosting) "开" else "关" }) {
          persist(settings.copy(lcdGhosting = !settings.lcdGhosting))
        },
        MenuEntry("像素淡入淡出", value = { "${settings.lcdFadeMs} ms" }, chevron = true) {
          lateinit var adj: IpodView.Adjust
          adj = IpodView.Adjust(
            title = "像素淡入淡出（ms）",
            value = settings.lcdFadeMs.toFloat(),
            min = 40f, max = 1000f, step = 20f,
            unit = "ms",
            onChange = { v -> settings = settings.copy(lcdFadeMs = v.roundToInt()) },
            onConfirm = {
              persist(settings)
              popCurrent {}
            },
          )
          push(adj)
        },
        MenuEntry("残影强度", value = { "${settings.lcdGhostStrength}%" }, chevron = true) {
          lateinit var adj: IpodView.Adjust
          adj = IpodView.Adjust(
            title = "残影强度（%）",
            value = settings.lcdGhostStrength.toFloat(),
            min = 0f, max = 100f, step = 5f,
            unit = "%",
            onChange = { v -> settings = settings.copy(lcdGhostStrength = v.roundToInt()) },
            onConfirm = {
              persist(settings)
              popCurrent {}
            },
          )
          push(adj)
        },
        MenuEntry("残影恢复默认") {
          persist(
            settings.copy(
              lcdGhosting = true,
              lcdFadeMs = 460,
              lcdGhostStrength = 15,
            )
          )
        },
        MenuEntry("睡眠定时", value = {
          val ms = controller.state.value.sleepAtMs
          if (ms > 0) sleepLabel(ms) else "关闭"
        }, chevron = true) {
          push(IpodView.Menu(sleepMenu()))
        },
        MenuEntry("输出格式") {
          push(IpodView.Menu(outputMenu()))
        },
        MenuEntry("关于", chevron = true) {
          push(IpodView.Menu(aboutMenu()))
        },
      ),
    )
  }

  fun folderDetailMenu(folder: AddedFolder): MenuPage {
    val entries = mutableListOf<MenuEntry>()
    entries += MenuEntry("移除此文件夹") {
      addedFolders = addedFolders.filterNot { it.uri == folder.uri }
      onRemoveFolder(folder.uri)
      // 回到音乐菜单，避免停留在已移除的旧文件夹页面
      if (stack.size > 2) {
        stack.removeAt(stack.lastIndex)
        stack.removeAt(stack.lastIndex)
        navForward = false
        navAnimate = true
        navSeq++
      }
    }
    folder.tracks.forEachIndexed { i, t ->
      entries += MenuEntry(t.title, value = { t.artist }) { play(folder.tracks, i) }
    }
    return MenuPage(folder.name, entries)
  }

  fun folderMenu(): MenuPage = MenuPage(
    "已添加文件夹",
    addedFolders.map { folder ->
      MenuEntry(folder.name, value = { "${folder.tracks.size} 首" }, chevron = true) {
        push(IpodView.Menu(folderDetailMenu(folder)))
      }
    }.ifEmpty { listOf(MenuEntry("（空）") { }) },
  )

  fun libraryMenu(): MenuPage = MenuPage(
    "播放列表",
    listOf(
      MenuEntry("已下载", value = { "${downloaded.size} 首" }) {
        push(IpodView.Menu(trackMenu("已下载", downloaded)))
      },
      MenuEntry(
        "本地文件夹",
        value = {
          when {
            folderLoading -> "扫描中…"
            addedFolders.isEmpty() -> "未添加"
            else -> "${addedFolders.sumOf { it.tracks.size }} 首"
          }
        },
        chevron = addedFolders.isNotEmpty(),
      ) {
        if (addedFolders.isEmpty()) onAddFolder()
        else push(IpodView.Menu(trackMenu("本地文件夹", addedFolders.flatMap { it.tracks })))
      },
    ),
  )

  fun musicMenu(): MenuPage = MenuPage(
    "音乐",
    listOf(
      MenuEntry("歌曲", value = { "${libraryTracks.value.size} 首" }) {
        push(IpodView.Menu(trackMenu("歌曲", libraryTracks.value)))
      },
      MenuEntry("专辑", chevron = true) { push(IpodView.Menu(albumMenu())) },
      MenuEntry("歌手", chevron = true) { push(IpodView.Menu(artistMenu())) },
      MenuEntry("已下载", value = { "${downloaded.size} 首" }) {
        push(IpodView.Menu(trackMenu("已下载", downloaded)))
      },
      MenuEntry("从手机添加", chevron = true) { onAddFolder() },
      MenuEntry(
        "已添加文件夹",
        value = {
          when {
            folderLoading -> "扫描中…"
            addedFolders.isEmpty() -> "无"
            else -> "${addedFolders.size} 个"
          }
        },
        chevron = true,
      ) {
        push(IpodView.Menu(folderMenu()))
      },
    ),
  )

  fun rootMenu(): MenuPage = MenuPage(
    "iPod",
    listOf(
      MenuEntry("音乐", chevron = true) { push(IpodView.Menu(musicMenu())) },
      MenuEntry("播放列表", chevron = true) { push(IpodView.Menu(libraryMenu())) },
      MenuEntry("正在播放", chevron = true) { push(IpodView.Playing) },
      MenuEntry("均衡器", value = { if (settings.eqEnabled) settings.eqPreset else "关" }) {
        push(IpodView.Menu(eqMenu()))
      },
      MenuEntry("随机播放全部") {
        val shuffled = libraryTracks.value.shuffled()
        play(shuffled, 0)
        controller.toggleShuffle()
      },
      MenuEntry("设置", chevron = true) { push(IpodView.Menu(settingsMenu())) },
    ),
  )

  // 首次进入
  LaunchedEffect(settingsLoaded, libraryLoading) {
    if (settingsLoaded && !libraryLoading && stack.isEmpty()) {
      stack.add(IpodView.Menu(rootMenu()))
      navAnimate = false
      navSeq++
    }
  }

  if (!settingsLoaded || libraryLoading || stack.isEmpty()) {
    Box(Modifier.fillMaxSize().background(IpodColors.bezelBackground), contentAlignment = Alignment.Center) {
      Text("正在载入…", style = ipodTextStyle(20f).copy(color = Color.White))
    }
    return
  }

  val full = settings.mode == "full"
  val current = stack.last()
  val frame = NavFrame(
    view = current,
    seq = navSeq,
    forward = navForward,
    animate = navAnimate,
  )

  fun scrollBy(delta: Int) {
    bumpBacklight()
    when (val v = stack.lastOrNull()) {
      is IpodView.Menu -> {
        val n = v.page.entries.size
        if (n > 0) v.page.selected = (v.page.selected + delta).coerceIn(0, n - 1)
      }
      is IpodView.Adjust -> {
        v.value = (v.value + delta * v.step).coerceIn(v.min, v.max)
        v.onChange(v.value)
      }
      else -> Unit
    }
  }

  fun activate() {
    bumpBacklight()
    when (val v = stack.lastOrNull()) {
      is IpodView.Menu -> v.page.entries.getOrNull(v.page.selected)?.action?.invoke()
      is IpodView.Adjust -> v.onConfirm()
      else -> Unit
    }
  }

  fun back() {
    bumpBacklight()
    if (stack.size > 1) {
      stack.removeAt(stack.lastIndex)
      navForward = false
      navAnimate = true
      navSeq++
    }
  }

  BoxWithConstraints(Modifier.fillMaxSize()) {
    val m = if (full) fullMetrics(maxWidth, maxHeight) else bezelMetrics(maxWidth, maxHeight)
    val density = LocalDensity.current
    val glowRadius = with(density) { maxWidth.toPx() }
    Box(
      Modifier
        .fillMaxSize()
        .background(
          if (full) Brush.verticalGradient(listOf(IpodColors.bodyGradient1, IpodColors.bodyGradient2))
          else Brush.radialGradient(
            listOf(Color(0xFF1B1F24), IpodColors.bezelBackground),
            radius = glowRadius,
          )
        ),
    ) {
      if (!full) {
        DeviceShell(m, Modifier.align(Alignment.Center)) {
          ScreenAndWheel(
            m, frame, playerState, settings, backlightOn,
            onScroll = ::scrollBy, onSelect = ::activate, onBack = ::back,
            onPlayPause = { bumpBacklight(); controller.toggle() },
            onPrev = { bumpBacklight(); controller.previous() },
            onNext = { bumpBacklight(); controller.next() },
          )
        }
      } else {
        FullBody(m) {
          ScreenAndWheel(
            m, frame, playerState, settings, backlightOn,
            onScroll = ::scrollBy, onSelect = ::activate, onBack = ::back,
            onPlayPause = { bumpBacklight(); controller.toggle() },
            onPrev = { bumpBacklight(); controller.previous() },
            onNext = { bumpBacklight(); controller.next() },
          )
        }
      }

      TextButton(
        onClick = onExit,
        modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(start = 4.dp),
      ) {
        Text("‹ 退出", style = ipodTextStyle(15f).copy(color = if (full) Color(0xFF444444) else Color.White))
      }
    }
  }
}

fun sleepMinutes(atMs: Long): Int {
  if (atMs <= 0) return 0
  val left = ((atMs - System.currentTimeMillis()) / 60_000L).coerceAtLeast(0)
  return left.toInt()
}

fun sleepLabel(atMs: Long): String {
  if (atMs <= 0) return "关闭"
  val ms = (atMs - System.currentTimeMillis()).coerceAtLeast(0)
  return mmss(ms)
}

// ---------------- 尺寸与外壳 ----------------

class IpodMetrics(
  val bodyW: Dp,
  val bodyH: Dp,
  val screenW: Dp,
  val screenH: Dp,
  val screenTop: Dp,
  val wheel: Dp,
  val wheelGap: Dp,
  val corner: Dp,
  val full: Boolean,
)

private fun bezelMetrics(maxW: Dp, maxH: Dp): IpodMetrics {
  // 按简化比例图：机身 525×867，屏幕 360×298，
  // 滚轮 434，屏幕到滚轮 35，滚轮底边距 59。
  val s = min((maxW - 32.dp) / 415.dp, (maxH - 40.dp) / 685.dp)
  val k = max(0.55f, s)
  val bodyW = 415.dp * k
  val bodyH = 685.dp * k
  return IpodMetrics(
    bodyW = bodyW,
    bodyH = bodyH,
    screenW = bodyW * (360f / 525f),
    screenH = bodyH * (298f / 867f),
    screenTop = bodyH * (42f / 867f),
    wheel = bodyW * (434f / 525f),
    wheelGap = bodyH * (35f / 867f),
    corner = bodyW * 0.065f,
    full = false,
  )
}

private fun fullMetrics(maxW: Dp, maxH: Dp): IpodMetrics {
  val screenW = (maxW * 0.84f).coerceAtMost(maxW - 56.dp)
  return IpodMetrics(
    bodyW = maxW,
    bodyH = maxH,
    screenW = screenW,
    screenH = screenW / 1.208f,
    screenTop = 26.dp,
    wheel = (maxW * 0.90f).coerceAtMost(maxH * 0.46f),
    wheelGap = 20.dp,
    corner = 0.dp,
    full = true,
  )
}

@Composable
private fun DeviceShell(m: IpodMetrics, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
  val density = LocalDensity.current
  val endX = with(density) { m.bodyW.toPx() }
  val startY = with(density) { m.bodyH.toPx() }
  Box(
    modifier
      .size(m.bodyW, m.bodyH)
      .shadow(28.dp, RoundedCornerShape(m.corner), ambientColor = Color.Black, spotColor = Color.Black)
      .clip(RoundedCornerShape(m.corner))
      .background(
        Brush.linearGradient(
          colors = listOf(IpodColors.bodyGradient1, IpodColors.bodyGradient2),
          start = Offset(0f, startY),
          end = Offset(endX, 0f),
        )
      )
      .border(1.5.dp, IpodColors.bodyRim, RoundedCornerShape(m.corner)),
  ) {
    content()
  }
}

@Composable
private fun FullBody(m: IpodMetrics, content: @Composable () -> Unit) {
  Box(Modifier.fillMaxSize()) { content() }
}

@Composable
private fun ScreenAndWheel(
  m: IpodMetrics,
  frame: NavFrame,
  state: PlayerUiState,
  settings: IpodSettings,
  backlightOn: Boolean,
  onScroll: (Int) -> Unit,
  onSelect: () -> Unit,
  onBack: () -> Unit,
  onPlayPause: () -> Unit,
  onPrev: () -> Unit,
  onNext: () -> Unit,
) {
  Column(
    Modifier
      .fillMaxSize()
      .then(if (m.full) Modifier.statusBarsPadding() else Modifier)
      .padding(top = if (m.full) 8.dp else 0.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Spacer(Modifier.height(m.screenTop))
    IpodScreen(
      m = m,
      frame = frame,
      state = state,
      backlightOn = backlightOn,
      backlightFadeMs = settings.lcdFadeMs,
      ghosting = settings.lcdGhosting,
      ghostFadeMs = settings.lcdFadeMs,
      ghostStrength = settings.lcdGhostStrength,
    )
    if (m.full) {
      Spacer(Modifier.weight(1f))
    } else {
      Spacer(Modifier.height(m.wheelGap))
    }
    ClickWheel(
      wheelSize = m.wheel,
      onUp = { onScroll(-1) },
      onDown = { onScroll(1) },
      onMenu = onBack,
      onPrev = onPrev,
      onNext = onNext,
      onPlayPause = onPlayPause,
      onSelect = onSelect,
    )
    if (m.full) {
      Spacer(Modifier.weight(1f))
    } else {
      Spacer(Modifier.weight(1f))
    }
  }
}

// ---------------- 屏幕 ----------------

private data class GhostFrame(
  val bitmap: androidx.compose.ui.graphics.ImageBitmap,
  val timeNs: Long,
)

@Composable
private fun GhostHost(
  enabled: Boolean,
  fadeMs: Int,
  strength: Int,
  modifier: Modifier = Modifier,
  content: @Composable () -> Unit,
) {
  val layer = rememberGraphicsLayer()
  val frames = remember { mutableListOf<GhostFrame>() }
  var version by remember { mutableIntStateOf(0) }
  val fade = fadeMs.coerceIn(40, 1000)
  val alphaScale = strength.coerceIn(0, 100) / 100f

  LaunchedEffect(enabled, fade, alphaScale) {
    if (!enabled || alphaScale <= 0f) {
      frames.clear()
      version++
      return@LaunchedEffect
    }
    var lastCapture = 0L
    while (true) {
      withFrameNanos { }
      val now = System.nanoTime()
      // 30fps 采样：既能看清拖影，又不会因为每帧 GPU 回读把页面拖卡
      if (now - lastCapture < 33_000_000L) continue
      lastCapture = now
      val bmp = runCatching { layer.toImageBitmap() }.getOrNull() ?: continue
      frames.add(GhostFrame(bmp, now))
      frames.removeAll { now - it.timeNs > fade * 1_000_000L }
      while (frames.size > 24) frames.removeAt(0)
      version++
    }
  }

  Box(
    modifier.drawWithContent {
      @Suppress("UNUSED_VARIABLE")
      val v = version
      layer.record {
        this@drawWithContent.drawContent()
      }
      drawLayer(layer)
      if (enabled && alphaScale > 0f) {
        val now = System.nanoTime()
        val tauMs = (fade / 4f).coerceAtLeast(10f)
        frames.forEach { f ->
          val ageMs = (now - f.timeNs) / 1_000_000f
          val a = alphaScale * exp(-ageMs / tauMs)
          if (a > 0.005f) {
            drawImage(image = f.bitmap, alpha = a)
          }
        }
      }
    },
  ) {
    content()
  }
}

@Composable
private fun IpodScreen(
  m: IpodMetrics,
  frame: NavFrame,
  state: PlayerUiState,
  backlightOn: Boolean,
  backlightFadeMs: Int,
  ghosting: Boolean,
  ghostFadeMs: Int,
  ghostStrength: Int,
) {
  val density = LocalDensity.current
  val bodySize = with(density) { (m.screenH.toPx() * 0.09f).toSp() }
  val pixelPx = with(density) { bodySize.toPx() / 12f }
  val pixelDp = with(density) { pixelPx.toDp() }
  val titleH = m.screenH * 0.16f
  val bodyH = (m.screenH - titleH - pixelDp).coerceAtLeast(0.dp)
  val glowRadius = with(density) { m.screenW.toPx() * 0.9f }
  val batteryLevel = rememberPhoneBatteryLevel()
  val screenShape = RoundedCornerShape(m.screenH * 0.032f)
  val dim by animateFloatAsState(
    targetValue = if (backlightOn) 0f else 0.55f,
    animationSpec = tween(durationMillis = backlightFadeMs.coerceIn(40, 1000)),
    label = "lcd-dim",
  )

  Box(
    Modifier
      .size(m.screenW, m.screenH)
      .shadow(
        elevation = 7.dp,
        shape = screenShape,
        clip = false,
        ambientColor = Color.Black.copy(alpha = 0.18f),
        spotColor = Color.Black.copy(alpha = 0.22f),
      )
      .clip(screenShape)
      .background(IpodColors.screenPaper),
  ) {
    Column(Modifier.fillMaxSize()) {
      // 顶部栏固定不动，文字直接切换
      Box(
        Modifier
          .fillMaxWidth()
          .height(titleH)
          .padding(horizontal = 8.dp),
      ) {
        if (state.isPlaying) {
          Text(
            "❚❚",
            modifier = Modifier.align(Alignment.CenterStart),
            style = ipodTextStyle(bodySize.value * 0.9f).copy(fontSize = bodySize * 0.9f),
          )
        }
        Text(
          titleOf(frame.view),
          modifier = Modifier
            .align(Alignment.Center)
            .width(m.screenW * 0.66f),
          style = ipodTextStyle(bodySize.value).copy(fontSize = bodySize),
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        PhoneBattery(
          level = batteryLevel,
          modifier = Modifier
            .align(Alignment.CenterEnd)
            .size(m.screenW * 0.13f, titleH * 0.46f),
          strokePx = pixelPx,
        )
      }
      Box(
        Modifier
          .fillMaxWidth()
          .height(pixelDp)
          .background(IpodColors.screenInk.copy(alpha = 0.72f)),
      )

      // 只有内容区滑动
      Box(
        Modifier
          .fillMaxWidth()
          .weight(1f),
      ) {
        GhostHost(
          enabled = ghosting,
          fadeMs = ghostFadeMs,
          strength = ghostStrength,
          modifier = Modifier.fillMaxSize(),
        ) {
          AnimatedContent(
            targetState = frame,
            modifier = Modifier.fillMaxSize(),
            transitionSpec = {
              if (!targetState.animate) {
                EnterTransition.None togetherWith ExitTransition.None
              } else {
                val spec = tween<androidx.compose.ui.unit.IntOffset>(
                  durationMillis = 260,
                  easing = LinearEasing,
                )
                if (targetState.forward) {
                  slideInHorizontally(spec) { it } togetherWith
                    slideOutHorizontally(spec) { -it }
                } else {
                  slideInHorizontally(spec) { -it } togetherWith
                    slideOutHorizontally(spec) { it }
                }
              }
            },
            contentKey = { it.seq },
            label = "ipod-page",
          ) { f ->
            Box(
              Modifier
                .fillMaxSize()
                .background(IpodColors.screenPaper),
            ) {
              when (val view = f.view) {
                is IpodView.Menu -> MenuBody(view.page, bodySize, bodyH)
                is IpodView.Info -> InfoBody(view.text, bodySize)
                is IpodView.Adjust -> AdjustBody(view, bodySize)
                IpodView.Playing -> NowPlayingBody(state, bodySize, pixelPx, pixelDp)
              }
            }
          }
        }
      }
    }

    Box(
      Modifier
        .fillMaxSize()
        .background(
          Brush.linearGradient(
            0f to Color.White.copy(alpha = 0.13f),
            0.26f to Color.Transparent,
          )
        ),
    )

    if (backlightOn) {
      Box(
        Modifier
          .fillMaxSize()
          .background(
            Brush.radialGradient(
              listOf(IpodColors.backlightGlow.copy(alpha = 0.12f), Color.Transparent),
              radius = glowRadius,
            )
          ),
      )
    }

    if (dim > 0.001f) {
      Box(
        Modifier
          .fillMaxSize()
          .background(Color.Black.copy(alpha = dim)),
      )
    }
  }
}

private fun titleOf(view: IpodView): String = when (view) {
  is IpodView.Menu -> view.page.title
  is IpodView.Info -> view.title
  is IpodView.Adjust -> view.title
  IpodView.Playing -> "正在播放"
}

@Composable
private fun InfoBody(text: String, fontSize: androidx.compose.ui.unit.TextUnit) {
  Column(
    Modifier
      .fillMaxSize()
      .verticalScroll(rememberScrollState())
      .padding(horizontal = 12.dp, vertical = 10.dp),
  ) {
    Text(
      text,
      style = ipodTextStyle(fontSize.value * 0.85f).copy(
        fontSize = fontSize * 0.85f,
        lineHeight = fontSize * 1.25f,
      ),
    )
    Spacer(Modifier.height(10.dp))
    Text(
      "按 MENU 返回",
      style = ipodTextStyle(fontSize.value * 0.7f).copy(
        fontSize = fontSize * 0.7f,
        color = IpodColors.screenInk.copy(alpha = 0.6f),
      ),
    )
  }
}

@Composable
private fun MenuBody(page: MenuPage, fontSize: androidx.compose.ui.unit.TextUnit, bodyH: Dp) {
  val density = LocalDensity.current
  val rowH = (bodyH - 3.dp) / 6f
  val visible = 6
  val total = page.entries.size
  val start = (page.selected - visible + 2).coerceIn(0, max(0, total - visible))
  val showBar = total > visible
  val pixelDp = with(density) { (fontSize.toPx() / 12f).toDp() }
  val barW = pixelDp * 2.5f
  val barGap = pixelDp * 2f

  Box(Modifier.fillMaxSize().clipToBounds()) {
    Column(
      Modifier
        .fillMaxSize()
        .padding(end = if (showBar) barW + barGap else 0.dp),
    ) {
      for (i in start until min(total, start + visible)) {
        val e = page.entries[i]
        val sel = i == page.selected
        Row(
          Modifier
            .fillMaxWidth()
            .height(rowH)
            .background(if (sel) IpodColors.screenSelected else Color.Transparent)
            .padding(horizontal = 11.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text(
            e.label,
            modifier = Modifier.weight(1f),
            style = ipodTextStyle(fontSize.value).copy(
              fontSize = fontSize,
              color = if (sel) IpodColors.screenInkInverse else IpodColors.screenInk,
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
          if (e.value != null) {
            val v = e.value.invoke()
            if (v != null) {
              Text(
                v,
                style = ipodTextStyle(fontSize.value).copy(
                  fontSize = fontSize * 0.85f,
                  color = if (sel) IpodColors.screenInkInverse else IpodColors.screenInk.copy(alpha = 0.75f),
                ),
                maxLines = 1,
              )
            }
          }
          if (e.chevron) {
            if (e.value != null) Spacer(Modifier.width(5.dp))
            Text(
              "›",
              style = ipodTextStyle(fontSize.value).copy(
                fontSize = fontSize,
                color = if (sel) IpodColors.screenInkInverse else IpodColors.screenInk,
              ),
            )
          }
        }
      }
    }

    if (showBar) {
      Canvas(
        Modifier
          .align(Alignment.CenterEnd)
          .width(barW)
          .fillMaxHeight()
          .padding(vertical = pixelDp),
      ) {
        val track = IpodColors.screenInk.copy(alpha = 0.08f)
        val border = IpodColors.screenInk.copy(alpha = 0.26f)
        val thumb = IpodColors.screenInk.copy(alpha = 0.55f)
        val stroke = pixelDp.toPx().coerceAtLeast(1f)

        drawRect(track, size = size)
        drawRect(border, size = size, style = androidx.compose.ui.graphics.drawscope.Stroke(stroke))

        val maxStart = (total - visible).coerceAtLeast(1)
        val thumbH = (size.height * visible / total.toFloat()).coerceAtLeast(size.height * 0.16f)
        val top = (size.height - thumbH) * (start.toFloat() / maxStart)
        drawRect(
          color = thumb,
          topLeft = Offset(stroke, top + stroke),
          size = androidx.compose.ui.geometry.Size(
            (size.width - stroke * 2).coerceAtLeast(1f),
            (thumbH - stroke * 2).coerceAtLeast(1f),
          ),
        )
      }
    }
  }
}

@Composable
private fun AdjustBody(view: IpodView.Adjust, fontSize: androidx.compose.ui.unit.TextUnit) {
  Column(
    Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 6.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center,
  ) {
    Text(
      if (view.unit == "dB") dbLabel(view.value) else "${view.value.roundToInt()} ${view.unit}",
      style = ipodTextStyle(fontSize.value * 1.9f).copy(fontSize = fontSize * 1.9f),
    )
    Spacer(Modifier.height(6.dp))
    Canvas(Modifier.fillMaxWidth().height(10.dp)) {
      val frac = ((view.value - view.min) / (view.max - view.min)).coerceIn(0f, 1f)
      drawRect(IpodColors.screenInk.copy(alpha = 0.2f), size = size)
      drawRect(
        IpodColors.screenInk,
        size = androidx.compose.ui.geometry.Size(size.width * frac, size.height),
      )
      drawLine(
        IpodColors.screenInk,
        Offset(size.width / 2f, 0f),
        Offset(size.width / 2f, size.height),
        strokeWidth = 1.5f,
      )
    }
    Spacer(Modifier.height(6.dp))
    Text(
      "滚轮调整 · 中键完成",
      style = ipodTextStyle(fontSize.value).copy(fontSize = fontSize * 0.7f),
    )
  }
}

@Composable
private fun NowPlayingBody(
  state: PlayerUiState,
  fontSize: androidx.compose.ui.unit.TextUnit,
  pixelPx: Float,
  pixelDp: Dp,
) {
  val textStyle = ipodTextStyle(fontSize.value).copy(fontSize = fontSize)
  val smallGap = pixelDp * 0.5f
  Column(
    Modifier
      .fillMaxSize()
      .padding(horizontal = 6.dp, vertical = pixelDp),
  ) {
    if (state.count > 0) {
      Text(
        "第 ${state.index + 1} 个（共 ${state.count} 个）",
        modifier = Modifier.fillMaxWidth(),
        style = textStyle,
        maxLines = 1,
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
      )
      Spacer(Modifier.height(smallGap))
      PixelMarqueeText(
        text = state.title.ifBlank { "未在播放" },
        style = textStyle,
        modifier = Modifier.fillMaxWidth(),
      )
      Text(
        state.artist.ifBlank { "未知歌手" },
        modifier = Modifier.fillMaxWidth(),
        style = textStyle,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
      )
      Text(
        state.album.ifBlank { "未知专辑" },
        modifier = Modifier.fillMaxWidth(),
        style = textStyle,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
      )
    } else {
      Spacer(Modifier.weight(1f))
      Text(
        "未在播放",
        modifier = Modifier.fillMaxWidth(),
        style = textStyle,
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
      )
      Spacer(Modifier.weight(1f))
    }

    Spacer(Modifier.weight(1f))
    val frac = if (state.durationMs > 0) {
      (state.positionMs.toFloat() / state.durationMs).coerceIn(0f, 1f)
    } else 0f
    Canvas(
      Modifier
        .fillMaxWidth()
        .height(pixelDp * 8f),
    ) {
      val radius = androidx.compose.ui.geometry.CornerRadius(pixelPx * 2f, pixelPx * 2f)
      drawRoundRect(
        color = IpodColors.progressTrack,
        size = size,
        cornerRadius = radius,
      )
      if (frac > 0f) {
        drawRoundRect(
          color = IpodColors.screenInk,
          size = androidx.compose.ui.geometry.Size(size.width * frac, size.height),
          cornerRadius = radius,
        )
      }
      drawRoundRect(
        color = IpodColors.progressBorder,
        size = size,
        cornerRadius = radius,
        style = androidx.compose.ui.graphics.drawscope.Stroke(width = pixelPx),
      )
    }
    Spacer(Modifier.height(pixelDp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
      Text(mmss(state.positionMs), style = textStyle)
      val right = if (state.sleepAtMs > 0) "⏾ ${sleepLabel(state.sleepAtMs)}"
      else "-${mmss((state.durationMs - state.positionMs).coerceAtLeast(0))}"
      Text(right, style = textStyle)
    }
  }
}

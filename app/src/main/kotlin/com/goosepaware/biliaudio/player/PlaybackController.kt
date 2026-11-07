package com.goosepaware.biliaudio.player

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private suspend fun <T> ListenableFuture<T>.awaitCompat(): T = suspendCancellableCoroutine { cont ->
  addListener(
    {
      try {
        cont.resume(get())
      } catch (e: Throwable) {
        cont.resumeWithException(e)
      }
    },
    MoreExecutors.directExecutor(),
  )
  cont.invokeOnCancellation { cancel(true) }
}

data class PlayerUiState(
  val connected: Boolean = false,
  val isPlaying: Boolean = false,
  val title: String = "",
  val artist: String = "",
  val album: String = "",
  val positionMs: Long = 0,
  val durationMs: Long = 0,
  val index: Int = 0,
  val count: Int = 0,
  val shuffle: Boolean = false,
  val repeatMode: Int = Player.REPEAT_MODE_OFF,
  val artwork: Bitmap? = null,
  val spec: AudioSpec? = null,
  val sleepAtMs: Long = 0,
) {
  val hasTrack: Boolean get() = count > 0
}

/**
 * UI 与播放服务之间的桥：MediaController + 状态流。
 */
class PlaybackController(context: Context) {

  private val appContext = context.applicationContext
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
  private val _state = MutableStateFlow(PlayerUiState())
  val state: StateFlow<PlayerUiState> = _state.asStateFlow()

  private var controller: MediaController? = null
  private var ticker: Job? = null
  private var sleepJob: Job? = null
  private var currentTrack: Track? = null
  private var artJob: Job? = null
  private val artCache = HashMap<String, Bitmap?>()

  val isConnected: Boolean get() = controller != null

  suspend fun connect() {
    if (controller != null) return
    val token = SessionToken(appContext, ComponentName(appContext, PlaybackService::class.java))
    val future = MediaController.Builder(appContext, token).buildAsync()
    val c = try {
      future.awaitCompat()
    } catch (e: Throwable) {
      Log.w("Player", "连接失败: ${e.message}")
      return
    }
    controller = c
    c.addListener(object : Player.Listener {
      override fun onEvents(player: Player, events: Player.Events) {
        syncFromPlayer()
      }
    })
    syncFromPlayer()
    ticker = scope.launch {
      while (true) {
        if (c.isPlaying) syncFromPlayer(light = true)
        delay(400)
      }
    }
  }

  private fun syncFromPlayer(light: Boolean = false) {
    val c = controller ?: return
    val item = c.currentMediaItem
    val meta = item?.mediaMetadata
    val index = c.currentMediaItemIndex.coerceAtLeast(0)
    val duration = c.duration.takeIf { it > 0 } ?: currentTrack?.durationMs ?: 0

    if (!light) {
      val key = item?.mediaId
      if (key != null && key != currentTrack?.key) {
        currentTrack = libraryLookup(key)
        if (currentTrack != null) loadExtras(currentTrack!!)
      }
    }

    _state.value = _state.value.copy(
      connected = true,
      isPlaying = c.isPlaying,
      title = meta?.title?.toString().orEmpty(),
      artist = meta?.artist?.toString().orEmpty(),
      album = meta?.albumTitle?.toString().orEmpty(),
      positionMs = c.currentPosition.coerceAtLeast(0),
      durationMs = duration,
      index = index,
      count = c.mediaItemCount,
      shuffle = c.shuffleModeEnabled,
      repeatMode = c.repeatMode,
    )
  }

  private fun libraryLookup(key: String): Track? = queue.firstOrNull { it.key == key }

  private var queue: List<Track> = emptyList()

  private fun loadExtras(track: Track) {
    artJob?.cancel()
    artJob = scope.launch {
      val cached = artCache[track.key]
      if (cached != null) {
        _state.value = _state.value.copy(artwork = cached)
      } else {
        val bmp = withContext(Dispatchers.IO) { decodeArt(track) }
        artCache[track.key] = bmp
        _state.value = _state.value.copy(artwork = bmp)
      }
      val spec = withContext(Dispatchers.IO) { LibraryRepository.audioSpec(appContext, track) }
      _state.value = _state.value.copy(spec = spec)
    }
  }

  private fun decodeArt(track: Track): Bitmap? {
    val bytes = LibraryRepository.cover(appContext, track) ?: return null
    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    var scale = 1
    while (opts.outWidth / scale > 640 || opts.outHeight / scale > 640) scale *= 2
    return BitmapFactory.decodeByteArray(
      bytes, 0, bytes.size,
      BitmapFactory.Options().apply { inSampleSize = scale },
    )
  }

  fun setQueue(tracks: List<Track>, startIndex: Int) {
    val c = controller ?: return
    queue = tracks
    currentTrack = tracks.getOrNull(startIndex)
    val items = tracks.map { t ->
      val md = MediaMetadata.Builder()
        .setTitle(t.title)
        .setArtist(t.artist)
        .setAlbumTitle(t.album)
        .build()
      MediaItem.Builder()
        .setUri(t.uri)
        .setMediaId(t.key)
        .setMediaMetadata(md)
        .build()
    }
    c.setMediaItems(items, startIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0)), 0)
    c.prepare()
    c.play()
    if (currentTrack != null) loadExtras(currentTrack!!)
    syncFromPlayer()
  }

  fun toggle() {
    val c = controller ?: return
    if (c.isPlaying) c.pause() else if (c.mediaItemCount > 0) c.play()
  }

  fun next() { controller?.seekToNextMediaItem() }
  fun previous() { controller?.seekToPreviousMediaItem() }
  fun seekTo(ms: Long) { controller?.seekTo(ms.coerceAtLeast(0)) }
  fun seekBy(deltaMs: Long) {
    val c = controller ?: return
    c.seekTo((c.currentPosition + deltaMs).coerceIn(0, c.duration.coerceAtLeast(0)))
  }

  fun toggleShuffle() { controller?.let { it.shuffleModeEnabled = !it.shuffleModeEnabled } }
  fun cycleRepeat() {
    val c = controller ?: return
    c.repeatMode = when (c.repeatMode) {
      Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
      Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
      else -> Player.REPEAT_MODE_OFF
    }
  }

  /** 睡眠定时：分钟；0 = 关闭。 */
  fun setSleepMinutes(minutes: Int) {
    sleepJob?.cancel()
    if (minutes <= 0) {
      _state.value = _state.value.copy(sleepAtMs = 0)
      return
    }
    val end = System.currentTimeMillis() + minutes * 60_000L
    _state.value = _state.value.copy(sleepAtMs = end)
    sleepJob = scope.launch {
      while (System.currentTimeMillis() < end) delay(1000)
      controller?.pause()
      _state.value = _state.value.copy(sleepAtMs = 0)
    }
  }

  fun release() {
    ticker?.cancel()
    sleepJob?.cancel()
    artJob?.cancel()
    controller?.release()
    controller = null
  }
}

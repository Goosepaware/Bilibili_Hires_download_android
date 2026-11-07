package com.goosepaware.biliaudio.player

import android.content.ContentUris
import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.InputStream

enum class TrackSource { MEDIA_STORE, FOLDER }

data class Track(
  val uri: Uri,
  val title: String,
  val artist: String,
  val album: String,
  val durationMs: Long,
  val trackNo: Int = 0,
  val albumId: Long? = null,
  val source: TrackSource = TrackSource.MEDIA_STORE,
  val relativePath: String = "",
  val mime: String = "",
) {
  val key: String get() = uri.toString()
  val isDownloaded: Boolean get() = relativePath.startsWith("Music/BiliAudio")
  val albumKey: String get() = "$album\u0000$artist"
  val artistKey: String get() = artist.ifBlank { "未知歌手" }
}

data class AudioSpec(val bits: Int?, val sampleRate: Int?) {
  val label: String
    get() {
      val b = bits?.let { "$it-bit" }
      val s = sampleRate?.let { if (it % 1000 == 0) "${it / 1000} kHz" else "%.1f kHz".format(it / 1000.0) }
      return listOfNotNull(b, s).joinToString(" / ")
    }

  companion object {
    val UNKNOWN = AudioSpec(null, null)

    /** 读 FLAC 的 STREAMINFO。 */
    fun flac(stream: InputStream): AudioSpec? = runCatching {
      val head = ByteArray(42)
      var read = 0
      while (read < head.size) {
        val n = stream.read(head, read, head.size - read)
        if (n <= 0) break
        read += n
      }
      if (read < 42) return null
      if (!(head[0] == 'f'.code.toByte() && head[1] == 'L'.code.toByte() &&
          head[2] == 'a'.code.toByte() && head[3] == 'C'.code.toByte())
      ) return null
      // 4..7 是第一个 metadata block 头，8..41 是 STREAMINFO
      val b = head.copyOfRange(8, 42)
      var bitPos = 80 // 采样率从第 10 字节开始（STREAMINFO 内偏移）
      fun readBits(count: Int): Long {
        var v = 0L
        repeat(count) {
          val byteIndex = bitPos / 8
          val bitIndex = 7 - (bitPos % 8)
          val bit = (b[byteIndex].toInt() shr bitIndex) and 1
          v = (v shl 1) or bit.toLong()
          bitPos++
        }
        return v
      }
      val sr = readBits(20).toInt()
      readBits(3)
      val bits = (readBits(5) + 1).toInt()
      AudioSpec(bits, sr.takeIf { it > 0 })
    }.onFailure { Log.w("AudioSpec", "flac 解析失败: ${it.message}") }.getOrNull()

    fun extractor(context: Context, uri: Uri): AudioSpec = runCatching {
      val ex = MediaExtractor()
      ex.setDataSource(context, uri, null)
      try {
        if (ex.trackCount == 0) return AudioSpec.UNKNOWN
        val f = ex.getTrackFormat(0)
        val sr = if (f.containsKey(MediaFormat.KEY_SAMPLE_RATE)) f.getInteger(MediaFormat.KEY_SAMPLE_RATE) else null
        val bits = if (f.containsKey("bits-per-sample")) f.getInteger("bits-per-sample") else null
        AudioSpec(bits?.takeIf { it in 8..64 }, sr)
      } finally {
        ex.release()
      }
    }.getOrDefault(AudioSpec.UNKNOWN)
  }
}

/** 曲库扫描：媒体库 / 自选文件夹。 */
object LibraryRepository {

  private val audioExt = setOf("flac", "m4a", "mp3", "aac", "wav", "ogg", "opus", "ape", "wv", "dsf", "dff", "m4b")

  fun scanMediaStore(context: Context, onlyDownloaded: Boolean): List<Track> {
    val resolver = context.contentResolver
    val cols = arrayOf(
      MediaStore.Audio.Media._ID,
      MediaStore.Audio.Media.TITLE,
      MediaStore.Audio.Media.ARTIST,
      MediaStore.Audio.Media.ALBUM,
      MediaStore.Audio.Media.DURATION,
      MediaStore.Audio.Media.TRACK,
      MediaStore.Audio.Media.ALBUM_ID,
      MediaStore.Audio.Media.DISPLAY_NAME,
      MediaStore.Audio.Media.MIME_TYPE,
      if (Build.VERSION.SDK_INT >= 29) MediaStore.Audio.Media.RELATIVE_PATH else MediaStore.Audio.Media.DATA,
    )
    val condition = if (onlyDownloaded) {
      if (Build.VERSION.SDK_INT >= 29) "${MediaStore.Audio.Media.RELATIVE_PATH} LIKE ?"
      else "${MediaStore.Audio.Media.DATA} LIKE ?"
    } else {
      "${MediaStore.Audio.Media.IS_MUSIC} != 0"
    }
    val args = if (onlyDownloaded) {
      arrayOf(if (Build.VERSION.SDK_INT >= 29) "Music/BiliAudio%" else "%/Music/BiliAudio/%")
    } else null
    val selection = condition

    val out = ArrayList<Track>()
    resolver.query(
      MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, cols, selection, args,
      "${MediaStore.Audio.Media.ARTIST} COLLATE NOCASE, ${MediaStore.Audio.Media.ALBUM} COLLATE NOCASE, ${MediaStore.Audio.Media.TRACK}",
    )?.use { c ->
      val iId = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
      val iTitle = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
      val iArtist = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
      val iAlbum = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
      val iDur = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
      val iTrack = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TRACK)
      val iAlbumId = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
      val iMime = c.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)
      val iPath = c.getColumnIndex(if (Build.VERSION.SDK_INT >= 29) MediaStore.Audio.Media.RELATIVE_PATH else MediaStore.Audio.Media.DATA)
      while (c.moveToNext()) {
        val id = c.getLong(iId)
        val path = if (iPath >= 0) c.getString(iPath).orEmpty() else ""
        out += Track(
          uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id),
          title = c.getString(iTitle)?.takeIf { it.isNotBlank() } ?: "未知曲目",
          artist = c.getString(iArtist)?.takeIf { it.isNotBlank() && it != "<unknown>" } ?: "未知歌手",
          album = c.getString(iAlbum)?.takeIf { it.isNotBlank() && it != "<unknown>" } ?: "未知专辑",
          durationMs = c.getLong(iDur),
          trackNo = c.getInt(iTrack) % 1000,
          albumId = c.getLong(iAlbumId),
          source = TrackSource.MEDIA_STORE,
          relativePath = path,
          mime = c.getString(iMime).orEmpty(),
        )
      }
    }
    return out
  }

  fun scanTree(context: Context, treeUri: Uri): List<Track> {
    val root = DocumentFile.fromTreeUri(context, treeUri) ?: return emptyList()
    val out = ArrayList<Track>()
    walk(context, root, out, 0)
    out.sortWith(compareBy({ it.artist.lowercase() }, { it.album.lowercase() }, { it.trackNo }))
    return out
  }

  fun folderName(context: Context, treeUri: Uri): String {
    val name = runCatching { DocumentFile.fromTreeUri(context, treeUri)?.name }.getOrNull()
    return name?.takeIf { it.isNotBlank() }
      ?: treeUri.lastPathSegment?.substringAfterLast(':')?.takeIf { it.isNotBlank() }
      ?: "文件夹"
  }

  private fun walk(context: Context, dir: DocumentFile, out: MutableList<Track>, depth: Int) {
    if (depth > 12) return
    for (doc in dir.listFiles()) {
      if (doc.isDirectory) {
        walk(context, doc, out, depth + 1)
        continue
      }
      val name = doc.name ?: continue
      val ext = name.substringAfterLast('.', "").lowercase()
      if (ext !in audioExt) continue
      out += trackFromDocument(context, doc, name)
    }
  }

  private fun trackFromDocument(context: Context, doc: DocumentFile, name: String): Track {
    var title = name.substringBeforeLast('.')
    var artist = "未知歌手"
    var album = "未知专辑"
    var duration = 0L
    var trackNo = 0
    runCatching {
      val r = MediaMetadataRetriever()
      try {
        r.setDataSource(context, doc.uri)
        r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() }?.let { title = it }
        r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)?.takeIf { it.isNotBlank() }?.let { artist = it }
        r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)?.takeIf { it.isNotBlank() }?.let { album = it }
        duration = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        trackNo = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)
          ?.substringBefore('/')?.toIntOrNull() ?: 0
      } finally {
        r.release()
      }
    }
    return Track(
      uri = doc.uri,
      title = title,
      artist = artist,
      album = album,
      durationMs = duration,
      trackNo = trackNo,
      source = TrackSource.FOLDER,
      relativePath = (doc.uri.path ?: "").substringBeforeLast('/'),
    )
  }

  fun audioSpec(context: Context, track: Track): AudioSpec {
    if (track.mime.contains("flac", true) || track.uri.toString().endsWith(".flac", true)) {
      runCatching {
        context.contentResolver.openInputStream(track.uri)?.use { input ->
          AudioSpec.flac(input)?.let { return it }
        }
      }
    }
    return AudioSpec.extractor(context, track.uri)
  }

  fun cover(context: Context, track: Track): ByteArray? {
    runCatching {
      val r = MediaMetadataRetriever()
      try {
        r.setDataSource(context, track.uri)
        r.embeddedPicture?.takeIf { it.isNotEmpty() }?.let { return it }
      } finally {
        r.release()
      }
    }
    // 文件夹里的 cover.jpg
    runCatching {
      if (track.source == TrackSource.FOLDER) {
        val parent = DocumentFile.fromSingleUri(context, track.uri)?.parentFile ?: return null
        for (n in listOf("cover.jpg", "cover.jpeg", "folder.jpg", "front.jpg")) {
          parent.findFile(n)?.let { f ->
            context.contentResolver.openInputStream(f.uri)?.use { return it.readBytes() }
          }
        }
      }
    }
    return null
  }

  fun folderName(track: Track): String =
    track.relativePath.trimEnd('/').substringAfterLast('/').ifBlank { "音乐" }

  @Suppress("unused")
  private fun isAudioFile(file: File): Boolean = file.extension.lowercase() in audioExt
}

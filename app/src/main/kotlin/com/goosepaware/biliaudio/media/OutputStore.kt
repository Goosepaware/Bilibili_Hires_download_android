package com.goosepaware.biliaudio.media

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import java.io.File

data class SavedFile(val uri: String, val name: String, val displayPath: String)

/**
 * 输出发布：默认写入系统媒体库 Music/BiliAudio/<专辑>/；
 * 若用户用 SAF 选了目录，则写进该目录（持久化授权）。
 */
object OutputStore {

  const val ROOT = "BiliAudio"

  fun publish(context: Context, file: File, ext: String, subDir: String, treeUri: String): SavedFile {
    val mime = Formats.mime(ext)
    val cleanName = file.name
    return if (treeUri.isNotBlank()) {
      publishSaf(context, file, mime, subDir, treeUri, cleanName)
    } else {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        publishMediaStore(context, file, mime, subDir, cleanName)
      } else {
        publishLegacy(context, file, subDir, cleanName)
      }
    }
  }

  private fun mediaRelative(subDir: String): String {
    val dir = subDir.trim().trim('/')
    return if (dir.isEmpty()) "Music/$ROOT" else "Music/$ROOT/$dir"
  }

  private fun uniqueDisplayName(context: Context, relativePath: String, name: String): String {
    val resolver = context.contentResolver
    var candidate = name
    val dot = name.lastIndexOf('.')
    val base = if (dot > 0) name.substring(0, dot) else name
    val suffix = if (dot > 0) name.substring(dot) else ""
    var i = 1
    while (i < 1000) {
      val exists = resolver.query(
        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
        arrayOf(MediaStore.MediaColumns._ID),
        "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?",
        arrayOf(candidate, "$relativePath/"),
        null,
      )?.use { it.count > 0 } ?: false
      if (!exists) return candidate
      candidate = "$base ($i)$suffix"
      i++
    }
    return "$base (${System.currentTimeMillis()})$suffix"
  }

  private fun publishMediaStore(context: Context, file: File, mime: String, subDir: String, name: String): SavedFile {
    val resolver = context.contentResolver
    val relative = mediaRelative(subDir)
    val finalName = uniqueDisplayName(context, relative, name)
    val values = ContentValues().apply {
      put(MediaStore.MediaColumns.DISPLAY_NAME, finalName)
      put(MediaStore.MediaColumns.MIME_TYPE, mime)
      put(MediaStore.MediaColumns.RELATIVE_PATH, relative)
      put(MediaStore.MediaColumns.IS_PENDING, 1)
      put(MediaStore.Audio.Media.IS_MUSIC, 1)
    }
    val uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
      ?: throw IllegalStateException("无法写入媒体库")
    try {
      resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
        ?: throw IllegalStateException("无法打开输出流")
      val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
      resolver.update(uri, done, null, null)
    } catch (e: Throwable) {
      runCatching { resolver.delete(uri, null, null) }
      throw e
    }
    return SavedFile(uri.toString(), finalName, "内部存储/$relative/$finalName")
  }

  private fun publishLegacy(context: Context, file: File, subDir: String, name: String): SavedFile {
    val base = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), ROOT)
    val dir = if (subDir.isBlank()) base else File(base, subDir)
    dir.mkdirs()
    var dest = File(dir, name)
    var i = 1
    val dot = name.lastIndexOf('.')
    val stem = if (dot > 0) name.substring(0, dot) else name
    val ext = if (dot > 0) name.substring(dot) else ""
    while (dest.exists()) {
      dest = File(dir, "$stem ($i)$ext")
      i++
    }
    file.copyTo(dest, overwrite = true)
    MediaScannerConnection.scanFile(context, arrayOf(dest.absolutePath), null, null)
    return SavedFile(Uri.fromFile(dest).toString(), dest.name, dest.absolutePath)
  }

  private fun publishSaf(
    context: Context,
    file: File,
    mime: String,
    subDir: String,
    treeUri: String,
    name: String,
  ): SavedFile {
    val tree = DocumentFile.fromTreeUri(context, Uri.parse(treeUri))
      ?: throw IllegalStateException("输出目录已失效，请重新选择")
    var dir = tree
    if (subDir.isNotBlank()) {
      dir = dir.findFile(subDir) ?: dir.createDirectory(subDir)
      ?: throw IllegalStateException("无法创建专辑目录")
    }
    var candidate = name
    var i = 1
    val dot = name.lastIndexOf('.')
    val stem = if (dot > 0) name.substring(0, dot) else name
    val ext = if (dot > 0) name.substring(dot) else ""
    while (dir.findFile(candidate) != null) {
      candidate = "$stem ($i)$ext"
      i++
    }
    val doc = dir.createFile(mime, candidate) ?: throw IllegalStateException("无法创建文件")
    context.contentResolver.openOutputStream(doc.uri)?.use { out ->
      file.inputStream().use { it.copyTo(out) }
    } ?: throw IllegalStateException("无法写入所选目录")
    val shown = doc.uri.path ?: doc.uri.toString()
    return SavedFile(doc.uri.toString(), candidate, shown)
  }

  fun openFile(context: Context, saved: SavedFile, mime: String) {
    val uri = Uri.parse(saved.uri)
    val intent = Intent(Intent.ACTION_VIEW).apply {
      setDataAndType(uri, mime)
      addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { context.startActivity(intent) }
      .onFailure { openAudioList(context) }
  }

  /** 打开系统的音频列表（多数文件管理器/音乐 App 都支持）。 */
  fun openAudioList(context: Context) {
    val intent = Intent(Intent.ACTION_VIEW).apply {
      setDataAndType(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, "vnd.android.cursor.dir/audio")
      addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { context.startActivity(intent) }
  }

  fun contentUri(saved: SavedFile): Uri = Uri.parse(saved.uri)

  @Suppress("unused")
  private fun itemUri(id: Long): Uri =
    ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
}

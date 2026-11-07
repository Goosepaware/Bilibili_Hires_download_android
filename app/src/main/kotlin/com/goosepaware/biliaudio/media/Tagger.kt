package com.goosepaware.biliaudio.media

import android.util.Log
import java.io.File

/**
 * 标签与封面：统一交给 ffmpeg 写（-c:a copy，不重新编码）。
 * 覆盖 FLAC / M4A / MP3 / WAV，与桌面端 mutagen 的输出等价。
 */
object Tagger {

  suspend fun tag(
    file: File,
    title: String,
    artist: String,
    album: String,
    track: Int,
    total: Int,
    comment: String = "",
    cover: ByteArray? = null,
  ): Boolean {
    val dir = file.parentFile ?: return false
    val ext = file.extension.lowercase()
    val out = File(dir, "${file.nameWithoutExtension}.tagged.$ext")
    var coverSrc: File? = null
    var coverJpg: File? = null

    try {
      val args = mutableListOf(
        "-y", "-hide_banner", "-v", "error",
        "-i", file.absolutePath,
      )

      if (cover != null && cover.isNotEmpty()) {
        val src = File(dir, "cover_${System.nanoTime()}.img")
        src.writeBytes(cover)
        coverSrc = src
        val jpg = File(dir, "cover_${System.nanoTime()}.jpg")
        val conv = Ffmpeg.run(
          "-y", "-hide_banner", "-v", "error",
          "-i", src.absolutePath, "-frames:v", "1", "-q:v", "2", jpg.absolutePath,
        )
        if (conv.success && jpg.length() > 0) {
          coverJpg = jpg
          args += listOf("-i", jpg.absolutePath)
        }
      }

      args += listOf("-map", "0:a", "-map_metadata", "-1", "-c:a", "copy")
      if (coverJpg != null) {
        args += listOf(
          "-map", "1:v", "-c:v", "copy", "-disposition:v", "attached_pic",
          "-metadata:s:v", "title=Cover", "-metadata:s:v", "comment=Cover (front)",
        )
      }

      args += listOf("-metadata", "title=$title")
      if (artist.isNotBlank()) args += listOf("-metadata", "artist=$artist")
      if (album.isNotBlank()) args += listOf("-metadata", "album=$album")
      if (ext == "mp3" || ext == "m4a") {
        args += listOf("-metadata", "track=$track/$total")
      } else {
        args += listOf("-metadata", "track=$track", "-metadata", "tracktotal=$total")
      }
      if (comment.isNotBlank()) args += listOf("-metadata", "comment=$comment")

      when (ext) {
        "flac" -> args += listOf("-f", "flac")
        "m4a" -> args += listOf("-movflags", "+faststart", "-f", "mp4")
        "mp3" -> args += listOf("-id3v2_version", "3", "-f", "mp3")
        "wav" -> args += listOf("-write_id3v2", "1", "-f", "wav")
      }
      args += out.absolutePath

      val r = Ffmpeg.run(*args.toTypedArray())
      if (!r.success || !out.exists() || out.length() == 0L) {
        Log.w("Tagger", "写标签失败 ${file.name}: ${r.output.takeLast(300)}")
        return false
      }
      if (!file.delete()) return false
      if (!out.renameTo(file)) {
        out.copyTo(file, overwrite = true)
        out.delete()
      }
      return true
    } catch (e: Throwable) {
      Log.w("Tagger", "写标签异常 ${file.name}: ${e.message}")
      out.delete()
      return false
    } finally {
      coverSrc?.delete()
      coverJpg?.delete()
    }
  }
}

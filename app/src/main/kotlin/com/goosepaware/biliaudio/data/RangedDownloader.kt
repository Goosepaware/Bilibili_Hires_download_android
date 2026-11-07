package com.goosepaware.biliaudio.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.ceil
import kotlin.math.min

/**
 * 分片并发下载：断点续传、URL 过期自动刷新。
 * 与桌面端 download.py 行为一致（.part / .part.json）。
 */
class RangedDownloader(private val client: OkHttpClient) {

  companion object {
    private const val CHUNK_MIN = 4L * 1024 * 1024
  }

  private fun headerBuilder(): Request.Builder = Request.Builder()
    .header("User-Agent", BiliConst.UA)
    .header("Referer", BiliConst.REFERER)
    .header("Origin", "https://www.bilibili.com")
    .header("Accept", "*/*")
    .header("Accept-Encoding", "identity")

  private data class Probe(val total: Long, val ranged: Boolean)

  private fun probe(url: String): Probe {
    val req = headerBuilder().url(url).header("Range", "bytes=0-0").build()
    client.newCall(req).execute().use { r ->
      when (r.code) {
        206 -> {
          val cr = r.header("Content-Range").orEmpty()
          val total = cr.substringAfterLast('/', "0").toLongOrNull() ?: 0
          return Probe(total, true)
        }
        200 -> return Probe(r.header("Content-Length")?.toLongOrNull() ?: 0, false)
        403, 401 -> throw IllegalStateException("流地址已过期")
        else -> if (!r.isSuccessful) throw IllegalStateException("HTTP ${r.code}") else return Probe(0, false)
      }
    }
  }

  private class Chunk(var start: Long, var end: Long, var pos: Long = start)

  suspend fun download(
    urls: List<String>,
    dest: File,
    threads: Int = 4,
    progress: ((Long, Long, Double) -> Unit)? = null,
    refresh: (suspend () -> List<String>)? = null,
    replace: Boolean = false,
  ): Long = withContext(Dispatchers.IO) {
    dest.parentFile?.mkdirs()
    val stateFile = File(dest.parentFile, dest.name + ".part.json")
    val partFile = File(dest.parentFile, dest.name + ".part")

    if (dest.exists() && !replace && !partFile.exists()) return@withContext dest.length()

    var urlList = urls.toMutableList()
    var probed = try {
      probe(urlList[0])
    } catch (e: Exception) {
      if (refresh == null) throw e
      urlList = (refresh() ?: urlList).toMutableList()
      probe(urlList[0])
    }
    if (probed.total <= 0) throw IllegalStateException("无法获取文件大小")

    val total = probed.total
    var chunks: MutableList<Chunk> = mutableListOf()

    if (partFile.exists() && stateFile.exists()) {
      runCatching {
        val st = JSONObject(stateFile.readText())
        val same = st.optLong("total") == total &&
          st.optString("urls_tail") == urlList[0].split('?')[0].takeLast(60)
        if (same) {
          val arr: JSONArray = st.getJSONArray("chunks")
          chunks = (0 until arr.length()).map { i ->
            val c = arr.getJSONArray(i)
            Chunk(c.getLong(0), c.getLong(1), c.getLong(2))
          }.toMutableList()
        }
      }
    }
    if (chunks.isEmpty()) {
      val n = maxOf(1, min(threads, ceil(total.toDouble() / CHUNK_MIN).toInt()))
      val size = ceil(total.toDouble() / n).toLong()
      chunks = (0 until n).map { i ->
        Chunk(i * size, min(total - 1, (i + 1) * size - 1))
      }.toMutableList()
      RandomAccessFile(partFile, "rw").use { it.setLength(total) }
    }

    val done = AtomicLong(chunks.sumOf { it.pos - it.start })
    val started = System.currentTimeMillis()
    var tail = urlList[0].split('?')[0].takeLast(60)
    val failure = java.util.concurrent.atomic.AtomicReference<Throwable?>(null)

    fun saveState() {
      val arr = JSONArray()
      for (c in chunks) arr.put(JSONArray(listOf(c.start, c.end, c.pos)))
      runCatching {
        stateFile.writeText(
          JSONObject()
            .put("total", total)
            .put("urls_tail", tail)
            .put("chunks", arr)
            .toString()
        )
      }
    }

    coroutineScope {
      val workers = chunks.map { chunk ->
        launch {
          var attempt = 0
          while (chunk.pos <= chunk.end) {
            ensureActive()
            var idx = 0
            var advanced = false
            while (idx < urlList.size) {
              try {
                val req = headerBuilder()
                  .url(urlList[idx])
                  .header("Range", "bytes=${chunk.pos}-${chunk.end}")
                  .build()
                client.newCall(req).execute().use { resp ->
                  if (resp.code == 403 || resp.code == 401) throw IllegalStateException("403")
                  if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
                  val body = resp.body ?: throw IllegalStateException("空响应")
                  RandomAccessFile(partFile, "rw").use { raf ->
                    raf.seek(chunk.pos)
                    body.byteStream().use { input ->
                      val buf = ByteArray(256 * 1024)
                      while (true) {
                        ensureActive()
                        val read = input.read(buf)
                        if (read <= 0) break
                        raf.write(buf, 0, read)
                        chunk.pos += read
                        done.addAndGet(read.toLong())
                        progress?.invoke(
                          done.get(),
                          total,
                          done.get() / maxOf(0.001, (System.currentTimeMillis() - started) / 1000.0),
                        )
                      }
                    }
                  }
                }
                attempt = 0
                advanced = true
                break
              } catch (ce: CancellationException) {
                throw ce
              } catch (e: Throwable) {
                idx++
                if (idx >= urlList.size) {
                  attempt++
                  if (attempt > 4) {
                    failure.set(e)
                    return@launch
                  }
                  if (refresh != null) {
                    runCatching {
                      val fresh = refresh()
                      if (!fresh.isNullOrEmpty()) {
                        urlList = fresh.toMutableList()
                        tail = urlList[0].split('?')[0].takeLast(60)
                        idx = 0
                        attempt = maxOf(0, attempt - 1)
                      }
                    }
                  }
                  delay(1500L * attempt)
                }
              }
            }
            if (!advanced && idx >= urlList.size && chunk.pos <= chunk.end) {
              // 全部地址都失败
              break
            }
            if (failure.get() != null) break
          }
        }
      }

      // 定期落盘续传状态
      val stateJob = launch {
        while (true) {
          delay(3000)
          saveState()
        }
      }
      workers.forEach { it.join() }
      stateJob.cancel()
    }

    saveState()
    val err = failure.get()
    if (err != null) throw IllegalStateException("下载未完成：${err.message}，已保留 .part 供续传")
    if (chunks.any { it.pos <= it.end }) {
      throw IllegalStateException("下载未完成（网络中断或地址失效），已保留 .part 供续传")
    }

    partFile.renameTo(dest)
    stateFile.delete()
    progress?.invoke(total, total, total / maxOf(0.001, (System.currentTimeMillis() - started) / 1000.0))
    total
  }
}

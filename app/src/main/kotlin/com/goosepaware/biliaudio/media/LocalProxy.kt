package com.goosepaware.biliaudio.media

import android.util.Log
import com.goosepaware.biliaudio.data.BiliConst
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 本机 HTTP 代理。
 *
 * ffmpeg-kit 的构建没有 TLS（gnutls），无法直接读 https 的 B 站 CDN。
 * 这里在 127.0.0.1 上起一个极小的转发服务（支持 Range），
 * 让 ffmpeg 用 http 读取，从而保留远程采样/按时间段分轨的能力。
 */
object LocalProxy {

  private val client = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(60, TimeUnit.SECONDS)
    .retryOnConnectionFailure(true)
    .build()

  private val routes = Collections.synchronizedMap(LinkedHashMap<String, String>())
  private val seq = AtomicInteger(0)
  private val pool = Executors.newCachedThreadPool { r ->
    Thread(r, "bili-proxy").apply { isDaemon = true }
  }

  @Volatile private var server: ServerSocket? = null
  @Volatile private var acceptThread: Thread? = null

  private val hopHeaders = setOf(
    "connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
    "te", "trailers", "transfer-encoding", "upgrade", "content-encoding",
  )

  private fun ensureStarted(): Int {
    server?.let { if (!it.isClosed) return it.localPort }
    synchronized(this) {
      server?.let { if (!it.isClosed) return it.localPort }
      val s = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
      server = s
      acceptThread = Thread({
        while (!s.isClosed) {
          val socket = try {
            s.accept()
          } catch (e: Throwable) {
            break
          }
          pool.submit { runCatching { handle(socket) } }
        }
      }, "bili-proxy-accept").apply { isDaemon = true; start() }
      return s.localPort
    }
  }

  /** 把 https 地址换成 http://127.0.0.1:port/p/<token>；本地路径原样返回。 */
  fun localize(url: String?): String {
    if (url.isNullOrBlank()) return ""
    if (!url.startsWith("https://", ignoreCase = true)) return url
    val port = ensureStarted()
    val token = "${seq.incrementAndGet()}-${Integer.toHexString(url.hashCode())}"
    synchronized(routes) {
      routes[token] = url
      if (routes.size > 64) {
        val it = routes.keys.iterator()
        while (routes.size > 64 && it.hasNext()) {
          it.next()
          it.remove()
        }
      }
    }
    return "http://127.0.0.1:$port/p/$token"
  }

  private fun handle(socket: Socket) {
    socket.use { s ->
      s.soTimeout = 60_000
      val input = BufferedInputStream(s.getInputStream())
      val requestLine = readLine(input) ?: return
      val parts = requestLine.split(' ')
      if (parts.size < 2) return
      val method = parts[0].uppercase()
      val path = parts[1]

      var range: String? = null
      while (true) {
        val line = readLine(input) ?: break
        if (line.isEmpty()) break
        val idx = line.indexOf(':')
        if (idx > 0) {
          val name = line.substring(0, idx).trim()
          val value = line.substring(idx + 1).trim()
          if (name.equals("Range", true)) range = value
        }
      }

      val token = path.substringAfterLast('/')
      val remote = synchronized(routes) { routes[token] }
      val out = s.getOutputStream()
      if (remote == null) {
        writeAll(out, "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
        return
      }

      val builder = Request.Builder()
        .url(remote)
        .header("User-Agent", BiliConst.UA)
        .header("Referer", BiliConst.REFERER)
        .header("Origin", "https://www.bilibili.com")
        .header("Accept", "*/*")
        .header("Accept-Encoding", "identity")
      if (range != null) builder.header("Range", range)

      val call = if (method == "HEAD") builder.head().build() else builder.get().build()
      client.newCall(call).execute().use { resp ->
        val head = StringBuilder()
        head.append("HTTP/1.1 ${resp.code} ${resp.message}\r\n")
        resp.headers.forEach { (k, v) ->
          if (k.lowercase() !in hopHeaders) head.append("$k: $v\r\n")
        }
        head.append("Connection: close\r\n\r\n")
        writeAll(out, head.toString())
        if (method != "HEAD") {
          resp.body?.byteStream()?.let { copy(it, out) }
        }
      }
      out.flush()
    }
  }

  private fun readLine(input: InputStream): String? {
    val sb = StringBuilder()
    var prev = -1
    while (true) {
      val b = input.read()
      if (b < 0) return if (sb.isEmpty()) null else sb.toString()
      if (b == '\n'.code) {
        if (prev == '\r'.code && sb.isNotEmpty()) sb.setLength(sb.length - 1)
        return sb.toString()
      }
      sb.append(b.toChar())
      prev = b
      if (sb.length > 8192) return sb.toString()
    }
  }

  private fun writeAll(out: OutputStream, text: String) {
    out.write(text.toByteArray(Charsets.ISO_8859_1))
    out.flush()
  }

  private fun copy(input: InputStream, out: OutputStream) {
    val buf = ByteArray(64 * 1024)
    while (true) {
      val n = input.read(buf)
      if (n <= 0) break
      out.write(buf, 0, n)
      out.flush()
    }
  }

  fun logRoutes() {
    Log.d("LocalProxy", "routes=${routes.size} port=${server?.localPort}")
  }
}

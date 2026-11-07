package com.goosepaware.biliaudio.data

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.delay
import java.net.URI

/**
 * 扫码登录：生成二维码 → 轮询 → 保存 Cookie（与桌面端 auth.py 一致）。
 */
class LoginRepository(private val http: BiliHttp) {

  private fun qrBitmap(content: String, size: Int = 560): Bitmap {
    val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size)
    val pixels = IntArray(size * size)
    for (y in 0 until size) {
      for (x in 0 until size) {
        pixels[y * size + x] = if (matrix.get(x, y)) Color.BLACK else Color.WHITE
      }
    }
    return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
  }

  private fun parseCookieUrl(url: String): Map<String, String> {
    val q = runCatching { URI(url).rawQuery }.getOrNull() ?: return emptyMap()
    return q.split('&').mapNotNull { part ->
      val i = part.indexOf('=')
      if (i <= 0) null else part.substring(0, i) to part.substring(i + 1)
    }.toMap().filterKeys { it in SessionStore.COOKIE_KEYS }
  }

  /**
   * 从粘贴的 Cookie 文本导入（支持 `SESSDATA=...; bili_jct=...` 整段）。
   */
  fun importCookieText(text: String): Boolean {
    val pairs = linkedMapOf<String, String>()

    // Netscape cookies.txt
    for (line in text.lineSequence()) {
      if (line.isBlank() || line.startsWith("#")) continue
      val parts = line.split('\t')
      if (parts.size >= 7 && parts[0].contains("bilibili", ignoreCase = true)) {
        pairs[parts[5].trim()] = parts[6].trim()
      }
    }

    val re = Regex("(DedeUserID__ckMd5|DedeUserID|SESSDATA|bili_jct|sid|buvid3|buvid4)=([^;\\s&\"']+)")
    re.findAll(text).forEach { m -> pairs[m.groupValues[1]] = m.groupValues[2] }
    if (pairs["SESSDATA"].isNullOrBlank()) return false
    http.saveCookies(pairs)
    return true
  }

  /**
   * 完整登录流程。onQr 收到二维码图片，onStatus 收到状态文本。
   * 返回登录后的账号信息；用户取消或超时返回 null。
   */
  suspend fun login(
    onQr: (Bitmap) -> Unit,
    onStatus: (String) -> Unit,
    timeoutMs: Long = 600_000,
  ): Account? {
    val deadline = System.currentTimeMillis() + timeoutMs
    http.ensureBuvid()

    while (System.currentTimeMillis() < deadline) {
      val gen = http.getJson(BiliConst.PASSPORT + "/x/passport-login/web/qrcode/generate")
      val d = gen.obj("data") ?: throw BiliException(-1, "二维码生成失败")
      val qrUrl = d.str("url") ?: throw BiliException(-1, "二维码内容为空")
      val key = d.str("qrcode_key") ?: throw BiliException(-1, "二维码 key 为空")
      onQr(qrBitmap(qrUrl))
      onStatus("请用手机 B 站扫描二维码…")

      var scanned = false
      while (System.currentTimeMillis() < deadline) {
        val resp = http.getJson(
          BiliConst.PASSPORT + "/x/passport-login/web/qrcode/poll",
          mapOf("qrcode_key" to key),
        )
        val pd = resp.obj("data")
        when (pd?.int("code") ?: -1) {
          0 -> {
            onStatus("登录成功，正在读取账号…")
            http.saveCookies(parseCookieUrl(pd?.str("url").orEmpty()))
            val account = http.fetchAccount()
            return account ?: throw BiliException(-1, "登录成功但没拿到账号信息")
          }
          86101 -> if (!scanned) {
            scanned = true
            onStatus("请用手机 B 站扫描二维码…")
          }
          86090 -> onStatus("已扫码，请在手机上确认登录")
          86038 -> {
            onStatus("二维码已过期，正在重新生成…")
            break
          }
          else -> onStatus("等待扫码…")
        }
        delay(1200)
      }
    }
    throw BiliException(-1, "登录超时")
  }
}

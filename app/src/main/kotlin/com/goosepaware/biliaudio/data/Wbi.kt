package com.goosepaware.biliaudio.data

import java.net.URLEncoder
import java.security.MessageDigest

/**
 * WBI 签名（playurl 等接口的硬性要求），与桌面端算法一致。
 */
object Wbi {

  private val MIXIN_TAB = intArrayOf(
    46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35, 27, 43, 5, 49,
    33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13, 37, 48, 7, 16, 24, 55, 40,
    61, 26, 17, 0, 1, 60, 51, 30, 4, 22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11,
    36, 20, 34, 44, 52,
  )

  @Volatile private var cachedKeys: Pair<String, String>? = null
  @Volatile private var cachedAt: Long = 0

  fun keys(http: BiliHttp, force: Boolean = false): Pair<String, String> {
    val now = System.currentTimeMillis()
    cachedKeys?.let { if (!force && now - cachedAt < 600_000) return it }
    // 未登录时 nav 返回 code=-101，但 data.wbi_img 仍然可用，所以这里不走 apiGet
    val data = http.getJson(BiliConst.API + "/x/web-interface/nav")
    val wbi = (data.obj("data")?.obj("wbi_img"))
      ?: throw BiliException(data.int("code") ?: -1, data.str("message") ?: "无法获取 WBI 密钥")
    val img = wbi.str("img_url") ?: throw BiliException(-1, "WBI 密钥缺失")
    val sub = wbi.str("sub_url") ?: throw BiliException(-1, "WBI 密钥缺失")
    val pair = img.substringAfterLast('/').substringBefore('.') to sub.substringAfterLast('/').substringBefore('.')
    cachedKeys = pair
    cachedAt = now
    return pair
  }

  private fun mixinKey(img: String, sub: String): String {
    val orig = img + sub
    return MIXIN_TAB.take(32).map { orig[it] }.joinToString("")
  }

  /** 返回带 wts / w_rid 的参数表。 */
  fun sign(http: BiliHttp, params: Map<String, Any?>): LinkedHashMap<String, String> {
    val (img, sub) = keys(http)
    val mixin = mixinKey(img, sub)
    val p = LinkedHashMap<String, String>()
    for ((k, v) in params) if (v != null) p[k] = v.toString()
    p["wts"] = (System.currentTimeMillis() / 1000).toString()
    for (k in p.keys.toList()) p[k] = p[k]!!.filter { it !in "!'()*" }
    val query = p.toSortedMap().entries.joinToString("&") { "${encode(it.key)}=${encode(it.value)}" }
    p["w_rid"] = md5(query + mixin)
    return p
  }

  private fun encode(s: String): String = URLEncoder.encode(s, "UTF-8")

  private fun md5(s: String): String {
    val d = MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.UTF_8))
    return d.joinToString("") { "%02x".format(it) }
  }
}

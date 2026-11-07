package com.goosepaware.biliaudio.data

import android.util.Log
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.doubleOrNull
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

object BiliConst {
  const val API = "https://api.bilibili.com"
  const val PASSPORT = "https://passport.bilibili.com"
  const val REFERER = "https://www.bilibili.com/"
  const val UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
      "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
}

class BiliException(val code: Int, message: String = "") :
  RuntimeException("[$code] ${ERRORS[code] ?: message.ifBlank { "未知错误" }}") {
  companion object {
    val ERRORS = mapOf(
      -101 to "未登录或登录已过期",
      -111 to "csrf 校验失败",
      -400 to "请求参数错误",
      -403 to "访问权限不足（可能需要大会员或登录）",
      -404 to "资源不存在",
      -10403 to "地区限制，当前网络无法访问",
      87007 to "充电专属视频，需要充电后才能观看",
      87008 to "充电专属视频（非充电用户）",
      600200 to "稿件审核中",
      62002 to "稿件不可见",
      62004 to "稿件审核中",
      62012 to "仅 UP 主自己可见",
    )
  }
}

val biliJson = Json { ignoreUnknownKeys = true; isLenient = true; explicitNulls = false }

fun JsonObject.str(key: String): String? =
  (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull

fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull
fun JsonObject.dbl(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull
fun JsonObject.obj(key: String): JsonObject? = (this[key] as? JsonObject)
fun JsonObject.arr(key: String): JsonArray? = (this[key] as? JsonArray)
fun JsonObject.path(vararg keys: String): JsonElement? {
  var cur: JsonElement = this
  for (k in keys) {
    cur = (cur as? JsonObject)?.get(k) ?: return null
  }
  return cur
}

fun JsonElement?.asObj(): JsonObject? = this as? JsonObject
fun JsonElement?.asArr(): JsonArray? = this as? JsonArray

class PersistCookieJar(private val store: SessionStore) : CookieJar {
  override fun loadForRequest(url: HttpUrl): List<Cookie> {
    if (!url.host.endsWith("bilibili.com")) return emptyList()
    val now = System.currentTimeMillis()
    return store.load().mapNotNull { (k, v) ->
      runCatching {
        Cookie.Builder()
          .domain("bilibili.com")
          .path("/")
          .name(k)
          .value(v)
          .expiresAt(now + 30L * 24 * 3600 * 1000)
          .build()
      }.getOrNull()
    }
  }

  override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
    if (cookies.isEmpty()) return
    val m = store.load()
    for (c in cookies) m[c.name] = c.value
    store.save(m)
  }
}

/**
 * B 站 JSON 接口客户端：统一 UA/Referer、Cookie 持久化、退避重试与业务错误码。
 */
class BiliHttp(private val store: SessionStore) {

  val client: OkHttpClient = OkHttpClient.Builder()
    .cookieJar(PersistCookieJar(store))
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(30, TimeUnit.SECONDS)
    .writeTimeout(30, TimeUnit.SECONDS)
    .retryOnConnectionFailure(true)
    .build()

  fun headers(builder: Request.Builder): Request.Builder = builder
    .header("User-Agent", BiliConst.UA)
    .header("Referer", BiliConst.REFERER)
    .header("Origin", "https://www.bilibili.com")
    .header("Accept", "application/json, text/plain, */*")
    .header("Accept-Language", "zh-CN,zh;q=0.9")

  fun newRequest(url: String): Request.Builder = headers(Request.Builder().url(url))

  fun getJson(url: String, params: Map<String, Any?> = emptyMap()): JsonObject {
    val b = url.toHttpUrlOrNull()?.newBuilder() ?: throw BiliException(-1, "非法地址: $url")
    for ((k, v) in params) if (v != null) b.addQueryParameter(k, v.toString())
    val req = headers(Request.Builder().url(b.build())).get().build()
    client.newCall(req).execute().use { resp ->
      if (resp.code == 412) throw BiliException(412, "请求被风控拦截，请降低并发或稍后再试")
      if (!resp.isSuccessful) throw BiliException(-1, "HTTP ${resp.code}")
      val text = resp.body?.string() ?: ""
      return biliJson.parseToJsonElement(text).jsonObject
    }
  }

  fun apiGet(path: String, params: Map<String, Any?> = emptyMap(), retries: Int = 3): JsonObject {
    val url = if (path.startsWith("http")) path else BiliConst.API + path
    var last: Throwable? = null
    repeat(retries) { attempt ->
      try {
        val data = getJson(url, params)
        val code = data.int("code") ?: 0
        if (code != 0) throw BiliException(code, data.str("message").orEmpty())
        return data
      } catch (e: BiliException) {
        throw e
      } catch (e: Throwable) {
        last = e
        Thread.sleep((800L * (attempt + 1)) + (Math.random() * 400).toLong())
      }
    }
    throw BiliException(-1, "网络请求失败: ${last?.message}")
  }

  /** 补 buvid3/4，降低被风控概率。 */
  fun ensureBuvid() {    if (!store.load()["buvid3"].isNullOrBlank()) return
    runCatching {
      val d = apiGet("/x/frontend/finger/spi").obj("data") ?: return
      val m = store.load()
      d.str("b_3")?.let { m["buvid3"] = it }
      d.str("b_4")?.let { m["buvid4"] = it }
      store.save(m)
    }.onFailure { Log.w("BiliHttp", "buvid 获取失败: ${it.message}") }
  }

  fun saveCookies(m: Map<String, String>) {
    if (m.isEmpty()) return
    val cur = store.load()
    cur.putAll(m)
    store.save(cur)
  }

  fun fetchAccount(): Account? {
    if (store.sessData().isNullOrBlank()) return null
    return runCatching {
      val d = apiGet("/x/web-interface/nav").obj("data") ?: return null
      if (d["isLogin"]?.toString() != "true") return null
      Account(
        mid = d.long("mid") ?: 0,
        name = d.str("uname").orEmpty(),
        vip = d.int("vipStatus") == 1,
        level = d.obj("level_info")?.int("current_level") ?: 0,
        vipDue = d.long("vipDueDate")?.let { formatDate(it) },
      )
    }.getOrNull()
  }

  private fun formatDate(ms: Long): String {
    val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.CHINA)
    return sdf.format(java.util.Date(ms))
  }
}

data class Account(val mid: Long, val name: String, val vip: Boolean, val level: Int, val vipDue: String?)

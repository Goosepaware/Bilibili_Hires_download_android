package com.goosepaware.biliaudio.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * 登录 Cookie 存储。优先用 EncryptedSharedPreferences（等价于桌面端 DPAPI），
 * 个别机型 Keystore 异常时回退到普通 prefs，保证能登录。
 */
class SessionStore(context: Context) {

  private val prefs: SharedPreferences = runCatching {
    val key = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
    EncryptedSharedPreferences.create(
      context,
      "bili_session",
      key,
      EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
      EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )
  }.getOrElse { context.getSharedPreferences("bili_session_plain", Context.MODE_PRIVATE) }

  companion object {
    val COOKIE_KEYS = setOf(
      "SESSDATA", "bili_jct", "DedeUserID", "DedeUserID__ckMd5", "sid", "buvid3", "buvid4",
    )
    private const val KEY_COOKIES = "cookies"
  }

  @Synchronized
  fun load(): MutableMap<String, String> {
    val raw = prefs.getString(KEY_COOKIES, null) ?: return mutableMapOf()
    return raw.split('\n').mapNotNull { line ->
      val i = line.indexOf('=')
      if (i <= 0) null else line.substring(0, i) to line.substring(i + 1)
    }.toMap().toMutableMap()
  }

  @Synchronized
  fun save(cookies: Map<String, String>) {
    val filtered = cookies.filterKeys { it in COOKIE_KEYS || it.startsWith("buvid") }
    prefs.edit().putString(KEY_COOKIES, filtered.entries.joinToString("\n") { "${it.key}=${it.value}" }).apply()
  }

  @Synchronized
  fun put(key: String, value: String) {
    val m = load()
    m[key] = value
    save(m)
  }

  @Synchronized
  fun clear() {
    prefs.edit().remove(KEY_COOKIES).apply()
  }

  fun sessData(): String? = load()["SESSDATA"]
}

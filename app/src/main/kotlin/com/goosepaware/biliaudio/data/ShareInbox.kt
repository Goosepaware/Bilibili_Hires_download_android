package com.goosepaware.biliaudio.data

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 系统分享 / 外部跳转进来的地址。
 */
object ShareInbox {
  val pending = MutableStateFlow<String?>(null)
  @Volatile var last: String? = null
    private set

  fun submit(raw: String?) {
    val link = LinkExtractor.extract(raw) ?: return
    last = link
    pending.value = link
  }

  fun consume(): String? = pending.value.also { pending.value = null }
}

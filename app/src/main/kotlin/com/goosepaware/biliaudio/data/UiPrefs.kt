package com.goosepaware.biliaudio.data

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 全局界面开关，供各个页面即时响应「更多 → 设置」里的改动。
 * 持久化仍然由 SettingsStore 负责。
 */
object UiPrefs {
  val showLog = MutableStateFlow(false)
  val waveformAacProxy = MutableStateFlow(false)
}

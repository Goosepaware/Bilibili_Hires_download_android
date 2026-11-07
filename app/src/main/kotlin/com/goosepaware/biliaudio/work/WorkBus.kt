package com.goosepaware.biliaudio.work

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** 全局任务状态：UI 与前台通知都从这里读。 */
object WorkBus {

  data class Progress(
    val title: String = "",
    val stage: String = "",
    val done: Long = 0,
    val total: Long = 0,
    val speed: Double = 0.0,
    val eta: Double = 0.0,
    val index: Int = 1,
    val count: Int = 1,
  )

  private val _progress = MutableStateFlow<Progress?>(null)
  val progress: StateFlow<Progress?> = _progress

  private val _logs = MutableStateFlow<List<String>>(emptyList())
  val logs: StateFlow<List<String>> = _logs

  private val _running = MutableStateFlow(false)
  val running: StateFlow<Boolean> = _running

  fun start(title: String, clearLogs: Boolean = true) {
    if (clearLogs) _logs.value = emptyList()
    _progress.value = Progress(title = title, stage = "准备中…")
    _running.value = true
  }

  fun update(transform: (Progress) -> Progress) {
    _progress.value = _progress.value?.let(transform) ?: Progress()
  }

  fun setProgress(p: Progress?) {
    _progress.value = p
  }

  fun log(line: String) {
    val cur = _logs.value
    _logs.value = (cur + line).takeLast(300)
  }

  fun clearLogs() {
    _logs.value = emptyList()
  }

  fun finish() {
    _running.value = false
    _progress.value = null
  }
}

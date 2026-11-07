package com.goosepaware.biliaudio.work

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.goosepaware.biliaudio.MainActivity
import com.goosepaware.biliaudio.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * 前台服务：保证下载 / 转码 / 分轨在切到后台后继续跑，并显示进度通知。
 */
class DownloadService : Service() {

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
  private var watcher: Job? = null

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onCreate() {
    super.onCreate()
    val nm = getSystemService(NotificationManager::class.java)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val ch = NotificationChannel(CHANNEL, "下载任务", NotificationManager.IMPORTANCE_LOW)
      ch.description = "下载、转码与分轨进度"
      nm.createNotificationChannel(ch)
    }
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    val initial = buildNotification("准备中…", null)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      startForeground(ID, initial, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    } else {
      startForeground(ID, initial)
    }

    watcher?.cancel()
    watcher = scope.launch {
      combine(WorkBus.progress, WorkBus.running) { p, r -> p to r }.collect { (p, running) ->
        if (!running) {
          stopForeground(true)
          stopSelf()
          return@collect
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(ID, buildNotification(p?.stage ?: "处理中…", p))
      }
    }
    return START_NOT_STICKY
  }

  private fun buildNotification(stage: String, p: WorkBus.Progress?): Notification {
    val intent = Intent(this, MainActivity::class.java)
    val pi = PendingIntent.getActivity(
      this, 0, intent,
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    val builder = NotificationCompat.Builder(this, CHANNEL)
      .setSmallIcon(android.R.drawable.stat_sys_download)
      .setContentTitle(getString(R.string.app_name))
      .setContentText(stage)
      .setOngoing(true)
      .setOnlyAlertOnce(true)
      .setContentIntent(pi)

    if (p != null && p.total > 0) {
      val pct = ((p.done.toDouble() / p.total) * 100).toInt().coerceIn(0, 100)
      builder.setProgress(100, pct, false)
      val speed = "%.2f MB/s".format(p.speed / 1048576)
      builder.setSubText("$pct% · $speed")
    } else if (p != null) {
      builder.setProgress(0, 0, true)
    }
    return builder.build()
  }

  override fun onDestroy() {
    watcher?.cancel()
    super.onDestroy()
  }

  companion object {
    private const val CHANNEL = "bili_download"
    private const val ID = 1001

    fun start(context: Context) {
      runCatching {
        ContextCompat.startForegroundService(context, Intent(context, DownloadService::class.java))
      }
    }

    fun stop(context: Context) {
      runCatching { context.stopService(Intent(context, DownloadService::class.java)) }
    }
  }
}

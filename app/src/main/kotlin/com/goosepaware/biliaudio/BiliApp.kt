package com.goosepaware.biliaudio

import android.app.Application
import com.goosepaware.biliaudio.data.BiliApi
import com.goosepaware.biliaudio.data.BiliHttp
import com.goosepaware.biliaudio.data.LoginRepository
import com.goosepaware.biliaudio.data.RangedDownloader
import com.goosepaware.biliaudio.data.SessionStore
import com.goosepaware.biliaudio.data.SettingsStore
import com.goosepaware.biliaudio.work.DownloadPipeline
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class BiliApp : Application() {

  lateinit var sessionStore: SessionStore
  lateinit var http: BiliHttp
  lateinit var api: BiliApi
  lateinit var downloader: RangedDownloader
  lateinit var settingsStore: SettingsStore
  lateinit var loginRepository: LoginRepository
  lateinit var pipeline: DownloadPipeline

  val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  override fun onCreate() {
    super.onCreate()
    sessionStore = SessionStore(this)
    http = BiliHttp(sessionStore)
    api = BiliApi(http)
    downloader = RangedDownloader(http.client)
    settingsStore = SettingsStore(this)
    loginRepository = LoginRepository(http)
    pipeline = DownloadPipeline(this, http, api, downloader)
    scope.launch { runCatching { http.ensureBuvid() } }
  }

  companion object {
    fun of(context: android.content.Context): BiliApp =
      context.applicationContext as BiliApp
  }
}

package com.goosepaware.biliaudio.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.settingsDataStore by preferencesDataStore("bili_settings")

data class Settings(
  val fmt: String = "flac",
  val preset: String = "medium",
  val atmos: Boolean = false,
  val aacFallback: Boolean = true,
  val verify: Boolean = true,
  val tag: Boolean = true,
  val pages: String = "all",
  val outputTreeUri: String = "",
  val showLog: Boolean = false,
  val waveformAacProxy: Boolean = false,
)

class SettingsStore(private val context: Context) {

  private object Keys {
    val fmt = stringPreferencesKey("fmt")
    val preset = stringPreferencesKey("preset")
    val atmos = booleanPreferencesKey("atmos")
    val aacFallback = booleanPreferencesKey("aac_fallback")
    val verify = booleanPreferencesKey("verify")
    val tag = booleanPreferencesKey("tag")
    val pages = stringPreferencesKey("pages")
    val outputTree = stringPreferencesKey("output_tree")
    val showLog = booleanPreferencesKey("show_log")
    val waveformAacProxy = booleanPreferencesKey("waveform_aac_proxy")
  }

  suspend fun load(): Settings {
    val p: Preferences = context.settingsDataStore.data.first()
    val d = Settings()
    return Settings(
      fmt = p[Keys.fmt] ?: d.fmt,
      preset = p[Keys.preset] ?: d.preset,
      atmos = p[Keys.atmos] ?: d.atmos,
      aacFallback = p[Keys.aacFallback] ?: d.aacFallback,
      verify = p[Keys.verify] ?: d.verify,
      tag = p[Keys.tag] ?: d.tag,
      pages = p[Keys.pages] ?: d.pages,
      outputTreeUri = p[Keys.outputTree] ?: d.outputTreeUri,
      showLog = p[Keys.showLog] ?: d.showLog,
      waveformAacProxy = p[Keys.waveformAacProxy] ?: d.waveformAacProxy,
    )
  }

  suspend fun save(s: Settings) {
    context.settingsDataStore.edit { p ->
      p[Keys.fmt] = s.fmt
      p[Keys.preset] = s.preset
      p[Keys.atmos] = s.atmos
      p[Keys.aacFallback] = s.aacFallback
      p[Keys.verify] = s.verify
      p[Keys.tag] = s.tag
      p[Keys.pages] = s.pages
      p[Keys.outputTree] = s.outputTreeUri
      p[Keys.showLog] = s.showLog
      p[Keys.waveformAacProxy] = s.waveformAacProxy
    }
  }
}

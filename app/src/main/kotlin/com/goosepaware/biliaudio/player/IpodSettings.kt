package com.goosepaware.biliaudio.player

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.ipodDataStore by preferencesDataStore("ipod_settings_v2")

data class IpodSettings(
  val mode: String = "bezel",          // bezel / full
  val backlightTimer: Int = -1,        // -1 始终打开，0 关闭，1/2/3/5 秒
  val lcdGhosting: Boolean = true,
  val lcdFadeMs: Int = 460,
  val lcdGhostStrength: Int = 15,      // 0–100
  val eqEnabled: Boolean = false,
  val eqGains: List<Float> = List(EqProcessor.BAND_COUNT) { 0f },
  val eqPreset: String = "平直",
  val customPresets: Map<String, List<Float>> = emptyMap(),
  val folderUris: List<String> = emptyList(),
)

class IpodSettingsStore(private val context: Context) {

  private object Keys {
    val mode = stringPreferencesKey("mode")
    val backlightTimer = intPreferencesKey("backlight_timer")
    val backlightLegacy = booleanPreferencesKey("backlight")
    val lcdGhosting = booleanPreferencesKey("lcd_ghosting_v2")
    val lcdFadeMs = intPreferencesKey("lcd_fade_ms_v3")
    val lcdGhostStrength = intPreferencesKey("lcd_ghost_strength_v2")
    val eqEnabled = booleanPreferencesKey("eq_enabled")
    val eqGains = stringPreferencesKey("eq_gains")
    val eqPreset = stringPreferencesKey("eq_preset")
    val custom = stringPreferencesKey("eq_custom")
    val folder = stringPreferencesKey("folder_uri")
    val folders = stringPreferencesKey("folder_uris")
  }

  private fun encodePresets(map: Map<String, List<Float>>): String =
    map.entries.joinToString(";") { (k, v) -> "$k=${v.joinToString(",")}" }

  private fun decodePresets(raw: String): Map<String, List<Float>> {
    if (raw.isBlank()) return emptyMap()
    return raw.split(';').mapNotNull { part ->
      val i = part.indexOf('=')
      if (i <= 0) return@mapNotNull null
      val name = part.substring(0, i)
      val gains = part.substring(i + 1).split(',').mapNotNull { it.toFloatOrNull() }
      if (gains.size == EqProcessor.BAND_COUNT) name to gains else null
    }.toMap()
  }

  suspend fun load(): IpodSettings {
    val p: Preferences = context.ipodDataStore.data.first()
    val d = IpodSettings()
    val gains = p[Keys.eqGains]?.split(',')?.mapNotNull { it.toFloatOrNull() }
      ?.takeIf { it.size == EqProcessor.BAND_COUNT } ?: d.eqGains
    val custom = p[Keys.custom]?.let { decodePresets(it) } ?: emptyMap()
    val legacyTimer = when (p[Keys.backlightLegacy]) {
      false -> 0
      true -> -1
      null -> d.backlightTimer
    }
    val folders = p[Keys.folders]?.split('\n')?.map { it.trim() }?.filter { it.isNotBlank() }?.distinct()
      ?: p[Keys.folder]?.takeIf { it.isNotBlank() }?.let { listOf(it) }
      ?: d.folderUris
    return IpodSettings(
      mode = p[Keys.mode] ?: d.mode,
      backlightTimer = p[Keys.backlightTimer] ?: legacyTimer,
      lcdGhosting = p[Keys.lcdGhosting] ?: d.lcdGhosting,
      lcdFadeMs = (p[Keys.lcdFadeMs] ?: d.lcdFadeMs).coerceIn(40, 1000),
      lcdGhostStrength = (p[Keys.lcdGhostStrength] ?: d.lcdGhostStrength).coerceIn(0, 100),
      eqEnabled = p[Keys.eqEnabled] ?: d.eqEnabled,
      eqGains = gains,
      eqPreset = p[Keys.eqPreset] ?: d.eqPreset,
      customPresets = custom,
      folderUris = folders,
    )
  }

  suspend fun save(s: IpodSettings) {
    context.ipodDataStore.edit { p ->
      p[Keys.mode] = s.mode
      p[Keys.backlightTimer] = s.backlightTimer
      p[Keys.lcdGhosting] = s.lcdGhosting
      p[Keys.lcdFadeMs] = s.lcdFadeMs.coerceIn(40, 1000)
      p[Keys.lcdGhostStrength] = s.lcdGhostStrength.coerceIn(0, 100)
      p[Keys.eqEnabled] = s.eqEnabled
      p[Keys.eqGains] = s.eqGains.joinToString(",")
      p[Keys.eqPreset] = s.eqPreset
      p[Keys.custom] = encodePresets(s.customPresets)
      p[Keys.folders] = s.folderUris.distinct().joinToString("\n")
      p[Keys.folder] = s.folderUris.firstOrNull().orEmpty()
    }
  }
}

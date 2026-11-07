package com.goosepaware.biliaudio.player

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioOffloadSupport
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * 8 段图形均衡器（biquad peaking），接在 ExoPlayer 的音频链路里。
 * 不依赖系统 Equalizer（那套通常只有 5 段且是全局的）。
 */
class EqProcessor : BaseAudioProcessor() {

  companion object {
    val BANDS = intArrayOf(60, 170, 310, 600, 1000, 3000, 6000, 12000)
    const val BAND_COUNT = 8
    private const val Q = 1.0
  }

  @Volatile private var gains = FloatArray(BAND_COUNT)
  @Volatile private var enabled = false
  @Volatile private var version = 0

  private var appliedVersion = -1
  private var sampleRate = 48000
  private var channelCount = 2
  private var isFloat = false
  private var encoding = C.ENCODING_PCM_16BIT
  private var coeffs = DoubleArray(0)      // BAND_COUNT * 5
  private var state = DoubleArray(0)       // channels * BAND_COUNT * 4

  fun updateGains(newGains: FloatArray, on: Boolean) {
    gains = newGains.copyOf(BAND_COUNT)
    enabled = on
    version++
  }

  override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
    if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT &&
      inputAudioFormat.encoding != C.ENCODING_PCM_24BIT &&
      inputAudioFormat.encoding != C.ENCODING_PCM_32BIT &&
      inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT
    ) {
      return AudioFormat.NOT_SET
    }
    sampleRate = inputAudioFormat.sampleRate
    channelCount = inputAudioFormat.channelCount
    isFloat = inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT
    encoding = inputAudioFormat.encoding
    rebuild()
    return inputAudioFormat
  }

  override fun onFlush() {
    appliedVersion = -1
    rebuild()
  }

  override fun onReset() {
    appliedVersion = -1
    coeffs = DoubleArray(0)
    state = DoubleArray(0)
  }

  private fun rebuild() {
    coeffs = DoubleArray(BAND_COUNT * 5)
    for (b in 0 until BAND_COUNT) {
      val g = if (enabled) gains[b].toDouble() else 0.0
      val a = 10.0.pow(g / 40.0)
      val w0 = 2.0 * PI * BANDS[b] / sampleRate
      val alpha = sin(w0) / (2.0 * Q)
      val cosw = cos(w0)
      val b0 = 1.0 + alpha * a
      val b1 = -2.0 * cosw
      val b2 = 1.0 - alpha * a
      val a0 = 1.0 + alpha / a
      val a1 = -2.0 * cosw
      val a2 = 1.0 - alpha / a
      coeffs[b * 5 + 0] = b0 / a0
      coeffs[b * 5 + 1] = b1 / a0
      coeffs[b * 5 + 2] = b2 / a0
      coeffs[b * 5 + 3] = a1 / a0
      coeffs[b * 5 + 4] = a2 / a0
    }
    state = DoubleArray(channelCount * BAND_COUNT * 4)
    appliedVersion = version
  }

  private inline fun processSample(ch: Int, input: Double): Double {
    if (!enabled) return input
    var x = input
    for (b in 0 until BAND_COUNT) {
      val base = (ch * BAND_COUNT + b) * 4
      val x1 = state[base]
      val x2 = state[base + 1]
      val y1 = state[base + 2]
      val y2 = state[base + 3]
      val b0 = coeffs[b * 5]
      val b1 = coeffs[b * 5 + 1]
      val b2 = coeffs[b * 5 + 2]
      val a1 = coeffs[b * 5 + 3]
      val a2 = coeffs[b * 5 + 4]
      val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
      state[base] = x
      state[base + 1] = x1
      state[base + 2] = y
      state[base + 3] = y1
      x = y
    }
    return x
  }

  override fun queueInput(inputBuffer: ByteBuffer) {
    val size = inputBuffer.remaining()
    if (size == 0) return
    val out = replaceOutputBuffer(size)
    out.put(inputBuffer)
    out.flip()

    if (!enabled) {
      return
    }
    if (appliedVersion != version) rebuild()

    if (isFloat) {
      val fb = out.order(ByteOrder.nativeOrder()).asFloatBuffer()
      val frames = fb.limit() / channelCount
      var i = 0
      for (f in 0 until frames) {
        for (ch in 0 until channelCount) {
          val v = fb.get(i + ch) / 1.0f
          val y = processSample(ch, v.toDouble()).coerceIn(-1.0, 1.0).toFloat()
          fb.put(i + ch, y)
        }
        i += channelCount
      }
    } else if (encoding == C.ENCODING_PCM_32BIT) {
      val ib = out.order(ByteOrder.nativeOrder()).asIntBuffer()
      val frames = ib.limit() / channelCount
      var i = 0
      for (f in 0 until frames) {
        for (ch in 0 until channelCount) {
          val v = ib.get(i + ch) / 2147483648.0
          val y = (processSample(ch, v) * 2147483648.0)
            .coerceIn(-2147483648.0, 2147483647.0).toInt()
          ib.put(i + ch, y)
        }
        i += channelCount
      }
    } else if (encoding == C.ENCODING_PCM_24BIT) {
      val bb = out.order(ByteOrder.nativeOrder())
      val bytesPerFrame = channelCount * 3
      val frames = bb.limit() / bytesPerFrame
      var i = 0
      for (f in 0 until frames) {
        for (ch in 0 until channelCount) {
          val idx = i + ch * 3
          val raw = (bb.get(idx).toInt() and 0xFF) or
            ((bb.get(idx + 1).toInt() and 0xFF) shl 8) or
            (bb.get(idx + 2).toInt() shl 16)
          val v = raw / 8388608.0
          val y = (processSample(ch, v) * 8388608.0)
            .coerceIn(-8388608.0, 8388607.0).toInt()
          bb.put(idx, (y and 0xFF).toByte())
          bb.put(idx + 1, ((y shr 8) and 0xFF).toByte())
          bb.put(idx + 2, ((y shr 16) and 0xFF).toByte())
        }
        i += bytesPerFrame
      }
    } else {
      val sb = out.order(ByteOrder.nativeOrder()).asShortBuffer()
      val frames = sb.limit() / channelCount
      var i = 0
      for (f in 0 until frames) {
        for (ch in 0 until channelCount) {
          val v = sb.get(i + ch) / 32768.0
          val y = (processSample(ch, v) * 32768.0).coerceIn(-32768.0, 32767.0)
          sb.put(i + ch, y.toInt().toShort())
        }
        i += channelCount
      }
    }
  }
}

/** 全局 EQ 状态：UI 改，服务里的 processor 读。 */
object EqState {
  @Volatile private var processor: EqProcessor? = null

  @Volatile var gains: FloatArray = FloatArray(EqProcessor.BAND_COUNT)
    private set

  @Volatile var enabled: Boolean = false
    private set

  @Volatile var presetName: String = "平直"
    private set

  fun attach(p: EqProcessor) {
    processor = p
    p.updateGains(gains, enabled)
  }

  fun set(gains: FloatArray, enabled: Boolean, name: String) {
    this.gains = gains.copyOf(EqProcessor.BAND_COUNT)
    this.enabled = enabled
    presetName = name
    processor?.updateGains(this.gains, enabled)
  }

  fun setEnabled(on: Boolean) = set(gains, on, presetName)
}

data class EqPreset(val name: String, val gains: List<Float>)

object EqPresets {
  val builtin: List<EqPreset> = listOf(
    EqPreset("平直", List(8) { 0f }),
    EqPreset("摇滚", listOf(5f, 4f, 2f, 0f, -1f, 1f, 3f, 4f)),
    EqPreset("流行", listOf(-1f, 0f, 2f, 4f, 3f, 1f, 0f, -1f)),
    EqPreset("爵士", listOf(3f, 2f, 1f, 2f, -1f, -1f, 1f, 2f)),
    EqPreset("古典", listOf(4f, 3f, 2f, 1f, 0f, 1f, 2f, 3f)),
    EqPreset("低音增强", listOf(8f, 7f, 5f, 2f, 0f, 0f, 0f, 0f)),
    EqPreset("人声", listOf(-2f, -1f, 1f, 3f, 5f, 4f, 2f, 0f)),
    EqPreset("电子", listOf(4f, 3f, 0f, -2f, 2f, 3f, 4f, 3f)),
  )
}

class EqRenderersFactory(
  context: Context,
  private val eq: EqProcessor,
) : DefaultRenderersFactory(context) {

  override fun buildAudioSink(
    context: Context,
    enableFloatOutput: Boolean,
    enableAudioTrackPlaybackParams: Boolean,
  ): AudioSink {
    return DefaultAudioSink.Builder(context)
      // 关闭音频 offload，否则 FLAC 会直接走 DSP，均衡器完全被绕过
      .setAudioOffloadSupportProvider { _, _ -> AudioOffloadSupport.DEFAULT_UNSUPPORTED }
      .setAudioProcessorChain(DefaultAudioSink.DefaultAudioProcessorChain(eq))
      // 不使用 float 输出：部分设备 float 直出会跳过 AudioProcessor，均衡器失效
      .setEnableFloatOutput(false)
      .build()
  }
}

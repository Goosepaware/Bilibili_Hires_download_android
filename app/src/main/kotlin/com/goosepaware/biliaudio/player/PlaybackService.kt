package com.goosepaware.biliaudio.player

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.goosepaware.biliaudio.MainActivity

class PlaybackService : MediaSessionService() {

  private var session: MediaSession? = null
  private var eq: EqProcessor? = null

  override fun onCreate() {
    super.onCreate()
    val processor = EqProcessor()
    eq = processor
    EqState.attach(processor)

    val player = ExoPlayer.Builder(this, EqRenderersFactory(this, processor))
      .setAudioAttributes(
        AudioAttributes.Builder()
          .setUsage(C.USAGE_MEDIA)
          .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
          .build(),
        true,
      )
      .setHandleAudioBecomingNoisy(true)
      .setWakeMode(C.WAKE_MODE_LOCAL)
      .build()

    val openApp = PendingIntent.getActivity(
      this, 0,
      Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    session = MediaSession.Builder(this, player)
      .setSessionActivity(openApp)
      .build()
  }

  override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

  override fun onTaskRemoved(rootIntent: Intent?) {
    val player = session?.player
    if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
      stopSelf()
    }
  }

  override fun onDestroy() {
    session?.let {
      it.player.release()
      it.release()
    }
    session = null
    eq = null
    super.onDestroy()
  }
}

package com.example.mmtv.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import com.example.mmtv.api.SessionManager

@OptIn(UnstableApi::class)
class MmtvPlayer(private val context: Context) {

    private var exoPlayer: ExoPlayer? = null
    private val sessionManager = SessionManager(context)

    fun createPlayer(): ExoPlayer {
        val loadErrorHandlingPolicy = DefaultLoadErrorHandlingPolicy(3)
        
        // Pro-inställningar för IPTV (Liknande TiviMate/Perfect Player)
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                3_500,  // Min buffert: 3.5s (Håller anslutningen stabil men rapp)
                15_000, // Max buffert: 15s (Här slutar den ladda för att spara minne)
                1_000,  // Start-buffert: 1s (Kanalen startar nästan direkt)
                2_000   // Efter-buffert: 2s (Om den mot förmodan skulle buffra om)
            )
            .setBackBuffer(0, false)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        val renderersFactory = DefaultRenderersFactory(context).apply {
            setEnableDecoderFallback(true)
            // Vi föredrar hårdvaruavkodning för att undvika microlagg
            setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
        }

        val player = ExoPlayer.Builder(context, renderersFactory)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(context)
                    .setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)
            )
            .setLoadControl(loadControl)
            .setHandleAudioBecomingNoisy(true)
            .setDeviceVolumeControlEnabled(true)
            .build()
        
        player.repeatMode = Player.REPEAT_MODE_OFF // Säkerställ att den aldrig loopar
        
        this.exoPlayer = player
        return player
    }

    fun releasePlayer() {
        exoPlayer?.release()
        exoPlayer = null
    }
}

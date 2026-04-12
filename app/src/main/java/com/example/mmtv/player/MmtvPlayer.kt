package com.example.mmtv.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
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
        
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                30_000, // Min buffer 30s
                60_000, // Max buffer 60s
                2_500,  // Buffer for playback 2.5s
                5_000   // Buffer for playback after rebuffer 5s
            )
            .setBackBuffer(30_000, true) // Support back-seeking without redownload
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        val renderersFactory = DefaultRenderersFactory(context).apply {
            setEnableDecoderFallback(true)
            setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
        }

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()

        val mediaSourceFactory = DefaultMediaSourceFactory(context)
            .setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)

        val player = ExoPlayer.Builder(context, renderersFactory)
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(loadControl)
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .setSeekForwardIncrementMs(60000)
            .setSeekBackIncrementMs(60000)
            .build()
        
        this.exoPlayer = player
        return player
    }

    fun releasePlayer() {
        exoPlayer?.release()
        exoPlayer = null
    }
}

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
        val bufferMs = sessionManager.getBufferSize()
        // Begränsa retries till 2 för att inte hamra servern
        val loadErrorHandlingPolicy = DefaultLoadErrorHandlingPolicy(2)
        
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                bufferMs, // Min buffer to start playback
                bufferMs * 3, // Max buffer
                1000, // Buffer to resume after re-buffer
                1500  // Buffer to resume after user pause
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        val renderersFactory = DefaultRenderersFactory(context).apply {
            if (sessionManager.isTunnelingEnabled()) {
                setEnableDecoderFallback(true)
            }
        }

        val mediaSourceFactory = DefaultMediaSourceFactory(context)
            .setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)

        val player = ExoPlayer.Builder(context, renderersFactory)
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(loadControl)
            .setAudioAttributes(AudioAttributes.DEFAULT, true)
            .setSeekForwardIncrementMs(60000)
            .setSeekBackIncrementMs(60000)
            .build()

        if (sessionManager.isTunnelingEnabled()) {
            player.videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING
        }
        
        this.exoPlayer = player
        return player
    }

    fun releasePlayer() {
        exoPlayer?.release()
        exoPlayer = null
    }
}

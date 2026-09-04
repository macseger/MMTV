package com.example.mmtv.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.extractor.DefaultExtractorsFactory
import com.example.mmtv.api.SessionManager

@OptIn(UnstableApi::class)
class MmtvPlayer(private val context: Context) {

    private var exoPlayer: ExoPlayer? = null
    private val sessionManager = SessionManager(context)

    fun createPlayer(isLive: Boolean): ExoPlayer {
        val loadErrorHandlingPolicy = DefaultLoadErrorHandlingPolicy(3)

        // Live ska starta så fort en spelbar bildruta finns. VOD får hålla mer data
        // för att klara korta nätverksdippar utan avbrott.
        val (minBufferMs, maxBufferMs, startBufferMs, rebufferMs) = if (isLive) {
            intArrayOf(1_000, 3_000, 250, 500)
        } else {
            intArrayOf(5_000, 20_000, 1_500, 3_000)
        }

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                minBufferMs,
                maxBufferMs,
                startBufferMs,
                rebufferMs
            )
            .setBackBuffer(0, false)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        val renderersFactory = DefaultRenderersFactory(context).apply {
            setEnableDecoderFallback(true)
            // Vi föredrar hårdvaruavkodning för att undvika microlagg
            setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
        }

        val trackSelector = DefaultTrackSelector(context)
        if (sessionManager.getUseTunneling()) {
            trackSelector.parameters = trackSelector.buildUponParameters()
                .setTunnelingEnabled(true)
                .build()
        }

        // DataSource factory med User-Agent TiviMate - servrar är ofta extremt optimerade för denna UA
        val dataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("TiviMate/5.1.0 (Linux; Android 11)")
            .setAllowCrossProtocolRedirects(true)

        val player = ExoPlayer.Builder(context, renderersFactory)
            .setTrackSelector(trackSelector)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(context)
                    .setDataSourceFactory(dataSourceFactory)
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

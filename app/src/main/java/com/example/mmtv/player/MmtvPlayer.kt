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

    fun createPlayer(): ExoPlayer {
        val loadErrorHandlingPolicy = DefaultLoadErrorHandlingPolicy(3)
        
        // Pro-inställningar för IPTV (Liknande TiviMate/Perfect Player)
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                2_500,  // Min buffert: 2.5s (Sänkt från 3.5s för rappare respons)
                10_000, // Max buffert: 10s (Håller mindre i minnet för lägre latens)
                500,    // Start-buffert: 0.5s (Kanalen startar blixtsnabbt)
                1_000   // Efter-buffert: 1s (Snabb återhämtning)
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

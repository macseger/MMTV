package com.example.mmtv.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.ChannelMixingAudioProcessor
import androidx.media3.common.audio.ChannelMixingMatrix
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.extractor.DefaultExtractorsFactory
import com.example.mmtv.api.SessionManager
import java.nio.ByteBuffer

@OptIn(UnstableApi::class)
class MmtvPlayer(private val context: Context) {

    private var exoPlayer: ExoPlayer? = null
    private var vodStereoDownmixProcessor: SwitchableStereoDownmixAudioProcessor? = null
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

        val renderersFactory = if (isLive) {
            vodStereoDownmixProcessor = null
            DefaultRenderersFactory(context)
        } else {
            val downmixProcessor = SwitchableStereoDownmixAudioProcessor()
            vodStereoDownmixProcessor = downmixProcessor
            object : DefaultRenderersFactory(context) {
                override fun buildAudioSink(
                    context: Context,
                    enableFloatOutput: Boolean,
                    enableAudioTrackPlaybackParams: Boolean
                ): AudioSink {
                    return DefaultAudioSink.Builder(context)
                        .setAudioProcessors(arrayOf<AudioProcessor>(downmixProcessor))
                        .setEnableFloatOutput(enableFloatOutput)
                        .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                        .build()
                }
            }
        }.apply {
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

    fun enableVodStereoDownmix(inputChannelCount: Int): Boolean {
        return vodStereoDownmixProcessor?.enable(inputChannelCount) == true
    }

    fun disableVodStereoDownmix() {
        vodStereoDownmixProcessor?.disable()
    }

    fun releasePlayer() {
        exoPlayer?.release()
        exoPlayer = null
        vodStereoDownmixProcessor = null
    }
}

@OptIn(UnstableApi::class)
private class SwitchableStereoDownmixAudioProcessor : AudioProcessor {
    private val channelMixer = ChannelMixingAudioProcessor()

    @Volatile
    private var enabledInputChannelCount: Int? = null
    private var active = false

    fun enable(inputChannelCount: Int): Boolean {
        if (inputChannelCount != 6 && inputChannelCount != 8) return false
        enabledInputChannelCount = inputChannelCount
        return true
    }

    fun disable() {
        enabledInputChannelCount = null
    }

    override fun configure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        val matrix = when {
            inputAudioFormat.channelCount != enabledInputChannelCount -> null
            inputAudioFormat.channelCount == 6 -> createSixChannelStereoMatrix()
            inputAudioFormat.channelCount == 8 -> createEightChannelStereoMatrix()
            else -> null
        }
        if (matrix == null) {
            active = false
            channelMixer.reset()
            return AudioProcessor.AudioFormat.NOT_SET
        }

        channelMixer.putChannelMixingMatrix(matrix)
        val outputFormat = channelMixer.configure(inputAudioFormat)
        active = channelMixer.isActive
        return outputFormat
    }

    override fun isActive(): Boolean = active

    override fun queueInput(inputBuffer: ByteBuffer) = channelMixer.queueInput(inputBuffer)

    override fun queueEndOfStream() = channelMixer.queueEndOfStream()

    override fun getOutput(): ByteBuffer = channelMixer.output

    override fun isEnded(): Boolean = channelMixer.isEnded

    override fun flush() = channelMixer.flush()

    override fun reset() {
        active = false
        channelMixer.reset()
    }

    private fun createSixChannelStereoMatrix(): ChannelMixingMatrix {
        val surroundGain = 0.70710677f
        val normalization = 1f / (1f + surroundGain + surroundGain)
        return ChannelMixingMatrix(
            6,
            2,
            floatArrayOf(
                1f, 0f,                         // Front left
                0f, 1f,                         // Front right
                surroundGain, surroundGain,     // Center
                0f, 0f,                         // LFE
                surroundGain, 0f,               // Left surround
                0f, surroundGain                // Right surround
            )
        ).scaleBy(normalization)
    }

    private fun createEightChannelStereoMatrix(): ChannelMixingMatrix {
        val centerGain = 0.70710677f
        val surroundGain = 0.5f
        val normalization = 1f / (1f + centerGain + surroundGain + surroundGain)
        return ChannelMixingMatrix(
            8,
            2,
            floatArrayOf(
                1f, 0f,                         // Front left
                0f, 1f,                         // Front right
                centerGain, centerGain,         // Center
                0f, 0f,                         // LFE
                surroundGain, 0f,               // Back left
                0f, surroundGain,               // Back right
                surroundGain, 0f,               // Side left
                0f, surroundGain                // Side right
            )
        ).scaleBy(normalization)
    }
}

package com.example.mmtv.ui

import org.junit.Assert.*
import org.junit.Test

class PlaybackDetailsTest {
    @Test fun labelsStandardResolutions() {
        assertEquals("4K", videoQualityLabel(3840, 2160))
        assertEquals("Full HD", videoQualityLabel(1920, 1080))
        assertEquals("HD", videoQualityLabel(1280, 720))
        assertEquals("SD", videoQualityLabel(720, 480))
    }

    @Test fun estimatesCadenceDespiteOneDroppedFrame() {
        val estimator = FrameRateEstimator()
        var timestamp = 0L
        estimator.addFrame(timestamp)
        repeat(30) {
            timestamp += if (it == 15) 80_000 else 40_000
            estimator.addFrame(timestamp)
        }
        assertEquals(25f, estimator.framesPerSecond()!!, 0.01f)
    }

    @Test fun waitsForSamplesAndResetsAfterSeek() {
        val estimator = FrameRateEstimator()
        repeat(10) { estimator.addFrame(it * 40_000L) }
        assertNull(estimator.framesPerSecond())
        repeat(20) { estimator.addFrame((it + 10) * 40_000L) }
        assertNotNull(estimator.framesPerSecond())
        estimator.addFrame(0)
        assertNull(estimator.framesPerSecond())
    }
}

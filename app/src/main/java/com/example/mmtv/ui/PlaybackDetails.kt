package com.example.mmtv.ui

internal fun videoQualityLabel(width: Int, height: Int): String = when {
    width > 1920 -> "4K"
    height > 720 -> "Full HD"
    height > 480 -> "HD"
    else -> "SD"
}

// Frame timestamps describe the video cadence independently of playback speed.
// A median ignores occasional dropped frames; seeking resets the sample window.
internal class FrameRateEstimator {
    private var previousUs: Long? = null
    private val intervals = ArrayDeque<Long>()

    @Synchronized
    fun addFrame(timeUs: Long) {
        val previous = previousUs
        previousUs = timeUs
        if (previous == null) return
        val delta = timeUs - previous
        if (delta <= 0 || delta > 250_000) {
            intervals.clear()
            return
        }
        intervals.addLast(delta)
        if (intervals.size > 30) intervals.removeFirst()
    }

    @Synchronized
    fun framesPerSecond(): Float? {
        if (intervals.size < 12) return null
        val sorted = intervals.sorted()
        return 1_000_000f / sorted[sorted.size / 2]
    }
}

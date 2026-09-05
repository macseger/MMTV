package com.example.mmtv.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EpisodeWatchProgressTest {
    @Test fun usesPlayerDurationWhenAvailable() {
        assertEquals(0.5f, episodeWatchProgress(900_000, 1_800_000, "45:00")!!, 0.001f)
    }

    @Test fun supportsPreviouslySavedEpisodes() {
        assertEquals(0.5f, episodeWatchProgress(900_000, 0, "00:30:00")!!, 0.001f)
        assertEquals(0.5f, episodeWatchProgress(900_000, 0, "30:00")!!, 0.001f)
    }

    @Test fun hidesUnstartedAndCompletedEpisodes() {
        assertNull(episodeWatchProgress(0, 1_800_000, null))
        assertNull(episodeWatchProgress(10_000, 1_800_000, null))
        assertNull(episodeWatchProgress(1_795_000, 1_800_000, null))
        assertNull(episodeWatchProgress(1_900_000, 1_800_000, null))
    }

    @Test fun hidesProgressWithoutUsableDuration() {
        for (duration in listOf(null, "", "unknown", "00:00", "-1:30", "12:99")) {
            assertNull(episodeWatchProgress(900_000, 0, duration))
        }
    }
}

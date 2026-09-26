package com.example.mmtv.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class DetailsMediaResolverTest {

    @Test
    fun seriesHistorySelection_usesCanonicalCatalogMedia() {
        val historyItem = MediaSource(
            id = 12868,
            title = "Youth - NC Youth S01E01 Lockjaw",
            icon = "history-icon",
            type = MediaType.SERIES
        )
        val canonical = MediaSource(
            id = 12868,
            title = "NC Youth",
            icon = "catalog-icon",
            type = MediaType.SERIES
        )

        val resolved = resolveCanonicalSeriesDetailsMedia(historyItem, canonical)

        assertSame(canonical, resolved)
        assertEquals("NC Youth", resolved.title)
        assertEquals(12868, resolved.id)
    }

    @Test
    fun movieSelection_remainsUnchanged() {
        val movie = MediaSource(7, "Movie History Title", null, type = MediaType.MOVIE)
        val unrelatedCanonical = MediaSource(7, "Catalog Movie", null, type = MediaType.MOVIE)

        assertSame(movie, resolveCanonicalSeriesDetailsMedia(movie, unrelatedCanonical))
    }

    @Test
    fun mismatchedSeriesCandidate_isNotUsed() {
        val historyItem = MediaSource(10, "Series - Episode", null, type = MediaType.SERIES)
        val otherSeries = MediaSource(11, "Other Series", null, type = MediaType.SERIES)

        assertSame(historyItem, resolveCanonicalSeriesDetailsMedia(historyItem, otherSeries))
    }
}

package com.example.mmtv.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DetailsSeasonNavigationTest {
    @Test
    fun `single season is selected when it has episodes`() {
        assertEquals(
            "1",
            selectNavigableSeasonTarget(
                renderedSeasonKeys = listOf("1"),
                seasonsWithEpisodes = setOf("1"),
                selectedSeason = null
            )
        )
    }

    @Test
    fun `current selected season is kept when navigable`() {
        assertEquals(
            "3",
            selectNavigableSeasonTarget(
                renderedSeasonKeys = listOf("1", "2", "3"),
                seasonsWithEpisodes = setOf("1", "2", "3"),
                selectedSeason = "3"
            )
        )
    }

    @Test
    fun `first navigable rendered season is fallback`() {
        assertEquals(
            "2",
            selectNavigableSeasonTarget(
                renderedSeasonKeys = listOf("1", "2", "3"),
                seasonsWithEpisodes = setOf("2", "3"),
                selectedSeason = "1"
            )
        )
    }

    @Test
    fun `no season target exists without navigable episodes`() {
        assertNull(
            selectNavigableSeasonTarget(
                renderedSeasonKeys = listOf("1", "2"),
                seasonsWithEpisodes = emptySet(),
                selectedSeason = "1"
            )
        )
    }

    @Test
    fun `shortcut visibility requires loaded series with a target`() {
        assertTrue(shouldShowEpisodesShortcut(true, false, "1"))
        assertFalse(shouldShowEpisodesShortcut(true, true, "1"))
        assertFalse(shouldShowEpisodesShortcut(true, false, null))
        assertFalse(shouldShowEpisodesShortcut(false, false, "1"))
    }
}

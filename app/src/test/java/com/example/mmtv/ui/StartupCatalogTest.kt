package com.example.mmtv.ui

import com.example.mmtv.model.GroupedMedia
import com.example.mmtv.model.MediaSource
import com.example.mmtv.model.MediaType
import org.junit.Assert.*
import org.junit.Test

class StartupCatalogTest {
    private val channels = listOf(MediaSource(1, "Channel", "file://picon.png", type = MediaType.LIVE))

    @Test fun failedRefreshKeepsLocalContent() {
        val local = listOf(GroupedMedia("Local", channels, "live"))
        assertSame(local, mergeStartupCategories(local, emptyList()))
    }

    @Test fun refreshPreservesLoadedItemsByIdAcrossCategoryReordering() {
        val local = listOf(GroupedMedia("Old title", channels, "live"))
        val refreshed = listOf(
            GroupedMedia("New category", emptyList(), "new"),
            GroupedMedia("New title", emptyList(), "live")
        )
        val result = mergeStartupCategories(local, refreshed)
        assertEquals(listOf("new", "live"), result.map { it.categoryId })
        assertEquals("New title", result[1].title)
        assertSame(channels, result[1].items)
        assertTrue(result[0].items.isEmpty())
    }

    @Test fun successfulRefreshRemovesCategoriesNoLongerInCatalog() {
        val local = listOf(GroupedMedia("Removed", channels, "old"))
        val refreshed = listOf(GroupedMedia("Current", emptyList(), "new"))
        assertEquals(refreshed, mergeStartupCategories(local, refreshed))
    }
}

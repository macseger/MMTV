package com.example.mmtv.repository

import com.example.mmtv.database.MediaEntity
import com.example.mmtv.model.MediaType
import org.junit.Assert.assertEquals
import org.junit.Test

class ExternalEpgMatchingTest {
    @Test
    fun oneVariantKeyResolvesAllDistinctTargets() {
        val channels = listOf(
            liveChannel(1, "SE: TV4 Fotboll FHD (T2)", "tv4-fotboll-t2.se"),
            liveChannel(2, "SE: TV4 Fotboll FHD", "tv4-fotboll-fhd.se"),
            liveChannel(3, "SE: TV4 Fotboll HD", "tv4-fotboll-hd.se"),
            liveChannel(4, "SE: TV4 Fotboll", null)
        )
        val targetsByVariant = mutableMapOf<String, MutableList<MediaEntity>>()

        channels.forEach { registerExternalEpgTarget(targetsByVariant, "tv4fotboll", it) }

        val resolved = resolveExternalEpgTargets(listOf("tv4fotboll"), targetsByVariant)
        assertEquals(
            listOf("tv4-fotboll-t2.se", "tv4-fotboll-fhd.se", "tv4-fotboll-hd.se", "stream:4"),
            resolved.map { it.externalEpgTargetId() }
        )
    }

    @Test
    fun resolutionKeepsFirstMatchingVariantPriorityAndDeduplicatesEffectiveEpgIds() {
        val exact = liveChannel(1, "Exact", "shared.se")
        val duplicateTarget = liveChannel(2, "Duplicate", "shared.se")
        val lowerPriority = liveChannel(3, "Lower priority", "other.se")
        val targetsByVariant = mutableMapOf<String, MutableList<MediaEntity>>()
        registerExternalEpgTarget(targetsByVariant, "exact", exact)
        registerExternalEpgTarget(targetsByVariant, "exact", exact)
        registerExternalEpgTarget(targetsByVariant, "exact", duplicateTarget)
        registerExternalEpgTarget(targetsByVariant, "normalized", lowerPriority)

        val resolved = resolveExternalEpgTargets(
            listOf("exact", "normalized"),
            targetsByVariant
        )

        assertEquals(listOf(1), resolved.map { it.id })
    }

    private fun liveChannel(id: Int, title: String, epgId: String?) = MediaEntity(
        id = id,
        title = title,
        icon = null,
        type = MediaType.LIVE,
        categoryId = "se",
        categoryName = "Sweden",
        epgId = epgId
    )
}

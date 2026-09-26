package com.example.mmtv.repository

import com.example.mmtv.model.DetailMetadataKey
import com.example.mmtv.model.MediaType
import com.example.mmtv.model.MetadataSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TmdbEnrichmentCoordinatorTest {

    @Test
    fun initialSuccessfulLookup_isNotReplacedByLaterXtreamDetails() {
        val coordinator = TmdbEnrichmentCoordinator(MetadataSource.TMDB)
        val key = DetailMetadataKey(MediaType.MOVIE, 42)
        val initial = requireNotNull(coordinator.begin(key, "The Matrix"))

        assertNull(coordinator.offerRefinedYear(key, "1999-03-31"))
        val completion = coordinator.complete(initial, matched = true)

        assertTrue(completion.shouldPublish)
        assertNull(completion.refinedRequest)
        assertNull(coordinator.offerRefinedYear(key, "1999-03-31"))
    }

    @Test
    fun refinedYearLookup_isCreatedAfterInitialNoMatch() {
        val coordinator = TmdbEnrichmentCoordinator(MetadataSource.TMDB)
        val key = DetailMetadataKey(MediaType.MOVIE, 42)
        val initial = requireNotNull(coordinator.begin(key, "Dune"))

        val completion = coordinator.complete(initial, matched = false)
        assertFalse(completion.shouldPublish)
        assertNull(completion.refinedRequest)

        val refined = coordinator.offerRefinedYear(key, "2021-10-22")
        assertEquals(TmdbLookupKind.REFINED, refined?.kind)
        assertEquals("Dune", refined?.catalogTitle)
        assertEquals(2021, refined?.year)
    }

    @Test
    fun staleMediaCompletion_cannotPublishAfterNavigation() {
        val coordinator = TmdbEnrichmentCoordinator(MetadataSource.TMDB)
        val oldKey = DetailMetadataKey(MediaType.MOVIE, 1)
        val oldRequest = requireNotNull(coordinator.begin(oldKey, "Old Movie"))
        val newKey = DetailMetadataKey(MediaType.MOVIE, 2)
        coordinator.begin(newKey, "New Movie")

        val staleCompletion = coordinator.complete(oldRequest, matched = true)

        assertFalse(staleCompletion.shouldPublish)
        assertNull(staleCompletion.refinedRequest)
        assertFalse(coordinator.isActive(oldKey))
        assertTrue(coordinator.isActive(newKey))
    }

    @Test
    fun providerMode_doesNotStartEnrichment() {
        val coordinator = TmdbEnrichmentCoordinator(MetadataSource.PROVIDER)
        val key = DetailMetadataKey(MediaType.MOVIE, 42)

        assertNull(coordinator.begin(key, "The Matrix"))
        assertFalse(coordinator.isActive(key))
    }

    @Test
    fun tmdbMode_startsExistingEnrichment() {
        val coordinator = TmdbEnrichmentCoordinator(MetadataSource.PROVIDER)
        val key = DetailMetadataKey(MediaType.SERIES, 42)
        coordinator.setMetadataSource(MetadataSource.TMDB)

        val request = coordinator.begin(key, "Youth")

        assertEquals(TmdbLookupKind.INITIAL, request?.kind)
        assertTrue(coordinator.isActive(key))
    }

    @Test
    fun switchingBackToProvider_invalidatesInFlightPublication() {
        val coordinator = TmdbEnrichmentCoordinator(MetadataSource.TMDB)
        val key = DetailMetadataKey(MediaType.MOVIE, 42)
        val request = requireNotNull(coordinator.begin(key, "The Matrix"))

        coordinator.setMetadataSource(MetadataSource.PROVIDER)
        val completion = coordinator.complete(request, matched = true)

        assertFalse(completion.shouldPublish)
        assertFalse(coordinator.isActive(key))
    }
}

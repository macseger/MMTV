package com.example.mmtv.model

import org.junit.Assert.assertEquals
import org.junit.Test

class MetadataSourceTest {

    @Test
    fun missingPreference_defaultsToTmdb() {
        assertEquals(MetadataSource.TMDB, MetadataSource.fromStoredValue(null))
    }

    @Test
    fun persistedTmdbSelection_isRestored() {
        assertEquals(MetadataSource.TMDB, MetadataSource.fromStoredValue("TMDB"))
    }

    @Test
    fun persistedProviderSelection_isRestored() {
        assertEquals(MetadataSource.PROVIDER, MetadataSource.fromStoredValue("PROVIDER"))
    }

    @Test
    fun invalidPreference_fallsBackToTmdb() {
        assertEquals(MetadataSource.TMDB, MetadataSource.fromStoredValue("UNKNOWN"))
    }
}

package com.example.mmtv.model

import org.junit.Assert.assertEquals
import org.junit.Test

class MetadataSourceTest {

    @Test
    fun missingPreference_defaultsToProvider() {
        assertEquals(MetadataSource.PROVIDER, MetadataSource.fromStoredValue(null))
    }

    @Test
    fun persistedTmdbSelection_isRestored() {
        assertEquals(MetadataSource.TMDB, MetadataSource.fromStoredValue("TMDB"))
    }

    @Test
    fun invalidPreference_fallsBackToProvider() {
        assertEquals(MetadataSource.PROVIDER, MetadataSource.fromStoredValue("UNKNOWN"))
    }
}

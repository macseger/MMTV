package com.example.mmtv.ui

import com.example.mmtv.model.MediaType
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CategoryCatalogTest {
    @Test fun fetchesOnlyThreeSectionsOnceInOrder() = runBlocking {
        val requested = mutableListOf<MediaType>()
        val result = loadCategoryCatalog { type ->
            requested.add(type)
            emptyList()
        }
        assertEquals(MediaType.entries.toList(), requested)
        assertEquals(MediaType.entries.toSet(), result.keys)
    }

    @Test fun stopsOnFailureWithoutRetryingOrStartingOtherSections() = runBlocking {
        var requests = 0
        try {
            loadCategoryCatalog {
                requests++
                throw java.io.IOException("Unavailable")
            }
            fail("Expected failure")
        } catch (_: java.io.IOException) { }
        assertEquals(1, requests)
    }

    @Test fun cancelsSlowRequestAndDoesNotRetry() = runBlocking {
        var requests = 0
        var cancelled = false
        try {
            loadCategoryCatalog(timeoutMillis = 100) {
                requests++
                try { awaitCancellation() } finally { cancelled = true }
            }
            fail("Expected timeout")
        } catch (_: TimeoutCancellationException) { }
        assertEquals(1, requests)
        assertTrue(cancelled)
    }
}

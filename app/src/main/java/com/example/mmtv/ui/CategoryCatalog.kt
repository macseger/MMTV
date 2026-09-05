package com.example.mmtv.ui

import android.util.Log
import com.example.mmtv.model.GroupedMedia
import com.example.mmtv.model.MediaType
import kotlinx.coroutines.withTimeoutOrNull

internal suspend fun loadCategoryCatalog(
    timeoutMillis: Long = 45_000,
    fetch: suspend (MediaType) -> List<GroupedMedia>
): Map<MediaType, List<GroupedMedia>> {
    val result = mutableMapOf<MediaType, List<GroupedMedia>>()
    
    withTimeoutOrNull(timeoutMillis) {
        for (type in MediaType.entries) {
            try {
                result[type] = fetch(type)
            } catch (e: Exception) {
                Log.e("CategoryCatalog", "Kunde inte hämta kategorier för $type: ${e.message}")
                result[type] = emptyList()
            }
        }
    }
    
    for (type in MediaType.entries) {
        if (!result.containsKey(type)) {
            result[type] = emptyList()
        }
    }
    
    return result
}

package com.example.mmtv.ui

import com.example.mmtv.model.GroupedMedia
import com.example.mmtv.model.MediaType
import kotlinx.coroutines.withTimeout

// One request at a time, one attempt per section, and a bounded total startup wait.
internal suspend fun loadCategoryCatalog(
    timeoutMillis: Long = 20_000,
    fetch: suspend (MediaType) -> List<GroupedMedia>
): Map<MediaType, List<GroupedMedia>> = withTimeout(timeoutMillis) {
    MediaType.entries.associateWith { type -> fetch(type) }
}

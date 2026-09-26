package com.example.mmtv.repository

import com.example.mmtv.model.DetailMetadataKey
import com.example.mmtv.model.MetadataSource

enum class TmdbLookupKind {
    INITIAL,
    REFINED
}

data class TmdbLookupRequest(
    val key: DetailMetadataKey,
    val catalogTitle: String,
    val year: Int?,
    val kind: TmdbLookupKind
)

data class TmdbLookupCompletion(
    val shouldPublish: Boolean = false,
    val refinedRequest: TmdbLookupRequest? = null
)

class TmdbEnrichmentCoordinator(
    initialMetadataSource: MetadataSource = MetadataSource.PROVIDER
) {
    private data class Session(
        val key: DetailMetadataKey,
        val catalogTitle: String,
        val initialYear: Int?,
        var pendingRefinedYear: Int? = null,
        var initialCompletedWithoutMatch: Boolean = false,
        var refinedStarted: Boolean = false,
        var matchPublished: Boolean = false
    )

    private var session: Session? = null
    private var metadataSource = initialMetadataSource

    fun setMetadataSource(source: MetadataSource) {
        metadataSource = source
        if (source == MetadataSource.PROVIDER) session = null
    }

    fun begin(key: DetailMetadataKey, catalogTitle: String): TmdbLookupRequest? {
        if (metadataSource != MetadataSource.TMDB) return null
        val initialYear = TmdbTitleMatcher.extractYear(catalogTitle)
        session = Session(key, catalogTitle, initialYear)
        return TmdbLookupRequest(key, catalogTitle, initialYear, TmdbLookupKind.INITIAL)
    }

    fun offerRefinedYear(key: DetailMetadataKey, releaseDate: String?): TmdbLookupRequest? {
        val current = session?.takeIf { it.key == key } ?: return null
        val year = TmdbTitleMatcher.extractYear(releaseDate) ?: return null
        if (year == current.initialYear || current.matchPublished || current.refinedStarted) return null

        current.pendingRefinedYear = year
        return if (current.initialCompletedWithoutMatch) createRefinedRequest(current) else null
    }

    fun complete(request: TmdbLookupRequest, matched: Boolean): TmdbLookupCompletion {
        val current = session?.takeIf { it.key == request.key } ?: return TmdbLookupCompletion()

        if (matched) {
            current.matchPublished = true
            return TmdbLookupCompletion(shouldPublish = true)
        }

        if (request.kind == TmdbLookupKind.INITIAL) {
            current.initialCompletedWithoutMatch = true
            return TmdbLookupCompletion(
                refinedRequest = current.pendingRefinedYear?.let { createRefinedRequest(current) }
            )
        }

        return TmdbLookupCompletion()
    }

    fun isActive(key: DetailMetadataKey): Boolean = session?.key == key

    private fun createRefinedRequest(current: Session): TmdbLookupRequest? {
        val year = current.pendingRefinedYear ?: return null
        if (current.refinedStarted || current.matchPublished) return null
        current.refinedStarted = true
        return TmdbLookupRequest(
            key = current.key,
            catalogTitle = current.catalogTitle,
            year = year,
            kind = TmdbLookupKind.REFINED
        )
    }
}

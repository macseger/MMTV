package com.example.mmtv.model

data class DetailMetadata(
    val plot: String? = null,
    val genre: String? = null,
    val releaseDate: String? = null,
    val rating: String? = null
)

data class DetailMetadataKey(
    val mediaType: MediaType,
    val serverMediaId: Int
)

data class KeyedDetailMetadata(
    val key: DetailMetadataKey,
    val metadata: DetailMetadata
)

fun mergeDetailMetadata(
    tmdb: DetailMetadata?,
    xtream: DetailMetadata,
    mediaSource: DetailMetadata
): DetailMetadata = DetailMetadata(
    plot = firstNonBlank(tmdb?.plot, xtream.plot, mediaSource.plot),
    genre = firstNonBlank(tmdb?.genre, xtream.genre, mediaSource.genre),
    releaseDate = firstNonBlank(tmdb?.releaseDate, xtream.releaseDate, mediaSource.releaseDate),
    rating = firstNonBlank(tmdb?.rating, xtream.rating, mediaSource.rating)
)

private fun firstNonBlank(vararg values: String?): String? =
    values.firstOrNull { !it.isNullOrBlank() }?.trim()

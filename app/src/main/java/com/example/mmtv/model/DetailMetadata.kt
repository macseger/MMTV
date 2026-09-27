package com.example.mmtv.model

data class DetailMetadata(
    val plot: String? = null,
    val genre: String? = null,
    val releaseDate: String? = null,
    val rating: String? = null,
    val backdropPath: String? = null,
    val director: String? = null,
    val cast: String? = null
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
    rating = firstNonBlank(tmdb?.rating, xtream.rating, mediaSource.rating),
    backdropPath = firstNonBlank(tmdb?.backdropPath, xtream.backdropPath, mediaSource.backdropPath),
    director = firstNonBlank(tmdb?.director, xtream.director, mediaSource.director),
    cast = firstNonBlank(tmdb?.cast, xtream.cast, mediaSource.cast)
)

private fun firstNonBlank(vararg values: String?): String? =
    values.firstOrNull { !it.isNullOrBlank() }?.trim()

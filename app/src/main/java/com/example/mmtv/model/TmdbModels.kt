package com.example.mmtv.model

import com.google.gson.annotations.SerializedName

data class TmdbMovieSearchResponse(
    val results: List<TmdbMovieSearchResult> = emptyList()
)

data class TmdbMovieSearchResult(
    val id: Int,
    val title: String?,
    @SerializedName("original_title") val originalTitle: String?,
    @SerializedName("release_date") val releaseDate: String?
)

data class TmdbTvSearchResponse(
    val results: List<TmdbTvSearchResult> = emptyList()
)

data class TmdbTvSearchResult(
    val id: Int,
    val name: String?,
    @SerializedName("original_name") val originalName: String?,
    @SerializedName("first_air_date") val firstAirDate: String?
)

data class TmdbGenre(
    val name: String?
)

data class TmdbMovieDetails(
    val overview: String?,
    val genres: List<TmdbGenre> = emptyList(),
    @SerializedName("backdrop_path") val backdropPath: String?,
    @SerializedName("release_date") val releaseDate: String?,
    @SerializedName("vote_average") val voteAverage: Double?,
    @SerializedName("vote_count") val voteCount: Int?
)

data class TmdbTvDetails(
    val overview: String?,
    val genres: List<TmdbGenre> = emptyList(),
    @SerializedName("backdrop_path") val backdropPath: String?,
    @SerializedName("first_air_date") val firstAirDate: String?,
    @SerializedName("vote_average") val voteAverage: Double?,
    @SerializedName("vote_count") val voteCount: Int?
)

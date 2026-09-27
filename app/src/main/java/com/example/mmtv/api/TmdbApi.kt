package com.example.mmtv.api

import com.example.mmtv.model.TmdbMovieDetails
import com.example.mmtv.model.TmdbMovieSearchResponse
import com.example.mmtv.model.TmdbTvDetails
import com.example.mmtv.model.TmdbTvSearchResponse
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

interface TmdbApi {
    @GET("search/movie")
    suspend fun searchMovies(
        @Query("query") query: String,
        @Query("primary_release_year") year: Int? = null,
        @Query("language") language: String = "sv-SE",
        @Query("include_adult") includeAdult: Boolean = false
    ): TmdbMovieSearchResponse

    @GET("search/tv")
    suspend fun searchTv(
        @Query("query") query: String,
        @Query("first_air_date_year") year: Int? = null,
        @Query("language") language: String = "sv-SE",
        @Query("include_adult") includeAdult: Boolean = false
    ): TmdbTvSearchResponse

    @GET("movie/{movie_id}")
    suspend fun getMovieDetails(
        @Path("movie_id") movieId: Int,
        @Query("language") language: String = "sv-SE",
        @Query("append_to_response") appendToResponse: String = "credits"
    ): TmdbMovieDetails

    @GET("tv/{series_id}")
    suspend fun getTvDetails(
        @Path("series_id") seriesId: Int,
        @Query("language") language: String = "sv-SE",
        @Query("append_to_response") appendToResponse: String = "aggregate_credits"
    ): TmdbTvDetails
}

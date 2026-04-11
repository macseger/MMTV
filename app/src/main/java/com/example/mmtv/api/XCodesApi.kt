package com.example.mmtv.api

import com.example.mmtv.model.*
import okhttp3.ResponseBody
import retrofit2.http.GET
import retrofit2.http.Query

interface XCodesApi {
    @GET("player_api.php")
    suspend fun login(
        @Query("username") user: String,
        @Query("password") pass: String
    ): LoginResponse

    @GET("player_api.php")
    suspend fun getLiveCategories(
        @Query("username") user: String,
        @Query("password") pass: String,
        @Query("action") action: String = "get_live_categories"
    ): List<Category>

    @GET("player_api.php")
    suspend fun getLiveStreams(
        @Query("username") user: String,
        @Query("password") pass: String,
        @Query("action") action: String = "get_live_streams"
    ): List<LiveStream>

    @GET("player_api.php")
    suspend fun getMovieCategories(
        @Query("username") user: String,
        @Query("password") pass: String,
        @Query("action") action: String = "get_vod_categories"
    ): List<Category>

    @GET("player_api.php")
    suspend fun getMovies(
        @Query("username") user: String,
        @Query("password") pass: String,
        @Query("action") action: String = "get_vod_streams"
    ): List<Movie>

    @GET("player_api.php")
    suspend fun getSeriesCategories(
        @Query("username") user: String,
        @Query("password") pass: String,
        @Query("action") action: String = "get_series_categories"
    ): List<Category>

    @GET("player_api.php")
    suspend fun getSeries(
        @Query("username") user: String,
        @Query("password") pass: String,
        @Query("action") action: String = "get_series"
    ): List<Series>

    @GET("player_api.php")
    suspend fun getSeriesInfo(
        @Query("username") user: String,
        @Query("password") pass: String,
        @Query("series_id") seriesId: Int,
        @Query("action") action: String = "get_series_info"
    ): SeriesInfoResponse

    @GET("xmltv.php")
    suspend fun getFullEpg(
        @Query("username") user: String,
        @Query("password") pass: String
    ): ResponseBody

    @GET
    suspend fun getExternalEpg(@retrofit2.http.Url url: String): ResponseBody
}

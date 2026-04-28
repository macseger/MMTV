package com.example.mmtv.model

import com.google.gson.annotations.SerializedName

data class LoginResponse(
    @SerializedName("user_info") val userInfo: UserInfo?,
    @SerializedName("server_info") val serverInfo: ServerInfo?
)

data class UserInfo(
    val username: String?,
    val status: String?,
    @SerializedName("exp_date") val expDate: String?,
    @SerializedName("active_cons") val activeCons: String?,
    @SerializedName("max_connections") val maxConnections: String?
)

data class ServerInfo(
    val url: String?,
    val port: String?,
    @SerializedName("https_port") val httpsPort: String?,
    @SerializedName("server_protocol") val serverProtocol: String?
)

data class Category(
    @SerializedName("category_id") val categoryId: String,
    @SerializedName("category_name") val categoryName: String
)

data class LiveStream(
    @SerializedName("num") val num: Int?,
    @SerializedName("name") val name: String?,
    @SerializedName("stream_type") val streamType: String?,
    @SerializedName("stream_id") val streamId: Int,
    @SerializedName("stream_icon") val streamIcon: String?,
    @SerializedName("category_id") val categoryId: String?,
    @SerializedName("epg_channel_id") val epgId: String? = null
)

data class Movie(
    @SerializedName("num") val num: Int?,
    @SerializedName("name") val name: String?,
    @SerializedName("stream_id") val streamId: Int,
    @SerializedName("stream_icon") val streamIcon: String?,
    @SerializedName("category_id") val categoryId: String?,
    @SerializedName("rating") val rating: String?,
    @SerializedName("added") val added: String?,
    @SerializedName("container_extension") val containerExtension: String?
)

data class Series(
    @SerializedName("num") val num: Int?,
    @SerializedName("name") val name: String?,
    @SerializedName("series_id") val seriesId: Int,
    @SerializedName("cover") val cover: String?,
    @SerializedName("plot") val plot: String?,
    @SerializedName("cast") val cast: String?,
    @SerializedName("director") val director: String?,
    @SerializedName("genre") val genre: String?,
    @SerializedName("releaseDate") val releaseDate: String?,
    @SerializedName("last_modified") val lastModified: String?,
    @SerializedName("rating") val rating: String?,
    @SerializedName("category_id") val categoryId: String?
)

data class MovieInfoResponse(
    val info: MovieInfo?,
    @SerializedName("movie_data") val movieData: Movie?
)

data class MovieInfo(
    val plot: String?,
    val genre: String?,
    val cast: String?,
    val director: String?,
    val rating: String?,
    @SerializedName("releasedate") val releaseDate: String?,
    @SerializedName("movie_image") val movieImage: String?,
    val duration: String?
)

// För att hantera säsonger och avsnitt
data class SeriesInfoResponse(
    val info: Series?,
    val seasons: List<Season>?,
    val episodes: Map<String, List<Episode>>?
)

data class Season(
    @SerializedName("season_number") val seasonNumber: Int,
    val name: String?
)

data class Episode(
    val id: String?,
    val title: String?,
    @SerializedName("container_extension") val containerExtension: String?,
    @SerializedName("season") val seasonNumber: Int?,
    val info: EpisodeInfo?
)

data class EpisodeInfo(
    val plot: String?,
    val duration: String?,
    @SerializedName("movie_image") val icon: String?
)

data class EpgResponse(
    @SerializedName("epg_listings") val listings: List<EpgListing>?
)

data class EpgListing(
    val id: String?,
    @SerializedName("epg_id") val epgId: String?,
    val title: String?,
    @SerializedName("dec") val description: String?,
    val start: String?,
    val end: String?,
    @SerializedName("start_timestamp") val startTimestamp: Long?,
    @SerializedName("stop_timestamp") val stopTimestamp: Long?,
    val icon: String? = null
)

data class GroupedMedia(
    val title: String?,
    val items: List<MediaSource>,
    val categoryId: String? = null
)

data class MediaSource(
    val id: Int,
    val title: String?,
    val icon: String?,
    val type: MediaType,
    val extension: String? = null,
    val epgId: String? = null,
    val plot: String? = null,
    val rating: String? = null,
    val director: String? = null,
    val genre: String? = null,
    val cast: String? = null,
    val isFavorite: Boolean = false,
    val favoriteDate: Long = 0L,
    val addedDate: Long = 0L
)

enum class MediaType {
    LIVE, MOVIE, SERIES
}
